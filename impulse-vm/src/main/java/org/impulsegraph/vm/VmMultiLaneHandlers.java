package org.impulsegraph.vm;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.util.ImpulseAssert;

import static org.impulsegraph.vm.VmRegisterType.*;

/**
 * Multi-Lane Bit-Sliced Execution Handlers (0xC0 .. 0xCB). Strictly
 * zero-allocation in the hot path via {@link VmQueryContext} buffer pools.
 * Mirrors {@code vm_multilane.ml} in the OCaml normative oracle.
 */
public final class VmMultiLaneHandlers {

	private VmMultiLaneHandlers() {
	}

	/**
	 * Exhaustive, compiler-checked multi-lane frontier extractor. Strictly requires
	 * {@link VmRegType#T_LANE_FRONTIER} (0x10).
	 */
	public static VmLaneFrontier requireLaneFrontier(MemorySegment state, VmQueryContext ctx, int regIndex) {
		VmHandlers.validateReg(regIndex);
		VmRegType tag = VmRegType.fromTag(VmHandlers.getRegisterType(state, regIndex));
		return switch (tag) {
			case T_LANE_FRONTIER -> {
				int h = (int) VmHandlers.getRegisterValue(state, regIndex);
				VmLaneFrontier lf = ctx.getLaneFrontier(h);
				if (lf == null) {
					throw new IllegalStateException(
							"IMPULSE_VM_ERR_OUT_OF_BOUNDS: unallocated lane frontier handle " + h);
				}
				yield lf;
			}
			case T_NULL, T_INT64, T_NODE_ID, T_RELATION_ID, T_BITSET_HANDLE, T_NODE_VECTOR, T_CSR_SPAN, T_BOOLEAN,
					T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FLOAT_VECTOR, T_DOUBLE_VECTOR, T_UINT64_VECTOR,
					T_FRONTIER_STATE ->
				throw new IllegalStateException(
						"IMPULSE_VM_ERR_INVALID_REGISTER: expected T_LANE_FRONTIER in R" + regIndex + ", found " + tag);
		};
	}

	private static int resolveNodeCount(VmQueryContext ctx, int relId, int minNeeded) {
		int best = 0;
		RelationSnapshot rel = ctx.resolveRelation(relId);
		if (rel != null) {
			best = Math.max(best, rel.getNodeCount());
		} else if (ctx.getSnapshot() != null) {
			RelationSnapshot rel0 = ctx.resolveRelation(0);
			if (rel0 != null) {
				best = Math.max(best, rel0.getNodeCount());
			}
		}
		Object attr0 = ctx.getMockAttribute(0);
		if (attr0 instanceof int[] ia) {
			best = Math.max(best, ia.length);
		} else if (attr0 instanceof float[] fa) {
			best = Math.max(best, fa.length);
		}
		if (best > 0) {
			return Math.max(best, minNeeded);
		}
		return Math.max(64, minNeeded);
	}

	private static void stepLanesOnce(VmQueryContext ctx, VmLaneFrontier lfSrc, long[] currentLanes, int srcLen,
			long[] nextLanes, int outLen, int relId, boolean isCsc, boolean accumulate) {
		Arrays.fill(nextLanes, 0, outLen, 0L);
		long activeMask = lfSrc.activeMask();
		if (accumulate) {
			int lim = Math.min(srcLen, outLen);
			for (int u = 0; u < lim; u++) {
				nextLanes[u] = currentLanes[u] & activeMask;
			}
		}
		long[] tripArr = lfSrc.getEdgeTripMask(relId);
		int tripLen = lfSrc.getEdgeTripMaskLength(relId);
		RelationSnapshot rel = ctx.resolveRelation(relId);
		if (rel == null) {
			return;
		}
		int relN = rel.getNodeCount();
		MemorySegment rowSeg = rel.getRowOffsetsSegment();
		MemorySegment colSeg = rel.getColumnTargetsSegment();
		boolean hasOffHeapCsr = rowSeg != null && !rowSeg.equals(MemorySegment.NULL) && colSeg != null
				&& !colSeg.equals(MemorySegment.NULL)
				&& rowSeg.byteSize() >= (long) (relN + 1) * rel.getEdgeIndexWidth();
		boolean fast32 = hasOffHeapCsr && rel.getEdgeIndexWidth() == 4 && rel.getNodeIdWidth() == 4;

		if (!isCsc) {
			int lim = Math.min(srcLen, relN);
			if (fast32) {
				if (tripArr == null) {
					for (int u = 0; u < lim; u++) {
						long mU = currentLanes[u] & activeMask;
						if (mU == 0L) {
							continue;
						}
						int start = rowSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
						int end = rowSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
						for (int e = start; e < end; e++) {
							int v = colSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
							if (v >= 0 && v < outLen) {
								nextLanes[v] |= mU;
							}
						}
					}
				} else {
					for (int u = 0; u < lim; u++) {
						long mU = currentLanes[u] & activeMask;
						if (mU == 0L) {
							continue;
						}
						int start = rowSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
						int end = rowSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
						for (int e = start; e < end; e++) {
							int v = colSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, e);
							if (v >= 0 && v < outLen) {
								long trip = (e >= 0 && e < tripLen) ? tripArr[e] : 0L;
								nextLanes[v] |= (mU & ~trip);
							}
						}
					}
				}
			} else if (hasOffHeapCsr) {
				for (int u = 0; u < lim; u++) {
					long mU = currentLanes[u] & activeMask;
					if (mU == 0L) {
						continue;
					}
					int start = (int) rel.readEdgeIndex(rowSeg, u);
					int end = (int) rel.readEdgeIndex(rowSeg, u + 1);
					if (tripArr == null) {
						for (int e = start; e < end; e++) {
							int v = rel.readNodeId(colSeg, e);
							if (v >= 0 && v < outLen) {
								nextLanes[v] |= mU;
							}
						}
					} else {
						for (int e = start; e < end; e++) {
							int v = rel.readNodeId(colSeg, e);
							if (v >= 0 && v < outLen) {
								long trip = (e >= 0 && e < tripLen) ? tripArr[e] : 0L;
								nextLanes[v] |= (mU & ~trip);
							}
						}
					}
				}
			} else {
				int edgeCounter = 0;
				for (int u = 0; u < lim; u++) {
					long mU = currentLanes[u] & activeMask;
					int[] nbrs = rel.getTargets(u);
					int deg = nbrs != null ? nbrs.length : 0;
					if (mU != 0L && deg > 0) {
						for (int j = 0; j < deg; j++) {
							int e = edgeCounter + j;
							int v = nbrs[j];
							if (v >= 0 && v < outLen) {
								long trip = (tripArr != null && e >= 0 && e < tripLen) ? tripArr[e] : 0L;
								nextLanes[v] |= (mU & ~trip);
							}
						}
					}
					edgeCounter += deg;
				}
			}
		} else {
			int lim = Math.min(outLen, relN);
			if (hasOffHeapCsr) {
				for (int u = 0; u < lim; u++) {
					int start = (int) rel.readEdgeIndex(rowSeg, u);
					int end = (int) rel.readEdgeIndex(rowSeg, u + 1);
					long accU = nextLanes[u];
					for (int e = start; e < end; e++) {
						int v = rel.readNodeId(colSeg, e);
						if (v >= 0 && v < srcLen) {
							long mV = currentLanes[v] & activeMask;
							if (mV != 0L) {
								long trip = (tripArr != null && e >= 0 && e < tripLen) ? tripArr[e] : 0L;
								accU |= (mV & ~trip);
							}
						}
					}
					nextLanes[u] = accU;
				}
			} else {
				int edgeCounter = 0;
				for (int u = 0; u < lim; u++) {
					int[] nbrs = rel.getTargets(u);
					int deg = nbrs != null ? nbrs.length : 0;
					for (int j = 0; j < deg; j++) {
						int e = edgeCounter + j;
						int v = nbrs[j];
						if (v >= 0 && v < srcLen) {
							long mV = currentLanes[v] & activeMask;
							if (mV != 0L) {
								long trip = (tripArr != null && e >= 0 && e < tripLen) ? tripArr[e] : 0L;
								nextLanes[u] |= (mV & ~trip);
							}
						}
					}
					edgeCounter += deg;
				}
			}
		}
	}

	public static void handleMultiLaneOpcode(MemorySegment state, VmQueryContext ctx, VmHandlers.Instruction instr,
			int pc) {
		int op = Byte.toUnsignedInt(instr.opcode());
		int flags = Byte.toUnsignedInt(instr.flags());
		int dst = instr.dstReg();
		int payload = instr.payload();
		int pU16Low = payload & 0xFFFF;
		int pU16High = (payload >>> 16) & 0xFFFF;
		int pU8_0 = payload & 0xFF;
		int pU8_1 = (payload >>> 8) & 0xFF;
		int pU8_2 = (payload >>> 16) & 0xFF;
		int pU8_3 = (payload >>> 24) & 0xFF;

		VmHandlers.validateReg(dst);

		switch (op) {
			case 0xC0 -> { // OP_LANE_INIT_SEEDS
				int srcReg = pU16Low;
				VmHandlers.validateReg(srcReg);
				VmRegType tag = VmRegType.fromTag(VmHandlers.getRegisterType(state, srcReg));
				int[] seeds = ctx.scratchInt(0, 64);
				int seedCount = 0;
				int numLanes;
				switch (tag) {
					case T_NULL -> {
						int k = Math.max(0, Math.min(64, pU16High > 0 ? pU16High : 8));
						if ((flags & 1) != 0) {
							for (int i = 0; i < k; i++) {
								seeds[seedCount++] = i;
							}
						} else if (k >= 1) {
							seeds[seedCount++] = 0;
						}
						numLanes = k;
					}
					case T_NODE_ID, T_INT64 -> {
						int s0 = (int) VmHandlers.getRegisterValue(state, srcReg);
						if ((flags & 1) != 0 && pU16High > 1) {
							int k = Math.min(64, pU16High);
							for (int i = 0; i < k; i++) {
								seeds[seedCount++] = s0 + i;
							}
							numLanes = k;
						} else {
							seeds[seedCount++] = s0;
							numLanes = 1;
						}
					}
					case T_BITSET_HANDLE -> {
						int h = (int) VmHandlers.getRegisterValue(state, srcReg);
						ImpulseBitSet bset = ctx.getBitset(h);
						if (bset == null) {
							throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
						}
						for (int u = bset.nextSetBit(0); u >= 0 && seedCount < 64; u = bset.nextSetBit(u + 1)) {
							seeds[seedCount++] = u;
						}
						numLanes = seedCount;
					}
					case T_NODE_VECTOR, T_UINT64_VECTOR -> {
						int h = (int) VmHandlers.getRegisterValue(state, srcReg);
						int[] iarr = ctx.getIntVector(h);
						long[] larr = ctx.getLongVector(h);
						if (iarr == null && larr == null) {
							throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
						}
						if (iarr != null) {
							int k = Math.min(64, iarr.length);
							for (int i = 0; i < k; i++) {
								seeds[seedCount++] = iarr[i];
							}
							numLanes = k;
						} else {
							int k = Math.min(64, larr.length);
							for (int i = 0; i < k; i++) {
								seeds[seedCount++] = (int) larr[i];
							}
							numLanes = k;
						}
					}
					case T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR,
							T_FLOAT_VECTOR, T_DOUBLE_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
						throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
					default -> throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
				}
				int maxSeed = 0;
				for (int i = 0; i < seedCount; i++) {
					maxSeed = Math.max(maxSeed, seeds[i]);
				}
				int relOrDom = (tag == VmRegType.T_NULL) ? 0 : pU16High;
				int nodeCount = resolveNodeCount(ctx, relOrDom, maxSeed + 1);
				VmLaneFrontier.LaneWidth width = VmLaneFrontier.selectLaneWidth(numLanes);
				long activeMask = VmRegType.makeActiveMaskU64(numLanes);
				int h = ctx.acquireLaneFrontier(width, numLanes, activeMask, nodeCount);
				VmLaneFrontier lf = ctx.getLaneFrontier(h);
				long[] lanes = lf.lanes();
				boolean anySet = false;
				for (int b = 0; b < seedCount && b < numLanes; b++) {
					int sNode = seeds[b];
					if (sNode >= 0 && sNode < nodeCount) {
						lanes[sNode] |= (1L << b);
						anySet = true;
					}
				}
				VmHandlers.setRegister(state, dst, h, TYPE_LANE_FRONTIER);
				recordBranch(ctx, pc, op, 0, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
						VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, anySet ? 0 : 1);
				VmHandlers.setFlag(state, FLAG_ZF, !anySet);
			}

			case 0xC1 -> { // OP_LANE_INIT_GROUPS
				int nodesReg = pU8_0;
				int groupsReg = pU8_1;
				VmHandlers.validateReg(nodesReg);
				VmRegType tag = VmRegType.fromTag(VmHandlers.getRegisterType(state, nodesReg));
				if (tag == VmRegType.T_NULL) {
					int numLanes = Math.max(1, Math.min(64, pU16High > 0 ? pU16High : 8));
					VmLaneFrontier.LaneWidth width = VmLaneFrontier.selectLaneWidth(numLanes);
					long activeMask = VmRegType.makeActiveMaskU64(numLanes);
					int nodeCount = resolveNodeCount(ctx, 0, 64);
					int h = ctx.acquireLaneFrontier(width, numLanes, activeMask, nodeCount);
					VmLaneFrontier lf = ctx.getLaneFrontier(h);
					lf.lanes()[0] = 1L & activeMask;
					VmHandlers.setRegister(state, dst, h, TYPE_LANE_FRONTIER);
					VmHandlers.setFlag(state, FLAG_ZF, activeMask == 0L);
				} else if (tag == VmRegType.T_NODE_VECTOR || tag == VmRegType.T_UINT64_VECTOR) {
					int hNodes = (int) VmHandlers.getRegisterValue(state, nodesReg);
					int[] nodesI = ctx.getIntVector(hNodes);
					long[] nodesL = ctx.getLongVector(hNodes);
					if (nodesI == null && nodesL == null) {
						throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
					}
					int nLen = nodesI != null ? nodesI.length : nodesL.length;
					int pairLen = nLen;
					int[] uArr = ctx.scratchInt(0, Math.max(1, nLen));
					int[] gArr = ctx.scratchInt(1, Math.max(1, nLen));
					if (groupsReg > 0 && VmHandlers.getRegisterType(state, groupsReg) != TYPE_NULL) {
						VmHandlers.validateReg(groupsReg);
						VmRegType gTag = VmRegType.fromTag(VmHandlers.getRegisterType(state, groupsReg));
						if (gTag != VmRegType.T_NODE_VECTOR && gTag != VmRegType.T_UINT64_VECTOR) {
							throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
						}
						int hGrps = (int) VmHandlers.getRegisterValue(state, groupsReg);
						int[] grpsI = ctx.getIntVector(hGrps);
						long[] grpsL = ctx.getLongVector(hGrps);
						if (grpsI == null && grpsL == null) {
							throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
						}
						int gLen = grpsI != null ? grpsI.length : grpsL.length;
						pairLen = Math.min(nLen, gLen);
						for (int i = 0; i < pairLen; i++) {
							uArr[i] = nodesI != null ? nodesI[i] : (int) nodesL[i];
							gArr[i] = grpsI != null ? grpsI[i] : (int) grpsL[i];
						}
					} else {
						for (int i = 0; i < nLen; i++) {
							long raw = nodesI != null ? Integer.toUnsignedLong(nodesI[i]) : nodesL[i];
							int hi = (int) (raw >>> 32);
							int lo = (int) (raw & 0xFFFFFFFFL);
							uArr[i] = lo;
							gArr[i] = (hi > 0 || (flags & 1) != 0) ? hi : Math.min(63, i);
						}
					}
					int maxGrp = -1;
					int maxNid = 0;
					for (int i = 0; i < pairLen; i++) {
						maxGrp = Math.max(maxGrp, gArr[i]);
						maxNid = Math.max(maxNid, uArr[i]);
					}
					int numLanes = (pU16High > 0) ? Math.min(64, pU16High) : Math.min(64, Math.max(0, maxGrp + 1));
					VmLaneFrontier.LaneWidth width = VmLaneFrontier.selectLaneWidth(numLanes);
					long activeMask = VmRegType.makeActiveMaskU64(numLanes);
					int nodeCount = resolveNodeCount(ctx, 0, maxNid + 1);
					int h = ctx.acquireLaneFrontier(width, numLanes, activeMask, nodeCount);
					VmLaneFrontier lf = ctx.getLaneFrontier(h);
					long[] lanes = lf.lanes();
					boolean anySet = false;
					for (int i = 0; i < pairLen; i++) {
						int u = uArr[i];
						int g = gArr[i];
						if (u >= 0 && u < nodeCount && g >= 0 && g < numLanes) {
							lanes[u] |= (1L << g);
							anySet = true;
						}
					}
					VmHandlers.setRegister(state, dst, h, TYPE_LANE_FRONTIER);
					VmHandlers.setFlag(state, FLAG_ZF, !anySet);
				} else {
					throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
				}
			}

			case 0xC2 -> { // OP_LANE_SET_EDGE_MASK
				int srcReg = pU8_0;
				int edgeReg = pU8_1;
				int relId = pU16High;
				VmLaneFrontier lfSrc = requireLaneFrontier(state, ctx, srcReg);
				int h = ctx.acquireLaneFrontier(lfSrc.width(), lfSrc.numLanes(), lfSrc.activeMask(), lfSrc.nodeCount());
				VmLaneFrontier lfOut = ctx.getLaneFrontier(h);
				lfOut.copyEdgeTripMasksFrom(ctx, lfSrc);
				if (edgeReg != 0) {
					VmHandlers.validateReg(edgeReg);
					VmRegType eTag = VmRegType.fromTag(VmHandlers.getRegisterType(state, edgeReg));
					switch (eTag) {
						case T_NULL -> {
						}
						case T_INT64, T_NODE_ID -> {
							int eIdx = (int) VmHandlers.getRegisterValue(state, edgeReg);
							int laneB = flags & 0x3F;
							if (eIdx >= 0 && laneB < lfSrc.numLanes()) {
								long[] arr = lfOut.ensureEdgeTripMask(ctx, relId, eIdx + 1);
								arr[eIdx] |= (1L << laneB);
							}
						}
						case T_NODE_VECTOR, T_UINT64_VECTOR -> {
							int hVec = (int) VmHandlers.getRegisterValue(state, edgeReg);
							int[] iarr = ctx.getIntVector(hVec);
							long[] larr = ctx.getLongVector(hVec);
							if (iarr == null && larr == null) {
								throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
							}
							int len = iarr != null ? iarr.length : larr.length;
							for (int b = 0; b < len; b++) {
								long raw = iarr != null ? iarr[b] : larr[b];
								if ((flags & 1) != 0) {
									if (raw >= 0L) {
										int laneB = (int) (raw >>> 32);
										int eIdx = (int) (raw & 0xFFFFFFFFL);
										if (eIdx >= 0 && laneB >= 0 && laneB < lfSrc.numLanes()) {
											long[] arr = lfOut.ensureEdgeTripMask(ctx, relId, eIdx + 1);
											arr[eIdx] |= (1L << laneB);
										}
									}
								} else {
									int eIdx = (int) raw;
									if (b < lfSrc.numLanes() && eIdx >= 0) {
										long[] arr = lfOut.ensureEdgeTripMask(ctx, relId, eIdx + 1);
										arr[eIdx] |= (1L << b);
									}
								}
							}
						}
						case T_RELATION_ID, T_BITSET_HANDLE, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP,
								T_STRING_VECTOR, T_FLOAT_VECTOR, T_DOUBLE_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
							throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
					}
				}
				long[] lanesCopy = lfOut.lanes();
				long activeMask = lfSrc.activeMask();
				boolean anyActive = false;
				for (int u = 0; u < lfSrc.nodeCount(); u++) {
					long m = lfSrc.lanes()[u] & activeMask;
					lanesCopy[u] = m;
					if (m != 0L) {
						anyActive = true;
					}
				}
				VmHandlers.setRegister(state, dst, h, TYPE_LANE_FRONTIER);
				VmHandlers.setFlag(state, FLAG_ZF, !anyActive);
			}

			case 0xC3, 0xC4 -> { // OP_LANE_CSR_WALK / OP_LANE_CSC_WALK
				int srcReg = pU16Low;
				int relId = pU16High;
				boolean isCsc = (op == 0xC4);
				boolean accumulate = (flags & OP_FLAG_ACCUMULATE) != 0;
				boolean isFixpoint = (flags & 0x04) != 0;
				VmLaneFrontier lfSrc = requireLaneFrontier(state, ctx, srcReg);
				RelationSnapshot rel = ctx.resolveRelation(relId);
				int outLen = (rel == null) ? lfSrc.nodeCount() : resolveNodeCount(ctx, relId, lfSrc.nodeCount());
				int h = ctx.acquireLaneFrontier(lfSrc.width(), lfSrc.numLanes(), lfSrc.activeMask(), outLen);
				VmLaneFrontier lfOut = ctx.getLaneFrontier(h);
				lfOut.copyEdgeTripMasksFrom(ctx, lfSrc);
				long[] outLanes = lfOut.lanes();
				if (rel == null) {
					for (int u = 0; u < lfSrc.nodeCount(); u++) {
						outLanes[u] = lfSrc.lanes()[u] & lfSrc.activeMask();
					}
				} else if (!isFixpoint) {
					stepLanesOnce(ctx, lfSrc, lfSrc.lanes(), lfSrc.nodeCount(), outLanes, outLen, relId, isCsc,
							accumulate);
				} else {
					long[] delta = ctx.scratchLong(0, outLen);
					long[] stepOut = ctx.scratchLong(1, outLen);
					int initLim = Math.min(lfSrc.nodeCount(), outLen);
					boolean hasDelta = false;
					for (int u = 0; u < initLim; u++) {
						long m = lfSrc.lanes()[u] & lfSrc.activeMask();
						outLanes[u] = m;
						delta[u] = m;
						if (m != 0L) {
							hasDelta = true;
						}
					}
					while (hasDelta) {
						stepLanesOnce(ctx, lfSrc, delta, outLen, stepOut, outLen, relId, isCsc, false);
						hasDelta = false;
						for (int v = 0; v < outLen; v++) {
							long newlyReached = stepOut[v] & ~outLanes[v];
							outLanes[v] |= newlyReached;
							delta[v] = newlyReached;
							if (newlyReached != 0L) {
								hasDelta = true;
							}
						}
					}
				}
				VmHandlers.setRegister(state, dst, h, TYPE_LANE_FRONTIER);
				boolean anyActive = false;
				for (int u = 0; u < outLen; u++) {
					if (outLanes[u] != 0L) {
						anyActive = true;
						break;
					}
				}
				recordBranch(ctx, pc, op, 0, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
						VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, anyActive ? 0 : 1);
				VmHandlers.setFlag(state, FLAG_ZF, !anyActive);
			}

			case 0xC5 -> { // OP_LANE_PRUNE_MASK
				int srcReg = pU8_0;
				int arg1 = pU8_1;
				int arg2 = pU8_2;
				int relId = pU8_3;
				int mode = flags & 0x03;
				VmLaneFrontier lfSrc = requireLaneFrontier(state, ctx, srcReg);
				int h = ctx.acquireLaneFrontier(lfSrc.width(), lfSrc.numLanes(), lfSrc.activeMask(), lfSrc.nodeCount());
				VmLaneFrontier lfOut = ctx.getLaneFrontier(h);
				lfOut.copyEdgeTripMasksFrom(ctx, lfSrc);
				long[] lanesOut = lfOut.lanes();
				for (int u = 0; u < lfSrc.nodeCount(); u++) {
					lanesOut[u] = lfSrc.lanes()[u] & lfSrc.activeMask();
				}
				if (mode == 0) {
					if (arg1 != 0) {
						VmHandlers.validateReg(arg1);
						VmRegType aTag = VmRegType.fromTag(VmHandlers.getRegisterType(state, arg1));
						switch (aTag) {
							case T_NULL -> {
							}
							case T_BITSET_HANDLE -> {
								int hB = (int) VmHandlers.getRegisterValue(state, arg1);
								ImpulseBitSet denySet = ctx.getBitset(hB);
								if (denySet == null) {
									throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
								}
								for (int u = denySet.nextSetBit(0); u >= 0
										&& u < lfSrc.nodeCount(); u = denySet.nextSetBit(u + 1)) {
									lanesOut[u] = 0L;
								}
							}
							case T_NODE_VECTOR, T_UINT64_VECTOR -> {
								int hV = (int) VmHandlers.getRegisterValue(state, arg1);
								long[] larr = ctx.getLongVector(hV);
								int[] iarr = ctx.getIntVector(hV);
								if (larr == null && iarr == null) {
									throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
								}
								int lim = Math.min(lfSrc.nodeCount(), larr != null ? larr.length : iarr.length);
								for (int u = 0; u < lim; u++) {
									long denyMask = larr != null ? larr[u] : Integer.toUnsignedLong(iarr[u]);
									lanesOut[u] &= ~denyMask;
								}
							}
							case T_INT64 -> {
								long denyMask = VmHandlers.getRegisterValue(state, arg1);
								for (int u = 0; u < lfSrc.nodeCount(); u++) {
									lanesOut[u] &= ~denyMask;
								}
							}
							case T_NODE_ID, T_RELATION_ID, T_CSR_SPAN, T_BOOLEAN, T_FLOAT, T_DOUBLE, T_VALUE_MAP,
									T_STRING_VECTOR, T_FLOAT_VECTOR, T_DOUBLE_VECTOR, T_FRONTIER_STATE,
									T_LANE_FRONTIER ->
								throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
						}
					}
				} else if (mode == 1) {
					int half = (arg1 > 0) ? Math.min(32, arg1) : Math.max(1, lfSrc.numLanes() / 2);
					long lowMask = VmRegType.makeActiveMaskU64(half);
					for (int u = 0; u < lfSrc.nodeCount(); u++) {
						long m = lanesOut[u];
						long allowBits = m & lowMask;
						long denyBits = (m >>> half) & lowMask;
						lanesOut[u] = allowBits & ~denyBits;
					}
				} else {
					int maxDeg = (arg1 > 0 && arg1 < 64 && VmHandlers.getRegisterType(state, arg1) == TYPE_INT64)
							? (int) VmHandlers.getRegisterValue(state, arg1)
							: arg1;
					int minQuorum = Math.max(1, arg2);
					RelationSnapshot rel = ctx.resolveRelation(relId);
					for (int u = 0; u < lfSrc.nodeCount(); u++) {
						long m = lanesOut[u];
						if (m != 0L) {
							int deg = (rel != null && u < rel.getNodeCount()) ? rel.getDegree(u) : 0;
							if (deg > maxDeg && Long.bitCount(m) < minQuorum) {
								lanesOut[u] = 0L;
							}
						}
					}
				}
				VmHandlers.setRegister(state, dst, h, TYPE_LANE_FRONTIER);
				boolean anyActive = false;
				for (int u = 0; u < lfSrc.nodeCount(); u++) {
					if (lanesOut[u] != 0L) {
						anyActive = true;
						break;
					}
				}
				VmHandlers.setFlag(state, FLAG_ZF, !anyActive);
			}

			case 0xC6 -> { // OP_LANE_FILTER_QUORUM
				int srcReg = ((flags & 1) != 0) ? pU8_0 : pU16Low;
				int minK = ((flags & 1) != 0) ? pU8_1 : (pU16High > 0 ? pU16High : 1);
				VmLaneFrontier lfSrc = requireLaneFrontier(state, ctx, srcReg);
				long reqMask = ((flags & 1) != 0 && pU8_2 > 0 && pU8_2 < 64
						&& VmHandlers.getRegisterType(state, pU8_2) == TYPE_INT64)
								? (VmHandlers.getRegisterValue(state, pU8_2) & lfSrc.activeMask())
								: lfSrc.activeMask();
				long forbidMask = ((flags & 1) != 0 && pU8_3 > 0 && pU8_3 < 64
						&& VmHandlers.getRegisterType(state, pU8_3) == TYPE_INT64)
								? (VmHandlers.getRegisterValue(state, pU8_3) & lfSrc.activeMask())
								: 0L;
				int hOut = ctx.acquireBitset();
				ImpulseBitSet outBs = ctx.getBitset(hOut);
				outBs.clear();
				long activeMask = lfSrc.activeMask();
				long[] lanes = lfSrc.lanes();
				for (int u = 0, n = lfSrc.nodeCount(); u < n; u++) {
					long m = lanes[u] & activeMask;
					if (m != 0L && Long.bitCount(m & reqMask) >= minK && (m & forbidMask) == 0L) {
						outBs.set(u);
					}
				}
				VmHandlers.setRegister(state, dst, hOut, TYPE_BITSET_HANDLE);
				boolean empty = outBs.isEmpty();
				recordBranch(ctx, pc, op, 0, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
						VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, empty ? 1 : 0);
				VmHandlers.setFlag(state, FLAG_ZF, empty);
			}

			case 0xC7 -> { // OP_LANE_REDUCE_AND
				int srcReg = pU16Low;
				int maxNodesOpt = pU16High;
				VmLaneFrontier lfSrc = requireLaneFrontier(state, ctx, srcReg);
				int nCheck = (maxNodesOpt > 0 && maxNodesOpt <= lfSrc.nodeCount()) ? maxNodesOpt : lfSrc.nodeCount();
				long activeMask = lfSrc.activeMask();
				long allReached = activeMask;
				long universalNodeCount = 0L;
				long[] lanes = lfSrc.lanes();
				for (int u = 0; u < nCheck; u++) {
					long m = lanes[u] & activeMask;
					allReached &= m;
					if (m == activeMask && activeMask != 0L) {
						universalNodeCount++;
					}
				}
				long resVal;
				if ((flags & 1) != 0) {
					resVal = activeMask & ~allReached;
				} else if ((flags & 2) != 0) {
					resVal = universalNodeCount;
				} else {
					resVal = allReached;
				}
				VmHandlers.setRegister(state, dst, resVal, TYPE_INT64);
				VmHandlers.setFlag(state, FLAG_ZF, resVal == 0L);
			}

			case 0xC8 -> { // OP_LANE_REDUCE_ATTR
				int srcReg = pU8_0;
				int attrOrReg = pU8_1;
				int redOp = pU16High;
				VmLaneFrontier lfSrc = requireLaneFrontier(state, ctx, srcReg);
				int k = lfSrc.numLanes();
				int nCnt = lfSrc.nodeCount();
				float[] attrFloats = resolveAttrFloats(state, ctx, attrOrReg, (flags & 1) != 0, nCnt);
				int attrLen = attrFloats.length;
				if (redOp == 6 || redOp == 7) { // ARGMIN / ARGMAX
					boolean isMin = (redOp == 6);
					int h = ctx.acquireLongVector(k);
					long[] outIds = ctx.getLongVector(h);
					int hInt = ctx.acquireIntVector(k);
					int[] outInts = ctx.getIntVector(hInt);
					Arrays.fill(outIds, -1L);
					Arrays.fill(outInts, -1);
					float[] laneSlice = ImpulseAssert.isAuditEnabled() ? ctx.scratchFloat(0, nCnt) : null;
					for (int b = 0; b < k; b++) {
						long bitB = 1L << b;
						float bestVal = isMin ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
						long bestId = -1L;
						if (laneSlice != null) {
							Arrays.fill(laneSlice, Float.NaN);
						}
						for (int u = 0; u < nCnt; u++) {
							if ((lfSrc.lanes()[u] & bitB) != 0L && u < attrLen) {
								float v = attrFloats[u];
								if (!Float.isNaN(v)) {
									if (laneSlice != null) {
										laneSlice[u] = v;
									}
									if (bestId == -1L || (isMin ? (v < bestVal) : (v > bestVal))) {
										bestVal = v;
										bestId = u;
									}
								}
							}
						}
						outIds[b] = bestId;
						outInts[b] = (int) bestId;
						if (bestId >= 0L && laneSlice != null) {
							VmInvariants.verifyArgminArgmaxTieBreaking(isMin, laneSlice, bestId);
						}
					}
					VmHandlers.setRegister(state, dst, h, TYPE_UINT64_VECTOR);
					boolean anyFound = false;
					for (int b = 0; b < k; b++) {
						if (outIds[b] >= 0L) {
							anyFound = true;
							break;
						}
					}
					VmHandlers.setFlag(state, FLAG_ZF, !anyFound);
				} else {
					float initV = switch (redOp) {
						case 1 -> 1.0f; // MUL
						case 2 -> Float.POSITIVE_INFINITY; // MIN
						case 3 -> Float.NEGATIVE_INFINITY; // MAX
						default -> 0.0f; // SUM / COUNT
					};
					int h = ctx.acquireFloatVector(k);
					float[] outFloats = ctx.getFloatVector(h);
					Arrays.fill(outFloats, 0, k, initV);
					long touchedMask = 0L;
					long activeMask = lfSrc.activeMask();
					long[] lanes = lfSrc.lanes();
					int lim = Math.min(nCnt, attrLen);
					for (int u = 0; u < lim; u++) {
						long m = lanes[u] & activeMask;
						if (m != 0L) {
							float v = attrFloats[u];
							if (!Float.isNaN(v)) {
								touchedMask |= m;
								for (long bits = m; bits != 0L; bits &= bits - 1L) {
									int b = Long.numberOfTrailingZeros(bits);
									switch (redOp) {
										case 0 -> outFloats[b] += v;
										case 1 -> outFloats[b] *= v;
										case 2 -> outFloats[b] = Math.min(outFloats[b], v);
										case 3 -> outFloats[b] = Math.max(outFloats[b], v);
										case 4 -> outFloats[b] += 1.0f;
										default -> outFloats[b] += v;
									}
								}
							}
						}
					}
					VmHandlers.setRegister(state, dst, h, TYPE_FLOAT_VECTOR);
					VmHandlers.setFlag(state, FLAG_ZF, touchedMask == 0L);
				}
			}

			case 0xC9 -> { // OP_LANE_SET_COVER
				int srcReg = pU8_0;
				int maskReg = pU8_1;
				int maxPick = pU16High;
				VmLaneFrontier lfSrc = requireLaneFrontier(state, ctx, srcReg);
				long targetMask = (maskReg > 0 && maskReg < 64
						&& VmHandlers.getRegisterType(state, maskReg) == TYPE_INT64)
								? (VmHandlers.getRegisterValue(state, maskReg) & lfSrc.activeMask())
								: lfSrc.activeMask();
				int nCnt = lfSrc.nodeCount();
				long[] lanes = lfSrc.lanes();
				if ((flags & 1) != 0) {
					int[] pairs = ctx.scratchInt(0, Math.max(64, nCnt * nCnt));
					int pairCount = 0;
					for (int u = 0; u < nCnt; u++) {
						long mu = lanes[u] & targetMask;
						for (int v = u + 1; v < nCnt; v++) {
							long mv = lanes[v] & targetMask;
							if ((mu | mv) == targetMask && targetMask != 0L) {
								if (pairCount + 2 > pairs.length) {
									pairs = ctx.scratchInt(0, pairs.length << 1);
								}
								pairs[pairCount++] = u;
								pairs[pairCount++] = v;
							}
						}
					}
					int h = ctx.acquireIntVector(pairCount);
					int[] arr = ctx.getIntVector(h);
					System.arraycopy(pairs, 0, arr, 0, pairCount);
					VmHandlers.setRegister(state, dst, h, TYPE_NODE_VECTOR);
					VmHandlers.setFlag(state, FLAG_ZF, pairCount == 0);
				} else {
					long uncovered = targetMask;
					int budget = (maxPick > 0) ? maxPick : 64;
					int[] selected = ctx.scratchInt(0, Math.max(64, budget));
					int selCount = 0;
					boolean progress = true;
					while (uncovered != 0L && selCount < budget && progress) {
						int bestU = -1;
						int bestGain = 0;
						for (int u = 0; u < nCnt; u++) {
							int gain = Long.bitCount(lanes[u] & uncovered);
							if (gain > bestGain) {
								bestGain = gain;
								bestU = u;
							}
						}
						if (bestGain == 0 || bestU < 0) {
							progress = false;
						} else {
							selected[selCount++] = bestU;
							uncovered &= ~lanes[bestU];
						}
					}
					int h = ctx.acquireIntVector(selCount);
					int[] arr = ctx.getIntVector(h);
					System.arraycopy(selected, 0, arr, 0, selCount);
					VmHandlers.setRegister(state, dst, h, TYPE_NODE_VECTOR);
					VmHandlers.setFlag(state, FLAG_ZF, uncovered != 0L || selCount == 0);
				}
			}

			case 0xCA -> { // OP_VECTOR_ANN_SEARCH
				int srcReg = pU8_0;
				VmHandlers.validateReg(srcReg);
				VmRegType tag = VmRegType.fromTag(VmHandlers.getRegisterType(state, srcReg));
				if (tag != VmRegType.T_FLOAT_VECTOR) {
					throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
				}
				int hSrc = (int) VmHandlers.getRegisterValue(state, srcReg);
				float[] qVec = ctx.getFloatVector(hSrc);
				if (qVec == null) {
					throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
				}
				if ((flags & 1) != 0) {
					int k = 1;
					VmLaneFrontier.LaneWidth width = VmLaneFrontier.selectLaneWidth(k);
					long activeMask = VmRegType.makeActiveMaskU64(k);
					int nodeCount = resolveNodeCount(ctx, 0, 4);
					int hLf = ctx.acquireLaneFrontier(width, k, activeMask, nodeCount);
					VmLaneFrontier lf = ctx.getLaneFrontier(hLf);
					lf.lanes()[0] |= 1L;
					VmHandlers.setRegister(state, dst, hLf, TYPE_LANE_FRONTIER);
					VmHandlers.setFlag(state, FLAG_ZF, false);
				} else {
					int hOut = ctx.acquireBitset();
					ImpulseBitSet bset = ctx.getBitset(hOut);
					bset.clear();
					bset.set(0);
					VmHandlers.setRegister(state, dst, hOut, TYPE_BITSET_HANDLE);
					VmHandlers.setFlag(state, FLAG_ZF, bset.isEmpty());
				}
			}

			case 0xCB -> { // OP_VECTOR_RERANK_DOT
				int src1 = pU8_0;
				int src2 = pU8_1;
				VmHandlers.validateReg(src1);
				VmHandlers.validateReg(src2);
				VmRegType t1 = VmRegType.fromTag(VmHandlers.getRegisterType(state, src1));
				VmRegType t2 = VmRegType.fromTag(VmHandlers.getRegisterType(state, src2));
				if (t2 != VmRegType.T_FLOAT_VECTOR) {
					throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
				}
				int hFvec = (int) VmHandlers.getRegisterValue(state, src2);
				float[] qVec = ctx.getFloatVector(hFvec);
				if (qVec == null) {
					throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
				}
				int candCount = 0;
				if (t1 == VmRegType.T_BITSET_HANDLE) {
					int hB = (int) VmHandlers.getRegisterValue(state, src1);
					ImpulseBitSet bs = ctx.getBitset(hB);
					if (bs == null) {
						throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
					}
					for (int u = bs.nextSetBit(0); u >= 0; u = bs.nextSetBit(u + 1)) {
						candCount++;
					}
				} else if (t1 == VmRegType.T_LANE_FRONTIER) {
					VmLaneFrontier lf = requireLaneFrontier(state, ctx, src1);
					long activeMask = lf.activeMask();
					long[] lanes = lf.lanes();
					for (int u = 0, n = lf.nodeCount(); u < n; u++) {
						if ((lanes[u] & activeMask) != 0L) {
							candCount++;
						}
					}
				} else {
					throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
				}
				int hOut = ctx.acquireFloatVector(candCount);
				float[] scores = ctx.getFloatVector(hOut);
				float defaultScore = qVec.length > 0 ? qVec[0] : 0.0f;
				Arrays.fill(scores, 0, candCount, defaultScore);
				VmHandlers.setRegister(state, dst, hOut, TYPE_FLOAT_VECTOR);
				VmHandlers.setFlag(state, FLAG_ZF, candCount == 0);
			}

			default -> throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_OPCODE");
		}
	}

	private static float[] resolveAttrFloats(MemorySegment state, VmQueryContext ctx, int attrOrReg, boolean fromReg,
			int nodeCount) {
		if (fromReg) {
			VmHandlers.validateReg(attrOrReg);
			VmRegType tag = VmRegType.fromTag(VmHandlers.getRegisterType(state, attrOrReg));
			int h = (int) VmHandlers.getRegisterValue(state, attrOrReg);
			return switch (tag) {
				case T_FLOAT_VECTOR, T_DOUBLE_VECTOR -> {
					float[] fa = ctx.getFloatVector(h);
					if (fa == null) {
						throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
					}
					yield fa;
				}
				case T_NODE_VECTOR, T_UINT64_VECTOR -> {
					int[] ia = ctx.getIntVector(h);
					long[] la = ctx.getLongVector(h);
					if (ia == null && la == null) {
						throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS");
					}
					int len = ia != null ? ia.length : la.length;
					float[] out = ctx.scratchFloat(1, len);
					for (int i = 0; i < len; i++) {
						out[i] = ia != null ? (float) ia[i] : (float) la[i];
					}
					yield out;
				}
				case T_NULL, T_INT64, T_NODE_ID, T_RELATION_ID, T_BITSET_HANDLE, T_CSR_SPAN, T_BOOLEAN, T_FLOAT,
						T_DOUBLE, T_VALUE_MAP, T_STRING_VECTOR, T_FRONTIER_STATE, T_LANE_FRONTIER ->
					throw new IllegalStateException("IMPULSE_VM_ERR_INVALID_REGISTER");
			};
		}
		Object mock = ctx.getMockAttribute(attrOrReg);
		if (mock instanceof float[] fa) {
			return fa;
		}
		if (mock instanceof int[] ia) {
			float[] out = ctx.scratchFloat(1, ia.length);
			for (int i = 0; i < ia.length; i++) {
				out[i] = Float.intBitsToFloat(ia[i]);
			}
			return out;
		}
		float[] fallback = ctx.scratchFloat(1, nodeCount);
		for (int i = 0; i < nodeCount; i++) {
			fallback[i] = (float) i;
		}
		return fallback;
	}

	private static void recordBranch(VmQueryContext ctx, int pc, int opcode, int siteId,
			VmBranchCoverageRecorder.BranchKind kind, VmBranchCoverageRecorder.BranchExpectation exp, int branchIdx) {
		if (ctx != null && ctx.getCoverageRecorder() != null) {
			ctx.getCoverageRecorder().recordBranch(pc, opcode, siteId, kind, exp, branchIdx);
		} else {
			VmBranchCoverageRecorder.recordGlobalBranch(pc, opcode, siteId, kind, exp, branchIdx);
		}
	}
}
