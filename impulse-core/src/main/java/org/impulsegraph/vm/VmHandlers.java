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
                int[] targets = rel.getTargets((int) srcVal);
                if (targets != null) {
                    for (int t : targets) outBs.set(t);
                }
            } else if (srcType == TYPE_BITSET_HANDLE) {
                BitSet inBs = ctx.getBitset((int) srcVal);
                if (inBs != null) {
                    for (int u = inBs.nextSetBit(0); u >= 0; u = inBs.nextSetBit(u + 1)) {
                        int[] targets = rel.getTargets(u);
                        if (targets != null) {
                            for (int t : targets) outBs.set(t);
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
}
