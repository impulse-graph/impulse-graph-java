package org.impulsegraph.compiler.emitter;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.vm.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Retargeted ImpScheme {@link ScmProgram} bytecode emitter targeting ImpulseVM
 * v0.11.0 ({@code .impb} v11 64-bit nibble ISA). Mirrors C++
 * {@code V11BytecodeEmitter} bit-for-bit.
 */
public final class ImpOpsBytecodeEmitter {

	private ImpOpsBytecodeEmitter() {
	}

	/**
	 * Emitted & statically verified v0.11 compiled program ready for interpreter or
	 * MethodHandle JIT execution.
	 */
	public record CompiledProgram(ImpulseImpbContainer container, Map<String, Integer> relationIdMap,
			Map<String, Integer> paramNameToId, int startDomainId, int finalDomainId, int requiredDomains,
			int finalFReg, boolean finalIsMultiLane) {
		public CompiledProgram(ImpulseImpbContainer container, Map<String, Integer> relationIdMap,
				Map<String, Integer> paramNameToId, int startDomainId, int finalDomainId, int requiredDomains) {
			this(container, relationIdMap, paramNameToId, startDomainId, finalDomainId, requiredDomains, 0, false);
		}

		public long[] codeWords() {
			return container.codeWords;
		}

		public byte[] impbBytes() {
			return container.rawBytes;
		}

		/**
		 * Executes via {@link ImpulseInterpreter} on a bound
		 * {@link ImpulseSnapshotView}.
		 */
		public int executeInterpreted(ImpulseSnapshotView view, ImpulseWorkspace ws, long gasLimit) {
			container.loadIntoWorkspace(ws);
			int[] domCards = new int[view.domainCards.length];
			for (int i = 0; i < domCards.length; i++)
				domCards[i] = (int) view.domainCards[i];
			ImpulseInterpreter.SnapshotBinding binding = new ImpulseInterpreter.SnapshotBinding(domCards, view);
			ws.reset();
			ws.gasRemaining = gasLimit;
			ImpulseInterpreter.executePreValidated(container.codeWords, binding, ws, gasLimit);
			return ws.status;
		}
	}

	public static CompiledProgram emit(ImpScmNode ast, ImpulseGraphSnapshot snapshot) {
		return emit(ast, snapshot, 0);
	}

	public static CompiledProgram emit(ImpScmNode ast, ImpulseGraphSnapshot snapshot, int startDomainId) {
		Objects.requireNonNull(ast, "ast must not be null");
		if (!(ast instanceof ScmProgram prog)) {
			throw new IllegalArgumentException("ImpOpsBytecodeEmitter expects a ScmProgram root");
		}

		V11ProgramBuilder b = new V11ProgramBuilder();
		Map<String, Integer> relIdMap = new HashMap<>();
		Map<String, Integer> paramMap = new HashMap<>();

		int curF = 0;
		int curV = 0;
		int curR = 0;
		int curDom = startDomainId;
		int actualStartDom = startDomainId;
		boolean frontierInit = false;
		boolean activeIsVector = false;
		boolean activeIsScalar = false;
		boolean activeIsMultiLane = false;
		boolean hasCollect = false;
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
					b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF, 0, 0,
							0, 0, srcDom);
					paramMap.put("$seed", 0);
					frontierInit = true;
					curDom = srcDom;
					actualStartDom = srcDom;
					maxDomSeen = Math.max(maxDomSeen, srcDom);
				}
				maxDomSeen = Math.max(maxDomSeen, tgtDom);

				if (!walk.shaderSteps().isEmpty()) {
					b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, dstF, 0, 0, 0, tgtDom,
							0);
					int msub = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
							? ImpulseIsaTables.SUBOP_WALK_MORSEL_REV
							: ImpulseIsaTables.SUBOP_WALK_MORSEL_FWD;
					int hdrPc = b.emitU16(ImpulseIsaTables.OP_WALK_MORSEL, msub, 0, 0, srcF, 0, 0, relId, 0);

					int[] regs = {12, 1}; // [nextMReg, nextRReg]
					int activeMaskF = 8; // MF0
					for (ImpScmNode sstep : walk.shaderSteps()) {
						if (sstep instanceof ScmStreamFilter sf) {
							int predM = emitMorselExpr(sf.predicate(), b, regs, snapshot, tgtDom, relId, false);
							if (activeMaskF == 8) {
								b.emit(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0,
										9 /* MF1 */, predM, 0, 0, 0);
								activeMaskF = 9;
							} else {
								b.emit(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0,
										10 /* MF2 */, predM, 0, 0, 0);
								b.emit(ImpulseIsaTables.OP_SET_OP, ImpulseIsaTables.SUBOP_SET_OP_INTERSECT, 0,
										9 /* MF1 */, 9 /* MF1 */, 10 /* MF2 */, 0, 0);
							}
							regs[0] = 12;
							regs[1] = 1;
						}
					}
					b.emit(ImpulseIsaTables.OP_MORSEL_COMMIT, ImpulseIsaTables.SUBOP_MORSEL_COMMIT_YIELD_FRONTIER,
							ImpulseIsaTables.FLAG_ACCUM | ImpulseIsaTables.FLAG_MASKED_S3, dstF, 9 /* M1=tgt_id */, 0,
							activeMaskF, 0);
					int endPc = b.emitU16(ImpulseIsaTables.OP_MORSEL_END, ImpulseIsaTables.SUBOP_MORSEL_END_END, 0, 0,
							0, 0, 0, 0, 0);
					int span = endPc - hdrPc;
					b.patchImm16Hi(hdrPc, span);
					b.patchImm16Hi(endPc, span);
				} else {
					int wsub = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
							? ImpulseIsaTables.SUBOP_WALK_REV
							: ImpulseIsaTables.SUBOP_WALK_FWD;
					b.emitU16(ImpulseIsaTables.OP_WALK, wsub, 0, dstF, srcF, 0, 0, relId, 0);
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
					b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF, 0, 0,
							0, 0, d0);
					paramMap.put("$seed", 0);
					frontierInit = true;
					curDom = d0;
					actualStartDom = d0;
					maxDomSeen = Math.max(maxDomSeen, d0);
				}
				maxDomSeen = Math.max(maxDomSeen, Math.max(d1, d2));
				b.emitU16(ImpulseIsaTables.OP_WALK, ImpulseIsaTables.SUBOP_WALK_FWD, 0, dstF, srcF, 0, 0, rel1, 0);
				b.emitU16(ImpulseIsaTables.OP_WALK, ImpulseIsaTables.SUBOP_WALK_FWD, 0, srcF, dstF, 0, 0, rel2, 0);
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
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, srcDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
						curDom = srcDom;
						actualStartDom = srcDom;
					}
					int fsub = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
							? ImpulseIsaTables.SUBOP_WALK_REV_FIXPOINT_STAR
							: ImpulseIsaTables.SUBOP_WALK_FWD_FIXPOINT_STAR;
					b.emitU16(ImpulseIsaTables.OP_WALK, fsub, 0, dstF, srcF, 0, 0, relId, 0);
					curF = dstF;
				} else if (!bodySteps.isEmpty()) {
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, curDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
					}
					int prevF = 2;
					int loopPc = b.pc();
					b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_F, 0, prevF, curF, 0, 0, 0);
					for (ImpScmNode sub : bodySteps) {
						if (sub instanceof ScmWalk w) {
							int rid = resolveRelId(snapshot, w.relationName(), w.relationId());
							int wsub = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
									? ImpulseIsaTables.SUBOP_WALK_REV
									: ImpulseIsaTables.SUBOP_WALK_FWD;
							int nxt = 1 - curF;
							b.emitU16(ImpulseIsaTables.OP_WALK, wsub, 0, nxt, curF, 0, 0, rid, 0);
							curF = nxt;
						}
					}
					b.emit(ImpulseIsaTables.OP_SET_OP, ImpulseIsaTables.SUBOP_SET_OP_UNION, 0, curF, curF, prevF, 0, 0);
					if (curF != srcF) {
						b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_F, 0, srcF, curF, 0, 0, 0);
						curF = srcF;
					}
					b.emit(ImpulseIsaTables.OP_STABLE_CHECK, ImpulseIsaTables.SUBOP_STABLE_CHECK_FRONTIER_EQ, 0, 0,
							curF, prevF, 0, 0);
					int relOff = loopPc - b.pc();
					b.emit(ImpulseIsaTables.OP_JCC, ImpulseIsaTables.SUBOP_JCC_JNST, 0, 0, 0, 0, 0, relOff);
				}
				activeIsVector = false;
				activeIsScalar = false;
			} else if (step instanceof ScmSetOp sop) {
				int ssub = switch (sop.kind()) {
					case INTERSECT -> ImpulseIsaTables.SUBOP_SET_OP_INTERSECT;
					case DIFF -> ImpulseIsaTables.SUBOP_SET_OP_DIFF;
					default -> ImpulseIsaTables.SUBOP_SET_OP_UNION;
				};
				if (!frontierInit) {
					int initDom = curDom;
					if (sop.lhs() instanceof ScmWalk lw) {
						int rid = resolveRelId(snapshot, lw.relationName(), lw.relationId());
						initDom = (lw.direction() == ScmWalk.Direction.REVERSE_CSC)
								? resolveRelTgtDom(snapshot, rid, curDom)
								: resolveRelSrcDom(snapshot, rid, curDom);
					}
					b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF, 0, 0,
							0, 0, initDom);
					paramMap.put("$seed", 0);
					frontierInit = true;
					curDom = initDom;
					maxDomSeen = Math.max(maxDomSeen, initDom);
				}
				if (sop.lhs() != null) {
					int outDom = emitBranch(sop.lhs(), srcF, 2, b, snapshot, relIdMap, curDom);
					emitBranch(sop.rhs(), srcF, 3, b, snapshot, relIdMap, curDom);
					b.emit(ImpulseIsaTables.OP_SET_OP, ssub, 0, dstF, 2, 3, 0, 0);
					curDom = outDom;
					maxDomSeen = Math.max(maxDomSeen, outDom);
				} else {
					emitBranch(sop.rhs(), srcF, 2, b, snapshot, relIdMap, curDom);
					b.emit(ImpulseIsaTables.OP_SET_OP, ssub, 0, dstF, srcF, 2, 0, 0);
				}
				curF = dstF;
				activeIsVector = false;
				activeIsScalar = false;
				activeIsMultiLane = false;
			} else if (step instanceof ScmReduce red) {
				if (!activeIsVector) {
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, curDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
					}
					b.emit(ImpulseIsaTables.OP_SET_CARDINALITY, ImpulseIsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0,
							curR, curF, 0, 0, 0);
				} else {
					int rsub = switch (red.op()) {
						case SUM -> ImpulseIsaTables.SUBOP_VEC_REDUCE_SUM;
						case COUNT -> ImpulseIsaTables.SUBOP_VEC_REDUCE_COUNT_VALID;
						case MIN -> ImpulseIsaTables.SUBOP_VEC_REDUCE_MIN;
						case MAX -> ImpulseIsaTables.SUBOP_VEC_REDUCE_MAX;
						case ARGMIN -> ImpulseIsaTables.SUBOP_VEC_REDUCE_ARGMIN;
						case ARGMAX -> ImpulseIsaTables.SUBOP_VEC_REDUCE_ARGMAX;
						default -> ImpulseIsaTables.SUBOP_VEC_REDUCE_SUM;
					};
					b.emit(ImpulseIsaTables.OP_VEC_REDUCE, rsub, 0, curR, curV, 0, 0, 0);
				}
				activeIsScalar = true;
				activeIsVector = false;
			} else if (step instanceof ScmLaneInitSeeds laneSeeds) {
				frontierInit = true;
				if (!laneSeeds.seeds().isEmpty()) {
					long[] arr = laneSeeds.seeds().stream().mapToLong(Long::longValue).toArray();
					int cpId = b.addConstPoolVec(ImpulseVector.ofU64(arr, null));
					b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0, 0, 0, 0,
							0, cpId);
				} else {
					b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_VEC, 0, 0, 0, 0, 0, 0,
							0xFFFF);
				}
				int lsub = (laneSeeds.widthFlag() == 64)
						? ImpulseIsaTables.SUBOP_LANE_INIT_SEEDS_FORCE_W64
						: ImpulseIsaTables.SUBOP_LANE_INIT_SEEDS_AUTO_WIDTH;
				b.emitU16(ImpulseIsaTables.OP_LANE_INIT_SEEDS, lsub, 0, dstF, 0, 0, 0, curDom, 0);
				curF = dstF;
				activeIsVector = false;
				activeIsScalar = false;
				activeIsMultiLane = true;
			} else if (step instanceof ScmLaneInitGroups laneGroups) {
				frontierInit = true;
				List<Long> pairs = laneGroups.nodeGroupPairs();
				int count = pairs.size() / 2;
				long[] nodes = new long[count];
				long[] lanes = new long[count];
				for (int i = 0; i < count; i++) {
					nodes[i] = pairs.get(i * 2);
					lanes[i] = pairs.get(i * 2 + 1);
				}
				int cpNodes = b.addConstPoolVec(ImpulseVector.ofU64(nodes, null));
				int cpLanes = b.addConstPoolVec(ImpulseVector.ofU64(lanes, null));
				b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0, 0, 0, 0, 0,
						cpNodes);
				b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0, 1, 0, 0, 0,
						cpLanes);
				b.emitU16(ImpulseIsaTables.OP_LANE_INIT_GROUPS, ImpulseIsaTables.SUBOP_LANE_INIT_GROUPS_GROUPS, 0, dstF,
						0, 1, 0, curDom, 0);
				curF = dstF;
				activeIsVector = false;
				activeIsScalar = false;
				activeIsMultiLane = true;
			} else if (step instanceof ScmLanePruneMask pm) {
				int sub = (pm.mode() == 1)
						? ImpulseIsaTables.SUBOP_LANE_BITWISE_KEEP_MASK
						: ImpulseIsaTables.SUBOP_LANE_BITWISE_PRUNE_MASK;
				int rMask = 3;
				b.emitLoadConstU64(rMask, Integer.toUnsignedLong(pm.arg0()));
				b.emit(ImpulseIsaTables.OP_LANE_BITWISE, sub, ImpulseIsaTables.FLAG_SCALAR_RHS, dstF, srcF, rMask, 0,
						0);
				curF = dstF;
				activeIsMultiLane = true;
			} else if (step instanceof ScmLaneWalk lw) {
				int relId = resolveRelId(snapshot, lw.relationName(), lw.relationId());
				relIdMap.put(lw.relationName(), relId);
				int lwsub = (lw.direction() == ScmWalk.Direction.REVERSE_CSC)
						? (lw.fixedPoint()
								? ImpulseIsaTables.SUBOP_LANE_WALK_REV_FIXPOINT_STAR
								: ImpulseIsaTables.SUBOP_LANE_WALK_REV)
						: (lw.fixedPoint()
								? ImpulseIsaTables.SUBOP_LANE_WALK_FWD_FIXPOINT_STAR
								: ImpulseIsaTables.SUBOP_LANE_WALK_FWD);
				b.emitU16(ImpulseIsaTables.OP_LANE_WALK, lwsub, 0, dstF, srcF, 0, 0, relId, 0);
				curF = dstF;
				activeIsMultiLane = true;
			} else if (step instanceof ScmLaneFilterQuorum q) {
				b.emitU16(ImpulseIsaTables.OP_LANE_FILTER_QUORUM, ImpulseIsaTables.SUBOP_LANE_FILTER_QUORUM_AT_LEAST, 0,
						dstF, srcF, 0, 0, q.minQuorum(), 0);
				curF = dstF;
				activeIsMultiLane = false;
			} else if (step instanceof ScmLaneReduceAnd) {
				b.emit(ImpulseIsaTables.OP_LANE_REDUCE_AND, ImpulseIsaTables.SUBOP_LANE_REDUCE_AND_REDUCE_AND, 0, dstF,
						srcF, 0, 0, 0);
				curF = dstF;
				activeIsMultiLane = false;
			} else if (step instanceof ScmLaneReduceAttr ra) {
				int rsub = ra.reduceOp() & 0x03;
				b.emitU16(ImpulseIsaTables.OP_LOAD_COLUMN, ImpulseIsaTables.SUBOP_LOAD_COLUMN_NODE_ATTR, 0, 1, 0, 0, 0,
						curDom, ra.attrId());
				b.emit(ImpulseIsaTables.OP_LANE_REDUCE_ATTR, rsub, 0, curV, srcF, 1, 0, 0);
				activeIsVector = true;
				activeIsScalar = false;
				activeIsMultiLane = false;
			} else if (step instanceof ScmVectorAnnSearch ann) {
				frontierInit = true;
				float[] qf = ann.queryVector();
				double[] q = new double[qf.length];
				for (int i = 0; i < q.length; i++) {
					q[i] = qf[i];
				}
				int cpQ = b.addConstPoolVec(ImpulseVector.ofF64(q, ImpulseVector.allocValidAllTrue(q.length)));
				b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0, 1, 0, 0, 0,
						cpQ);
				b.emitLoadConstI64(2, ann.topK());
				b.emitU16(ImpulseIsaTables.OP_VECTOR_ANN_SEARCH, ImpulseIsaTables.SUBOP_VECTOR_ANN_SEARCH_IDS, 0, curV,
						1, 2, 0, curDom, ann.indexOrAttrId());
				if (ann.fusedLaneFrontier()) {
					b.emitU16(ImpulseIsaTables.OP_LANE_INIT_SEEDS, ImpulseIsaTables.SUBOP_LANE_INIT_SEEDS_AUTO_WIDTH, 0,
							dstF, curV, 0, 0, curDom, 0);
					curF = dstF;
					activeIsMultiLane = true;
					activeIsVector = false;
				} else {
					activeIsVector = true;
					activeIsMultiLane = false;
				}
				activeIsScalar = false;
			} else if (step instanceof ScmVectorRerankDot rr) {
				float[] qf = rr.queryVector();
				double[] q = new double[qf.length];
				for (int i = 0; i < q.length; i++) {
					q[i] = qf[i];
				}
				int cpQ = b.addConstPoolVec(ImpulseVector.ofF64(q, ImpulseVector.allocValidAllTrue(q.length)));
				b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0, 2, 0, 0, 0,
						cpQ);
				int metricSub = switch (rr.metric() & 0x03) {
					case 1 -> ImpulseIsaTables.SUBOP_VECTOR_RERANK_NEG_L2_SQ;
					case 2 -> ImpulseIsaTables.SUBOP_VECTOR_RERANK_COSINE;
					default -> ImpulseIsaTables.SUBOP_VECTOR_RERANK_DOT;
				};
				if (!activeIsVector) {
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, curDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
					}
					b.emit(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_F2V_IDS, 0, curV, curF,
							0, 0, 0);
				}
				b.emitU16(ImpulseIsaTables.OP_VECTOR_RERANK, metricSub, 0, curV, curV, 2, 0, curDom, rr.attrId());
				activeIsVector = true;
				activeIsScalar = false;
				activeIsMultiLane = false;
			} else if (step instanceof ScmCollect collect) {
				hasCollect = true;
				if (activeIsMultiLane) {
					b.emitU16(ImpulseIsaTables.OP_LANE_FILTER_QUORUM,
							ImpulseIsaTables.SUBOP_LANE_FILTER_QUORUM_AT_LEAST, 0, dstF, curF, 0, 0, 1, 0);
					curF = dstF;
					activeIsMultiLane = false;
				}
				if (activeIsScalar) {
					b.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_SCALAR, 0, 0, curR, 0, 0, 0,
							0);
				} else if (activeIsVector) {
					b.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_VEC, 0, 0, curV, 0, 0, 0, 0);
				} else {
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, curDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
					}
					if (collect.format() == ScmCollect.Format.COUNT || collect.format() == ScmCollect.Format.SCALAR) {
						b.emit(ImpulseIsaTables.OP_SET_CARDINALITY, ImpulseIsaTables.SUBOP_SET_CARDINALITY_CARD_1BIT, 0,
								curR, curF, 0, 0, 0);
						b.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_SCALAR, 0, 0, curR, 0, 0,
								0, 0);
					} else if (collect.format() == ScmCollect.Format.VECTOR
							|| collect.format() == ScmCollect.Format.LIST) {
						b.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_FRONTIER_IDS, 0, 0, curF,
								0, 0, 0, 0);
					} else if (collect.format() == ScmCollect.Format.KEYS) {
						b.emitU16(ImpulseIsaTables.OP_MAP_DENSE_TO_KEYS,
								ImpulseIsaTables.SUBOP_MAP_DENSE_TO_KEYS_FRONTIER_SORTED, 0, 0, curF, 0, 0, curDom, 0);
						b.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_VEC, 0, 0, 0, 0, 0, 0, 0);
					} else {
						b.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_FRONTIER_BITSET, 0, 0,
								curF, 0, 0, 0, 0);
					}
				}
			} else if (step instanceof ScmList list && !list.elements().isEmpty()
					&& list.elements().get(0) instanceof ScmSymbol headSym) {
				String opName = headSym.name();
				if ("domain-all".equals(opName)) {
					b.emitU16(ImpulseIsaTables.OP_INIT_DOMAIN_SPAN, ImpulseIsaTables.SUBOP_INIT_DOMAIN_SPAN_ALL_SPAN, 0,
							dstF, 0, 0, 0, curDom, 0);
					curF = dstF;
					frontierInit = true;
				} else if (("repeat".equals(opName) || "repeat-exact".equals(opName)) && list.elements().size() >= 3) {
					int repeatCount = (list.elements().get(1) instanceof ScmLiteral.ScmInt si) ? (int) si.value() : 1;
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, curDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
					}
					if (repeatCount > 0) {
						int cntR = 2;
						b.emitLoadConstI64(cntR, repeatCount);
						int loopPc = b.pc();
						if (list.elements().get(2) instanceof ScmProgram subProg) {
							for (ImpScmNode sub : subProg.steps()) {
								if (sub instanceof ScmWalk w) {
									int rid = resolveRelId(snapshot, w.relationName(), w.relationId());
									int wsub = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
											? ImpulseIsaTables.SUBOP_WALK_REV
											: ImpulseIsaTables.SUBOP_WALK_FWD;
									int nxt = 1 - curF;
									b.emitU16(ImpulseIsaTables.OP_WALK, wsub, 0, nxt, curF, 0, 0, rid, 0);
									curF = nxt;
								}
							}
						}
						if (curF != srcF) {
							b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_F, 0, srcF, curF, 0, 0, 0);
							curF = srcF;
						}
						int relOff = loopPc - b.pc();
						b.emit(ImpulseIsaTables.OP_LOOP_DECR, ImpulseIsaTables.SUBOP_LOOP_DECR_DECR, 0, cntR, 0, 0, 0,
								relOff);
					}
				} else if ("repeat-until-stable".equals(opName) && list.elements().size() >= 2) {
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, curDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
					}
					if (list.elements().get(1) instanceof ScmProgram subProg && !subProg.steps().isEmpty()) {
						if (subProg.steps().size() == 1 && subProg.steps().get(0) instanceof ScmWalk walk) {
							int rid = resolveRelId(snapshot, walk.relationName(), walk.relationId());
							relIdMap.put(walk.relationName(), rid);
							int fsub = (walk.direction() == ScmWalk.Direction.REVERSE_CSC)
									? ImpulseIsaTables.SUBOP_WALK_REV_FIXPOINT_STAR
									: ImpulseIsaTables.SUBOP_WALK_FWD_FIXPOINT_STAR;
							b.emitU16(ImpulseIsaTables.OP_WALK, fsub, 0, dstF, srcF, 0, 0, rid, 0);
							curF = dstF;
						} else {
							int prevF = 2;
							int loopPc = b.pc();
							b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_F, 0, prevF, curF, 0, 0, 0);
							for (ImpScmNode sub : subProg.steps()) {
								if (sub instanceof ScmWalk w) {
									int rid = resolveRelId(snapshot, w.relationName(), w.relationId());
									relIdMap.put(w.relationName(), rid);
									int wsub = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
											? ImpulseIsaTables.SUBOP_WALK_REV
											: ImpulseIsaTables.SUBOP_WALK_FWD;
									int nxt = 1 - curF;
									b.emitU16(ImpulseIsaTables.OP_WALK, wsub, 0, nxt, curF, 0, 0, rid, 0);
									curF = nxt;
								}
							}
							b.emit(ImpulseIsaTables.OP_SET_OP, ImpulseIsaTables.SUBOP_SET_OP_UNION, 0, curF, curF,
									prevF, 0, 0);
							if (curF != srcF) {
								b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_F, 0, srcF, curF, 0, 0, 0);
								curF = srcF;
							}
							b.emit(ImpulseIsaTables.OP_STABLE_CHECK, ImpulseIsaTables.SUBOP_STABLE_CHECK_FRONTIER_EQ, 0,
									0, curF, prevF, 0, 0);
							int relOff = loopPc - b.pc();
							b.emit(ImpulseIsaTables.OP_JCC, ImpulseIsaTables.SUBOP_JCC_JNST, 0, 0, 0, 0, 0, relOff);
						}
					}
				} else if ("fixpoint-max-hops".equals(opName) && list.elements().size() >= 3) {
					int maxHops = (list.elements().get(1) instanceof ScmLiteral.ScmInt si) ? (int) si.value() : 16;
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, curDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
					}
					if (maxHops > 0 && list.elements().get(2) instanceof ScmProgram subProg) {
						int prevF = 2;
						int cntR = 2;
						b.emitLoadConstI64(cntR, maxHops);
						int loopPc = b.pc();
						b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_F, 0, prevF, curF, 0, 0, 0);
						for (ImpScmNode sub : subProg.steps()) {
							if (sub instanceof ScmWalk w) {
								int rid = resolveRelId(snapshot, w.relationName(), w.relationId());
								int wsub = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
										? ImpulseIsaTables.SUBOP_WALK_REV
										: ImpulseIsaTables.SUBOP_WALK_FWD;
								int nxt = 1 - curF;
								b.emitU16(ImpulseIsaTables.OP_WALK, wsub, 0, nxt, curF, 0, 0, rid, 0);
								curF = nxt;
							}
						}
						b.emit(ImpulseIsaTables.OP_SET_OP, ImpulseIsaTables.SUBOP_SET_OP_UNION, 0, curF, curF, prevF, 0,
								0);
						if (curF != srcF) {
							b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_F, 0, srcF, curF, 0, 0, 0);
							curF = srcF;
						}
						b.emit(ImpulseIsaTables.OP_STABLE_CHECK, ImpulseIsaTables.SUBOP_STABLE_CHECK_FRONTIER_EQ, 0, 0,
								curF, prevF, 0, 0);
						int jstPc = b.emit(ImpulseIsaTables.OP_JCC, ImpulseIsaTables.SUBOP_JCC_JST, 0, 0, 0, 0, 0, 0);
						int relOff = loopPc - b.pc();
						b.emit(ImpulseIsaTables.OP_LOOP_DECR, ImpulseIsaTables.SUBOP_LOOP_DECR_DECR, 0, cntR, 0, 0, 0,
								relOff);
						b.patchImm32(jstPc, b.pc() - jstPc);
					}
				} else if ("set-op-nodes".equals(opName) && list.elements().size() >= 3) {
					String kind = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "union";
					String csv = list.elements().get(2) instanceof ScmLiteral.ScmString s ? s.value() : "";
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, curDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
					}
					long[] ids = parseCsvLongs(csv);
					int rhsF = 2;
					if (ids.length == 0) {
						b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, rhsF, 0, 0, 0,
								curDom, 0);
					} else {
						int cpId = b.addConstPoolFrontier(ids);
						b.emitU16(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_FRONTIER,
								0, rhsF, 0, 0, 0, cpId, curDom);
					}
					int ssub = switch (kind) {
						case "intersect" -> ImpulseIsaTables.SUBOP_SET_OP_INTERSECT;
						case "difference", "diff" -> ImpulseIsaTables.SUBOP_SET_OP_DIFF;
						default -> ImpulseIsaTables.SUBOP_SET_OP_UNION;
					};
					b.emit(ImpulseIsaTables.OP_SET_OP, ssub, 0, dstF, srcF, rhsF, 0, 0);
					curF = dstF;
				} else if ("cel-domain-filter".equals(opName) && list.elements().size() >= 3) {
					String domName = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "";
					String celExpr = list.elements().get(2) instanceof ScmLiteral.ScmString s ? s.value() : "true";
					int domId = resolveDomId(snapshot, domName, curDom);
					curDom = domId;
					maxDomSeen = Math.max(maxDomSeen, domId);
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, domId);
						paramMap.put("$seed", 0);
						frontierInit = true;
						actualStartDom = domId;
					}
					MiniCelExpr celAst = MiniCelParser.parse(celExpr);
					b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, dstF, 0, 0, 0, domId,
							0);
					int hdrPc = b.emitU16(ImpulseIsaTables.OP_FILTER_MORSEL, ImpulseIsaTables.SUBOP_FILTER_MORSEL_NODE,
							0, 0, srcF, 0, 0, 0, 0);
					int[] regs = {12, 1};
					int predM = emitCelMorselExpr(celAst, b, regs, snapshot, domId, -1, false);
					b.emit(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0, 9 /* MF1 */,
							predM, 0, 0, 0);
					b.emitU16(ImpulseIsaTables.OP_MORSEL_COMMIT, ImpulseIsaTables.SUBOP_MORSEL_COMMIT_YIELD_FRONTIER,
							ImpulseIsaTables.FLAG_ACCUM | ImpulseIsaTables.FLAG_MASKED_S3, dstF, 8 /* M0=node_id */, 0,
							9, domId, 0);
					int endPc = b.emitU16(ImpulseIsaTables.OP_MORSEL_END, ImpulseIsaTables.SUBOP_MORSEL_END_END, 0, 0,
							0, 0, 0, 0, 0);
					int span = endPc - hdrPc;
					b.patchImm16Hi(hdrPc, span);
					b.patchImm16Hi(endPc, span);
					curF = dstF;
				} else if ("cel-edge-walk".equals(opName) && list.elements().size() >= 4) {
					String dir = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "out";
					String relName = list.elements().get(2) instanceof ScmLiteral.ScmString s ? s.value() : "";
					String celExpr = list.elements().get(3) instanceof ScmLiteral.ScmString s ? s.value() : "true";
					boolean isRev = "in".equalsIgnoreCase(dir);
					int relId = resolveRelId(snapshot, relName, -1);
					relIdMap.put(relName, relId);
					int srcDom = isRev
							? resolveRelTgtDom(snapshot, relId, curDom)
							: resolveRelSrcDom(snapshot, relId, curDom);
					int tgtDom = isRev
							? resolveRelSrcDom(snapshot, relId, curDom)
							: resolveRelTgtDom(snapshot, relId, curDom);
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, srcDom);
						paramMap.put("$seed", 0);
						frontierInit = true;
						curDom = srcDom;
						actualStartDom = srcDom;
					}
					maxDomSeen = Math.max(maxDomSeen, tgtDom);
					MiniCelExpr celAst = MiniCelParser.parse(celExpr);
					b.emitU16(ImpulseIsaTables.OP_FSEED, ImpulseIsaTables.SUBOP_FSEED_EMPTY, 0, dstF, 0, 0, 0, tgtDom,
							0);
					int msub = isRev ? ImpulseIsaTables.SUBOP_WALK_MORSEL_REV : ImpulseIsaTables.SUBOP_WALK_MORSEL_FWD;
					int hdrPc = b.emitU16(ImpulseIsaTables.OP_WALK_MORSEL, msub, 0, 0, srcF, 0, 0, relId, 0);
					int[] regs = {12, 1};
					int predM = emitCelMorselExpr(celAst, b, regs, snapshot, tgtDom, relId, true);
					b.emit(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0, 9 /* MF1 */,
							predM, 0, 0, 0);
					b.emitU16(ImpulseIsaTables.OP_MORSEL_COMMIT, ImpulseIsaTables.SUBOP_MORSEL_COMMIT_YIELD_FRONTIER,
							ImpulseIsaTables.FLAG_ACCUM | ImpulseIsaTables.FLAG_MASKED_S3, dstF, 9 /* M1=tgt_id */, 0,
							9, tgtDom, 0);
					int endPc = b.emitU16(ImpulseIsaTables.OP_MORSEL_END, ImpulseIsaTables.SUBOP_MORSEL_END_END, 0, 0,
							0, 0, 0, 0, 0);
					int span = endPc - hdrPc;
					b.patchImm16Hi(hdrPc, span);
					b.patchImm16Hi(endPc, span);
					curF = dstF;
					curDom = tgtDom;
				} else if ("cel-domain-reduce".equals(opName) && list.elements().size() >= 4) {
					String kind = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "sum";
					String domName = list.elements().get(2) instanceof ScmLiteral.ScmString s ? s.value() : "";
					String attrName = list.elements().get(3) instanceof ScmLiteral.ScmString s ? s.value() : "";
					int domId = resolveDomId(snapshot, domName, curDom);
					curDom = domId;
					maxDomSeen = Math.max(maxDomSeen, domId);
					if (!frontierInit) {
						b.emitU16(ImpulseIsaTables.OP_LOAD_PARAM, ImpulseIsaTables.SUBOP_LOAD_PARAM_FRONTIER, 0, curF,
								0, 0, 0, 0, domId);
						paramMap.put("$seed", 0);
						frontierInit = true;
						actualStartDom = domId;
					}
					int aid = resolveNodeAttrId(snapshot, domId, attrName);
					b.emitU16(ImpulseIsaTables.OP_GATHER_NODE_ATTR,
							ImpulseIsaTables.SUBOP_GATHER_NODE_ATTR_FRONTIER_COMPACT, 0, curV, curF, 0, 0, domId, aid);
					int rsub = switch (kind.toLowerCase()) {
						case "min" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_MIN;
						case "max" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_MAX;
						case "mean", "avg" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_MEAN;
						case "count" -> ImpulseIsaTables.SUBOP_VEC_REDUCE_COUNT_VALID;
						default -> ImpulseIsaTables.SUBOP_VEC_REDUCE_SUM;
					};
					b.emit(ImpulseIsaTables.OP_VEC_REDUCE, rsub, 0, curR, curV, 0, 0, 0);
					activeIsScalar = true;
					activeIsVector = false;
				} else if ("lane-init-domain-seeds".equals(opName) && list.elements().size() >= 2) {
					String domName = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "";
					int domId = resolveDomId(snapshot, domName, curDom);
					curDom = domId;
					actualStartDom = domId;
					maxDomSeen = Math.max(maxDomSeen, domId);
					frontierInit = true;
					int k = list.elements().size() - 2;
					long[] seeds = new long[k];
					for (int i = 0; i < k; i++) {
						seeds[i] = (list.elements().get(2 + i) instanceof ScmLiteral.ScmInt si) ? si.value() : 0L;
					}
					int cpId = b.addConstPoolVec(ImpulseVector.ofU64(seeds, null));
					b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0, 0, 0, 0,
							0, cpId);
					b.emitU16(ImpulseIsaTables.OP_LANE_INIT_SEEDS, ImpulseIsaTables.SUBOP_LANE_INIT_SEEDS_AUTO_WIDTH, 0,
							dstF, 0, 0, 0, domId, 0);
					curF = dstF;
					activeIsMultiLane = true;
				} else if ("lane-init-domain-groups".equals(opName) && list.elements().size() >= 3) {
					String domName = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "";
					int domId = resolveDomId(snapshot, domName, curDom);
					curDom = domId;
					actualStartDom = domId;
					maxDomSeen = Math.max(maxDomSeen, domId);
					frontierInit = true;
					int pairInts = list.elements().size() - 3;
					int count = pairInts / 2;
					long[] nodes = new long[count];
					long[] lanes = new long[count];
					for (int i = 0; i < count; i++) {
						nodes[i] = (list.elements().get(3 + i * 2) instanceof ScmLiteral.ScmInt si) ? si.value() : 0L;
						lanes[i] = (list.elements().get(3 + i * 2 + 1) instanceof ScmLiteral.ScmInt si)
								? si.value()
								: 0L;
					}
					int cpNodes = b.addConstPoolVec(ImpulseVector.ofU64(nodes, null));
					int cpLanes = b.addConstPoolVec(ImpulseVector.ofU64(lanes, null));
					b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0, 0, 0, 0,
							0, cpNodes);
					b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_VEC, 0, 1, 0, 0,
							0, cpLanes);
					b.emitU16(ImpulseIsaTables.OP_LANE_INIT_GROUPS, ImpulseIsaTables.SUBOP_LANE_INIT_GROUPS_GROUPS, 0,
							dstF, 0, 1, 0, domId, 0);
					curF = dstF;
					activeIsMultiLane = true;
				} else if ("lane-walk-both".equals(opName) && list.elements().size() >= 2) {
					String relName = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "";
					int relId = resolveRelId(snapshot, relName, -1);
					relIdMap.put(relName, relId);
					b.emitU16(ImpulseIsaTables.OP_LANE_WALK, ImpulseIsaTables.SUBOP_LANE_WALK_BOTH, 0, dstF, srcF, 0, 0,
							relId, 0);
					curF = dstF;
					activeIsMultiLane = true;
				} else if ("lane-walk-union".equals(opName) && list.elements().size() >= 3) {
					String dir = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "out";
					int lwsub = "in".equalsIgnoreCase(dir)
							? ImpulseIsaTables.SUBOP_LANE_WALK_REV
							: ImpulseIsaTables.SUBOP_LANE_WALK_FWD;
					for (int i = 2; i < list.elements().size(); i++) {
						String relName = list.elements().get(i) instanceof ScmLiteral.ScmString s ? s.value() : "";
						int relId = resolveRelId(snapshot, relName, -1);
						relIdMap.put(relName, relId);
						int fl = (i == 2) ? 0 : ImpulseIsaTables.FLAG_ACCUM;
						b.emitU16(ImpulseIsaTables.OP_LANE_WALK, lwsub, fl, dstF, srcF, 0, 0, relId, 0);
						if (i == 2) {
							curDom = "in".equalsIgnoreCase(dir)
									? resolveRelSrcDom(snapshot, relId, curDom)
									: resolveRelTgtDom(snapshot, relId, curDom);
						}
					}
					curF = dstF;
					activeIsMultiLane = true;
				} else if ("lane-walk-where".equals(opName) && list.elements().size() >= 4) {
					String dir = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "out";
					String relName = list.elements().get(2) instanceof ScmLiteral.ScmString s ? s.value() : "";
					String celExpr = list.elements().get(3) instanceof ScmLiteral.ScmString s ? s.value() : "true";
					boolean isRev = "in".equalsIgnoreCase(dir);
					int relId = resolveRelId(snapshot, relName, -1);
					relIdMap.put(relName, relId);
					int tgtDom = isRev
							? resolveRelSrcDom(snapshot, relId, curDom)
							: resolveRelTgtDom(snapshot, relId, curDom);
					maxDomSeen = Math.max(maxDomSeen, tgtDom);
					MiniCelExpr celAst = MiniCelParser.parse(celExpr);
					int msub = isRev ? ImpulseIsaTables.SUBOP_WALK_MORSEL_REV : ImpulseIsaTables.SUBOP_WALK_MORSEL_FWD;
					int hdrPc = b.emitU16(ImpulseIsaTables.OP_WALK_MORSEL, msub, 0, 0, srcF, 0, 0, relId, 0);
					int[] regs = {12, 1};
					int predM = emitCelMorselExpr(celAst, b, regs, snapshot, tgtDom, relId, true);
					b.emit(ImpulseIsaTables.OP_SET_CONVERT, ImpulseIsaTables.SUBOP_SET_CONVERT_B2F, 0, 9 /* MF1 */,
							predM, 0, 0, 0);
					b.emitU16(ImpulseIsaTables.OP_MORSEL_COMMIT, ImpulseIsaTables.SUBOP_MORSEL_COMMIT_YIELD_LANE_OR,
							ImpulseIsaTables.FLAG_MASKED_S3, dstF, 9 /* M1=tgt_id */, 11 /* M3=lane_mask */, 9, tgtDom,
							0);
					int endPc = b.emitU16(ImpulseIsaTables.OP_MORSEL_END, ImpulseIsaTables.SUBOP_MORSEL_END_END, 0, 0,
							0, 0, 0, 0, 0);
					int span = endPc - hdrPc;
					b.patchImm16Hi(hdrPc, span);
					b.patchImm16Hi(endPc, span);
					curF = dstF;
					curDom = tgtDom;
					activeIsMultiLane = true;
				} else if ("lane-fixpoint-max-hops".equals(opName) && list.elements().size() >= 3) {
					String relName = list.elements().get(1) instanceof ScmLiteral.ScmString s ? s.value() : "";
					int maxHops = (list.elements().get(2) instanceof ScmLiteral.ScmInt si) ? (int) si.value() : 16;
					int relId = resolveRelId(snapshot, relName, -1);
					relIdMap.put(relName, relId);
					if (maxHops > 0) {
						int cntR = 2;
						b.emitLoadConstI64(cntR, maxHops);
						int loopPc = b.pc();
						b.emitU16(ImpulseIsaTables.OP_LANE_WALK, ImpulseIsaTables.SUBOP_LANE_WALK_FWD,
								ImpulseIsaTables.FLAG_ACCUM, curF, curF, 0, 0, relId, 0);
						int relOff = loopPc - b.pc();
						b.emit(ImpulseIsaTables.OP_LOOP_DECR, ImpulseIsaTables.SUBOP_LOOP_DECR_DECR, 0, cntR, 0, 0, 0,
								relOff);
					}
					activeIsMultiLane = true;
				} else if ("lane-quorum-inplace".equals(opName) && list.elements().size() >= 4) {
					long reqMask = list.elements().get(2) instanceof ScmLiteral.ScmString s
							? Long.parseUnsignedLong(s.value())
							: 0L;
					long forbidMask = list.elements().get(3) instanceof ScmLiteral.ScmString s
							? Long.parseUnsignedLong(s.value())
							: 0L;
					if (forbidMask != 0L) {
						int rMask = 3;
						b.emitLoadConstU64(rMask, forbidMask);
						b.emit(ImpulseIsaTables.OP_LANE_BITWISE, ImpulseIsaTables.SUBOP_LANE_BITWISE_PRUNE_MASK,
								ImpulseIsaTables.FLAG_SCALAR_RHS, dstF, srcF, rMask, 0, 0);
						curF = dstF;
					}
					if (reqMask != 0L) {
						int rMask = 3;
						b.emitLoadConstU64(rMask, reqMask);
						int sF = curF;
						int dF = 1 - curF;
						b.emit(ImpulseIsaTables.OP_LANE_BITWISE, ImpulseIsaTables.SUBOP_LANE_BITWISE_KEEP_MASK,
								ImpulseIsaTables.FLAG_SCALAR_RHS, dF, sF, rMask, 0, 0);
						curF = dF;
					}
					activeIsMultiLane = true;
				}
			}
		}

		if (activeIsScalar && !hasCollect) {
			b.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_SCALAR, 0, 0, curR, 0, 0, 0, 0);
		} else if (activeIsVector && !hasCollect) {
			b.emitU16(ImpulseIsaTables.OP_COLLECT, ImpulseIsaTables.SUBOP_COLLECT_VEC, 0, 0, curV, 0, 0, 0, 0);
		}
		b.emit(ImpulseIsaTables.OP_HALT, ImpulseIsaTables.SUBOP_HALT_OK, 0, 0, 0, 0, 0, 0);

		int reqDoms = (snapshot != null && snapshot.getDomainNames() != null && !snapshot.getDomainNames().isEmpty())
				? snapshot.getDomainNames().size()
				: (maxDomSeen + 1);
		ImpulseImpbContainer container = b.buildImpb(0, 0, reqDoms);

		// Static verification post-condition: emitted bytecode MUST pass
		// ImpulseValidator
		ImpulseValidator.ValidationResult vr = ImpulseValidator.validate(container.codeWords);
		if (vr.status() != ImpulseIsaTables.IMPULSE_VM_OK) {
			throw new IllegalStateException("ImpOpsBytecodeEmitter produced invalid v0.11 bytecode at pc=" + vr.badPc()
					+ " status=" + vr.status());
		}

		return new CompiledProgram(container, relIdMap, paramMap, actualStartDom, curDom, reqDoms, curF,
				activeIsMultiLane);
	}

	private static int emitMorselExpr(ImpScmNode expr, V11ProgramBuilder b, int[] regs, ImpulseGraphSnapshot snap,
			int tgtDom, int relId, boolean isNodeMorsel) {
		if (expr instanceof ScmList list && !list.elements().isEmpty()
				&& list.elements().get(0) instanceof ScmSymbol sym) {
			String op = sym.name();
			int csub = switch (op) {
				case "stream-cmp-eq", "vec-cmp-eq", "=" -> ImpulseIsaTables.SUBOP_VEC_CMP_EQ;
				case "stream-cmp-ne", "vec-cmp-ne", "!=" -> ImpulseIsaTables.SUBOP_VEC_CMP_NE;
				case "stream-cmp-lt", "vec-cmp-lt", "<" -> ImpulseIsaTables.SUBOP_VEC_CMP_LT;
				case "stream-cmp-le", "vec-cmp-le", "<=" -> ImpulseIsaTables.SUBOP_VEC_CMP_LE;
				case "stream-cmp-gt", "vec-cmp-gt", ">" -> ImpulseIsaTables.SUBOP_VEC_CMP_GT;
				case "stream-cmp-ge", "vec-cmp-ge", ">=" -> ImpulseIsaTables.SUBOP_VEC_CMP_GE;
				default -> -1;
			};
			if (csub >= 0 && list.elements().size() >= 3) {
				int lhs = emitMorselExpr(list.elements().get(1), b, regs, snap, tgtDom, relId, isNodeMorsel);
				int dst = allocMReg(regs);
				if (list.elements().get(2) instanceof ScmLiteral lit) {
					int r = allocRReg(regs);
					if (lit instanceof ScmLiteral.ScmFloat sf)
						b.emitLoadConstF64(r, sf.value());
					else if (lit instanceof ScmLiteral.ScmInt si)
						b.emitLoadConstI64(r, si.value());
					else
						b.emitLoadConstI64(r, 0L);
					b.emit(ImpulseIsaTables.OP_VEC_CMP, csub, ImpulseIsaTables.FLAG_SCALAR_RHS, dst, lhs, r, 0, 0);
				} else {
					int rhs = emitMorselExpr(list.elements().get(2), b, regs, snap, tgtDom, relId, isNodeMorsel);
					b.emit(ImpulseIsaTables.OP_VEC_CMP, csub, 0, dst, lhs, rhs, 0, 0);
				}
				return dst;
			}
			if (("stream-logic-and".equals(op) || "mask-and".equals(op) || "and".equals(op))
					&& list.elements().size() >= 3) {
				int lhs = emitMorselExpr(list.elements().get(1), b, regs, snap, tgtDom, relId, isNodeMorsel);
				int rhs = emitMorselExpr(list.elements().get(2), b, regs, snap, tgtDom, relId, isNodeMorsel);
				int dst = allocMReg(regs);
				b.emit(ImpulseIsaTables.OP_VEC_3VL_OP, ImpulseIsaTables.SUBOP_VEC_3VL_OP_AND, 0, dst, lhs, rhs, 0, 0);
				return dst;
			}
			if (("stream-logic-or".equals(op) || "mask-or".equals(op) || "or".equals(op))
					&& list.elements().size() >= 3) {
				int lhs = emitMorselExpr(list.elements().get(1), b, regs, snap, tgtDom, relId, isNodeMorsel);
				int rhs = emitMorselExpr(list.elements().get(2), b, regs, snap, tgtDom, relId, isNodeMorsel);
				int dst = allocMReg(regs);
				b.emit(ImpulseIsaTables.OP_VEC_3VL_OP, ImpulseIsaTables.SUBOP_VEC_3VL_OP_OR, 0, dst, lhs, rhs, 0, 0);
				return dst;
			}
			if ("stream-load-attr".equals(op) || "get-attr".equals(op) || "node-attr".equals(op)) {
				int aid = 0;
				if (snap != null && list.elements().size() >= 2) {
					String aname = list.elements().get(1) instanceof ScmLiteral.ScmString ls
							? ls.value()
							: (list.elements().get(1) instanceof ScmSymbol s ? s.name() : "");
					String dname = snap.getDomainName(tgtDom);
					if (dname != null) {
						List<String> names = snap.getDomainAttributeNames(dname);
						int idx = names.indexOf(aname);
						if (idx >= 0)
							aid = idx;
					}
				}
				int dst = allocMReg(regs);
				int idxReg = isNodeMorsel ? 8 : 9;
				b.emitU16(ImpulseIsaTables.OP_GATHER_NODE_ATTR, ImpulseIsaTables.SUBOP_GATHER_NODE_ATTR_VEC, 0, dst,
						idxReg, 0, 0, tgtDom, aid);
				return dst;
			}
		}
		return isNodeMorsel ? 8 : 9;
	}

	private static int emitBranch(ImpScmNode branch, int inF, int outF, V11ProgramBuilder b, ImpulseGraphSnapshot snap,
			Map<String, Integer> relIdMap, int curDom) {
		if (branch instanceof ScmWalk w) {
			int rid = resolveRelId(snap, w.relationName(), w.relationId());
			relIdMap.put(w.relationName(), rid);
			int tdom = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
					? resolveRelSrcDom(snap, rid, curDom)
					: resolveRelTgtDom(snap, rid, curDom);
			int wsub = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
					? ImpulseIsaTables.SUBOP_WALK_REV
					: ImpulseIsaTables.SUBOP_WALK_FWD;
			b.emitU16(ImpulseIsaTables.OP_WALK, wsub, 0, outF, inF, 0, 0, rid, 0);
			return tdom;
		}
		if (branch instanceof ScmProgram sp) {
			int curB = inF;
			int bdom = curDom;
			for (ImpScmNode sub : sp.steps()) {
				if (sub instanceof ScmWalk w) {
					int rid = resolveRelId(snap, w.relationName(), w.relationId());
					relIdMap.put(w.relationName(), rid);
					bdom = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
							? resolveRelSrcDom(snap, rid, bdom)
							: resolveRelTgtDom(snap, rid, bdom);
					int wsub = (w.direction() == ScmWalk.Direction.REVERSE_CSC)
							? ImpulseIsaTables.SUBOP_WALK_REV
							: ImpulseIsaTables.SUBOP_WALK_FWD;
					int nxt = (curB == outF) ? (outF + 4) : outF;
					b.emitU16(ImpulseIsaTables.OP_WALK, wsub, 0, nxt, curB, 0, 0, rid, 0);
					curB = nxt;
				}
			}
			if (curB != outF) {
				b.emit(ImpulseIsaTables.OP_MOV, ImpulseIsaTables.SUBOP_MOV_F, 0, outF, curB, 0, 0, 0);
			}
			return bdom;
		}
		return curDom;
	}

	private static int resolveRelId(ImpulseGraphSnapshot snap, String name, int fallback) {
		if (fallback >= 0)
			return fallback;
		if (snap == null || name == null || name.isEmpty())
			return 0;
		RelationSnapshot rs = snap.getRelationSnapshot(name);
		if (rs != null && rs.getRelationId() >= 0)
			return rs.getRelationId();
		Map<String, RelationSnapshot> all = snap.getAllRelationSnapshots();
		if (all != null) {
			int idx = 0;
			for (Map.Entry<String, RelationSnapshot> e : all.entrySet()) {
				if (e.getKey().equalsIgnoreCase(name)) {
					int id = e.getValue() != null ? e.getValue().getRelationId() : -1;
					return id >= 0 ? id : idx;
				}
				idx++;
			}
		}
		return 0;
	}

	private static int resolveRelSrcDom(ImpulseGraphSnapshot snap, int relId, int fallback) {
		if (snap == null)
			return fallback;
		RelationSnapshot rs = snap.getRelationSnapshot(relId);
		return rs != null ? rs.getSrcDomainId() : fallback;
	}

	private static int resolveRelTgtDom(ImpulseGraphSnapshot snap, int relId, int fallback) {
		if (snap == null)
			return fallback;
		RelationSnapshot rs = snap.getRelationSnapshot(relId);
		return rs != null ? rs.getTgtDomainId() : fallback;
	}

	private static int resolveDomId(ImpulseGraphSnapshot snap, String domName, int fallback) {
		if (snap == null || domName == null || domName.isEmpty()) {
			return fallback;
		}
		if (snap instanceof org.impulsegraph.storage.csr.GraphSnapshot gs) {
			var ld = gs.getDomain(domName);
			if (ld != null) {
				return ld.domainId();
			}
		}
		Set<String> names = snap.getDomainNames();
		if (names != null) {
			int idx = 0;
			for (String n : names) {
				if (n.equalsIgnoreCase(domName)) {
					return idx;
				}
				idx++;
			}
		}
		return fallback;
	}

	private static int resolveNodeAttrId(ImpulseGraphSnapshot snap, int domId, String attrName) {
		if (snap == null || attrName == null || attrName.isEmpty()) {
			return 0;
		}
		String dname = snap.getDomainName(domId);
		if (dname != null) {
			List<String> names = snap.getDomainAttributeNames(dname);
			for (int i = 0; i < names.size(); i++) {
				if (names.get(i).equalsIgnoreCase(attrName)) {
					return i;
				}
			}
		}
		return 0;
	}

	private static int resolveEdgeAttrId(ImpulseGraphSnapshot snap, int relId, String attrName) {
		if (snap == null || attrName == null || attrName.isEmpty()) {
			return 0;
		}
		RelationSnapshot rs = snap.getRelationSnapshot(relId);
		if (rs instanceof org.impulsegraph.storage.csr.RelationSnapshot crs) {
			int idx = crs.findAttributeIndex(attrName);
			if (idx >= 0) {
				return idx;
			}
		}
		return 0;
	}

	private static long[] parseCsvLongs(String csv) {
		if (csv == null || csv.isBlank()) {
			return new long[0];
		}
		String[] parts = csv.split(",");
		List<Long> ids = new ArrayList<>(parts.length);
		for (String p : parts) {
			String t = p.trim();
			if (!t.isEmpty()) {
				ids.add(Long.parseLong(t));
			}
		}
		long[] out = new long[ids.size()];
		for (int i = 0; i < ids.size(); i++) {
			out[i] = ids.get(i);
		}
		return out;
	}

	private static int allocMReg(int[] regs) {
		int r = regs[0];
		if (r < 12 || r > 15) {
			throw new IllegalStateException("Morsel register overflow: register " + r + " outside M4..M7 (12..15)");
		}
		regs[0] = r + 1;
		return r;
	}

	private static int allocRReg(int[] regs) {
		int r = regs[1];
		if (r < 1 || r > 7) {
			throw new IllegalStateException(
					"Morsel scalar register overflow: register " + r + " outside R1..R7 (1..7)");
		}
		regs[1] = r + 1;
		return r;
	}

	private static int emitCelMorselExpr(MiniCelExpr node, V11ProgramBuilder b, int[] regs, ImpulseGraphSnapshot snap,
			int domId, int relId, boolean isEdgeMorsel) {
		if (node == null) {
			return 8;
		}
		switch (node.kind) {
			case BINARY -> {
				String op = node.text;
				MiniCelExpr lhsNode = node.args.get(0);
				MiniCelExpr rhsNode = node.args.get(1);
				if ("&&".equals(op)) {
					int l = emitCelMorselExpr(lhsNode, b, regs, snap, domId, relId, isEdgeMorsel);
					int r = emitCelMorselExpr(rhsNode, b, regs, snap, domId, relId, isEdgeMorsel);
					int dst = allocMReg(regs);
					b.emit(ImpulseIsaTables.OP_VEC_3VL_OP, ImpulseIsaTables.SUBOP_VEC_3VL_OP_AND, 0, dst, l, r, 0, 0);
					return dst;
				}
				if ("||".equals(op)) {
					int l = emitCelMorselExpr(lhsNode, b, regs, snap, domId, relId, isEdgeMorsel);
					int r = emitCelMorselExpr(rhsNode, b, regs, snap, domId, relId, isEdgeMorsel);
					int dst = allocMReg(regs);
					b.emit(ImpulseIsaTables.OP_VEC_3VL_OP, ImpulseIsaTables.SUBOP_VEC_3VL_OP_OR, 0, dst, l, r, 0, 0);
					return dst;
				}
				int csub = switch (op) {
					case "==", "=" -> ImpulseIsaTables.SUBOP_VEC_CMP_EQ;
					case "!=" -> ImpulseIsaTables.SUBOP_VEC_CMP_NE;
					case "<" -> ImpulseIsaTables.SUBOP_VEC_CMP_LT;
					case "<=" -> ImpulseIsaTables.SUBOP_VEC_CMP_LE;
					case ">" -> ImpulseIsaTables.SUBOP_VEC_CMP_GT;
					case ">=" -> ImpulseIsaTables.SUBOP_VEC_CMP_GE;
					default -> -1;
				};
				if (csub >= 0) {
					int l = emitCelMorselExpr(lhsNode, b, regs, snap, domId, relId, isEdgeMorsel);
					int dst = allocMReg(regs);
					if (rhsNode.kind == MiniCelExpr.Kind.STR) {
						int strCp = b.addConstPoolStr(rhsNode.text);
						int rReg = allocRReg(regs);
						b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_STR, 0, rReg,
								0, 0, 0, strCp);
						int ssub = switch (op) {
							case "==", "=" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_EQ;
							case "!=" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_NE;
							case "<" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_LT;
							case "<=" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_LE;
							case ">" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_GT;
							case ">=" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_GE;
							default -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_EQ;
						};
						b.emit(ImpulseIsaTables.OP_VEC_STR_PRED, ssub, ImpulseIsaTables.FLAG_SCALAR_RHS, dst, l, rReg,
								0, 0);
						return dst;
					}
					if (rhsNode.kind == MiniCelExpr.Kind.INT || rhsNode.kind == MiniCelExpr.Kind.FLOAT
							|| rhsNode.kind == MiniCelExpr.Kind.BOOL) {
						int rReg = allocRReg(regs);
						if (rhsNode.kind == MiniCelExpr.Kind.FLOAT) {
							b.emitLoadConstF64(rReg, rhsNode.fval);
						} else {
							b.emitLoadConstI64(rReg, rhsNode.ival);
						}
						b.emit(ImpulseIsaTables.OP_VEC_CMP, csub, ImpulseIsaTables.FLAG_SCALAR_RHS, dst, l, rReg, 0, 0);
						return dst;
					}
					int r = emitCelMorselExpr(rhsNode, b, regs, snap, domId, relId, isEdgeMorsel);
					b.emit(ImpulseIsaTables.OP_VEC_CMP, csub, 0, dst, l, r, 0, 0);
					return dst;
				}
				int asub = switch (op) {
					case "+" -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_ADD;
					case "-" -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_SUB;
					case "*" -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_MUL;
					case "/" -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_DIV;
					case "%" -> ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_REM;
					default -> -1;
				};
				if (asub >= 0) {
					int l = emitCelMorselExpr(lhsNode, b, regs, snap, domId, relId, isEdgeMorsel);
					int dst = allocMReg(regs);
					if (rhsNode.kind == MiniCelExpr.Kind.INT || rhsNode.kind == MiniCelExpr.Kind.FLOAT) {
						int rReg = allocRReg(regs);
						if (rhsNode.kind == MiniCelExpr.Kind.FLOAT) {
							b.emitLoadConstF64(rReg, rhsNode.fval);
						} else {
							b.emitLoadConstI64(rReg, rhsNode.ival);
						}
						b.emit(ImpulseIsaTables.OP_VEC_MATH_ARITH, asub, ImpulseIsaTables.FLAG_SCALAR_RHS, dst, l, rReg,
								0, 0);
						return dst;
					}
					int r = emitCelMorselExpr(rhsNode, b, regs, snap, domId, relId, isEdgeMorsel);
					b.emit(ImpulseIsaTables.OP_VEC_MATH_ARITH, asub, 0, dst, l, r, 0, 0);
					return dst;
				}
				return 8;
			}
			case UNARY -> {
				int inner = emitCelMorselExpr(node.args.get(0), b, regs, snap, domId, relId, isEdgeMorsel);
				int dst = allocMReg(regs);
				if ("!".equals(node.text)) {
					b.emit(ImpulseIsaTables.OP_VEC_3VL_OP, ImpulseIsaTables.SUBOP_VEC_3VL_OP_NOT, 0, dst, inner, 0, 0,
							0);
					return dst;
				}
				if ("-".equals(node.text)) {
					b.emit(ImpulseIsaTables.OP_VEC_MATH_ARITH, ImpulseIsaTables.SUBOP_VEC_MATH_ARITH_NEG, 0, dst, inner,
							0, 0, 0);
					return dst;
				}
				return inner;
			}
			case CALL -> {
				String fn = node.text;
				if (node.args.size() >= 2 && node.args.get(1).kind == MiniCelExpr.Kind.STR) {
					int ssub = switch (fn) {
						case "startsWith" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_STARTS_WITH;
						case "endsWith" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_ENDS_WITH;
						case "contains" -> ImpulseIsaTables.SUBOP_VEC_STR_PRED_CONTAINS;
						default -> -1;
					};
					if (ssub >= 0) {
						int recv = emitCelMorselExpr(node.args.get(0), b, regs, snap, domId, relId, isEdgeMorsel);
						int strCp = b.addConstPoolStr(node.args.get(1).text);
						int rReg = allocRReg(regs);
						b.emit(ImpulseIsaTables.OP_LOAD_CONST_POOL, ImpulseIsaTables.SUBOP_LOAD_CONST_POOL_STR, 0, rReg,
								0, 0, 0, strCp);
						int dst = allocMReg(regs);
						b.emit(ImpulseIsaTables.OP_VEC_STR_PRED, ssub, ImpulseIsaTables.FLAG_SCALAR_RHS, dst, recv,
								rReg, 0, 0);
						return dst;
					}
				}
				return 8;
			}
			case IDENT -> {
				String attrName = node.text;
				if (!isEdgeMorsel && ("id".equals(attrName) || "_id".equals(attrName))) {
					return 8; // M0 = node_id
				}
				int dst = allocMReg(regs);
				if (isEdgeMorsel) {
					int aid = resolveEdgeAttrId(snap, relId, attrName);
					b.emitU16(ImpulseIsaTables.OP_GATHER_EDGE_ATTR, ImpulseIsaTables.SUBOP_GATHER_EDGE_ATTR_VEC, 0, dst,
							10 /* M2=edge_id */, 0, 0, relId, aid);
				} else {
					int aid = resolveNodeAttrId(snap, domId, attrName);
					b.emitU16(ImpulseIsaTables.OP_GATHER_NODE_ATTR, ImpulseIsaTables.SUBOP_GATHER_NODE_ATTR_VEC, 0, dst,
							8 /* M0=node_id */, 0, 0, domId, aid);
				}
				return dst;
			}
			default -> {
				return 8;
			}
		}
	}

	private record MiniCelExpr(Kind kind, String text, long ival, double fval, List<MiniCelExpr> args) {
		enum Kind {
			INT, FLOAT, BOOL, STR, IDENT, UNARY, BINARY, CALL
		}
	}

	private static final class MiniCelParser {
		private final String src;
		private int pos;

		private MiniCelParser(String src) {
			this.src = src != null ? src : "";
			this.pos = 0;
		}

		static MiniCelExpr parse(String src) {
			return new MiniCelParser(src).parseOr();
		}

		private void skipWs() {
			while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
				pos++;
			}
		}

		private boolean match(String tok) {
			skipWs();
			if (src.startsWith(tok, pos)) {
				pos += tok.length();
				return true;
			}
			return false;
		}

		private MiniCelExpr parseOr() {
			MiniCelExpr left = parseAnd();
			while (match("||")) {
				left = new MiniCelExpr(MiniCelExpr.Kind.BINARY, "||", 0, 0, List.of(left, parseAnd()));
			}
			return left;
		}

		private MiniCelExpr parseAnd() {
			MiniCelExpr left = parseCmp();
			while (match("&&")) {
				left = new MiniCelExpr(MiniCelExpr.Kind.BINARY, "&&", 0, 0, List.of(left, parseCmp()));
			}
			return left;
		}

		private MiniCelExpr parseCmp() {
			MiniCelExpr left = parseAdd();
			skipWs();
			for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">", "="}) {
				if (match(op)) {
					return new MiniCelExpr(MiniCelExpr.Kind.BINARY, op, 0, 0, List.of(left, parseAdd()));
				}
			}
			return left;
		}

		private MiniCelExpr parseAdd() {
			MiniCelExpr left = parseMul();
			while (true) {
				if (match("+")) {
					left = new MiniCelExpr(MiniCelExpr.Kind.BINARY, "+", 0, 0, List.of(left, parseMul()));
				} else if (match("-")) {
					left = new MiniCelExpr(MiniCelExpr.Kind.BINARY, "-", 0, 0, List.of(left, parseMul()));
				} else {
					break;
				}
			}
			return left;
		}

		private MiniCelExpr parseMul() {
			MiniCelExpr left = parseUnary();
			while (true) {
				if (match("*")) {
					left = new MiniCelExpr(MiniCelExpr.Kind.BINARY, "*", 0, 0, List.of(left, parseUnary()));
				} else if (match("/")) {
					left = new MiniCelExpr(MiniCelExpr.Kind.BINARY, "/", 0, 0, List.of(left, parseUnary()));
				} else if (match("%")) {
					left = new MiniCelExpr(MiniCelExpr.Kind.BINARY, "%", 0, 0, List.of(left, parseUnary()));
				} else {
					break;
				}
			}
			return left;
		}

		private MiniCelExpr parseUnary() {
			if (match("!")) {
				return new MiniCelExpr(MiniCelExpr.Kind.UNARY, "!", 0, 0, List.of(parseUnary()));
			}
			if (match("-")) {
				MiniCelExpr inner = parseUnary();
				if (inner.kind == MiniCelExpr.Kind.INT) {
					return new MiniCelExpr(MiniCelExpr.Kind.INT, "", -inner.ival, 0, List.of());
				}
				if (inner.kind == MiniCelExpr.Kind.FLOAT) {
					return new MiniCelExpr(MiniCelExpr.Kind.FLOAT, "", 0, -inner.fval, List.of());
				}
				return new MiniCelExpr(MiniCelExpr.Kind.UNARY, "-", 0, 0, List.of(inner));
			}
			return parsePostfix();
		}

		private MiniCelExpr parsePostfix() {
			MiniCelExpr base = parsePrimary();
			while (match(".")) {
				String field = readIdent();
				if (match("(")) {
					List<MiniCelExpr> args = new ArrayList<>();
					args.add(base);
					if (!match(")")) {
						args.add(parseOr());
						while (match(",")) {
							args.add(parseOr());
						}
						match(")");
					}
					base = new MiniCelExpr(MiniCelExpr.Kind.CALL, field, 0, 0, args);
				} else {
					base = new MiniCelExpr(MiniCelExpr.Kind.IDENT, field, 0, 0, List.of());
				}
			}
			return base;
		}

		private MiniCelExpr parsePrimary() {
			skipWs();
			if (pos >= src.length()) {
				return new MiniCelExpr(MiniCelExpr.Kind.BOOL, "true", 1, 0, List.of());
			}
			char c = src.charAt(pos);
			if (c == '(') {
				pos++;
				MiniCelExpr e = parseOr();
				match(")");
				return e;
			}
			if (c == '\'' || c == '"') {
				char q = c;
				pos++;
				StringBuilder sb = new StringBuilder();
				while (pos < src.length() && src.charAt(pos) != q) {
					if (src.charAt(pos) == '\\' && pos + 1 < src.length()) {
						pos++;
					}
					sb.append(src.charAt(pos++));
				}
				if (pos < src.length())
					pos++;
				return new MiniCelExpr(MiniCelExpr.Kind.STR, sb.toString(), 0, 0, List.of());
			}
			if (Character.isDigit(c)) {
				int start = pos;
				boolean isFloat = false;
				while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
					if (src.charAt(pos) == '.')
						isFloat = true;
					pos++;
				}
				String num = src.substring(start, pos);
				if (isFloat) {
					return new MiniCelExpr(MiniCelExpr.Kind.FLOAT, num, 0, Double.parseDouble(num), List.of());
				}
				return new MiniCelExpr(MiniCelExpr.Kind.INT, num, Long.parseLong(num), 0, List.of());
			}
			String id = readIdent();
			if ("true".equals(id)) {
				return new MiniCelExpr(MiniCelExpr.Kind.BOOL, "true", 1, 0, List.of());
			}
			if ("false".equals(id)) {
				return new MiniCelExpr(MiniCelExpr.Kind.BOOL, "false", 0, 0, List.of());
			}
			return new MiniCelExpr(MiniCelExpr.Kind.IDENT, id, 0, 0, List.of());
		}

		private String readIdent() {
			skipWs();
			int start = pos;
			while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'
					|| src.charAt(pos) == '$')) {
				pos++;
			}
			return src.substring(start, pos);
		}
	}
}
