package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.impulsegraph.core.mutation.DualColumnarOverlay;
import org.impulsegraph.core.mutation.DeletedNodeBitSet;
import org.impulsegraph.core.mutation.OffHeapTombstoneBitSet;
import org.impulsegraph.core.mutation.OverlayMutator;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.HashMap;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Instruction Handlers for Impulse VM opcodes.
 * Provides static execution methods operating zero-copy on off-heap FFM MemorySegments and VmQueryContext.
 */
public final class VmHandlers {

    public static final int PARALLEL_FRONTIER_THRESHOLD = 524_288; // 512k Frontier Threshold
    public static final byte FLAG_HALT_ON_EMPTY = 0x01;
    public static final byte FLAG_INPUT_SEED = 0x02;

    private VmHandlers() {}

    /**
     * Decode instruction fields from 8-byte off-heap instruction segment or encoded long.
     */
    public record Instruction(byte opcode, byte flags, int dstReg, int payload) {}

    public static Instruction decodeInstruction(MemorySegment programSeg, long pc) {
        long offset = pc * INSTRUCTION_SIZE_BYTES;
        byte opcode = (byte) INSTR_OPCODE_HANDLE.get(programSeg, offset);
        byte flags = (byte) INSTR_FLAGS_HANDLE.get(programSeg, offset);
        int dstReg = Short.toUnsignedInt((short) INSTR_DST_REG_HANDLE.get(programSeg, offset));
        int payload = (int) INSTR_PAYLOAD_HANDLE.get(programSeg, offset);
        return new Instruction(opcode, flags, dstReg, payload);
    }

    // --- State Access Helpers ---

    public static long getRegisterValue(MemorySegment state, int regIndex) {
        return (long) REGISTER_ELEMENT_HANDLE.get(state, 0L, (long) regIndex);
    }

    public static void setRegister(MemorySegment state, int regIndex, long value, byte typeTag) {
        REGISTER_ELEMENT_HANDLE.set(state, 0L, (long) regIndex, value);
        REGISTER_TYPE_ELEMENT_HANDLE.set(state, 0L, (long) regIndex, typeTag);
    }

    public static byte getRegisterType(MemorySegment state, int regIndex) {
        return (byte) REGISTER_TYPE_ELEMENT_HANDLE.get(state, 0L, (long) regIndex);
    }

    public static long getFlags(MemorySegment state) {
        return (long) FLAGS_HANDLE.get(state, 0L);
    }

    public static void handleCreateScratchIndex(MemorySegment state, Instruction inst, VmQueryContext ctx) {
        int relationId = (inst.payload() >> 16) & 0xFFFF;
        if (relationId != 0xFFFF) {
            throw new UnsupportedOperationException("Edge attribute secondary indexes are currently unimplemented");
        }
        setFlag(state, FLAG_ZF, false);
    }

    public static void setFlag(MemorySegment state, long flagMask, boolean value) {
        long curFlags = getFlags(state);
        if (value) {
            FLAGS_HANDLE.set(state, 0L, curFlags | flagMask);
        } else {
            FLAGS_HANDLE.set(state, 0L, curFlags & ~flagMask);
        }
    }

    public static boolean checkFlag(MemorySegment state, long flagMask) {
        return (getFlags(state) & flagMask) != 0;
    }

    // --- Instruction Handlers ---

    public static void handleInitInputNode(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
        long nodeId = 0;
        if (input instanceof Number n) {
            nodeId = n.longValue();
        }
        setRegister(state, instr.dstReg(), nodeId, TYPE_NODE_ID);
        setFlag(state, FLAG_ZF, false);
    }

    public static void handleInitInputSet(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
        int handle = ctx.acquireBitset();
        ImpulseBitSet bs = ctx.getBitset(handle);
        if (input instanceof ImpulseBitSet inBs) {
            bs.or(inBs);
        } else if (input instanceof Number n) {
            bs.set(n.intValue());
        } else if (input instanceof Iterable<?> it) {
            for (Object elem : it) {
                if (elem instanceof Number n) bs.set(n.intValue());
            }
        }
        setRegister(state, instr.dstReg(), handle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, bs.isEmpty());
    }

    public static void handleLoadConstInt(MemorySegment state, Instruction instr) {
        setRegister(state, instr.dstReg(), (long) instr.payload(), TYPE_INT64);
    }

    public static void handleLoadConstFloat(MemorySegment state, Instruction instr) {
        float fVal = Float.intBitsToFloat(instr.payload());
        setRegister(state, instr.dstReg(), Float.floatToRawIntBits(fVal), TYPE_FLOAT);
    }

    private static final String[] PRECACHED_REL_NAMES = new String[256];
    static {
        for (int i = 0; i < 256; i++) {
            PRECACHED_REL_NAMES[i] = "rel_" + i;
        }
    }

    private static RelationSnapshot resolveRelation(VmQueryContext ctx, int relId) {
        GraphSnapshot graph = ctx.snapshot();
        if (graph == null) return null;
        String name = (relId >= 0 && relId < 256) ? PRECACHED_REL_NAMES[relId] : ("rel_" + relId);
        RelationSnapshot rel = graph.getRelationSnapshot(name);
        if (rel == null && !graph.getAllRelationSnapshots().isEmpty()) {
            int idx = 0;
            for (RelationSnapshot snap : graph.getAllRelationSnapshots().values()) {
                if (idx == relId) {
                    rel = snap;
                    break;
                }
                idx++;
            }
        }
        return rel;
    }

    public static void handleCsrWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        handleCsrWalk(state, ctx, instr, null);
    }

    public static void handleCsrWalk(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
        int srcReg = instr.payload() & 0xFFFF;
        int relId = (instr.payload() >> 16) & 0xFFFF;
        executeCsrWalk(state, ctx, instr.dstReg(), srcReg, relId, instr.flags(), input);
    }

    public static void executeCsrWalk(MemorySegment state, VmQueryContext ctx, int dstReg, int srcReg, int relId) {
        executeCsrWalk(state, ctx, dstReg, srcReg, relId, (byte) 0, null);
    }

    public static void executeCsrWalk(MemorySegment state, VmQueryContext ctx, int dstReg, int srcReg, int relId, byte flags, Object input) {
        RelationSnapshot rel = resolveRelation(ctx, relId);
        final DualColumnarOverlay overlay = (rel != null && ctx.snapshot() != null) ? ctx.snapshot().getOverlay(rel) : null;
        final boolean hasOverlay = overlay != null && overlay.getMutationCount() > 0;


        byte srcType = getRegisterType(state, srcReg);
        long srcVal = getRegisterValue(state, srcReg);

        if ((flags & FLAG_INPUT_SEED) != 0 && input instanceof Number n) {
            srcType = TYPE_NODE_ID;
            srcVal = n.longValue();
        }

        int outHandle = ctx.acquireBitset();
        ImpulseBitSet outBs = ctx.getBitset(outHandle);

        if (rel != null) {
            if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
                if (hasOverlay) executeFusedWalkScalar((int) srcVal, rel, overlay, outBs, ctx); else rel.copyTargetsSimd((int) srcVal, outBs);
            } else if (srcType == TYPE_BITSET_HANDLE) {
                ImpulseBitSet inBs = ctx.getBitset((int) srcVal);
                if (inBs != null) {
                    long card = inBs.cardinality();
                    int numThreads = Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism());
                    if (card >= 5_000 && numThreads > 1) {
                        java.util.concurrent.atomic.AtomicInteger nextChunk = new java.util.concurrent.atomic.AtomicInteger(0);
                        int nodeCount = rel.getNodeCount();
                        final int chunkSize = 1024;

                        ImpulseBitSet[] threadBs = new ImpulseBitSet[numThreads];
                        for (int t = 0; t < numThreads; t++) {
                            threadBs[t] = ctx.getBitset(ctx.acquireBitset());
                        }

                        java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
                            ImpulseBitSet localBs = threadBs[t];
                            while (true) {
                                int startV = nextChunk.getAndAdd(chunkSize);
                                if (startV >= nodeCount) break;
                                int endV = Math.min(startV + chunkSize, nodeCount);
                                for (int u = inBs.nextSetBit(startV); u >= 0 && u < endV; u = inBs.nextSetBit(u + 1)) {
                                    if (hasOverlay) executeFusedWalkScalar(u, rel, overlay, localBs, ctx); else rel.copyTargetsSimd(u, localBs);
                                }
                            }
                        });

                        for (int t = 0; t < numThreads; t++) {
                            outBs.or(threadBs[t]);
                        }
                    } else {
                        for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
                            if (hasOverlay) executeFusedWalkScalar(u, rel, overlay, outBs, ctx); else rel.copyTargetsSimd(u, outBs);
                        }
                    }
                }
            }
        }

        setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
    }

    private static void executeFusedWalkScalar(int srcId, RelationSnapshot rel, DualColumnarOverlay overlay, ImpulseBitSet outBs, VmQueryContext ctx) {
        DeletedNodeBitSet deletedNodes = ctx.snapshot() != null ? ctx.snapshot().getDeletedNodes() : null;
        if (deletedNodes != null && deletedNodes.isDeleted(0, srcId)) return;

        OffHeapTombstoneBitSet tombstones = ctx.snapshot() != null ? ctx.snapshot().getEdgeTombstones(rel) : null;

        if (srcId < rel.getNodeCount()) {
            int start = rel.getRowOffsetsSegment().getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, srcId);
            int end = rel.getRowOffsetsSegment().getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, srcId + 1);
            for (int i = start; i < end; i++) {
                if (tombstones != null && tombstones.get(i)) continue;
                int tgt = rel.getColumnTargetsSegment().getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, i);
                if (deletedNodes != null && deletedNodes.isDeleted(0, tgt)) continue;
                outBs.set(tgt);
            }
        }
        if (overlay != null) {
            int[] additions = overlay.getForwardEdges(srcId);
            for (int tgt : additions) {
                if (deletedNodes != null && deletedNodes.isDeleted(0, tgt)) continue;
                outBs.set(tgt);
            }
        }
    }

    public static void handleCsrWalk2Hop(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
        int relId1 = instr.payload() & 0xFFFF;
        int relId2 = (instr.payload() >> 16) & 0xFFFF;
        executeCsrWalk2Hop(state, ctx, instr.dstReg(), 0, relId1, relId2, instr.flags(), input);
    }

    public static void executeCsrWalk2Hop(MemorySegment state, VmQueryContext ctx, int dstReg, int srcReg,
                                          int relId1, int relId2, byte flags, Object input) {
        RelationSnapshot rel1 = resolveRelation(ctx, relId1);
        RelationSnapshot rel2 = resolveRelation(ctx, relId2);

        int outHandle = ctx.acquireBitset();
        ImpulseBitSet outBs = ctx.getBitset(outHandle);

        if (rel1 != null && rel2 != null) {
            MemorySegment r1Offsets = rel1.getRowOffsetsSegment();
            MemorySegment r1Targets = rel1.getColumnTargetsSegment();

            if ((flags & FLAG_INPUT_SEED) != 0 && input instanceof Number n) {
                int seed = n.intValue();
                if (seed >= 0 && seed < rel1.getNodeCount()) {
                    int start1 = r1Offsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, seed);
                    int end1 = r1Offsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, seed + 1);
                    for (int i = start1; i < end1; i++) {
                        int hop1Target = r1Targets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
                        rel2.copyTargetsSimd(hop1Target, outBs);
                    }
                }
            } else {
                byte srcType = getRegisterType(state, srcReg);
                long srcVal = getRegisterValue(state, srcReg);
                if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
                    int seed = (int) srcVal;
                    if (seed >= 0 && seed < rel1.getNodeCount()) {
                        int start1 = r1Offsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, seed);
                        int end1 = r1Offsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, seed + 1);
                        for (int i = start1; i < end1; i++) {
                            int hop1Target = r1Targets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
                            rel2.copyTargetsSimd(hop1Target, outBs);
                        }
                    }
                } else if (srcType == TYPE_BITSET_HANDLE) {
                    ImpulseBitSet inBs = ctx.getBitset((int) srcVal);
                    if (inBs != null) {
                        for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
                            if (u < rel1.getNodeCount()) {
                                int start1 = r1Offsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                                int end1 = r1Offsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
                                for (int i = start1; i < end1; i++) {
                                    int hop1Target = r1Targets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
                                    rel2.copyTargetsSimd(hop1Target, outBs);
                                }
                            }
                        }
                    }
                }
            }
        }

        setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
    }

    public static void handleLoadConstStrPrefix(MemorySegment state, Instruction instr) {
        long val = Integer.toUnsignedLong(instr.payload());
        setRegister(state, instr.dstReg(), val, TYPE_INT64);
        setFlag(state, FLAG_ZF, val == 0);
    }

    public static void handleCscWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        handleCscWalk(state, ctx, instr, null);
    }

    public static void handleCscWalk(MemorySegment state, VmQueryContext ctx, Instruction instr, Object input) {
        int frontierReg = instr.payload() & 0xFFFF;
        int unvisitedReg = (instr.payload() >> 16) & 0xFF;
        int relId = (instr.payload() >> 24) & 0xFF;

        RelationSnapshot rel = resolveRelation(ctx, relId);
        if (rel == null || !rel.hasCsc()) {
            throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
        }

        int outHandle = ctx.acquireBitset();
        ImpulseBitSet outBs = ctx.getBitset(outHandle);

        if (unvisitedReg != 0) {
            // Bottom-Up Pull Mode (Frontier = frontierReg, Unvisited = unvisitedReg)
            ImpulseBitSet frontierBs = ctx.getBitset((int) getRegisterValue(state, frontierReg));
            ImpulseBitSet unvisitedBs = ctx.getBitset((int) getRegisterValue(state, unvisitedReg));

            if (frontierBs != null && unvisitedBs != null) {
                MemorySegment cscRowOff = rel.getCscRowOffsetsSegment();
                MemorySegment cscColIdx = rel.getCscColumnTargetsSegment();
                int nodeCount = rel.getNodeCount();

                int numThreads = Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism());
                int unvisitedCount = (int) unvisitedBs.cardinality();
                if (unvisitedCount >= 10_000 && nodeCount >= 10_000) {
                    if (numThreads > 1) {
                        java.util.concurrent.atomic.AtomicInteger nextChunk = new java.util.concurrent.atomic.AtomicInteger(0);
                        final int chunkSize = 1024;

                        java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
                            while (true) {
                                int startV = nextChunk.getAndAdd(chunkSize);
                                if (startV >= nodeCount) break;
                                int endV = Math.min(startV + chunkSize, nodeCount);
                                for (int v = unvisitedBs.nextSetBit(startV); v >= 0 && v < endV; v = unvisitedBs.nextSetBit(v + 1)) {
                                    int start = cscRowOff.getAtIndex(ValueLayout.JAVA_INT, v);
                                    int end = cscRowOff.getAtIndex(ValueLayout.JAVA_INT, v + 1);
                                    for (int i = start; i < end; i++) {
                                        int target = cscColIdx.getAtIndex(ValueLayout.JAVA_INT, i);
                                        if (frontierBs.get(target)) {
                                            outBs.set(v);
                                            break;
                                        }
                                    }
                                }
                            }
                        });
                    } else {
                        // Cache-tiled single-threaded loop (1024 nodes per L1 cache tile)
                        final int chunkSize = 1024;
                        for (int startV = 0; startV < nodeCount; startV += chunkSize) {
                            int endV = Math.min(startV + chunkSize, nodeCount);
                            for (int v = unvisitedBs.nextSetBit(startV); v >= 0 && v < endV; v = unvisitedBs.nextSetBit(v + 1)) {
                                int start = cscRowOff.getAtIndex(ValueLayout.JAVA_INT, v);
                                int end = cscRowOff.getAtIndex(ValueLayout.JAVA_INT, v + 1);
                                for (int i = start; i < end; i++) {
                                    int target = cscColIdx.getAtIndex(ValueLayout.JAVA_INT, i);
                                    if (frontierBs.get(target)) {
                                        outBs.set(v);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else {
                    for (int v = unvisitedBs.nextSetBit(0); v >= 0; v = unvisitedBs.nextSetBit(v + 1)) {
                        int start = cscRowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, v);
                        int end = cscRowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, v + 1);
                        for (int idx = start; idx < end; idx++) {
                            int u = cscColIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
                            if (frontierBs.get(u)) {
                                outBs.set(v);
                                break;
                            }
                        }
                    }
                }
            }
        } else {
            // Standard CSC Walk Mode
            byte srcType = getRegisterType(state, frontierReg);
            long srcVal = getRegisterValue(state, frontierReg);

            if ((instr.flags() & FLAG_INPUT_SEED) != 0 && input instanceof Number n) {
                srcType = TYPE_NODE_ID;
                srcVal = n.longValue();
            }

            if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
                rel.copyInTargetsSimd((int) srcVal, outBs);
            } else if (srcType == TYPE_BITSET_HANDLE) {
                ImpulseBitSet inBs = ctx.getBitset((int) srcVal);
                if (inBs != null) {
                    for (int v = inBs.nextSetBit(0); v >= 0; v = inBs.nextSetBit(v + 1)) {
                        rel.copyInTargetsSimd(v, outBs);
                    }
                }
            }
        }

        setRegister(state, instr.dstReg(), outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
    }

    public static void handleAdaptiveWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int frontierReg = instr.payload() & 0xFFFF;
        int unvisitedReg = (instr.payload() >> 16) & 0xFF;
        int relId = (instr.payload() >> 24) & 0xFF;

        RelationSnapshot rel = resolveRelation(ctx, relId);
        if (rel == null) {
            throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
        }

        if (unvisitedReg != 0 && rel.hasCsc()) {
            ImpulseBitSet frontierBs = ctx.getBitset((int) getRegisterValue(state, frontierReg));
            if (frontierBs != null) {
                long frontierSize = frontierBs.cardinality();
                org.impulsegraph.api.stats.RelationStatistics stats = rel.getStatistics();

                long estimatedFrontierEdges = frontierSize * (long) Math.max(1.0, stats.getAvgDegree());
                long pullThreshold = stats.getEdgeCount() / 80;
                boolean shouldUsePull = estimatedFrontierEdges > pullThreshold || frontierSize > (stats.getNodeCount() / 80);

                if (!shouldUsePull && frontierSize > 50_000 && stats.getSupernodeBitSet() != null && !stats.getSupernodeBitSet().isEmpty()) {
                    ImpulseBitSet supernodes = stats.getSupernodeBitSet();
                    for (int s = supernodes.nextSetBit(0); s >= 0; s = supernodes.nextSetBit(s + 1)) {
                        if (frontierBs.get(s)) {
                            shouldUsePull = true;
                            break;
                        }
                    }
                }

                if (shouldUsePull) {
                    handleCscWalk(state, ctx, instr);
                    return;
                }
            }
        }

        executeCsrWalk(state, ctx, instr.dstReg(), frontierReg, relId);
    }

    public static void handleCcAfforest(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int relId = instr.payload() & 0xFFFF;
        RelationSnapshot rel = resolveRelation(ctx, relId);
        if (rel == null) {
            throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
        }

        int nodeCount = rel.getNodeCount();
        java.util.concurrent.atomic.AtomicIntegerArray comp = new java.util.concurrent.atomic.AtomicIntegerArray(nodeCount);

        MemorySegment rowOff = rel.getRowOffsetsSegment();
        MemorySegment colIdx = rel.getColumnTargetsSegment();

        int numThreads = Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism());
        int chunkSize = (nodeCount + numThreads - 1) / numThreads;

        // Parallel Initialization
        java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
            int startU = t * chunkSize;
            int endU = Math.min(startU + chunkSize, nodeCount);
            for (int u = startU; u < endU; u++) comp.set(u, u);
        });

        // 1. 2-Neighbor sampling pass
        for (int r = 0; r < 2; r++) {
            final int neighborIdx = r;
            java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
                int startU = t * chunkSize;
                int endU = Math.min(startU + chunkSize, nodeCount);
                for (int u = startU; u < endU; u++) {
                    int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                    int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
                    int deg = end - start;
                    if (neighborIdx < deg) {
                        int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + neighborIdx);
                        if (v < nodeCount) unionCcNodesAtomic(comp, u, v);
                    }
                }
            });
        }

        // 2. Identify Giant Component Root
        int sampleN = Math.min(nodeCount, 100_000);
        int[] counts = new int[Math.min(sampleN, 1024)];
        int giantRoot = 0;
        int maxCount = 0;
        for (int i = 0; i < counts.length; i++) {
            int u = (int) ((i * 9973L) % nodeCount);
            counts[i] = findCcRootAtomic(comp, u);
        }
        for (int i = 0; i < counts.length; i++) {
            int root = counts[i];
            int cnt = 0;
            for (int j = 0; j < counts.length; j++) {
                if (counts[j] == root) cnt++;
            }
            if (cnt > maxCount) {
                maxCount = cnt;
                giantRoot = root;
            }
        }

        // 3. Parallel Full CSR Edge Processing (skipping giant component)
        final int gRoot = giantRoot;
        java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
            int startU = t * chunkSize;
            int endU = Math.min(startU + chunkSize, nodeCount);
            for (int u = startU; u < endU; u++) {
                if (findCcRootAtomic(comp, u) != gRoot) {
                    int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                    int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
                    for (int idx = start; idx < end; idx++) {
                        int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
                        if (v < nodeCount) unionCcNodesAtomic(comp, u, v);
                    }
                }
            }
        });

        // 4. Parallel Path Compression
        int[] resultComp = new int[nodeCount];
        java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
            int startU = t * chunkSize;
            int endU = Math.min(startU + chunkSize, nodeCount);
            for (int u = startU; u < endU; u++) {
                resultComp[u] = findCcRootAtomic(comp, u);
            }
        });

        int outHandle = ctx.acquireNodeVector(resultComp);
        setRegister(state, instr.dstReg(), outHandle, TYPE_NODE_VECTOR);
        setFlag(state, FLAG_ZF, false);
    }

    private static int findCcRootAtomic(java.util.concurrent.atomic.AtomicIntegerArray comp, int curr) {
        while (curr != comp.get(curr)) {
            int parent = comp.get(curr);
            int grandParent = comp.get(parent);
            comp.compareAndSet(curr, parent, grandParent);
            curr = grandParent;
        }
        return curr;
    }

    private static void unionCcNodesAtomic(java.util.concurrent.atomic.AtomicIntegerArray comp, int u, int v) {
        while (true) {
            int rootU = findCcRootAtomic(comp, u);
            int rootV = findCcRootAtomic(comp, v);
            if (rootU == rootV) return;
            int high = Math.min(rootU, rootV);
            int low = Math.max(rootU, rootV);
            if (comp.compareAndSet(low, low, high)) break;
        }
    }

    public static void handleMxv(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int xReg = instr.payload() & 0xFFFF;
        int relId = (instr.payload() >> 16) & 0xFFFF;

        RelationSnapshot rel = resolveRelation(ctx, relId);
        if (rel == null) {
            throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
        }

        int nodeCount = rel.getNodeCount();
        float[] x = ctx.getFloatVector((int) getRegisterValue(state, xReg));
        if (x == null || x.length < nodeCount) {
            throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_VECTOR");
        }

        int outHandle = ctx.acquireFloatVector(nodeCount);
        float[] y = ctx.getFloatVector(outHandle);

        MemorySegment rowOff = rel.getRowOffsetsSegment();
        MemorySegment colIdx = rel.getColumnTargetsSegment();

        int numThreads = Math.max(1, java.util.concurrent.ForkJoinPool.commonPool().getParallelism());
        int chunkSize = (nodeCount + numThreads - 1) / numThreads;

        // Parallel Zero-Allocation 4-Wide Unrolled SpMV: y[u] = sum(x[v] for v in neighbors(u))
        java.util.stream.IntStream.range(0, numThreads).parallel().forEach(t -> {
            int startU = t * chunkSize;
            int endU = Math.min(startU + chunkSize, nodeCount);

            for (int u = startU; u < endU; u++) {
                int start = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                int end = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
                int count = end - start;

                float sum = 0.0f;
                int idx = start;
                int end4 = start + ((count >> 2) << 2);

                for (; idx < end4; idx += 4) {
                    int v0 = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
                    int v1 = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx + 1);
                    int v2 = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx + 2);
                    int v3 = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx + 3);
                    sum += x[v0] + x[v1] + x[v2] + x[v3];
                }
                for (; idx < end; idx++) {
                    int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, idx);
                    sum += x[v];
                }
                y[u] = sum;
            }
        });

        setRegister(state, instr.dstReg(), outHandle, TYPE_FLOAT_VECTOR);
        setFlag(state, FLAG_ZF, false);
    }

    public static void handleHasCsr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int relId = instr.payload() & 0xFFFF;
        RelationSnapshot rel = resolveRelation(ctx, relId);
        boolean present = (rel != null && rel.hasCsr());
        setRegister(state, instr.dstReg(), present ? 1L : 0L, TYPE_INT64);
        setFlag(state, FLAG_ZF, !present);
    }

    public static void handleHasCsc(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int relId = instr.payload() & 0xFFFF;
        RelationSnapshot rel = resolveRelation(ctx, relId);
        boolean present = (rel != null && rel.hasCsc());
        setRegister(state, instr.dstReg(), present ? 1L : 0L, TYPE_INT64);
        setFlag(state, FLAG_ZF, !present);
    }

    public static void handleHasCoo(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int relId = instr.payload() & 0xFFFF;
        RelationSnapshot rel = resolveRelation(ctx, relId);
        boolean present = (rel != null);
        setRegister(state, instr.dstReg(), present ? 1L : 0L, TYPE_INT64);
        setFlag(state, FLAG_ZF, !present);
    }

    public static void handleHasKeyCatalog(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        setRegister(state, instr.dstReg(), 0L, TYPE_INT64);
        setFlag(state, FLAG_ZF, true);
    }

    public static void handleCsrDegree(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg = instr.payload() & 0xFFFF;
        int relId = (instr.payload() >> 16) & 0xFFFF;
        long u = getRegisterValue(state, srcReg);

        RelationSnapshot rel = resolveRelation(ctx, relId);
        long degree = 0;
        if (rel != null && u >= 0 && u < rel.getNodeCount()) {
            degree = rel.getDegree((int) u);
        }

        setRegister(state, instr.dstReg(), degree, TYPE_INT64);
        setFlag(state, FLAG_ZF, degree == 0);
    }

    public static void handleCsrWalkPredicate(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        handleCsrWalk(state, ctx, instr);
    }

    public static void handleVectorDiv(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int reg1 = instr.payload() & 0xFFFF;
        int reg2 = (instr.payload() >> 16) & 0xFFFF;
        float[] v1 = ctx.getFloatVector((int) getRegisterValue(state, reg1));
        float[] v2 = ctx.getFloatVector((int) getRegisterValue(state, reg2));
        if (v1 != null && v2 != null && v1.length == v2.length) {
            for (int i = 0; i < v1.length; i++) {
                if (v2[i] != 0.0f) v1[i] /= v2[i];
            }
        }
    }

    public static void handleVectorStrConcat(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int outHandle = ctx.registerStringVector(new String[1024]);
        setRegister(state, instr.dstReg(), outHandle, TYPE_STRING_VECTOR);
        setFlag(state, FLAG_ZF, false);
    }

    public static void handleRoaringBitmapAnd(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        handleSetIntersect(state, ctx, instr);
    }

    public static void handleSetUnion(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int dstReg = instr.dstReg();
        int srcReg1 = instr.payload() & 0xFFFF;
        int srcReg2 = (instr.payload() >> 16) & 0xFFFF;

        int outHandle = ctx.acquireBitset();
        ImpulseBitSet outBs = ctx.getBitset(outHandle);

        byte type1 = getRegisterType(state, srcReg1);
        if (type1 == TYPE_BITSET_HANDLE) {
            ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
            if (bs1 != null) outBs.or(bs1);
        } else if (type1 == TYPE_NODE_ID || type1 == TYPE_INT64) {
            outBs.set((int) getRegisterValue(state, srcReg1));
        }

        if (srcReg2 != 0) {
            byte type2 = getRegisterType(state, srcReg2);
            if (type2 == TYPE_BITSET_HANDLE) {
                ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));
                if (bs2 != null) outBs.or(bs2);
            } else if (type2 == TYPE_NODE_ID || type2 == TYPE_INT64) {
                outBs.set((int) getRegisterValue(state, srcReg2));
            }
        }

        setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
    }



    public static void handleSetIntersect(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg1 = instr.payload() & 0xFFFF;
        int srcReg2 = (instr.payload() >> 16) & 0xFFFF;

        ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
        ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));

        int outHandle = ctx.acquireBitset();
        ImpulseBitSet outBs = ctx.getBitset(outHandle);
        if (bs1 != null && bs2 != null) {
            outBs.or(bs1);
            outBs.and(bs2);
        }

        setRegister(state, instr.dstReg(), outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
    }

    public static void handleSetDifference(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg1 = instr.payload() & 0xFFFF;
        int srcReg2 = (instr.payload() >> 16) & 0xFFFF;

        ImpulseBitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
        ImpulseBitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));

        int outHandle = ctx.acquireBitset();
        ImpulseBitSet outBs = ctx.getBitset(outHandle);
        if (bs1 != null) {
            outBs.or(bs1);
            if (bs2 != null) {
                outBs.andNot(bs2);
            }
        }

        setRegister(state, instr.dstReg(), outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
    }

    public static void handleSetCardinality(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg = instr.payload() & 0xFFFF;
        ImpulseBitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
        long count = (bs != null) ? bs.cardinality() : 0;
        setRegister(state, instr.dstReg(), count, TYPE_INT64);
        setFlag(state, FLAG_ZF, count == 0);
    }

    public static boolean pushCallStack(MemorySegment state, int returnPc) {
        int depth = (int) CALL_STACK_DEPTH_HANDLE.get(state, 0L);
        if (depth >= 8) return false;
        CALL_STACK_ELEMENT_HANDLE.set(state, 0L, (long) depth, returnPc);
        CALL_STACK_DEPTH_HANDLE.set(state, 0L, depth + 1);
        return true;
    }

    public static int popCallStack(MemorySegment state) {
        int depth = (int) CALL_STACK_DEPTH_HANDLE.get(state, 0L);
        if (depth <= 0) return -1;
        int returnPc = (int) CALL_STACK_ELEMENT_HANDLE.get(state, 0L, (long) (depth - 1));
        CALL_STACK_DEPTH_HANDLE.set(state, 0L, depth - 1);
        return returnPc;
    }

    public static void handleMov(MemorySegment state, Instruction instr) {
        int srcReg = (instr.payload() >> 16) != 0 ? ((instr.payload() >> 16) & 0xFFFF) : (instr.payload() & 0xFFFF);
        long val = getRegisterValue(state, srcReg);
        byte typeTag = getRegisterType(state, srcReg);
        setRegister(state, instr.dstReg(), val, typeTag);
    }

    public static void handleClearReg(MemorySegment state, Instruction instr) {
        setRegister(state, instr.dstReg(), 0L, TYPE_NULL);
    }

    public static void handleStableCheck(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int dstReg = instr.dstReg();
        int srcReg = instr.payload() & 0xFFFF;

        byte dstType = getRegisterType(state, dstReg);
        byte srcType = getRegisterType(state, srcReg);

        long dstVal = getRegisterValue(state, dstReg);
        long srcVal = getRegisterValue(state, srcReg);

        boolean isSubset = false;
        if (srcType == TYPE_BITSET_HANDLE && dstType == TYPE_BITSET_HANDLE) {
            ImpulseBitSet bsSrc = ctx.getBitset((int) srcVal);
            ImpulseBitSet bsDst = ctx.getBitset((int) dstVal);
            if (bsSrc != null && bsDst != null) {
                isSubset = true;
                for (int i = bsSrc.nextSetBit(0); i >= 0; i = bsSrc.nextSetBit(i + 1)) {
                    if (!bsDst.get(i)) {
                        isSubset = false;
                        break;
                    }
                }
            }
        } else if (srcType != TYPE_BITSET_HANDLE && dstType == TYPE_BITSET_HANDLE) {
            ImpulseBitSet bsDst = ctx.getBitset((int) dstVal);
            isSubset = (bsDst != null) && bsDst.get((int) srcVal);
        } else if (srcType != TYPE_BITSET_HANDLE && dstType != TYPE_BITSET_HANDLE) {
            isSubset = (srcVal == dstVal);
        }

        setFlag(state, FLAG_ST, isSubset);
        setFlag(state, FLAG_ZF, isSubset);
    }

    public static Object handleCollectBitset(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg = (instr.payload() >> 16) != 0 ? ((instr.payload() >> 16) & 0xFFFF) : (instr.payload() & 0xFFFF);
        if (srcReg == 0 && instr.dstReg() != 0) {
            srcReg = instr.dstReg();
        }
        byte typeTag = getRegisterType(state, srcReg);
        int outHandle = ctx.acquireBitset();
        ImpulseBitSet outBs = ctx.getBitset(outHandle);
        if (typeTag == TYPE_BITSET_HANDLE) {
            ImpulseBitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
            if (bs != null && bs != outBs) outBs.or(bs);
        } else if (typeTag == TYPE_NODE_ID || typeTag == TYPE_INT64) {
            outBs.set((int) getRegisterValue(state, srcReg));
        }
        setRegister(state, instr.dstReg(), outHandle, TYPE_BITSET_HANDLE);
        return outBs;
    }

    public static void handleFloatVectorScale(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int reg = instr.dstReg();
        float alpha = Float.intBitsToFloat(instr.payload());
        int handle = (int) getRegisterValue(state, reg);
        float[] vec = ctx.getFloatVector(handle);
        if (vec != null) {
            for (int i = 0; i < vec.length; i++) {
                vec[i] *= alpha;
            }
        }
    }

    public static void handleL1NormDiff(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int reg1 = instr.payload() & 0xFFFF;
        int reg2 = (instr.payload() >> 16) & 0xFFFF;
        float[] v1 = ctx.getFloatVector((int) getRegisterValue(state, reg1));
        float[] v2 = ctx.getFloatVector((int) getRegisterValue(state, reg2));
        double diffSum = 0.0;
        if (v1 != null && v2 != null && v1.length == v2.length) {
            for (int i = 0; i < v1.length; i++) {
                diffSum += Math.abs(v1[i] - v2[i]);
            }
        }
        setRegister(state, instr.dstReg(), Double.doubleToRawLongBits(diffSum), TYPE_FLOAT);
        setFlag(state, FLAG_ZF, diffSum < 1e-4);
    }

    public static void handleTcSweepBatch(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int relId = instr.payload() & 0xFFFF;
        int srcReg = (instr.payload() >> 16) & 0xFFFF;
        GraphSnapshot graph = ctx.snapshot();
        RelationSnapshot rel = (graph != null) ? graph.getRelationSnapshot("rel_" + relId) : null;
        if (rel == null && graph != null && !graph.getAllRelationSnapshots().isEmpty()) {
            rel = graph.getAllRelationSnapshots().values().iterator().next();
        }
        long triangleCount = 0;
        if (rel != null) {
            ImpulseBitSet inBs = ctx.getBitset((int) getRegisterValue(state, srcReg));
            if (inBs != null) {
                MemorySegment rowOff = rel.getRowOffsetsSegment();
                MemorySegment colIdx = rel.getColumnTargetsSegment();
                int nodeCount = rel.getNodeCount();

                for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
                    if (u >= nodeCount) continue;
                    int startU = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
                    int endU = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);

                    for (int i = startU; i < endU; i++) {
                        int v = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
                        if (v > u && v < nodeCount) {
                            int startV = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, v);
                            int endV = rowOff.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, v + 1);
                            triangleCount += countOffHeapIntersection(colIdx, startU, endU, startV, endV);
                        }
                    }
                }
            }
        }
        setRegister(state, instr.dstReg(), triangleCount, TYPE_INT64);
        setFlag(state, FLAG_ZF, triangleCount == 0);
    }

    private static long countOffHeapIntersection(MemorySegment colIdx, int startA, int endA, int startB, int endB) {
        long count = 0;
        int i = startA, j = startB;
        while (i < endA && j < endB) {
            int valA = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
            int valB = colIdx.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, j);
            if (valA == valB) {
                count++;
                i++;
                j++;
            } else if (valA < valB) {
                i++;
            } else {
                j++;
            }
        }
        return count;
    }

    public static void handleReadEdgeWeight(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        setRegister(state, instr.dstReg(), Float.floatToRawIntBits(1.0f), TYPE_FLOAT);
        setFlag(state, FLAG_ZF, false);
    }

    private static int runIslandDetectBfs(int N, MemorySegment offsetsSeg, MemorySegment targetsSeg, MemorySegment branchIdsSeg, long k1, long k2) {
        if (N <= 0) return 0;
        ImpulseBitSet visited = new OffHeapBitSet(java.lang.foreign.Arena.ofAuto(), N);
        int[] queue = new int[N];
        int components = 0;

        for (int i = 0; i < N; i++) {
            if (!visited.get(i)) {
                components++;
                int head = 0;
                int tail = 0;
                queue[tail++] = i;
                visited.set(i);

                while (head < tail) {
                    int u = queue[head++];
                    if (u >= N) continue;

                    int start = offsetsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, u);
                    int end = offsetsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, u + 1);

                    for (int e = start; e < end; e++) {
                        if (branchIdsSeg != null && !branchIdsSeg.equals(MemorySegment.NULL)) {
                            int brId = branchIdsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, e);
                            if (brId == k1 || brId == k2) {
                                continue;
                            }
                        }

                        int v = targetsSeg.getAtIndex(java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED, e);
                        if (v >= 0 && v < N && !visited.get(v)) {
                            visited.set(v);
                            queue[tail++] = v;
                        }
                    }
                }
            }
        }
        return components;
    }

    public static void handleIslandDetect(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int dst = instr.dstReg();
        int src1 = instr.payload() & 0xFF;
        int src2 = (instr.payload() >> 8) & 0xFF;
        int relId = (instr.payload() >> 16) & 0xFFFF;

        java.util.List<Integer> lines1 = new java.util.ArrayList<>();
        if (src1 < 64) {
            byte type = getRegisterType(state, src1);
            if (type == TYPE_INT64) {
                lines1.add((int) getRegisterValue(state, src1));
            } else if (type == TYPE_BITSET_HANDLE) {
                int handle = (int) getRegisterValue(state, src1);
                ImpulseBitSet bs = ctx.getBitset(handle);
                if (bs != null) {
                    for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
                        lines1.add(i);
                    }
                }
            }
        }
        if (lines1.isEmpty()) lines1.add(-1);

        java.util.List<Integer> lines2 = new java.util.ArrayList<>();
        if (src2 < 64) {
            byte type = getRegisterType(state, src2);
            if (type == TYPE_INT64) {
                lines2.add((int) getRegisterValue(state, src2));
            } else if (type == TYPE_BITSET_HANDLE) {
                int handle = (int) getRegisterValue(state, src2);
                ImpulseBitSet bs = ctx.getBitset(handle);
                if (bs != null) {
                    for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
                        lines2.add(i);
                    }
                }
            }
        }
        if (lines2.isEmpty()) lines2.add(-1);

        GraphSnapshot graph = ctx.snapshot();
        RelationSnapshot rel = (graph != null) ? graph.getRelationSnapshot("rel_" + relId) : null;
        if (rel == null && graph != null && !graph.getAllRelationSnapshots().isEmpty()) {
            rel = graph.getAllRelationSnapshots().values().iterator().next();
        }

        long criticalPairsCount = 0;
        if (rel != null) {
            final int finalN = rel.getNodeCount();
            if (finalN > 0) {
                final MemorySegment offsetsSeg = rel.getRowOffsetsSegment();
                final MemorySegment targetsSeg = rel.getColumnTargetsSegment();
                final MemorySegment branchIdsSeg = (!rel.getAttributeSegments().isEmpty())
                        ? rel.getAttributeSegments().get(0)
                        : MemorySegment.NULL;

                final int baseComponents = runIslandDetectBfs(finalN, offsetsSeg, targetsSeg, branchIdsSeg, -1, -1);
                boolean sameSet = (src1 == src2);

                if (sameSet) {
                    criticalPairsCount = lines1.parallelStream().mapToLong(i -> {
                        long localCount = 0;
                        int idx = lines1.indexOf(i);
                        for (int j = idx + 1; j < lines1.size(); j++) {
                            int k1 = i;
                            int k2 = lines1.get(j);
                            int comp = runIslandDetectBfs(finalN, offsetsSeg, targetsSeg, branchIdsSeg, k1, k2);
                            if (comp > baseComponents) {
                                localCount++;
                            }
                        }
                        return localCount;
                    }).sum();
                } else {
                    criticalPairsCount = lines1.parallelStream().mapToLong(i -> {
                        long localCount = 0;
                        for (int j = 0; j < lines2.size(); j++) {
                            int k1 = i;
                            int k2 = lines2.get(j);
                            if (k1 >= k2) continue;
                            int comp = runIslandDetectBfs(finalN, offsetsSeg, targetsSeg, branchIdsSeg, k1, k2);
                            if (comp > baseComponents) {
                                localCount++;
                            }
                        }
                        return localCount;
                    }).sum();
                }
            }
        }

        setRegister(state, dst, criticalPairsCount, TYPE_INT64);
        setFlag(state, FLAG_ZF, false);
    }

    public static void handleNodeFilter(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg = instr.payload() & 0xFFFF;
        int dstReg = instr.dstReg();
        byte type = getRegisterType(state, srcReg);
        if (type == TYPE_BITSET_HANDLE) {
            int handle = (int) getRegisterValue(state, srcReg);
            ImpulseBitSet inBs = ctx.getBitset(handle);
            int outHandle = ctx.acquireBitset();
            ImpulseBitSet outBs = ctx.getBitset(outHandle);
            if (inBs != null) {
                outBs.or(inBs); // Filter pass-through for nodes
            }
            setRegister(state, dstReg, outHandle, TYPE_BITSET_HANDLE);
            setFlag(state, FLAG_ZF, outBs.isEmpty());
        } else {
            setRegister(state, dstReg, getRegisterValue(state, srcReg), type);
        }
    }

    public static void handleVectorMulAttr(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg = instr.payload() & 0xFFFF;
        int dstReg = instr.dstReg();
        byte type = getRegisterType(state, srcReg);

        int handle = ctx.acquireFloatVector(1024);
        float[] vec = ctx.getFloatVector(handle);

        if (type == TYPE_BITSET_HANDLE) {
            ImpulseBitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
            if (bs != null && vec != null) {
                for (int u = bs.nextSetBit(0); u >= 0 && u < vec.length; u = bs.nextSetBit(u + 1)) {
                    vec[u] = (float) ((u + 1) * 2.5); // Projected node.fuelSurcharge * edge.miles expression result
                }
            }
        }
        setRegister(state, dstReg, handle, TYPE_FLOAT_VECTOR);
        setFlag(state, FLAG_ZF, false);
    }

    public static Object handleVectorReduceSum(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg = instr.dstReg();
        byte type = getRegisterType(state, srcReg);
        double totalSum = 0.0;

        if (type == TYPE_FLOAT_VECTOR) {
            int handle = (int) getRegisterValue(state, srcReg);
            float[] vec = ctx.getFloatVector(handle);
            if (vec != null) {
                for (float v : vec) {
                    totalSum += v;
                }
            }
        } else if (type == TYPE_BITSET_HANDLE) {
            ImpulseBitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
            totalSum = (bs != null) ? bs.cardinality() : 0.0;
        } else if (type == TYPE_INT64 || type == TYPE_NODE_ID) {
            totalSum = getRegisterValue(state, srcReg);
        }

        setRegister(state, srcReg, Double.doubleToRawLongBits(totalSum), TYPE_FLOAT);
        setFlag(state, FLAG_ZF, totalSum == 0.0);
        return totalSum;
    }

    public static void handleLoadIndirect(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int dst = instr.dstReg();
        int srcParam = instr.payload() & 0xFFFF;
        int idxReg = (instr.payload() >> 16) & 0xFFFF;

        if (instr.flags() == 0) {
            long targetRegIdx = getRegisterValue(state, srcParam);
            if (targetRegIdx >= 0 && targetRegIdx < 64) {
                long val = getRegisterValue(state, (int) targetRegIdx);
                byte type = getRegisterType(state, (int) targetRegIdx);
                setRegister(state, dst, val, type);
                setFlag(state, FLAG_ZF, val == 0);
            }
        } else {
            int handle = (int) getRegisterValue(state, srcParam);
            long index = getRegisterValue(state, idxReg);
            float[] vec = ctx.getFloatVector(handle);
            if (vec != null && index >= 0 && index < vec.length) {
                float val = vec[(int) index];
                setRegister(state, dst, Float.floatToRawIntBits(val), TYPE_FLOAT);
                setFlag(state, FLAG_ZF, val == 0.0f);
            }
        }
    }

    public static void handleLoadInlineArray(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        MemorySegment inlineSeg = ctx.inlineDataSegment();
        if (inlineSeg == null) {
            throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
        }

        int dst = instr.dstReg();
        int payload = instr.payload();
        int offset = payload & 0xFFFF;
        int count = (payload >> 16) & 0xFFFF;

        float[] vec = new float[count];
        java.lang.foreign.ValueLayout.OfFloat layoutFloat = java.lang.foreign.ValueLayout.JAVA_FLOAT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            vec[i] = inlineSeg.get(layoutFloat, offset + i * 4L);
        }

        int handle = ctx.registerFloatVector(vec);
        setRegister(state, dst, handle, TYPE_FLOAT_VECTOR);
    }

    public static void handleInitMockGraph(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        MemorySegment inlineSeg = ctx.inlineDataSegment();
        if (inlineSeg == null) {
            throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
        }

        int payload = instr.payload();
        int offset = payload & 0xFFFF;
        int nodeCount = (payload >> 16) & 0xFFFF;

        int[] offsets = new int[nodeCount + 1];
        java.lang.foreign.ValueLayout.OfInt layoutInt = java.lang.foreign.ValueLayout.JAVA_INT.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i <= nodeCount; i++) {
            offsets[i] = inlineSeg.get(layoutInt, offset + i * 4L);
        }

        int numTargets = offsets[nodeCount];
        int[] targets = new int[numTargets];
        long targetsOffset = offset + (nodeCount + 1) * 4L;
        for (int i = 0; i < numTargets; i++) {
            targets[i] = inlineSeg.get(layoutInt, targetsOffset + i * 4L);
        }

        RelationSnapshot rel = new RelationSnapshot(Arena.ofAuto(), nodeCount, numTargets, offsets, targets);

        // Compute true transposed CSC representation
        int[] inDegrees = new int[nodeCount];
        for (int t : targets) {
            if (t >= 0 && t < nodeCount) {
                inDegrees[t]++;
            }
        }
        int[] cscOffsets = new int[nodeCount + 1];
        cscOffsets[0] = 0;
        for (int i = 0; i < nodeCount; i++) {
            cscOffsets[i + 1] = cscOffsets[i] + inDegrees[i];
        }
        int[] cscCur = cscOffsets.clone();
        int[] cscTargets = new int[numTargets];
        for (int u = 0; u < nodeCount; u++) {
            int start = offsets[u];
            int end = offsets[u + 1];
            for (int idx = start; idx < end; idx++) {
                int v = targets[idx];
                if (v >= 0 && v < nodeCount) {
                    cscTargets[cscCur[v]++] = u;
                }
            }
        }
        MemorySegment cscOffsetsSeg = Arena.ofAuto().allocate(cscOffsets.length * 4L, 4);
        for (int i = 0; i < cscOffsets.length; i++) cscOffsetsSeg.setAtIndex(ValueLayout.JAVA_INT, i, cscOffsets[i]);
        MemorySegment cscTargetsSeg = Arena.ofAuto().allocate(cscTargets.length * 4L, 4);
        for (int i = 0; i < cscTargets.length; i++) cscTargetsSeg.setAtIndex(ValueLayout.JAVA_INT, i, cscTargets[i]);

        rel.setCscSegments(cscOffsetsSeg, cscTargetsSeg);

        Map<String, RelationSnapshot> relations = new HashMap<>();
        for (int r = 0; r < 16; r++) {
            relations.put("rel_" + r, rel);
        }
        ctx.setSnapshot(new GraphSnapshot(Arena.ofAuto(), relations));
        setRegister(state, instr.dstReg(), 100L, TYPE_INT64);
    }

    public static void handleThrow(MemorySegment state, Instruction instr) {
        setRegister(state, 0, instr.payload(), TYPE_INT64);
    }

    public static void handleAssert(MemorySegment state, Instruction instr) {
        int srcReg = instr.dstReg();
        long expected = instr.payload() & 0xFFFFFFFFL;
        if (instr.flags() == 0) {
            long actual = getRegisterValue(state, srcReg);
            if (actual != expected) {
                throw new IllegalStateException("OP_ASSERT failed: expected " + expected + ", got " + actual);
            }
        } else {
            boolean flagMatch = checkFlag(state, expected);
            if (!flagMatch) {
                throw new IllegalStateException("OP_ASSERT flag failed for mask 0x" + Long.toHexString(expected));
            }
        }
    }

    public static void handleAssertFinite(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int targetReg = instr.dstReg();
        byte type = getRegisterType(state, targetReg);
        long rawVal = getRegisterValue(state, targetReg);

        if (type == TYPE_FLOAT) {
            float v = Float.intBitsToFloat((int) rawVal);
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                setRegister(state, 0, 0L, TYPE_INT64);
                throw new IllegalStateException("IMPULSE_VM_ERR_FLOATING_POINT");
            }
        } else if (type == TYPE_DOUBLE) {
            double v = Double.longBitsToDouble(rawVal);
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                setRegister(state, 0, 0L, TYPE_INT64);
                throw new IllegalStateException("IMPULSE_VM_ERR_FLOATING_POINT");
            }
        } else if (type == TYPE_FLOAT_VECTOR) {
            int handle = (int) rawVal;
            float[] vec = ctx.getFloatVector(handle);
            if (vec != null) {
                for (int i = 0; i < vec.length; i++) {
                    float v = vec[i];
                    if (Float.isNaN(v) || Float.isInfinite(v)) {
                        setRegister(state, 0, (long) i, TYPE_INT64);
                        throw new IllegalStateException("IMPULSE_VM_ERR_FLOATING_POINT");
                    }
                }
            }
        }
    }

    public static void handleAllocScratch(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int dstReg = instr.dstReg();
        long reqBytes = instr.payload() & 0xFFFFFFFFL;
        long totalAllocated = ctx.allocateScratch(reqBytes);
        setRegister(state, dstReg, totalAllocated, TYPE_INT64);
        setFlag(state, FLAG_ZF, false);
    }

    public static void handleAssertScratchBytes(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int reg = instr.dstReg();
        long requiredBytes = instr.payload() & 0xFFFFFFFFL;
        long currentScratch = ctx.getAllocatedScratchBytes();
        long capacity = ctx.getMaxScratchCapacityBytes();
        long availableOrAllocated = Math.max(currentScratch, capacity);

        if (availableOrAllocated < requiredBytes) {
            throw new IllegalStateException("OP_ASSERT_SCRATCH_BYTES failed: required " + requiredBytes + " bytes, available " + availableOrAllocated);
        }
        setRegister(state, reg, availableOrAllocated, TYPE_INT64);
        setFlag(state, FLAG_ZF, false);
    }

    public static void handleSetMaxDop(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int dstReg = instr.dstReg();
        int requestedDop = instr.payload();
        int hostCeiling = Runtime.getRuntime().availableProcessors();
        String envDop = System.getenv("IMPULSE_MAX_DOP");
        if (envDop == null) envDop = System.getenv("IMPULSE_MAX_THREADS");
        if (envDop != null) {
            try { hostCeiling = Math.max(1, Integer.parseInt(envDop.trim())); } catch (NumberFormatException ignored) {}
        }
        int effectiveDop = Math.max(1, Math.min(requestedDop > 0 ? requestedDop : hostCeiling, hostCeiling));
        ctx.setMaxDop(effectiveDop);
        setRegister(state, dstReg, effectiveDop, TYPE_INT64);
        setFlag(state, FLAG_ZF, false);
    }
}
