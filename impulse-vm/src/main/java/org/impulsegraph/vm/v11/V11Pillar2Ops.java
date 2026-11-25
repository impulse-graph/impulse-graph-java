package org.impulsegraph.vm.v11;

import java.util.Arrays;

import static org.impulsegraph.vm.v11.V11IsaTables.*;

/**
 * Normative Pillar 2 Evaluator ({@code 0x20}–{@code 0x27}, 8 opcodes / 24 subops) for ImpulseVM v0.11.0.
 *
 * <p>Bit-identical to {@code V11_eval_p2} in {@code impulse-ocaml}:
 * {@code OP_FSEED}, {@code OP_INIT_DOMAIN_SPAN}, {@code OP_SET_OP}, {@code OP_SET_COMPLEMENT},
 * {@code OP_SET_CARDINALITY}, {@code OP_SET_CONTAINS}, {@code OP_SET_CONVERT}, and {@code OP_STABLE_CHECK}.
 */
public final class V11Pillar2Ops {

    public static final int RESULT_UNHANDLED = 0;
    public static final int RESULT_STEPPED   = 1;

    private V11Pillar2Ops() {}

    public static int step(V11Workspace ws, V11Interpreter.SnapshotBinding bind, long w) {
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
        final boolean accum = (flags & FLAG_ACCUM) != 0;

        switch (op) {
            case OP_FSEED -> {
                execFseed(ws, bind, subop, accum, dst, s1, imm16Lo, imm16Hi);
                return RESULT_STEPPED;
            }
            case OP_INIT_DOMAIN_SPAN -> {
                execInitDomainSpan(ws, bind, dst, imm16Lo);
                return RESULT_STEPPED;
            }
            case OP_SET_OP -> {
                execSetOp(ws, subop, dst, s1, s2);
                return RESULT_STEPPED;
            }
            case OP_SET_COMPLEMENT -> {
                execSetComplement(ws, dst, s1);
                return RESULT_STEPPED;
            }
            case OP_SET_CARDINALITY -> {
                execSetCardinality(ws, subop, dst, s1, imm16Lo);
                return RESULT_STEPPED;
            }
            case OP_SET_CONTAINS -> {
                execSetContains(ws, subop, dst, s1, s2);
                return RESULT_STEPPED;
            }
            case OP_SET_CONVERT -> {
                execSetConvert(ws, bind, subop, accum, dst, s1, imm16Lo);
                return RESULT_STEPPED;
            }
            case OP_STABLE_CHECK -> {
                execStableCheck(ws, subop, dst, s1, s2, s3);
                return RESULT_STEPPED;
            }
            default -> {
                return RESULT_UNHANDLED;
            }
        }
    }

    private static int getDomainCard(V11Interpreter.SnapshotBinding bind, int domId) {
        if (bind == null) {
            return -IMPULSE_VM_ERR_NULL_SNAPSHOT;
        }
        if (domId < 0 || domId >= bind.domainCards.length) {
            return -IMPULSE_VM_ERR_OUT_OF_BOUNDS;
        }
        return bind.domainCards[domId];
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

    private static int[] addSortedUnique(int[] base, int u) {
        int pos = Arrays.binarySearch(base, u);
        if (pos >= 0) {
            return base;
        }
        int ins = -pos - 1;
        int[] out = new int[base.length + 1];
        System.arraycopy(base, 0, out, 0, ins);
        out[ins] = u;
        System.arraycopy(base, ins, out, ins + 1, base.length - ins);
        return out;
    }

    private static int[] removeSorted(int[] base, int u) {
        int pos = Arrays.binarySearch(base, u);
        if (pos < 0) {
            return base;
        }
        int[] out = new int[base.length - 1];
        System.arraycopy(base, 0, out, 0, pos);
        System.arraycopy(base, pos + 1, out, pos, base.length - pos - 1);
        return out;
    }

    private static void setF1Bit(V11Workspace ws, int dst, int domId, int domCard, int[] sortedIds, boolean isAllSpan, boolean knowCard) {
        if (isAllSpan && sortedIds.length == domCard && domCard > 0) {
            ws.fRegs[dst].initAllSpan(domId, domCard);
        } else {
            ws.fRegs[dst].initFromValidatedIds(domId, domCard, sortedIds, ws.geometry, knowCard);
        }
        ws.setFInitialized(dst, true);
    }

    private static void execFseed(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop, boolean accum,
            int dst, int s1, int imm16Lo, int imm16Hi) {
        if (subop <= 2) {
            int domId = imm16Hi;
            int domCard = getDomainCard(bind, domId);
            if (domCard < 0) {
                ws.status = -domCard;
                return;
            }
            int[] base;
            if (accum) {
                if (!ws.isFInitialized(dst)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                V11Frontier existing = ws.fRegs[dst];
                if (existing.domainId != domId) {
                    ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                    return;
                }
                if (existing.multiLane) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                base = existing.toSortedArray();
            } else {
                base = new int[0];
            }

            switch (subop) {
                case 0 -> { // .empty
                    ws.fRegs[dst].resetEmpty(domId, domCard);
                    ws.setFInitialized(dst, true);
                    ws.zf = true;
                    ws.pc++;
                }
                case 1 -> { // .single_imm
                    if (imm16Lo >= domCard) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    } else {
                        int[] next = addSortedUnique(base, imm16Lo);
                        setF1Bit(ws, dst, domId, domCard, next, false, true);
                        ws.zf = (next.length == 0);
                        ws.pc++;
                    }
                }
                case 2 -> { // .single_reg
                    if (!ws.isRValid(s1)) {
                        setF1Bit(ws, dst, domId, domCard, base, false, true);
                        ws.zf = (base.length == 0);
                        ws.pc++;
                    } else {
                        long u = ws.rRegs[s1];
                        if (u < 0L || Long.compareUnsigned(u, domCard) >= 0) {
                            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        } else {
                            int[] next = addSortedUnique(base, (int) u);
                            setF1Bit(ws, dst, domId, domCard, next, false, true);
                            ws.zf = (next.length == 0);
                            ws.pc++;
                        }
                    }
                }
                default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
            }
        } else if (subop == 3 || subop == 4) { // .insert_reg / .remove_reg
            if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            V11Frontier fd = ws.fRegs[dst];
            if (!ws.isRValid(s1)) {
                ws.zf = fd.isEmpty();
                ws.pc++;
                return;
            }
            long u = ws.rRegs[s1];
            if (u < 0L || Long.compareUnsigned(u, fd.domainCard) >= 0) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            int[] cur = fd.toSortedArray();
            boolean knowCard = fd.cachedCard >= 0;
            int[] next = (subop == 3) ? addSortedUnique(cur, (int) u) : removeSorted(cur, (int) u);
            setF1Bit(ws, dst, fd.domainId, fd.domainCard, next, false, knowCard);
            ws.zf = (next.length == 0);
            ws.pc++;
        } else {
            ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    private static void execInitDomainSpan(V11Workspace ws, V11Interpreter.SnapshotBinding bind, int dst, int domId) {
        int domCard = getDomainCard(bind, domId);
        if (domCard < 0) {
            ws.status = -domCard;
            return;
        }
        ws.fRegs[dst].initAllSpan(domId, domCard);
        ws.setFInitialized(dst, true);
        ws.zf = (domCard == 0);
        ws.pc++;
    }

    private static void execSetOp(V11Workspace ws, int subop, int dst, int s1, int s2) {
        if (!ws.isFInitialized(s1) || !ws.isFInitialized(s2)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        V11Frontier fa = ws.fRegs[s1];
        V11Frontier fb = ws.fRegs[s2];
        if (fa.domainId != fb.domainId) {
            ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
            return;
        }
        if (fa.multiLane != fb.multiLane) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int domId = fa.domainId;
        int domCard = fa.domainCard;

        if (!fa.multiLane) {
            int[] a = fa.toSortedArray();
            int[] b = fb.toSortedArray();
            int[] res = switch (subop) {
                case 0 -> sortedUnion(a, b);
                case 1 -> sortedIntersect(a, b);
                case 2 -> sortedDiff(a, b);
                case 3 -> sortedSymDiff(a, b);
                default -> new int[0];
            };
            boolean isAll = (res.length == domCard && domCard > 0);
            setF1Bit(ws, dst, domId, domCard, res, isAll, true);
            ws.zf = (res.length == 0);
            ws.pc++;
        } else {
            if (fa.allocWidth != fb.allocWidth) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            long[] an = fa.mlSortedNodes();
            long[] am = fa.mlSortedMasks();
            long[] bn = fb.mlSortedNodes();
            long[] bm = fb.mlSortedMasks();
            long[] outN = new long[an.length + bn.length];
            long[] outM = new long[an.length + bn.length];
            int i = 0, j = 0, k = 0;
            while (i < an.length || j < bn.length) {
                long u, va, vb;
                if (j >= bn.length || (i < an.length && an[i] < bn[j])) {
                    u = an[i]; va = am[i++]; vb = 0L;
                } else if (i >= an.length || bn[j] < an[i]) {
                    u = bn[j]; va = 0L; vb = bm[j++];
                } else {
                    u = an[i]; va = am[i++]; vb = bm[j++];
                }
                long r = switch (subop) {
                    case 0 -> va | vb;
                    case 1 -> va & vb;
                    case 2 -> va & ~vb;
                    case 3 -> va ^ vb;
                    default -> 0L;
                };
                if (r != 0L) {
                    outN[k] = u;
                    outM[k] = r;
                    k++;
                }
            }
            long[] fn = (k == outN.length) ? outN : Arrays.copyOf(outN, k);
            long[] fm = (k == outM.length) ? outM : Arrays.copyOf(outM, k);
            int activeLanes = Math.max(fa.activeLaneCount, fb.activeLaneCount);
            ws.fRegs[dst].initMultiLane(domId, domCard, fa.allocWidth, activeLanes, fn, fm, ws.geometry);
            ws.setFInitialized(dst, true);
            ws.zf = (k == 0);
            ws.pc++;
        }
    }

    private static int[] sortedUnion(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) out[k++] = a[i++];
            else if (b[j] < a[i]) out[k++] = b[j++];
            else { out[k++] = a[i++]; j++; }
        }
        while (i < a.length) out[k++] = a[i++];
        while (j < b.length) out[k++] = b[j++];
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    private static int[] sortedIntersect(int[] a, int[] b) {
        int[] out = new int[Math.min(a.length, b.length)];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) i++;
            else if (b[j] < a[i]) j++;
            else { out[k++] = a[i++]; j++; }
        }
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    private static int[] sortedDiff(int[] a, int[] b) {
        int[] out = new int[a.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) out[k++] = a[i++];
            else if (b[j] < a[i]) j++;
            else { i++; j++; }
        }
        while (i < a.length) out[k++] = a[i++];
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    private static int[] sortedSymDiff(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        int i = 0, j = 0, k = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) out[k++] = a[i++];
            else if (b[j] < a[i]) out[k++] = b[j++];
            else { i++; j++; }
        }
        while (i < a.length) out[k++] = a[i++];
        while (j < b.length) out[k++] = b[j++];
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    private static void execSetComplement(V11Workspace ws, int dst, int s1) {
        if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        V11Frontier fa = ws.fRegs[s1];
        int domId = fa.domainId;
        int n = fa.domainCard;
        int[] sa = fa.toSortedArray();
        int[] comp = new int[n - sa.length];
        int p = 0, k = 0;
        for (int u = 0; u < n; u++) {
            if (p < sa.length && sa[p] == u) {
                p++;
            } else {
                comp[k++] = u;
            }
        }
        boolean isAll = (sa.length == 0 && n > 0);
        setF1Bit(ws, dst, domId, n, comp, isAll, true);
        ws.zf = (comp.length == 0);
        ws.pc++;
    }

    private static void execSetCardinality(V11Workspace ws, int subop, int dst, int s1, int imm16Lo) {
        if (!ws.isFInitialized(s1)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        V11Frontier fa = ws.fRegs[s1];
        if (subop == 0) {
            if (fa.multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            long c = fa.cardinality();
            ws.writeR(dst, SCALAR_U64, c);
            setScalarFlags(ws, true, c);
            ws.pc++;
        } else if (subop >= 1 && subop <= 4) {
            if (!fa.multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            long c;
            switch (subop) {
                case 1 -> { // .lane_pop_sum
                    long sum = 0L;
                    for (int i = 0; i < fa.mlCount; i++) {
                        sum += Long.bitCount(fa.mlMasks[i]);
                    }
                    c = sum;
                }
                case 2 -> c = fa.mlCount; // .lane_any_nodes
                case 3 -> { // .lane_all_nodes
                    if (fa.activeLaneCount <= 0) {
                        c = 0L;
                    } else {
                        long req = (fa.activeLaneCount >= 64) ? -1L : ((1L << fa.activeLaneCount) - 1L);
                        long cnt = 0L;
                        for (int i = 0; i < fa.mlCount; i++) {
                            if ((fa.mlMasks[i] & req) == req) cnt++;
                        }
                        c = cnt;
                    }
                }
                case 4 -> { // .single_lane
                    int lane = imm16Lo;
                    if (lane < 0 || lane >= fa.allocWidth) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        return;
                    }
                    long bit = 1L << lane;
                    long cnt = 0L;
                    for (int i = 0; i < fa.mlCount; i++) {
                        if ((fa.mlMasks[i] & bit) != 0L) cnt++;
                    }
                    c = cnt;
                }
                default -> {
                    ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
                    return;
                }
            }
            ws.writeR(dst, SCALAR_U64, c);
            setScalarFlags(ws, true, c);
            ws.pc++;
        } else {
            ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    private static void execSetContains(V11Workspace ws, int subop, int dst, int s1, int s2) {
        if (!ws.isFInitialized(s1)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        V11Frontier fa = ws.fRegs[s1];
        if (subop == 0) {
            if (fa.multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (!ws.isRValid(s2)) {
                ws.writeRNull(dst);
                setScalarFlags(ws, false, 0L);
                ws.pc++;
                return;
            }
            long u = ws.rRegs[s2];
            if (u < 0L || Long.compareUnsigned(u, fa.domainCard) >= 0) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            long v = fa.contains((int) u) ? 1L : 0L;
            ws.writeR(dst, SCALAR_BOOL_3VL, v);
            setScalarFlags(ws, true, v);
            ws.pc++;
        } else if (subop == 1) {
            if (!fa.multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (!ws.isRValid(s2)) {
                ws.writeRNull(dst);
                setScalarFlags(ws, false, 0L);
                ws.pc++;
                return;
            }
            long u = ws.rRegs[s2];
            if (u < 0L || Long.compareUnsigned(u, fa.domainCard) >= 0) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            long mask = 0L;
            if (fa.mlNodes != null && fa.mlCount > 0) {
                int idx = Arrays.binarySearch(fa.mlNodes, 0, fa.mlCount, u);
                if (idx >= 0) {
                    mask = fa.mlMasks[idx];
                }
            }
            ws.writeR(dst, SCALAR_U64, mask);
            setScalarFlags(ws, true, mask);
            ws.pc++;
        } else {
            ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    private static void execSetConvert(
            V11Workspace ws, V11Interpreter.SnapshotBinding bind, int subop, boolean accum,
            int dst, int s1, int domId) {
        switch (subop) {
            case 0 -> { // .f2v_ids
                if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                int[] ids = ws.fRegs[s1].toSortedArray();
                long[] vals = new long[ids.length];
                for (int i = 0; i < ids.length; i++) vals[i] = ids[i] & 0xFFFFFFFFL;
                ws.vVectors[dst] = V11Vector.ofU64(vals, V11Vector.allocValidAllTrue(ids.length));
                ws.zf = (ids.length == 0);
                ws.pc++;
            }
            case 1 -> { // .v2f_ids
                int domCard = getDomainCard(bind, domId);
                if (domCard < 0) {
                    ws.status = -domCard;
                    return;
                }
                V11Vector vec = ws.vVectors[s1];
                if (vec == null || (vec.elemTag != V11Vector.TAG_I64 && vec.elemTag != V11Vector.TAG_U64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                int[] base;
                if (accum) {
                    if (!ws.isFInitialized(dst)) {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                    V11Frontier ex = ws.fRegs[dst];
                    if (ex.domainId != domId) {
                        ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                        return;
                    }
                    if (ex.multiLane) {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                    base = ex.toSortedArray();
                } else {
                    base = new int[0];
                }
                int[] buf = new int[base.length + vec.length];
                System.arraycopy(base, 0, buf, 0, base.length);
                int cnt = base.length;
                for (int i = 0; i < vec.length; i++) {
                    if (!vec.isValidAt(i)) continue;
                    long u = vec.i64Data[i];
                    if (u < 0L || Long.compareUnsigned(u, domCard) >= 0) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        return;
                    }
                    buf[cnt++] = (int) u;
                }
                int[] res = dedupeSorted(buf, cnt);
                setF1Bit(ws, dst, domId, domCard, res, false, true);
                ws.zf = (res.length == 0);
                ws.pc++;
            }
            case 2 -> { // .b2f
                V11Vector vec = ws.vVectors[s1];
                if (vec == null || vec.elemTag != V11Vector.TAG_BOOL_3VL) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                int domCard;
                if (bind != null) {
                    if (domId < 0 || domId >= bind.domainCards.length) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        return;
                    }
                    domCard = bind.domainCards[domId];
                } else {
                    domCard = vec.length;
                }
                if (vec.length > domCard) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                int[] base;
                if (accum) {
                    if (!ws.isFInitialized(dst)) {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                    V11Frontier ex = ws.fRegs[dst];
                    if (ex.domainId != domId) {
                        ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                        return;
                    }
                    if (ex.multiLane) {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                    base = ex.toSortedArray();
                } else {
                    base = new int[0];
                }
                int[] buf = new int[base.length + vec.length];
                System.arraycopy(base, 0, buf, 0, base.length);
                int cnt = base.length;
                for (int i = 0; i < vec.length; i++) {
                    if (vec.isValidAt(i) && vec.boolDataAt(i)) {
                        buf[cnt++] = i;
                    }
                }
                int[] res = dedupeSorted(buf, cnt);
                setF1Bit(ws, dst, domId, domCard, res, false, true);
                ws.zf = (res.length == 0);
                ws.pc++;
            }
            case 3 -> { // .f2b
                if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                V11Frontier fa = ws.fRegs[s1];
                int n = fa.domainCard;
                int words = Math.max(1, (n + 63) >>> 6);
                long[] valid = new long[words];
                long[] data = new long[words];
                for (int i = 0; i < n; i++) {
                    valid[i >>> 6] |= (1L << (i & 63));
                }
                int[] ids = fa.toSortedArray();
                for (int u : ids) {
                    data[u >>> 6] |= (1L << (u & 63));
                }
                ws.vVectors[dst] = V11Vector.ofBool3vl(n, valid, data);
                ws.zf = (ids.length == 0);
                ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    private static int[] dedupeSorted(int[] buf, int len) {
        if (len == 0) return new int[0];
        Arrays.sort(buf, 0, len);
        int uniq = 1;
        for (int i = 1; i < len; i++) {
            if (buf[i] != buf[uniq - 1]) {
                buf[uniq++] = buf[i];
            }
        }
        return Arrays.copyOf(buf, uniq);
    }

    private static void execStableCheck(V11Workspace ws, int subop, int dst, int s1, int s2, int s3) {
        if (subop == 0) { // .frontier_eq
            if (!ws.isFInitialized(s1) || !ws.isFInitialized(s2)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            V11Frontier fa = ws.fRegs[s1];
            V11Frontier fb = ws.fRegs[s2];
            if (fa.domainId != fb.domainId) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
            if (fa.multiLane != fb.multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            boolean eq;
            if (!fa.multiLane) {
                eq = Arrays.equals(fa.toSortedArray(), fb.toSortedArray());
            } else {
                if (fa.allocWidth != fb.allocWidth) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                eq = Arrays.equals(fa.mlSortedNodes(), fb.mlSortedNodes())
                        && Arrays.equals(fa.mlSortedMasks(), fb.mlSortedMasks());
            }
            ws.st = eq;
            ws.zf = eq;
            ws.pc++;
        } else if (subop == 1) { // .vec_l1
            if (!ws.isRValid(s3)) {
                ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
                return;
            }
            V11Vector v1 = ws.vVectors[s1];
            V11Vector v2 = ws.vVectors[s2];
            if (v1 == null || v2 == null) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            double eps = Double.longBitsToDouble(ws.rRegs[s3]);
            if (Double.isNaN(eps)) {
                ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
                return;
            }
            if (!isNumericVec(v1) || !isNumericVec(v2)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (v1.length != v2.length) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            double sum = 0.0;
            boolean nullMismatch = false;
            for (int i = 0; i < v1.length; i++) {
                boolean b1 = v1.isValidAt(i);
                boolean b2 = v2.isValidAt(i);
                if (!b1 && !b2) {
                    continue;
                }
                if (b1 != b2) {
                    nullMismatch = true;
                } else {
                    double x = readNumericAsF64(v1, i);
                    double y = readNumericAsF64(v2, i);
                    sum += Math.abs(x - y);
                }
            }
            if (Double.isNaN(sum)) {
                ws.writeRNull(dst);
                ws.zf = true;
                ws.st = false;
                ws.nf = true;
            } else {
                ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(sum));
                ws.zf = (sum == 0.0);
                ws.st = (!nullMismatch && sum <= eps);
                ws.nf = false;
            }
            ws.pc++;
        } else {
            ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    private static boolean isNumericVec(V11Vector v) {
        return v != null && (v.elemTag == V11Vector.TAG_F64
                || v.elemTag == V11Vector.TAG_I64
                || v.elemTag == V11Vector.TAG_U64);
    }

    private static double readNumericAsF64(V11Vector v, int idx) {
        return switch (v.elemTag) {
            case V11Vector.TAG_F64 -> v.f64Data[idx];
            case V11Vector.TAG_I64, V11Vector.TAG_U64 -> (double) v.i64Data[idx];
            default -> 0.0;
        };
    }
}
