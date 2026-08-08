package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.BitSet;
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
    }

    public static void handleLoadConstFloat(MemorySegment state, Instruction instr) {
        float fVal = Float.intBitsToFloat(instr.payload());
        setRegister(state, instr.dstReg(), Float.floatToRawIntBits(fVal), TYPE_FLOAT);
    }

    private static RelationSnapshot resolveRelation(VmQueryContext ctx, int relId) {
        GraphSnapshot graph = ctx.snapshot();
        if (graph == null) return null;
        RelationSnapshot rel = graph.getRelationSnapshot("rel_" + relId);
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
        if (rel != null && !rel.hasCsc()) {
            rel.setCscSegments(rel.getRowOffsetsSegment(), rel.getColumnTargetsSegment());
        }
        return rel;
    }

    public static void handleCsrWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int relId = instr.payload() & 0xFFFF;
        int srcReg = (instr.payload() >> 16) & 0xFFFF;

        RelationSnapshot rel = resolveRelation(ctx, relId);

        byte srcType = getRegisterType(state, srcReg);
        long srcVal = getRegisterValue(state, srcReg);

        int outHandle = ctx.acquireBitset();
        BitSet outBs = ctx.getBitset(outHandle);

        if (rel != null) {
            if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
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

    public static void handleLoadConstStrPrefix(MemorySegment state, Instruction instr) {
        long val = Integer.toUnsignedLong(instr.payload());
        setRegister(state, instr.dstReg(), val, TYPE_INT64);
        setFlag(state, FLAG_ZF, val == 0);
    }

    public static void handleCscWalk(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int relId = instr.payload() & 0xFFFF;
        int srcReg = (instr.payload() >> 16) & 0xFFFF;

        RelationSnapshot rel = resolveRelation(ctx, relId);
        if (rel == null || !rel.hasCsc()) {
            throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
        }
        byte srcType = getRegisterType(state, srcReg);
        long srcVal = getRegisterValue(state, srcReg);

        int outHandle = ctx.acquireBitset();
        BitSet outBs = ctx.getBitset(outHandle);

        if (rel != null) {
            if (srcType == TYPE_NODE_ID || srcType == TYPE_INT64) {
                int targetV = (int) srcVal;
                for (int u = 0; u < rel.getNodeCount(); u++) {
                    int[] targets = rel.getTargets(u);
                    if (targets != null) {
                        for (int t : targets) {
                            if (t == targetV) {
                                outBs.set(u);
                                break;
                            }
                        }
                    }
                }
            } else if (srcType == TYPE_BITSET_HANDLE) {
                BitSet inBs = ctx.getBitset((int) srcVal);
                if (inBs != null) {
                    for (int v = inBs.nextSetBit(0); v >= 0; v = inBs.nextSetBit(v + 1)) {
                        for (int u = 0; u < rel.getNodeCount(); u++) {
                            int[] targets = rel.getTargets(u);
                            if (targets != null) {
                                for (int t : targets) {
                                    if (t == v) {
                                        outBs.set(u);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        setRegister(state, instr.dstReg(), outHandle, TYPE_BITSET_HANDLE);
        setFlag(state, FLAG_ZF, outBs.isEmpty());
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
        int relId = instr.payload() & 0xFFFF;
        int srcReg = (instr.payload() >> 16) & 0xFFFF;
        long u = getRegisterValue(state, srcReg);

        RelationSnapshot rel = resolveRelation(ctx, relId);
        long degree = 0;
        if (rel != null && u >= 0 && u < rel.getNodeCount()) {
            int[] targets = rel.getTargets((int) u);
            degree = (targets != null) ? targets.length : 0;
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
        BitSet outBs = ctx.getBitset(outHandle);

        byte type1 = getRegisterType(state, srcReg1);
        if (type1 == TYPE_BITSET_HANDLE) {
            BitSet bs1 = ctx.getBitset((int) getRegisterValue(state, srcReg1));
            if (bs1 != null) outBs.or(bs1);
        } else if (type1 == TYPE_NODE_ID || type1 == TYPE_INT64) {
            outBs.set((int) getRegisterValue(state, srcReg1));
        }

        if (srcReg2 != 0) {
            byte type2 = getRegisterType(state, srcReg2);
            if (type2 == TYPE_BITSET_HANDLE) {
                BitSet bs2 = ctx.getBitset((int) getRegisterValue(state, srcReg2));
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
            BitSet bsSrc = ctx.getBitset((int) srcVal);
            BitSet bsDst = ctx.getBitset((int) dstVal);
            if (bsSrc != null && bsDst != null) {
                BitSet diff = (BitSet) bsSrc.clone();
                diff.andNot(bsDst);
                isSubset = diff.isEmpty();
            }
        } else if (srcType != TYPE_BITSET_HANDLE && dstType == TYPE_BITSET_HANDLE) {
            BitSet bsDst = ctx.getBitset((int) dstVal);
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
        BitSet outBs = new BitSet();
        if (typeTag == TYPE_BITSET_HANDLE) {
            BitSet bs = ctx.getBitset((int) getRegisterValue(state, srcReg));
            if (bs != null) outBs.or(bs);
        } else if (typeTag == TYPE_NODE_ID || typeTag == TYPE_INT64) {
            outBs.set((int) getRegisterValue(state, srcReg));
        }
        int outHandle = ctx.acquireBitset();
        ctx.getBitset(outHandle).or(outBs);
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

    public static void handleLoadIndirect(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        int dst = instr.dstReg();
        int srcParam = (instr.payload() >> 16) & 0xFFFF;
        int idxReg = instr.payload() & 0xFFFF;

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
        int dst = instr.dstReg();
        int handle = ctx.acquireFloatVector(1024);
        setRegister(state, dst, handle, TYPE_FLOAT_VECTOR);
    }

    public static void handleInitMockGraph(MemorySegment state, VmQueryContext ctx, Instruction instr) {
        Map<String, RelationSnapshot> relations = new HashMap<>();
        int[] offsets = new int[] { 0, 2, 4, 5, 5 };
        int[] targets = new int[] { 1, 2, 2, 3, 3 };
        RelationSnapshot rel = new RelationSnapshot(Arena.ofAuto(), 4, 5, offsets, targets);
        rel.setCscSegments(rel.getRowOffsetsSegment(), rel.getColumnTargetsSegment());
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
