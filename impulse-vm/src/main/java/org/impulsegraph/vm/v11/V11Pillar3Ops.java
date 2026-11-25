package org.impulsegraph.vm.v11;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.impulsegraph.vm.v11.V11IsaTables.*;

/**
 * Normative Pillar 3 Evaluator ({@code 0x30}–{@code 0x3B}, 11 opcodes / 71 subops) for ImpulseVM v0.11.0.
 *
 * <p>Bit-identical to {@code V11_eval_p3} in {@code impulse-ocaml}:
 * {@code OP_WALK}, {@code OP_WALK_FILTERED}, {@code OP_DEGREE}, {@code OP_SPMV},
 * {@code OP_WALK_MORSEL}, {@code OP_FILTER_MORSEL}, {@code OP_MORSEL_COMMIT}, {@code OP_MORSEL_END},
 * {@code OP_SAMPLE_NEIGHBORS}, {@code OP_HAS_TOPOLOGY}, and {@code OP_GRAPH_KERNEL}.
 */
public final class V11Pillar3Ops {

    public static final int RESULT_UNHANDLED = 0;
    public static final int RESULT_STEPPED   = 1;

    private V11Pillar3Ops() {}

    public static int step(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, long[] program, long w,
            long[] stepsRef, long maxSteps) {
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
            case OP_WALK -> {
                execWalk(ws, bind, subop, flags, dst, s1, s3, imm16Lo, imm16Hi);
                return RESULT_STEPPED;
            }
            case OP_WALK_FILTERED -> {
                execWalkFiltered(ws, bind, subop, flags, dst, s1, s2, s3, imm16Lo, imm16Hi);
                return RESULT_STEPPED;
            }
            case OP_DEGREE -> {
                execDegree(ws, bind, subop, flags, dst, s1, s3, imm16Lo);
                return RESULT_STEPPED;
            }
            case OP_SPMV -> {
                execSpmv(ws, bind, subop, flags, dst, s1, s2, s3, imm16Lo, imm16Hi);
                return RESULT_STEPPED;
            }
            case OP_WALK_MORSEL -> {
                execWalkMorsel(ws, bind, program, subop, flags, s1, s3, imm16Lo, imm16Hi, stepsRef, maxSteps);
                return RESULT_STEPPED;
            }
            case OP_FILTER_MORSEL -> {
                execFilterMorsel(ws, bind, program, s1, imm16Hi, stepsRef, maxSteps);
                return RESULT_STEPPED;
            }
            case OP_MORSEL_COMMIT, OP_MORSEL_END -> {
                ws.status = IMPULSE_VM_ERR_VALIDATION_FAILED;
                return RESULT_STEPPED;
            }
            case OP_SAMPLE_NEIGHBORS -> {
                execSampleNeighbors(ws, bind, subop, dst, s1, s2, imm16Lo, imm16Hi);
                return RESULT_STEPPED;
            }
            case OP_HAS_TOPOLOGY -> {
                execHasTopology(ws, bind, subop, dst, imm16Lo, imm16Hi);
                return RESULT_STEPPED;
            }
            case OP_GRAPH_KERNEL -> {
                execGraphKernel(ws, bind, subop, flags, dst, s3, imm16Lo, imm16Hi);
                return RESULT_STEPPED;
            }
            default -> {
                return RESULT_UNHANDLED;
            }
        }
    }

    private static V11SnapshotView getView(V11Interpreter.SnapshotBinding bind, V11Workspace ws) {
        if (bind == null || bind.view == null) {
            ws.status = IMPULSE_VM_ERR_NULL_SNAPSHOT;
            return null;
        }
        return bind.view;
    }

    private static V11SnapshotView.RelView getRel(V11Interpreter.SnapshotBinding bind, V11Workspace ws, int rid) {
        V11SnapshotView view = getView(bind, ws);
        if (view == null) return null;
        if (rid < 0 || rid >= view.relations.length) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return null;
        }
        return view.relations[rid];
    }

    private static V11Frontier getF1Bit(V11Workspace ws, int idx) {
        if (!ws.isFInitialized(idx) || ws.fRegs[idx].multiLane) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return null;
        }
        return ws.fRegs[idx];
    }

    private static void setScalarFlags(V11Workspace ws, boolean valid, long val) {
        if (!valid) {
            ws.zf = true;
            ws.nf = true;
        } else {
            ws.zf = (val == 0L);
            ws.nf = false;
        }
    }

    private static double ieeeMinF64(double a, double b) {
        if (a == 0.0 && b == 0.0) {
            return Double.longBitsToDouble(Double.doubleToRawLongBits(a) | Double.doubleToRawLongBits(b));
        }
        return (a < b) ? a : b;
    }

    private static double ieeeMaxF64(double a, double b) {
        if (a == 0.0 && b == 0.0) {
            return Double.longBitsToDouble(Double.doubleToRawLongBits(a) & Double.doubleToRawLongBits(b));
        }
        return (a > b) ? a : b;
    }

    private static int[] toSortedInts(Set<Integer> s) {
        int[] arr = new int[s.size()];
        int i = 0;
        for (int v : s) arr[i++] = v;
        Arrays.sort(arr);
        return arr;
    }

    private static Set<Integer> toIntSet(V11Frontier f) {
        Set<Integer> s = new TreeSet<>();
        for (int u : f.toSortedArray()) s.add(u);
        return s;
    }

    private static List<int[]>[] buildRevAdj(V11SnapshotView view, V11SnapshotView.RelView rel) {
        int nTgt = (int) view.domainCards[rel.tgtDom];
        @SuppressWarnings("unchecked")
        List<int[]>[] rev = new List[nTgt];
        for (int i = 0; i < nTgt; i++) rev[i] = new ArrayList<>();
        int nSrc = rel.rowOffsets.length - 1;
        for (int u = 0; u < nSrc; u++) {
            for (int e = rel.rowOffsets[u]; e < rel.rowOffsets[u + 1]; e++) {
                int v = (int) rel.colTargets[e];
                if (v >= 0 && v < nTgt) {
                    rev[v].add(new int[]{u, e});
                }
            }
        }
        return rev;
    }

    private static Set<Integer> fwdStep(V11SnapshotView.RelView rel, Set<Integer> src, Set<Integer> excl) {
        Set<Integer> out = new TreeSet<>();
        for (int u : src) {
            if (u >= 0 && u + 1 < rel.rowOffsets.length) {
                for (int e = rel.rowOffsets[u]; e < rel.rowOffsets[u + 1]; e++) {
                    int v = (int) rel.colTargets[e];
                    if (!excl.contains(v)) out.add(v);
                }
            }
        }
        return out;
    }

    private static Set<Integer> revStep(List<int[]>[] revAdj, Set<Integer> tgt, Set<Integer> excl) {
        Set<Integer> out = new TreeSet<>();
        for (int v : tgt) {
            if (v >= 0 && v < revAdj.length) {
                for (int[] pair : revAdj[v]) {
                    int u = pair[0];
                    if (!excl.contains(u)) out.add(u);
                }
            }
        }
        return out;
    }

    // --- 0x30 OP_WALK ---
    private static void execWalk(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop, int flags,
            int dst, int s1, int s3, int relId, int imm16Hi) {
        V11SnapshotView.RelView rel = getRel(bind, ws, relId);
        if (rel == null) return;
        V11Frontier fSrc = getF1Bit(ws, s1);
        if (fSrc == null) return;
        V11SnapshotView view = bind.view;

        boolean endo = (rel.srcDom == rel.tgtDom);
        int inDom = (subop == 1) ? rel.tgtDom : rel.srcDom;
        int outDom = (subop == 1) ? rel.srcDom : rel.tgtDom;
        if (subop >= 2 && !endo) {
            ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
            return;
        }
        if (fSrc.domainId != inDom) {
            ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
            return;
        }
        if (subop == 7 && imm16Hi < 1) {
            ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
            return;
        }

        Set<Integer> sExcl = Set.of();
        if ((flags & FLAG_MASKED_S3) != 0) {
            V11Frontier fe = getF1Bit(ws, s3);
            if (fe == null) return;
            if (fe.domainId != outDom) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
            sExcl = toIntSet(fe);
        }
        Set<Integer> sAccum = Set.of();
        if ((flags & FLAG_ACCUM) != 0) {
            V11Frontier fd = getF1Bit(ws, dst);
            if (fd == null) return;
            if (fd.domainId != outDom) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
            sAccum = toIntSet(fd);
        }

        Set<Integer> sSrc = toIntSet(fSrc);
        List<int[]>[] revAdj = (subop == 1 || subop == 2 || subop == 5 || subop == 6) ? buildRevAdj(view, rel) : null;

        Set<Integer> outS;
        boolean stFlag = ws.st;
        switch (subop) {
            case 0 -> outS = fwdStep(rel, sSrc, sExcl);
            case 1 -> outS = revStep(revAdj, sSrc, sExcl);
            case 2 -> {
                outS = fwdStep(rel, sSrc, sExcl);
                outS.addAll(revStep(revAdj, sSrc, sExcl));
            }
            case 3, 5 -> { // fixpoint_plus
                Set<Integer> visited = new TreeSet<>();
                Set<Integer> frontier = (subop == 3) ? fwdStep(rel, sSrc, sExcl) : revStep(revAdj, sSrc, sExcl);
                while (!frontier.isEmpty()) {
                    visited.addAll(frontier);
                    Set<Integer> excl = new HashSet<>(sExcl);
                    excl.addAll(visited);
                    frontier = (subop == 3) ? fwdStep(rel, frontier, excl) : revStep(revAdj, frontier, excl);
                }
                outS = visited;
                stFlag = true;
            }
            case 4, 6 -> { // fixpoint_star
                Set<Integer> visited = new TreeSet<>(sSrc);
                visited.removeAll(sExcl);
                Set<Integer> frontier = new TreeSet<>(visited);
                while (!frontier.isEmpty()) {
                    Set<Integer> excl = new HashSet<>(sExcl);
                    excl.addAll(visited);
                    Set<Integer> next = (subop == 4) ? fwdStep(rel, frontier, excl) : revStep(revAdj, frontier, excl);
                    visited.addAll(next);
                    frontier = next;
                }
                outS = visited;
                stFlag = true;
            }
            case 7 -> { // fwd_bounded k
                int k = imm16Hi;
                Set<Integer> visited = new TreeSet<>();
                Set<Integer> frontier = sSrc;
                boolean converged = false;
                for (int hop = 0; hop < k && !converged; hop++) {
                    Set<Integer> excl = new HashSet<>(sExcl);
                    excl.addAll(visited);
                    Set<Integer> next = fwdStep(rel, frontier, excl);
                    if (next.isEmpty()) {
                        converged = true;
                    } else {
                        visited.addAll(next);
                        frontier = next;
                    }
                }
                outS = visited;
                stFlag = converged;
            }
            default -> {
                ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
                return;
            }
        }

        Set<Integer> finalS = new TreeSet<>(sAccum);
        finalS.addAll(outS);
        int outCard = (int) view.domainCards[outDom];
        ws.fRegs[dst].initFromValidatedIds(outDom, outCard, toSortedInts(finalS), ws.geometry, false);
        ws.setFInitialized(dst, true);
        ws.zf = finalS.isEmpty();
        ws.st = stFlag;
        ws.pc++;
    }

    // --- 0x31 OP_WALK_FILTERED ---
    private static boolean evalEdgePred(V11Vector col, int e, int cmpOp, boolean rhsValid, long rhsBits, int rhsTy) {
        if (cmpOp == 6) { // is_not_null
            return col.isValidAt(e);
        }
        if (cmpOp == 7) { // is_null
            return !col.isValidAt(e);
        }
        if (!rhsValid || !col.isValidAt(e)) {
            return false;
        }
        return switch (col.elemTag) {
            case V11Vector.TAG_F64 -> {
                double lv = col.f64Data[e];
                double rv = (rhsTy == SCALAR_I64 || rhsTy == SCALAR_U64)
                        ? (double) rhsBits
                        : Double.longBitsToDouble(rhsBits);
                if (Double.isNaN(lv) || Double.isNaN(rv)) yield false;
                yield switch (cmpOp) {
                    case 0 -> lv == rv;
                    case 1 -> lv != rv;
                    case 2 -> lv < rv;
                    case 3 -> lv <= rv;
                    case 4 -> lv > rv;
                    case 5 -> lv >= rv;
                    default -> false;
                };
            }
            case V11Vector.TAG_I64 -> {
                int c = Long.compare(col.i64Data[e], rhsBits);
                yield switch (cmpOp) {
                    case 0 -> c == 0;
                    case 1 -> c != 0;
                    case 2 -> c < 0;
                    case 3 -> c <= 0;
                    case 4 -> c > 0;
                    case 5 -> c >= 0;
                    default -> false;
                };
            }
            case V11Vector.TAG_U64 -> {
                int c = Long.compareUnsigned(col.i64Data[e], rhsBits);
                yield switch (cmpOp) {
                    case 0 -> c == 0;
                    case 1 -> c != 0;
                    case 2 -> c < 0;
                    case 3 -> c <= 0;
                    case 4 -> c > 0;
                    case 5 -> c >= 0;
                    default -> false;
                };
            }
            default -> false;
        };
    }

    private static void execWalkFiltered(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop, int flags,
            int dst, int s1, int s2, int s3, int relId, int attrId) {
        V11SnapshotView.RelView rel = getRel(bind, ws, relId);
        if (rel == null) return;
        V11Frontier fSrc = getF1Bit(ws, s1);
        if (fSrc == null) return;
        V11SnapshotView view = bind.view;

        boolean isRev = (subop >= 8);
        int cmpOp = subop & 7;
        int inDom = isRev ? rel.tgtDom : rel.srcDom;
        int outDom = isRev ? rel.srcDom : rel.tgtDom;
        if (fSrc.domainId != inDom) {
            ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
            return;
        }
        V11Vector col = rel.edgeAttrs.get(attrId);
        if (col == null) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }

        Set<Integer> sExcl = Set.of();
        if ((flags & FLAG_MASKED_S3) != 0) {
            V11Frontier fe = getF1Bit(ws, s3);
            if (fe == null) return;
            if (fe.domainId != outDom) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
            sExcl = toIntSet(fe);
        }
        Set<Integer> sAccum = Set.of();
        if ((flags & FLAG_ACCUM) != 0) {
            V11Frontier fd = getF1Bit(ws, dst);
            if (fd == null) return;
            if (fd.domainId != outDom) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
            sAccum = toIntSet(fd);
        }

        boolean rhsValid = (cmpOp <= 5) && ws.isRValid(s2);
        long rhsBits = rhsValid ? ws.rRegs[s2] : 0L;
        int rhsTy = rhsValid ? ws.readRType(s2) : SCALAR_NULL_UNSET;

        Set<Integer> out = new TreeSet<>();
        if (!(cmpOp <= 5 && !rhsValid)) {
            int[] srcIds = fSrc.toSortedArray();
            if (!isRev) {
                for (int u : srcIds) {
                    for (int e = rel.rowOffsets[u]; e < rel.rowOffsets[u + 1]; e++) {
                        int v = (int) rel.colTargets[e];
                        if (!sExcl.contains(v) && evalEdgePred(col, e, cmpOp, rhsValid, rhsBits, rhsTy)) {
                            out.add(v);
                        }
                    }
                }
            } else {
                List<int[]>[] revAdj = buildRevAdj(view, rel);
                for (int v : srcIds) {
                    for (int[] pair : revAdj[v]) {
                        int u = pair[0];
                        int e = pair[1];
                        if (!sExcl.contains(u) && evalEdgePred(col, e, cmpOp, rhsValid, rhsBits, rhsTy)) {
                            out.add(u);
                        }
                    }
                }
            }
        }

        Set<Integer> finalS = new TreeSet<>(sAccum);
        finalS.addAll(out);
        int outCard = (int) view.domainCards[outDom];
        ws.fRegs[dst].initFromValidatedIds(outDom, outCard, toSortedInts(finalS), ws.geometry, false);
        ws.setFInitialized(dst, true);
        ws.zf = finalS.isEmpty();
        ws.pc++;
    }

    // --- 0x33 OP_DEGREE ---
    private static int[] computeInDegs(V11SnapshotView view, V11SnapshotView.RelView rel) {
        int nTgt = (int) view.domainCards[rel.tgtDom];
        int[] d = new int[nTgt];
        for (long v64 : rel.colTargets) {
            int v = (int) v64;
            if (v >= 0 && v < nTgt) d[v]++;
        }
        return d;
    }

    private static void execDegree(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop, int flags,
            int dst, int s1, int s3, int relId) {
        V11SnapshotView.RelView rel = getRel(bind, ws, relId);
        if (rel == null) return;
        V11SnapshotView view = bind.view;

        switch (subop) {
            case 0 -> { // .sum_fwd_r
                V11Frontier fSrc = getF1Bit(ws, s1);
                if (fSrc == null) return;
                if (fSrc.domainId != rel.srcDom) {
                    ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                    return;
                }
                long sum = 0L;
                for (int u : fSrc.toSortedArray()) sum += (rel.rowOffsets[u + 1] - rel.rowOffsets[u]);
                ws.writeR(dst, SCALAR_U64, sum);
                setScalarFlags(ws, true, sum);
                ws.pc++;
            }
            case 1 -> { // .sum_rev_r
                V11Frontier fTgt = getF1Bit(ws, s1);
                if (fTgt == null) return;
                if (fTgt.domainId != rel.tgtDom) {
                    ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                    return;
                }
                int[] inD = computeInDegs(view, rel);
                long sum = 0L;
                for (int v : fTgt.toSortedArray()) sum += inD[v];
                ws.writeR(dst, SCALAR_U64, sum);
                setScalarFlags(ws, true, sum);
                ws.pc++;
            }
            case 2 -> { // .vec_fwd_v
                Set<Integer> maskSet = null;
                if ((flags & FLAG_MASKED_S3) != 0) {
                    V11Frontier fm = getF1Bit(ws, s3);
                    if (fm == null) return;
                    if (fm.domainId != rel.srcDom) {
                        ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                        return;
                    }
                    maskSet = toIntSet(fm);
                }
                int n = (int) view.domainCards[rel.srcDom];
                long[] vals = new long[n];
                long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
                for (int u = 0; u < n; u++) {
                    if (maskSet == null || maskSet.contains(u)) {
                        vals[u] = rel.rowOffsets[u + 1] - rel.rowOffsets[u];
                        valid[u >>> 6] |= (1L << (u & 63));
                    }
                }
                ws.vVectors[dst] = V11Vector.ofU64(vals, valid);
                ws.pc++;
            }
            case 3 -> { // .vec_rev_v
                Set<Integer> maskSet = null;
                if ((flags & FLAG_MASKED_S3) != 0) {
                    V11Frontier fm = getF1Bit(ws, s3);
                    if (fm == null) return;
                    if (fm.domainId != rel.tgtDom) {
                        ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                        return;
                    }
                    maskSet = toIntSet(fm);
                }
                int[] inD = computeInDegs(view, rel);
                int n = inD.length;
                long[] vals = new long[n];
                long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
                for (int v = 0; v < n; v++) {
                    if (maskSet == null || maskSet.contains(v)) {
                        vals[v] = inD[v];
                        valid[v >>> 6] |= (1L << (v & 63));
                    }
                }
                ws.vVectors[dst] = V11Vector.ofU64(vals, valid);
                ws.pc++;
            }
            case 4 -> { // .node_fwd_r
                if (!ws.isRValid(s1)) {
                    ws.writeRNull(dst);
                    setScalarFlags(ws, false, 0L);
                    ws.pc++;
                    return;
                }
                long u64 = ws.rRegs[s1];
                long dcard = view.domainCards[rel.srcDom];
                if (u64 < 0L || Long.compareUnsigned(u64, dcard) >= 0) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                int u = (int) u64;
                long deg = rel.rowOffsets[u + 1] - rel.rowOffsets[u];
                ws.writeR(dst, SCALAR_U64, deg);
                setScalarFlags(ws, true, deg);
                ws.pc++;
            }
            case 5 -> { // .node_rev_r
                if (!ws.isRValid(s1)) {
                    ws.writeRNull(dst);
                    setScalarFlags(ws, false, 0L);
                    ws.pc++;
                    return;
                }
                long v64 = ws.rRegs[s1];
                long dcard = view.domainCards[rel.tgtDom];
                if (v64 < 0L || Long.compareUnsigned(v64, dcard) >= 0) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                int[] inD = computeInDegs(view, rel);
                long deg = inD[(int) v64];
                ws.writeR(dst, SCALAR_U64, deg);
                setScalarFlags(ws, true, deg);
                ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    // --- 0x34 OP_SPMV ---
    private static void execSpmv(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop, int flags,
            int dst, int s1, int s2, int s3, int relId, int attrId) {
        V11SnapshotView.RelView rel = getRel(bind, ws, relId);
        if (rel == null) return;
        V11Frontier fSrc = getF1Bit(ws, s1);
        if (fSrc == null) return;
        V11SnapshotView view = bind.view;

        boolean isRev = (subop == 1 || subop == 3);
        int inDom = isRev ? rel.tgtDom : rel.srcDom;
        int outDom = isRev ? rel.srcDom : rel.tgtDom;
        if (fSrc.domainId != inDom) {
            ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
            return;
        }

        Set<Integer> tgtMask = null;
        if ((flags & FLAG_MASKED_S3) != 0) {
            V11Frontier fm = getF1Bit(ws, s3);
            if (fm == null) return;
            if (fm.domainId != outDom) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
            tgtMask = toIntSet(fm);
        }

        V11Vector weightCol = null;
        if (attrId != 0xFFFF) {
            weightCol = rel.edgeAttrs.get(attrId);
            if (weightCol == null || (weightCol.elemTag != V11Vector.TAG_F64
                    && weightCol.elemTag != V11Vector.TAG_I64
                    && weightCol.elemTag != V11Vector.TAG_U64)) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }

        int nIn = (int) view.domainCards[inDom];
        int nOut = (int) view.domainCards[outDom];
        List<int[]> edges = new ArrayList<>();
        int[] srcIds = fSrc.toSortedArray();
        if (!isRev) {
            for (int u : srcIds) {
                for (int e = rel.rowOffsets[u]; e < rel.rowOffsets[u + 1]; e++) {
                    int v = (int) rel.colTargets[e];
                    if (tgtMask == null || tgtMask.contains(v)) {
                        edges.add(new int[]{u, v, e});
                    }
                }
            }
        } else {
            List<int[]>[] revAdj = buildRevAdj(view, rel);
            for (int v : srcIds) {
                for (int[] pair : revAdj[v]) {
                    int u = pair[0];
                    int e = pair[1];
                    if (tgtMask == null || tgtMask.contains(u)) {
                        edges.add(new int[]{v, u, e});
                    }
                }
            }
        }

        if (subop == 7) { // .fwd_first_src
            long[] out = new long[nOut];
            long[] valid = new long[Math.max(1, (nOut + 63) >>> 6)];
            int hits = 0;
            for (int[] ed : edges) {
                int u = ed[0], v = ed[1];
                long u64 = u & 0xFFFFFFFFL;
                boolean has = ((valid[v >>> 6] >>> (v & 63)) & 1L) != 0L;
                if (!has) {
                    out[v] = u64;
                    valid[v >>> 6] |= (1L << (v & 63));
                    hits++;
                } else if (Long.compareUnsigned(u64, out[v]) < 0) {
                    out[v] = u64;
                }
            }
            ws.vVectors[dst] = V11Vector.ofU64(out, valid);
            ws.zf = (hits == 0);
            ws.pc++;
            return;
        }

        // RHS snapshot (Jacobi-safe when dst == s2)
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        boolean scalarValid = scalarRhs && ws.isRValid(s2);
        double scalarVal = scalarValid ? Double.longBitsToDouble(ws.rRegs[s2]) : Double.NaN;
        double[] rhsSnap = null;
        boolean[] rhsSnapValid = null;
        if (!scalarRhs) {
            V11Vector rv = ws.vVectors[s2];
            if (rv == null || (rv.elemTag != V11Vector.TAG_F64 && rv.elemTag != V11Vector.TAG_I64 && rv.elemTag != V11Vector.TAG_U64)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (rv.length < nIn) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            rhsSnap = new double[rv.length];
            rhsSnapValid = new boolean[rv.length];
            for (int i = 0; i < rv.length; i++) {
                if (rv.isValidAt(i)) {
                    rhsSnapValid[i] = true;
                    rhsSnap[i] = (rv.elemTag == V11Vector.TAG_F64) ? rv.f64Data[i] : (double) rv.i64Data[i];
                }
            }
        }

        if (subop == 8) { // .fwd_argmin_plus
            double[] bestCost = new double[nOut];
            long[] bestSrc = new long[nOut];
            long[] valid = new long[Math.max(1, (nOut + 63) >>> 6)];
            int hits = 0;
            for (int[] ed : edges) {
                int u = ed[0], v = ed[1], e = ed[2];
                if (scalarRhs ? !scalarValid : !rhsSnapValid[u]) continue;
                double du = scalarRhs ? scalarVal : rhsSnap[u];
                if (weightCol != null && !weightCol.isValidAt(e)) continue;
                double w = (weightCol == null) ? 1.0
                        : (weightCol.elemTag == V11Vector.TAG_F64 ? weightCol.f64Data[e] : (double) weightCol.i64Data[e]);
                double c = du + w;
                if (Double.isNaN(c)) continue;
                long u64 = u & 0xFFFFFFFFL;
                boolean has = ((valid[v >>> 6] >>> (v & 63)) & 1L) != 0L;
                if (!has) {
                    bestCost[v] = c;
                    bestSrc[v] = u64;
                    valid[v >>> 6] |= (1L << (v & 63));
                    hits++;
                } else if (c < bestCost[v] || (c == bestCost[v] && Long.compareUnsigned(u64, bestSrc[v]) < 0)) {
                    bestCost[v] = c;
                    bestSrc[v] = u64;
                }
            }
            ws.vVectors[dst] = V11Vector.ofU64(bestSrc, valid);
            ws.zf = (hits == 0);
            ws.pc++;
            return;
        }

        boolean isPlusTimes = (subop == 0 || subop == 1);
        double[] out = new double[nOut];
        long[] outValid = new long[Math.max(1, (nOut + 63) >>> 6)];
        if ((flags & FLAG_ACCUM) != 0) {
            V11Vector dv = ws.vVectors[dst];
            if (dv == null) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (dv.elemTag != V11Vector.TAG_F64 || dv.length != nOut) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            System.arraycopy(dv.f64Data, 0, out, 0, nOut);
            for (int i = 0; i < nOut; i++) {
                if (dv.isValidAt(i)) outValid[i >>> 6] |= (1L << (i & 63));
            }
        } else if (isPlusTimes) {
            for (int i = 0; i < nOut; i++) {
                out[i] = 0.0;
                outValid[i >>> 6] |= (1L << (i & 63));
            }
        }

        int hits = 0;
        for (int[] ed : edges) {
            int u = ed[0], v = ed[1], e = ed[2];
            if (scalarRhs ? !scalarValid : !rhsSnapValid[u]) continue;
            double ru = scalarRhs ? scalarVal : rhsSnap[u];
            if (weightCol != null && !weightCol.isValidAt(e)) continue;
            double w = (weightCol == null) ? 1.0
                    : (weightCol.elemTag == V11Vector.TAG_F64 ? weightCol.f64Data[e] : (double) weightCol.i64Data[e]);
            double contrib = switch (subop) {
                case 0, 1, 5 -> ru * w;
                case 2, 3, 4 -> ru + w;
                case 6 -> ieeeMaxF64(ru, w);
                default -> ru;
            };
            if (Double.isNaN(contrib)) continue;
            hits++;
            boolean has = ((outValid[v >>> 6] >>> (v & 63)) & 1L) != 0L;
            switch (subop) {
                case 0, 1 -> {
                    double prev = has ? out[v] : 0.0;
                    double sum = prev + contrib;
                    if (Double.isNaN(sum)) {
                        outValid[v >>> 6] &= ~(1L << (v & 63));
                    } else {
                        out[v] = sum;
                        outValid[v >>> 6] |= (1L << (v & 63));
                    }
                }
                case 2, 3, 6 -> {
                    out[v] = has ? ieeeMinF64(out[v], contrib) : contrib;
                    outValid[v >>> 6] |= (1L << (v & 63));
                }
                case 4, 5 -> {
                    out[v] = has ? ieeeMaxF64(out[v], contrib) : contrib;
                    outValid[v >>> 6] |= (1L << (v & 63));
                }
                default -> {}
            }
        }

        ws.vVectors[dst] = V11Vector.ofF64(out, outValid);
        ws.zf = (hits == 0);
        ws.pc++;
    }

    // --- 0x35 OP_WALK_MORSEL / 0x36 OP_FILTER_MORSEL ---
    private static void execWalkMorsel(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, long[] program,
            int subop, int flags, int s1, int s3, int relId, int endOff,
            long[] stepsRef, long maxSteps) {
        int hdrPc = ws.pc;
        int endPc = hdrPc + endOff;
        V11SnapshotView.RelView rel = getRel(bind, ws, relId);
        if (rel == null) return;
        V11Frontier fSrc = getF1Bit(ws, s1);
        if (fSrc == null) return;
        V11SnapshotView view = bind.view;

        boolean isRev = (subop == 1);
        int inDom = isRev ? rel.tgtDom : rel.srcDom;
        int outDom = isRev ? rel.srcDom : rel.tgtDom;
        if (fSrc.domainId != inDom) {
            ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
            return;
        }
        Set<Integer> sExcl = Set.of();
        if ((flags & FLAG_MASKED_S3) != 0) {
            V11Frontier fe = getF1Bit(ws, s3);
            if (fe == null) return;
            if (fe.domainId != outDom) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
            sExcl = toIntSet(fe);
        }

        List<long[]> items = new ArrayList<>();
        int[] srcIds = fSrc.toSortedArray();
        if (!isRev) {
            for (int u : srcIds) {
                for (int e = rel.rowOffsets[u]; e < rel.rowOffsets[u + 1]; e++) {
                    int v = (int) rel.colTargets[e];
                    if (!sExcl.contains(v)) {
                        items.add(new long[]{u & 0xFFFFFFFFL, v & 0xFFFFFFFFL, e & 0xFFFFFFFFL, 1L});
                    }
                }
            }
        } else {
            List<int[]>[] revAdj = buildRevAdj(view, rel);
            for (int v : srcIds) {
                for (int[] pair : revAdj[v]) {
                    int u = pair[0];
                    int e = pair[1];
                    if (!sExcl.contains(u)) {
                        items.add(new long[]{u & 0xFFFFFFFFL, v & 0xFFFFFFFFL, e & 0xFFFFFFFFL, 1L});
                    }
                }
            }
        }
        runMorselBlock(ws, view, program, hdrPc, endPc, outDom, false, items, stepsRef, maxSteps);
    }

    private static void execFilterMorsel(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, long[] program,
            int s1, int endOff, long[] stepsRef, long maxSteps) {
        int hdrPc = ws.pc;
        int endPc = hdrPc + endOff;
        V11SnapshotView view = getView(bind, ws);
        if (view == null) return;
        V11Frontier fSrc = getF1Bit(ws, s1);
        if (fSrc == null) return;

        int[] srcIds = fSrc.toSortedArray();
        List<long[]> items = new ArrayList<>(srcIds.length);
        for (int u : srcIds) {
            items.add(new long[]{u & 0xFFFFFFFFL, 0L, 0L, 1L});
        }
        runMorselBlock(ws, view, program, hdrPc, endPc, fSrc.domainId, true, items, stepsRef, maxSteps);
    }

    @SuppressWarnings("unchecked")
    private static void runMorselBlock(
            V11Workspace ws, V11SnapshotView view, long[] program, int hdrPc, int endPc,
            int defaultDom, boolean isFilter, List<long[]> items,
            long[] stepsRef, long maxSteps) {
        Set<Integer>[] accF = new Set[8];
        TreeMap<Long, Long>[] accMl = new TreeMap[8];
        double[][] accVF64 = new double[8][];
        boolean[][] accVF64Valid = new boolean[8][];
        long[][] accVU64 = new long[8][];
        boolean[][] accVU64Valid = new boolean[8][];
        long[] accRCnt = new long[8];

        int[] committedFDom = new int[8];
        int[] committedMlDom = new int[8];
        Arrays.fill(committedFDom, -1);
        Arrays.fill(committedMlDom, -1);
        boolean[] committedVF64 = new boolean[8];
        boolean[] committedVU64 = new boolean[8];
        boolean[] committedRCnt = new boolean[8];

        for (int d = 0; d < 8; d++) {
            accF[d] = (ws.isFInitialized(d) && !ws.fRegs[d].multiLane) ? toIntSet(ws.fRegs[d]) : new TreeSet<>();
            accMl[d] = new TreeMap<>();
            if (ws.isFInitialized(d) && ws.fRegs[d].multiLane) {
                long[] ns = ws.fRegs[d].mlSortedNodes();
                long[] ms = ws.fRegs[d].mlSortedMasks();
                for (int k = 0; k < ns.length; k++) accMl[d].put(ns[k], ms[k]);
            }
            V11Vector vd = ws.vVectors[d];
            if (vd != null && vd.elemTag == V11Vector.TAG_F64) {
                accVF64[d] = Arrays.copyOf(vd.f64Data, vd.length);
                accVF64Valid[d] = new boolean[vd.length];
                for (int i = 0; i < vd.length; i++) accVF64Valid[d][i] = vd.isValidAt(i);
            }
            if (vd != null && (vd.elemTag == V11Vector.TAG_U64 || vd.elemTag == V11Vector.TAG_I64)) {
                accVU64[d] = Arrays.copyOf(vd.i64Data, vd.length);
                accVU64Valid[d] = new boolean[vd.length];
                for (int i = 0; i < vd.length; i++) accVU64Valid[d][i] = vd.isValidAt(i);
            }
            accRCnt[d] = ws.isRValid(d) ? ws.rRegs[d] : 0L;
        }

        for (int p = hdrPc + 1; p < endPc; p++) {
            long cw = program[p];
            if ((int) (cw & 0xFFL) == OP_MORSEL_COMMIT) {
                int cSub = (int) ((cw >>> 8) & 0xFL);
                int cFlags = (int) ((cw >>> 12) & 0xFL);
                int cDst = (int) ((cw >>> 20) & 0xFL);
                int cLo = (int) ((cw >>> 32) & 0xFFFFL);
                boolean cAccum = (cFlags & FLAG_ACCUM) != 0;
                int dom = (cLo == 0xFFFF) ? defaultDom : cLo;
                switch (cSub) {
                    case 0 -> {
                        committedFDom[cDst] = dom;
                        if (!cAccum) accF[cDst].clear();
                    }
                    case 1 -> {
                        committedMlDom[cDst] = dom;
                        if (!cAccum) accMl[cDst].clear();
                    }
                    case 2 -> {
                        committedVF64[cDst] = true;
                        if (!cAccum && accVF64[cDst] != null) {
                            Arrays.fill(accVF64[cDst], 0.0);
                            Arrays.fill(accVF64Valid[cDst], true);
                        }
                    }
                    case 3, 4 -> {
                        committedVF64[cDst] = true;
                        if (!cAccum && accVF64Valid[cDst] != null) {
                            Arrays.fill(accVF64Valid[cDst], false);
                        }
                    }
                    case 5, 6, 7 -> {
                        committedVU64[cDst] = true;
                        if (!cAccum && accVU64Valid[cDst] != null) {
                            Arrays.fill(accVU64Valid[cDst], false);
                        }
                    }
                    case 8 -> {
                        committedRCnt[cDst] = true;
                        if (!cAccum) accRCnt[cDst] = 0L;
                    }
                    default -> {}
                }
            }
        }

        int total = items.size();
        int mMax = Math.max(1, ws.geometry.morselMax());
        int cursor = 0;
        while (cursor < total && ws.status == IMPULSE_VM_OK) {
            if (stepsRef[0] >= maxSteps) {
                ws.status = IMPULSE_VM_ERR_GAS_EXHAUSTED;
                break;
            }
            stepsRef[0]++;
            int len = Math.min(mMax, total - cursor);
            int[] mf0Ids = new int[len];
            long[] m0 = new long[len];
            long[] m1 = new long[len];
            long[] m2 = new long[len];
            long[] m3 = new long[len];
            for (int i = 0; i < len; i++) {
                mf0Ids[i] = i;
                long[] it = items.get(cursor + i);
                m0[i] = it[0];
                m1[i] = it[1];
                m2[i] = it[2];
                m3[i] = it[3];
            }
            ws.fRegs[8].initFromValidatedIds(0xFFFF, len, mf0Ids, V11Geometry.P0_DEFAULT, true);
            ws.setFInitialized(8, true);
            ws.vVectors[8] = V11Vector.ofU64(m0, V11Vector.allocValidAllTrue(len));
            if (!isFilter) {
                ws.vVectors[9] = V11Vector.ofU64(m1, V11Vector.allocValidAllTrue(len));
                ws.vVectors[10] = V11Vector.ofU64(m2, V11Vector.allocValidAllTrue(len));
            } else {
                ws.vVectors[9] = null;
                ws.vVectors[10] = null;
            }
            ws.vVectors[11] = V11Vector.ofU64(m3, V11Vector.allocValidAllTrue(len));
            V11Vector[] morselSpills = new V11Vector[16];

            ws.pc = hdrPc + 1;
            while (ws.pc < endPc && ws.status == IMPULSE_VM_OK) {
                execMorselBodyInst(ws, view, len, program[ws.pc],
                        accF, accMl, accVF64, accVF64Valid, accVU64, accVU64Valid, accRCnt, morselSpills);
            }
            cursor += len;
        }

        // Clean up morsel-private registers 8..15
        for (int k = 8; k < 16; k++) {
            ws.setFInitialized(k, false);
            ws.vVectors[k] = null;
        }

        if (ws.status == IMPULSE_VM_OK) {
            for (int d = 0; d < 8; d++) {
                if (committedFDom[d] >= view.domainCards.length || committedMlDom[d] >= view.domainCards.length) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
            }
            boolean anyNonEmpty = false;
            for (int d = 0; d < 8; d++) {
                if (committedFDom[d] >= 0) {
                    int dom = committedFDom[d];
                    int dcard = (int) view.domainCards[dom];
                    if (!accF[d].isEmpty()) anyNonEmpty = true;
                    ws.fRegs[d].initFromValidatedIds(dom, dcard, toSortedInts(accF[d]), ws.geometry, false);
                    ws.setFInitialized(d, true);
                }
                if (committedMlDom[d] >= 0) {
                    int dom = committedMlDom[d];
                    int dcard = (int) view.domainCards[dom];
                    if (!accMl[d].isEmpty()) anyNonEmpty = true;
                    long[] ns = new long[accMl[d].size()];
                    long[] ms = new long[accMl[d].size()];
                    int idx = 0;
                    for (var e : accMl[d].entrySet()) {
                        ns[idx] = e.getKey();
                        ms[idx] = e.getValue();
                        idx++;
                    }
                    ws.fRegs[d].initMultiLane(dom, dcard, 64, 64, ns, ms, ws.geometry);
                    ws.setFInitialized(d, true);
                }
                if (committedVF64[d] && accVF64[d] != null) {
                    int n = accVF64[d].length;
                    long[] vBits = new long[Math.max(1, (n + 63) >>> 6)];
                    for (int i = 0; i < n; i++) {
                        if (accVF64Valid[d][i]) {
                            vBits[i >>> 6] |= (1L << (i & 63));
                            if (accVF64[d][i] != 0.0) anyNonEmpty = true;
                        }
                    }
                    ws.vVectors[d] = V11Vector.ofF64(accVF64[d], vBits);
                }
                if (committedVU64[d] && accVU64[d] != null) {
                    int n = accVU64[d].length;
                    long[] vBits = new long[Math.max(1, (n + 63) >>> 6)];
                    for (int i = 0; i < n; i++) {
                        if (accVU64Valid[d][i]) {
                            vBits[i >>> 6] |= (1L << (i & 63));
                            anyNonEmpty = true;
                        }
                    }
                    ws.vVectors[d] = V11Vector.ofU64(accVU64[d], vBits);
                }
                if (committedRCnt[d]) {
                    if (accRCnt[d] != 0L) anyNonEmpty = true;
                    ws.writeR(d, SCALAR_U64, accRCnt[d]);
                }
            }
            ws.zf = !anyNonEmpty;
            ws.pc = endPc + 1;
        }
    }

    private static void execMorselBodyInst(
            V11Workspace ws, V11SnapshotView view, int morselLen, long w,
            Set<Integer>[] accF, TreeMap<Long, Long>[] accMl,
            double[][] accVF64, boolean[][] accVF64Valid,
            long[][] accVU64, boolean[][] accVU64Valid,
            long[] accRCnt, V11Vector[] morselSpills) {
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
        boolean masked = (flags & FLAG_MASKED_S3) != 0;
        boolean scalar = (flags & FLAG_SCALAR_RHS) != 0;

        Set<Integer> activeMask = null;
        if (masked) {
            V11Frontier fm = getF1Bit(ws, s3);
            if (fm == null) return;
            activeMask = toIntSet(fm);
        }

        switch (op) {
            case OP_NOP -> ws.pc++;
            case OP_SPILL -> {
                if (subop != 2 || imm16Lo < 0 || imm16Lo >= 16) {
                    ws.status = IMPULSE_VM_ERR_STACK_OVERFLOW;
                    return;
                }
                morselSpills[imm16Lo] = ws.vVectors[s1];
                ws.pc++;
            }
            case OP_RELOAD -> {
                if (subop != 2 || imm16Lo < 0 || imm16Lo >= 16) {
                    ws.status = IMPULSE_VM_ERR_STACK_OVERFLOW;
                    return;
                }
                if (morselSpills[imm16Lo] == null) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                ws.vVectors[dst] = morselSpills[imm16Lo];
                ws.pc++;
            }
            case OP_JMP -> ws.pc += imm32;
            case OP_JCC -> {
                boolean take = switch (subop) {
                    case 6 -> ws.isRValid(s1) && ws.rRegs[s1] != 0L;
                    case 7 -> !ws.isRValid(s1) || ws.rRegs[s1] == 0L;
                    case 8 -> !ws.isFInitialized(s1) || ws.fRegs[s1].isEmpty();
                    case 9 -> ws.isFInitialized(s1) && !ws.fRegs[s1].isEmpty();
                    default -> false;
                };
                ws.pc = take ? (ws.pc + imm32) : (ws.pc + 1);
            }
            case OP_MOV -> {
                if (subop == 1) {
                    if (!ws.isFInitialized(s1)) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                    ws.fRegs[dst].copyFrom(ws.fRegs[s1]);
                    ws.setFInitialized(dst, true);
                    ws.pc++;
                } else if (subop == 2) {
                    if (ws.vVectors[s1] == null) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                    ws.vVectors[dst] = ws.vVectors[s1];
                    ws.pc++;
                } else {
                    ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
                }
            }
            case OP_SWAP -> {
                if (subop == 1) {
                    boolean id = ws.isFInitialized(dst), is = ws.isFInitialized(s1);
                    V11Frontier t = ws.fRegs[dst]; ws.fRegs[dst] = ws.fRegs[s1]; ws.fRegs[s1] = t;
                    ws.setFInitialized(dst, is); ws.setFInitialized(s1, id);
                    ws.pc++;
                } else if (subop == 2) {
                    V11Vector t = ws.vVectors[dst]; ws.vVectors[dst] = ws.vVectors[s1]; ws.vVectors[s1] = t;
                    ws.pc++;
                } else {
                    ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
                }
            }
            case OP_SET_OP -> {
                V11Frontier fa = getF1Bit(ws, s1);
                V11Frontier fb = getF1Bit(ws, s2);
                if (fa == null || fb == null) return;
                Set<Integer> sa = toIntSet(fa), sb = toIntSet(fb), res = new TreeSet<>();
                switch (subop) {
                    case 0 -> { res.addAll(sa); res.addAll(sb); }
                    case 1 -> { res.addAll(sa); res.retainAll(sb); }
                    case 2 -> { res.addAll(sa); res.removeAll(sb); }
                    case 3 -> {
                        for (int x : sa) if (!sb.contains(x)) res.add(x);
                        for (int x : sb) if (!sa.contains(x)) res.add(x);
                    }
                    default -> {}
                }
                ws.fRegs[dst].initFromValidatedIds(0xFFFF, morselLen, toSortedInts(res), V11Geometry.P0_DEFAULT, true);
                ws.setFInitialized(dst, true);
                ws.pc++;
            }
            case OP_SET_COMPLEMENT -> {
                V11Frontier fa = getF1Bit(ws, s1);
                if (fa == null) return;
                Set<Integer> sa = toIntSet(fa), res = new TreeSet<>();
                for (int i = 0; i < morselLen; i++) if (!sa.contains(i)) res.add(i);
                ws.fRegs[dst].initFromValidatedIds(0xFFFF, morselLen, toSortedInts(res), V11Geometry.P0_DEFAULT, true);
                ws.setFInitialized(dst, true);
                ws.pc++;
            }
            case OP_GATHER_NODE_ATTR, OP_GATHER_EDGE_ATTR -> {
                V11Vector idxVec = ws.vVectors[s1];
                if (idxVec == null || idxVec.elemTag != V11Vector.TAG_U64) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                if (idxVec.length < morselLen) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                V11Vector col;
                if (op == OP_GATHER_NODE_ATTR) {
                    col = view.getNodeAttr(imm16Lo, imm16Hi);
                } else {
                    if (imm16Lo >= view.relations.length) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        return;
                    }
                    col = view.relations[imm16Lo].edgeAttrs.get(imm16Hi);
                }
                if (col == null) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                int colLen = col.length;
                for (int i = 0; i < morselLen; i++) {
                    if (activeMask != null && !activeMask.contains(i)) continue;
                    if (idxVec.isValidAt(i)) {
                        long id = idxVec.i64Data[i];
                        if (id < 0 || id >= colLen) {
                            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                            return;
                        }
                    }
                }
                long[] vBits = new long[Math.max(1, (morselLen + 63) >>> 6)];
                if (col.elemTag == V11Vector.TAG_F64) {
                    double[] out = new double[morselLen];
                    for (int i = 0; i < morselLen; i++) {
                        if (activeMask != null && !activeMask.contains(i)) continue;
                        if (idxVec.isValidAt(i)) {
                            int id = (int) idxVec.i64Data[i];
                            if (col.isValidAt(id)) {
                                out[i] = col.f64Data[id];
                                vBits[i >>> 6] |= (1L << (i & 63));
                            }
                        }
                    }
                    ws.vVectors[dst] = V11Vector.ofF64(out, vBits);
                } else if (col.elemTag == V11Vector.TAG_I64 || col.elemTag == V11Vector.TAG_U64) {
                    long[] out = new long[morselLen];
                    for (int i = 0; i < morselLen; i++) {
                        if (activeMask != null && !activeMask.contains(i)) continue;
                        if (idxVec.isValidAt(i)) {
                            int id = (int) idxVec.i64Data[i];
                            if (col.isValidAt(id)) {
                                out[i] = col.i64Data[id];
                                vBits[i >>> 6] |= (1L << (i & 63));
                            }
                        }
                    }
                    ws.vVectors[dst] = (col.elemTag == V11Vector.TAG_I64)
                            ? V11Vector.ofI64(out, vBits) : V11Vector.ofU64(out, vBits);
                } else {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                ws.pc++;
            }
            case OP_COALESCE -> {
                V11Vector lhs = ws.vVectors[s1];
                if (lhs == null) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                long[] vBits = new long[Math.max(1, (morselLen + 63) >>> 6)];
                if (lhs.elemTag == V11Vector.TAG_F64) {
                    double[] out = new double[morselLen];
                    V11Vector rv = scalar ? null : ws.vVectors[s2];
                    boolean rScOk = scalar && ws.isRValid(s2) && !Double.isNaN(Double.longBitsToDouble(ws.rRegs[s2]));
                    double rSc = rScOk ? Double.longBitsToDouble(ws.rRegs[s2]) : 0.0;
                    for (int i = 0; i < morselLen; i++) {
                        if (activeMask != null && !activeMask.contains(i)) continue;
                        if (lhs.isValidAt(i)) {
                            out[i] = lhs.f64Data[i];
                            vBits[i >>> 6] |= (1L << (i & 63));
                        } else if (scalar) {
                            if (rScOk) { out[i] = rSc; vBits[i >>> 6] |= (1L << (i & 63)); }
                        } else if (rv != null && rv.elemTag == V11Vector.TAG_F64 && i < rv.length && rv.isValidAt(i)) {
                            out[i] = rv.f64Data[i];
                            vBits[i >>> 6] |= (1L << (i & 63));
                        }
                    }
                    ws.vVectors[dst] = V11Vector.ofF64(out, vBits);
                    ws.pc++;
                } else if (lhs.elemTag == V11Vector.TAG_I64 || lhs.elemTag == V11Vector.TAG_U64) {
                    long[] out = new long[morselLen];
                    V11Vector rv = scalar ? null : ws.vVectors[s2];
                    for (int i = 0; i < morselLen; i++) {
                        if (activeMask != null && !activeMask.contains(i)) continue;
                        if (lhs.isValidAt(i)) {
                            out[i] = lhs.i64Data[i];
                            vBits[i >>> 6] |= (1L << (i & 63));
                        } else if (scalar) {
                            if (ws.isRValid(s2)) { out[i] = ws.rRegs[s2]; vBits[i >>> 6] |= (1L << (i & 63)); }
                        } else if (rv != null && rv.elemTag == lhs.elemTag && i < rv.length && rv.isValidAt(i)) {
                            out[i] = rv.i64Data[i];
                            vBits[i >>> 6] |= (1L << (i & 63));
                        }
                    }
                    ws.vVectors[dst] = (lhs.elemTag == V11Vector.TAG_I64)
                            ? V11Vector.ofI64(out, vBits) : V11Vector.ofU64(out, vBits);
                    ws.pc++;
                } else {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                }
            }
            case OP_VEC_MATH_ARITH -> {
                V11Vector lhs = ws.vVectors[s1];
                if (lhs == null || lhs.elemTag != V11Vector.TAG_F64) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                double[] out = new double[morselLen];
                long[] vBits = new long[Math.max(1, (morselLen + 63) >>> 6)];
                V11Vector rv = scalar ? null : ws.vVectors[s2];
                boolean rScOk = scalar && ws.isRValid(s2) && !Double.isNaN(Double.longBitsToDouble(ws.rRegs[s2]));
                double rSc = rScOk ? Double.longBitsToDouble(ws.rRegs[s2]) : 0.0;
                for (int i = 0; i < morselLen; i++) {
                    if (activeMask != null && !activeMask.contains(i)) continue;
                    if (!lhs.isValidAt(i)) continue;
                    double x = lhs.f64Data[i];
                    double r;
                    if (subop == 8) r = -x;
                    else if (subop == 9) r = Math.abs(x);
                    else if (subop == 10) { if (x < 0.0) continue; r = Math.sqrt(x); }
                    else {
                        boolean hasY = scalar ? rScOk : (rv != null && rv.elemTag == V11Vector.TAG_F64 && i < rv.length && rv.isValidAt(i));
                        if (!hasY) continue;
                        double y = scalar ? rSc : rv.f64Data[i];
                        r = switch (subop) {
                            case 0 -> x + y;
                            case 1 -> x - y;
                            case 2 -> x * y;
                            case 3 -> (y == 0.0) ? Double.NaN : (x / y);
                            case 6 -> ieeeMinF64(x, y);
                            case 7 -> ieeeMaxF64(x, y);
                            default -> Double.NaN;
                        };
                    }
                    if (!Double.isNaN(r)) {
                        out[i] = r;
                        vBits[i >>> 6] |= (1L << (i & 63));
                    }
                }
                ws.vVectors[dst] = V11Vector.ofF64(out, vBits);
                ws.pc++;
            }
            case OP_VEC_CMP -> {
                V11Vector lhs = ws.vVectors[s1];
                if (lhs == null || lhs.elemTag != V11Vector.TAG_F64) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                int words = Math.max(1, (morselLen + 63) >>> 6);
                long[] valid = new long[words];
                long[] data = new long[words];
                V11Vector rv = scalar ? null : ws.vVectors[s2];
                boolean rScOk = scalar && ws.isRValid(s2);
                double rSc = rScOk ? Double.longBitsToDouble(ws.rRegs[s2]) : Double.NaN;
                for (int i = 0; i < morselLen; i++) {
                    if (activeMask != null && !activeMask.contains(i)) continue;
                    if (!lhs.isValidAt(i)) continue;
                    boolean hasY = scalar ? rScOk : (rv != null && rv.elemTag == V11Vector.TAG_F64 && i < rv.length && rv.isValidAt(i));
                    if (!hasY) continue;
                    double x = lhs.f64Data[i];
                    double y = scalar ? rSc : rv.f64Data[i];
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    boolean res = switch (subop) {
                        case 0 -> x == y;
                        case 1 -> x != y;
                        case 2 -> x < y;
                        case 3 -> x <= y;
                        case 4 -> x > y;
                        case 5 -> x >= y;
                        default -> false;
                    };
                    long bit = 1L << (i & 63);
                    valid[i >>> 6] |= bit;
                    if (res) data[i >>> 6] |= bit;
                }
                ws.vVectors[dst] = V11Vector.ofBool3vl(morselLen, valid, data);
                ws.pc++;
            }
            case OP_SET_CONVERT -> {
                if (subop == 2) { // .b2f
                    V11Vector bv = ws.vVectors[s1];
                    if (bv == null || bv.elemTag != V11Vector.TAG_BOOL_3VL) {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                    int[] tmp = new int[bv.length];
                    int cnt = 0;
                    for (int i = 0; i < bv.length; i++) {
                        if (bv.isValidAt(i) && bv.boolDataAt(i)) tmp[cnt++] = i;
                    }
                    ws.fRegs[dst].initFromValidatedIds(0xFFFF, morselLen, Arrays.copyOf(tmp, cnt), V11Geometry.P0_DEFAULT, true);
                    ws.setFInitialized(dst, true);
                    ws.pc++;
                } else {
                    ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
                }
            }
            case OP_MORSEL_COMMIT -> {
                Set<Integer> commitMask;
                if (masked) {
                    commitMask = activeMask;
                } else if (ws.isFInitialized(8) && !ws.fRegs[8].multiLane) {
                    commitMask = toIntSet(ws.fRegs[8]);
                } else {
                    commitMask = new TreeSet<>();
                }
                switch (subop) {
                    case 0 -> { // .yield_frontier
                        V11Vector ids = ws.vVectors[s1];
                        if (ids == null || ids.elemTag != V11Vector.TAG_U64) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                        for (int i : commitMask) {
                            if (ids.isValidAt(i)) accF[dst].add((int) ids.i64Data[i]);
                        }
                        ws.pc++;
                    }
                    case 1 -> { // .yield_lane_or
                        V11Vector ids = ws.vVectors[s1];
                        V11Vector lms = ws.vVectors[s2];
                        if (ids == null || ids.elemTag != V11Vector.TAG_U64 || lms == null || lms.elemTag != V11Vector.TAG_U64) {
                            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                            return;
                        }
                        for (int i : commitMask) {
                            if (ids.isValidAt(i) && lms.isValidAt(i) && lms.i64Data[i] != 0L) {
                                long tgt = ids.i64Data[i];
                                long prev = accMl[dst].getOrDefault(tgt, 0L);
                                accMl[dst].put(tgt, prev | lms.i64Data[i]);
                            }
                        }
                        ws.pc++;
                    }
                    case 2, 3, 4 -> { // .scatter_add / min / max
                        V11Vector ids = ws.vVectors[s1];
                        if (ids == null || ids.elemTag != V11Vector.TAG_U64 || accVF64[dst] == null) {
                            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                            return;
                        }
                        V11Vector vals = scalar ? null : ws.vVectors[s2];
                        boolean rOk = scalar && ws.isRValid(s2);
                        double rVal = rOk ? Double.longBitsToDouble(ws.rRegs[s2]) : Double.NaN;
                        for (int i : commitMask) {
                            if (!ids.isValidAt(i)) continue;
                            boolean hasV = scalar ? (rOk && !Double.isNaN(rVal)) : (vals != null && vals.elemTag == V11Vector.TAG_F64 && vals.isValidAt(i));
                            if (!hasV) continue;
                            double v = scalar ? rVal : vals.f64Data[i];
                            long tgt64 = ids.i64Data[i];
                            int ti = (int) tgt64;
                            if (tgt64 < 0L || ti < 0 || ti >= accVF64[dst].length) {
                                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                                return;
                            }
                            if (subop == 2) {
                                double prev = accVF64Valid[dst][ti] ? accVF64[dst][ti] : 0.0;
                                double sum = prev + v;
                                if (Double.isNaN(sum)) {
                                    accVF64Valid[dst][ti] = false;
                                } else {
                                    accVF64[dst][ti] = sum;
                                    accVF64Valid[dst][ti] = true;
                                }
                            } else if (subop == 3) {
                                accVF64[dst][ti] = accVF64Valid[dst][ti] ? ieeeMinF64(accVF64[dst][ti], v) : v;
                                accVF64Valid[dst][ti] = true;
                            } else {
                                accVF64[dst][ti] = accVF64Valid[dst][ti] ? ieeeMaxF64(accVF64[dst][ti], v) : v;
                                accVF64Valid[dst][ti] = true;
                            }
                        }
                        ws.pc++;
                    }
                    case 5, 6, 7 -> { // .scatter_min_src / argmin / argmax
                        V11Vector ids = ws.vVectors[s1];
                        if (ids == null || ids.elemTag != V11Vector.TAG_U64 || accVU64[dst] == null) {
                            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                            return;
                        }
                        V11Vector vals = scalar ? null : ws.vVectors[s2];
                        for (int i : commitMask) {
                            if (!ids.isValidAt(i)) continue;
                            boolean hasV = scalar ? ws.isRValid(s2) : (vals != null && (vals.elemTag == V11Vector.TAG_U64 || vals.elemTag == V11Vector.TAG_I64) && vals.isValidAt(i));
                            if (!hasV) continue;
                            long v = scalar ? ws.rRegs[s2] : vals.i64Data[i];
                            long tgt64 = ids.i64Data[i];
                            int ti = (int) tgt64;
                            if (tgt64 < 0L || ti < 0 || ti >= accVU64[dst].length) {
                                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                                return;
                            }
                            if (!accVU64Valid[dst][ti]) {
                                accVU64[dst][ti] = v;
                                accVU64Valid[dst][ti] = true;
                            } else if (subop == 5 || subop == 6) {
                                if (Long.compareUnsigned(v, accVU64[dst][ti]) < 0) accVU64[dst][ti] = v;
                            } else {
                                if (Long.compareUnsigned(v, accVU64[dst][ti]) > 0) accVU64[dst][ti] = v;
                            }
                        }
                        ws.pc++;
                    }
                    case 8 -> { // .count_add_r
                        accRCnt[dst] += commitMask.size();
                        ws.pc++;
                    }
                    default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
                }
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    // --- 0x39 OP_SAMPLE_NEIGHBORS ---
    private static long splitMixSample(long seed, long u, long j) {
        long z = seed ^ (u * 0x9E3779B97F4A7C15L) ^ (j * 0xBF58476D1CE4E5B9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static void execSampleNeighbors(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop,
            int dst, int s1, int s2, int relId, int k) {
        V11SnapshotView.RelView rel = getRel(bind, ws, relId);
        if (rel == null) return;
        if (!ws.isRValid(s2)) {
            ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
            return;
        }
        long seed = ws.rRegs[s2];
        V11SnapshotView view = bind.view;

        switch (subop) {
            case 0 -> { // .frontier_wor
                V11Frontier fSrc = getF1Bit(ws, s1);
                if (fSrc == null) return;
                if (fSrc.domainId != rel.srcDom) {
                    ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                    return;
                }
                if (k <= 0) {
                    ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
                    return;
                }
                Set<Integer> out = new TreeSet<>();
                for (int u : fSrc.toSortedArray()) {
                    int lo = rel.rowOffsets[u], hi = rel.rowOffsets[u + 1];
                    int deg = hi - lo;
                    if (deg <= k) {
                        for (int e = lo; e < hi; e++) out.add((int) rel.colTargets[e]);
                    } else {
                        Set<Integer> chosen = new HashSet<>(k * 2);
                        for (int t = deg - k; t < deg; t++) {
                            long r = splitMixSample(seed, u & 0xFFFFFFFFL, t);
                            int p = (int) Long.remainderUnsigned(r, t + 1L);
                            int pick = chosen.contains(p) ? t : p;
                            chosen.add(pick);
                            out.add((int) rel.colTargets[lo + pick]);
                        }
                    }
                }
                int tgtCard = (int) view.domainCards[rel.tgtDom];
                ws.fRegs[dst].initFromValidatedIds(rel.tgtDom, tgtCard, toSortedInts(out), ws.geometry, false);
                ws.setFInitialized(dst, true);
                ws.zf = out.isEmpty();
                ws.pc++;
            }
            case 1 -> { // .vec_wr
                V11Vector seeds = ws.vVectors[s1];
                if (seeds == null || (seeds.elemTag != V11Vector.TAG_U64 && seeds.elemTag != V11Vector.TAG_I64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                if (k <= 0) {
                    ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
                    return;
                }
                int n = seeds.length;
                long[] out = new long[n * k];
                long[] valid = new long[Math.max(1, (n * k + 63) >>> 6)];
                boolean anyValid = false;
                long dcard = view.domainCards[rel.srcDom];
                for (int i = 0; i < n; i++) {
                    if (!seeds.isValidAt(i)) continue;
                    long u64 = seeds.i64Data[i];
                    if (u64 < 0L || Long.compareUnsigned(u64, dcard) >= 0) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        return;
                    }
                    int u = (int) u64;
                    int lo = rel.rowOffsets[u], hi = rel.rowOffsets[u + 1];
                    int deg = hi - lo;
                    if (deg > 0) {
                        for (int j = 0; j < k; j++) {
                            long r = splitMixSample(seed, u64, j);
                            int idx = (int) Long.remainderUnsigned(r, deg);
                            int pos = i * k + j;
                            out[pos] = rel.colTargets[lo + idx];
                            valid[pos >>> 6] |= (1L << (pos & 63));
                            anyValid = true;
                        }
                    }
                }
                ws.vVectors[dst] = V11Vector.ofU64(out, valid);
                ws.zf = !anyValid;
                ws.pc++;
            }
            case 2, 3 -> { // .rw_step_fwd / .rw_step_stay
                V11Vector walkers = ws.vVectors[s1];
                if (walkers == null || (walkers.elemTag != V11Vector.TAG_U64 && walkers.elemTag != V11Vector.TAG_I64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                boolean stay = (subop == 3);
                int n = walkers.length;
                long[] out = new long[n];
                long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
                boolean anyValid = false;
                long dcard = view.domainCards[rel.srcDom];
                for (int i = 0; i < n; i++) {
                    if (!walkers.isValidAt(i)) continue;
                    long u64 = walkers.i64Data[i];
                    if (u64 < 0L || Long.compareUnsigned(u64, dcard) >= 0) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        return;
                    }
                    int u = (int) u64;
                    int lo = rel.rowOffsets[u], hi = rel.rowOffsets[u + 1];
                    int deg = hi - lo;
                    if (deg == 0) {
                        if (stay) {
                            out[i] = u64;
                            valid[i >>> 6] |= (1L << (i & 63));
                            anyValid = true;
                        }
                    } else {
                        long r = splitMixSample(seed, u64, i);
                        int idx = (int) Long.remainderUnsigned(r, deg);
                        out[i] = rel.colTargets[lo + idx];
                        valid[i >>> 6] |= (1L << (i & 63));
                        anyValid = true;
                    }
                }
                ws.vVectors[dst] = V11Vector.ofU64(out, valid);
                ws.zf = !anyValid;
                ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    // --- 0x3A OP_HAS_TOPOLOGY ---
    private static void execHasTopology(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop, int dst, int lo, int hi) {
        V11SnapshotView view = getView(bind, ws);
        if (view == null) return;
        long val;
        switch (subop) {
            case 0 -> val = (lo < view.relations.length && view.relations[lo].hasCsr) ? 1L : 0L;
            case 1 -> val = (lo < view.relations.length && view.relations[lo].hasCsc) ? 1L : 0L;
            case 2 -> val = (lo < view.relations.length && view.relations[lo].hasCoo) ? 1L : 0L;
            case 3 -> val = (lo < view.relations.length && view.relations[lo].hasDense) ? 1L : 0L;
            case 4 -> val = view.keyCatalogs.containsKey(lo) ? 1L : 0L;
            case 5 -> val = view.hasNodeAttr(lo, hi) ? 1L : 0L;
            case 6 -> val = (lo < view.relations.length && view.relations[lo].edgeAttrs.containsKey(hi)) ? 1L : 0L;
            case 7 -> val = view.hasIndex(lo, hi) ? 1L : 0L;
            case 8 -> {
                if (lo >= view.domainCards.length) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                val = view.domainCards[lo];
            }
            case 9 -> {
                if (lo >= view.relations.length) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                val = view.relations[lo].colTargets.length;
            }
            default -> { ws.status = IMPULSE_VM_ERR_INVALID_OPCODE; return; }
        }
        ws.writeR(dst, SCALAR_U64, val);
        setScalarFlags(ws, true, val);
        ws.pc++;
    }

    // --- 0x3B OP_GRAPH_KERNEL ---
    private static int ufFind(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    @SuppressWarnings("unchecked")
    private static void execGraphKernel(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop, int flags,
            int dst, int s3, int relId, int imm16Hi) {
        V11SnapshotView.RelView rel = getRel(bind, ws, relId);
        if (rel == null) return;
        if (rel.srcDom != rel.tgtDom) {
            ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
            return;
        }
        V11SnapshotView view = bind.view;
        int dom = rel.srcDom;
        int n = (int) view.domainCards[dom];

        Set<Integer> maskSet = null;
        if ((flags & FLAG_MASKED_S3) != 0) {
            V11Frontier fm = getF1Bit(ws, s3);
            if (fm == null) return;
            if (fm.domainId != dom) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
            maskSet = toIntSet(fm);
        }

        Set<Integer>[] adj = new Set[n];
        for (int u = 0; u < n; u++) adj[u] = new TreeSet<>();
        for (int u = 0; u < n; u++) {
            if (maskSet != null && !maskSet.contains(u)) continue;
            for (int e = rel.rowOffsets[u]; e < rel.rowOffsets[u + 1]; e++) {
                int v = (int) rel.colTargets[e];
                if (v != u && v >= 0 && v < n && (maskSet == null || maskSet.contains(v))) {
                    adj[u].add(v);
                    adj[v].add(u);
                }
            }
        }

        switch (subop) {
            case 0 -> { // .cc_afforest
                int[] parent = new int[n];
                for (int i = 0; i < n; i++) parent[i] = i;
                for (int u = 0; u < n; u++) {
                    if (maskSet != null && !maskSet.contains(u)) continue;
                    for (int v : adj[u]) {
                        int ra = ufFind(parent, u);
                        int rb = ufFind(parent, v);
                        if (ra != rb) {
                            if (ra < rb) parent[rb] = ra;
                            else parent[ra] = rb;
                        }
                    }
                }
                long[] out = new long[n];
                long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
                boolean any = false;
                for (int u = 0; u < n; u++) {
                    if (maskSet == null || maskSet.contains(u)) {
                        out[u] = ufFind(parent, u);
                        valid[u >>> 6] |= (1L << (u & 63));
                        any = true;
                    }
                }
                ws.vVectors[dst] = V11Vector.ofU64(out, valid);
                ws.zf = !any;
                ws.pc++;
            }
            case 1, 2 -> { // .kcore_nums / .kcore_frontier
                int[] deg = new int[n];
                int[] core = new int[n];
                boolean[] removed = new boolean[n];
                int remaining = 0;
                for (int u = 0; u < n; u++) {
                    if (maskSet != null && !maskSet.contains(u)) {
                        deg[u] = -1;
                        removed[u] = true;
                    } else {
                        deg[u] = adj[u].size();
                        remaining++;
                    }
                }
                int curK = 0;
                while (remaining > 0) {
                    int minU = -1, minD = Integer.MAX_VALUE;
                    for (int u = 0; u < n; u++) {
                        if (!removed[u] && deg[u] < minD) {
                            minD = deg[u];
                            minU = u;
                        }
                    }
                    if (minD > curK) curK = minD;
                    removed[minU] = true;
                    core[minU] = curK;
                    remaining--;
                    for (int v : adj[minU]) {
                        if (!removed[v] && deg[v] > curK) deg[v]--;
                    }
                }
                if (subop == 1) {
                    long[] out = new long[n];
                    long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
                    boolean anyPos = false;
                    for (int u = 0; u < n; u++) {
                        if (maskSet == null || maskSet.contains(u)) {
                            out[u] = core[u];
                            valid[u >>> 6] |= (1L << (u & 63));
                            if (core[u] > 0) anyPos = true;
                        }
                    }
                    ws.vVectors[dst] = V11Vector.ofU64(out, valid);
                    ws.zf = !anyPos;
                    ws.pc++;
                } else {
                    int k = imm16Hi;
                    int[] buf = new int[n];
                    int cnt = 0;
                    for (int u = 0; u < n; u++) {
                        if ((maskSet == null || maskSet.contains(u)) && core[u] >= k) {
                            buf[cnt++] = u;
                        }
                    }
                    ws.fRegs[dst].initFromValidatedIds(dom, n, Arrays.copyOf(buf, cnt), ws.geometry, true);
                    ws.setFInitialized(dst, true);
                    ws.zf = (cnt == 0);
                    ws.pc++;
                }
            }
            case 3, 4 -> { // .tri_count_total / .tri_count_vec
                long[] triU = new long[n];
                long totalTri = 0L;
                for (int u = 0; u < n; u++) {
                    if (maskSet != null && !maskSet.contains(u)) continue;
                    for (int v : adj[u]) {
                        if (v > u) {
                            for (int w : adj[u]) {
                                if (w > v && adj[v].contains(w)) {
                                    totalTri++;
                                    triU[u]++;
                                    triU[v]++;
                                    triU[w]++;
                                }
                            }
                        }
                    }
                }
                if (subop == 3) {
                    ws.writeR(dst, SCALAR_U64, totalTri);
                    setScalarFlags(ws, true, totalTri);
                    ws.pc++;
                } else {
                    long[] out = new long[n];
                    long[] valid = new long[Math.max(1, (n + 63) >>> 6)];
                    for (int u = 0; u < n; u++) {
                        if (maskSet == null || maskSet.contains(u)) {
                            out[u] = triU[u];
                            valid[u >>> 6] |= (1L << (u & 63));
                        }
                    }
                    ws.vVectors[dst] = V11Vector.ofU64(out, valid);
                    ws.zf = (totalTri == 0L);
                    ws.pc++;
                }
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }
}
