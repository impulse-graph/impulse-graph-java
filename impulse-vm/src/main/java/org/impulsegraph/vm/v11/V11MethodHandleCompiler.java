package org.impulsegraph.vm.v11;

import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;

/**
 * Straight-Line & Lexical-Morsel MethodHandle JIT Compiler for ImpulseVM v0.11.0.
 *
 * <p>Compiles a v0.11 program + bound snapshot into a single flat {@code (V11Workspace) -> void}
 * {@link MethodHandle} chain with all register indices, CSR/CSC {@link MemorySegment}s,
 * integer widths, and morsel bodies constant-folded for C2 inlining.
 */
public final class V11MethodHandleCompiler {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final MethodHandle MH_RESET;
    private static final MethodHandle MH_LOAD_CONST_F32;
    private static final MethodHandle MH_FSEED_IMM;
    private static final MethodHandle MH_INIT_DOMAIN_SPAN;
    private static final MethodHandle MH_SET_OP_UNION;
    private static final MethodHandle MH_VEC_INIT_F64;
    private static final MethodHandle MH_WALK;
    private static final MethodHandle MH_WALK_MORSEL;
    private static final MethodHandle MH_MORSEL_GATHER_F32;
    private static final MethodHandle MH_MORSEL_GATHER_F64;
    private static final MethodHandle MH_MORSEL_CMP_GT_SCALAR;
    private static final MethodHandle MH_MORSEL_B2F;
    private static final MethodHandle MH_MORSEL_COMMIT_FRONTIER;
    private static final MethodHandle MH_MORSEL_COMMIT_SCATTER_ADD;

    static {
        try {
            MH_RESET = LOOKUP.findVirtual(V11Workspace.class, "reset", MethodType.methodType(void.class));
            MH_LOAD_CONST_F32 = LOOKUP.findStatic(V11MethodHandleCompiler.class, "opLoadConstF32",
                    MethodType.methodType(void.class, int.class, long.class, boolean.class, V11Workspace.class));
            MH_FSEED_IMM = LOOKUP.findStatic(V11MethodHandleCompiler.class, "opFseedImm",
                    MethodType.methodType(void.class, int.class, int.class, int.class, int.class, boolean.class, V11Workspace.class));
            MH_INIT_DOMAIN_SPAN = LOOKUP.findStatic(V11MethodHandleCompiler.class, "opInitDomainSpan",
                    MethodType.methodType(void.class, int.class, int.class, int.class, V11Workspace.class));
            MH_SET_OP_UNION = LOOKUP.findStatic(V11MethodHandleCompiler.class, "opSetUnion",
                    MethodType.methodType(void.class, int.class, int.class, int.class, V11Workspace.class));
            MH_VEC_INIT_F64 = LOOKUP.findStatic(V11MethodHandleCompiler.class, "opVecInitF64",
                    MethodType.methodType(void.class, int.class, int.class, double.class, V11Workspace.class));
            MH_WALK = LOOKUP.findStatic(V11MethodHandleCompiler.class, "opWalk",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, int.class, int.class,
                            int.class, int.class, int.class, int.class, int.class, boolean.class, boolean.class, V11Workspace.class));
            MH_WALK_MORSEL = LOOKUP.findStatic(V11MethodHandleCompiler.class, "opWalkMorsel",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, int.class, int.class,
                            int.class, int.class, boolean.class, int.class, int.class, int.class, int.class,
                            MethodHandle.class, V11Workspace.class));
            MH_MORSEL_GATHER_F32 = LOOKUP.findStatic(V11MethodHandleCompiler.class, "morselGatherF32",
                    MethodType.methodType(void.class, MemorySegment.class, int.class, int.class, V11Workspace.class, int.class));
            MH_MORSEL_GATHER_F64 = LOOKUP.findStatic(V11MethodHandleCompiler.class, "morselGatherF64",
                    MethodType.methodType(void.class, MemorySegment.class, int.class, int.class, V11Workspace.class, int.class));
            MH_MORSEL_CMP_GT_SCALAR = LOOKUP.findStatic(V11MethodHandleCompiler.class, "morselCmpGtScalar",
                    MethodType.methodType(void.class, int.class, int.class, int.class, V11Workspace.class, int.class));
            MH_MORSEL_B2F = LOOKUP.findStatic(V11MethodHandleCompiler.class, "morselB2f",
                    MethodType.methodType(void.class, int.class, int.class, V11Workspace.class, int.class));
            MH_MORSEL_COMMIT_FRONTIER = LOOKUP.findStatic(V11MethodHandleCompiler.class, "morselCommitFrontier",
                    MethodType.methodType(void.class, int.class, int.class, int.class, boolean.class, V11Workspace.class, int.class));
            MH_MORSEL_COMMIT_SCATTER_ADD = LOOKUP.findStatic(V11MethodHandleCompiler.class, "morselCommitScatterAdd",
                    MethodType.methodType(void.class, int.class, int.class, int.class, int.class, boolean.class, V11Workspace.class, int.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private V11MethodHandleCompiler() {}

    /** Compile a straight-line / morsel v0.11 program into a {@code (V11Workspace) -> void} MethodHandle. */
    public static MethodHandle compile(long[] program, V11Interpreter.SnapshotBinding bind) {
        MethodHandle chain = MH_RESET;
        int pc = 0;
        while (pc < program.length) {
            long w = program[pc];
            int op = (int) (w & 0xFFL);
            int subop = (int) ((w >>> 8) & 0xFL);
            int flags = (int) ((w >>> 12) & 0xFL);
            int s1 = (int) ((w >>> 16) & 0xFL);
            int dst = (int) ((w >>> 20) & 0xFL);
            int s3 = (int) ((w >>> 24) & 0xFL);
            int s2 = (int) ((w >>> 28) & 0xFL);
            int imm32 = (int) (w >>> 32);
            int imm16Lo = imm32 & 0xFFFF;
            int imm16Hi = (imm32 >>> 16) & 0xFFFF;

            if (op == V11Interpreter.OP_HALT) {
                break;
            }
            MethodHandle step;
            switch (op) {
                case V11Interpreter.OP_LOAD_CONST -> {
                    double d = Float.intBitsToFloat(imm32);
                    step = MethodHandles.insertArguments(MH_LOAD_CONST_F32, 0, dst, Double.doubleToRawLongBits(d), d == 0.0);
                    pc++;
                }
                case V11Interpreter.OP_FSEED -> {
                    int domCard = bind.domainCards[imm16Hi];
                    step = MethodHandles.insertArguments(MH_FSEED_IMM, 0, dst, imm16Lo, imm16Hi, domCard, (flags & V11Interpreter.FLAG_ACCUM) != 0);
                    pc++;
                }
                case V11Interpreter.OP_INIT_DOMAIN_SPAN -> {
                    int domCard = bind.domainCards[imm16Lo];
                    step = MethodHandles.insertArguments(MH_INIT_DOMAIN_SPAN, 0, dst, imm16Lo, domCard);
                    pc++;
                }
                case V11Interpreter.OP_SET_OP -> {
                    step = MethodHandles.insertArguments(MH_SET_OP_UNION, 0, dst, s1, s2);
                    pc++;
                }
                case V11Interpreter.OP_VEC_INIT -> {
                    int len = ((subop & 8) != 0) ? bind.domainCards[imm16Lo] : imm32;
                    step = MethodHandles.insertArguments(MH_VEC_INIT_F64, 0, dst, len, 0.0);
                    pc++;
                }
                case V11Interpreter.OP_WALK -> {
                    RelationSnapshot rel = bind.rels[imm16Lo];
                    boolean rev = (subop == 1);
                    MemorySegment rowOff = rev ? rel.getCscRowOffsetsSegment() : rel.getRowOffsetsSegment();
                    MemorySegment colTgt = rev ? rel.getCscColumnTargetsSegment() : rel.getColumnTargetsSegment();
                    int edgeWidth = rel.getEdgeIndexWidth();
                    int nodeWidth = rev ? rel.getSrcNodeIdWidth() : rel.getNodeIdWidth();
                    int tgtDom = rev ? rel.getSrcDomainId() : rel.getTgtDomainId();
                    int tgtCard = bind.domainCards[tgtDom];
                    step = MethodHandles.insertArguments(MH_WALK, 0,
                            rowOff, colTgt, edgeWidth, nodeWidth,
                            dst, s1, s3, tgtDom, tgtCard,
                            (flags & V11Interpreter.FLAG_MASK) != 0,
                            (flags & V11Interpreter.FLAG_ACCUM) != 0);
                    pc++;
                }
                case V11Interpreter.OP_WALK_MORSEL -> {
                    int endPc = pc + imm16Hi;
                    RelationSnapshot rel = bind.rels[imm16Lo];
                    int tgtDom = rel.getTgtDomainId();
                    int tgtCard = bind.domainCards[tgtDom];
                    int clearFMask = 0;
                    int clearVMask = 0;
                    MethodHandle body = null;
                    for (int p = pc + 1; p < endPc; p++) {
                        long mw = program[p];
                        int mop = (int) (mw & 0xFFL);
                        int msub = (int) ((mw >>> 8) & 0xFL);
                        int mflg = (int) ((mw >>> 12) & 0xFL);
                        int ms1 = (int) ((mw >>> 16) & 0xFL);
                        int mdst = (int) ((mw >>> 20) & 0xFL);
                        int ms3 = (int) ((mw >>> 24) & 0xFL);
                        int ms2 = (int) ((mw >>> 28) & 0xFL);
                        int mimm = (int) (mw >>> 32);
                        int mHi = (mimm >>> 16) & 0xFFFF;
                        MethodHandle mstep = switch (mop) {
                            case V11Interpreter.OP_GATHER_EDGE_ATTR -> {
                                MemorySegment seg = rel.getAttributeSegments().get(mHi);
                                boolean isF32 = (seg.byteSize() == rel.getEdgeCount() * 4L);
                                yield MethodHandles.insertArguments(isF32 ? MH_MORSEL_GATHER_F32 : MH_MORSEL_GATHER_F64, 0, seg, mdst, ms1);
                            }
                            case V11Interpreter.OP_VEC_CMP ->
                                MethodHandles.insertArguments(MH_MORSEL_CMP_GT_SCALAR, 0, mdst, ms1, ms2);
                            case V11Interpreter.OP_SET_CONVERT ->
                                MethodHandles.insertArguments(MH_MORSEL_B2F, 0, mdst, ms1);
                            case V11Interpreter.OP_MORSEL_COMMIT -> {
                                if ((mflg & V11Interpreter.FLAG_ACCUM) == 0) {
                                    if (msub == 0) clearFMask |= (1 << mdst);
                                    else if (msub == 2) clearVMask |= (1 << mdst);
                                }
                                yield (msub == 0)
                                        ? MethodHandles.insertArguments(MH_MORSEL_COMMIT_FRONTIER, 0, mdst, ms1, ms3, (mflg & V11Interpreter.FLAG_MASK) != 0)
                                        : MethodHandles.insertArguments(MH_MORSEL_COMMIT_SCATTER_ADD, 0, mdst, ms1, ms2, ms3, (mflg & V11Interpreter.FLAG_MASK) != 0);
                            }
                            default -> throw new IllegalArgumentException("Unsupported morsel op: 0x" + Integer.toHexString(mop));
                        };
                        body = (body == null) ? mstep : MethodHandles.foldArguments(mstep, body);
                    }
                    step = MethodHandles.insertArguments(MH_WALK_MORSEL, 0,
                            rel.getRowOffsetsSegment(), rel.getColumnTargetsSegment(),
                            (int) rel.getEdgeIndexWidth(), (int) rel.getNodeIdWidth(),
                            s1, s3, (flags & V11Interpreter.FLAG_MASK) != 0,
                            tgtDom, tgtCard, clearFMask, clearVMask, body);
                    pc = endPc + 1;
                }
                default -> throw new IllegalArgumentException("Unsupported JIT op: 0x" + Integer.toHexString(op));
            }
            chain = MethodHandles.foldArguments(step, chain);
        }
        return chain;
    }

    private static void opLoadConstF32(int dst, long f64Bits, boolean isZero, V11Workspace ws) {
        ws.rRegs[dst] = f64Bits;
        ws.rValidMask |= (1 << dst);
        ws.zf = isZero;
    }

    private static void opFseedImm(int dst, int nodeId, int domId, int domCard, boolean accum, V11Workspace ws) {
        V11Frontier fd = ws.fRegs[dst];
        if (!accum) fd.resetEmpty(domId, domCard);
        fd.add(nodeId);
        ws.zf = false;
    }

    private static void opInitDomainSpan(int dst, int domId, int domCard, V11Workspace ws) {
        ws.fRegs[dst].initAllSpan(domId, domCard);
        ws.zf = (domCard == 0);
    }

    private static void opSetUnion(int dst, int s1, int s2, V11Workspace ws) {
        V11Frontier fd = ws.fRegs[dst];
        V11Frontier fa = ws.fRegs[s1];
        V11Frontier fb = ws.fRegs[s2];
        if (fd != fa) fd.copyFrom(fa);
        switch (fb.tier) {
            case V11Frontier.TIER0_INLINE -> {
                for (int i = 0; i < fb.inlineCount; i++) fd.add(fb.inlineIds[i]);
            }
            case V11Frontier.TIER1_PAGED -> {
                for (int s = 0; s < fb.dirtyPageCount; s++) {
                    int baseNode = fb.dirtyPageIds[s] << V11Frontier.PAGE_SHIFT;
                    int baseWord = s << 6;
                    for (int w = 0; w < V11Frontier.PAGE_WORDS; w++) {
                        long bits = fb.pageBits[baseWord + w];
                        while (bits != 0L) {
                            fd.add(baseNode + (w << 6) + Long.numberOfTrailingZeros(bits));
                            bits &= (bits - 1L);
                        }
                    }
                }
            }
            case V11Frontier.TIER2_DENSE -> {
                for (int w = 0; w < fb.denseWordCount; w++) {
                    long bits = fb.denseWords[w];
                    while (bits != 0L) {
                        fd.add((w << 6) + Long.numberOfTrailingZeros(bits));
                        bits &= (bits - 1L);
                    }
                }
            }
            default -> {}
        }
        ws.zf = fd.isEmpty();
    }

    private static void opVecInitF64(int dst, int len, double fill, V11Workspace ws) {
        Arrays.fill(ws.vF64[dst], 0, len, fill);
        ws.vLen[dst] = len;
    }

    private static void opWalk(MemorySegment rowOff, MemorySegment colTgt, int edgeWidth, int nodeWidth,
            int dst, int s1, int s3, int tgtDom, int tgtCard, boolean masked, boolean accum, V11Workspace ws) {
        V11Frontier fSrc = ws.fRegs[s1];
        V11Frontier fDst = ws.fRegs[dst];
        if (!accum) fDst.resetEmpty(tgtDom, tgtCard);
        if (fSrc.isEmpty()) {
            ws.zf = fDst.isEmpty();
            return;
        }
        V11Frontier fExcl = masked ? ws.fRegs[s3] : null;
        switch (fSrc.tier) {
            case V11Frontier.TIER0_INLINE -> {
                for (int i = 0; i < fSrc.inlineCount; i++) {
                    walkNode(rowOff, colTgt, edgeWidth, nodeWidth, fSrc.inlineIds[i], fExcl, fDst);
                }
            }
            case V11Frontier.TIER1_PAGED -> {
                for (int s = 0; s < fSrc.dirtyPageCount; s++) {
                    int baseNode = fSrc.dirtyPageIds[s] << V11Frontier.PAGE_SHIFT;
                    int baseWord = s << 6;
                    for (int w = 0; w < V11Frontier.PAGE_WORDS; w++) {
                        long bits = fSrc.pageBits[baseWord + w];
                        while (bits != 0L) {
                            walkNode(rowOff, colTgt, edgeWidth, nodeWidth, baseNode + (w << 6) + Long.numberOfTrailingZeros(bits), fExcl, fDst);
                            bits &= (bits - 1L);
                        }
                    }
                }
            }
            case V11Frontier.TIER2_DENSE -> {
                for (int w = 0; w < fSrc.denseWordCount; w++) {
                    long bits = fSrc.denseWords[w];
                    while (bits != 0L) {
                        walkNode(rowOff, colTgt, edgeWidth, nodeWidth, (w << 6) + Long.numberOfTrailingZeros(bits), fExcl, fDst);
                        bits &= (bits - 1L);
                    }
                }
            }
            case V11Frontier.TIER_ALL_SPAN -> {
                for (int u = 0; u < fSrc.domainCard; u++) {
                    walkNode(rowOff, colTgt, edgeWidth, nodeWidth, u, fExcl, fDst);
                }
            }
        }
        ws.zf = fDst.isEmpty();
    }

    private static void walkNode(MemorySegment rowOff, MemorySegment colTgt, int edgeWidth, int nodeWidth,
            int u, V11Frontier excl, V11Frontier dst) {
        long start = (edgeWidth == 8)
                ? rowOff.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) u * 8L)
                : Integer.toUnsignedLong(rowOff.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u * 4L));
        long end = (edgeWidth == 8)
                ? rowOff.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) (u + 1) * 8L)
                : Integer.toUnsignedLong(rowOff.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) * 4L));
        if (excl == null || excl.isEmpty()) {
            for (long e = start; e < end; e++) {
                dst.add(readNode(colTgt, nodeWidth, e));
            }
        } else {
            for (long e = start; e < end; e++) {
                int v = readNode(colTgt, nodeWidth, e);
                if (!excl.contains(v)) dst.add(v);
            }
        }
    }

    private static void opWalkMorsel(MemorySegment rowOff, MemorySegment colTgt, int edgeWidth, int nodeWidth,
            int s1, int s3, boolean masked, int tgtDom, int tgtCard, int clearFMask, int clearVMask,
            MethodHandle body, V11Workspace ws) throws Throwable {
        for (int d = 0; d < 8; d++) {
            if ((clearFMask & (1 << d)) != 0) ws.fRegs[d].resetEmpty(tgtDom, tgtCard);
            if ((clearVMask & (1 << d)) != 0) {
                Arrays.fill(ws.vF64[d], 0, tgtCard, 0.0);
                ws.vLen[d] = tgtCard;
            }
        }
        V11Frontier src = ws.fRegs[s1];
        if (src.isEmpty()) {
            ws.zf = true;
            return;
        }
        int[] mSrc = ws.vU32[8];
        int[] mTgt = ws.vU32[9];
        int[] mEdge = ws.vU32[10];
        int len = 0;
        if (src.tier == V11Frontier.TIER_ALL_SPAN) {
            for (int u = 0; u < src.domainCard; u++) {
                long s = (edgeWidth == 8)
                        ? rowOff.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) u * 8L)
                        : Integer.toUnsignedLong(rowOff.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u * 4L));
                long e = (edgeWidth == 8)
                        ? rowOff.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) (u + 1) * 8L)
                        : Integer.toUnsignedLong(rowOff.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) * 4L));
                for (long edge = s; edge < e; edge++) {
                    mSrc[len] = u;
                    mTgt[len] = readNode(colTgt, nodeWidth, edge);
                    mEdge[len] = (int) edge;
                    if (++len == V11Workspace.MORSEL_MAX) {
                        body.invokeExact(ws, len);
                        len = 0;
                    }
                }
            }
        }
        if (len > 0) {
            body.invokeExact(ws, len);
        }
    }

    private static void morselGatherF32(MemorySegment seg, int dst, int s1, V11Workspace ws, int len) {
        int[] idx = ws.vU32[s1];
        double[] out = ws.vF64[dst];
        for (int i = 0; i < len; i++) {
            out[i] = seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) idx[i] * 4L);
        }
    }

    private static void morselGatherF64(MemorySegment seg, int dst, int s1, V11Workspace ws, int len) {
        int[] idx = ws.vU32[s1];
        double[] out = ws.vF64[dst];
        for (int i = 0; i < len; i++) {
            out[i] = seg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) idx[i] * 8L);
        }
    }

    private static void morselCmpGtScalar(int dst, int s1, int s2, V11Workspace ws, int len) {
        double[] lhs = ws.vF64[s1];
        double rhs = Double.longBitsToDouble(ws.rRegs[s2]);
        long[] outBits = ws.vBool[dst];
        int words = (len + 63) >>> 6;
        Arrays.fill(outBits, 0, words, 0L);
        for (int i = 0; i < len; i++) {
            if (lhs[i] > rhs) outBits[i >>> 6] |= (1L << i);
        }
    }

    private static void morselB2f(int dst, int s1, V11Workspace ws, int len) {
        System.arraycopy(ws.vBool[s1], 0, ws.fRegs[dst].denseWords, 0, (len + 63) >>> 6);
    }

    private static void morselCommitFrontier(int dst, int s1, int s3, boolean masked, V11Workspace ws, int len) {
        long[] maskWords = ws.fRegs[masked ? s3 : 8].denseWords;
        int[] tgtIds = ws.vU32[s1];
        V11Frontier fDst = ws.fRegs[dst];
        int words = (len + 63) >>> 6;
        for (int wi = 0; wi < words; wi++) {
            long bits = maskWords[wi];
            while (bits != 0L) {
                fDst.add(tgtIds[(wi << 6) + Long.numberOfTrailingZeros(bits)]);
                bits &= (bits - 1L);
            }
        }
    }

    private static void morselCommitScatterAdd(int dst, int s1, int s2, int s3, boolean masked, V11Workspace ws, int len) {
        long[] maskWords = ws.fRegs[masked ? s3 : 8].denseWords;
        int[] tgtIds = ws.vU32[s1];
        double[] vals = ws.vF64[s2];
        double[] outVec = ws.vF64[dst];
        int words = (len + 63) >>> 6;
        for (int wi = 0; wi < words; wi++) {
            long bits = maskWords[wi];
            while (bits != 0L) {
                int lane = (wi << 6) + Long.numberOfTrailingZeros(bits);
                outVec[tgtIds[lane]] += vals[lane];
                bits &= (bits - 1L);
            }
        }
    }

    private static int readNode(MemorySegment seg, int width, long edgeIdx) {
        return switch (width) {
            case 2 -> Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, edgeIdx * 2L));
            case 8 -> (int) seg.get(ValueLayout.JAVA_LONG_UNALIGNED, edgeIdx * 8L);
            default -> seg.get(ValueLayout.JAVA_INT_UNALIGNED, edgeIdx * 4L);
        };
    }
}
