package org.impulsegraph.vm.v11;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.List;

/**
 * Minimal Vertical-Slice Interpreter for ImpulseVM v0.11.0 (Gut-Check Benchmark Prototype).
 *
 * <p>Executes 64-bit nibble-encoded v0.11 bytecode over a caller-owned {@link V11Workspace}
 * and direct off-heap {@link RelationSnapshot} CSR/CSC segments with zero steady-state heap allocations.
 */
public final class V11Interpreter {

    // Global Modifier Flags (high nibble of Byte 1)
    public static final int FLAG_MASK   = 0x1;
    public static final int FLAG_SCALAR = 0x2;
    public static final int FLAG_ACCUM  = 0x4;
    public static final int FLAG_EXT    = 0x8;

    // Opcodes (vertical slice)
    public static final int OP_HALT             = 0x00;
    public static final int OP_NOP              = 0x01;
    public static final int OP_LOAD_CONST       = 0x05;
    public static final int OP_SCALAR_INT_ALU   = 0x0C;
    public static final int OP_SCALAR_FP_ALU    = 0x0D;
    public static final int OP_JMP              = 0x0F;
    public static final int OP_JCC              = 0x10;
    public static final int OP_LOOP_DECR        = 0x11;
    public static final int OP_FSEED            = 0x20;
    public static final int OP_INIT_DOMAIN_SPAN = 0x21;
    public static final int OP_SET_OP           = 0x22;
    public static final int OP_SET_CARDINALITY  = 0x24;
    public static final int OP_SET_CONVERT      = 0x26;
    public static final int OP_STABLE_CHECK     = 0x27;
    public static final int OP_WALK             = 0x30;
    public static final int OP_SPMV             = 0x34;
    public static final int OP_WALK_MORSEL      = 0x35;
    public static final int OP_MORSEL_COMMIT    = 0x37;
    public static final int OP_MORSEL_END       = 0x38;
    public static final int OP_GATHER_EDGE_ATTR = 0x42;
    public static final int OP_VEC_CMP          = 0x45;
    public static final int OP_VEC_MATH_ARITH   = 0x48;
    public static final int OP_VEC_INIT         = 0x50;

    private V11Interpreter() {}

    /** Pre-bound snapshot tables (constructed once per snapshot, zero map lookup during execution). */
    public static final class SnapshotBinding {
        public final RelationSnapshot[] rels;
        public final int[] domainCards;
        public final int maxDomainCard;
        public final V11SnapshotView view;

        public SnapshotBinding(int[] domainCards) {
            this(domainCards, null);
        }

        public SnapshotBinding(int[] domainCards, V11SnapshotView view) {
            this.rels = new RelationSnapshot[0];
            this.domainCards = domainCards != null ? domainCards.clone() : new int[0];
            int maxCard = 64;
            for (int c : this.domainCards) {
                if (c > maxCard) maxCard = c;
            }
            this.maxDomainCard = maxCard;
            this.view = view;
        }

        public SnapshotBinding(ImpulseGraphSnapshot snap) {
            this.view = null;
            int maxRelId = -1;
            int maxDomId = 0;
            for (RelationSnapshot r : snap.getAllRelationSnapshots().values()) {
                if (r != null) {
                    maxRelId = Math.max(maxRelId, r.getRelationId());
                    maxDomId = Math.max(maxDomId, Math.max(r.getSrcDomainId(), r.getTgtDomainId()));
                }
            }
            this.rels = new RelationSnapshot[maxRelId + 1];
            this.domainCards = new int[maxDomId + 1];
            int maxCard = 64;
            for (RelationSnapshot r : snap.getAllRelationSnapshots().values()) {
                if (r != null && r.getRelationId() >= 0) {
                    this.rels[r.getRelationId()] = r;
                    int srcCard = r.getNodeCount();
                    int tgtCard = r.hasCsc() ? r.getCscNodeCount() : srcCard;
                    String srcName = snap.getDomainName(r.getSrcDomainId());
                    if (srcName != null) {
                        srcCard = Math.max(srcCard, (int) snap.getNodeCount(srcName));
                    }
                    String tgtName = snap.getDomainName(r.getTgtDomainId());
                    if (tgtName != null) {
                        tgtCard = Math.max(tgtCard, (int) snap.getNodeCount(tgtName));
                    }
                    this.domainCards[r.getSrcDomainId()] = Math.max(this.domainCards[r.getSrcDomainId()], srcCard);
                    this.domainCards[r.getTgtDomainId()] = Math.max(this.domainCards[r.getTgtDomainId()], tgtCard);
                    maxCard = Math.max(maxCard, Math.max(this.domainCards[r.getSrcDomainId()], this.domainCards[r.getTgtDomainId()]));
                }
            }
            this.maxDomainCard = maxCard;
        }
    }

    /** Encode a canonical 64-bit v0.11 instruction word. */
    public static long encode(int op, int flags, int subop, int dst, int s1, int s2, int s3, int imm32) {
        long b0 = op & 0xFFL;
        long b1 = (((flags & 0xFL) << 4) | (subop & 0xFL));
        long b2 = (((dst & 0xFL) << 4) | (s1 & 0xFL));
        long b3 = (((s2 & 0xFL) << 4) | (s3 & 0xFL));
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | ((imm32 & 0xFFFFFFFFL) << 32);
    }

    public static int packImm16(int lo, int hi) {
        return (lo & 0xFFFF) | ((hi & 0xFFFF) << 16);
    }

    /**
     * Validate and execute a v0.11 program using caller-owned workspace {@code ws}
     * (retaining pre-populated {@code constPool}, {@code params}, and {@code geometry}).
     *
     * <p>Returns the final status code ({@link V11IsaTables#IMPULSE_VM_OK} = 0 on normal HALT).
     */
    public static int executeValidated(long[] program, SnapshotBinding bind, V11Workspace ws, long maxSteps) {
        V11Validator.ValidationResult vr = V11Validator.validate(program);
        if (!vr.isOk()) {
            ws.pc = vr.badPc();
            ws.status = vr.status();
            return ws.status;
        }
        ws.pc = 0;
        ws.status = V11IsaTables.IMPULSE_VM_OK;
        final int n = program.length;
        long[] stepsRef = new long[]{0L};

        while (ws.status == V11IsaTables.IMPULSE_VM_OK) {
            if (stepsRef[0] >= maxSteps) {
                ws.status = V11IsaTables.IMPULSE_VM_ERR_GAS_EXHAUSTED;
                return ws.status;
            }
            if (ws.pc < 0 || ws.pc >= n) {
                ws.status = V11IsaTables.IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return ws.status;
            }
            stepsRef[0]++;
            long w = program[ws.pc];
            int p1 = V11Pillar1Ops.step(ws, bind, program, w);
            if (p1 == V11Pillar1Ops.RESULT_HALTED) {
                return ws.status;
            }
            if (p1 == V11Pillar1Ops.RESULT_STEPPED) {
                continue;
            }
            int p2 = V11Pillar2Ops.step(ws, bind, w);
            if (p2 == V11Pillar2Ops.RESULT_STEPPED) {
                continue;
            }
            int p3 = V11Pillar3Ops.step(ws, bind, program, w, stepsRef, maxSteps);
            if (p3 == V11Pillar3Ops.RESULT_STEPPED) {
                continue;
            }
            int p4 = V11Pillar4Ops.step(ws, bind, w);
            if (p4 == V11Pillar4Ops.RESULT_STEPPED) {
                continue;
            }
            ws.status = V11IsaTables.IMPULSE_VM_ERR_INVALID_OPCODE;
            return ws.status;
        }
        return ws.status;
    }

    /** Execute a v0.11 program to HALT using caller-owned workspace [ws]. */
    public static int execute(long[] program, SnapshotBinding bind, V11Workspace ws) {
        ws.reset();
        int pc = 0;
        final int n = program.length;

        while (pc >= 0 && pc < n) {
            final long w = program[pc];
            final int op = (int) (w & 0xFFL);
            final int subop = (int) ((w >>> 8) & 0xFL);
            final int flags = (int) ((w >>> 12) & 0xFL);
            final int s1 = (int) ((w >>> 16) & 0xFL);
            final int dst = (int) ((w >>> 20) & 0xFL);
            final int s3 = (int) ((w >>> 24) & 0xFL);
            final int s2 = (int) ((w >>> 28) & 0xFL);
            final int imm32 = (int) (w >>> 32);
            final int imm16Lo = imm32 & 0xFFFF;
            final int imm16Hi = (imm32 >>> 16) & 0xFFFF;

            switch (op) {
                case OP_HALT -> { return 0; }
                case OP_NOP -> pc++;

                case OP_LOAD_CONST -> {
                    switch (subop) {
                        case 0 -> { // .i32
                            ws.rRegs[dst] = imm32;
                            ws.rValidMask |= (1 << dst);
                            ws.zf = (imm32 == 0);
                            pc++;
                        }
                        case 1 -> { // .u32
                            ws.rRegs[dst] = imm32 & 0xFFFFFFFFL;
                            ws.rValidMask |= (1 << dst);
                            ws.zf = (imm32 == 0);
                            pc++;
                        }
                        case 2 -> { // .f32 -> f64 bits
                            double d = Float.intBitsToFloat(imm32);
                            ws.rRegs[dst] = Double.doubleToRawLongBits(d);
                            ws.rValidMask |= (1 << dst);
                            ws.zf = (d == 0.0);
                            pc++;
                        }
                        default -> throw new IllegalArgumentException("Unsupported LOAD_CONST subop: " + subop);
                    }
                }

                case OP_FSEED -> {
                    int domCard = bind.domainCards[imm16Hi];
                    V11Frontier fd = ws.fRegs[dst];
                    if ((flags & FLAG_ACCUM) == 0) {
                        fd.resetEmpty(imm16Hi, domCard);
                    }
                    if (subop == 1) {
                        fd.add(imm16Lo);
                    } else if (subop == 2) {
                        fd.add((int) ws.rRegs[s1]);
                    }
                    ws.zf = fd.isEmpty();
                    pc++;
                }

                case OP_INIT_DOMAIN_SPAN -> {
                    int domCard = bind.domainCards[imm16Lo];
                    ws.fRegs[dst].initAllSpan(imm16Lo, domCard);
                    ws.zf = (domCard == 0);
                    pc++;
                }

                case OP_SET_OP -> {
                    execSetOp(subop, ws.fRegs[dst], ws.fRegs[s1], ws.fRegs[s2]);
                    ws.zf = ws.fRegs[dst].isEmpty();
                    pc++;
                }

                case OP_SET_CARDINALITY -> {
                    int card = ws.fRegs[s1].cardinality();
                    ws.rRegs[dst] = card;
                    ws.rValidMask |= (1 << dst);
                    ws.zf = (card == 0);
                    pc++;
                }

                case OP_STABLE_CHECK -> {
                    V11Frontier fa = ws.fRegs[s1];
                    V11Frontier fb = ws.fRegs[s2];
                    ws.st = (fa.cardinality() == fb.cardinality());
                    pc++;
                }

                case OP_JMP -> pc += imm32;

                case OP_JCC -> {
                    boolean take = switch (subop) {
                        case 0 -> ws.zf;
                        case 1 -> !ws.zf;
                        case 2 -> ws.st;
                        case 3 -> !ws.st;
                        case 8 -> ws.fRegs[s1].isEmpty();
                        case 9 -> !ws.fRegs[s1].isEmpty();
                        default -> false;
                    };
                    pc = take ? (pc + imm32) : (pc + 1);
                }

                case OP_LOOP_DECR -> {
                    long next = ws.rRegs[dst] - 1L;
                    ws.rRegs[dst] = next;
                    ws.zf = (next == 0L);
                    pc = (next > 0L) ? (pc + imm32) : (pc + 1);
                }

                case OP_WALK -> {
                    RelationSnapshot rel = bind.rels[imm16Lo];
                    boolean rev = (subop == 1);
                    int tgtDom = rev ? rel.getSrcDomainId() : rel.getTgtDomainId();
                    int tgtCard = bind.domainCards[tgtDom];
                    V11Frontier fSrc = ws.fRegs[s1];
                    V11Frontier fDst = ws.fRegs[dst];
                    V11Frontier fExcl = ((flags & FLAG_MASK) != 0) ? ws.fRegs[s3] : null;
                    if ((flags & FLAG_ACCUM) == 0) {
                        fDst.resetEmpty(tgtDom, tgtCard);
                    }
                    execWalk(rel, rev, fSrc, fExcl, fDst);
                    ws.zf = fDst.isEmpty();
                    pc++;
                }

                case OP_SPMV -> {
                    RelationSnapshot rel = bind.rels[imm16Lo];
                    int tgtDom = rel.getTgtDomainId();
                    int tgtCard = bind.domainCards[tgtDom];
                    double[] srcVec = ws.vF64[s1];
                    double[] dstVec = ws.vF64[dst];
                    ws.vLen[dst] = tgtCard;
                    execSpmvPull(rel, tgtCard, srcVec, dstVec);
                    pc++;
                }

                case OP_VEC_INIT -> {
                    boolean domMode = (subop & 8) != 0;
                    int len = domMode ? bind.domainCards[imm16Lo] : imm32;
                    double fill = ((flags & FLAG_SCALAR) != 0) ? Double.longBitsToDouble(ws.rRegs[s2]) : 0.0;
                    Arrays.fill(ws.vF64[dst], 0, len, fill);
                    ws.vLen[dst] = len;
                    pc++;
                }

                case OP_WALK_MORSEL -> {
                    int endPc = pc + imm16Hi;
                    RelationSnapshot rel = bind.rels[imm16Lo];
                    V11Frontier fSrc = ws.fRegs[s1];
                    V11Frontier fExcl = ((flags & FLAG_MASK) != 0) ? ws.fRegs[s3] : null;
                    execWalkMorsel(program, pc, endPc, rel, bind, fSrc, fExcl, ws);
                    pc = endPc + 1;
                }

                default -> throw new IllegalArgumentException("Unsupported v0.11 opcode: 0x" + Integer.toHexString(op));
            }
        }
        return 0;
    }

    private static void execWalk(RelationSnapshot rel, boolean rev, V11Frontier src, V11Frontier excl, V11Frontier dst) {
        if (src.isEmpty()) return;
        MemorySegment rowOff = rev ? rel.getCscRowOffsetsSegment() : rel.getRowOffsetsSegment();
        MemorySegment colTgt = rev ? rel.getCscColumnTargetsSegment() : rel.getColumnTargetsSegment();
        int edgeWidth = rel.getEdgeIndexWidth();
        int nodeWidth = rev ? rel.getSrcNodeIdWidth() : rel.getNodeIdWidth();

        switch (src.tier) {
            case V11Frontier.TIER0_INLINE -> {
                for (int i = 0; i < src.inlineCount; i++) {
                    walkSingleNode(rowOff, colTgt, edgeWidth, nodeWidth, src.inlineIds[i], excl, dst);
                }
            }
            case V11Frontier.TIER1_PAGED -> {
                for (int s = 0; s < src.dirtyPageCount; s++) {
                    int baseNode = src.dirtyPageIds[s] << V11Frontier.PAGE_SHIFT;
                    int baseWord = s << 6;
                    for (int w = 0; w < V11Frontier.PAGE_WORDS; w++) {
                        long bits = src.pageBits[baseWord + w];
                        while (bits != 0L) {
                            int u = baseNode + (w << 6) + Long.numberOfTrailingZeros(bits);
                            walkSingleNode(rowOff, colTgt, edgeWidth, nodeWidth, u, excl, dst);
                            bits &= (bits - 1L);
                        }
                    }
                }
            }
            case V11Frontier.TIER2_DENSE -> {
                for (int w = 0; w < src.denseWordCount; w++) {
                    long bits = src.denseWords[w];
                    while (bits != 0L) {
                        int u = (w << 6) + Long.numberOfTrailingZeros(bits);
                        walkSingleNode(rowOff, colTgt, edgeWidth, nodeWidth, u, excl, dst);
                        bits &= (bits - 1L);
                    }
                }
            }
            case V11Frontier.TIER_ALL_SPAN -> {
                for (int u = 0; u < src.domainCard; u++) {
                    walkSingleNode(rowOff, colTgt, edgeWidth, nodeWidth, u, excl, dst);
                }
            }
        }
    }

    private static void walkSingleNode(MemorySegment rowOff, MemorySegment colTgt, int edgeWidth, int nodeWidth,
            int u, V11Frontier excl, V11Frontier dst) {
        long start = readEdgeIdx(rowOff, edgeWidth, u);
        long end   = readEdgeIdx(rowOff, edgeWidth, u + 1);
        if (excl == null || excl.isEmpty()) {
            for (long e = start; e < end; e++) {
                dst.add(readNode(colTgt, nodeWidth, e));
            }
        } else {
            for (long e = start; e < end; e++) {
                int v = readNode(colTgt, nodeWidth, e);
                if (!excl.contains(v)) {
                    dst.add(v);
                }
            }
        }
    }

    private static void execSpmvPull(RelationSnapshot rel, int tgtCard, double[] srcVec, double[] dstVec) {
        MemorySegment cscOff = rel.getCscRowOffsetsSegment();
        MemorySegment cscSrc = rel.getCscColumnTargetsSegment();
        int edgeWidth = rel.getEdgeIndexWidth();
        int srcWidth = rel.getSrcNodeIdWidth();
        for (int v = 0; v < tgtCard; v++) {
            long start = readEdgeIdx(cscOff, edgeWidth, v);
            long end   = readEdgeIdx(cscOff, edgeWidth, v + 1);
            double sum = 0.0;
            for (long e = start; e < end; e++) {
                int u = readNode(cscSrc, srcWidth, e);
                sum += srcVec[u];
            }
            dstVec[v] = sum;
        }
    }

    private static void execSetOp(int subop, V11Frontier dst, V11Frontier a, V11Frontier b) {
        if (subop == 0) { // UNION
            if (dst != a) {
                dst.copyFrom(a);
            }
            addAll(b, dst);
        } else if (subop == 2) { // DIFF (a \ b)
            dst.resetEmpty(a.domainId, a.domainCard);
            if (a.tier == V11Frontier.TIER0_INLINE) {
                for (int i = 0; i < a.inlineCount; i++) {
                    int u = a.inlineIds[i];
                    if (!b.contains(u)) dst.add(u);
                }
            } else if (a.tier == V11Frontier.TIER1_PAGED) {
                for (int s = 0; s < a.dirtyPageCount; s++) {
                    int baseNode = a.dirtyPageIds[s] << V11Frontier.PAGE_SHIFT;
                    int baseWord = s << 6;
                    for (int w = 0; w < V11Frontier.PAGE_WORDS; w++) {
                        long bits = a.pageBits[baseWord + w];
                        while (bits != 0L) {
                            int u = baseNode + (w << 6) + Long.numberOfTrailingZeros(bits);
                            if (!b.contains(u)) dst.add(u);
                            bits &= (bits - 1L);
                        }
                    }
                }
            } else if (a.tier == V11Frontier.TIER2_DENSE) {
                for (int w = 0; w < a.denseWordCount; w++) {
                    long bits = a.denseWords[w];
                    while (bits != 0L) {
                        int u = (w << 6) + Long.numberOfTrailingZeros(bits);
                        if (!b.contains(u)) dst.add(u);
                        bits &= (bits - 1L);
                    }
                }
            }
        }
    }

    private static void addAll(V11Frontier src, V11Frontier dst) {
        switch (src.tier) {
            case V11Frontier.TIER_EMPTY -> {}
            case V11Frontier.TIER0_INLINE -> {
                for (int i = 0; i < src.inlineCount; i++) dst.add(src.inlineIds[i]);
            }
            case V11Frontier.TIER1_PAGED -> {
                for (int s = 0; s < src.dirtyPageCount; s++) {
                    int baseNode = src.dirtyPageIds[s] << V11Frontier.PAGE_SHIFT;
                    int baseWord = s << 6;
                    for (int w = 0; w < V11Frontier.PAGE_WORDS; w++) {
                        long bits = src.pageBits[baseWord + w];
                        while (bits != 0L) {
                            dst.add(baseNode + (w << 6) + Long.numberOfTrailingZeros(bits));
                            bits &= (bits - 1L);
                        }
                    }
                }
            }
            case V11Frontier.TIER2_DENSE -> {
                for (int w = 0; w < src.denseWordCount; w++) {
                    long bits = src.denseWords[w];
                    while (bits != 0L) {
                        dst.add((w << 6) + Long.numberOfTrailingZeros(bits));
                        bits &= (bits - 1L);
                    }
                }
            }
            case V11Frontier.TIER_ALL_SPAN -> dst.initAllSpan(src.domainId, src.domainCard);
        }
    }

    /** Lexical Morsel Loop: streams CSR edges in chunks <= 2048 through the morsel body with zero allocation. */
    private static void execWalkMorsel(long[] program, int hdrPc, int endPc, RelationSnapshot rel,
            SnapshotBinding bind, V11Frontier src, V11Frontier excl, V11Workspace ws) {
        // 1. Pre-clear all non-.accum outer commit sinks once at WALK_MORSEL entry
        int tgtDom = rel.getTgtDomainId();
        int tgtCard = bind.domainCards[tgtDom];
        for (int p = hdrPc + 1; p < endPc; p++) {
            long cw = program[p];
            if ((cw & 0xFFL) == OP_MORSEL_COMMIT) {
                int cSub = (int) ((cw >>> 8) & 0xFL);
                int cFlags = (int) ((cw >>> 12) & 0xFL);
                int cDst = (int) ((cw >>> 20) & 0xFL);
                if ((cFlags & FLAG_ACCUM) == 0) {
                    if (cSub == 0) {
                        ws.fRegs[cDst].resetEmpty(tgtDom, tgtCard);
                    } else if (cSub == 2) {
                        Arrays.fill(ws.vF64[cDst], 0, tgtCard, 0.0);
                        ws.vLen[cDst] = tgtCard;
                    }
                }
            }
        }
        if (src.isEmpty()) {
            ws.zf = true;
            return;
        }

        MemorySegment rowOff = rel.getRowOffsetsSegment();
        MemorySegment colTgt = rel.getColumnTargetsSegment();
        int edgeWidth = rel.getEdgeIndexWidth();
        int nodeWidth = rel.getNodeIdWidth();
        int[] mSrc  = ws.morselSrc;
        int[] mTgt  = ws.morselTgt;
        int[] mEdge = ws.morselEdge;
        int len = 0;

        switch (src.tier) {
            case V11Frontier.TIER0_INLINE -> {
                for (int i = 0; i < src.inlineCount; i++) {
                    int u = src.inlineIds[i];
                    long s = readEdgeIdx(rowOff, edgeWidth, u);
                    long e = readEdgeIdx(rowOff, edgeWidth, u + 1);
                    for (long edge = s; edge < e; edge++) {
                        int v = readNode(colTgt, nodeWidth, edge);
                        if (excl != null && excl.contains(v)) continue;
                        mSrc[len] = u; mTgt[len] = v; mEdge[len] = (int) edge;
                        if (++len == V11Workspace.MORSEL_MAX) {
                            runMorselSlice(program, hdrPc, endPc, rel, ws, len);
                            len = 0;
                        }
                    }
                }
            }
            case V11Frontier.TIER_ALL_SPAN -> {
                for (int u = 0; u < src.domainCard; u++) {
                    long s = readEdgeIdx(rowOff, edgeWidth, u);
                    long e = readEdgeIdx(rowOff, edgeWidth, u + 1);
                    for (long edge = s; edge < e; edge++) {
                        int v = readNode(colTgt, nodeWidth, edge);
                        if (excl != null && excl.contains(v)) continue;
                        mSrc[len] = u; mTgt[len] = v; mEdge[len] = (int) edge;
                        if (++len == V11Workspace.MORSEL_MAX) {
                            runMorselSlice(program, hdrPc, endPc, rel, ws, len);
                            len = 0;
                        }
                    }
                }
            }
            case V11Frontier.TIER2_DENSE -> {
                for (int w = 0; w < src.denseWordCount; w++) {
                    long bits = src.denseWords[w];
                    while (bits != 0L) {
                        int u = (w << 6) + Long.numberOfTrailingZeros(bits);
                        long s = readEdgeIdx(rowOff, edgeWidth, u);
                        long e = readEdgeIdx(rowOff, edgeWidth, u + 1);
                        for (long edge = s; edge < e; edge++) {
                            int v = readNode(colTgt, nodeWidth, edge);
                            if (excl != null && excl.contains(v)) continue;
                            mSrc[len] = u; mTgt[len] = v; mEdge[len] = (int) edge;
                            if (++len == V11Workspace.MORSEL_MAX) {
                                runMorselSlice(program, hdrPc, endPc, rel, ws, len);
                                len = 0;
                            }
                        }
                        bits &= (bits - 1L);
                    }
                }
            }
            default -> {}
        }
        if (len > 0) {
            runMorselSlice(program, hdrPc, endPc, rel, ws, len);
        }
    }

    private static void runMorselSlice(long[] program, int hdrPc, int endPc, RelationSnapshot rel,
            V11Workspace ws, int len) {
        // Bind M0 (v8=src), M1 (v9=tgt), M2 (v10=edgeIdx)
        System.arraycopy(ws.morselSrc, 0, ws.vU32[8], 0, len);
        System.arraycopy(ws.morselTgt, 0, ws.vU32[9], 0, len);
        System.arraycopy(ws.morselEdge, 0, ws.vU32[10], 0, len);
        ws.vLen[8] = len; ws.vLen[9] = len; ws.vLen[10] = len;
        int words = (len + 63) >>> 6;
        // MF0 (fRegs[8]) = all lanes active in dense words
        long[] mf0 = ws.fRegs[8].denseWords;
        Arrays.fill(mf0, 0, words, -1L);
        int rem = len & 63;
        if (rem != 0) mf0[words - 1] = (1L << rem) - 1L;

        List<MemorySegment> edgeAttrs = rel.getAttributeSegments();
        for (int p = hdrPc + 1; p < endPc; p++) {
            long w = program[p];
            int op = (int) (w & 0xFFL);
            int subop = (int) ((w >>> 8) & 0xFL);
            int flags = (int) ((w >>> 12) & 0xFL);
            int s1 = (int) ((w >>> 16) & 0xFL);
            int dst = (int) ((w >>> 20) & 0xFL);
            int s3 = (int) ((w >>> 24) & 0xFL);
            int s2 = (int) ((w >>> 28) & 0xFL);
            int imm32 = (int) (w >>> 32);
            int imm16Hi = (imm32 >>> 16) & 0xFFFF;

            switch (op) {
                case OP_GATHER_EDGE_ATTR -> {
                    MemorySegment attrSeg = edgeAttrs.get(imm16Hi);
                    int[] idx = ws.vU32[s1];
                    double[] out = ws.vF64[dst];
                    boolean isF32 = (attrSeg.byteSize() == rel.getEdgeCount() * 4L);
                    if (isF32) {
                        for (int i = 0; i < len; i++) {
                            out[i] = attrSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) idx[i] * 4L);
                        }
                    } else {
                        for (int i = 0; i < len; i++) {
                            out[i] = attrSeg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) idx[i] * 8L);
                        }
                    }
                    ws.vLen[dst] = len;
                }
                case OP_VEC_CMP -> {
                    double[] lhs = ws.vF64[s1];
                    double rhsScalar = Double.longBitsToDouble(ws.rRegs[s2]);
                    long[] outBits = ws.vBool[dst];
                    Arrays.fill(outBits, 0, words, 0L);
                    for (int i = 0; i < len; i++) {
                        double x = lhs[i];
                        boolean ok = switch (subop) {
                            case 2 -> x < rhsScalar;
                            case 3 -> x <= rhsScalar;
                            case 4 -> x > rhsScalar;
                            case 5 -> x >= rhsScalar;
                            default -> x == rhsScalar;
                        };
                        if (ok) outBits[i >>> 6] |= (1L << i);
                    }
                    ws.vLen[dst] = len;
                }
                case OP_SET_CONVERT -> { // .b2f into MF[dst]
                    System.arraycopy(ws.vBool[s1], 0, ws.fRegs[dst].denseWords, 0, words);
                }
                case OP_VEC_MATH_ARITH -> {
                    double[] lhs = ws.vF64[s1];
                    double rhsScalar = Double.longBitsToDouble(ws.rRegs[s2]);
                    double[] out = ws.vF64[dst];
                    if (subop == 2) { // mul
                        for (int i = 0; i < len; i++) out[i] = lhs[i] * rhsScalar;
                    } else if (subop == 0) {
                        for (int i = 0; i < len; i++) out[i] = lhs[i] + rhsScalar;
                    }
                    ws.vLen[dst] = len;
                }
                case OP_MORSEL_COMMIT -> {
                    long[] maskWords = ((flags & FLAG_MASK) != 0) ? ws.fRegs[s3].denseWords : mf0;
                    int[] tgtIds = ws.vU32[s1];
                    if (subop == 0) { // .yield_frontier
                        V11Frontier fDst = ws.fRegs[dst];
                        for (int wi = 0; wi < words; wi++) {
                            long bits = maskWords[wi];
                            while (bits != 0L) {
                                int lane = (wi << 6) + Long.numberOfTrailingZeros(bits);
                                fDst.add(tgtIds[lane]);
                                bits &= (bits - 1L);
                            }
                        }
                    } else if (subop == 2) { // .scatter_add
                        double[] vals = ws.vF64[s2];
                        double[] outVec = ws.vF64[dst];
                        for (int wi = 0; wi < words; wi++) {
                            long bits = maskWords[wi];
                            while (bits != 0L) {
                                int lane = (wi << 6) + Long.numberOfTrailingZeros(bits);
                                outVec[tgtIds[lane]] += vals[lane];
                                bits &= (bits - 1L);
                            }
                        }
                    }
                }
                default -> {}
            }
        }
    }

    private static long readEdgeIdx(MemorySegment seg, int width, int nodeId) {
        return (width == 8)
                ? seg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) nodeId * 8L)
                : Integer.toUnsignedLong(seg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) nodeId * 4L));
    }

    private static int readNode(MemorySegment seg, int width, long edgeIdx) {
        return switch (width) {
            case 2 -> Short.toUnsignedInt(seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, edgeIdx * 2L));
            case 8 -> (int) seg.get(ValueLayout.JAVA_LONG_UNALIGNED, edgeIdx * 8L);
            default -> seg.get(ValueLayout.JAVA_INT_UNALIGNED, edgeIdx * 4L);
        };
    }
}
