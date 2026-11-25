package org.impulsegraph.vm.v11;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.impulsegraph.vm.v11.V11IsaTables.*;

/**
 * Pillar 4 Operations for ImpulseVM v0.11.0 ({@code 0x40}–{@code 0x57}):
 * Column loads, node/edge/vector gathers, node filtering, vector comparisons,
 * 3VL Kleene logic, string predicates, math/bitwise/ternary (delegated to
 * {@link V11Pillar4MathOps}), blend, coalesce, vector init/point/reduce, top-K,
 * index scan, key<->dense mapping, and terminal {@code OP_COLLECT}.
 */
public final class V11Pillar4Ops {

    public static final int RESULT_NOT_HANDLED = 0;
    public static final int RESULT_STEPPED     = 1;

    private V11Pillar4Ops() {}

    public static int step(V11Workspace ws, V11Interpreter.SnapshotBinding bind, long w) {
        int op = (int) (w & 0xFFL);
        if (op >= OP_VEC_MATH_ARITH && op <= OP_VEC_TERNARY) {
            return V11Pillar4MathOps.step(ws, w);
        }

        int subop   = (int) ((w >>> 8) & 0xFL);
        int flags   = (int) ((w >>> 12) & 0xFL);
        int s1      = (int) ((w >>> 16) & 0xFL);
        int dst     = (int) ((w >>> 20) & 0xFL);
        int s3      = (int) ((w >>> 24) & 0xFL);
        int s2      = (int) ((w >>> 28) & 0xFL);
        int imm32   = (int) (w >>> 32);
        int imm16Lo = imm32 & 0xFFFF;
        int imm16Hi = (imm32 >>> 16) & 0xFFFF;

        return switch (op) {
            case OP_LOAD_COLUMN       -> { execLoadColumn(ws, bind, subop, dst, imm16Lo, imm16Hi); yield RESULT_STEPPED; }
            case OP_GATHER_NODE_ATTR  -> { execGatherNodeAttr(ws, bind, subop, flags, dst, s1, s3, imm16Lo, imm16Hi); yield RESULT_STEPPED; }
            case OP_GATHER_EDGE_ATTR  -> { execGatherEdgeAttr(ws, bind, subop, flags, dst, s1, s3, imm16Lo, imm16Hi); yield RESULT_STEPPED; }
            case OP_GATHER_VEC        -> { execGatherVec(ws, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_NODE_FILTER       -> { execNodeFilter(ws, bind, subop, flags, dst, s1, s2, s3, imm16Lo, imm16Hi); yield RESULT_STEPPED; }
            case OP_VEC_CMP           -> { execVecCmp(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_3VL_OP        -> { execVec3vl(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_STR_PRED      -> { execVecStrPred(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_BLEND         -> { execVecBlend(ws, subop, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_COALESCE          -> { execCoalesce(ws, flags, dst, s1, s2, s3); yield RESULT_STEPPED; }
            case OP_VEC_INIT          -> { execVecInit(ws, bind, subop, flags, dst, s2, imm16Lo); yield RESULT_STEPPED; }
            case OP_VEC_POINT         -> { execVecPoint(ws, subop, dst, s1, s2, imm16Lo); yield RESULT_STEPPED; }
            case OP_VEC_REDUCE        -> { execVecReduce(ws, subop, flags, dst, s1, s3); yield RESULT_STEPPED; }
            case OP_TOP_K             -> { execTopK(ws, subop, flags, dst, s1, s2, s3, imm16Lo); yield RESULT_STEPPED; }
            case OP_INDEX_SCAN        -> { execIndexScan(ws, bind, subop, flags, dst, s2, s3, imm16Lo, imm16Hi); yield RESULT_STEPPED; }
            case OP_MAP_KEYS_TO_DENSE -> { execMapKeysToDense(ws, bind, subop, flags, dst, s1, imm16Lo, imm16Hi); yield RESULT_STEPPED; }
            case OP_MAP_DENSE_TO_KEYS -> { execMapDenseToKeys(ws, bind, subop, dst, s1, imm16Lo); yield RESULT_STEPPED; }
            case OP_COLLECT           -> { execCollect(ws, subop, flags, s1, s3, imm16Lo); yield RESULT_STEPPED; }
            default -> RESULT_NOT_HANDLED;
        };
    }

    // --- Shared Helpers ---

    private static V11SnapshotView requireView(V11Workspace ws, V11Interpreter.SnapshotBinding bind) {
        if (bind == null || bind.view == null) {
            ws.status = IMPULSE_VM_ERR_NULL_SNAPSHOT;
            return null;
        }
        return bind.view;
    }

    private static V11Vector gatherFromVec(V11Vector col, int[] indices, boolean[] idxValid, V11Frontier mask) {
        int n = indices.length;
        int words = Math.max(1, (n + 63) >>> 6);
        switch (col.elemTag) {
            case V11Vector.TAG_I64, V11Vector.TAG_U64 -> {
                long[] out = new long[n];
                long[] valid = new long[words];
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (idxValid != null && !idxValid[i]) continue;
                    int idx = indices[i];
                    if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
                        out[i] = col.i64Data[idx];
                        valid[i >>> 6] |= (1L << (i & 63));
                    }
                }
                return col.elemTag == V11Vector.TAG_U64 ? V11Vector.ofU64(out, valid) : V11Vector.ofI64(out, valid);
            }
            case V11Vector.TAG_F64 -> {
                double[] out = new double[n];
                long[] valid = new long[words];
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (idxValid != null && !idxValid[i]) continue;
                    int idx = indices[i];
                    if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
                        out[i] = col.f64Data[idx];
                        valid[i >>> 6] |= (1L << (i & 63));
                    }
                }
                return V11Vector.ofF64(out, valid);
            }
            case V11Vector.TAG_BOOL_3VL -> {
                long[] valid = new long[words];
                long[] data = new long[words];
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (idxValid != null && !idxValid[i]) continue;
                    int idx = indices[i];
                    if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
                        valid[i >>> 6] |= (1L << (i & 63));
                        if (col.boolDataAt(idx)) data[i >>> 6] |= (1L << (i & 63));
                    }
                }
                return V11Vector.ofBool3vl(n, valid, data);
            }
            case V11Vector.TAG_STR -> {
                String[] out = new String[n];
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (idxValid != null && !idxValid[i]) continue;
                    int idx = indices[i];
                    if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
                        out[i] = col.strData[idx];
                    }
                }
                return V11Vector.ofStr(out);
            }
            case V11Vector.TAG_EMB -> {
                double[][] out = new double[n][];
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (idxValid != null && !idxValid[i]) continue;
                    int idx = indices[i];
                    if (idx >= 0 && idx < col.length && col.isValidAt(idx)) {
                        out[i] = col.embData[idx];
                    }
                }
                return V11Vector.ofEmb(col.embDim, out);
            }
            default -> {
                return V11Vector.ofI64(new long[0], new long[1]);
            }
        }
    }

    private static double vecElemAsF64(V11Vector v, int i) {
        if (v.elemTag == V11Vector.TAG_F64) return v.f64Data[i];
        return (double) v.i64Data[i];
    }

    private static void writeVecElemToScalar(V11Workspace ws, int dst, V11Vector col, int idx) {
        if (!col.isValidAt(idx)) {
            ws.writeRNull(dst);
            ws.zf = true;
            ws.nf = true;
            return;
        }
        switch (col.elemTag) {
            case V11Vector.TAG_I64 -> {
                long v = col.i64Data[idx];
                ws.writeR(dst, SCALAR_I64, v);
                ws.zf = (v == 0L);
                ws.nf = false;
            }
            case V11Vector.TAG_U64 -> {
                long v = col.i64Data[idx];
                ws.writeR(dst, SCALAR_U64, v);
                ws.zf = (v == 0L);
                ws.nf = false;
            }
            case V11Vector.TAG_F64 -> {
                double f = col.f64Data[idx];
                if (Double.isNaN(f)) {
                    ws.writeRNull(dst);
                    ws.zf = true;
                    ws.nf = true;
                } else {
                    ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(f));
                    ws.zf = (f == 0.0);
                    ws.nf = false;
                }
            }
            case V11Vector.TAG_BOOL_3VL -> {
                long b = col.boolDataAt(idx) ? 1L : 0L;
                ws.writeR(dst, SCALAR_BOOL_3VL, b);
                ws.zf = (b == 0L);
                ws.nf = false;
            }
            case V11Vector.TAG_STR -> {
                String s = col.strData[idx];
                if (s == null) {
                    ws.writeRNull(dst);
                    ws.zf = true;
                    ws.nf = true;
                } else {
                    long h = ws.internScalarStr(s);
                    ws.writeR(dst, SCALAR_STR_HANDLE, h);
                    ws.zf = s.isEmpty();
                    ws.nf = false;
                }
            }
            default -> ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
        }
    }

    private static boolean evalCmpOp(int subop, int cmp) {
        return switch (subop) {
            case 0 -> cmp == 0;
            case 1 -> cmp != 0;
            case 2 -> cmp < 0;
            case 3 -> cmp <= 0;
            case 4 -> cmp > 0;
            case 5 -> cmp >= 0;
            default -> false;
        };
    }

    private static boolean evalCmpF64(int subop, double a, double b) {
        return switch (subop) {
            case 0 -> a == b;
            case 1 -> a != b;
            case 2 -> a < b;
            case 3 -> a <= b;
            case 4 -> a > b;
            case 5 -> a >= b;
            default -> false;
        };
    }

    // --- 0x40 OP_LOAD_COLUMN ---

    private static void execLoadColumn(V11Workspace ws, V11Interpreter.SnapshotBinding bind,
                                       int subop, int dst, int imm16Lo, int imm16Hi) {
        V11SnapshotView view = requireView(ws, bind);
        if (view == null) return;
        V11Vector col;
        if (subop == SUBOP_LOAD_COLUMN_NODE_ATTR) {
            if (imm16Lo >= view.domainCards.length) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            col = view.getNodeAttr(imm16Lo, imm16Hi);
            if (col == null) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        } else if (subop == SUBOP_LOAD_COLUMN_EDGE_ATTR) {
            if (imm16Lo >= view.relations.length || view.relations[imm16Lo] == null) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            col = view.relations[imm16Lo].edgeAttrs.get(imm16Hi);
            if (col == null) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        } else {
            ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
            return;
        }
        ws.vVectors[dst] = col.shallowCopy();
        ws.pc++;
    }

    // --- 0x41 OP_GATHER_NODE_ATTR ---

    private static void execGatherNodeAttr(V11Workspace ws, V11Interpreter.SnapshotBinding bind,
                                           int subop, int flags, int dst, int s1, int s3, int domId, int attrId) {
        V11SnapshotView view = requireView(ws, bind);
        if (view == null) return;
        if (domId >= view.domainCards.length) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        V11Vector col = view.getNodeAttr(domId, attrId);
        if (col == null) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        switch (subop) {
            case SUBOP_GATHER_NODE_ATTR_VEC -> {
                V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
                if (ws.status != IMPULSE_VM_OK) return;
                V11Vector idxVec = ws.vVectors[s1];
                if (idxVec == null || (idxVec.elemTag != V11Vector.TAG_I64 && idxVec.elemTag != V11Vector.TAG_U64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                int n = idxVec.length;
                int[] indices = new int[n];
                boolean[] valid = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i) || !idxVec.isValidAt(i)) continue;
                    long u = idxVec.i64Data[i];
                    if (u < 0L || u >= col.length) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        return;
                    }
                    indices[i] = (int) u;
                    valid[i] = true;
                }
                ws.vVectors[dst] = gatherFromVec(col, indices, valid, mask);
                ws.pc++;
            }
            case SUBOP_GATHER_NODE_ATTR_FRONTIER_COMPACT -> {
                if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                V11Frontier f = ws.fRegs[s1];
                if (f.domainId != domId) {
                    ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                    return;
                }
                int[] ids = f.toSortedArray();
                ws.vVectors[dst] = gatherFromVec(col, ids, null, null);
                ws.pc++;
            }
            case SUBOP_GATHER_NODE_ATTR_SCALAR -> {
                if (!ws.isRValid(s1)) {
                    ws.writeRNull(dst);
                    ws.zf = true;
                    ws.nf = true;
                    ws.pc++;
                    return;
                }
                long u = ws.rRegs[s1];
                if (u < 0L || u >= col.length) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                writeVecElemToScalar(ws, dst, col, (int) u);
                if (ws.status == IMPULSE_VM_OK) ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    // --- 0x42 OP_GATHER_EDGE_ATTR ---

    private static void execGatherEdgeAttr(V11Workspace ws, V11Interpreter.SnapshotBinding bind,
                                           int subop, int flags, int dst, int s1, int s3, int relId, int attrId) {
        V11SnapshotView view = requireView(ws, bind);
        if (view == null) return;
        if (relId >= view.relations.length || view.relations[relId] == null) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        V11Vector col = view.relations[relId].edgeAttrs.get(attrId);
        if (col == null) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        switch (subop) {
            case SUBOP_GATHER_EDGE_ATTR_VEC -> {
                V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
                if (ws.status != IMPULSE_VM_OK) return;
                V11Vector idxVec = ws.vVectors[s1];
                if (idxVec == null || (idxVec.elemTag != V11Vector.TAG_I64 && idxVec.elemTag != V11Vector.TAG_U64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                int n = idxVec.length;
                int[] indices = new int[n];
                boolean[] valid = new boolean[n];
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i) || !idxVec.isValidAt(i)) continue;
                    long e = idxVec.i64Data[i];
                    if (e < 0L || e >= col.length) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                        return;
                    }
                    indices[i] = (int) e;
                    valid[i] = true;
                }
                ws.vVectors[dst] = gatherFromVec(col, indices, valid, mask);
                ws.pc++;
            }
            case SUBOP_GATHER_EDGE_ATTR_SCALAR -> {
                if (!ws.isRValid(s1)) {
                    ws.writeRNull(dst);
                    ws.zf = true;
                    ws.nf = true;
                    ws.pc++;
                    return;
                }
                long e = ws.rRegs[s1];
                if (e < 0L || e >= col.length) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                writeVecElemToScalar(ws, dst, col, (int) e);
                if (ws.status == IMPULSE_VM_OK) ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    // --- 0x43 OP_GATHER_VEC ---

    private static void execGatherVec(V11Workspace ws, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector srcVec = ws.vVectors[s1];
        V11Vector idxVec = ws.vVectors[s2];
        if (srcVec == null || idxVec == null
                || (idxVec.elemTag != V11Vector.TAG_I64 && idxVec.elemTag != V11Vector.TAG_U64)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = idxVec.length;
        int[] indices = new int[n];
        boolean[] valid = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!V11Pillar4MathOps.laneActive(mask, i) || !idxVec.isValidAt(i)) continue;
            long idx = idxVec.i64Data[i];
            if (idx < 0L || idx >= srcVec.length) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            indices[i] = (int) idx;
            valid[i] = true;
        }
        ws.vVectors[dst] = gatherFromVec(srcVec, indices, valid, mask);
        ws.pc++;
    }

    // --- 0x44 OP_NODE_FILTER ---

    private static void execNodeFilter(V11Workspace ws, V11Interpreter.SnapshotBinding bind,
                                       int subop, int flags, int dst, int s1, int s2, int s3,
                                       int domId, int attrId) {
        V11SnapshotView view = requireView(ws, bind);
        if (view == null) return;
        if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        V11Frontier fSrc = ws.fRegs[s1];
        if (fSrc.domainId != domId) {
            ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
            return;
        }
        if (domId >= view.domainCards.length) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        V11Vector col = view.getNodeAttr(domId, attrId);
        if (col == null) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        int domCard = (int) view.domainCards[domId];
        boolean accum = (flags & FLAG_ACCUM) != 0;
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        if (accum) {
            if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (ws.fRegs[dst].domainId != domId) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
                return;
            }
        }
        int[] srcIds = fSrc.toSortedArray();
        V11Frontier out = new V11Frontier(Math.max(domCard, 64));
        if (accum) {
            out.copyFrom(ws.fRegs[dst]);
        } else {
            out.resetEmpty(domId, domCard);
        }

        V11Vector vS2 = scalarRhs ? null : ws.vVectors[s2];
        V11Vector vS3 = scalarRhs ? null : ws.vVectors[s3];

        for (int u : srcIds) {
            if (u < 0 || u >= col.length) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            boolean colValid = col.isValidAt(u);
            boolean keep = false;
            if (subop == SUBOP_NODE_FILTER_IS_NULL) {
                keep = !colValid;
            } else if (subop == SUBOP_NODE_FILTER_IS_NOT_NULL) {
                keep = colValid;
            } else if (colValid) {
                if (col.elemTag == V11Vector.TAG_F64) {
                    double x = col.f64Data[u];
                    boolean loOk = scalarRhs ? V11Pillar4MathOps.isScalarF64BitsValid(ws, s2)
                            : (vS2 != null && (vS2.elemTag == V11Vector.TAG_F64 || vS2.elemTag == V11Vector.TAG_I64 || vS2.elemTag == V11Vector.TAG_U64)
                               && u < vS2.length && vS2.isValidAt(u));
                    if (loOk) {
                        double lo = scalarRhs ? V11Pillar4MathOps.readScalarF64Bits(ws, s2) : vecElemAsF64(vS2, u);
                        if (subop == SUBOP_NODE_FILTER_BETWEEN) {
                            boolean hiOk = scalarRhs ? V11Pillar4MathOps.isScalarF64BitsValid(ws, s3)
                                    : (vS3 != null && (vS3.elemTag == V11Vector.TAG_F64 || vS3.elemTag == V11Vector.TAG_I64 || vS3.elemTag == V11Vector.TAG_U64)
                                       && u < vS3.length && vS3.isValidAt(u));
                            if (hiOk) {
                                double hi = scalarRhs ? V11Pillar4MathOps.readScalarF64Bits(ws, s3) : vecElemAsF64(vS3, u);
                                keep = (x >= lo && x <= hi);
                            }
                        } else {
                            keep = evalCmpF64(subop, x, lo);
                        }
                    }
                } else if (col.elemTag == V11Vector.TAG_I64 || col.elemTag == V11Vector.TAG_U64) {
                    boolean isU = (col.elemTag == V11Vector.TAG_U64);
                    long x = col.i64Data[u];
                    boolean loOk = scalarRhs ? ws.isRValid(s2)
                            : (vS2 != null && (vS2.elemTag == V11Vector.TAG_I64 || vS2.elemTag == V11Vector.TAG_U64)
                               && u < vS2.length && vS2.isValidAt(u));
                    if (loOk) {
                        long lo = scalarRhs ? ws.rRegs[s2] : vS2.i64Data[u];
                        if (subop == SUBOP_NODE_FILTER_BETWEEN) {
                            boolean hiOk = scalarRhs ? ws.isRValid(s3)
                                    : (vS3 != null && (vS3.elemTag == V11Vector.TAG_I64 || vS3.elemTag == V11Vector.TAG_U64)
                                       && u < vS3.length && vS3.isValidAt(u));
                            if (hiOk) {
                                long hi = scalarRhs ? ws.rRegs[s3] : vS3.i64Data[u];
                                keep = isU
                                        ? (Long.compareUnsigned(x, lo) >= 0 && Long.compareUnsigned(x, hi) <= 0)
                                        : (x >= lo && x <= hi);
                            }
                        } else {
                            int cmp = isU ? Long.compareUnsigned(x, lo) : Long.compare(x, lo);
                            keep = evalCmpOp(subop, cmp);
                        }
                    }
                }
            }
            if (keep) out.add(u);
        }

        ws.fRegs[dst].copyFrom(out);
        ws.setFInitialized(dst, true);
        ws.zf = ws.fRegs[dst].isEmpty();
        ws.pc++;
    }

    // --- 0x45 OP_VEC_CMP ---

    private static void execVecCmp(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Vector va = ws.vVectors[s1];
        if (va == null) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        int words = Math.max(1, (n + 63) >>> 6);
        long[] outValid = new long[words];
        long[] outData = new long[words];

        V11Frontier mask = (subop == SUBOP_VEC_CMP_BETWEEN) ? null : V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;

        if (subop == SUBOP_VEC_CMP_IN_SET) {
            if ((va.elemTag != V11Vector.TAG_I64 && va.elemTag != V11Vector.TAG_U64)
                    || !ws.isFInitialized(s2) || ws.fRegs[s2].multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            V11Frontier fset = ws.fRegs[s2];
            for (int i = 0; i < n; i++) {
                if (!V11Pillar4MathOps.laneActive(mask, i) || !va.isValidAt(i)) continue;
                long u = va.i64Data[i];
                boolean mem = (u >= 0L && u < fset.domainCard && fset.contains((int) u));
                outValid[i >>> 6] |= (1L << (i & 63));
                if (mem) outData[i >>> 6] |= (1L << (i & 63));
            }
            ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
            ws.pc++;
            return;
        }

        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;

        if (va.elemTag == V11Vector.TAG_F64) {
            V11Vector vb = null, vc = null;
            if (!scalarRhs) {
                vb = ws.vVectors[s2];
                if (vb == null || (vb.elemTag != V11Vector.TAG_F64 && vb.elemTag != V11Vector.TAG_I64 && vb.elemTag != V11Vector.TAG_U64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                if (vb.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                if (subop == SUBOP_VEC_CMP_BETWEEN) {
                    vc = ws.vVectors[s3];
                    if (vc == null || (vc.elemTag != V11Vector.TAG_F64 && vc.elemTag != V11Vector.TAG_I64 && vc.elemTag != V11Vector.TAG_U64)) {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                    if (vc.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                }
            }
            boolean sbValid = scalarRhs && V11Pillar4MathOps.isScalarF64BitsValid(ws, s2);
            double sbVal = sbValid ? V11Pillar4MathOps.readScalarF64Bits(ws, s2) : Double.NaN;
            boolean scValid = scalarRhs && subop == SUBOP_VEC_CMP_BETWEEN && V11Pillar4MathOps.isScalarF64BitsValid(ws, s3);
            double scVal = scValid ? V11Pillar4MathOps.readScalarF64Bits(ws, s3) : Double.NaN;

            for (int i = 0; i < n; i++) {
                if (!V11Pillar4MathOps.laneActive(mask, i) || !va.isValidAt(i)) continue;
                double x = va.f64Data[i];
                if (Double.isNaN(x)) continue;
                if (scalarRhs ? !sbValid : !vb.isValidAt(i)) continue;
                double y = scalarRhs ? sbVal : vecElemAsF64(vb, i);
                if (Double.isNaN(y)) continue;
                boolean ok;
                if (subop == SUBOP_VEC_CMP_BETWEEN) {
                    if (scalarRhs ? !scValid : !vc.isValidAt(i)) continue;
                    double hi = scalarRhs ? scVal : vecElemAsF64(vc, i);
                    if (Double.isNaN(hi)) continue;
                    ok = (x >= y && x <= hi);
                } else {
                    ok = evalCmpF64(subop, x, y);
                }
                outValid[i >>> 6] |= (1L << (i & 63));
                if (ok) outData[i >>> 6] |= (1L << (i & 63));
            }
            ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
            ws.pc++;
            return;
        }

        if (va.elemTag == V11Vector.TAG_I64 || va.elemTag == V11Vector.TAG_U64) {
            boolean isU = (va.elemTag == V11Vector.TAG_U64);
            V11Vector vb = null, vc = null;
            if (!scalarRhs) {
                vb = ws.vVectors[s2];
                if (vb == null || (vb.elemTag != V11Vector.TAG_I64 && vb.elemTag != V11Vector.TAG_U64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                if (vb.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                if (subop == SUBOP_VEC_CMP_BETWEEN) {
                    vc = ws.vVectors[s3];
                    if (vc == null || (vc.elemTag != V11Vector.TAG_I64 && vc.elemTag != V11Vector.TAG_U64)) {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                    if (vc.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                }
            }
            boolean sbValid = scalarRhs && ws.isRValid(s2);
            long sbVal = ws.rRegs[s2];
            boolean scValid = scalarRhs && subop == SUBOP_VEC_CMP_BETWEEN && ws.isRValid(s3);
            long scVal = ws.rRegs[s3];

            for (int i = 0; i < n; i++) {
                if (!V11Pillar4MathOps.laneActive(mask, i) || !va.isValidAt(i)) continue;
                long x = va.i64Data[i];
                if (scalarRhs ? !sbValid : !vb.isValidAt(i)) continue;
                long y = scalarRhs ? sbVal : vb.i64Data[i];
                boolean ok;
                if (subop == SUBOP_VEC_CMP_BETWEEN) {
                    if (scalarRhs ? !scValid : !vc.isValidAt(i)) continue;
                    long hi = scalarRhs ? scVal : vc.i64Data[i];
                    ok = isU
                            ? (Long.compareUnsigned(x, y) >= 0 && Long.compareUnsigned(x, hi) <= 0)
                            : (x >= y && x <= hi);
                } else {
                    int cmp = isU ? Long.compareUnsigned(x, y) : Long.compare(x, y);
                    ok = evalCmpOp(subop, cmp);
                }
                outValid[i >>> 6] |= (1L << (i & 63));
                if (ok) outData[i >>> 6] |= (1L << (i & 63));
            }
            ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
            ws.pc++;
            return;
        }

        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
    }

    // --- 0x46 OP_VEC_3VL_OP ---

    private static void execVec3vl(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector va = ws.vVectors[s1];
        if (va == null) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;

        if (subop >= SUBOP_VEC_3VL_OP_IS_NAN && subop <= SUBOP_VEC_3VL_OP_ASSERT_FINITE) {
            if (va.elemTag != V11Vector.TAG_F64) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (subop == SUBOP_VEC_3VL_OP_ASSERT_FINITE) {
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (va.isValidAt(i) && !Double.isFinite(va.f64Data[i])) {
                        ws.status = IMPULSE_VM_ERR_ASSERTION_FAILED;
                        return;
                    }
                }
                ws.pc++;
                return;
            }
            int words = Math.max(1, (n + 63) >>> 6);
            long[] outValid = new long[words];
            long[] outData = new long[words];
            for (int i = 0; i < n; i++) {
                if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                boolean res = false;
                if (va.isValidAt(i)) {
                    double x = va.f64Data[i];
                    res = switch (subop) {
                        case SUBOP_VEC_3VL_OP_IS_NAN    -> Double.isNaN(x);
                        case SUBOP_VEC_3VL_OP_IS_INF    -> Double.isInfinite(x);
                        case SUBOP_VEC_3VL_OP_IS_FINITE -> Double.isFinite(x);
                        default -> false;
                    };
                }
                outValid[i >>> 6] |= (1L << (i & 63));
                if (res) outData[i >>> 6] |= (1L << (i & 63));
            }
            ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
            ws.pc++;
            return;
        }

        if (subop == SUBOP_VEC_3VL_OP_IS_NULL || subop == SUBOP_VEC_3VL_OP_IS_NOT_NULL) {
            int words = Math.max(1, (n + 63) >>> 6);
            long[] outValid = new long[words];
            long[] outData = new long[words];
            for (int i = 0; i < n; i++) {
                if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                boolean res = (subop == SUBOP_VEC_3VL_OP_IS_NULL) ? !va.isValidAt(i) : va.isValidAt(i);
                outValid[i >>> 6] |= (1L << (i & 63));
                if (res) outData[i >>> 6] |= (1L << (i & 63));
            }
            ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
            ws.pc++;
            return;
        }

        if (va.elemTag != V11Vector.TAG_BOOL_3VL) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        boolean isBinary = (subop == SUBOP_VEC_3VL_OP_AND || subop == SUBOP_VEC_3VL_OP_OR || subop == SUBOP_VEC_3VL_OP_XOR);
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        V11Vector vb = null;
        if (isBinary && !scalarRhs) {
            vb = ws.vVectors[s2];
            if (vb == null || vb.elemTag != V11Vector.TAG_BOOL_3VL) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vb.length != n) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
        }
        boolean sValid = isBinary && scalarRhs && ws.isRValid(s2);
        boolean sVal = sValid && (ws.rRegs[s2] != 0L);

        int words = Math.max(1, (n + 63) >>> 6);
        long[] outValid = new long[words];
        long[] outData = new long[words];

        for (int i = 0; i < n; i++) {
            if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
            boolean av = va.isValidAt(i);
            boolean ad = av && va.boolDataAt(i);
            boolean rv, rd;
            switch (subop) {
                case SUBOP_VEC_3VL_OP_AND -> {
                    boolean bv = scalarRhs ? sValid : vb.isValidAt(i);
                    boolean bd = bv && (scalarRhs ? sVal : vb.boolDataAt(i));
                    if ((av && !ad) || (bv && !bd)) { rv = true; rd = false; }
                    else if (av && ad && bv && bd) { rv = true; rd = true; }
                    else { rv = false; rd = false; }
                }
                case SUBOP_VEC_3VL_OP_OR -> {
                    boolean bv = scalarRhs ? sValid : vb.isValidAt(i);
                    boolean bd = bv && (scalarRhs ? sVal : vb.boolDataAt(i));
                    if ((av && ad) || (bv && bd)) { rv = true; rd = true; }
                    else if (av && !ad && bv && !bd) { rv = true; rd = false; }
                    else { rv = false; rd = false; }
                }
                case SUBOP_VEC_3VL_OP_NOT -> { rv = av; rd = av && !ad; }
                case SUBOP_VEC_3VL_OP_XOR -> {
                    boolean bv = scalarRhs ? sValid : vb.isValidAt(i);
                    boolean bd = bv && (scalarRhs ? sVal : vb.boolDataAt(i));
                    if (av && bv) { rv = true; rd = (ad ^ bd); }
                    else { rv = false; rd = false; }
                }
                case SUBOP_VEC_3VL_OP_IS_TRUE -> { rv = true; rd = (av && ad); }
                case SUBOP_VEC_3VL_OP_IS_FALSE -> { rv = true; rd = (av && !ad); }
                default -> { rv = false; rd = false; }
            }
            if (rv) {
                outValid[i >>> 6] |= (1L << (i & 63));
                if (rd) outData[i >>> 6] |= (1L << (i & 63));
            }
        }
        ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
        ws.pc++;
    }

    // --- 0x47 OP_VEC_STR_PRED ---

    private static void execVecStrPred(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector va = ws.vVectors[s1];
        if (va == null || va.elemTag != V11Vector.TAG_STR) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        int words = Math.max(1, (n + 63) >>> 6);
        long[] outValid = new long[words];
        long[] outData = new long[words];

        if (subop == SUBOP_VEC_STR_PRED_LEN_EQ) {
            V11Vector vb = null;
            if (!scalarRhs) {
                vb = ws.vVectors[s2];
                if (vb == null || (vb.elemTag != V11Vector.TAG_I64 && vb.elemTag != V11Vector.TAG_U64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                if (vb.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
            }
            boolean sValid = scalarRhs && ws.isRValid(s2);
            long sLen = ws.rRegs[s2];
            for (int i = 0; i < n; i++) {
                if (!V11Pillar4MathOps.laneActive(mask, i) || !va.isValidAt(i)) continue;
                if (scalarRhs ? !sValid : !vb.isValidAt(i)) continue;
                long want = scalarRhs ? sLen : vb.i64Data[i];
                int byteLen = va.strData[i].getBytes(StandardCharsets.UTF_8).length;
                outValid[i >>> 6] |= (1L << (i & 63));
                if (byteLen == want) outData[i >>> 6] |= (1L << (i & 63));
            }
            ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
            ws.pc++;
            return;
        }

        V11Vector vb = null;
        String sStr = null;
        boolean sValid = false;
        if (scalarRhs) {
            if (ws.isRValid(s2)) {
                sStr = ws.resolveScalarStr(ws.rRegs[s2]);
                sValid = (sStr != null);
            }
        } else {
            vb = ws.vVectors[s2];
            if (vb == null || vb.elemTag != V11Vector.TAG_STR) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vb.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
        }

        for (int i = 0; i < n; i++) {
            if (!V11Pillar4MathOps.laneActive(mask, i) || !va.isValidAt(i)) continue;
            String a = va.strData[i];
            String b;
            if (scalarRhs) {
                if (!sValid) continue;
                b = sStr;
            } else {
                if (!vb.isValidAt(i)) continue;
                b = vb.strData[i];
            }
            boolean ok = switch (subop) {
                case SUBOP_VEC_STR_PRED_EQ          -> a.equals(b);
                case SUBOP_VEC_STR_PRED_NE          -> !a.equals(b);
                case SUBOP_VEC_STR_PRED_LT          -> a.compareTo(b) < 0;
                case SUBOP_VEC_STR_PRED_LE          -> a.compareTo(b) <= 0;
                case SUBOP_VEC_STR_PRED_GT          -> a.compareTo(b) > 0;
                case SUBOP_VEC_STR_PRED_GE          -> a.compareTo(b) >= 0;
                case SUBOP_VEC_STR_PRED_STARTS_WITH -> a.startsWith(b);
                case SUBOP_VEC_STR_PRED_ENDS_WITH   -> a.endsWith(b);
                case SUBOP_VEC_STR_PRED_CONTAINS    -> a.contains(b);
                default -> false;
            };
            outValid[i >>> 6] |= (1L << (i & 63));
            if (ok) outData[i >>> 6] |= (1L << (i & 63));
        }
        ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
        ws.pc++;
    }

    // --- 0x4E OP_VEC_BLEND ---
    // s1 = cond (BOOL_3VL), s2 = trueVal (R[s2] if FLAG_SCALAR_RHS else V[s2]),
    // s3 = falseVal (R[s3] if (subop & 1) == 1 else V[s3])

    private static void execVecBlend(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3) {
        V11Vector cond = ws.vVectors[s1];
        if (cond == null || cond.elemTag != V11Vector.TAG_BOOL_3VL) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = cond.length;
        boolean isCase = (subop >= 2);
        boolean tIsR = (flags & FLAG_SCALAR_RHS) != 0;
        boolean fIsR = (subop & 1) != 0;
        int words = Math.max(1, (n + 63) >>> 6);

        V11Vector vt = tIsR ? null : ws.vVectors[s2];
        if (!tIsR && vt != null && (vt.elemTag == V11Vector.TAG_I64 || vt.elemTag == V11Vector.TAG_U64)) {
            if (vt.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
            boolean isU = (vt.elemTag == V11Vector.TAG_U64);
            V11Vector vf = null;
            if (!fIsR) {
                vf = ws.vVectors[s3];
                if (vf == null || vf.elemTag != vt.elemTag) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                if (vf.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
            }
            boolean sfValid = fIsR && ws.isRValid(s3);
            long sfVal = ws.rRegs[s3];
            long[] out = new long[n];
            long[] outValid = new long[words];
            for (int i = 0; i < n; i++) {
                boolean cv = cond.isValidAt(i);
                boolean cd = cv && cond.boolDataAt(i);
                if (!cv && !isCase) continue;
                if (cv && cd) {
                    if (vt.isValidAt(i)) {
                        out[i] = vt.i64Data[i];
                        outValid[i >>> 6] |= (1L << (i & 63));
                    }
                } else if (fIsR) {
                    if (sfValid) {
                        out[i] = sfVal;
                        outValid[i >>> 6] |= (1L << (i & 63));
                    }
                } else if (vf.isValidAt(i)) {
                    out[i] = vf.i64Data[i];
                    outValid[i >>> 6] |= (1L << (i & 63));
                }
            }
            ws.vVectors[dst] = isU ? V11Vector.ofU64(out, outValid) : V11Vector.ofI64(out, outValid);
            ws.pc++;
            return;
        }

        // F64 path
        if (!tIsR) {
            if (vt == null || (vt.elemTag != V11Vector.TAG_F64 && vt.elemTag != V11Vector.TAG_I64 && vt.elemTag != V11Vector.TAG_U64)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vt.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
        }
        V11Vector vf = null;
        if (!fIsR) {
            vf = ws.vVectors[s3];
            if (vf == null || (vf.elemTag != V11Vector.TAG_F64 && vf.elemTag != V11Vector.TAG_I64 && vf.elemTag != V11Vector.TAG_U64)) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
            if (vf.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
        }
        boolean stValid = tIsR && V11Pillar4MathOps.isScalarF64BitsValid(ws, s2);
        double stVal = stValid ? V11Pillar4MathOps.readScalarF64Bits(ws, s2) : Double.NaN;
        boolean sfValid = fIsR && V11Pillar4MathOps.isScalarF64BitsValid(ws, s3);
        double sfVal = sfValid ? V11Pillar4MathOps.readScalarF64Bits(ws, s3) : Double.NaN;

        double[] out = new double[n];
        long[] outValid = new long[words];
        for (int i = 0; i < n; i++) {
            boolean cv = cond.isValidAt(i);
            boolean cd = cv && cond.boolDataAt(i);
            if (!cv && !isCase) continue;
            if (cv && cd) {
                if (tIsR) {
                    if (stValid) { out[i] = stVal; outValid[i >>> 6] |= (1L << (i & 63)); }
                } else if (vt.isValidAt(i)) {
                    out[i] = vecElemAsF64(vt, i); outValid[i >>> 6] |= (1L << (i & 63));
                }
            } else {
                if (fIsR) {
                    if (sfValid) { out[i] = sfVal; outValid[i >>> 6] |= (1L << (i & 63)); }
                } else if (vf.isValidAt(i)) {
                    out[i] = vecElemAsF64(vf, i); outValid[i >>> 6] |= (1L << (i & 63));
                }
            }
        }
        ws.vVectors[dst] = V11Vector.ofF64(out, outValid);
        ws.pc++;
    }

    // --- 0x4F OP_COALESCE ---

    private static void execCoalesce(V11Workspace ws, int flags, int dst, int s1, int s2, int s3) {
        V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector va = ws.vVectors[s1];
        if (va == null) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = va.length;
        boolean scalarRhs = (flags & FLAG_SCALAR_RHS) != 0;
        V11Vector vb = null;
        if (!scalarRhs) {
            vb = ws.vVectors[s2];
            if (vb == null) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
        }
        int words = Math.max(1, (n + 63) >>> 6);
        switch (va.elemTag) {
            case V11Vector.TAG_I64, V11Vector.TAG_U64 -> {
                if (!scalarRhs && vb.elemTag != V11Vector.TAG_I64 && vb.elemTag != V11Vector.TAG_U64) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                if (!scalarRhs && vb.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                long[] out = new long[n];
                long[] outValid = new long[words];
                boolean sv = scalarRhs && ws.isRValid(s2);
                long sr = ws.rRegs[s2];
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (va.isValidAt(i)) {
                        out[i] = va.i64Data[i]; outValid[i >>> 6] |= (1L << (i & 63));
                    } else if (scalarRhs) {
                        if (sv) { out[i] = sr; outValid[i >>> 6] |= (1L << (i & 63)); }
                    } else if (vb.isValidAt(i)) {
                        out[i] = vb.i64Data[i]; outValid[i >>> 6] |= (1L << (i & 63));
                    }
                }
                ws.vVectors[dst] = va.elemTag == V11Vector.TAG_U64
                        ? V11Vector.ofU64(out, outValid) : V11Vector.ofI64(out, outValid);
            }
            case V11Vector.TAG_F64 -> {
                if (!scalarRhs && vb.elemTag != V11Vector.TAG_F64 && vb.elemTag != V11Vector.TAG_I64 && vb.elemTag != V11Vector.TAG_U64) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                if (!scalarRhs && vb.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                double[] out = new double[n];
                long[] outValid = new long[words];
                boolean sv = scalarRhs && V11Pillar4MathOps.isScalarF64BitsValid(ws, s2);
                double sr = sv ? V11Pillar4MathOps.readScalarF64Bits(ws, s2) : Double.NaN;
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (va.isValidAt(i)) {
                        out[i] = va.f64Data[i]; outValid[i >>> 6] |= (1L << (i & 63));
                    } else if (scalarRhs) {
                        if (sv) { out[i] = sr; outValid[i >>> 6] |= (1L << (i & 63)); }
                    } else if (vb.isValidAt(i)) {
                        out[i] = vecElemAsF64(vb, i); outValid[i >>> 6] |= (1L << (i & 63));
                    }
                }
                ws.vVectors[dst] = V11Vector.ofF64(out, outValid);
            }
            case V11Vector.TAG_BOOL_3VL -> {
                if (!scalarRhs && vb.elemTag != V11Vector.TAG_BOOL_3VL) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                if (!scalarRhs && vb.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                long[] outValid = new long[words];
                long[] outData = new long[words];
                boolean sv = scalarRhs && ws.isRValid(s2);
                boolean sr = sv && (ws.rRegs[s2] != 0L);
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (va.isValidAt(i)) {
                        outValid[i >>> 6] |= (1L << (i & 63));
                        if (va.boolDataAt(i)) outData[i >>> 6] |= (1L << (i & 63));
                    } else if (scalarRhs) {
                        if (sv) {
                            outValid[i >>> 6] |= (1L << (i & 63));
                            if (sr) outData[i >>> 6] |= (1L << (i & 63));
                        }
                    } else if (vb.isValidAt(i)) {
                        outValid[i >>> 6] |= (1L << (i & 63));
                        if (vb.boolDataAt(i)) outData[i >>> 6] |= (1L << (i & 63));
                    }
                }
                ws.vVectors[dst] = V11Vector.ofBool3vl(n, outValid, outData);
            }
            case V11Vector.TAG_STR -> {
                if (!scalarRhs && vb.elemTag != V11Vector.TAG_STR) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                if (!scalarRhs && vb.length != n) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                String[] out = new String[n];
                String sr = (scalarRhs && ws.isRValid(s2)) ? ws.resolveScalarStr(ws.rRegs[s2]) : null;
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    out[i] = va.isValidAt(i) ? va.strData[i] : (scalarRhs ? sr : vb.strData[i]);
                }
                ws.vVectors[dst] = V11Vector.ofStr(out);
            }
            default -> {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                return;
            }
        }
        ws.pc++;
    }

    // --- 0x50 OP_VEC_INIT ---

    private static void execVecInit(V11Workspace ws, V11Interpreter.SnapshotBinding bind,
                                    int subop, int flags, int dst, int s2, int imm16Lo) {
        boolean domMode = (subop & 8) != 0;
        int len;
        if (domMode) {
            V11SnapshotView view = requireView(ws, bind);
            if (view == null) return;
            if (imm16Lo >= view.domainCards.length) {
                ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                return;
            }
            len = (int) view.domainCards[imm16Lo];
        } else {
            len = imm16Lo;
        }
        int baseType = subop & 7;
        boolean fillScalar = (flags & FLAG_SCALAR_RHS) != 0;
        int words = Math.max(1, (len + 63) >>> 6);
        boolean validFill = !fillScalar || ws.isRValid(s2);
        long[] valid = validFill ? V11Vector.allocValidAllTrue(len) : new long[words];

        switch (baseType) {
            case 0, 2 -> {
                long[] arr = new long[len];
                if (fillScalar && validFill) Arrays.fill(arr, ws.rRegs[s2]);
                ws.vVectors[dst] = V11Vector.ofI64(arr, valid);
            }
            case 1, 3 -> {
                long[] arr = new long[len];
                if (fillScalar && validFill) Arrays.fill(arr, ws.rRegs[s2]);
                ws.vVectors[dst] = V11Vector.ofU64(arr, valid);
            }
            case 4, 5 -> {
                double[] arr = new double[len];
                if (fillScalar) {
                    if (!V11Pillar4MathOps.isScalarF64BitsValid(ws, s2)) {
                        valid = new long[words];
                    } else {
                        Arrays.fill(arr, V11Pillar4MathOps.readScalarF64Bits(ws, s2));
                    }
                }
                ws.vVectors[dst] = V11Vector.ofF64(arr, valid);
            }
            case 6 -> {
                long[] data = new long[words];
                if (fillScalar && validFill && ws.rRegs[s2] != 0L) {
                    data = V11Vector.allocValidAllTrue(len);
                }
                ws.vVectors[dst] = V11Vector.ofBool3vl(len, valid, data);
            }
            default -> {
                ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
                return;
            }
        }
        ws.pc++;
    }

    // --- 0x51 OP_VEC_POINT ---

    private static void execVecPoint(V11Workspace ws, int subop, int dst, int s1, int s2, int imm16Lo) {
        switch (subop) {
            case SUBOP_VEC_POINT_VEC_GET -> {
                V11Vector v = ws.vVectors[s1];
                if (v == null) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                if (!ws.isRValid(s2)) {
                    ws.writeRNull(dst);
                    ws.zf = true;
                    ws.nf = true;
                    ws.pc++;
                    return;
                }
                long idx = ws.rRegs[s2];
                if (idx < 0L || idx >= v.length) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                writeVecElemToScalar(ws, dst, v, (int) idx);
                if (ws.status == IMPULSE_VM_OK) ws.pc++;
            }
            case SUBOP_VEC_POINT_VEC_SET -> {
                V11Vector vd = ws.vVectors[dst];
                if (vd == null) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                if (!ws.isRValid(s1)) { ws.status = IMPULSE_VM_ERR_INVALID_OPERAND; return; }
                long idx64 = ws.rRegs[s1];
                if (idx64 < 0L || idx64 >= vd.length) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                    return;
                }
                int idx = (int) idx64;
                boolean valValid = ws.isRValid(s2);
                int words = Math.max(1, (vd.length + 63) >>> 6);
                switch (vd.elemTag) {
                    case V11Vector.TAG_I64, V11Vector.TAG_U64 -> {
                        long[] arr = vd.i64Data.clone();
                        long[] valid = vd.validBits != null ? vd.validBits.clone() : V11Vector.allocValidAllTrue(vd.length);
                        if (valValid) {
                            arr[idx] = ws.rRegs[s2];
                            valid[idx >>> 6] |= (1L << (idx & 63));
                        } else {
                            valid[idx >>> 6] &= ~(1L << (idx & 63));
                        }
                        ws.vVectors[dst] = vd.elemTag == V11Vector.TAG_U64
                                ? V11Vector.ofU64(arr, valid) : V11Vector.ofI64(arr, valid);
                    }
                    case V11Vector.TAG_F64 -> {
                        double[] arr = vd.f64Data.clone();
                        long[] valid = vd.validBits != null ? vd.validBits.clone() : V11Vector.allocValidAllTrue(vd.length);
                        if (valValid && V11Pillar4MathOps.isScalarF64BitsValid(ws, s2)) {
                            arr[idx] = V11Pillar4MathOps.readScalarF64Bits(ws, s2);
                            valid[idx >>> 6] |= (1L << (idx & 63));
                        } else {
                            valid[idx >>> 6] &= ~(1L << (idx & 63));
                        }
                        ws.vVectors[dst] = V11Vector.ofF64(arr, valid);
                    }
                    case V11Vector.TAG_BOOL_3VL -> {
                        long[] valid = vd.validBits != null ? vd.validBits.clone() : V11Vector.allocValidAllTrue(vd.length);
                        long[] data = vd.boolDataBits != null ? vd.boolDataBits.clone() : new long[words];
                        if (valValid) {
                            valid[idx >>> 6] |= (1L << (idx & 63));
                            if (ws.rRegs[s2] != 0L) data[idx >>> 6] |= (1L << (idx & 63));
                            else data[idx >>> 6] &= ~(1L << (idx & 63));
                        } else {
                            valid[idx >>> 6] &= ~(1L << (idx & 63));
                            data[idx >>> 6] &= ~(1L << (idx & 63));
                        }
                        ws.vVectors[dst] = V11Vector.ofBool3vl(vd.length, valid, data);
                    }
                    case V11Vector.TAG_STR -> {
                        String[] arr = vd.strData.clone();
                        arr[idx] = valValid ? ws.resolveScalarStr(ws.rRegs[s2]) : null;
                        ws.vVectors[dst] = V11Vector.ofStr(arr);
                    }
                    default -> {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                }
                ws.pc++;
            }
            case SUBOP_VEC_POINT_VEC_CAST -> {
                V11Vector v = ws.vVectors[s1];
                if (v == null) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                int n = v.length;
                int words = Math.max(1, (n + 63) >>> 6);
                long[] outValid = new long[words];
                switch (imm16Lo) {
                    case 0, 2 -> {
                        if (v.elemTag != V11Vector.TAG_I64 && v.elemTag != V11Vector.TAG_U64 && v.elemTag != V11Vector.TAG_F64) {
                            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                        }
                        long[] out = new long[n];
                        for (int i = 0; i < n; i++) {
                            if (!v.isValidAt(i)) continue;
                            long val;
                            if (v.elemTag == V11Vector.TAG_F64) {
                                double f = v.f64Data[i];
                                if (Double.isNaN(f)) continue;
                                if (f >= 9223372036854775808.0) val = Long.MAX_VALUE;
                                else if (f <= -9223372036854775808.0) val = Long.MIN_VALUE;
                                else val = (long) f;
                            } else {
                                val = v.i64Data[i];
                            }
                            out[i] = val;
                            outValid[i >>> 6] |= (1L << (i & 63));
                        }
                        ws.vVectors[dst] = V11Vector.ofI64(out, outValid);
                    }
                    case 1, 3 -> {
                        if (v.elemTag != V11Vector.TAG_I64 && v.elemTag != V11Vector.TAG_U64 && v.elemTag != V11Vector.TAG_F64) {
                            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                        }
                        long[] out = new long[n];
                        for (int i = 0; i < n; i++) {
                            if (!v.isValidAt(i)) continue;
                            long val;
                            if (v.elemTag == V11Vector.TAG_F64) {
                                double f = v.f64Data[i];
                                if (Double.isNaN(f)) continue;
                                if (f <= 0.0) val = 0L;
                                else if (f >= 18446744073709551616.0) val = -1L;
                                else if (f >= 9223372036854775808.0) val = ((long) (f - 9223372036854775808.0)) | Long.MIN_VALUE;
                                else val = (long) f;
                            } else {
                                val = v.i64Data[i];
                            }
                            out[i] = val;
                            outValid[i >>> 6] |= (1L << (i & 63));
                        }
                        ws.vVectors[dst] = V11Vector.ofU64(out, outValid);
                    }
                    case 4, 5 -> {
                        if (v.elemTag != V11Vector.TAG_I64 && v.elemTag != V11Vector.TAG_U64 && v.elemTag != V11Vector.TAG_F64) {
                            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                        }
                        double[] out = new double[n];
                        for (int i = 0; i < n; i++) {
                            if (!v.isValidAt(i)) continue;
                            double f = (v.elemTag == V11Vector.TAG_F64) ? v.f64Data[i] : (double) v.i64Data[i];
                            if (Double.isNaN(f)) continue;
                            out[i] = f;
                            outValid[i >>> 6] |= (1L << (i & 63));
                        }
                        ws.vVectors[dst] = V11Vector.ofF64(out, outValid);
                    }
                    default -> {
                        ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                        return;
                    }
                }
                ws.pc++;
            }
            case SUBOP_VEC_POINT_VEC_LEN -> {
                V11Vector v = ws.vVectors[s1];
                if (v == null) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                long len = v.length & 0xFFFFFFFFL;
                ws.writeR(dst, SCALAR_U64, len);
                ws.zf = (len == 0L);
                ws.nf = false;
                ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    // --- 0x52 OP_VEC_REDUCE ---

    private static void execVecReduce(V11Workspace ws, int subop, int flags, int dst, int s1, int s3) {
        V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector v = ws.vVectors[s1];
        if (v == null) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = v.length;

        switch (subop) {
            case SUBOP_VEC_REDUCE_COUNT_VALID -> {
                long cnt = 0L;
                for (int i = 0; i < n; i++) {
                    if (V11Pillar4MathOps.laneActive(mask, i) && v.isValidAt(i)) cnt++;
                }
                ws.writeR(dst, SCALAR_U64, cnt);
                ws.zf = (cnt == 0L);
                ws.nf = false;
                ws.pc++;
            }
            case SUBOP_VEC_REDUCE_ANY_TRUE -> {
                if (v.elemTag != V11Vector.TAG_BOOL_3VL) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                boolean hasTrue = false, hasNull = false;
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (!v.isValidAt(i)) hasNull = true;
                    else if (v.boolDataAt(i)) { hasTrue = true; break; }
                }
                if (hasTrue) { ws.writeR(dst, SCALAR_BOOL_3VL, 1L); ws.zf = false; ws.nf = false; }
                else if (hasNull) { ws.writeRNull(dst); ws.zf = true; ws.nf = true; }
                else { ws.writeR(dst, SCALAR_BOOL_3VL, 0L); ws.zf = true; ws.nf = false; }
                ws.pc++;
            }
            case SUBOP_VEC_REDUCE_ALL_TRUE -> {
                if (v.elemTag != V11Vector.TAG_BOOL_3VL) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                boolean hasFalse = false, hasNull = false;
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i)) continue;
                    if (!v.isValidAt(i)) hasNull = true;
                    else if (!v.boolDataAt(i)) { hasFalse = true; break; }
                }
                if (hasFalse) { ws.writeR(dst, SCALAR_BOOL_3VL, 0L); ws.zf = true; ws.nf = false; }
                else if (hasNull) { ws.writeRNull(dst); ws.zf = true; ws.nf = true; }
                else { ws.writeR(dst, SCALAR_BOOL_3VL, 1L); ws.zf = false; ws.nf = false; }
                ws.pc++;
            }
            default -> {
                if (v.elemTag != V11Vector.TAG_F64 && v.elemTag != V11Vector.TAG_I64 && v.elemTag != V11Vector.TAG_U64) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
                    return;
                }
                int cnt = 0;
                double sum = 0.0, l1 = 0.0, l2sq = 0.0;
                boolean hasMn = false, hasMx = false;
                double mn = 0.0, mx = 0.0;
                int argmn = -1, argmx = -1;
                for (int i = 0; i < n; i++) {
                    if (!V11Pillar4MathOps.laneActive(mask, i) || !v.isValidAt(i)) continue;
                    double x = vecElemAsF64(v, i);
                    if (Double.isNaN(x)) continue;
                    cnt++;
                    sum += x;
                    l1 += Math.abs(x);
                    l2sq += x * x;
                    if (!hasMn) {
                        hasMn = true; mn = x; argmn = i;
                    } else if (x < mn) {
                        mn = x; argmn = i;
                    } else if (x == 0.0 && mn == 0.0) {
                        mn = V11Pillar4MathOps.ieeeMinF64(mn, x);
                    }
                    if (!hasMx) {
                        hasMx = true; mx = x; argmx = i;
                    } else if (x > mx) {
                        mx = x; argmx = i;
                    } else if (x == 0.0 && mx == 0.0) {
                        mx = V11Pillar4MathOps.ieeeMaxF64(mx, x);
                    }
                }
                switch (subop) {
                    case SUBOP_VEC_REDUCE_SUM -> writeF64Scalar(ws, dst, true, cnt == 0 ? 0.0 : sum);
                    case SUBOP_VEC_REDUCE_MIN -> writeF64Scalar(ws, dst, hasMn, mn);
                    case SUBOP_VEC_REDUCE_MAX -> writeF64Scalar(ws, dst, hasMx, mx);
                    case SUBOP_VEC_REDUCE_MEAN -> writeF64Scalar(ws, dst, cnt > 0, cnt > 0 ? sum / cnt : Double.NaN);
                    case SUBOP_VEC_REDUCE_L1_NORM -> writeF64Scalar(ws, dst, true, cnt == 0 ? 0.0 : l1);
                    case SUBOP_VEC_REDUCE_L2_NORM_SQ -> writeF64Scalar(ws, dst, true, cnt == 0 ? 0.0 : l2sq);
                    case SUBOP_VEC_REDUCE_ARGMIN -> {
                        if (argmn < 0) { ws.writeRNull(dst); ws.zf = true; ws.nf = true; }
                        else { ws.writeR(dst, SCALAR_U64, argmn & 0xFFFFFFFFL); ws.zf = (argmn == 0); ws.nf = false; }
                    }
                    case SUBOP_VEC_REDUCE_ARGMAX -> {
                        if (argmx < 0) { ws.writeRNull(dst); ws.zf = true; ws.nf = true; }
                        else { ws.writeR(dst, SCALAR_U64, argmx & 0xFFFFFFFFL); ws.zf = (argmx == 0); ws.nf = false; }
                    }
                    default -> { ws.status = IMPULSE_VM_ERR_INVALID_OPCODE; return; }
                }
                ws.pc++;
            }
        }
    }

    private static void writeF64Scalar(V11Workspace ws, int dst, boolean valid, double val) {
        if (!valid || Double.isNaN(val)) {
            ws.writeRNull(dst);
            ws.zf = true;
            ws.nf = true;
        } else {
            ws.writeR(dst, SCALAR_F64, Double.doubleToRawLongBits(val));
            ws.zf = (val == 0.0);
            ws.nf = false;
        }
    }

    // --- 0x53 OP_TOP_K ---

    private static void execTopK(V11Workspace ws, int subop, int flags, int dst, int s1, int s2, int s3, int imm16Lo) {
        V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
        if (ws.status != IMPULSE_VM_OK) return;
        V11Vector v = ws.vVectors[s1];
        if (v == null || (v.elemTag != V11Vector.TAG_F64 && v.elemTag != V11Vector.TAG_I64 && v.elemTag != V11Vector.TAG_U64)) {
            ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
            return;
        }
        int n = v.length;
        long k64;
        if ((flags & FLAG_SCALAR_RHS) != 0) {
            if (!ws.isRValid(s2) || ws.rRegs[s2] < 0L) {
                ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
                return;
            }
            k64 = ws.rRegs[s2];
        } else {
            k64 = imm16Lo;
        }
        int k = (int) Math.min(k64, n);

        Integer[] idxs = new Integer[n];
        double[] vals = new double[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            if (!V11Pillar4MathOps.laneActive(mask, i) || !v.isValidAt(i)) continue;
            double x = vecElemAsF64(v, i);
            if (Double.isNaN(x)) continue;
            vals[i] = x;
            idxs[m++] = i;
        }

        boolean desc = (subop == SUBOP_TOP_K_DESC_IDS || subop == SUBOP_TOP_K_DESC_SCORES);
        Arrays.sort(idxs, 0, m, (a, b) -> {
            double va = vals[a], vb = vals[b];
            int cmp = desc ? (vb < va ? -1 : vb > va ? 1 : 0) : (va < vb ? -1 : va > vb ? 1 : 0);
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });

        int take = Math.min(k, m);
        if (subop <= 1) {
            long[] out = new long[take];
            for (int i = 0; i < take; i++) out[i] = idxs[i] & 0xFFFFFFFFL;
            ws.vVectors[dst] = V11Vector.ofU64(out, V11Vector.allocValidAllTrue(take));
        } else {
            double[] out = new double[take];
            for (int i = 0; i < take; i++) out[i] = vals[idxs[i]];
            ws.vVectors[dst] = V11Vector.ofF64(out, V11Vector.allocValidAllTrue(take));
        }
        ws.zf = (take == 0);
        ws.pc++;
    }

    // --- 0x54 OP_INDEX_SCAN ---
    // lo = R[s2], hi = R[s3]

    private static void execIndexScan(V11Workspace ws, V11Interpreter.SnapshotBinding bind,
                                      int subop, int flags, int dst, int s2, int s3, int domId, int attrId) {
        V11SnapshotView view = requireView(ws, bind);
        if (view == null) return;
        if (domId >= view.domainCards.length || !view.hasIndex(domId, attrId)) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        V11Vector col = view.getNodeAttr(domId, attrId);
        if (col == null) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        int domCard = (int) view.domainCards[domId];
        boolean accum = (flags & FLAG_ACCUM) != 0;
        if (accum) {
            if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) {
                ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
            }
            if (ws.fRegs[dst].domainId != domId) {
                ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH; return;
            }
        }
        V11Frontier out = new V11Frontier(Math.max(domCard, 64));
        if (accum) out.copyFrom(ws.fRegs[dst]);
        else out.resetEmpty(domId, domCard);

        boolean loValid = ws.isRValid(s2);
        boolean hiValid = ws.isRValid(s3);
        long loBits = ws.rRegs[s2];
        long hiBits = ws.rRegs[s3];
        double loF = Double.longBitsToDouble(loBits);
        double hiF = Double.longBitsToDouble(hiBits);
        String loStr = loValid ? ws.resolveScalarStr(loBits) : null;
        String hiStr = hiValid ? ws.resolveScalarStr(hiBits) : null;

        int n = col.length;
        for (int u = 0; u < n; u++) {
            if (!col.isValidAt(u) || !loValid) continue;
            boolean keep = false;
            switch (col.elemTag) {
                case V11Vector.TAG_I64 -> {
                    long x = col.i64Data[u];
                    keep = switch (subop) {
                        case SUBOP_INDEX_SCAN_EQ -> x == loBits;
                        case SUBOP_INDEX_SCAN_BETWEEN_CC -> hiValid && x >= loBits && x <= hiBits;
                        case SUBOP_INDEX_SCAN_BETWEEN_CO -> hiValid && x >= loBits && x < hiBits;
                        case SUBOP_INDEX_SCAN_LT -> x < loBits;
                        case SUBOP_INDEX_SCAN_LE -> x <= loBits;
                        case SUBOP_INDEX_SCAN_GT -> x > loBits;
                        case SUBOP_INDEX_SCAN_GE -> x >= loBits;
                        default -> false;
                    };
                }
                case V11Vector.TAG_U64 -> {
                    long x = col.i64Data[u];
                    keep = switch (subop) {
                        case SUBOP_INDEX_SCAN_EQ -> x == loBits;
                        case SUBOP_INDEX_SCAN_BETWEEN_CC -> hiValid && Long.compareUnsigned(x, loBits) >= 0 && Long.compareUnsigned(x, hiBits) <= 0;
                        case SUBOP_INDEX_SCAN_BETWEEN_CO -> hiValid && Long.compareUnsigned(x, loBits) >= 0 && Long.compareUnsigned(x, hiBits) < 0;
                        case SUBOP_INDEX_SCAN_LT -> Long.compareUnsigned(x, loBits) < 0;
                        case SUBOP_INDEX_SCAN_LE -> Long.compareUnsigned(x, loBits) <= 0;
                        case SUBOP_INDEX_SCAN_GT -> Long.compareUnsigned(x, loBits) > 0;
                        case SUBOP_INDEX_SCAN_GE -> Long.compareUnsigned(x, loBits) >= 0;
                        default -> false;
                    };
                }
                case V11Vector.TAG_F64 -> {
                    double x = col.f64Data[u];
                    if (!Double.isNaN(loF)) {
                        keep = switch (subop) {
                            case SUBOP_INDEX_SCAN_EQ -> x == loF;
                            case SUBOP_INDEX_SCAN_BETWEEN_CC -> hiValid && !Double.isNaN(hiF) && x >= loF && x <= hiF;
                            case SUBOP_INDEX_SCAN_BETWEEN_CO -> hiValid && !Double.isNaN(hiF) && x >= loF && x < hiF;
                            case SUBOP_INDEX_SCAN_LT -> x < loF;
                            case SUBOP_INDEX_SCAN_LE -> x <= loF;
                            case SUBOP_INDEX_SCAN_GT -> x > loF;
                            case SUBOP_INDEX_SCAN_GE -> x >= loF;
                            default -> false;
                        };
                    }
                }
                case V11Vector.TAG_STR -> {
                    String x = col.strData[u];
                    if (loStr != null) {
                        keep = switch (subop) {
                            case SUBOP_INDEX_SCAN_EQ -> x.equals(loStr);
                            case SUBOP_INDEX_SCAN_BETWEEN_CC -> hiStr != null && x.compareTo(loStr) >= 0 && x.compareTo(hiStr) <= 0;
                            case SUBOP_INDEX_SCAN_BETWEEN_CO -> hiStr != null && x.compareTo(loStr) >= 0 && x.compareTo(hiStr) < 0;
                            case SUBOP_INDEX_SCAN_LT -> x.compareTo(loStr) < 0;
                            case SUBOP_INDEX_SCAN_LE -> x.compareTo(loStr) <= 0;
                            case SUBOP_INDEX_SCAN_GT -> x.compareTo(loStr) > 0;
                            case SUBOP_INDEX_SCAN_GE -> x.compareTo(loStr) >= 0;
                            default -> false;
                        };
                    }
                }
                default -> {}
            }
            if (keep) out.add(u);
        }
        ws.fRegs[dst].copyFrom(out);
        ws.setFInitialized(dst, true);
        ws.zf = ws.fRegs[dst].isEmpty();
        ws.pc++;
    }

    // --- 0x55 OP_MAP_KEYS_TO_DENSE ---

    private static int lookupKey(String[] catalog, String key) {
        if (catalog == null || key == null) return -1;
        for (int i = 0; i < catalog.length; i++) {
            if (key.equals(catalog[i])) return i;
        }
        return -1;
    }

    private static void execMapKeysToDense(V11Workspace ws, V11Interpreter.SnapshotBinding bind,
                                           int subop, int flags, int dst, int s1, int domId, int imm16Hi) {
        V11SnapshotView view = requireView(ws, bind);
        if (view == null) return;
        if (domId >= view.domainCards.length) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        String[] catalog = view.keyCatalogs.get(domId);
        if (catalog == null) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        int domCard = (int) view.domainCards[domId];
        boolean accum = (flags & FLAG_ACCUM) != 0;

        switch (subop) {
            case SUBOP_MAP_KEYS_TO_DENSE_PARAM_TO_F -> {
                V11Workspace.ParamEntry pe = ws.params.get(imm16Hi);
                if (pe == null) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                if (!(pe instanceof V11Workspace.ParamVec pv) || pv.vec().elemTag != V11Vector.TAG_STR) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                if (accum) {
                    if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                    if (ws.fRegs[dst].domainId != domId) { ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH; return; }
                }
                V11Frontier out = new V11Frontier(Math.max(domCard, 64));
                if (accum) out.copyFrom(ws.fRegs[dst]);
                else out.resetEmpty(domId, domCard);
                V11Vector sv = pv.vec();
                for (int i = 0; i < sv.length; i++) {
                    if (!sv.isValidAt(i)) continue;
                    int id = lookupKey(catalog, sv.strData[i]);
                    if (id >= 0 && id < domCard) out.add(id);
                }
                ws.fRegs[dst].copyFrom(out);
                ws.setFInitialized(dst, true);
                ws.zf = ws.fRegs[dst].isEmpty();
                ws.pc++;
            }
            case SUBOP_MAP_KEYS_TO_DENSE_VEC_TO_V, SUBOP_MAP_KEYS_TO_DENSE_V_TO_V -> {
                V11Vector sv = ws.vVectors[s1];
                if (sv == null || sv.elemTag != V11Vector.TAG_STR) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                int n = sv.length;
                long[] out = new long[n];
                long[] outValid = new long[Math.max(1, (n + 63) >>> 6)];
                int hits = 0;
                for (int i = 0; i < n; i++) {
                    if (!sv.isValidAt(i)) continue;
                    int id = lookupKey(catalog, sv.strData[i]);
                    if (id >= 0 && id < domCard) {
                        out[i] = id & 0xFFFFFFFFL;
                        outValid[i >>> 6] |= (1L << (i & 63));
                        hits++;
                    }
                }
                ws.vVectors[dst] = V11Vector.ofU64(out, outValid);
                ws.zf = (hits == 0);
                ws.pc++;
            }
            case SUBOP_MAP_KEYS_TO_DENSE_SCALAR_TO_R -> {
                if (!ws.isRValid(s1)) {
                    ws.writeRNull(dst);
                    ws.zf = true; ws.nf = true;
                    ws.pc++;
                    return;
                }
                String key = ws.resolveScalarStr(ws.rRegs[s1]);
                if (key == null) {
                    ws.writeRNull(dst);
                    ws.zf = true; ws.nf = true;
                    ws.pc++;
                    return;
                }
                int id = lookupKey(catalog, key);
                if (id < 0 || id >= domCard) {
                    ws.writeRNull(dst);
                    ws.zf = true; ws.nf = true;
                } else {
                    ws.writeR(dst, SCALAR_U64, id & 0xFFFFFFFFL);
                    ws.zf = (id == 0); ws.nf = false;
                }
                ws.pc++;
            }
            case SUBOP_MAP_KEYS_TO_DENSE_F_TO_F -> {
                if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                V11Frontier fSrc = ws.fRegs[s1];
                String[] srcCatalog = view.keyCatalogs.get(fSrc.domainId);
                if (srcCatalog == null) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                if (accum) {
                    if (!ws.isFInitialized(dst) || ws.fRegs[dst].multiLane) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                    if (ws.fRegs[dst].domainId != domId) { ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH; return; }
                }
                V11Frontier out = new V11Frontier(Math.max(domCard, 64));
                if (accum) out.copyFrom(ws.fRegs[dst]);
                else out.resetEmpty(domId, domCard);
                for (int u : fSrc.toSortedArray()) {
                    if (u >= 0 && u < srcCatalog.length) {
                        int tid = lookupKey(catalog, srcCatalog[u]);
                        if (tid >= 0 && tid < domCard) out.add(tid);
                    }
                }
                ws.fRegs[dst].copyFrom(out);
                ws.setFInitialized(dst, true);
                ws.zf = ws.fRegs[dst].isEmpty();
                ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    // --- 0x56 OP_MAP_DENSE_TO_KEYS ---

    private static void execMapDenseToKeys(V11Workspace ws, V11Interpreter.SnapshotBinding bind,
                                           int subop, int dst, int s1, int domId) {
        V11SnapshotView view = requireView(ws, bind);
        if (view == null) return;
        if (domId >= view.domainCards.length) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        String[] catalog = view.keyCatalogs.get(domId);
        if (catalog == null) {
            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
            return;
        }
        switch (subop) {
            case SUBOP_MAP_DENSE_TO_KEYS_VEC -> {
                V11Vector iv = ws.vVectors[s1];
                if (iv == null || (iv.elemTag != V11Vector.TAG_I64 && iv.elemTag != V11Vector.TAG_U64)) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                int n = iv.length;
                String[] out = new String[n];
                for (int i = 0; i < n; i++) {
                    if (!iv.isValidAt(i)) continue;
                    long u = iv.i64Data[i];
                    if (u < 0L || u >= catalog.length) {
                        ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return;
                    }
                    out[i] = catalog[(int) u];
                }
                ws.vVectors[dst] = V11Vector.ofStr(out);
                ws.pc++;
            }
            case SUBOP_MAP_DENSE_TO_KEYS_FRONTIER_SORTED -> {
                if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                V11Frontier f = ws.fRegs[s1];
                if (f.domainId != domId) { ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH; return; }
                int[] ids = f.toSortedArray();
                String[] out = new String[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    int u = ids[i];
                    if (u < 0 || u >= catalog.length) { ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return; }
                    out[i] = catalog[u];
                }
                ws.vVectors[dst] = V11Vector.ofStr(out);
                ws.pc++;
            }
            case SUBOP_MAP_DENSE_TO_KEYS_SCALAR -> {
                if (!ws.isRValid(s1)) {
                    ws.writeRNull(dst);
                    ws.pc++;
                    return;
                }
                long u = ws.rRegs[s1];
                if (u < 0L || u >= catalog.length) {
                    ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS; return;
                }
                String s = catalog[(int) u];
                if (s == null) {
                    ws.writeRNull(dst);
                } else {
                    long h = ws.internScalarStr(s);
                    ws.writeR(dst, SCALAR_STR_HANDLE, h);
                }
                ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }

    // --- 0x57 OP_COLLECT ---

    private static void execCollect(V11Workspace ws, int subop, int flags, int s1, int s3, int slot) {
        boolean masked = (flags & FLAG_MASKED_S3) != 0;
        switch (subop) {
            case SUBOP_COLLECT_FRONTIER_BITSET -> {
                if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                V11Frontier fa = ws.fRegs[s1];
                int n = fa.domainCard;
                int words = Math.max(1, (n + 63) >>> 6);
                long[] valid = V11Vector.allocValidAllTrue(n);
                long[] data = new long[words];
                int[] ids = fa.toSortedArray();
                for (int u : ids) data[u >>> 6] |= (1L << (u & 63));
                ws.outputs.put(slot, V11Vector.ofBool3vl(n, valid, data));
                ws.zf = (ids.length == 0);
                ws.pc++;
            }
            case SUBOP_COLLECT_FRONTIER_IDS -> {
                if (!ws.isFInitialized(s1) || ws.fRegs[s1].multiLane) {
                    ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return;
                }
                int[] ids = ws.fRegs[s1].toSortedArray();
                long[] vals = new long[ids.length];
                for (int i = 0; i < ids.length; i++) vals[i] = ids[i] & 0xFFFFFFFFL;
                ws.outputs.put(slot, V11Vector.ofU64(vals, V11Vector.allocValidAllTrue(ids.length)));
                ws.zf = (ids.length == 0);
                ws.pc++;
            }
            case SUBOP_COLLECT_VEC, SUBOP_COLLECT_TABLE_COL -> {
                V11Frontier mask = V11Pillar4MathOps.getMaskFrontier(ws, flags, s3);
                if (ws.status != IMPULSE_VM_OK) return;
                V11Vector v = ws.vVectors[s1];
                if (v == null) { ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH; return; }
                V11Vector out;
                if (masked) {
                    int[] ids = mask.toSortedArray();
                    for (int idx : ids) {
                        if (idx < 0 || idx >= v.length) {
                            ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
                            return;
                        }
                    }
                    out = gatherFromVec(v, ids, null, null);
                } else {
                    out = v.shallowCopy();
                }
                ws.outputs.put(slot, out);
                ws.zf = (out.length == 0);
                ws.pc++;
            }
            case SUBOP_COLLECT_SCALAR -> {
                boolean valid = ws.isRValid(s1);
                int ty = ws.readRType(s1);
                long val = ws.rRegs[s1];
                V11Vector out;
                boolean isZero;
                if (!valid) {
                    out = V11Vector.ofI64(new long[1], new long[1]);
                    isZero = true;
                } else if (ty == SCALAR_F64) {
                    double f = Double.longBitsToDouble(val);
                    if (Double.isNaN(f)) {
                        out = V11Vector.ofF64(new double[1], new long[1]);
                        isZero = true;
                    } else {
                        out = V11Vector.ofF64(new double[]{f}, new long[]{1L});
                        isZero = (f == 0.0);
                    }
                } else if (ty == SCALAR_STR_HANDLE) {
                    String s = ws.resolveScalarStr(val);
                    out = V11Vector.ofStr(new String[]{s});
                    isZero = (s == null || s.isEmpty());
                } else if (ty == SCALAR_U64) {
                    out = V11Vector.ofU64(new long[]{val}, new long[]{1L});
                    isZero = (val == 0L);
                } else {
                    out = V11Vector.ofI64(new long[]{val}, new long[]{1L});
                    isZero = (val == 0L);
                }
                ws.outputs.put(slot, out);
                ws.zf = isZero;
                ws.pc++;
            }
            default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
        }
    }
}
