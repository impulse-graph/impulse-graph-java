package org.impulsegraph.vm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

import static org.impulsegraph.vm.ImpulseIsaTables.*;

/**
 * Normative Pillar 3 Evaluator ({@code 0x30}–{@code 0x3B}, 11 opcodes / 71
 * subops) for ImpulseVM v0.11.0.
 *
 * <p>
 * Bit-identical to {@code V11_eval_p3} in {@code impulse-ocaml}:
 * {@code OP_WALK}, {@code OP_WALK_FILTERED}, {@code OP_DEGREE},
 * {@code OP_SPMV}, {@code OP_WALK_MORSEL}, {@code OP_FILTER_MORSEL},
 * {@code OP_MORSEL_COMMIT}, {@code OP_MORSEL_END}, {@code OP_SAMPLE_NEIGHBORS},
 * {@code OP_HAS_TOPOLOGY}, and {@code OP_GRAPH_KERNEL}.
 */
public final class ImpulsePillar3Ops {

	public static final int RESULT_UNHANDLED = 0;
	public static final int RESULT_STEPPED = 1;

	private ImpulsePillar3Ops() {
	}

	public static int step(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, long[] program, long w,
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

	private static ImpulseSnapshotView getView(ImpulseInterpreter.SnapshotBinding bind, ImpulseWorkspace ws) {
		if (bind == null || bind.view == null) {
			ws.status = IMPULSE_VM_ERR_NULL_SNAPSHOT;
			return null;
		}
		return bind.view;
	}

	private static ImpulseSnapshotView.RelView getRel(ImpulseInterpreter.SnapshotBinding bind, ImpulseWorkspace ws,
			int rid) {
		ImpulseSnapshotView view = getView(bind, ws);
		if (view == null)
			return null;
		if (rid < 0 || rid >= view.relations.length || view.relations[rid] == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return null;
		}
		return view.relations[rid];
	}

	private static ImpulseFrontier getF1Bit(ImpulseWorkspace ws, int idx) {
		if (!ws.isFInitialized(idx) || ws.fRegs[idx].multiLane) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return null;
		}
		return ws.fRegs[idx];
	}

	private static void setScalarFlags(ImpulseWorkspace ws, boolean valid, long val) {
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

	private static void mlSiftDown(long[] nodes, long[] masks, int start, int end) {
		int root = start;
		while ((root << 1) + 1 < end) {
			int child = (root << 1) + 1;
			int swap = root;
			if (nodes[swap] < nodes[child])
				swap = child;
			if (child + 1 < end && nodes[swap] < nodes[child + 1])
				swap = child + 1;
			if (swap == root)
				return;
			long tn = nodes[root];
			nodes[root] = nodes[swap];
			nodes[swap] = tn;
			long tm = masks[root];
			masks[root] = masks[swap];
			masks[swap] = tm;
			root = swap;
		}
	}

	private static int mlSortAndCoalesce(long[] nodes, long[] masks, int count) {
		if (count <= 0)
			return 0;
		for (int i = (count >>> 1) - 1; i >= 0; i--) {
			mlSiftDown(nodes, masks, i, count);
		}
		for (int end = count - 1; end > 0; end--) {
			long tn = nodes[end];
			nodes[end] = nodes[0];
			nodes[0] = tn;
			long tm = masks[end];
			masks[end] = masks[0];
			masks[0] = tm;
			mlSiftDown(nodes, masks, 0, end);
		}
		int w = 0;
		int i = 0;
		while (i < count) {
			long u = nodes[i];
			long m = masks[i];
			i++;
			while (i < count && nodes[i] == u) {
				m |= masks[i];
				i++;
			}
			if (m != 0L) {
				nodes[w] = u;
				masks[w] = m;
				w++;
			}
		}
		return w;
	}

	// --- 0x30 OP_WALK ---
	private static void execWalk(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int flags,
			int dst, int s1, int s3, int relId, int imm16Hi) {
		ImpulseSnapshotView.RelView rel = getRel(bind, ws, relId);
		if (rel == null)
			return;
		ImpulseFrontier fSrc = getF1Bit(ws, s1);
		if (fSrc == null)
			return;
		ImpulseSnapshotView view = bind.view;

		boolean endo = (rel.srcDom == rel.tgtDom);
		int inDom = (subop == 1 || subop == 9) ? rel.tgtDom : rel.srcDom;
		int outDom = (subop == 1 || subop == 9) ? rel.srcDom : rel.tgtDom;
		if ((subop == 8 || subop == 9) && !rel.hasCooSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		if (subop >= 2 && subop <= 7 && !endo) {
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

		int fastOutCard = (int) view.domainCards[outDom];
		int outWords = Math.max(1, (fastOutCard + 63) >>> 6);

		// Zero-alloc fast path for all walk subops (0=fwd, 1=rev, 2=both, 3=fwd_plus,
		// 4=fwd_star, 5=rev_plus, 6=rev_star, 7=fwd_bounded, 8=coo_fwd, 9=coo_rev)
		if (subop >= 0 && subop <= 9) {
			boolean isCoo = (subop == 8 || subop == 9);
			boolean needsRev = (subop == 1 || subop == 2 || subop == 5 || subop == 6);
			boolean needsFwd = (subop == 0 || subop == 2 || subop == 3 || subop == 4 || subop == 7);
			if (isCoo && !rel.hasCooSegments()) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			if (needsFwd && !rel.hasCsrSegments()) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			if (needsRev && !rel.hasCscSegments()) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			ImpulseFrontier fe = null;
			if ((flags & FLAG_MASKED_S3) != 0) {
				fe = getF1Bit(ws, s3);
				if (fe == null)
					return;
				if (fe.domainId != outDom) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
			}
			ImpulseFrontier fd = null;
			if ((flags & FLAG_ACCUM) != 0) {
				fd = getF1Bit(ws, dst);
				if (fd == null)
					return;
				if (fd.domainId != outDom) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
			}
			long[] exclBits = null;
			if (fe != null && !fe.isEmpty()) {
				exclBits = ws.arena.allocLongZeroed(outWords);
				int fc = fe.cardinality();
				int[] fids = ws.arena.allocInt(fc);
				int fn = fe.toSortedArrayInto(fids);
				for (int i = 0; i < fn; i++) {
					int u = fids[i];
					exclBits[u >>> 6] |= (1L << u);
				}
			}
			long[] outBits = ws.arena.allocLongZeroed(outWords);
			int csrRows = rel.csrRowCount();
			int cscRows = needsRev ? rel.cscRowCount() : 0;
			int srcCard = fSrc.cardinality();
			boolean isMultiHop = (subop >= 3 && subop <= 7);
			int[] curQ = ws.arena.allocInt(isMultiHop ? Math.max(srcCard, fastOutCard) : srcCard);
			int curLen = fSrc.toSortedArrayInto(curQ);
			boolean stFlag = ws.st;

			if (subop == 8 || subop == 9) {
				int inCard = (int) view.domainCards[inDom];
				int inWords = Math.max(1, (inCard + 63) >>> 6);
				long[] srcBits = ws.arena.allocLongZeroed(inWords);
				for (int i = 0; i < curLen; i++) {
					int u = curQ[i];
					srcBits[u >>> 6] |= (1L << u);
				}
				int m = rel.cooEdgeCount();
				if (subop == 8) {
					for (int i = 0; i < m; i++) {
						int u = rel.cooSrc(i);
						if (u >= 0 && u < inCard && (srcBits[u >>> 6] & (1L << u)) != 0L) {
							int v = rel.cooTgt(i);
							if (v >= 0 && v < fastOutCard
									&& (exclBits == null || (exclBits[v >>> 6] & (1L << v)) == 0L)) {
								outBits[v >>> 6] |= (1L << v);
							}
						}
					}
				} else {
					for (int i = 0; i < m; i++) {
						int v = rel.cooTgt(i);
						if (v >= 0 && v < inCard && (srcBits[v >>> 6] & (1L << v)) != 0L) {
							int u = rel.cooSrc(i);
							if (u >= 0 && u < fastOutCard
									&& (exclBits == null || (exclBits[u >>> 6] & (1L << u)) == 0L)) {
								outBits[u >>> 6] |= (1L << u);
							}
						}
					}
				}
			} else if (subop == 0) {
				if (rel.edgeIdxWidth == 4 && rel.nodeIdWidth == 4) {
					var roSeg = rel.csrRowOffsetsSeg;
					var ctSeg = rel.csrColTargetsSeg;
					if (exclBits == null) {
						for (int i = 0; i < curLen; i++) {
							int u = curQ[i];
							if (u >= 0 && u < csrRows) {
								int eStart = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
								int eEnd = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) << 2);
								for (int e = eStart; e < eEnd; e++) {
									int v = ctSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
									int wi = v >>> 6;
									long bit = 1L << v;
									if ((outBits[wi] & bit) == 0L) {
										outBits[wi] |= bit;
									}
								}
							}
						}
					} else {
						for (int i = 0; i < curLen; i++) {
							int u = curQ[i];
							if (u >= 0 && u < csrRows) {
								int eStart = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
								int eEnd = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) << 2);
								for (int e = eStart; e < eEnd; e++) {
									int v = ctSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
									int wi = v >>> 6;
									long bit = 1L << v;
									if ((outBits[wi] & bit) == 0L && (exclBits[wi] & bit) == 0L) {
										outBits[wi] |= bit;
									}
								}
							}
						}
					}
				} else {
					for (int i = 0; i < curLen; i++) {
						int u = curQ[i];
						if (u >= 0 && u < csrRows) {
							int eStart = rel.csrRowOffset(u);
							int eEnd = rel.csrRowOffset(u + 1);
							for (int e = eStart; e < eEnd; e++) {
								int v = rel.csrColTarget(e);
								int wi = v >>> 6;
								long bit = 1L << v;
								if ((outBits[wi] & bit) == 0L && (exclBits == null || (exclBits[wi] & bit) == 0L)) {
									outBits[wi] |= bit;
								}
							}
						}
					}
				}
			} else if (subop == 1) {
				if (rel.edgeIdxWidth == 4 && rel.cooSrcWidth == 4) {
					var cscRoSeg = rel.cscRowOffsetsSeg;
					var cscCtSeg = rel.cscColSourcesSeg;
					if (exclBits == null) {
						for (int i = 0; i < curLen; i++) {
							int u = curQ[i];
							if (u >= 0 && u < cscRows) {
								int eStart = cscRoSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
								int eEnd = cscRoSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) << 2);
								for (int e = eStart; e < eEnd; e++) {
									int v = cscCtSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
									int wi = v >>> 6;
									long bit = 1L << v;
									if ((outBits[wi] & bit) == 0L) {
										outBits[wi] |= bit;
									}
								}
							}
						}
					} else {
						for (int i = 0; i < curLen; i++) {
							int u = curQ[i];
							if (u >= 0 && u < cscRows) {
								int eStart = cscRoSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
								int eEnd = cscRoSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) << 2);
								for (int e = eStart; e < eEnd; e++) {
									int v = cscCtSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
									int wi = v >>> 6;
									long bit = 1L << v;
									if ((outBits[wi] & bit) == 0L && (exclBits[wi] & bit) == 0L) {
										outBits[wi] |= bit;
									}
								}
							}
						}
					}
				} else {
					for (int i = 0; i < curLen; i++) {
						int u = curQ[i];
						if (u >= 0 && u < cscRows) {
							int eStart = rel.cscRowOffset(u);
							int eEnd = rel.cscRowOffset(u + 1);
							for (int e = eStart; e < eEnd; e++) {
								int v = rel.cscColSource(e);
								int wi = v >>> 6;
								long bit = 1L << v;
								if ((outBits[wi] & bit) == 0L && (exclBits == null || (exclBits[wi] & bit) == 0L)) {
									outBits[wi] |= bit;
								}
							}
						}
					}
				}
			} else if (subop == 2) {
				for (int i = 0; i < curLen; i++) {
					int u = curQ[i];
					if (u >= 0 && u < csrRows) {
						int eStart = rel.csrRowOffset(u);
						int eEnd = rel.csrRowOffset(u + 1);
						for (int e = eStart; e < eEnd; e++) {
							int v = rel.csrColTarget(e);
							if (exclBits == null || (exclBits[v >>> 6] & (1L << v)) == 0L) {
								outBits[v >>> 6] |= (1L << v);
							}
						}
					}
					if (u >= 0 && u < cscRows) {
						int eStart = rel.cscRowOffset(u);
						int eEnd = rel.cscRowOffset(u + 1);
						for (int e = eStart; e < eEnd; e++) {
							int v = rel.cscColSource(e);
							if (exclBits == null || (exclBits[v >>> 6] & (1L << v)) == 0L) {
								outBits[v >>> 6] |= (1L << v);
							}
						}
					}
				}
			} else {
				int[] nextQ = ws.arena.allocInt(Math.max(fastOutCard, 1));
				int maxHops = (subop == 7) ? imm16Hi : Integer.MAX_VALUE;
				boolean isRev = (subop == 5 || subop == 6);
				boolean isStar = (subop == 4 || subop == 6);
				boolean converged = false;
				if (isStar) { // fixpoint_star: seed visited with src \ excl
					int w = 0;
					for (int i = 0; i < curLen; i++) {
						int u = curQ[i];
						if (exclBits == null || (exclBits[u >>> 6] & (1L << u)) == 0L) {
							long bit = 1L << u;
							if ((outBits[u >>> 6] & bit) == 0L) {
								outBits[u >>> 6] |= bit;
								curQ[w++] = u;
							}
						}
					}
					curLen = w;
				}
				boolean fast32 = (rel.edgeIdxWidth == 4 && (isRev ? rel.cooSrcWidth == 4 : rel.nodeIdWidth == 4));
				for (int hop = 0; hop < maxHops; hop++) {
					int nextLen = 0;
					if (!isRev) {
						if (fast32 && exclBits == null) {
							var roSeg = rel.csrRowOffsetsSeg;
							var ctSeg = rel.csrColTargetsSeg;
							for (int i = 0; i < curLen; i++) {
								int u = curQ[i];
								if (u >= 0 && u < csrRows) {
									int eStart = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
									int eEnd = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) << 2);
									for (int e = eStart; e < eEnd; e++) {
										int v = ctSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
										long bit = 1L << v;
										int wi = v >>> 6;
										if ((outBits[wi] & bit) == 0L) {
											outBits[wi] |= bit;
											nextQ[nextLen++] = v;
										}
									}
								}
							}
						} else if (fast32) {
							var roSeg = rel.csrRowOffsetsSeg;
							var ctSeg = rel.csrColTargetsSeg;
							for (int i = 0; i < curLen; i++) {
								int u = curQ[i];
								if (u >= 0 && u < csrRows) {
									int eStart = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
									int eEnd = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) << 2);
									for (int e = eStart; e < eEnd; e++) {
										int v = ctSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
										long bit = 1L << v;
										int wi = v >>> 6;
										if ((outBits[wi] & bit) == 0L && (exclBits[wi] & bit) == 0L) {
											outBits[wi] |= bit;
											nextQ[nextLen++] = v;
										}
									}
								}
							}
						} else {
							for (int i = 0; i < curLen; i++) {
								int u = curQ[i];
								if (u >= 0 && u < csrRows) {
									int eStart = rel.csrRowOffset(u);
									int eEnd = rel.csrRowOffset(u + 1);
									for (int e = eStart; e < eEnd; e++) {
										int v = rel.csrColTarget(e);
										long bit = 1L << v;
										int wi = v >>> 6;
										if ((outBits[wi] & bit) == 0L
												&& (exclBits == null || (exclBits[wi] & bit) == 0L)) {
											outBits[wi] |= bit;
											nextQ[nextLen++] = v;
										}
									}
								}
							}
						}
					} else {
						if (fast32 && exclBits == null) {
							var cscRoSeg = rel.cscRowOffsetsSeg;
							var cscCtSeg = rel.cscColSourcesSeg;
							for (int i = 0; i < curLen; i++) {
								int u = curQ[i];
								if (u >= 0 && u < cscRows) {
									int eStart = cscRoSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
									int eEnd = cscRoSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) << 2);
									for (int e = eStart; e < eEnd; e++) {
										int v = cscCtSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
										long bit = 1L << v;
										int wi = v >>> 6;
										if ((outBits[wi] & bit) == 0L) {
											outBits[wi] |= bit;
											nextQ[nextLen++] = v;
										}
									}
								}
							}
						} else {
							for (int i = 0; i < curLen; i++) {
								int u = curQ[i];
								if (u >= 0 && u < cscRows) {
									int eStart = rel.cscRowOffset(u);
									int eEnd = rel.cscRowOffset(u + 1);
									for (int e = eStart; e < eEnd; e++) {
										int v = rel.cscColSource(e);
										long bit = 1L << v;
										int wi = v >>> 6;
										if ((outBits[wi] & bit) == 0L
												&& (exclBits == null || (exclBits[wi] & bit) == 0L)) {
											outBits[wi] |= bit;
											nextQ[nextLen++] = v;
										}
									}
								}
							}
						}
					}
					if (nextLen == 0) {
						converged = true;
						break;
					}
					int[] tmp = curQ;
					curQ = nextQ;
					nextQ = tmp;
					curLen = nextLen;
				}
				stFlag = (subop == 7) ? converged : true;
			}
			if (fd != null && !fd.isEmpty()) {
				int ac = fd.cardinality();
				int[] aids = ws.arena.allocInt(ac);
				int an = fd.toSortedArrayInto(aids);
				for (int i = 0; i < an; i++) {
					int u = aids[i];
					outBits[u >>> 6] |= (1L << u);
				}
			}
			int card = 0;
			for (int w = 0; w < outWords; w++)
				card += Long.bitCount(outBits[w]);
			ws.fRegs[dst].initFromDenseBits(outDom, fastOutCard, outBits, outWords, card, ws.geometry, false);
			ws.setFInitialized(dst, true);
			ws.zf = (card == 0);
			ws.st = stFlag;
			ws.pc++;
			return;
		}
		ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
	}

	// --- 0x31 OP_WALK_FILTERED ---
	private static boolean evalEdgePred(ImpulseVector col, int e, int cmpOp, boolean rhsValid, long rhsBits,
			int rhsTy) {
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
			case ImpulseVector.TAG_F64 -> {
				double lv = col.f64Data[e];
				double rv = (rhsTy == SCALAR_I64 || rhsTy == SCALAR_U64)
						? (double) rhsBits
						: Double.longBitsToDouble(rhsBits);
				if (Double.isNaN(lv) || Double.isNaN(rv))
					yield false;
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
			case ImpulseVector.TAG_I64 -> {
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
			case ImpulseVector.TAG_U64 -> {
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

	private static void execWalkFiltered(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s1, int s2, int s3, int relId, int attrId) {
		ImpulseSnapshotView.RelView rel = getRel(bind, ws, relId);
		if (rel == null)
			return;
		ImpulseFrontier fSrc = getF1Bit(ws, s1);
		if (fSrc == null)
			return;
		ImpulseSnapshotView view = bind.view;

		boolean isRev = (subop >= 8);
		int cmpOp = subop & 7;
		int inDom = isRev ? rel.tgtDom : rel.srcDom;
		int outDom = isRev ? rel.srcDom : rel.tgtDom;
		if (fSrc.domainId != inDom) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}
		ImpulseVector col = rel.edgeAttrs.get(attrId);
		if (col == null) {
			ImpulseSnapshotView.OffHeapAttrColumn off = rel.offHeapEdgeAttrs.get(attrId);
			if (off != null) {
				col = ImpulsePillar4Ops.materializeOffHeapColumn(ws, off);
			}
		}
		if (col == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}

		ImpulseFrontier fe = null;
		if ((flags & FLAG_MASKED_S3) != 0) {
			fe = getF1Bit(ws, s3);
			if (fe == null)
				return;
			if (fe.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
		}
		ImpulseFrontier fd = null;
		if ((flags & FLAG_ACCUM) != 0) {
			fd = getF1Bit(ws, dst);
			if (fd == null)
				return;
			if (fd.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
		}
		if (!isRev && !rel.hasCsrSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		if (isRev && !rel.hasCscSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}

		boolean rhsValid = (cmpOp <= 5) && ws.isRValid(s2);
		long rhsBits = rhsValid ? ws.rRegs[s2] : 0L;
		int rhsTy = rhsValid ? ws.readRType(s2) : SCALAR_NULL_UNSET;

		int outCard = (int) view.domainCards[outDom];
		int outWords = Math.max(1, (outCard + 63) >>> 6);
		long[] outBits = ws.arena.allocLongZeroed(outWords);
		if (fd != null && !fd.isEmpty()) {
			int ac = fd.cardinality();
			int[] aids = ws.arena.allocInt(Math.max(1, ac));
			int an = fd.toSortedArrayInto(aids);
			for (int i = 0; i < an; i++) {
				int u = aids[i];
				outBits[u >>> 6] |= (1L << u);
			}
		}
		if (!(cmpOp <= 5 && !rhsValid)) {
			int srcCard = fSrc.cardinality();
			int[] srcIds = ws.arena.allocInt(Math.max(1, srcCard));
			int srcLen = fSrc.toSortedArrayInto(srcIds);
			if (!isRev) {
				int csrRows = rel.csrRowCount();
				for (int si = 0; si < srcLen; si++) {
					int u = srcIds[si];
					if (u >= 0 && u < csrRows) {
						int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
						for (int e = s; e < end; e++) {
							int v = rel.csrColTarget(e);
							if ((fe == null || !fe.contains(v))
									&& evalEdgePred(col, e, cmpOp, rhsValid, rhsBits, rhsTy)) {
								outBits[v >>> 6] |= (1L << v);
							}
						}
					}
				}
			} else {
				int cscRows = rel.cscRowCount();
				for (int si = 0; si < srcLen; si++) {
					int v = srcIds[si];
					if (v >= 0 && v < cscRows) {
						int s = rel.cscRowOffset(v), end = rel.cscRowOffset(v + 1);
						for (int p = s; p < end; p++) {
							int u = rel.cscColSource(p);
							int e = rel.cscEdgeId(p);
							if ((fe == null || !fe.contains(u))
									&& evalEdgePred(col, e, cmpOp, rhsValid, rhsBits, rhsTy)) {
								outBits[u >>> 6] |= (1L << u);
							}
						}
					}
				}
			}
		}

		int card = 0;
		for (int w = 0; w < outWords; w++)
			card += Long.bitCount(outBits[w]);
		ws.fRegs[dst].initFromDenseBits(outDom, outCard, outBits, outWords, card, ws.geometry, false);
		ws.setFInitialized(dst, true);
		ws.zf = (card == 0);
		ws.pc++;
	}

	// --- 0x33 OP_DEGREE ---
	private static void execDegree(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int flags,
			int dst, int s1, int s3, int relId) {
		ImpulseSnapshotView.RelView rel = getRel(bind, ws, relId);
		if (rel == null)
			return;
		ImpulseSnapshotView view = bind.view;

		switch (subop) {
			case 0 -> { // .sum_fwd_r
				ImpulseFrontier fSrc = getF1Bit(ws, s1);
				if (fSrc == null)
					return;
				if (fSrc.domainId != rel.srcDom) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
				if (!rel.hasCsrSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				int cnt = fSrc.cardinality();
				int[] ids = ws.arena.allocInt(Math.max(1, cnt));
				int nIds = fSrc.toSortedArrayInto(ids);
				long sum = 0L;
				for (int i = 0; i < nIds; i++) {
					int u = ids[i];
					sum += (rel.csrRowOffset(u + 1) - rel.csrRowOffset(u));
				}
				ws.writeR(dst, SCALAR_U64, sum);
				setScalarFlags(ws, true, sum);
				ws.pc++;
			}
			case 1 -> { // .sum_rev_r
				ImpulseFrontier fTgt = getF1Bit(ws, s1);
				if (fTgt == null)
					return;
				if (fTgt.domainId != rel.tgtDom) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
				if (!rel.hasCscSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				int cnt = fTgt.cardinality();
				int[] ids = ws.arena.allocInt(Math.max(1, cnt));
				int nIds = fTgt.toSortedArrayInto(ids);
				long sum = 0L;
				for (int i = 0; i < nIds; i++) {
					int v = ids[i];
					sum += (rel.cscRowOffset(v + 1) - rel.cscRowOffset(v));
				}
				ws.writeR(dst, SCALAR_U64, sum);
				setScalarFlags(ws, true, sum);
				ws.pc++;
			}
			case 2 -> { // .vec_fwd_v
				ImpulseFrontier fm = null;
				if ((flags & FLAG_MASKED_S3) != 0) {
					fm = getF1Bit(ws, s3);
					if (fm == null)
						return;
					if (fm.domainId != rel.srcDom) {
						ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
						return;
					}
				}
				if (!rel.hasCsrSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				int n = (int) view.domainCards[rel.srcDom];
				int words = Math.max(1, (n + 63) >>> 6);
				long[] vals = ws.arena.allocLongZeroed(Math.max(1, n));
				long[] valid = ws.arena.allocLongZeroed(words);
				for (int u = 0; u < n; u++) {
					if (fm == null || fm.contains(u)) {
						vals[u] = rel.csrRowOffset(u + 1) - rel.csrRowOffset(u);
						valid[u >>> 6] |= (1L << (u & 63));
					}
				}
				ws.vVectors[dst] = ws.arena.allocU64(vals, n, valid);
				ws.pc++;
			}
			case 3 -> { // .vec_rev_v
				ImpulseFrontier fm = null;
				if ((flags & FLAG_MASKED_S3) != 0) {
					fm = getF1Bit(ws, s3);
					if (fm == null)
						return;
					if (fm.domainId != rel.tgtDom) {
						ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
						return;
					}
				}
				if (!rel.hasCscSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				int n = (int) view.domainCards[rel.tgtDom];
				int words = Math.max(1, (n + 63) >>> 6);
				long[] vals = ws.arena.allocLongZeroed(Math.max(1, n));
				long[] valid = ws.arena.allocLongZeroed(words);
				for (int v = 0; v < n; v++) {
					if (fm == null || fm.contains(v)) {
						vals[v] = rel.cscRowOffset(v + 1) - rel.cscRowOffset(v);
						valid[v >>> 6] |= (1L << (v & 63));
					}
				}
				ws.vVectors[dst] = ws.arena.allocU64(vals, n, valid);
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
				if (!rel.hasCsrSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				int u = (int) u64;
				long deg = rel.csrRowOffset(u + 1) - rel.csrRowOffset(u);
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
				if (!rel.hasCscSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				int v = (int) v64;
				long deg = rel.cscRowOffset(v + 1) - rel.cscRowOffset(v);
				ws.writeR(dst, SCALAR_U64, deg);
				setScalarFlags(ws, true, deg);
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x34 OP_SPMV ---
	private static void execSpmv(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop, int flags,
			int dst, int s1, int s2, int s3, int relId, int attrId) {
		ImpulseSnapshotView.RelView rel = getRel(bind, ws, relId);
		if (rel == null)
			return;
		ImpulseFrontier fSrc = getF1Bit(ws, s1);
		if (fSrc == null)
			return;
		ImpulseSnapshotView view = bind.view;

		boolean isRev = (subop == 1 || subop == 3);
		int inDom = isRev ? rel.tgtDom : rel.srcDom;
		int outDom = isRev ? rel.srcDom : rel.tgtDom;
		if (fSrc.domainId != inDom) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}

		ImpulseFrontier tgtMask = null;
		if ((flags & FLAG_MASKED_S3) != 0) {
			ImpulseFrontier fm = getF1Bit(ws, s3);
			if (fm == null)
				return;
			if (fm.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			tgtMask = fm;
		}

		ImpulseVector weightCol = null;
		if (attrId != 0xFFFF) {
			weightCol = rel.edgeAttrs.get(attrId);
			if (weightCol == null) {
				ImpulseSnapshotView.OffHeapAttrColumn off = rel.offHeapEdgeAttrs.get(attrId);
				if (off != null) {
					weightCol = ImpulsePillar4Ops.materializeOffHeapColumn(ws, off);
				}
			}
			if (weightCol == null || (weightCol.elemTag != ImpulseVector.TAG_F64
					&& weightCol.elemTag != ImpulseVector.TAG_I64 && weightCol.elemTag != ImpulseVector.TAG_U64)) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
		}

		if (!isRev && !rel.hasCsrSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		if (isRev && !rel.hasCscSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}

		int nIn = (int) view.domainCards[inDom];
		int nOut = (int) view.domainCards[outDom];
		int srcCard = fSrc.cardinality();
		int[] srcIds = ws.arena.allocInt(Math.max(1, srcCard));
		int srcLen = fSrc.toSortedArrayInto(srcIds);
		int outWords = Math.max(1, (nOut + 63) >>> 6);

		if (subop == 7) { // .fwd_first_src
			long[] out = ws.arena.allocLongZeroed(nOut);
			long[] valid = ws.arena.allocLongZeroed(outWords);
			int hits = 0;
			for (int si = 0; si < srcLen; si++) {
				int u = srcIds[si];
				long u64 = u & 0xFFFFFFFFL;
				int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
				for (int e = s; e < end; e++) {
					int v = rel.csrColTarget(e);
					if (tgtMask != null && !tgtMask.contains(v))
						continue;
					boolean has = ((valid[v >>> 6] >>> (v & 63)) & 1L) != 0L;
					if (!has) {
						out[v] = u64;
						valid[v >>> 6] |= (1L << (v & 63));
						hits++;
					} else if (Long.compareUnsigned(u64, out[v]) < 0) {
						out[v] = u64;
					}
				}
			}
			ws.vVectors[dst] = ws.arena.allocU64(out, nOut, valid);
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
			ImpulseVector rv = ws.vVectors[s2];
			if (rv == null || (rv.elemTag != ImpulseVector.TAG_F64 && rv.elemTag != ImpulseVector.TAG_I64
					&& rv.elemTag != ImpulseVector.TAG_U64)) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (rv.length < nIn) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			rhsSnap = ws.arena.allocDouble(nIn);
			rhsSnapValid = ws.arena.allocBool(nIn);
			for (int i = 0; i < nIn; i++) {
				boolean ok = rv.isValidAt(i);
				rhsSnapValid[i] = ok;
				if (ok)
					rhsSnap[i] = (rv.elemTag == ImpulseVector.TAG_F64) ? rv.f64Data[i] : (double) rv.i64Data[i];
			}
		}

		if (subop == 8) { // .fwd_argmin_plus
			double[] bestCost = ws.arena.allocDouble(nOut);
			long[] bestSrc = ws.arena.allocLongZeroed(nOut);
			long[] valid = ws.arena.allocLongZeroed(outWords);
			int hits = 0;
			for (int si = 0; si < srcLen; si++) {
				int u = srcIds[si];
				if (scalarRhs ? !scalarValid : !rhsSnapValid[u])
					continue;
				double du = scalarRhs ? scalarVal : rhsSnap[u];
				long u64 = u & 0xFFFFFFFFL;
				int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
				for (int e = s; e < end; e++) {
					int v = rel.csrColTarget(e);
					if (tgtMask != null && !tgtMask.contains(v))
						continue;
					if (weightCol != null && !weightCol.isValidAt(e))
						continue;
					double w = (weightCol == null)
							? 1.0
							: (weightCol.elemTag == ImpulseVector.TAG_F64
									? weightCol.f64Data[e]
									: (double) weightCol.i64Data[e]);
					double c = du + w;
					if (Double.isNaN(c))
						continue;
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
			}
			ws.vVectors[dst] = ws.arena.allocU64(bestSrc, nOut, valid);
			ws.zf = (hits == 0);
			ws.pc++;
			return;
		}

		boolean isPlusTimes = (subop == 0 || subop == 1);
		double[] out = ws.arena.allocDoubleZeroed(nOut);
		long[] outValid = ws.arena.allocLongZeroed(outWords);
		if ((flags & FLAG_ACCUM) != 0) {
			ImpulseVector dv = ws.vVectors[dst];
			if (dv == null) {
				ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				return;
			}
			if (dv.elemTag != ImpulseVector.TAG_F64 || dv.length != nOut) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			System.arraycopy(dv.f64Data, 0, out, 0, nOut);
			for (int i = 0; i < nOut; i++) {
				if (dv.isValidAt(i))
					outValid[i >>> 6] |= (1L << (i & 63));
			}
		} else if (isPlusTimes) {
			for (int i = 0; i < nOut; i++)
				outValid[i >>> 6] |= (1L << (i & 63));
		}

		int hits = 0;
		if (!isRev) {
			for (int si = 0; si < srcLen; si++) {
				int u = srcIds[si];
				if (scalarRhs ? !scalarValid : !rhsSnapValid[u])
					continue;
				double ru = scalarRhs ? scalarVal : rhsSnap[u];
				int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
				for (int e = s; e < end; e++) {
					int v = rel.csrColTarget(e);
					if (tgtMask != null && !tgtMask.contains(v))
						continue;
					if (weightCol != null && !weightCol.isValidAt(e))
						continue;
					double w = (weightCol == null)
							? 1.0
							: (weightCol.elemTag == ImpulseVector.TAG_F64
									? weightCol.f64Data[e]
									: (double) weightCol.i64Data[e]);
					double contrib = switch (subop) {
						case 0, 5 -> ru * w;
						case 2, 4 -> ru + w;
						case 6 -> ieeeMaxF64(ru, w);
						default -> ru;
					};
					if (Double.isNaN(contrib))
						continue;
					hits++;
					boolean has = ((outValid[v >>> 6] >>> (v & 63)) & 1L) != 0L;
					switch (subop) {
						case 0 -> {
							double prev = has ? out[v] : 0.0;
							double sum = prev + contrib;
							if (Double.isNaN(sum))
								outValid[v >>> 6] &= ~(1L << (v & 63));
							else {
								out[v] = sum;
								outValid[v >>> 6] |= (1L << (v & 63));
							}
						}
						case 2, 6 -> {
							out[v] = has ? ieeeMinF64(out[v], contrib) : contrib;
							outValid[v >>> 6] |= (1L << (v & 63));
						}
						case 4, 5 -> {
							out[v] = has ? ieeeMaxF64(out[v], contrib) : contrib;
							outValid[v >>> 6] |= (1L << (v & 63));
						}
						default -> {
						}
					}
				}
			}
		} else {
			for (int si = 0; si < srcLen; si++) {
				int v = srcIds[si];
				if (scalarRhs ? !scalarValid : !rhsSnapValid[v])
					continue;
				double rv = scalarRhs ? scalarVal : rhsSnap[v];
				int s = rel.cscRowOffset(v), end = rel.cscRowOffset(v + 1);
				for (int p = s; p < end; p++) {
					int u = rel.cscColSource(p);
					if (tgtMask != null && !tgtMask.contains(u))
						continue;
					int e = rel.cscEdgeId(p);
					if (weightCol != null && !weightCol.isValidAt(e))
						continue;
					double w = (weightCol == null)
							? 1.0
							: (weightCol.elemTag == ImpulseVector.TAG_F64
									? weightCol.f64Data[e]
									: (double) weightCol.i64Data[e]);
					double contrib = (subop == 1) ? (rv * w) : (rv + w);
					if (Double.isNaN(contrib))
						continue;
					hits++;
					boolean has = ((outValid[u >>> 6] >>> (u & 63)) & 1L) != 0L;
					if (subop == 1) {
						double prev = has ? out[u] : 0.0;
						double sum = prev + contrib;
						if (Double.isNaN(sum))
							outValid[u >>> 6] &= ~(1L << (u & 63));
						else {
							out[u] = sum;
							outValid[u >>> 6] |= (1L << (u & 63));
						}
					} else {
						out[u] = has ? ieeeMinF64(out[u], contrib) : contrib;
						outValid[u >>> 6] |= (1L << (u & 63));
					}
				}
			}
		}

		ws.vVectors[dst] = ws.arena.allocF64(out, nOut, outValid);
		ws.zf = (hits == 0);
		ws.pc++;
	}

	// --- 0x35 OP_WALK_MORSEL / 0x36 OP_FILTER_MORSEL ---
	private static void execWalkMorsel(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, long[] program,
			int subop, int flags, int s1, int s3, int relId, int endOff, long[] stepsRef, long maxSteps) {
		int hdrPc = ws.pc;
		int endPc = hdrPc + endOff;
		ImpulseSnapshotView.RelView rel = getRel(bind, ws, relId);
		if (rel == null)
			return;
		if (!ws.isFInitialized(s1)) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		ImpulseFrontier fSrc = ws.fRegs[s1];
		ImpulseSnapshotView view = bind.view;

		boolean isCoo = (subop == 2 || subop == 3);
		boolean isRev = (subop == 1 || subop == 3);
		if (isCoo && !rel.hasCooSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		if (!isCoo && !isRev && !rel.hasCsrSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		if (!isCoo && isRev && !rel.hasCscSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		int inDom = isRev ? rel.tgtDom : rel.srcDom;
		int outDom = isRev ? rel.srcDom : rel.tgtDom;
		if (fSrc.domainId != inDom) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}
		if (!isCoo && !isRev && !fSrc.multiLane && (flags & FLAG_MASKED_S3) == 0
				&& canRunForwardMorselZeroAlloc(program, hdrPc, endPc)) {
			runForwardWalkMorselZeroAlloc(ws, view, rel, fSrc, program, hdrPc, endPc, outDom, stepsRef, maxSteps);
			return;
		}

		ImpulseFrontier exclFrontier = null;
		if ((flags & FLAG_MASKED_S3) != 0) {
			ImpulseFrontier fe = getF1Bit(ws, s3);
			if (fe == null)
				return;
			if (fe.domainId != outDom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			exclFrontier = fe;
		}

		MorselStreamSession sess = acquireMorselSession(ws, bind, view, program, hdrPc, endPc, outDom, false, stepsRef,
				maxSteps);
		if (isCoo) {
			if (!rel.hasCooSegments()) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			int inCard = (int) view.domainCards[inDom];
			long[] srcLanes = ws.arena.allocLongZeroed(inCard);
			if (fSrc.multiLane) {
				long[] ns = fSrc.mlSortedNodes();
				long[] ms = fSrc.mlSortedMasks();
				for (int k = 0; k < ns.length; k++) {
					int node = (int) ns[k];
					if (node >= 0 && node < inCard)
						srcLanes[node] = ms[k];
				}
			} else {
				int[] srcIds = ws.arena.allocInt(fSrc.cardinality());
				int srcCnt = fSrc.toSortedArrayInto(srcIds);
				for (int k = 0; k < srcCnt; k++) {
					int u = srcIds[k];
					if (u >= 0 && u < inCard)
						srcLanes[u] = 1L;
				}
			}
			int m = rel.cooEdgeCount();
			for (int i = 0; i < m && ws.status == IMPULSE_VM_OK; i++) {
				int u = rel.cooSrc(i);
				int v = rel.cooTgt(i);
				int eid = rel.cooEdgeId(i);
				if (!isRev) {
					long lm = (u >= 0 && u < inCard) ? srcLanes[u] : 0L;
					if (lm != 0L && (exclFrontier == null || !exclFrontier.contains(v))) {
						sess.push(u & 0xFFFFFFFFL, v & 0xFFFFFFFFL, eid & 0xFFFFFFFFL, lm);
					}
				} else {
					long lm = (v >= 0 && v < inCard) ? srcLanes[v] : 0L;
					if (lm != 0L && (exclFrontier == null || !exclFrontier.contains(u))) {
						sess.push(u & 0xFFFFFFFFL, v & 0xFFFFFFFFL, eid & 0xFFFFFFFFL, lm);
					}
				}
			}
		} else if (!isRev) {
			if (!rel.hasCsrSegments()) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			if (fSrc.multiLane) {
				long[] ns = fSrc.mlSortedNodes();
				long[] ms = fSrc.mlSortedMasks();
				for (int k = 0; k < ns.length && ws.status == IMPULSE_VM_OK; k++) {
					long lm = ms[k];
					if (lm == 0L)
						continue;
					int u = (int) ns[k];
					int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
					for (int e = s; e < end && ws.status == IMPULSE_VM_OK; e++) {
						int v = rel.csrColTarget(e);
						if (exclFrontier == null || !exclFrontier.contains(v)) {
							sess.push(u & 0xFFFFFFFFL, v & 0xFFFFFFFFL, e & 0xFFFFFFFFL, lm);
						}
					}
				}
			} else {
				int[] srcIds = ws.arena.allocInt(fSrc.cardinality());
				int srcCnt = fSrc.toSortedArrayInto(srcIds);
				for (int k = 0; k < srcCnt && ws.status == IMPULSE_VM_OK; k++) {
					int u = srcIds[k];
					int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
					for (int e = s; e < end && ws.status == IMPULSE_VM_OK; e++) {
						int v = rel.csrColTarget(e);
						if (exclFrontier == null || !exclFrontier.contains(v)) {
							sess.push(u & 0xFFFFFFFFL, v & 0xFFFFFFFFL, e & 0xFFFFFFFFL, 1L);
						}
					}
				}
			}
		} else {
			if (!rel.hasCscSegments()) {
				ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
				return;
			}
			if (fSrc.multiLane) {
				long[] ns = fSrc.mlSortedNodes();
				long[] ms = fSrc.mlSortedMasks();
				for (int k = 0; k < ns.length && ws.status == IMPULSE_VM_OK; k++) {
					long lm = ms[k];
					if (lm == 0L)
						continue;
					int v = (int) ns[k];
					int s = rel.cscRowOffset(v), end = rel.cscRowOffset(v + 1);
					for (int p = s; p < end && ws.status == IMPULSE_VM_OK; p++) {
						int u = rel.cscColSource(p);
						int e = rel.cscEdgeId(p);
						if (exclFrontier == null || !exclFrontier.contains(u)) {
							sess.push(u & 0xFFFFFFFFL, v & 0xFFFFFFFFL, e & 0xFFFFFFFFL, lm);
						}
					}
				}
			} else {
				int[] srcIds = ws.arena.allocInt(fSrc.cardinality());
				int srcCnt = fSrc.toSortedArrayInto(srcIds);
				for (int k = 0; k < srcCnt && ws.status == IMPULSE_VM_OK; k++) {
					int v = srcIds[k];
					int s = rel.cscRowOffset(v), end = rel.cscRowOffset(v + 1);
					for (int p = s; p < end && ws.status == IMPULSE_VM_OK; p++) {
						int u = rel.cscColSource(p);
						int e = rel.cscEdgeId(p);
						if (exclFrontier == null || !exclFrontier.contains(u)) {
							sess.push(u & 0xFFFFFFFFL, v & 0xFFFFFFFFL, e & 0xFFFFFFFFL, 1L);
						}
					}
				}
			}
		}
		sess.finish();
	}

	private static boolean canRunForwardMorselZeroAlloc(long[] program, int hdrPc, int endPc) {
		for (int p = hdrPc + 1; p < endPc; p++) {
			long w = program[p];
			int op = (int) (w & 0xFFL);
			int subop = (int) ((w >>> 8) & 0xFL);
			int flags = (int) ((w >>> 12) & 0xFL);
			switch (op) {
				case OP_GATHER_EDGE_ATTR -> {
					if (subop != 0 || (flags & FLAG_MASKED_S3) != 0)
						return false;
				}
				case OP_VEC_CMP -> {
					if (subop > 5 || (flags & FLAG_MASKED_S3) != 0)
						return false;
				}
				case OP_SET_CONVERT -> {
					if (subop != 2)
						return false;
				}
				case OP_MORSEL_COMMIT -> {
					if ((subop != 0 && subop != 8) || (flags & FLAG_ACCUM) != 0)
						return false;
				}
				default -> {
					return false;
				}
			}
		}
		return true;
	}

	private static void runForwardWalkMorselZeroAlloc(ImpulseWorkspace ws, ImpulseSnapshotView view,
			ImpulseSnapshotView.RelView rel, ImpulseFrontier fSrc, long[] program, int hdrPc, int endPc, int defaultDom,
			long[] stepsRef, long maxSteps) {
		int mMax = Math.max(1, ws.geometry.morselMax());
		int mWords = Math.max(1, (mMax + 63) >>> 6);

		// Pre-scan commit sinks (0..7)
		int commitMaskBits = 0;
		int countMaskBits = 0;
		long[] countAcc = ws.arena.allocLongZeroed(8);
		int[] sinkDom = ws.arena.allocInt(8);
		for (int d = 0; d < 8; d++)
			sinkDom[d] = -1;
		for (int p = hdrPc + 1; p < endPc; p++) {
			long cw = program[p];
			if ((int) (cw & 0xFFL) == OP_MORSEL_COMMIT) {
				int cSub = (int) ((cw >>> 8) & 0xFL);
				int cDst = (int) ((cw >>> 20) & 0xFL);
				if (cSub == 0) {
					int cLo = (int) ((cw >>> 32) & 0xFFFFL);
					int dom = (cLo == 0xFFFF) ? defaultDom : cLo;
					sinkDom[cDst] = dom;
					commitMaskBits |= (1 << cDst);
				} else if (cSub == 8) {
					countMaskBits |= (1 << cDst);
				}
			}
		}

		// Allocate dense bitset per committed sink (zero TreeSet/boxing)
		long[] sinkBits0 = null, sinkBits1 = null, sinkBits2 = null, sinkBits3 = null;
		long[] sinkBits4 = null, sinkBits5 = null, sinkBits6 = null, sinkBits7 = null;
		for (int d = 0; d < 8; d++) {
			if ((commitMaskBits & (1 << d)) != 0 && sinkDom[d] < view.domainCards.length) {
				int dcard = (int) view.domainCards[sinkDom[d]];
				int dw = Math.max(1, (dcard + 63) >>> 6);
				long[] b = ws.arena.allocLongZeroed(dw);
				switch (d) {
					case 0 -> sinkBits0 = b;
					case 1 -> sinkBits1 = b;
					case 2 -> sinkBits2 = b;
					case 3 -> sinkBits3 = b;
					case 4 -> sinkBits4 = b;
					case 5 -> sinkBits5 = b;
					case 6 -> sinkBits6 = b;
					case 7 -> sinkBits7 = b;
				}
			}
		}

		// Allocate reusable chunk buffers ONCE for the entire morsel loop
		long[] m0 = ws.arena.allocLong(mMax);
		long[] m1 = ws.arena.allocLong(mMax);
		long[] m2 = ws.arena.allocLong(mMax);
		long[] m3 = ws.arena.allocLong(mMax);
		long[] allValid = ws.arena.allocValidAllTrue(mMax);
		int[] mf0Ids = ws.arena.allocInt(mMax);
		for (int i = 0; i < mMax; i++) {
			m3[i] = 1L;
			mf0Ids[i] = i;
		}

		ImpulseVector vM0 = ws.arena.allocU64(m0, 0, allValid);
		ImpulseVector vM1 = ws.arena.allocU64(m1, 0, allValid);
		ImpulseVector vM2 = ws.arena.allocU64(m2, 0, allValid);
		ImpulseVector vM3 = ws.arena.allocU64(m3, 0, allValid);

		// Pre-allocate per-register scratch for morsel interior V8..V15 (eliminates
		// register aliasing, 0-alloc)
		int[] scratchFIds = ws.arena.allocInt(mMax);
		ImpulseVector[] gatherVecs = ws.arena.morselGatherVecs;
		ImpulseVector[] cmpVecs = ws.arena.morselCmpVecs;
		Arrays.fill(gatherVecs, null);
		Arrays.fill(cmpVecs, null);
		for (int p = hdrPc + 1; p < endPc; p++) {
			long pw = program[p];
			int pop = (int) (pw & 0xFFL);
			int pdst = (int) ((pw >>> 20) & 0xFL);
			if (pop == OP_GATHER_EDGE_ATTR && gatherVecs[pdst] == null) {
				gatherVecs[pdst] = ws.arena.allocF64(ws.arena.allocDouble(mMax), 0, ws.arena.allocLong(mWords));
				gatherVecs[pdst].i64Data = ws.arena.allocLong(mMax);
			} else if (pop == OP_VEC_CMP && cmpVecs[pdst] == null) {
				cmpVecs[pdst] = ws.arena.allocBool3vl(0, ws.arena.allocLong(mWords), ws.arena.allocLong(mWords));
			}
		}

		if (!rel.hasCsrSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		int srcCard = fSrc.cardinality();
		int[] srcIds = ws.arena.allocInt(srcCard);
		int srcLen = fSrc.toSortedArrayInto(srcIds);

		int srcIdx = 0;
		boolean fast32 = (rel.edgeIdxWidth == 4 && rel.nodeIdWidth == 4);
		MemorySegment roSeg = fast32 ? rel.csrRowOffsetsSeg : null;
		MemorySegment ctSeg = fast32 ? rel.csrColTargetsSeg : null;
		int edgePos = (srcLen > 0)
				? (fast32
						? roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) srcIds[0] << 2)
						: rel.csrRowOffset(srcIds[0]))
				: 0;

		while (ws.status == IMPULSE_VM_OK) {
			// Fill up to mMax edges directly from off-heap CSR (zero intermediate
			// List<long[]>)
			int len = 0;
			if (fast32) {
				while (len < mMax && srcIdx < srcLen) {
					int u = srcIds[srcIdx];
					int eEnd = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (u + 1) << 2);
					int take = Math.min(eEnd - edgePos, mMax - len);
					long u64 = u & 0xFFFFFFFFL;
					for (int k = 0; k < take; k++) {
						int e = edgePos + k;
						m0[len + k] = u64;
						m1[len + k] = ctSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2) & 0xFFFFFFFFL;
						m2[len + k] = e & 0xFFFFFFFFL;
					}
					len += take;
					edgePos += take;
					if (edgePos >= eEnd) {
						srcIdx++;
						if (srcIdx < srcLen)
							edgePos = roSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) srcIds[srcIdx] << 2);
					}
				}
			} else {
				while (len < mMax && srcIdx < srcLen) {
					int u = srcIds[srcIdx];
					int eEnd = rel.csrRowOffset(u + 1);
					while (edgePos < eEnd && len < mMax) {
						m0[len] = u & 0xFFFFFFFFL;
						m1[len] = rel.csrColTarget(edgePos) & 0xFFFFFFFFL;
						m2[len] = edgePos & 0xFFFFFFFFL;
						len++;
						edgePos++;
					}
					if (edgePos >= eEnd) {
						srcIdx++;
						if (srcIdx < srcLen)
							edgePos = rel.csrRowOffset(srcIds[srcIdx]);
					}
				}
			}
			if (len == 0)
				break;

			if (stepsRef[0] >= maxSteps) {
				ws.status = IMPULSE_VM_ERR_GAS_EXHAUSTED;
				break;
			}
			stepsRef[0]++;

			int curWords = Math.max(1, (len + 63) >>> 6);
			vM0.length = len;
			vM1.length = len;
			vM2.length = len;
			vM3.length = len;
			ws.vVectors[8] = vM0;
			ws.vVectors[9] = vM1;
			ws.vVectors[10] = vM2;
			ws.vVectors[11] = vM3;
			ws.fRegs[8].initFromValidatedIds(0xFFFF, len, mf0Ids, len, ImpulseGeometry.P0_DEFAULT, true);
			ws.setFInitialized(8, true);

			ws.pc = hdrPc + 1;
			for (int pc = hdrPc + 1; pc < endPc && ws.status == IMPULSE_VM_OK; pc++) {
				ws.pc = pc;
				long w = program[pc];
				int op = (int) (w & 0xFFL);
				int subop = (int) ((w >>> 8) & 0xFL);
				int flags = (int) ((w >>> 12) & 0xFL);
				int s1 = (int) ((w >>> 16) & 0xFL);
				int dst = (int) ((w >>> 20) & 0xFL);
				int s3 = (int) ((w >>> 24) & 0xFL);
				int s2 = (int) ((w >>> 28) & 0xFL);
				int immLo = (int) ((w >>> 32) & 0xFFFFL);
				int immHi = (int) ((w >>> 48) & 0xFFFFL);

				long mProfT0 = 0L;
				if (ImpulseWorkspace.OPCODE_PROFILE) {
					if (op >= 0x20 && op < 128) {
						mProfT0 = System.nanoTime();
					} else if (op < 128) {
						ws.opCalls[op]++;
					}
				}
				switch (op) {
					case OP_GATHER_EDGE_ATTR -> {
						execZeroAllocGatherEdgeAttr(ws, view, immLo, immHi, s1, dst, gatherVecs[dst], len, curWords);
					}
					case OP_VEC_CMP -> {
						ImpulseVector lhs = ws.vVectors[s1];
						if (lhs == null || (lhs.elemTag != ImpulseVector.TAG_F64 && lhs.elemTag != ImpulseVector.TAG_I64
								&& lhs.elemTag != ImpulseVector.TAG_U64)) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							break;
						}
						boolean scalar = (flags & FLAG_SCALAR_RHS) != 0;
						ImpulseVector vCmp = cmpVecs[dst];
						long[] scratchCmpValid = vCmp.validBits;
						long[] scratchCmpData = vCmp.boolDataBits;
						Arrays.fill(scratchCmpValid, 0, curWords, 0L);
						Arrays.fill(scratchCmpData, 0, curWords, 0L);
						ImpulseVector rv = scalar ? null : ws.vVectors[s2];
						if (lhs.elemTag == ImpulseVector.TAG_F64) {
							boolean rScOk = scalar && ws.isRValid(s2);
							double rSc = rScOk ? Double.longBitsToDouble(ws.rRegs[s2]) : Double.NaN;
							double[] lF64 = lhs.f64Data;
							for (int i = 0; i < len && i < lhs.length; i++) {
								if (!lhs.isValidAt(i))
									continue;
								boolean hasY = scalar
										? rScOk
										: (rv != null && rv.elemTag == ImpulseVector.TAG_F64 && i < rv.length
												&& rv.isValidAt(i));
								if (!hasY)
									continue;
								double x = lF64[i];
								double y = scalar ? rSc : rv.f64Data[i];
								if (Double.isNaN(x) || Double.isNaN(y))
									continue;
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
								scratchCmpValid[i >>> 6] |= bit;
								if (res)
									scratchCmpData[i >>> 6] |= bit;
							}
						} else {
							boolean isU64 = (lhs.elemTag == ImpulseVector.TAG_U64);
							boolean rScOk = scalar && ws.isRValid(s2);
							long rSc = rScOk ? ws.rRegs[s2] : 0L;
							long[] lI64 = lhs.i64Data;
							for (int i = 0; i < len && i < lhs.length; i++) {
								if (!lhs.isValidAt(i))
									continue;
								boolean hasY = scalar
										? rScOk
										: (rv != null && rv.elemTag == lhs.elemTag && i < rv.length && rv.isValidAt(i));
								if (!hasY)
									continue;
								long x = lI64[i];
								long y = scalar ? rSc : rv.i64Data[i];
								int cmp = isU64 ? Long.compareUnsigned(x, y) : Long.compare(x, y);
								boolean res = switch (subop) {
									case 0 -> cmp == 0;
									case 1 -> cmp != 0;
									case 2 -> cmp < 0;
									case 3 -> cmp <= 0;
									case 4 -> cmp > 0;
									case 5 -> cmp >= 0;
									default -> false;
								};
								long bit = 1L << (i & 63);
								scratchCmpValid[i >>> 6] |= bit;
								if (res)
									scratchCmpData[i >>> 6] |= bit;
							}
						}
						vCmp.length = len;
						ws.vVectors[dst] = vCmp;
					}
					case OP_SET_CONVERT -> {
						ImpulseVector bv = ws.vVectors[s1];
						if (bv == null || bv.elemTag != ImpulseVector.TAG_BOOL_3VL) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							break;
						}
						int cnt = 0;
						long[] bValid = bv.validBits;
						long[] bData = bv.boolDataBits;
						for (int wi = 0; wi < curWords; wi++) {
							long m = bValid[wi] & bData[wi];
							int base = wi << 6;
							while (m != 0L) {
								int idx = base + Long.numberOfTrailingZeros(m);
								if (idx < len)
									scratchFIds[cnt++] = idx;
								m &= (m - 1L);
							}
						}
						ws.fRegs[dst].initFromValidatedIds(0xFFFF, len, scratchFIds, cnt, ImpulseGeometry.P0_DEFAULT,
								true);
						ws.setFInitialized(dst, true);
					}
					case OP_MORSEL_COMMIT -> {
						boolean masked = (flags & FLAG_MASKED_S3) != 0;
						if (subop == 8) { // .count_add_r
							if (!masked) {
								countAcc[dst] += len;
							} else {
								ImpulseFrontier fm = ws.fRegs[s3];
								if (fm == null || fm.multiLane || !ws.isFInitialized(s3)) {
									ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
									break;
								}
								countAcc[dst] += fm.cardinality();
							}
							break;
						}
						ImpulseVector ids = ws.vVectors[s1];
						if (ids == null || ids.elemTag != ImpulseVector.TAG_U64) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							break;
						}
						long[] targetBits = switch (dst) {
							case 0 -> sinkBits0;
							case 1 -> sinkBits1;
							case 2 -> sinkBits2;
							case 3 -> sinkBits3;
							case 4 -> sinkBits4;
							case 5 -> sinkBits5;
							case 6 -> sinkBits6;
							case 7 -> sinkBits7;
							default -> null;
						};
						long[] idData = ids.i64Data;
						if (!masked && ids.validBits == allValid && targetBits != null) {
							int tbLen = targetBits.length;
							for (int i = 0; i < len; i++) {
								int v = (int) idData[i];
								int wi = v >>> 6;
								if (v >= 0 && wi < tbLen) {
									long bit = 1L << (v & 63);
									if ((targetBits[wi] & bit) == 0L) {
										targetBits[wi] |= bit;
									}
								}
							}
						} else {
							ImpulseFrontier fm = masked ? ws.fRegs[s3] : ws.fRegs[8];
							if (fm == null || fm.multiLane || !ws.isFInitialized(masked ? s3 : 8)) {
								ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
								break;
							}
							int nM = fm.toSortedArrayInto(scratchFIds);
							if (targetBits != null) {
								int tbLen = targetBits.length;
								for (int k = 0; k < nM; k++) {
									int i = scratchFIds[k];
									if (ids.isValidAt(i)) {
										int v = (int) idData[i];
										int wi = v >>> 6;
										if (v >= 0 && wi < tbLen) {
											long bit = 1L << (v & 63);
											if ((targetBits[wi] & bit) == 0L) {
												targetBits[wi] |= bit;
											}
										}
									}
								}
							}
						}
					}
				}
				if (ImpulseWorkspace.OPCODE_PROFILE && op >= 0x20 && op < 128) {
					ws.recordOpcode(op, System.nanoTime() - mProfT0);
				}
				if (ws.status == IMPULSE_VM_OK)
					ws.pc = pc + 1;
			}
		}

		// Clean up morsel-private registers 8..15
		for (int k = 8; k < 16; k++) {
			ws.setFInitialized(k, false);
			ws.vVectors[k] = null;
		}
		if (ws.status == IMPULSE_VM_OK) {
			for (int d = 0; d < 8; d++) {
				if ((commitMaskBits & (1 << d)) != 0 && sinkDom[d] >= view.domainCards.length) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
			}
			boolean anyNonEmpty = false;
			for (int d = 0; d < 8; d++) {
				if ((commitMaskBits & (1 << d)) != 0) {
					int dom = sinkDom[d];
					int dcard = (int) view.domainCards[dom];
					int dw = Math.max(1, (dcard + 63) >>> 6);
					long[] b = switch (d) {
						case 0 -> sinkBits0;
						case 1 -> sinkBits1;
						case 2 -> sinkBits2;
						case 3 -> sinkBits3;
						case 4 -> sinkBits4;
						case 5 -> sinkBits5;
						case 6 -> sinkBits6;
						case 7 -> sinkBits7;
						default -> null;
					};
					int card = 0;
					for (int wi = 0; wi < dw; wi++)
						card += Long.bitCount(b[wi]);
					if (card > 0)
						anyNonEmpty = true;
					ws.fRegs[d].initFromDenseBits(dom, dcard, b, dw, card, ws.geometry, false);
					ws.setFInitialized(d, true);
				}
				if ((countMaskBits & (1 << d)) != 0) {
					if (countAcc[d] != 0L)
						anyNonEmpty = true;
					ws.writeR(d, SCALAR_U64, countAcc[d]);
				}
			}
			ws.zf = !anyNonEmpty;
			ws.pc = endPc + 1;
		}
	}

	private static void execFilterMorsel(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, long[] program,
			int s1, int endOff, long[] stepsRef, long maxSteps) {
		int hdrPc = ws.pc;
		int endPc = hdrPc + endOff;
		ImpulseSnapshotView view = getView(bind, ws);
		if (view == null)
			return;
		ImpulseFrontier fSrc = getF1Bit(ws, s1);
		if (fSrc == null)
			return;

		int[] srcIds = ws.arena.allocInt(fSrc.cardinality());
		int srcCnt = fSrc.toSortedArrayInto(srcIds);
		MorselStreamSession sess = acquireMorselSession(ws, bind, view, program, hdrPc, endPc, fSrc.domainId, true,
				stepsRef, maxSteps);
		for (int k = 0; k < srcCnt && ws.status == IMPULSE_VM_OK; k++) {
			sess.push(srcIds[k] & 0xFFFFFFFFL, 0L, 0L, 1L);
		}
		sess.finish();
	}

	private static MorselStreamSession acquireMorselSession(ImpulseWorkspace ws,
			ImpulseInterpreter.SnapshotBinding bind, ImpulseSnapshotView view, long[] program, int hdrPc, int endPc,
			int defaultDom, boolean isFilter, long[] stepsRef, long maxSteps) {
		MorselStreamSession sess = (MorselStreamSession) ws.reusableMorselSession;
		if (sess == null) {
			sess = new MorselStreamSession(); // INVARIANT_ALLOW: one-time per-workspace lazy init
			ws.reusableMorselSession = sess;
		}
		sess.init(ws, bind, view, program, hdrPc, endPc, defaultDom, isFilter, stepsRef, maxSteps);
		return sess;
	}

	private static final class MorselAccSlot {
		ImpulseFrontier f;
		long[] mlDense;
		long[] mlNodes;
		long[] mlMasks;
		int mlCount;
		int mlCap;
		double[] vF64;
		boolean[] vF64Valid;
		int vF64Len;
		long[] vU64;
		boolean[] vU64Valid;
		int vU64Len;

		void reset() {
			f = null;
			mlDense = null;
			mlNodes = null;
			mlMasks = null;
			mlCount = 0;
			mlCap = 0;
			vF64 = null;
			vF64Valid = null;
			vF64Len = 0;
			vU64 = null;
			vU64Valid = null;
			vU64Len = 0;
		}

		void addMlSparse(ImpulseWorkspace ws, long node, long mask) {
			if (mlCount >= mlCap) {
				int ncap = Math.max(64, mlCap * 2);
				long[] nn = ws.arena.allocLong(ncap);
				long[] nm = ws.arena.allocLong(ncap);
				if (mlCount > 0) {
					System.arraycopy(mlNodes, 0, nn, 0, mlCount);
					System.arraycopy(mlMasks, 0, nm, 0, mlCount);
				}
				mlNodes = nn;
				mlMasks = nm;
				mlCap = ncap;
			}
			mlNodes[mlCount] = node;
			mlMasks[mlCount] = mask;
			mlCount++;
		}
	}

	private static final class MorselStreamSession {
		ImpulseWorkspace ws;
		ImpulseInterpreter.SnapshotBinding bind;
		ImpulseSnapshotView view;
		long[] program;
		int hdrPc;
		int endPc;
		boolean isFilter;
		long[] stepsRef;
		long maxSteps;
		int mMax;
		int mWords;

		final MorselAccSlot[] acc;
		long[] accRCnt;
		int[] committedFDom;
		int[] committedMlDom;
		boolean[] committedVF64;
		boolean[] committedVU64;
		boolean[] committedRCnt;

		long[] m0, m1, m2, m3;
		int[] mf0Ids;
		long[] allValid;
		ImpulseVector vM0, vM1, vM2, vM3;
		final ImpulseVector[] morselSpills;
		int[] maskScratch;
		long[] tmpVBits;
		final ImpulseVector[] scratchVecF64;
		final ImpulseVector[] scratchVecI64;
		final ImpulseVector[] scratchVecU64;
		final ImpulseVector[] scratchVecBool;
		int chunkLen = 0;

		MorselStreamSession() {
			this.acc = new MorselAccSlot[8]; // INVARIANT_ALLOW: one-time per-workspace init
			for (int d = 0; d < 8; d++)
				this.acc[d] = new MorselAccSlot(); // INVARIANT_ALLOW: one-time per-workspace init
			this.morselSpills = new ImpulseVector[16]; // INVARIANT_ALLOW: one-time per-workspace init
			this.scratchVecF64 = new ImpulseVector[8]; // INVARIANT_ALLOW: one-time per-workspace init
			this.scratchVecI64 = new ImpulseVector[8]; // INVARIANT_ALLOW: one-time per-workspace init
			this.scratchVecU64 = new ImpulseVector[8]; // INVARIANT_ALLOW: one-time per-workspace init
			this.scratchVecBool = new ImpulseVector[8]; // INVARIANT_ALLOW: one-time per-workspace init
		}

		void init(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, ImpulseSnapshotView view,
				long[] program, int hdrPc, int endPc, int defaultDom, boolean isFilter, long[] stepsRef,
				long maxSteps) {
			this.ws = ws;
			this.bind = bind;
			this.view = view;
			this.program = program;
			this.hdrPc = hdrPc;
			this.endPc = endPc;
			this.isFilter = isFilter;
			this.stepsRef = stepsRef;
			this.maxSteps = maxSteps;
			this.mMax = Math.max(1, ws.geometry.morselMax());
			this.mWords = Math.max(1, (mMax + 63) >>> 6);
			this.chunkLen = 0;

			for (int d = 0; d < 8; d++)
				this.acc[d].reset();
			Arrays.fill(this.morselSpills, null);
			this.accRCnt = ws.arena.allocLongZeroed(8);
			this.committedFDom = ws.arena.allocInt(8);
			this.committedMlDom = ws.arena.allocInt(8);
			Arrays.fill(committedFDom, 0, 8, -1);
			Arrays.fill(committedMlDom, 0, 8, -1);
			this.committedVF64 = ws.arena.allocBoolZeroed(8);
			this.committedVU64 = ws.arena.allocBoolZeroed(8);
			this.committedRCnt = ws.arena.allocBoolZeroed(8);

			boolean[] accumF = ws.arena.allocBoolZeroed(8);
			boolean[] accumMl = ws.arena.allocBoolZeroed(8);
			boolean[] accumVF64 = ws.arena.allocBoolZeroed(8);
			boolean[] accumVU64 = ws.arena.allocBoolZeroed(8);
			boolean[] accumR = ws.arena.allocBoolZeroed(8);

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
							if (cAccum)
								accumF[cDst] = true;
						}
						case 1 -> {
							committedMlDom[cDst] = dom;
							if (cAccum)
								accumMl[cDst] = true;
						}
						case 2, 3, 4 -> {
							committedVF64[cDst] = true;
							if (cAccum)
								accumVF64[cDst] = true;
						}
						case 5, 6, 7 -> {
							committedVU64[cDst] = true;
							if (cAccum)
								accumVU64[cDst] = true;
						}
						case 8 -> {
							committedRCnt[cDst] = true;
							if (cAccum)
								accumR[cDst] = true;
						}
						default -> {
						}
					}
				}
			}

			for (int d = 0; d < 8; d++) {
				MorselAccSlot slot = acc[d];
				if (committedFDom[d] >= 0) {
					int fdom = committedFDom[d];
					int fdcard = (fdom >= 0 && fdom < view.domainCards.length) ? (int) view.domainCards[fdom] : 64;
					ImpulseFrontier af = ws.arena.allocFrontier(Math.max(fdcard, 64));
					if (accumF[d] && ws.isFInitialized(d) && !ws.fRegs[d].multiLane) {
						af.copyFrom(ws.fRegs[d]);
					} else {
						af.resetEmpty(fdom, fdcard);
					}
					slot.f = af;
				}
				if (committedMlDom[d] >= 0) {
					int mdom = committedMlDom[d];
					int mdcard = (mdom >= 0 && mdom < view.domainCards.length) ? (int) view.domainCards[mdom] : 0;
					if (mdcard > 0 && mdcard <= (1 << 22)) {
						slot.mlDense = ws.arena.allocLongZeroed(mdcard);
					}
					if (accumMl[d] && ws.isFInitialized(d) && ws.fRegs[d].multiLane) {
						ImpulseFrontier fd = ws.fRegs[d];
						long[] dense = slot.mlDense;
						for (int k = 0; k < fd.mlCount; k++) {
							long u = fd.mlNodes[k];
							long lm = fd.mlMasks[k];
							if (lm == 0L)
								continue;
							if (dense != null && u >= 0L && u < mdcard) {
								dense[(int) u] |= lm;
							} else {
								slot.addMlSparse(ws, u, lm);
							}
						}
					}
				}
				ImpulseVector vd = ws.vVectors[d];
				if (committedVF64[d] && vd != null && vd.elemTag == ImpulseVector.TAG_F64) {
					int vlen = vd.length;
					slot.vF64Len = vlen;
					if (accumVF64[d]) {
						slot.vF64 = ws.arena.allocDouble(vlen);
						System.arraycopy(vd.f64Data, 0, slot.vF64, 0, vlen);
						slot.vF64Valid = ws.arena.allocBool(vlen);
						for (int i = 0; i < vlen; i++)
							slot.vF64Valid[i] = vd.isValidAt(i);
					} else {
						slot.vF64 = ws.arena.allocDoubleZeroed(vlen);
						slot.vF64Valid = null; // Lazy all-valid for non-accum scatter_add
					}
				}
				if (committedVU64[d] && vd != null
						&& (vd.elemTag == ImpulseVector.TAG_U64 || vd.elemTag == ImpulseVector.TAG_I64)) {
					int vlen = vd.length;
					slot.vU64Len = vlen;
					if (accumVU64[d]) {
						slot.vU64 = ws.arena.allocLong(vlen);
						System.arraycopy(vd.i64Data, 0, slot.vU64, 0, vlen);
						slot.vU64Valid = ws.arena.allocBool(vlen);
						for (int i = 0; i < vlen; i++)
							slot.vU64Valid[i] = vd.isValidAt(i);
					} else {
						slot.vU64 = ws.arena.allocLongZeroed(vlen);
						slot.vU64Valid = ws.arena.allocBoolZeroed(vlen);
					}
				}
				if (committedRCnt[d]) {
					accRCnt[d] = (accumR[d] && ws.isRValid(d)) ? ws.rRegs[d] : 0L;
				}
			}

			// Non-accum subops 3,4 (min/max) start invalid; subop 2 (scatter_add) keeps
			// vF64Valid==null
			for (int p = hdrPc + 1; p < endPc; p++) {
				long cw = program[p];
				if ((int) (cw & 0xFFL) == OP_MORSEL_COMMIT) {
					int cSub = (int) ((cw >>> 8) & 0xFL);
					int cFlags = (int) ((cw >>> 12) & 0xFL);
					int cDst = (int) ((cw >>> 20) & 0xFL);
					boolean cAccum = (cFlags & FLAG_ACCUM) != 0;
					MorselAccSlot slot = acc[cDst];
					if ((cSub == 3 || cSub == 4) && !cAccum && slot.vF64 != null && slot.vF64Valid == null) {
						slot.vF64Valid = ws.arena.allocBoolZeroed(slot.vF64Len);
					}
				}
			}

			this.m0 = ws.arena.allocLong(mMax);
			this.m1 = ws.arena.allocLong(mMax);
			this.m2 = ws.arena.allocLong(mMax);
			this.m3 = ws.arena.allocLong(mMax);
			this.mf0Ids = ws.arena.allocInt(mMax);
			for (int i = 0; i < mMax; i++)
				mf0Ids[i] = i;
			this.allValid = ws.arena.allocValidAllTrue(mMax);
			this.vM0 = ws.arena.allocU64(m0, 0, allValid);
			this.vM1 = ws.arena.allocU64(m1, 0, allValid);
			this.vM2 = ws.arena.allocU64(m2, 0, allValid);
			this.vM3 = ws.arena.allocU64(m3, 0, allValid);
			this.maskScratch = ws.arena.allocInt(mMax);
			this.tmpVBits = ws.arena.allocLong(mWords);
			for (int r = 0; r < 8; r++) {
				double[] sf = ws.arena.allocDouble(mMax);
				long[] si = ws.arena.allocLong(mMax);
				long[] sv = ws.arena.allocLong(mWords);
				long[] sb = ws.arena.allocLong(mWords);
				scratchVecF64[r] = ws.arena.allocF64(sf, mMax, sv);
				scratchVecI64[r] = ws.arena.allocI64(si, mMax, sv);
				scratchVecU64[r] = ws.arena.allocU64(si, mMax, sv);
				scratchVecBool[r] = ws.arena.allocBool3vl(mMax, sv, sb);
			}
		}

		void push(long u, long v, long eid, long lm) {
			int idx = chunkLen++;
			m0[idx] = u;
			m1[idx] = v;
			m2[idx] = eid;
			m3[idx] = lm;
			if (chunkLen == mMax) {
				flush();
			}
		}

		void flush() {
			int len = chunkLen;
			if (len == 0 || ws.status != IMPULSE_VM_OK) {
				chunkLen = 0;
				return;
			}
			chunkLen = 0;
			if (stepsRef[0] >= maxSteps) {
				ws.status = IMPULSE_VM_ERR_GAS_EXHAUSTED;
				return;
			}
			stepsRef[0]++;
			ws.fRegs[8].initFromValidatedIds(0xFFFF, len, mf0Ids, len, ImpulseGeometry.P0_DEFAULT, true);
			ws.setFInitialized(8, true);
			vM0.length = len;
			ws.vVectors[8] = vM0;
			if (!isFilter) {
				vM1.length = len;
				vM2.length = len;
				ws.vVectors[9] = vM1;
				ws.vVectors[10] = vM2;
			} else {
				ws.vVectors[9] = null;
				ws.vVectors[10] = null;
			}
			vM3.length = len;
			ws.vVectors[11] = vM3;
			Arrays.fill(morselSpills, null);

			ws.pc = hdrPc + 1;
			while (ws.pc < endPc && ws.status == IMPULSE_VM_OK) {
				execMorselBodyInst(this, len, program[ws.pc]);
			}
		}

		void finish() {
			if (chunkLen > 0 && ws.status == IMPULSE_VM_OK) {
				flush();
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
					MorselAccSlot slot = acc[d];
					if (committedFDom[d] >= 0) {
						int dom = committedFDom[d];
						int dcard = (int) view.domainCards[dom];
						ImpulseFrontier af = slot.f;
						int card = af.cardinality();
						if (card > 0)
							anyNonEmpty = true;
						int[] ids = ws.arena.allocInt(Math.max(1, card));
						int nIds = af.toSortedArrayInto(ids);
						ws.fRegs[d].initFromValidatedIds(dom, dcard, ids, nIds, ws.geometry, false);
						ws.setFInitialized(d, true);
					}
					if (committedMlDom[d] >= 0) {
						int dom = committedMlDom[d];
						int dcard = (int) view.domainCards[dom];
						long[] dense = slot.mlDense;
						if (dense != null && slot.mlCount == 0) {
							int cnt = 0;
							for (int i = 0; i < dcard; i++) {
								if (dense[i] != 0L)
									cnt++;
							}
							if (cnt > 0)
								anyNonEmpty = true;
							long[] ns = ws.arena.allocLong(cnt);
							long[] ms = ws.arena.allocLong(cnt);
							int idx = 0;
							for (int i = 0; i < dcard; i++) {
								long m = dense[i];
								if (m != 0L) {
									ns[idx] = i;
									ms[idx] = m;
									idx++;
								}
							}
							ws.fRegs[d].initMultiLane(dom, dcard, 64, 64, ns, ms, cnt, ws.geometry);
							ws.setFInitialized(d, true);
						} else {
							if (dense != null) {
								for (int i = 0; i < dcard; i++) {
									if (dense[i] != 0L) {
										slot.addMlSparse(ws, i, dense[i]);
									}
								}
							}
							int cnt = mlSortAndCoalesce(slot.mlNodes, slot.mlMasks, slot.mlCount);
							if (cnt > 0)
								anyNonEmpty = true;
							ws.fRegs[d].initMultiLane(dom, dcard, 64, 64, slot.mlNodes, slot.mlMasks, cnt, ws.geometry);
							ws.setFInitialized(d, true);
						}
					}
					if (committedVF64[d] && slot.vF64 != null) {
						int n = slot.vF64Len;
						int vw = Math.max(1, (n + 63) >>> 6);
						long[] vBits = ws.arena.allocLongZeroed(vw);
						if (slot.vF64Valid == null) {
							Arrays.fill(vBits, 0, vw, -1L);
							if ((n & 63) != 0)
								vBits[vw - 1] = (1L << (n & 63)) - 1L;
							if (!anyNonEmpty) {
								double[] arr = slot.vF64;
								for (int i = 0; i < n; i++) {
									if (arr[i] != 0.0) {
										anyNonEmpty = true;
										break;
									}
								}
							}
						} else {
							for (int i = 0; i < n; i++) {
								if (slot.vF64Valid[i]) {
									vBits[i >>> 6] |= (1L << (i & 63));
									if (slot.vF64[i] != 0.0)
										anyNonEmpty = true;
								}
							}
						}
						ws.vVectors[d] = ws.arena.allocF64(slot.vF64, n, vBits);
					}
					if (committedVU64[d] && slot.vU64 != null) {
						int n = slot.vU64Len;
						int vw = Math.max(1, (n + 63) >>> 6);
						long[] vBits = ws.arena.allocLongZeroed(vw);
						for (int i = 0; i < n; i++) {
							if (slot.vU64Valid[i]) {
								vBits[i >>> 6] |= (1L << (i & 63));
								anyNonEmpty = true;
							}
						}
						ws.vVectors[d] = ws.arena.allocU64(slot.vU64, n, vBits);
					}
					if (committedRCnt[d]) {
						if (accRCnt[d] != 0L)
							anyNonEmpty = true;
						ws.writeR(d, SCALAR_U64, accRCnt[d]);
					}
				}
				ws.zf = !anyNonEmpty;
				ws.pc = endPc + 1;
			}
		}
	}

	private static void execMorselScatterF64(ImpulseWorkspace ws, MorselStreamSession sess, int subop, int dst, int s1,
			int s2, boolean scalar, int[] commitIds, int commitCnt) {
		ImpulseVector ids = ws.vVectors[s1];
		MorselAccSlot slot = sess.acc[dst];
		double[] dstArr = slot.vF64;
		if (ids == null || ids.elemTag != ImpulseVector.TAG_U64 || dstArr == null) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		ImpulseVector vals = scalar ? null : ws.vVectors[s2];
		boolean[] dstVal = slot.vF64Valid;
		int dstLen = slot.vF64Len;
		if (subop == 2 && !scalar && dstVal == null && vals != null && vals.elemTag == ImpulseVector.TAG_F64
				&& ids.validBits == sess.allValid) {
			long[] idArr = ids.i64Data;
			double[] valArr = vals.f64Data;
			long[] valBits = vals.validBits;
			for (int k = 0; k < commitCnt; k++) {
				int i = commitIds[k];
				if (valBits != null && ((valBits[i >>> 6] >>> (i & 63)) & 1L) == 0L)
					continue;
				double v = valArr[i];
				if (v != v)
					continue;
				long tgt64 = idArr[i];
				int ti = (int) tgt64;
				if (tgt64 < 0L || ti < 0 || ti >= dstLen) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				double sum = dstArr[ti] + v;
				if (sum == sum) {
					dstArr[ti] = sum;
				} else {
					dstVal = ws.arena.allocBool(dstLen);
					Arrays.fill(dstVal, 0, dstLen, true);
					slot.vF64Valid = dstVal;
					dstVal[ti] = false;
					for (int k2 = k + 1; k2 < commitCnt; k2++) {
						int i2 = commitIds[k2];
						if (valBits != null && ((valBits[i2 >>> 6] >>> (i2 & 63)) & 1L) == 0L)
							continue;
						double v2 = valArr[i2];
						if (v2 != v2)
							continue;
						long t64 = idArr[i2];
						int ti2 = (int) t64;
						if (t64 < 0L || ti2 < 0 || ti2 >= dstLen) {
							ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
							return;
						}
						double prev2 = dstVal[ti2] ? dstArr[ti2] : 0.0;
						double s2v = prev2 + v2;
						if (s2v != s2v)
							dstVal[ti2] = false;
						else {
							dstArr[ti2] = s2v;
							dstVal[ti2] = true;
						}
					}
					break;
				}
			}
			ws.pc++;
			return;
		}
		if (dstVal == null) {
			dstVal = ws.arena.allocBool(dstLen);
			Arrays.fill(dstVal, 0, dstLen, true);
			slot.vF64Valid = dstVal;
		}
		boolean rOk = scalar && ws.isRValid(s2);
		double rVal = rOk ? Double.longBitsToDouble(ws.rRegs[s2]) : Double.NaN;
		for (int k = 0; k < commitCnt; k++) {
			int i = commitIds[k];
			if (!ids.isValidAt(i))
				continue;
			boolean hasV = scalar
					? (rOk && !Double.isNaN(rVal))
					: (vals != null && vals.elemTag == ImpulseVector.TAG_F64 && vals.isValidAt(i));
			if (!hasV)
				continue;
			double v = scalar ? rVal : vals.f64Data[i];
			long tgt64 = ids.i64Data[i];
			int ti = (int) tgt64;
			if (tgt64 < 0L || ti < 0 || ti >= dstLen) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			if (subop == 2) {
				double prev = dstVal[ti] ? dstArr[ti] : 0.0;
				double sum = prev + v;
				if (Double.isNaN(sum)) {
					dstVal[ti] = false;
				} else {
					dstArr[ti] = sum;
					dstVal[ti] = true;
				}
			} else if (subop == 3) {
				dstArr[ti] = dstVal[ti] ? ieeeMinF64(dstArr[ti], v) : v;
				dstVal[ti] = true;
			} else {
				dstArr[ti] = dstVal[ti] ? ieeeMaxF64(dstArr[ti], v) : v;
				dstVal[ti] = true;
			}
		}
		ws.pc++;
	}

	private static void execMorselBodyInst(MorselStreamSession sess, int morselLen, long w) {
		ImpulseWorkspace ws = sess.ws;
		ImpulseInterpreter.SnapshotBinding bind = sess.bind;
		ImpulseSnapshotView view = sess.view;
		MorselAccSlot[] acc = sess.acc;
		long[] accRCnt = sess.accRCnt;
		ImpulseVector[] morselSpills = sess.morselSpills;
		int[] maskScratch = sess.maskScratch;

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

		boolean useMask = false;
		int maskCnt = 0;
		if (masked) {
			ImpulseFrontier fm = getF1Bit(ws, s3);
			if (fm == null)
				return;
			if (fm.tier != ImpulseFrontier.TIER_ALL_SPAN) {
				useMask = true;
				maskCnt = fm.toSortedArrayInto(maskScratch);
			}
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
					if (!ws.isFInitialized(s1)) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					ws.fRegs[dst].copyFrom(ws.fRegs[s1]);
					ws.setFInitialized(dst, true);
					ws.pc++;
				} else if (subop == 2) {
					if (ws.vVectors[s1] == null) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					ws.vVectors[dst] = ws.vVectors[s1];
					ws.pc++;
				} else {
					ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
				}
			}
			case OP_SWAP -> {
				if (subop == 1) {
					boolean id = ws.isFInitialized(dst), is = ws.isFInitialized(s1);
					ImpulseFrontier t = ws.fRegs[dst];
					ws.fRegs[dst] = ws.fRegs[s1];
					ws.fRegs[s1] = t;
					ws.setFInitialized(dst, is);
					ws.setFInitialized(s1, id);
					ws.pc++;
				} else if (subop == 2) {
					ImpulseVector t = ws.vVectors[dst];
					ws.vVectors[dst] = ws.vVectors[s1];
					ws.vVectors[s1] = t;
					ws.pc++;
				} else {
					ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
				}
			}
			case OP_SET_OP -> {
				ImpulseFrontier fa = getF1Bit(ws, s1);
				ImpulseFrontier fb = getF1Bit(ws, s2);
				if (fa == null || fb == null)
					return;
				int words = Math.max(1, (morselLen + 63) >>> 6);
				long[] ba = ws.arena.allocLongZeroed(words);
				long[] bb = ws.arena.allocLongZeroed(words);
				long[] out = ws.arena.allocLongZeroed(words);
				int na = fa.toSortedArrayInto(maskScratch);
				for (int i = 0; i < na; i++) {
					int u = maskScratch[i];
					if (u >= 0 && u < morselLen)
						ba[u >>> 6] |= (1L << u);
				}
				int nb = fb.toSortedArrayInto(maskScratch);
				for (int i = 0; i < nb; i++) {
					int u = maskScratch[i];
					if (u >= 0 && u < morselLen)
						bb[u >>> 6] |= (1L << u);
				}
				int card = 0;
				for (int wi = 0; wi < words; wi++) {
					long v = switch (subop) {
						case 0 -> ba[wi] | bb[wi];
						case 1 -> ba[wi] & bb[wi];
						case 2 -> ba[wi] & ~bb[wi];
						case 3 -> ba[wi] ^ bb[wi];
						default -> 0L;
					};
					out[wi] = v;
					card += Long.bitCount(v);
				}
				ws.fRegs[dst].initFromDenseBits(0xFFFF, morselLen, out, words, card, ImpulseGeometry.P0_DEFAULT, true);
				ws.setFInitialized(dst, true);
				ws.pc++;
			}
			case OP_SET_COMPLEMENT -> {
				ImpulseFrontier fa = getF1Bit(ws, s1);
				if (fa == null)
					return;
				int words = Math.max(1, (morselLen + 63) >>> 6);
				long[] ba = ws.arena.allocLongZeroed(words);
				int na = fa.toSortedArrayInto(maskScratch);
				for (int i = 0; i < na; i++) {
					int u = maskScratch[i];
					if (u >= 0 && u < morselLen)
						ba[u >>> 6] |= (1L << u);
				}
				int card = 0;
				for (int wi = 0; wi < words; wi++) {
					long v = ~ba[wi];
					if (wi == words - 1 && (morselLen & 63) != 0) {
						v &= (1L << (morselLen & 63)) - 1L;
					} else if (morselLen == 0) {
						v = 0L;
					}
					ba[wi] = v;
					card += Long.bitCount(v);
				}
				ws.fRegs[dst].initFromDenseBits(0xFFFF, morselLen, ba, words, card, ImpulseGeometry.P0_DEFAULT, true);
				ws.setFInitialized(dst, true);
				ws.pc++;
			}
			case OP_GATHER_NODE_ATTR, OP_GATHER_EDGE_ATTR -> {
				execMorselGatherAttr(ws, bind, view, sess, op, dst, s1, imm16Lo, imm16Hi, morselLen, useMask,
						maskScratch, maskCnt, w);
			}
			case OP_COALESCE -> {
				ImpulseVector lhs = ws.vVectors[s1];
				if (lhs == null) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int words = Math.max(1, (morselLen + 63) >>> 6);
				long[] vBits = ws.arena.allocLongZeroed(words);
				int iterCnt = useMask ? maskCnt : morselLen;
				if (lhs.elemTag == ImpulseVector.TAG_F64) {
					double[] out = ws.arena.allocDoubleZeroed(morselLen);
					ImpulseVector rv = scalar ? null : ws.vVectors[s2];
					boolean rScOk = scalar && ws.isRValid(s2) && !Double.isNaN(Double.longBitsToDouble(ws.rRegs[s2]));
					double rSc = rScOk ? Double.longBitsToDouble(ws.rRegs[s2]) : 0.0;
					for (int k = 0; k < iterCnt; k++) {
						int i = useMask ? maskScratch[k] : k;
						if (i >= morselLen)
							continue;
						if (lhs.isValidAt(i)) {
							out[i] = lhs.f64Data[i];
							vBits[i >>> 6] |= (1L << (i & 63));
						} else if (scalar) {
							if (rScOk) {
								out[i] = rSc;
								vBits[i >>> 6] |= (1L << (i & 63));
							}
						} else if (rv != null && rv.elemTag == ImpulseVector.TAG_F64 && i < rv.length
								&& rv.isValidAt(i)) {
							out[i] = rv.f64Data[i];
							vBits[i >>> 6] |= (1L << (i & 63));
						}
					}
					ws.vVectors[dst] = ws.arena.allocF64(out, morselLen, vBits);
					ws.pc++;
				} else if (lhs.elemTag == ImpulseVector.TAG_I64 || lhs.elemTag == ImpulseVector.TAG_U64) {
					long[] out = ws.arena.allocLongZeroed(morselLen);
					ImpulseVector rv = scalar ? null : ws.vVectors[s2];
					for (int k = 0; k < iterCnt; k++) {
						int i = useMask ? maskScratch[k] : k;
						if (i >= morselLen)
							continue;
						if (lhs.isValidAt(i)) {
							out[i] = lhs.i64Data[i];
							vBits[i >>> 6] |= (1L << (i & 63));
						} else if (scalar) {
							if (ws.isRValid(s2)) {
								out[i] = ws.rRegs[s2];
								vBits[i >>> 6] |= (1L << (i & 63));
							}
						} else if (rv != null && rv.elemTag == lhs.elemTag && i < rv.length && rv.isValidAt(i)) {
							out[i] = rv.i64Data[i];
							vBits[i >>> 6] |= (1L << (i & 63));
						}
					}
					ws.vVectors[dst] = (lhs.elemTag == ImpulseVector.TAG_I64)
							? ws.arena.allocI64(out, morselLen, vBits)
							: ws.arena.allocU64(out, morselLen, vBits);
					ws.pc++;
				} else {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
				}
			}
			case OP_VEC_MATH_ARITH -> {
				ImpulseVector lhs = ws.vVectors[s1];
				if (lhs == null || lhs.elemTag != ImpulseVector.TAG_F64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int words = Math.max(1, (morselLen + 63) >>> 6);
				boolean reuseDst = (dst >= 8 && dst < 16);
				double[] out = reuseDst ? sess.scratchVecF64[dst - 8].f64Data : ws.arena.allocDoubleZeroed(morselLen);
				long[] vBits = sess.tmpVBits;
				ImpulseVector rv = scalar ? null : ws.vVectors[s2];
				boolean rScOk = scalar && ws.isRValid(s2) && !Double.isNaN(Double.longBitsToDouble(ws.rRegs[s2]));
				double rSc = rScOk ? Double.longBitsToDouble(ws.rRegs[s2]) : 0.0;
				if (!useMask && lhs.length >= morselLen && (subop <= 3 || subop == 8 || subop == 9 || subop == 10)
						&& (subop >= 8 || scalar
								|| (rv != null && rv.elemTag == ImpulseVector.TAG_F64 && rv.length >= morselLen))) {
					if (subop >= 8) {
						ImpulseSimdKernels.f64ArithUnaryValid(subop, lhs.f64Data, lhs.validBits, out, vBits, morselLen);
					} else if (scalar) {
						if (rScOk) {
							ImpulseSimdKernels.f64ArithScalarValid(subop, lhs.f64Data, rSc, lhs.validBits, out, vBits,
									morselLen);
						} else {
							Arrays.fill(vBits, 0, words, 0L);
						}
					} else {
						ImpulseSimdKernels.f64ArithVecValid(subop, lhs.f64Data, rv.f64Data, lhs.validBits, rv.validBits,
								out, vBits, morselLen);
					}
				} else {
					Arrays.fill(vBits, 0, words, 0L);
					int iterCnt = useMask ? maskCnt : morselLen;
					for (int k = 0; k < iterCnt; k++) {
						int i = useMask ? maskScratch[k] : k;
						if (i >= morselLen || i >= lhs.length || !lhs.isValidAt(i))
							continue;
						double x = lhs.f64Data[i];
						double r;
						if (subop == 8)
							r = -x;
						else if (subop == 9)
							r = Math.abs(x);
						else if (subop == 10) {
							if (x < 0.0)
								continue;
							r = Math.sqrt(x);
						} else {
							boolean hasY = scalar
									? rScOk
									: (rv != null && rv.elemTag == ImpulseVector.TAG_F64 && i < rv.length
											&& rv.isValidAt(i));
							if (!hasY)
								continue;
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
				}
				if (reuseDst) {
					ImpulseVector sv = sess.scratchVecF64[dst - 8];
					System.arraycopy(vBits, 0, sv.validBits, 0, words);
					sv.length = morselLen;
					ws.vVectors[dst] = sv;
				} else {
					long[] vb = ws.arena.allocLong(words);
					System.arraycopy(vBits, 0, vb, 0, words);
					ws.vVectors[dst] = ws.arena.allocF64(out, morselLen, vb);
				}
				ws.pc++;
			}
			case OP_VEC_CMP -> {
				ImpulseVector lhs = ws.vVectors[s1];
				if (lhs != null && (lhs.elemTag == ImpulseVector.TAG_I64 || lhs.elemTag == ImpulseVector.TAG_U64)) {
					ImpulsePillar4Ops.step(ws, bind, w);
					return;
				}
				if (lhs == null || lhs.elemTag != ImpulseVector.TAG_F64) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				int words = Math.max(1, (morselLen + 63) >>> 6);
				boolean reuseDst = (dst >= 8 && dst < 16);
				long[] valid = sess.tmpVBits;
				long[] data = reuseDst ? sess.scratchVecBool[dst - 8].boolDataBits : ws.arena.allocLongZeroed(words);
				ImpulseVector rv = scalar ? null : ws.vVectors[s2];
				boolean rScOk = scalar && ws.isRValid(s2);
				double rSc = rScOk ? Double.longBitsToDouble(ws.rRegs[s2]) : Double.NaN;
				if (!useMask && subop <= 5 && lhs.length >= morselLen
						&& (scalar || (rv != null && rv.elemTag == ImpulseVector.TAG_F64 && rv.length >= morselLen))) {
					ImpulseSimdKernels.f64CmpWords(subop, lhs.f64Data, rv != null ? rv.f64Data : null, scalar, rSc,
							rScOk, lhs.validBits, rv != null ? rv.validBits : null, valid, data, morselLen);
				} else {
					Arrays.fill(valid, 0, words, 0L);
					Arrays.fill(data, 0, words, 0L);
					int iterCnt = useMask ? maskCnt : morselLen;
					for (int k = 0; k < iterCnt; k++) {
						int i = useMask ? maskScratch[k] : k;
						if (i >= morselLen || !lhs.isValidAt(i))
							continue;
						boolean hasY = scalar
								? rScOk
								: (rv != null && rv.elemTag == ImpulseVector.TAG_F64 && i < rv.length
										&& rv.isValidAt(i));
						if (!hasY)
							continue;
						double x = lhs.f64Data[i];
						double y = scalar ? rSc : rv.f64Data[i];
						if (Double.isNaN(x) || Double.isNaN(y))
							continue;
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
						if (res)
							data[i >>> 6] |= bit;
					}
				}
				if (reuseDst) {
					ImpulseVector sv = sess.scratchVecBool[dst - 8];
					System.arraycopy(valid, 0, sv.validBits, 0, words);
					sv.length = morselLen;
					ws.vVectors[dst] = sv;
				} else {
					long[] vb = ws.arena.allocLong(words);
					System.arraycopy(valid, 0, vb, 0, words);
					ws.vVectors[dst] = ws.arena.allocBool3vl(morselLen, vb, data);
				}
				ws.pc++;
			}
			case OP_SET_CONVERT -> {
				if (subop == 2) { // .b2f
					ImpulseVector bv = ws.vVectors[s1];
					if (bv == null || bv.elemTag != ImpulseVector.TAG_BOOL_3VL) {
						ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
						return;
					}
					int cnt = ImpulseSimdKernels.b2fExtractWords(bv.validBits, bv.boolDataBits, bv.length, maskScratch);
					ws.fRegs[dst].initFromValidatedIds(0xFFFF, morselLen, maskScratch, cnt, ImpulseGeometry.P0_DEFAULT,
							true);
					ws.setFInitialized(dst, true);
					ws.pc++;
				} else {
					ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
				}
			}
			case OP_MORSEL_COMMIT -> {
				int commitCnt;
				int[] commitIds;
				if (masked) {
					if (useMask) {
						commitIds = maskScratch;
						commitCnt = maskCnt;
					} else {
						commitIds = sess.mf0Ids;
						commitCnt = morselLen;
					}
				} else if (ws.isFInitialized(8) && !ws.fRegs[8].multiLane) {
					commitIds = maskScratch;
					commitCnt = ws.fRegs[8].toSortedArrayInto(maskScratch);
				} else {
					commitIds = maskScratch;
					commitCnt = 0;
				}
				switch (subop) {
					case 0 -> { // .yield_frontier
						ImpulseVector ids = ws.vVectors[s1];
						if (ids == null || ids.elemTag != ImpulseVector.TAG_U64) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							return;
						}
						ImpulseFrontier af = acc[dst].f;
						for (int k = 0; k < commitCnt; k++) {
							int i = commitIds[k];
							if (ids.isValidAt(i))
								af.add((int) ids.i64Data[i]);
						}
						ws.pc++;
					}
					case 1 -> { // .yield_lane_or
						ImpulseVector ids = ws.vVectors[s1];
						ImpulseVector lms = scalar ? null : ws.vVectors[s2];
						if (ids == null || ids.elemTag != ImpulseVector.TAG_U64 || (!scalar && (lms == null
								|| (lms.elemTag != ImpulseVector.TAG_U64 && lms.elemTag != ImpulseVector.TAG_I64)))) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							return;
						}
						boolean rOk = scalar && ws.isRValid(s2);
						long rVal = rOk ? ws.rRegs[s2] : 0L;
						MorselAccSlot slot = acc[dst];
						long[] dense = slot.mlDense;
						int mdom = sess.committedMlDom[dst];
						int dlen = (dense != null && mdom >= 0 && mdom < view.domainCards.length)
								? (int) view.domainCards[mdom]
								: 0;
						if (dense != null && !scalar && commitIds == null && ids.validBits == null
								&& lms.validBits == null && ids.length >= commitCnt && lms.length >= commitCnt) {
							long[] idArr = ids.i64Data;
							long[] lmArr = lms.i64Data;
							for (int i = 0; i < commitCnt; i++) {
								long lm = lmArr[i];
								if (lm != 0L) {
									long tgt = idArr[i];
									if (tgt >= 0L && tgt < dlen) {
										dense[(int) tgt] |= lm;
									} else {
										slot.addMlSparse(ws, tgt, lm);
									}
								}
							}
						} else {
							for (int k = 0; k < commitCnt; k++) {
								int i = (commitIds != null) ? commitIds[k] : k;
								if (!ids.isValidAt(i))
									continue;
								boolean hasLm = scalar ? rOk : lms.isValidAt(i);
								if (!hasLm)
									continue;
								long lm = scalar ? rVal : lms.i64Data[i];
								if (lm != 0L) {
									long tgt = ids.i64Data[i];
									if (dense != null && tgt >= 0L && tgt < dlen) {
										dense[(int) tgt] |= lm;
									} else {
										slot.addMlSparse(ws, tgt, lm);
									}
								}
							}
						}
						ws.pc++;
					}
					case 2, 3, 4 -> { // .scatter_add / min / max
						execMorselScatterF64(ws, sess, subop, dst, s1, s2, scalar, commitIds, commitCnt);
					}
					case 5, 6, 7 -> { // .scatter_min_src / argmin / argmax
						ImpulseVector ids = ws.vVectors[s1];
						MorselAccSlot slot = acc[dst];
						if (ids == null || ids.elemTag != ImpulseVector.TAG_U64 || slot.vU64 == null) {
							ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
							return;
						}
						ImpulseVector vals = scalar ? null : ws.vVectors[s2];
						long[] dstArr = slot.vU64;
						boolean[] dstVal = slot.vU64Valid;
						int dstLen = slot.vU64Len;
						for (int k = 0; k < commitCnt; k++) {
							int i = commitIds[k];
							if (!ids.isValidAt(i))
								continue;
							boolean hasV = scalar
									? ws.isRValid(s2)
									: (vals != null && (vals.elemTag == ImpulseVector.TAG_U64
											|| vals.elemTag == ImpulseVector.TAG_I64) && vals.isValidAt(i));
							if (!hasV)
								continue;
							long v = scalar ? ws.rRegs[s2] : vals.i64Data[i];
							long tgt64 = ids.i64Data[i];
							int ti = (int) tgt64;
							if (tgt64 < 0L || ti < 0 || ti >= dstLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							if (!dstVal[ti]) {
								dstArr[ti] = v;
								dstVal[ti] = true;
							} else if (subop == 5 || subop == 6) {
								if (Long.compareUnsigned(v, dstArr[ti]) < 0)
									dstArr[ti] = v;
							} else {
								if (Long.compareUnsigned(v, dstArr[ti]) > 0)
									dstArr[ti] = v;
							}
						}
						ws.pc++;
					}
					case 8 -> { // .count_add_r
						accRCnt[dst] += commitCnt;
						ws.pc++;
					}
					default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
				}
			}
			case OP_GATHER_VEC, OP_VEC_3VL_OP, OP_VEC_STR_PRED, OP_VEC_MATH_EXP_LOG, OP_VEC_MATH_TRIG_HYP,
					OP_VEC_MATH_ACT, OP_VEC_INT_BITWISE, OP_VEC_TERNARY, OP_VEC_BLEND -> {
				ImpulsePillar4Ops.step(ws, bind, w);
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x39 OP_SAMPLE_NEIGHBORS ---
	private static void execZeroAllocGatherEdgeAttr(ImpulseWorkspace ws, ImpulseSnapshotView view, int relId,
			int attrIdx, int s1, int dst, ImpulseVector vGather, int len, int curWords) {
		if (relId >= view.relations.length || view.relations[relId] == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		ImpulseSnapshotView.RelView gr = view.relations[relId];
		ImpulseVector col = gr.edgeAttrs.get(attrIdx);
		ImpulseSnapshotView.OffHeapAttrColumn offCol = (col == null) ? gr.offHeapEdgeAttrs.get(attrIdx) : null;
		ImpulseVector idxV = ws.vVectors[s1];
		if (col == null && offCol == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		if (idxV == null || idxV.elemTag != ImpulseVector.TAG_U64) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		long[] scratchValid = vGather.validBits;
		long[] idxData = idxV.i64Data;
		int colLen = (col != null) ? col.length : offCol.count();
		if (offCol != null && !offCol.nullable() && idxV.validBits == null) {
			int base = offCol.typeCode() & 0x7F;
			MemorySegment seg = offCol.dataSeg();
			Arrays.fill(scratchValid, 0, curWords, -1L);
			if ((len & 63) != 0)
				scratchValid[curWords - 1] = (1L << (len & 63)) - 1L;
			if (base == 6 || base == 7 || base == 10) {
				double[] scratchF64 = vGather.f64Data;
				if (base == 7 || base == 10) {
					for (int i = 0; i < len; i++) {
						long e64 = idxData[i];
						if (e64 < 0L || e64 >= colLen) {
							ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
							return;
						}
						double d = seg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, e64 << 3);
						if (Double.isNaN(d)) {
							scratchF64[i] = 0.0;
							scratchValid[i >>> 6] &= ~(1L << (i & 63));
						} else {
							scratchF64[i] = d;
						}
					}
				} else {
					for (int i = 0; i < len; i++) {
						long e64 = idxData[i];
						if (e64 < 0L || e64 >= colLen) {
							ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
							return;
						}
						float f = seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, e64 << 2);
						if (Float.isNaN(f)) {
							scratchF64[i] = 0.0;
							scratchValid[i >>> 6] &= ~(1L << (i & 63));
						} else {
							scratchF64[i] = f;
						}
					}
				}
				vGather.elemTag = ImpulseVector.TAG_F64;
				vGather.length = len;
				ws.vVectors[dst] = vGather;
				return;
			} else if ((base >= 1 && base <= 4) || base == 8 || base == 9) {
				long[] scratchI64 = vGather.i64Data;
				switch (base) {
					case 1 -> {
						for (int i = 0; i < len; i++) {
							long e64 = idxData[i];
							if (e64 < 0L || e64 >= colLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							scratchI64[i] = seg.get(ValueLayout.JAVA_BYTE, e64);
						}
					}
					case 2 -> {
						for (int i = 0; i < len; i++) {
							long e64 = idxData[i];
							if (e64 < 0L || e64 >= colLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							scratchI64[i] = seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, e64 << 1);
						}
					}
					case 3 -> {
						for (int i = 0; i < len; i++) {
							long e64 = idxData[i];
							if (e64 < 0L || e64 >= colLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							scratchI64[i] = seg.get(ValueLayout.JAVA_INT_UNALIGNED, e64 << 2);
						}
					}
					default -> {
						for (int i = 0; i < len; i++) {
							long e64 = idxData[i];
							if (e64 < 0L || e64 >= colLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							scratchI64[i] = seg.get(ValueLayout.JAVA_LONG_UNALIGNED, e64 << 3);
						}
					}
				}
				vGather.elemTag = (base <= 4) ? ImpulseVector.TAG_I64 : ImpulseVector.TAG_U64;
				vGather.length = len;
				ws.vVectors[dst] = vGather;
				return;
			}
		}
		Arrays.fill(scratchValid, 0, curWords, 0L);
		int tag = (col != null) ? col.elemTag : offCol.elemTag();
		if (tag == ImpulseVector.TAG_F64) {
			double[] scratchF64 = vGather.f64Data;
			for (int i = 0; i < len; i++) {
				if (!idxV.isValidAt(i))
					continue;
				long e64 = idxData[i];
				if (e64 < 0L || e64 >= colLen) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				int e = (int) e64;
				boolean ok = (col != null) ? col.isValidAt(e) : offCol.isValidAt(e);
				if (ok) {
					scratchF64[i] = (col != null) ? col.f64Data[e] : offCol.readF64(e);
					scratchValid[i >>> 6] |= (1L << (i & 63));
				}
			}
			vGather.elemTag = ImpulseVector.TAG_F64;
		} else if (tag == ImpulseVector.TAG_I64 || tag == ImpulseVector.TAG_U64) {
			long[] scratchI64 = vGather.i64Data;
			for (int i = 0; i < len; i++) {
				if (!idxV.isValidAt(i))
					continue;
				long e64 = idxData[i];
				if (e64 < 0L || e64 >= colLen) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				int e = (int) e64;
				boolean ok = (col != null) ? col.isValidAt(e) : offCol.isValidAt(e);
				if (ok) {
					scratchI64[i] = (col != null) ? col.i64Data[e] : offCol.readI64(e);
					scratchValid[i >>> 6] |= (1L << (i & 63));
				}
			}
			vGather.elemTag = tag;
		} else {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		vGather.length = len;
		ws.vVectors[dst] = vGather;
	}

	private static void execMorselGatherAttr(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind,
			ImpulseSnapshotView view, MorselStreamSession sess, int op, int dst, int s1, int imm16Lo, int imm16Hi,
			int morselLen, boolean useMask, int[] maskScratch, int maskCnt, long w) {
		ImpulseVector idxVec = ws.vVectors[s1];
		if (idxVec == null || idxVec.elemTag != ImpulseVector.TAG_U64) {
			ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
			return;
		}
		if (idxVec.length < morselLen) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		ImpulseVector col;
		ImpulseSnapshotView.OffHeapAttrColumn offCol = null;
		if (op == OP_GATHER_NODE_ATTR) {
			col = view.getNodeAttr(imm16Lo, imm16Hi);
			if (col == null)
				offCol = view.getOffHeapNodeAttr(imm16Lo, imm16Hi);
		} else {
			if (imm16Lo >= view.relations.length) {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
			col = view.relations[imm16Lo].edgeAttrs.get(imm16Hi);
			if (col == null)
				offCol = view.relations[imm16Lo].offHeapEdgeAttrs.get(imm16Hi);
		}
		if (col == null && offCol == null) {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		int colLen = (col != null) ? col.length : offCol.count();
		int words = Math.max(1, (morselLen + 63) >>> 6);
		boolean reuseDst = (dst >= 8 && dst < 16);

		// Fast single-pass non-nullable unmasked off-heap gather (F32/F64 & I8..U64)
		if (!useMask && offCol != null && !offCol.nullable() && idxVec.validBits == sess.allValid) {
			int base = offCol.typeCode() & 0x7F;
			long[] idxs = idxVec.i64Data;
			MemorySegment seg = offCol.dataSeg();
			if (base == 6 || base == 7 || base == 10) {
				double[] out = reuseDst ? sess.scratchVecF64[dst - 8].f64Data : ws.arena.allocDoubleZeroed(morselLen);
				long[] vBits = reuseDst ? sess.scratchVecF64[dst - 8].validBits : ws.arena.allocLong(words);
				Arrays.fill(vBits, 0, words, -1L);
				if ((morselLen & 63) != 0)
					vBits[words - 1] = (1L << (morselLen & 63)) - 1L;
				if (base == 7 || base == 10) {
					for (int i = 0; i < morselLen; i++) {
						long id = idxs[i];
						if (id < 0 || id >= colLen) {
							ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
							return;
						}
						double d = seg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, id << 3);
						if (Double.isNaN(d)) {
							out[i] = 0.0;
							vBits[i >>> 6] &= ~(1L << (i & 63));
						} else {
							out[i] = d;
						}
					}
				} else {
					for (int i = 0; i < morselLen; i++) {
						long id = idxs[i];
						if (id < 0 || id >= colLen) {
							ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
							return;
						}
						float f = seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, id << 2);
						if (Float.isNaN(f)) {
							out[i] = 0.0;
							vBits[i >>> 6] &= ~(1L << (i & 63));
						} else {
							out[i] = f;
						}
					}
				}
				if (reuseDst) {
					ImpulseVector sv = sess.scratchVecF64[dst - 8];
					sv.length = morselLen;
					ws.vVectors[dst] = sv;
				} else {
					ws.vVectors[dst] = ws.arena.allocF64(out, morselLen, vBits);
				}
				ws.pc++;
				return;
			} else if ((base >= 1 && base <= 4) || base == 8 || base == 9) {
				long[] out = reuseDst ? sess.scratchVecI64[dst - 8].i64Data : ws.arena.allocLongZeroed(morselLen);
				long[] vBits = reuseDst ? sess.scratchVecI64[dst - 8].validBits : ws.arena.allocLong(words);
				Arrays.fill(vBits, 0, words, -1L);
				if ((morselLen & 63) != 0)
					vBits[words - 1] = (1L << (morselLen & 63)) - 1L;
				switch (base) {
					case 1 -> {
						for (int i = 0; i < morselLen; i++) {
							long id = idxs[i];
							if (id < 0 || id >= colLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							out[i] = seg.get(ValueLayout.JAVA_BYTE, id);
						}
					}
					case 2 -> {
						for (int i = 0; i < morselLen; i++) {
							long id = idxs[i];
							if (id < 0 || id >= colLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							out[i] = seg.get(ValueLayout.JAVA_SHORT_UNALIGNED, id << 1);
						}
					}
					case 3 -> {
						for (int i = 0; i < morselLen; i++) {
							long id = idxs[i];
							if (id < 0 || id >= colLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							out[i] = seg.get(ValueLayout.JAVA_INT_UNALIGNED, id << 2);
						}
					}
					default -> {
						for (int i = 0; i < morselLen; i++) {
							long id = idxs[i];
							if (id < 0 || id >= colLen) {
								ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
								return;
							}
							out[i] = seg.get(ValueLayout.JAVA_LONG_UNALIGNED, id << 3);
						}
					}
				}
				if (reuseDst) {
					ImpulseVector sv = (base <= 4) ? sess.scratchVecI64[dst - 8] : sess.scratchVecU64[dst - 8];
					sv.length = morselLen;
					ws.vVectors[dst] = sv;
				} else {
					ws.vVectors[dst] = (base <= 4)
							? ws.arena.allocI64(out, morselLen, vBits)
							: ws.arena.allocU64(out, morselLen, vBits);
				}
				ws.pc++;
				return;
			}
		}

		if (useMask) {
			for (int k = 0; k < maskCnt; k++) {
				int i = maskScratch[k];
				if (i < morselLen && idxVec.isValidAt(i)) {
					long id = idxVec.i64Data[i];
					if (id < 0 || id >= colLen) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
				}
			}
		} else {
			for (int i = 0; i < morselLen; i++) {
				if (idxVec.isValidAt(i)) {
					long id = idxVec.i64Data[i];
					if (id < 0 || id >= colLen) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
				}
			}
		}
		long[] vBits = reuseDst ? sess.scratchVecF64[dst - 8].validBits : ws.arena.allocLongZeroed(words);
		if (reuseDst)
			Arrays.fill(vBits, 0, words, 0L);
		if (offCol != null) {
			int base = offCol.typeCode() & 0x7F;
			if (base == 6 || base == 7 || base == 10) {
				double[] out = reuseDst ? sess.scratchVecF64[dst - 8].f64Data : ws.arena.allocDoubleZeroed(morselLen);
				if (useMask) {
					for (int k = 0; k < maskCnt; k++) {
						int i = maskScratch[k];
						if (i < morselLen && idxVec.isValidAt(i)) {
							int id = (int) idxVec.i64Data[i];
							if (offCol.isValidAt(id)) {
								out[i] = offCol.readF64(id);
								vBits[i >>> 6] |= (1L << (i & 63));
							}
						}
					}
				} else {
					for (int i = 0; i < morselLen; i++) {
						if (idxVec.isValidAt(i)) {
							int id = (int) idxVec.i64Data[i];
							if (offCol.isValidAt(id)) {
								out[i] = offCol.readF64(id);
								vBits[i >>> 6] |= (1L << (i & 63));
							}
						}
					}
				}
				if (reuseDst) {
					ImpulseVector sv = sess.scratchVecF64[dst - 8];
					sv.length = morselLen;
					ws.vVectors[dst] = sv;
				} else {
					ws.vVectors[dst] = ws.arena.allocF64(out, morselLen, vBits);
				}
			} else if ((base >= 1 && base <= 4) || base == 8 || base == 9) {
				long[] out = reuseDst ? sess.scratchVecI64[dst - 8].i64Data : ws.arena.allocLongZeroed(morselLen);
				if (useMask) {
					for (int k = 0; k < maskCnt; k++) {
						int i = maskScratch[k];
						if (i < morselLen && idxVec.isValidAt(i)) {
							int id = (int) idxVec.i64Data[i];
							if (offCol.isValidAt(id)) {
								out[i] = offCol.readI64(id);
								vBits[i >>> 6] |= (1L << (i & 63));
							}
						}
					}
				} else {
					for (int i = 0; i < morselLen; i++) {
						if (idxVec.isValidAt(i)) {
							int id = (int) idxVec.i64Data[i];
							if (offCol.isValidAt(id)) {
								out[i] = offCol.readI64(id);
								vBits[i >>> 6] |= (1L << (i & 63));
							}
						}
					}
				}
				if (reuseDst) {
					ImpulseVector sv = (base <= 4) ? sess.scratchVecI64[dst - 8] : sess.scratchVecU64[dst - 8];
					sv.length = morselLen;
					ws.vVectors[dst] = sv;
				} else {
					ws.vVectors[dst] = (base <= 4)
							? ws.arena.allocI64(out, morselLen, vBits)
							: ws.arena.allocU64(out, morselLen, vBits);
				}
			} else if (base == 11) {
				ImpulsePillar4Ops.step(ws, bind, w);
				return;
			} else {
				ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
				return;
			}
		} else if (col.elemTag == ImpulseVector.TAG_F64) {
			double[] out = reuseDst ? sess.scratchVecF64[dst - 8].f64Data : ws.arena.allocDoubleZeroed(morselLen);
			if (useMask) {
				for (int k = 0; k < maskCnt; k++) {
					int i = maskScratch[k];
					if (i < morselLen && idxVec.isValidAt(i)) {
						int id = (int) idxVec.i64Data[i];
						if (col.isValidAt(id)) {
							out[i] = col.f64Data[id];
							vBits[i >>> 6] |= (1L << (i & 63));
						}
					}
				}
			} else {
				for (int i = 0; i < morselLen; i++) {
					if (idxVec.isValidAt(i)) {
						int id = (int) idxVec.i64Data[i];
						if (col.isValidAt(id)) {
							out[i] = col.f64Data[id];
							vBits[i >>> 6] |= (1L << (i & 63));
						}
					}
				}
			}
			if (reuseDst) {
				ImpulseVector sv = sess.scratchVecF64[dst - 8];
				sv.length = morselLen;
				ws.vVectors[dst] = sv;
			} else {
				ws.vVectors[dst] = ws.arena.allocF64(out, morselLen, vBits);
			}
		} else if (col.elemTag == ImpulseVector.TAG_I64 || col.elemTag == ImpulseVector.TAG_U64) {
			long[] out = reuseDst ? sess.scratchVecI64[dst - 8].i64Data : ws.arena.allocLongZeroed(morselLen);
			if (useMask) {
				for (int k = 0; k < maskCnt; k++) {
					int i = maskScratch[k];
					if (i < morselLen && idxVec.isValidAt(i)) {
						int id = (int) idxVec.i64Data[i];
						if (col.isValidAt(id)) {
							out[i] = col.i64Data[id];
							vBits[i >>> 6] |= (1L << (i & 63));
						}
					}
				}
			} else {
				for (int i = 0; i < morselLen; i++) {
					if (idxVec.isValidAt(i)) {
						int id = (int) idxVec.i64Data[i];
						if (col.isValidAt(id)) {
							out[i] = col.i64Data[id];
							vBits[i >>> 6] |= (1L << (i & 63));
						}
					}
				}
			}
			if (reuseDst) {
				ImpulseVector sv = (col.elemTag == ImpulseVector.TAG_I64)
						? sess.scratchVecI64[dst - 8]
						: sess.scratchVecU64[dst - 8];
				sv.length = morselLen;
				ws.vVectors[dst] = sv;
			} else {
				ws.vVectors[dst] = (col.elemTag == ImpulseVector.TAG_I64)
						? ws.arena.allocI64(out, morselLen, vBits)
						: ws.arena.allocU64(out, morselLen, vBits);
			}
		} else if (col.elemTag == ImpulseVector.TAG_STR) {
			ImpulsePillar4Ops.step(ws, bind, w);
			return;
		} else {
			ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
			return;
		}
		ws.pc++;
	}

	private static long splitMixSample(long seed, long u, long j) {
		long z = seed ^ (u * 0x9E3779B97F4A7C15L) ^ (j * 0xBF58476D1CE4E5B9L);
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private static void execSampleNeighbors(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int dst, int s1, int s2, int relId, int k) {
		ImpulseSnapshotView.RelView rel = getRel(bind, ws, relId);
		if (rel == null)
			return;
		if (!ws.isRValid(s2)) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}
		long seed = ws.rRegs[s2];
		ImpulseSnapshotView view = bind.view;

		switch (subop) {
			case 0 -> { // .frontier_wor
				ImpulseFrontier fSrc = getF1Bit(ws, s1);
				if (fSrc == null)
					return;
				if (fSrc.domainId != rel.srcDom) {
					ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
					return;
				}
				if (k <= 0) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				if (!rel.hasCsrSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				int tgtCard = (int) view.domainCards[rel.tgtDom];
				int tgtWords = Math.max(1, (tgtCard + 63) >>> 6);
				long[] outBits = ws.arena.allocLongZeroed(tgtWords);
				int srcCard = fSrc.cardinality();
				int[] srcIds = ws.arena.allocInt(Math.max(1, srcCard));
				int srcLen = fSrc.toSortedArrayInto(srcIds);
				// Epoch-stamped open-addressed hash table for Floyd's algorithm without HashSet
				int htCap = 16;
				while (htCap < k * 2)
					htCap <<= 1;
				int htMask = htCap - 1;
				int[] htKeys = ws.arena.allocInt(htCap);
				int[] htEpoch = ws.arena.allocIntZeroed(htCap);
				int curEpoch = 0;
				for (int si = 0; si < srcLen; si++) {
					int u = srcIds[si];
					int lo = rel.csrRowOffset(u), hi = rel.csrRowOffset(u + 1);
					int deg = hi - lo;
					if (deg <= k) {
						for (int e = lo; e < hi; e++) {
							int v = rel.csrColTarget(e);
							outBits[v >>> 6] |= (1L << v);
						}
					} else {
						curEpoch++;
						if (curEpoch == 0) {
							Arrays.fill(htEpoch, 0, htCap, 0);
							curEpoch = 1;
						}
						for (int t = deg - k; t < deg; t++) {
							long r = splitMixSample(seed, u & 0xFFFFFFFFL, t);
							int p = (int) Long.remainderUnsigned(r, t + 1L);
							int idx = (p * 0x9E3779B9) & htMask;
							boolean found = false;
							while (htEpoch[idx] == curEpoch) {
								if (htKeys[idx] == p) {
									found = true;
									break;
								}
								idx = (idx + 1) & htMask;
							}
							int pick = found ? t : p;
							int ins = (pick * 0x9E3779B9) & htMask;
							while (htEpoch[ins] == curEpoch) {
								if (htKeys[ins] == pick)
									break;
								ins = (ins + 1) & htMask;
							}
							htEpoch[ins] = curEpoch;
							htKeys[ins] = pick;
							int v = rel.csrColTarget(lo + pick);
							outBits[v >>> 6] |= (1L << v);
						}
					}
				}
				int card = 0;
				for (int wi = 0; wi < tgtWords; wi++)
					card += Long.bitCount(outBits[wi]);
				ws.fRegs[dst].initFromDenseBits(rel.tgtDom, tgtCard, outBits, tgtWords, card, ws.geometry, false);
				ws.setFInitialized(dst, true);
				ws.zf = (card == 0);
				ws.pc++;
			}
			case 1 -> { // .vec_wr
				ImpulseVector seeds = ws.vVectors[s1];
				if (seeds == null
						|| (seeds.elemTag != ImpulseVector.TAG_U64 && seeds.elemTag != ImpulseVector.TAG_I64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (k <= 0) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				if (!rel.hasCsrSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				int n = seeds.length;
				int total = n * k;
				long[] out = ws.arena.allocLongZeroed(total);
				long[] valid = ws.arena.allocLongZeroed(Math.max(1, (total + 63) >>> 6));
				boolean anyValid = false;
				long dcard = view.domainCards[rel.srcDom];
				for (int i = 0; i < n; i++) {
					if (!seeds.isValidAt(i))
						continue;
					long u64 = seeds.i64Data[i];
					if (u64 < 0L || Long.compareUnsigned(u64, dcard) >= 0) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					int u = (int) u64;
					int lo = rel.csrRowOffset(u), hi = rel.csrRowOffset(u + 1);
					int deg = hi - lo;
					if (deg > 0) {
						for (int j = 0; j < k; j++) {
							long r = splitMixSample(seed, u64, j);
							int idx = (int) Long.remainderUnsigned(r, deg);
							int pos = i * k + j;
							out[pos] = rel.csrColTarget(lo + idx) & 0xFFFFFFFFL;
							valid[pos >>> 6] |= (1L << (pos & 63));
							anyValid = true;
						}
					}
				}
				ws.vVectors[dst] = ws.arena.allocU64(out, total, valid);
				ws.zf = !anyValid;
				ws.pc++;
			}
			case 2, 3 -> { // .rw_step_fwd / .rw_step_stay
				ImpulseVector walkers = ws.vVectors[s1];
				if (walkers == null
						|| (walkers.elemTag != ImpulseVector.TAG_U64 && walkers.elemTag != ImpulseVector.TAG_I64)) {
					ws.status = IMPULSE_VM_ERR_TYPE_MISMATCH;
					return;
				}
				if (!rel.hasCsrSegments()) {
					ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
					return;
				}
				boolean stay = (subop == 3);
				int n = walkers.length;
				long[] out = ws.arena.allocLongZeroed(n);
				long[] valid = ws.arena.allocLongZeroed(Math.max(1, (n + 63) >>> 6));
				boolean anyValid = false;
				long dcard = view.domainCards[rel.srcDom];
				for (int i = 0; i < n; i++) {
					if (!walkers.isValidAt(i))
						continue;
					long u64 = walkers.i64Data[i];
					if (u64 < 0L || Long.compareUnsigned(u64, dcard) >= 0) {
						ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
						return;
					}
					int u = (int) u64;
					int lo = rel.csrRowOffset(u), hi = rel.csrRowOffset(u + 1);
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
						out[i] = rel.csrColTarget(lo + idx) & 0xFFFFFFFFL;
						valid[i >>> 6] |= (1L << (i & 63));
						anyValid = true;
					}
				}
				ws.vVectors[dst] = ws.arena.allocU64(out, n, valid);
				ws.zf = !anyValid;
				ws.pc++;
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}

	// --- 0x3A OP_HAS_TOPOLOGY ---
	private static void execHasTopology(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int dst, int lo, int hi) {
		ImpulseSnapshotView view = getView(bind, ws);
		if (view == null)
			return;
		long val;
		switch (subop) {
			case 0 ->
				val = (lo < view.relations.length && view.relations[lo] != null && view.relations[lo].hasCsr) ? 1L : 0L;
			case 1 ->
				val = (lo < view.relations.length && view.relations[lo] != null && view.relations[lo].hasCsc) ? 1L : 0L;
			case 2 ->
				val = (lo < view.relations.length && view.relations[lo] != null && view.relations[lo].hasCoo) ? 1L : 0L;
			case 3 -> val = (lo < view.relations.length && view.relations[lo] != null && view.relations[lo].hasDense)
					? 1L
					: 0L;
			case 4 -> val = view.hasKeyCatalog(lo) ? 1L : 0L;
			case 5 -> val = view.hasNodeAttr(lo, hi) ? 1L : 0L;
			case 6 -> val = (lo < view.relations.length && view.relations[lo] != null
					&& (view.relations[lo].edgeAttrs.containsKey(hi)
							|| view.relations[lo].offHeapEdgeAttrs.containsKey(hi))) ? 1L : 0L;
			case 7 -> val = view.hasIndex(lo, hi) ? 1L : 0L;
			case 8 -> {
				if (lo >= view.domainCards.length) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				val = view.domainCards[lo];
			}
			case 9 -> {
				if (lo >= view.relations.length || view.relations[lo] == null) {
					ws.status = IMPULSE_VM_ERR_OUT_OF_BOUNDS;
					return;
				}
				val = view.relations[lo].edgeCount();
			}
			default -> {
				ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
				return;
			}
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

	private static void execGraphKernel(ImpulseWorkspace ws, ImpulseInterpreter.SnapshotBinding bind, int subop,
			int flags, int dst, int s3, int relId, int imm16Hi) {
		ImpulseSnapshotView.RelView rel = getRel(bind, ws, relId);
		if (rel == null)
			return;
		if (rel.srcDom != rel.tgtDom) {
			ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
			return;
		}
		ImpulseSnapshotView view = bind.view;
		int dom = rel.srcDom;
		int n = (int) view.domainCards[dom];

		ImpulseFrontier maskFrontier = null;
		if ((flags & FLAG_MASKED_S3) != 0) {
			ImpulseFrontier fm = getF1Bit(ws, s3);
			if (fm == null)
				return;
			if (fm.domainId != dom) {
				ws.status = IMPULSE_VM_ERR_DOMAIN_MISMATCH;
				return;
			}
			maskFrontier = fm;
		}

		if (!rel.hasCsrSegments()) {
			ws.status = IMPULSE_VM_ERR_INVALID_OPERAND;
			return;
		}

		// Build deduplicated simple undirected adjacency in flat arena arrays (zero
		// heap collections)
		int[] adjOff = ws.arena.allocIntZeroed(n + 1);
		for (int u = 0; u < n; u++) {
			if (maskFrontier != null && !maskFrontier.contains(u))
				continue;
			int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
			for (int e = s; e < end; e++) {
				int v = rel.csrColTarget(e);
				if (v != u && v >= 0 && v < n && (maskFrontier == null || maskFrontier.contains(v))) {
					adjOff[u + 1]++;
					adjOff[v + 1]++;
				}
			}
		}
		for (int u = 0; u < n; u++) {
			adjOff[u + 1] += adjOff[u];
		}
		int totalAdj = adjOff[n];
		int[] adjNbrs = ws.arena.allocInt(Math.max(1, totalAdj));
		int[] writePos = ws.arena.allocInt(Math.max(1, n));
		System.arraycopy(adjOff, 0, writePos, 0, n);
		for (int u = 0; u < n; u++) {
			if (maskFrontier != null && !maskFrontier.contains(u))
				continue;
			int s = rel.csrRowOffset(u), end = rel.csrRowOffset(u + 1);
			for (int e = s; e < end; e++) {
				int v = rel.csrColTarget(e);
				if (v != u && v >= 0 && v < n && (maskFrontier == null || maskFrontier.contains(v))) {
					adjNbrs[writePos[u]++] = v;
					adjNbrs[writePos[v]++] = u;
				}
			}
		}
		int[] adjEnd = ws.arena.allocInt(Math.max(1, n));
		for (int u = 0; u < n; u++) {
			int lo = adjOff[u], hi = adjOff[u + 1];
			if (hi - lo > 1) {
				Arrays.sort(adjNbrs, lo, hi);
				int w = lo + 1;
				for (int i = lo + 1; i < hi; i++) {
					if (adjNbrs[i] != adjNbrs[w - 1])
						adjNbrs[w++] = adjNbrs[i];
				}
				adjEnd[u] = w;
			} else {
				adjEnd[u] = hi;
			}
		}

		int words = Math.max(1, (n + 63) >>> 6);
		switch (subop) {
			case 0 -> { // .cc_afforest
				int[] parent = ws.arena.allocInt(Math.max(1, n));
				for (int i = 0; i < n; i++)
					parent[i] = i;
				for (int u = 0; u < n; u++) {
					if (maskFrontier != null && !maskFrontier.contains(u))
						continue;
					for (int i = adjOff[u]; i < adjEnd[u]; i++) {
						int v = adjNbrs[i];
						int ra = ufFind(parent, u);
						int rb = ufFind(parent, v);
						if (ra != rb) {
							if (ra < rb)
								parent[rb] = ra;
							else
								parent[ra] = rb;
						}
					}
				}
				long[] out = ws.arena.allocLongZeroed(n);
				long[] valid = ws.arena.allocLongZeroed(words);
				boolean any = false;
				for (int u = 0; u < n; u++) {
					if (maskFrontier == null || maskFrontier.contains(u)) {
						out[u] = ufFind(parent, u);
						valid[u >>> 6] |= (1L << (u & 63));
						any = true;
					}
				}
				ws.vVectors[dst] = ws.arena.allocU64(out, n, valid);
				ws.zf = !any;
				ws.pc++;
			}
			case 1, 2 -> { // .kcore_nums / .kcore_frontier
				int[] deg = ws.arena.allocInt(Math.max(1, n));
				int[] core = ws.arena.allocIntZeroed(Math.max(1, n));
				boolean[] removed = ws.arena.allocBoolZeroed(Math.max(1, n));
				int remaining = 0;
				for (int u = 0; u < n; u++) {
					if (maskFrontier != null && !maskFrontier.contains(u)) {
						deg[u] = -1;
						removed[u] = true;
					} else {
						deg[u] = adjEnd[u] - adjOff[u];
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
					if (minD > curK)
						curK = minD;
					removed[minU] = true;
					core[minU] = curK;
					remaining--;
					for (int i = adjOff[minU]; i < adjEnd[minU]; i++) {
						int v = adjNbrs[i];
						if (!removed[v] && deg[v] > curK)
							deg[v]--;
					}
				}
				if (subop == 1) {
					long[] out = ws.arena.allocLongZeroed(n);
					long[] valid = ws.arena.allocLongZeroed(words);
					boolean anyPos = false;
					for (int u = 0; u < n; u++) {
						if (maskFrontier == null || maskFrontier.contains(u)) {
							out[u] = core[u];
							valid[u >>> 6] |= (1L << (u & 63));
							if (core[u] > 0)
								anyPos = true;
						}
					}
					ws.vVectors[dst] = ws.arena.allocU64(out, n, valid);
					ws.zf = !anyPos;
					ws.pc++;
				} else {
					int k = imm16Hi;
					int[] buf = ws.arena.allocInt(Math.max(1, n));
					int cnt = 0;
					for (int u = 0; u < n; u++) {
						if ((maskFrontier == null || maskFrontier.contains(u)) && core[u] >= k) {
							buf[cnt++] = u;
						}
					}
					ws.fRegs[dst].initFromValidatedIds(dom, n, buf, cnt, ws.geometry, true);
					ws.setFInitialized(dst, true);
					ws.zf = (cnt == 0);
					ws.pc++;
				}
			}
			case 3, 4 -> { // .tri_count_total / .tri_count_vec
				long[] triU = ws.arena.allocLongZeroed(n);
				long totalTri = 0L;
				for (int u = 0; u < n; u++) {
					if (maskFrontier != null && !maskFrontier.contains(u))
						continue;
					int uLo = adjOff[u], uHi = adjEnd[u];
					for (int iv = uLo; iv < uHi; iv++) {
						int v = adjNbrs[iv];
						if (v > u) {
							int vLo = adjOff[v], vHi = adjEnd[v];
							for (int iw = iv + 1; iw < uHi; iw++) {
								int w = adjNbrs[iw];
								if (Arrays.binarySearch(adjNbrs, vLo, vHi, w) >= 0) {
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
					long[] out = ws.arena.allocLongZeroed(n);
					long[] valid = ws.arena.allocLongZeroed(words);
					for (int u = 0; u < n; u++) {
						if (maskFrontier == null || maskFrontier.contains(u)) {
							out[u] = triU[u];
							valid[u >>> 6] |= (1L << (u & 63));
						}
					}
					ws.vVectors[dst] = ws.arena.allocU64(out, n, valid);
					ws.zf = (totalTri == 0L);
					ws.pc++;
				}
			}
			default -> ws.status = IMPULSE_VM_ERR_INVALID_OPCODE;
		}
	}
}
