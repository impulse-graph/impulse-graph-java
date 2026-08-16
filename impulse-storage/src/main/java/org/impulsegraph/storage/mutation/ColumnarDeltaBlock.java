package org.impulsegraph.storage.mutation;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * Off-heap Struct-of-Arrays (SoA) columnar delta block sized to the CPU L2 cache.
 * Holds uncommitted edge additions and attributes with zero-copy off-heap storage.
 */
public class ColumnarDeltaBlock {

    public enum SortKey {
        SRC_ID,
        DST_ID
    }

    /**
     * Contiguous index bounds [start, end) within the block.
     */
    public record Bounds(int start, int end) {
        public static final Bounds EMPTY = new Bounds(-1, -1);

        public boolean found() {
            return start >= 0 && end > start;
        }

        public int count() {
            return found() ? (end - start) : 0;
        }
    }

    public static final int DEFAULT_L2_CACHE_BYTES = 256 * 1024; // 256 KB default footprint
    public static final int DEFAULT_ATTR_BYTES_PER_EDGE = 8;     // 8 bytes (long/double payload)
    public static final int DEFAULT_CAPACITY = DEFAULT_L2_CACHE_BYTES / (8 + DEFAULT_ATTR_BYTES_PER_EDGE); // 16,384 edges

    private final Arena arena;
    private final int capacity;
    private final int attrBytesPerEdge;
    private final SortKey sortKey;
    private final MemorySegment srcSegment;
    private final MemorySegment dstSegment;
    private final MemorySegment attrSegment;

    private int count = 0;
    private boolean sorted = true;

    private int minSrcId = Integer.MAX_VALUE;
    private int maxSrcId = Integer.MIN_VALUE;
    private int minDstId = Integer.MAX_VALUE;
    private int maxDstId = Integer.MIN_VALUE;

    public ColumnarDeltaBlock(Arena arena) {
        this(arena, DEFAULT_CAPACITY, DEFAULT_ATTR_BYTES_PER_EDGE, SortKey.SRC_ID);
    }

    public ColumnarDeltaBlock(Arena arena, int capacity) {
        this(arena, capacity, DEFAULT_ATTR_BYTES_PER_EDGE, SortKey.SRC_ID);
    }

    public ColumnarDeltaBlock(Arena arena, int capacity, int attrBytesPerEdge) {
        this(arena, capacity, attrBytesPerEdge, SortKey.SRC_ID);
    }

    public ColumnarDeltaBlock(Arena arena, int capacity, int attrBytesPerEdge, SortKey sortKey) {
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
        }
        if (attrBytesPerEdge < 0) {
            throw new IllegalArgumentException("attrBytesPerEdge must be >= 0, got: " + attrBytesPerEdge);
        }
        this.capacity = capacity;
        this.attrBytesPerEdge = attrBytesPerEdge;
        this.sortKey = Objects.requireNonNull(sortKey, "sortKey must not be null");

        this.srcSegment = arena.allocate((long) capacity * ValueLayout.JAVA_INT.byteSize(), 64);
        this.dstSegment = arena.allocate((long) capacity * ValueLayout.JAVA_INT.byteSize(), 64);
        if (attrBytesPerEdge > 0) {
            this.attrSegment = arena.allocate((long) capacity * attrBytesPerEdge, 64);
        } else {
            this.attrSegment = MemorySegment.NULL;
        }
    }

    public Arena arena() {
        return arena;
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    public int attrBytesPerEdge() {
        return attrBytesPerEdge;
    }

    public SortKey sortKey() {
        return sortKey;
    }

    public boolean isFull() {
        return count >= capacity;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean isSorted() {
        return sorted;
    }

    public int minSrcId() {
        return count == 0 ? -1 : minSrcId;
    }

    public int maxSrcId() {
        return count == 0 ? -1 : maxSrcId;
    }

    public int minDstId() {
        return count == 0 ? -1 : minDstId;
    }

    public int maxDstId() {
        return count == 0 ? -1 : maxDstId;
    }

    public MemorySegment srcSegment() {
        return srcSegment;
    }

    public MemorySegment dstSegment() {
        return dstSegment;
    }

    public MemorySegment attrSegment() {
        return attrSegment;
    }

    public int getSrcId(int index) {
        checkIndex(index);
        return srcSegment.getAtIndex(ValueLayout.JAVA_INT, index);
    }

    public int getDstId(int index) {
        checkIndex(index);
        return dstSegment.getAtIndex(ValueLayout.JAVA_INT, index);
    }

    public long getAttrLong(int index) {
        checkIndex(index);
        if (attrBytesPerEdge == 8) {
            return attrSegment.getAtIndex(ValueLayout.JAVA_LONG, index);
        } else if (attrBytesPerEdge == 4) {
            return attrSegment.getAtIndex(ValueLayout.JAVA_INT, index);
        } else if (attrBytesPerEdge > 0) {
            long off = (long) index * attrBytesPerEdge;
            long val = 0;
            for (int b = 0; b < Math.min(8, attrBytesPerEdge); b++) {
                val |= ((long) (attrSegment.get(ValueLayout.JAVA_BYTE, off + b) & 0xFF)) << (b * 8);
            }
            return val;
        }
        return 0L;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for count " + count);
        }
    }

    /**
     * Appends an edge (srcId, dstId) with zero attribute.
     */
    public void append(int srcId, int dstId) {
        append(srcId, dstId, 0L);
    }

    /**
     * Appends an edge (srcId, dstId) with a numeric attribute value.
     */
    public void append(int srcId, int dstId, long attr) {
        if (count >= capacity) {
            throw new IllegalStateException("ColumnarDeltaBlock capacity exceeded: " + capacity);
        }

        srcSegment.setAtIndex(ValueLayout.JAVA_INT, count, srcId);
        dstSegment.setAtIndex(ValueLayout.JAVA_INT, count, dstId);

        if (attrBytesPerEdge == 8) {
            attrSegment.setAtIndex(ValueLayout.JAVA_LONG, count, attr);
        } else if (attrBytesPerEdge == 4) {
            attrSegment.setAtIndex(ValueLayout.JAVA_INT, count, (int) attr);
        } else if (attrBytesPerEdge > 0) {
            long off = (long) count * attrBytesPerEdge;
            for (int b = 0; b < attrBytesPerEdge; b++) {
                attrSegment.set(ValueLayout.JAVA_BYTE, off + b, (byte) (attr >>> (b * 8)));
            }
        }

        if (count > 0 && sorted) {
            int prevIdx = count - 1;
            if (sortKey == SortKey.SRC_ID) {
                int prevSrc = srcSegment.getAtIndex(ValueLayout.JAVA_INT, prevIdx);
                int prevDst = dstSegment.getAtIndex(ValueLayout.JAVA_INT, prevIdx);
                if (srcId < prevSrc || (srcId == prevSrc && dstId < prevDst)) {
                    sorted = false;
                }
            } else {
                int prevDst = dstSegment.getAtIndex(ValueLayout.JAVA_INT, prevIdx);
                int prevSrc = srcSegment.getAtIndex(ValueLayout.JAVA_INT, prevIdx);
                if (dstId < prevDst || (dstId == prevDst && srcId < prevSrc)) {
                    sorted = false;
                }
            }
        }

        if (srcId < minSrcId) minSrcId = srcId;
        if (srcId > maxSrcId) maxSrcId = srcId;
        if (dstId < minDstId) minDstId = dstId;
        if (dstId > maxDstId) maxDstId = dstId;

        count++;
    }

    /**
     * Appends an edge (srcId, dstId) with custom attribute bytes from off-heap memory.
     */
    public void append(int srcId, int dstId, MemorySegment attrSource, long offset) {
        if (count >= capacity) {
            throw new IllegalStateException("ColumnarDeltaBlock capacity exceeded: " + capacity);
        }

        srcSegment.setAtIndex(ValueLayout.JAVA_INT, count, srcId);
        dstSegment.setAtIndex(ValueLayout.JAVA_INT, count, dstId);

        if (attrBytesPerEdge > 0 && attrSource != null && !attrSource.equals(MemorySegment.NULL)) {
            MemorySegment.copy(attrSource, offset, attrSegment, (long) count * attrBytesPerEdge, attrBytesPerEdge);
        }

        if (count > 0 && sorted) {
            int prevIdx = count - 1;
            if (sortKey == SortKey.SRC_ID) {
                int prevSrc = srcSegment.getAtIndex(ValueLayout.JAVA_INT, prevIdx);
                int prevDst = dstSegment.getAtIndex(ValueLayout.JAVA_INT, prevIdx);
                if (srcId < prevSrc || (srcId == prevSrc && dstId < prevDst)) {
                    sorted = false;
                }
            } else {
                int prevDst = dstSegment.getAtIndex(ValueLayout.JAVA_INT, prevIdx);
                int prevSrc = srcSegment.getAtIndex(ValueLayout.JAVA_INT, prevIdx);
                if (dstId < prevDst || (dstId == prevDst && srcId < prevSrc)) {
                    sorted = false;
                }
            }
        }

        if (srcId < minSrcId) minSrcId = srcId;
        if (srcId > maxSrcId) maxSrcId = srcId;
        if (dstId < minDstId) minDstId = dstId;
        if (dstId > maxDstId) maxDstId = dstId;

        count++;
    }

    /**
     * In-place off-heap sort of the Struct-of-Arrays records according to the configured {@link SortKey}.
     */
    public void sort() {
        if (sorted || count <= 1) {
            sorted = true;
            return;
        }

        Integer[] indices = new Integer[count];
        for (int i = 0; i < count; i++) indices[i] = i;
        java.util.Arrays.sort(indices, this::compareIndices);

        int[] tempSrc = new int[count];
        int[] tempDst = new int[count];
        long[] tempAttr8 = (attrBytesPerEdge == 8) ? new long[count] : null;
        int[] tempAttr4 = (attrBytesPerEdge == 4) ? new int[count] : null;
        byte[] tempAttrB = (attrBytesPerEdge > 0 && attrBytesPerEdge != 4 && attrBytesPerEdge != 8) ? new byte[count * attrBytesPerEdge] : null;

        for (int i = 0; i < count; i++) {
            int oldIdx = indices[i];
            tempSrc[i] = srcSegment.getAtIndex(ValueLayout.JAVA_INT, oldIdx);
            tempDst[i] = dstSegment.getAtIndex(ValueLayout.JAVA_INT, oldIdx);
            if (attrBytesPerEdge == 8) {
                tempAttr8[i] = attrSegment.getAtIndex(ValueLayout.JAVA_LONG, oldIdx);
            } else if (attrBytesPerEdge == 4) {
                tempAttr4[i] = attrSegment.getAtIndex(ValueLayout.JAVA_INT, oldIdx);
            } else if (attrBytesPerEdge > 0) {
                MemorySegment.copy(attrSegment, (long) oldIdx * attrBytesPerEdge, MemorySegment.ofArray(tempAttrB), (long) i * attrBytesPerEdge, attrBytesPerEdge);
            }
        }

        MemorySegment.copy(MemorySegment.ofArray(tempSrc), 0, srcSegment, 0, (long) count * 4);
        MemorySegment.copy(MemorySegment.ofArray(tempDst), 0, dstSegment, 0, (long) count * 4);
        
        if (attrBytesPerEdge == 8) {
            MemorySegment.copy(MemorySegment.ofArray(tempAttr8), 0, attrSegment, 0, (long) count * 8);
        } else if (attrBytesPerEdge == 4) {
            MemorySegment.copy(MemorySegment.ofArray(tempAttr4), 0, attrSegment, 0, (long) count * 4);
        } else if (attrBytesPerEdge > 0) {
            MemorySegment.copy(MemorySegment.ofArray(tempAttrB), 0, attrSegment, 0, (long) count * attrBytesPerEdge);
        }

        sorted = true;
        recomputeBounds();
    }

    /**
     * Ensures the block is sorted before range search or split.
     */
    public void ensureSorted() {
        if (!sorted) {
            sort();
        }
    }

    private void recomputeBounds() {
        if (count == 0) {
            minSrcId = Integer.MAX_VALUE;
            maxSrcId = Integer.MIN_VALUE;
            minDstId = Integer.MAX_VALUE;
            maxDstId = Integer.MIN_VALUE;
            return;
        }
        int minS = Integer.MAX_VALUE;
        int maxS = Integer.MIN_VALUE;
        int minD = Integer.MAX_VALUE;
        int maxD = Integer.MIN_VALUE;

        for (int i = 0; i < count; i++) {
            int s = srcSegment.getAtIndex(ValueLayout.JAVA_INT, i);
            int d = dstSegment.getAtIndex(ValueLayout.JAVA_INT, i);
            if (s < minS) minS = s;
            if (s > maxS) maxS = s;
            if (d < minD) minD = d;
            if (d > maxD) maxD = d;
        }
        this.minSrcId = minS;
        this.maxSrcId = maxS;
        this.minDstId = minD;
        this.maxDstId = maxD;
    }

    private int compareIndices(int i, int j) {
        if (sortKey == SortKey.SRC_ID) {
            int s1 = srcSegment.getAtIndex(ValueLayout.JAVA_INT, i);
            int s2 = srcSegment.getAtIndex(ValueLayout.JAVA_INT, j);
            if (s1 != s2) return Integer.compare(s1, s2);
            int d1 = dstSegment.getAtIndex(ValueLayout.JAVA_INT, i);
            int d2 = dstSegment.getAtIndex(ValueLayout.JAVA_INT, j);
            return Integer.compare(d1, d2);
        } else {
            int d1 = dstSegment.getAtIndex(ValueLayout.JAVA_INT, i);
            int d2 = dstSegment.getAtIndex(ValueLayout.JAVA_INT, j);
            if (d1 != d2) return Integer.compare(d1, d2);
            int s1 = srcSegment.getAtIndex(ValueLayout.JAVA_INT, i);
            int s2 = srcSegment.getAtIndex(ValueLayout.JAVA_INT, j);
            return Integer.compare(s1, s2);
        }
    }

    private void swap(int i, int j) {
        if (i == j) return;
        int s = srcSegment.getAtIndex(ValueLayout.JAVA_INT, i);
        srcSegment.setAtIndex(ValueLayout.JAVA_INT, i, srcSegment.getAtIndex(ValueLayout.JAVA_INT, j));
        srcSegment.setAtIndex(ValueLayout.JAVA_INT, j, s);

        int d = dstSegment.getAtIndex(ValueLayout.JAVA_INT, i);
        dstSegment.setAtIndex(ValueLayout.JAVA_INT, i, dstSegment.getAtIndex(ValueLayout.JAVA_INT, j));
        dstSegment.setAtIndex(ValueLayout.JAVA_INT, j, d);

        if (attrBytesPerEdge == 8) {
            long a = attrSegment.getAtIndex(ValueLayout.JAVA_LONG, i);
            attrSegment.setAtIndex(ValueLayout.JAVA_LONG, i, attrSegment.getAtIndex(ValueLayout.JAVA_LONG, j));
            attrSegment.setAtIndex(ValueLayout.JAVA_LONG, j, a);
        } else if (attrBytesPerEdge == 4) {
            int a = attrSegment.getAtIndex(ValueLayout.JAVA_INT, i);
            attrSegment.setAtIndex(ValueLayout.JAVA_INT, i, attrSegment.getAtIndex(ValueLayout.JAVA_INT, j));
            attrSegment.setAtIndex(ValueLayout.JAVA_INT, j, a);
        } else if (attrBytesPerEdge > 0) {
            long offI = (long) i * attrBytesPerEdge;
            long offJ = (long) j * attrBytesPerEdge;
            for (int b = 0; b < attrBytesPerEdge; b++) {
                byte tmp = attrSegment.get(ValueLayout.JAVA_BYTE, offI + b);
                attrSegment.set(ValueLayout.JAVA_BYTE, offI + b, attrSegment.get(ValueLayout.JAVA_BYTE, offJ + b));
                attrSegment.set(ValueLayout.JAVA_BYTE, offJ + b, tmp);
            }
        }
    }

    /**
     * Binary searches for the first element index with primary key >= key (lower bound).
     */
    public int findLowerBound(int key) {
        ensureSorted();
        int low = 0;
        int high = count - 1;
        int ans = count;

        MemorySegment keySeg = (sortKey == SortKey.SRC_ID) ? srcSegment : dstSegment;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midVal = keySeg.getAtIndex(ValueLayout.JAVA_INT, mid);
            if (midVal >= key) {
                ans = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return ans;
    }

    /**
     * Binary searches for the first element index with primary key > key (upper bound).
     */
    public int findUpperBound(int key) {
        ensureSorted();
        int low = 0;
        int high = count - 1;
        int ans = count;

        MemorySegment keySeg = (sortKey == SortKey.SRC_ID) ? srcSegment : dstSegment;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midVal = keySeg.getAtIndex(ValueLayout.JAVA_INT, mid);
            if (midVal > key) {
                ans = mid;
                high = mid - 1;
            } else {
                low = mid + 1;
            }
        }
        return ans;
    }

    /**
     * Binary searches for contiguous [start, end) index bounds for the primary key.
     */
    public Bounds findBounds(int key) {
        ensureSorted();
        if (count == 0) return Bounds.EMPTY;

        int minKey = (sortKey == SortKey.SRC_ID) ? minSrcId : minDstId;
        int maxKey = (sortKey == SortKey.SRC_ID) ? maxSrcId : maxDstId;
        if (key < minKey || key > maxKey) {
            return Bounds.EMPTY;
        }

        int lower = findLowerBound(key);
        if (lower >= count) return Bounds.EMPTY;

        MemorySegment keySeg = (sortKey == SortKey.SRC_ID) ? srcSegment : dstSegment;
        if (keySeg.getAtIndex(ValueLayout.JAVA_INT, lower) != key) {
            return Bounds.EMPTY;
        }

        int upper = findUpperBound(key);
        return new Bounds(lower, upper);
    }

    /**
     * Intersects a sorted frontier of node IDs with this block (merge-join) and returns the count of matching edges.
     * This avoids binary searching each node in the frontier independently (O(F log D) -> O(F + D)).
     */
    public int intersectFrontierCount(int[] sortedFrontier) {
        ensureSorted();
        if (count == 0 || sortedFrontier.length == 0) return 0;

        int matchCount = 0;
        int blockIdx = 0;
        int frontIdx = 0;
        
        MemorySegment keySeg = (sortKey == SortKey.SRC_ID) ? srcSegment : dstSegment;

        while (blockIdx < count && frontIdx < sortedFrontier.length) {
            int blockKey = keySeg.getAtIndex(ValueLayout.JAVA_INT, blockIdx);
            int frontKey = sortedFrontier[frontIdx];
            
            if (blockKey < frontKey) {
                blockIdx++;
            } else if (blockKey > frontKey) {
                frontIdx++;
            } else {
                matchCount++;
                blockIdx++; // Continue checking this block entry (might have multiple edges for same src)
            }
        }
        return matchCount;
    }

    /**
     * Returns matching target IDs for a source ID in a SRC_ID-sorted block.
     */
    public int[] getTargets(int srcId) {
        if (sortKey != SortKey.SRC_ID) {
            throw new IllegalStateException("getTargets() requires SortKey.SRC_ID, current is " + sortKey);
        }
        Bounds bounds = findBounds(srcId);
        if (!bounds.found()) {
            return new int[0];
        }
        int len = bounds.count();
        int[] targets = new int[len];
        MemorySegment.copy(dstSegment, ValueLayout.JAVA_INT_UNALIGNED, (long) bounds.start() * 4, targets, 0, len);
        return targets;
    }

    /**
     * Returns matching source IDs for a destination ID in a DST_ID-sorted block.
     */
    public int[] getSources(int dstId) {
        if (sortKey != SortKey.DST_ID) {
            throw new IllegalStateException("getSources() requires SortKey.DST_ID, current is " + sortKey);
        }
        Bounds bounds = findBounds(dstId);
        if (!bounds.found()) {
            return new int[0];
        }
        int len = bounds.count();
        int[] sources = new int[len];
        MemorySegment.copy(srcSegment, ValueLayout.JAVA_INT_UNALIGNED, (long) bounds.start() * 4, sources, 0, len);
        return sources;
    }

    /**
     * Splits this block at the median index into two sorted blocks allocated in the given arena.
     *
     * @param targetArena the arena in which to allocate the child blocks
     * @return an array of two sorted ColumnarDeltaBlocks [left, right]
     */
    public ColumnarDeltaBlock[] split(Arena targetArena) {
        ensureSorted();
        if (count < 2) {
            throw new IllegalStateException("Cannot split block with fewer than 2 elements, count: " + count);
        }

        int mid = count / 2;
        int leftCount = mid;
        int rightCount = count - mid;

        ColumnarDeltaBlock left = new ColumnarDeltaBlock(targetArena, this.capacity, this.attrBytesPerEdge, this.sortKey);
        ColumnarDeltaBlock right = new ColumnarDeltaBlock(targetArena, this.capacity, this.attrBytesPerEdge, this.sortKey);

        // Copy left partition
        MemorySegment.copy(this.srcSegment, 0, left.srcSegment, 0, (long) leftCount * ValueLayout.JAVA_INT.byteSize());
        MemorySegment.copy(this.dstSegment, 0, left.dstSegment, 0, (long) leftCount * ValueLayout.JAVA_INT.byteSize());
        if (attrBytesPerEdge > 0) {
            MemorySegment.copy(this.attrSegment, 0, left.attrSegment, 0, (long) leftCount * attrBytesPerEdge);
        }
        left.count = leftCount;
        left.sorted = true;
        left.recomputeBounds();

        // Copy right partition
        long rightSrcByteOffset = (long) leftCount * ValueLayout.JAVA_INT.byteSize();
        long rightDstByteOffset = (long) leftCount * ValueLayout.JAVA_INT.byteSize();
        long rightAttrByteOffset = (long) leftCount * attrBytesPerEdge;

        MemorySegment.copy(this.srcSegment, rightSrcByteOffset, right.srcSegment, 0, (long) rightCount * ValueLayout.JAVA_INT.byteSize());
        MemorySegment.copy(this.dstSegment, rightDstByteOffset, right.dstSegment, 0, (long) rightCount * ValueLayout.JAVA_INT.byteSize());
        if (attrBytesPerEdge > 0) {
            MemorySegment.copy(this.attrSegment, rightAttrByteOffset, right.attrSegment, 0, (long) rightCount * attrBytesPerEdge);
        }
        right.count = rightCount;
        right.sorted = true;
        right.recomputeBounds();

        return new ColumnarDeltaBlock[]{left, right};
    }
}
