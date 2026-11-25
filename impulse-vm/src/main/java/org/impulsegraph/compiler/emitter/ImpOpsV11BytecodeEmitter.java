package org.impulsegraph.compiler.emitter;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.vm.v11.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Retargeted ImpScheme {@link ScmProgram} bytecode emitter targeting ImpulseVM v0.11.0
 * ({@code .impb} v11 64-bit nibble ISA). Mirrors C++ {@code V11BytecodeEmitter} bit-for-bit.
 */
public final class ImpOpsV11BytecodeEmitter {

    private ImpOpsV11BytecodeEmitter() {}

    /** Emitted & statically verified v0.11 compiled program ready for interpreter or MethodHandle JIT execution. */
    public record V11CompiledProgram(
            V11ImpbContainer container,
            Map<String, Integer> relationIdMap,
            Map<String, Integer> paramNameToId,
            int startDomainId,
            int finalDomainId,
            int requiredDomains
    ) {
        public long[] codeWords() {
            return container.codeWords;
        }

        public byte[] impbBytes() {
            return container.rawBytes;
        }

        /** Executes via {@link V11Interpreter} on a bound {@link V11SnapshotView}. */
        public int executeInterpreted(V11SnapshotView view, V11Workspace ws, long gasLimit) {
            container.loadIntoWorkspace(ws);
            ws.gasRemaining = gasLimit;
            int[] domCards = new int[view.domainCards.length];
            for (int i = 0; i < domCards.length; i++) domCards[i] = (int) view.domainCards[i];
            V11Interpreter.SnapshotBinding binding = new V11Interpreter.SnapshotBinding(domCards, view);
            V11Interpreter.execute(container.codeWords, binding, ws);
            return ws.status;
        }
    }

    public static V11CompiledProgram emit(ImpScmNode ast, ImpulseGraphSnapshot snapshot) {
        return emit(ast, snapshot, 0);
    }

    public static V11CompiledProgram emit(ImpScmNode ast, ImpulseGraphSnapshot snapshot, int startDomainId) {
        Objects.requireNonNull(ast, "ast must not be null");
        if (!(ast instanceof ScmProgram prog)) {
            throw new IllegalArgumentException("ImpOpsV11BytecodeEmitter expects a ScmProgram root");
        }

        V11ProgramBuilder b = new V11ProgramBuilder();
        Map<String, Integer> relIdMap = new HashMap<>();
        Map<String, Integer> paramMap = new HashMap<>();

        int curF = 0;
        int curV = 0;
        int curR = 0;
        int curDom = startDomainId;
        boolean frontierInit = false;
        boolean activeIsVector = false;
        boolean activeIsScalar = false;
        int maxDomSeen = startDomainId;

        for (ImpScmNode step : prog.steps()) {
            int srcF = curF;
            int dstF = 1 - curF;

            if (step instanceof ScmWalk walk) {
                int relId = resolveRelId(snapshot, walk.relationName(), walk.relationId());
                relIdMap.put(walk.relationName(), relId);
                int srcDom = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
                        ? resolveRelTgtDom(snapshot, relId, curDom)
                        : resolveRelSrcDom(snapshot, relId, curDom);
                int tgtDom = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
                        ? resolveRelSrcDom(snapshot, relId, curDom)
                        : resolveRelTgtDom(snapshot, relId, curDom);
                if (!frontierInit) {
                    b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0,
                            curF, 0, 0, 0, 0, srcDom);
                    paramMap.put("$seed", 0);
                    frontierInit = true;
                    curDom = srcDom;
                    maxDomSeen = Math.max(maxDomSeen, srcDom);
                }
                maxDomSeen = Math.max(maxDomSeen, tgtDom);

                if (!walk.shaderSteps().isEmpty()) {
                    b.emitU16(V11IsaTables.OP_FSEED, V11IsaTables.SUBOP_FSEED_EMPTY, 0, dstF, 0, 0, 0, tgtDom, 0);
                    int msub = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
                            ? V11IsaTables.SUBOP_WALK_MORSEL_REV : V11IsaTables.SUBOP_WALK_MORSEL_FWD;
                    int hdrPc = b.emitU16(V11IsaTables.OP_WALK_MORSEL, msub, 0, 0, srcF, 0, 0, relId, 0);

                    int[] regs = {12, 1}; // [nextMReg, nextRReg]
                    int activeMaskF = 8;  // MF0
                    for (ImpScmNode sstep : walk.shaderSteps()) {
                        if (sstep instanceof ScmStreamFilter sf) {
                            int predM = emitMorselExpr(sf.predicate(), b, regs, snapshot, tgtDom, relId, false);
                            b.emit(V11IsaTables.OP_SET_CONVERT, V11IsaTables.SUBOP_SET_CONVERT_B2F, 0,
                                    9 /*MF1*/, predM, 0, 0, 0);
                            activeMaskF = 9;
                        }
                    }
                    b.emit(V11IsaTables.OP_MORSEL_COMMIT, V11IsaTables.SUBOP_MORSEL_COMMIT_YIELD_FRONTIER,
                            V11IsaTables.FLAG_ACCUM | V11IsaTables.FLAG_MASKED_S3,
                            dstF, 9 /*M1=tgt_id*/, 0, activeMaskF, 0);
                    int endPc = b.emit(V11IsaTables.OP_MORSEL_END, V11IsaTables.SUBOP_MORSEL_END_END, 0, 0, 0, 0, 0, 0);
                    b.patchImm16Hi(hdrPc, endPc - hdrPc);
                } else {
                    int wsub = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
                            ? V11IsaTables.SUBOP_WALK_REV : V11IsaTables.SUBOP_WALK_FWD;
                    b.emitU16(V11IsaTables.OP_WALK, wsub, 0, dstF, srcF, 0, 0, relId, 0);
                }
                curF = dstF;
                curDom = tgtDom;
                activeIsVector = false;
                activeIsScalar = false;
            } else if (step instanceof ScmWalk2Hop hop2) {
                int rel1 = resolveRelId(snapshot, hop2.relation1Name(), hop2.relation1Id());
                int rel2 = resolveRelId(snapshot, hop2.relation2Name(), hop2.relation2Id());
                relIdMap.put(hop2.relation1Name(), rel1);
                relIdMap.put(hop2.relation2Name(), rel2);
                int d0 = resolveRelSrcDom(snapshot, rel1, curDom);
                int d1 = resolveRelTgtDom(snapshot, rel1, d0);
                int d2 = resolveRelTgtDom(snapshot, rel2, d1);
                if (!frontierInit) {
                    b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0,
                            curF, 0, 0, 0, 0, d0);
                    paramMap.put("$seed", 0);
                    frontierInit = true;
                }
                maxDomSeen = Math.max(maxDomSeen, Math.max(d1, d2));
                b.emitU16(V11IsaTables.OP_WALK, V11IsaTables.SUBOP_WALK_FWD, 0, dstF, srcF, 0, 0, rel1, 0);
                b.emitU16(V11IsaTables.OP_WALK, V11IsaTables.SUBOP_WALK_FWD, 0, srcF, dstF, 0, 0, rel2, 0);
                curF = srcF;
                curDom = d2;
                activeIsVector = false;
                activeIsScalar = false;
            } else if (step instanceof ScmFixpoint fix) {
                List<ImpScmNode> bodySteps = fix.body() != null ? fix.body().steps() : List.of();
                if (bodySteps.size() == 1 && bodySteps.get(0) instanceof ScmWalk walk) {
                    int relId = resolveRelId(snapshot, walk.relationName(), walk.relationId());
                    relIdMap.put(walk.relationName(), relId);
                    int srcDom = resolveRelSrcDom(snapshot, relId, curDom);
                    if (!frontierInit) {
                        b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0,
                                curF, 0, 0, 0, 0, srcDom);
                        paramMap.put("$seed", 0);
                        frontierInit = true;
                        curDom = srcDom;
                    }
                    int fsub = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
                            ? V11IsaTables.SUBOP_WALK_REV_FIXPOINT_STAR : V11IsaTables.SUBOP_WALK_FWD_FIXPOINT_STAR;
                    b.emitU16(V11IsaTables.OP_WALK, fsub, 0, dstF, srcF, 0, 0, relId, 0);
                    curF = dstF;
                } else if (!bodySteps.isEmpty()) {
                    if (!frontierInit) {
                        b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0,
                                curF, 0, 0, 0, 0, curDom);
                        paramMap.put("$seed", 0);
                        frontierInit = true;
                    }
                    int prevF = 2;
                    int loopPc = b.pc();
                    b.emit(V11IsaTables.OP_MOV, V11IsaTables.SUBOP_MOV_F, 0, prevF, curF, 0, 0, 0);
                    for (ImpScmNode sub : bodySteps) {
                        if (sub instanceof ScmWalk w) {
                            int rid = resolveRelId(snapshot, w.relationName(), w.relationId());
                            int wsub = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
                                    ? V11IsaTables.SUBOP_WALK_REV : V11IsaTables.SUBOP_WALK_FWD;
                            int nxt = 1 - curF;
                            b.emitU16(V11IsaTables.OP_WALK, wsub, 0, nxt, curF, 0, 0, rid, 0);
                            curF = nxt;
                        }
                    }
                    b.emit(V11IsaTables.OP_SET_OP, V11IsaTables.SUBOP_SET_OP_UNION, 0, curF, curF, prevF, 0, 0);
                    b.emit(V11IsaTables.OP_STABLE_CHECK, V11IsaTables.SUBOP_STABLE_CHECK_FRONTIER_EQ, 0, 0, curF, prevF, 0, 0);
                    int relOff = loopPc - b.pc();
                    b.emit(V11IsaTables.OP_JCC, V11IsaTables.SUBOP_JCC_JNST, 0, 0, 0, 0, 0, relOff);
                }
                activeIsVector = false;
                activeIsScalar = false;
            } else if (step instanceof ScmReduce red) {
                if (!activeIsVector) {
                    if (!frontierInit) {
                        b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0,
                                curF, 0, 0, 0, 0, curDom);
                        paramMap.put("$seed", 0);
                        frontierInit = true;
                    }
                    b.emit(V11IsaTables.OP_SET_CARDINALITY, V11IsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0,
                            curR, curF, 0, 0, 0);
                } else {
                    int rsub = switch (red.op()) {
                        case SUM -> V11IsaTables.SUBOP_VEC_REDUCE_SUM;
                        case COUNT -> V11IsaTables.SUBOP_VEC_REDUCE_COUNT_VALID;
                        case MIN -> V11IsaTables.SUBOP_VEC_REDUCE_MIN;
                        case MAX -> V11IsaTables.SUBOP_VEC_REDUCE_MAX;
                        case ARGMIN -> V11IsaTables.SUBOP_VEC_REDUCE_ARGMIN;
                        case ARGMAX -> V11IsaTables.SUBOP_VEC_REDUCE_ARGMAX;
                        default -> V11IsaTables.SUBOP_VEC_REDUCE_SUM;
                    };
                    b.emit(V11IsaTables.OP_VEC_REDUCE, rsub, 0, curR, curV, 0, 0, 0);
                }
                activeIsScalar = true;
                activeIsVector = false;
            } else if (step instanceof ScmLaneInitSeeds laneSeeds) {
                frontierInit = true;
                if (!laneSeeds.seeds().isEmpty()) {
                    long[] arr = laneSeeds.seeds().stream().mapToLong(Long::longValue).toArray();
                    int cpId = b.addConstPoolVec(V11Vector.ofU64(arr, null));
                    b.emit(V11IsaTables.OP_LOAD_CONST_POOL, V11IsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0,
                            0, 0, 0, 0, cpId);
                } else {
                    b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_VEC, 0, 0, 0, 0, 0, 0, 0xFFFF);
                }
                int lsub = (laneSeeds.widthFlag() == 64)
                        ? V11IsaTables.SUBOP_LANE_INIT_SEEDS_FORCE_W64
                        : V11IsaTables.SUBOP_LANE_INIT_SEEDS_AUTO_WIDTH;
                b.emitU16(V11IsaTables.OP_LANE_INIT_SEEDS, lsub, 0, dstF, 0, 0, 0, curDom, 0);
                curF = dstF;
                activeIsVector = false;
                activeIsScalar = false;
            } else if (step instanceof ScmLaneWalk lw) {
                int relId = resolveRelId(snapshot, lw.relationName(), lw.relationId());
                relIdMap.put(lw.relationName(), relId);
                int lwsub = (lw.direction() == ScmWalk.Direction.REVERSE_CSC)
                        ? (lw.fixedPoint() ? V11IsaTables.SUBOP_LANE_WALK_REV_FIXPOINT_STAR : V11IsaTables.SUBOP_LANE_WALK_REV)
                        : (lw.fixedPoint() ? V11IsaTables.SUBOP_LANE_WALK_FWD_FIXPOINT_STAR : V11IsaTables.SUBOP_LANE_WALK_FWD);
                b.emitU16(V11IsaTables.OP_LANE_WALK, lwsub, 0, dstF, srcF, 0, 0, relId, 0);
                curF = dstF;
            } else if (step instanceof ScmLaneFilterQuorum q) {
                b.emitU16(V11IsaTables.OP_LANE_FILTER_QUORUM, V11IsaTables.SUBOP_LANE_FILTER_QUORUM_AT_LEAST, 0,
                        dstF, srcF, 0, 0, q.minQuorum(), 0);
                curF = dstF;
            } else if (step instanceof ScmLaneReduceAnd) {
                b.emit(V11IsaTables.OP_LANE_REDUCE_AND, V11IsaTables.SUBOP_LANE_REDUCE_AND_REDUCE_AND, 0,
                        dstF, srcF, 0, 0, 0);
                curF = dstF;
            } else if (step instanceof ScmLaneReduceAttr ra) {
                int rsub = ra.reduceOp() & 0x03;
                b.emitU16(V11IsaTables.OP_LOAD_COLUMN, V11IsaTables.SUBOP_LOAD_COLUMN_NODE_ATTR, 0,
                        1, 0, 0, 0, curDom, ra.attrId());
                b.emit(V11IsaTables.OP_LANE_REDUCE_ATTR, rsub, 0, curV, srcF, 1, 0, 0);
                activeIsVector = true;
                activeIsScalar = false;
            } else if (step instanceof ScmCollect collect) {
                if (activeIsScalar) {
                    b.emitU16(V11IsaTables.OP_COLLECT, V11IsaTables.SUBOP_COLLECT_SCALAR, 0, 0, curR, 0, 0, 0, 0);
                } else if (activeIsVector) {
                    b.emitU16(V11IsaTables.OP_COLLECT, V11IsaTables.SUBOP_COLLECT_VEC, 0, 0, curV, 0, 0, 0, 0);
                } else {
                    if (!frontierInit) {
                        b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0,
                                curF, 0, 0, 0, 0, curDom);
                        paramMap.put("$seed", 0);
                        frontierInit = true;
                    }
                    if (collect.format() == ScmCollect.Format.VECTOR || collect.format() == ScmCollect.Format.LIST) {
                        b.emitU16(V11IsaTables.OP_COLLECT, V11IsaTables.SUBOP_COLLECT_FRONTIER_IDS, 0, 0, curF, 0, 0, 0, 0);
                    } else if (collect.format() == ScmCollect.Format.KEYS) {
                        b.emitU16(V11IsaTables.OP_MAP_DENSE_TO_KEYS, V11IsaTables.SUBOP_MAP_DENSE_TO_KEYS_FRONTIER_SORTED,
                                0, 0, curF, 0, 0, curDom, 0);
                        b.emitU16(V11IsaTables.OP_COLLECT, V11IsaTables.SUBOP_COLLECT_VEC, 0, 0, 0, 0, 0, 0, 0);
                    } else {
                        b.emitU16(V11IsaTables.OP_COLLECT, V11IsaTables.SUBOP_COLLECT_FRONTIER_BITSET, 0, 0, curF, 0, 0, 0, 0);
                    }
                }
            } else if (step instanceof ScmList list && !list.elements().isEmpty()
                    && list.elements().get(0) instanceof ScmSymbol headSym) {
                String opName = headSym.name();
                if ("domain-all".equals(opName)) {
                    b.emitU16(V11IsaTables.OP_INIT_DOMAIN_SPAN, V11IsaTables.SUBOP_INIT_DOMAIN_SPAN_ALL_SPAN,
                            0, dstF, 0, 0, 0, curDom, 0);
                    curF = dstF;
                    frontierInit = true;
                } else if ("repeat".equals(opName) && list.elements().size() >= 3) {
                    int repeatCount = (list.elements().get(1) instanceof ScmLiteral.ScmInt si)
                            ? (int) si.value() : 1;
                    if (!frontierInit) {
                        b.emitU16(V11IsaTables.OP_LOAD_PARAM, V11IsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0,
                                curF, 0, 0, 0, 0, curDom);
                        paramMap.put("$seed", 0);
                        frontierInit = true;
                    }
                    int cntR = 2;
                    b.emitLoadConstI64(cntR, repeatCount);
                    int loopPc = b.pc();
                    if (list.elements().get(2) instanceof ScmProgram subProg) {
                        for (ImpScmNode sub : subProg.steps()) {
                            if (sub instanceof ScmWalk w) {
                                int rid = resolveRelId(snapshot, w.relationName(), w.relationId());
                                int wsub = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
                                        ? V11IsaTables.SUBOP_WALK_REV : V11IsaTables.SUBOP_WALK_FWD;
                                int nxt = 1 - curF;
                                b.emitU16(V11IsaTables.OP_WALK, wsub, 0, nxt, curF, 0, 0, rid, 0);
                                curF = nxt;
                            }
                        }
                    }
                    if (curF != srcF) {
                        b.emit(V11IsaTables.OP_MOV, V11IsaTables.SUBOP_MOV_F, 0, srcF, curF, 0, 0, 0);
                        curF = srcF;
                    }
                    int relOff = loopPc - b.pc();
                    b.emit(V11IsaTables.OP_LOOP_DECR, V11IsaTables.SUBOP_LOOP_DECR_DECR, 0, cntR, 0, 0, 0, relOff);
                }
            }
        }

        b.emit(V11IsaTables.OP_HALT, V11IsaTables.SUBOP_HALT_OK, 0, 0, 0, 0, 0, 0);

        int reqDoms = (snapshot != null && snapshot.getDomainNames() != null && !snapshot.getDomainNames().isEmpty())
                ? snapshot.getDomainNames().size() : (maxDomSeen + 1);
        V11ImpbContainer container = b.buildImpb(0, 0, reqDoms);

        // Static verification post-condition: emitted bytecode MUST pass V11Validator
        V11Validator.ValidationResult vr = V11Validator.validate(container.codeWords);
        if (vr.status() != V11IsaTables.IMPULSE_VM_OK) {
            throw new IllegalStateException("ImpOpsV11BytecodeEmitter produced invalid v0.11 bytecode at pc="
                    + vr.badPc() + " status=" + vr.status());
        }

        return new V11CompiledProgram(container, relIdMap, paramMap, startDomainId, curDom, reqDoms);
    }

    private static int emitMorselExpr(ImpScmNode expr, V11ProgramBuilder b, int[] regs,
                                      ImpulseGraphSnapshot snap, int tgtDom, int relId, boolean isNodeMorsel) {
        if (expr instanceof ScmList list && !list.elements().isEmpty()
                && list.elements().get(0) instanceof ScmSymbol sym) {
            String op = sym.name();
            int csub = switch (op) {
                case "stream-cmp-eq", "vec-cmp-eq", "=" -> V11IsaTables.SUBOP_VEC_CMP_EQ;
                case "stream-cmp-ne", "vec-cmp-ne", "!=" -> V11IsaTables.SUBOP_VEC_CMP_NE;
                case "stream-cmp-lt", "vec-cmp-lt", "<" -> V11IsaTables.SUBOP_VEC_CMP_LT;
                case "stream-cmp-le", "vec-cmp-le", "<=" -> V11IsaTables.SUBOP_VEC_CMP_LE;
                case "stream-cmp-gt", "vec-cmp-gt", ">" -> V11IsaTables.SUBOP_VEC_CMP_GT;
                case "stream-cmp-ge", "vec-cmp-ge", ">=" -> V11IsaTables.SUBOP_VEC_CMP_GE;
                default -> -1;
            };
            if (csub >= 0 && list.elements().size() >= 3) {
                int lhs = emitMorselExpr(list.elements().get(1), b, regs, snap, tgtDom, relId, isNodeMorsel);
                int dst = regs[0]++;
                if (list.elements().get(2) instanceof ScmLiteral lit) {
                    int r = regs[1]++;
                    if (lit instanceof ScmLiteral.ScmFloat sf) b.emitLoadConstF64(r, sf.value());
                    else if (lit instanceof ScmLiteral.ScmInt si) b.emitLoadConstI64(r, si.value());
                    else b.emitLoadConstI64(r, 0L);
                    b.emit(V11IsaTables.OP_VEC_CMP, csub, V11IsaTables.FLAG_SCALAR_RHS, dst, lhs, r, 0, 0);
                } else {
                    int rhs = emitMorselExpr(list.elements().get(2), b, regs, snap, tgtDom, relId, isNodeMorsel);
                    b.emit(V11IsaTables.OP_VEC_CMP, csub, 0, dst, lhs, rhs, 0, 0);
                }
                return dst;
            }
            if (("stream-logic-and".equals(op) || "mask-and".equals(op) || "and".equals(op)) && list.elements().size() >= 3) {
                int lhs = emitMorselExpr(list.elements().get(1), b, regs, snap, tgtDom, relId, isNodeMorsel);
                int rhs = emitMorselExpr(list.elements().get(2), b, regs, snap, tgtDom, relId, isNodeMorsel);
                int dst = regs[0]++;
                b.emit(V11IsaTables.OP_VEC_3VL_OP, V11IsaTables.SUBOP_VEC_3VL_OP_AND, 0, dst, lhs, rhs, 0, 0);
                return dst;
            }
            if (("stream-logic-or".equals(op) || "mask-or".equals(op) || "or".equals(op)) && list.elements().size() >= 3) {
                int lhs = emitMorselExpr(list.elements().get(1), b, regs, snap, tgtDom, relId, isNodeMorsel);
                int rhs = emitMorselExpr(list.elements().get(2), b, regs, snap, tgtDom, relId, isNodeMorsel);
                int dst = regs[0]++;
                b.emit(V11IsaTables.OP_VEC_3VL_OP, V11IsaTables.SUBOP_VEC_3VL_OP_OR, 0, dst, lhs, rhs, 0, 0);
                return dst;
            }
            if ("stream-load-attr".equals(op) || "get-attr".equals(op) || "node-attr".equals(op)) {
                int aid = 0;
                if (snap != null && list.elements().size() >= 2) {
                    String aname = list.elements().get(1) instanceof ScmLiteral.ScmString ls ? ls.value()
                            : (list.elements().get(1) instanceof ScmSymbol s ? s.name() : "");
                    String dname = snap.getDomainName(tgtDom);
                    if (dname != null) {
                        List<String> names = snap.getDomainAttributeNames(dname);
                        int idx = names.indexOf(aname);
                        if (idx >= 0) aid = idx;
                    }
                }
                int dst = regs[0]++;
                int idxReg = isNodeMorsel ? 8 : 9;
                b.emitU16(V11IsaTables.OP_GATHER_NODE_ATTR, V11IsaTables.SUBOP_GATHER_NODE_ATTR_VEC, 0,
                        dst, idxReg, 0, 0, tgtDom, aid);
                return dst;
            }
        }
        return isNodeMorsel ? 8 : 9;
    }

    private static int resolveRelId(ImpulseGraphSnapshot snap, String name, int fallback) {
        if (fallback >= 0) return fallback;
        if (snap == null || name == null || name.isEmpty()) return 0;
        RelationSnapshot rs = snap.getRelationSnapshot(name);
        return (rs != null && rs.getRelationId() >= 0) ? rs.getRelationId() : 0;
    }

    private static int resolveRelSrcDom(ImpulseGraphSnapshot snap, int relId, int fallback) {
        if (snap == null) return fallback;
        RelationSnapshot rs = snap.getRelationSnapshot(relId);
        return rs != null ? rs.getSrcDomainId() : fallback;
    }

    private static int resolveRelTgtDom(ImpulseGraphSnapshot snap, int relId, int fallback) {
        if (snap == null) return fallback;
        RelationSnapshot rs = snap.getRelationSnapshot(relId);
        return rs != null ? rs.getTgtDomainId() : fallback;
    }
}
