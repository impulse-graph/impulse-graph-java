package org.impulsegraph.core.mutation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Off-heap index and B-Tree routing table for {@link ColumnarDeltaBlock} instances.
 * Provides flat {@code NodeDenseID -> BlockID} off-heap mapping and automatic block split orchestration.
 */
public class DeltaBlockIndex implements AutoCloseable {

    public static final int DEFAULT_NODE_CAPACITY = 1_048_576; // 1M nodes

    private final Arena arena;
    private final int defaultBlockCapacity;
    private final int attrBytesPerEdge;
    private final ColumnarDeltaBlock.SortKey sortKey;

    private final List<ColumnarDeltaBlock> blocks = new CopyOnWriteArrayList<>();
    private MemorySegment nodeToBlockSegment;
    private int nodeCapacity;

    public DeltaBlockIndex(Arena arena) {
        this(arena, ColumnarDeltaBlock.DEFAULT_CAPACITY, ColumnarDeltaBlock.DEFAULT_ATTR_BYTES_PER_EDGE, ColumnarDeltaBlock.SortKey.SRC_ID, DEFAULT_NODE_CAPACITY);
    }

    public DeltaBlockIndex(Arena arena, int defaultBlockCapacity, int attrBytesPerEdge, ColumnarDeltaBlock.SortKey sortKey) {
        this(arena, defaultBlockCapacity, attrBytesPerEdge, sortKey, DEFAULT_NODE_CAPACITY);
    }

    public DeltaBlockIndex(Arena arena, int defaultBlockCapacity, int attrBytesPerEdge, ColumnarDeltaBlock.SortKey sortKey, int initialNodeCapacity) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        this.defaultBlockCapacity = defaultBlockCapacity > 0 ? defaultBlockCapacity : ColumnarDeltaBlock.DEFAULT_CAPACITY;
        this.attrBytesPerEdge = Math.max(0, attrBytesPerEdge);
        this.sortKey = Objects.requireNonNull(sortKey, "sortKey must not be null");
        this.nodeCapacity = Math.max(1024, initialNodeCapacity);

        // Allocate flat node-to-block routing segment initialized to -1
        this.nodeToBlockSegment = arena.allocate((long) nodeCapacity * ValueLayout.JAVA_INT.byteSize(), 64);
        for (int i = 0; i < nodeCapacity; i++) {
            nodeToBlockSegment.setAtIndex(ValueLayout.JAVA_INT, i, -1);
        }

        // Initialize with one empty block
        blocks.add(new ColumnarDeltaBlock(arena, this.defaultBlockCapacity, this.attrBytesPerEdge, this.sortKey));
    }

    public Arena arena() {
        return arena;
    }

    public ColumnarDeltaBlock.SortKey sortKey() {
        return sortKey;
    }

    public int blockCount() {
        return blocks.size();
    }

    public List<ColumnarDeltaBlock> getBlocks() {
        return Collections.unmodifiableList(blocks);
    }

    public ColumnarDeltaBlock getBlock(int blockId) {
        if (blockId < 0 || blockId >= blocks.size()) {
            return null;
        }
        return blocks.get(blockId);
    }

    /**
     * Maps a dense node ID to a primary block ID.
     */
    public synchronized void mapNodeToBlock(int nodeId, int blockId) {
        if (nodeId < 0) return;
        ensureNodeCapacity(nodeId + 1);
        nodeToBlockSegment.setAtIndex(ValueLayout.JAVA_INT, nodeId, blockId);
    }

    /**
     * Gets the primary block ID for a dense node ID, or -1 if unmapped.
     */
    public int getBlockIdForNode(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCapacity) {
            return -1;
        }
        return nodeToBlockSegment.getAtIndex(ValueLayout.JAVA_INT, nodeId);
    }

    /**
     * Returns the primary block mapped to a node ID, or null if unmapped.
     */
    public ColumnarDeltaBlock getBlockForNode(int nodeId) {
        int blockId = getBlockIdForNode(nodeId);
        if (blockId >= 0 && blockId < blocks.size()) {
            return blocks.get(blockId);
        }
        return null;
    }

    /**
     * Finds the candidate block for appending an edge with the given primary key.
     */
    public synchronized ColumnarDeltaBlock findBlockForKey(int key) {
        if (blocks.isEmpty()) {
            ColumnarDeltaBlock newBlock = new ColumnarDeltaBlock(arena, defaultBlockCapacity, attrBytesPerEdge, sortKey);
            blocks.add(newBlock);
            return newBlock;
        }

        int mappedBlockId = getBlockIdForNode(key);
        if (mappedBlockId >= 0 && mappedBlockId < blocks.size()) {
            ColumnarDeltaBlock block = blocks.get(mappedBlockId);
            if (!block.isFull()) {
                return block;
            }
        }

        // Search for a block whose key range covers key
        for (ColumnarDeltaBlock b : blocks) {
            if (!b.isFull()) {
                int min = (sortKey == ColumnarDeltaBlock.SortKey.SRC_ID) ? b.minSrcId() : b.minDstId();
                int max = (sortKey == ColumnarDeltaBlock.SortKey.SRC_ID) ? b.maxSrcId() : b.maxDstId();
                if (b.isEmpty() || (min != -1 && key >= min && key <= max)) {
                    return b;
                }
            }
        }

        // Return the last block or first non-full block
        for (int i = blocks.size() - 1; i >= 0; i--) {
            ColumnarDeltaBlock b = blocks.get(i);
            if (!b.isFull()) {
                return b;
            }
        }

        return blocks.get(blocks.size() - 1);
    }

    /**
     * Finds all blocks that may contain edges for a given node ID.
     */
    public List<ColumnarDeltaBlock> findBlocksForNode(int nodeId) {
        List<ColumnarDeltaBlock> result = new ArrayList<>(2);
        int mappedBlockId = getBlockIdForNode(nodeId);
        if (mappedBlockId >= 0 && mappedBlockId < blocks.size()) {
            ColumnarDeltaBlock mapped = blocks.get(mappedBlockId);
            result.add(mapped);
        }

        for (int i = 0; i < blocks.size(); i++) {
            if (i == mappedBlockId) continue;
            ColumnarDeltaBlock b = blocks.get(i);
            if (b.isEmpty()) continue;
            int min = (sortKey == ColumnarDeltaBlock.SortKey.SRC_ID) ? b.minSrcId() : b.minDstId();
            int max = (sortKey == ColumnarDeltaBlock.SortKey.SRC_ID) ? b.maxSrcId() : b.maxDstId();
            if (nodeId >= min && nodeId <= max) {
                result.add(b);
            }
        }
        return result;
    }

    /**
     * Appends an edge to the index, automatically splitting blocks when capacity is reached.
     */
    public synchronized void append(int srcId, int dstId) {
        append(srcId, dstId, 0L);
    }

    /**
     * Appends an edge with an attribute to the index, automatically splitting blocks when capacity is reached.
     */
    public synchronized void append(int srcId, int dstId, long attr) {
        int primaryKey = (sortKey == ColumnarDeltaBlock.SortKey.SRC_ID) ? srcId : dstId;
        ColumnarDeltaBlock targetBlock = findBlockForKey(primaryKey);

        if (targetBlock.isFull()) {
            // Split the block
            ColumnarDeltaBlock[] splits = targetBlock.split(arena);
            int blockIdx = blocks.indexOf(targetBlock);
            if (blockIdx >= 0) {
                blocks.set(blockIdx, splits[0]);
                blocks.add(blockIdx + 1, splits[1]);
            } else {
                blocks.add(splits[0]);
                blocks.add(splits[1]);
            }

            // Decide which split block gets the new edge
            int splitKey = (sortKey == ColumnarDeltaBlock.SortKey.SRC_ID) ? splits[0].maxSrcId() : splits[0].maxDstId();
            if (primaryKey <= splitKey && !splits[0].isFull()) {
                targetBlock = splits[0];
            } else if (!splits[1].isFull()) {
                targetBlock = splits[1];
            } else {
                targetBlock = new ColumnarDeltaBlock(arena, defaultBlockCapacity, attrBytesPerEdge, sortKey);
                blocks.add(targetBlock);
            }

            // Rebuild node-to-block routing for all blocks
            rebuildNodeRouting();
        }

        targetBlock.append(srcId, dstId, attr);
        int targetIdx = blocks.indexOf(targetBlock);
        if (targetIdx >= 0) {
            mapNodeToBlock(primaryKey, targetIdx);
        }
    }

    private void rebuildNodeRouting() {
        for (int bId = 0; bId < blocks.size(); bId++) {
            ColumnarDeltaBlock b = blocks.get(bId);
            if (b.isEmpty()) continue;
            int count = b.count();
            for (int i = 0; i < count; i++) {
                int k = (sortKey == ColumnarDeltaBlock.SortKey.SRC_ID) ? b.getSrcId(i) : b.getDstId(i);
                mapNodeToBlock(k, bId);
            }
        }
    }

    private void ensureNodeCapacity(int requiredCapacity) {
        if (requiredCapacity <= nodeCapacity) return;
        int newCap = Math.max(requiredCapacity, nodeCapacity * 2);
        MemorySegment newSeg = arena.allocate((long) newCap * ValueLayout.JAVA_INT.byteSize(), 64);
        MemorySegment.copy(nodeToBlockSegment, 0, newSeg, 0, (long) nodeCapacity * ValueLayout.JAVA_INT.byteSize());
        for (int i = nodeCapacity; i < newCap; i++) {
            newSeg.setAtIndex(ValueLayout.JAVA_INT, i, -1);
        }
        this.nodeToBlockSegment = newSeg;
        this.nodeCapacity = newCap;
    }

    /**
     * Gets all target IDs for a source ID across all covering delta blocks.
     */
    public int[] getTargets(int srcId) {
        if (sortKey != ColumnarDeltaBlock.SortKey.SRC_ID) {
            throw new IllegalStateException("getTargets() is only supported on SRC_ID sorted index");
        }

        List<ColumnarDeltaBlock> candidateBlocks = findBlocksForNode(srcId);
        if (candidateBlocks.isEmpty()) {
            return new int[0];
        }

        if (candidateBlocks.size() == 1) {
            return candidateBlocks.get(0).getTargets(srcId);
        }

        // Multiple blocks contain this srcId
        int total = 0;
        List<int[]> targetArrays = new ArrayList<>(candidateBlocks.size());
        for (ColumnarDeltaBlock b : candidateBlocks) {
            int[] t = b.getTargets(srcId);
            if (t.length > 0) {
                targetArrays.add(t);
                total += t.length;
            }
        }

        if (total == 0) return new int[0];
        int[] merged = new int[total];
        int offset = 0;
        for (int[] arr : targetArrays) {
            System.arraycopy(arr, 0, merged, offset, arr.length);
            offset += arr.length;
        }
        return merged;
    }

    /**
     * Gets all source IDs for a destination ID across all covering delta blocks.
     */
    public int[] getSources(int dstId) {
        if (sortKey != ColumnarDeltaBlock.SortKey.DST_ID) {
            throw new IllegalStateException("getSources() is only supported on DST_ID sorted index");
        }

        List<ColumnarDeltaBlock> candidateBlocks = findBlocksForNode(dstId);
        if (candidateBlocks.isEmpty()) {
            return new int[0];
        }

        if (candidateBlocks.size() == 1) {
            return candidateBlocks.get(0).getSources(dstId);
        }

        int total = 0;
        List<int[]> sourceArrays = new ArrayList<>(candidateBlocks.size());
        for (ColumnarDeltaBlock b : candidateBlocks) {
            int[] s = b.getSources(dstId);
            if (s.length > 0) {
                sourceArrays.add(s);
                total += s.length;
            }
        }

        if (total == 0) return new int[0];
        int[] merged = new int[total];
        int offset = 0;
        for (int[] arr : sourceArrays) {
            System.arraycopy(arr, 0, merged, offset, arr.length);
            offset += arr.length;
        }
        return merged;
    }

    /**
     * Returns the total degree for a node across all covering blocks.
     */
    public int getDegree(int nodeId) {
        List<ColumnarDeltaBlock> candidateBlocks = findBlocksForNode(nodeId);
        int degree = 0;
        for (ColumnarDeltaBlock b : candidateBlocks) {
            ColumnarDeltaBlock.Bounds bounds = b.findBounds(nodeId);
            degree += bounds.count();
        }
        return degree;
    }

    /**
     * Total number of edge entries across all delta blocks in this index.
     */
    public long totalEdgeCount() {
        long sum = 0;
        for (ColumnarDeltaBlock b : blocks) {
            sum += b.count();
        }
        return sum;
    }

    /**
     * Clears all blocks and resets node-to-block routing.
     */
    public synchronized void clear() {
        blocks.clear();
        for (int i = 0; i < nodeCapacity; i++) {
            nodeToBlockSegment.setAtIndex(ValueLayout.JAVA_INT, i, -1);
        }
        blocks.add(new ColumnarDeltaBlock(arena, defaultBlockCapacity, attrBytesPerEdge, sortKey));
    }

    @Override
    public void close() {
        // Arena lifecycle is managed by caller or parent overlay
    }
}
