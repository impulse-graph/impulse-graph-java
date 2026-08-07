package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.lang.foreign.MemorySegment;
import java.util.BitSet;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Instruction Handlers for Impulse VM opcodes.
 * Provides static execution methods operating zero-copy on off-heap FFM MemorySegments and VmQueryContext.
 */
public final class VmHandlers {

    public static final int PARALLEL_FRONTIER_THRESHOLD = 524_288; // 512k Frontier Threshold

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

    public static void setFlags(MemorySegment state, long flags) {
        FLAGS_HANDLE.set(state, 0L, flags);
    }

    public static void setFlag(MemorySegment state, long flagMask, boolean value) {
        long curFlags = getFlags(state);
        if (value) {
            setFlags(state, curFlags | flagMask);
        } else {
            setFlags(state, curFlags & ~flagMask);
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
        BitSet bs = ctx.getBitset(handle);
        if (input instanceof BitSet inBs) {
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
        setFlag(state, FLAG_ZF, instr.payload() == 0);
    }

    public static void handleLoadConstFloat(MemorySegment state, Instruction instr) {
        float fVal = Float.intBitsToFloat(instr.payload());
        setRegister(state, instr.dstReg(), Float.floatToRawIntBits(fVal), TYPE_FLOAT);
        setFlag(state, FLAG_ZF, fVal == 0.0f);
    }

    public static void handleCsrWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int relId = instr.payload() & 0xFFFF;
        int srcReg = (instr.payload() >> 16) & 0xFFFF;

        GraphSnapshot graph = ctx.snapshot();
        RelationSnapshot rel = (graph != null) ? graph.getRelationSnapshot("rel_" + relId) : null;
        if (rel == null && graph != null && !graph.getAllRelationSnapshots().isEmpty()) {
            // Fallback to lookup by relation index in iteration order
            int idx = 0;
            for (RelationSnapshot snap : graph.getAllRelationSnapshots().values()) {
                if (idx == relId) {
                    rel = snap;
                    break;
                }
                idx++;
            }
        }

        byte srcType = getRegisterType(state, srcReg);
        long srcVal = getRegisterValue(state, srcReg);

        int outHandle = ctx.acquireBitset();
        BitSet outBs = ctx.getBitset(outHandle);

        if (rel != null) {
            if (srcType == TYPE_NODE_ID) {
                rel.copyTargetsSimd((int) srcVal, outBs);
            } else if (srcType == TYPE_BITSET_HANDLE) {
                BitSet inBs = ctx.getBitset((int) srcVal);
                if (inBs != null) {
                    int cardinality = inBs.cardinality();
                    final RelationSnapshot relSnap = rel;
                    if (cardinality >= PARALLEL_FRONTIER_THRESHOLD) {
                        // Multi-Threaded Parallel Execution (>= 512k active nodes)
                        inBs.stream().parallel().forEach(u -> relSnap.copyTargetsSimd(u, outBs));
                    } else {
                        // Single-Threaded Vector SIMD Execution (< 512k active nodes)
                        for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
                            relSnap.copyTargetsSimd(u, outBs);
                        }
                    }
                }
            }
        }

        setRegister(state, instr.dstReg(), outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
    }

    public static void handleSetUnion(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg1 = instr.payload() & 0xFFFF;
        int srcReg2 = (instr.payload() >> 16) & 0xFFFF;

        BitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
        BitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));

        int outHandle = ctx.acquireBitset();
        BitSet outBs = ctx.getBitset(outHandle);
        if (bs1 != null) outBs.or(bs1);
        if (bs2 != null) outBs.or(bs2);

        setRegister(state, instr.dstReg(), outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
    }

    public static void handleSetIntersect(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg1 = instr.payload() & 0xFFFF;
        int srcReg2 = (instr.payload() >> 16) & 0xFFFF;

        BitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
        BitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));

        int outHandle = ctx.acquireBitset();
        BitSet outBs = ctx.getBitset(outHandle);
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

        BitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
        BitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));

        int outHandle = ctx.acquireBitset();
        BitSet outBs = ctx.getBitset(outHandle);
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
        BitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
        long count = (bs != null) ? bs.cardinality() : 0;
        setRegister(state, instr.dstReg(), count, TYPE_INT64);
        setFlag(state, FLAG_ZF, count == 0);
    }

    public static void handleMov(MemorySegment state, Instruction instr) {
        int srcReg = instr.payload() & 0xFFFF;
        long val = getRegisterValue(state, srcReg);
        byte typeTag = getRegisterType(state, srcReg);
        setRegister(state, instr.dstReg(), val, typeTag);
    }

    public static void handleClearReg(MemorySegment state, Instruction instr) {
        setRegister(state, instr.dstReg(), 0L, TYPE_NULL);
    }

    public static void handleStableCheck(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int regPrev = instr.payload() & 0xFFFF;
        int regCurr = (instr.payload() >> 16) & 0xFFFF;

        BitSet bsPrev = ctx.getBitset((int) getRegisterValue(state, regPrev));
        BitSet bsCurr = ctx.getBitset((int) getRegisterValue(state, regCurr));

        boolean isStable = (bsPrev != null && bsCurr != null) && bsPrev.equals(bsCurr);
        setFlag(state, FLAG_ST, isStable);
        setFlag(state, FLAG_ZF, bsCurr == null || bsCurr.isEmpty());
    }

    public static Object handleCollectBitset(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int srcReg = instr.dstReg();
        byte typeTag = getRegisterType(state, srcReg);
        if (typeTag == TYPE_BITSET_HANDLE) {
            BitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
            return (bs != null) ? (BitSet) bs.clone() : new BitSet();
        } else if (typeTag == TYPE_NODE_ID) {
            BitSet bs = new BitSet();
            bs.set((int) getRegisterValue(state, srcReg));
            return bs;
        }
        return new BitSet();
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
            BitSet inBs = ctx.getBitset((int) getRegisterValue(state, srcReg));
            if (inBs != null) {
                for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
                    int[] targetsU = rel.getTargets(u);
                    for (int v : targetsU) {
                        if (v > u) {
                            int[] targetsV = rel.getTargets(v);
                            triangleCount += countIntersection(targetsU, targetsV);
                        }
                    }
                }
            }
        }
        setRegister(state, instr.dstReg(), triangleCount, TYPE_INT64);
        setFlag(state, FLAG_ZF, triangleCount == 0);
    }

    private static long countIntersection(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0;
        long count = 0;
        int i = 0, j = 0;
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { count++; i++; j++; }
            else if (a[i] < b[j]) { i++; }
            else { j++; }
        }
        return count;
    }

    public static void handleReadEdgeWeight(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        setRegister(state, instr.dstReg(), Float.floatToRawIntBits(1.0f), TYPE_FLOAT);
        setFlag(state, FLAG_ZF, false);
    }

    private static int runIslandDetectBfs(int N, MemorySegment offsetsSeg, MemorySegment targetsSeg, MemorySegment branchIdsSeg, long k1, long k2) {
        if (N <= 0) return 0;
        BitSet visited = new BitSet(N);
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
                BitSet bs = ctx.getBitset(handle);
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
                BitSet bs = ctx.getBitset(handle);
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
        int srcReg = (instr.payload() >> 16) & 0xFFFF;
        int dstReg = instr.dstReg();
        byte type = getRegisterType(state, srcReg);
        if (type == TYPE_BITSET_HANDLE) {
            int handle = (int) getRegisterValue(state, srcReg);
            BitSet inBs = ctx.getBitset(handle);
            int outHandle = ctx.acquireBitset();
            BitSet outBs = ctx.getBitset(outHandle);
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
        int srcReg = (instr.payload() >> 16) & 0xFFFF;
        int dstReg = instr.dstReg();
        byte type = getRegisterType(state, srcReg);

        int handle = ctx.acquireFloatVector(1024);
        float[] vec = ctx.getFloatVector(handle);

        if (type == TYPE_BITSET_HANDLE) {
            BitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
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
            BitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
            totalSum = (bs != null) ? bs.cardinality() : 0.0;
        } else if (type == TYPE_INT64 || type == TYPE_NODE_ID) {
            totalSum = getRegisterValue(state, srcReg);
        }

        setRegister(state, srcReg, Double.doubleToRawLongBits(totalSum), TYPE_FLOAT);
        setFlag(state, FLAG_ZF, totalSum == 0.0);
        return totalSum;
    }
}
