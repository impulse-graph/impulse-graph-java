package org.impulsegraph.vm;

import java.util.Arrays;
import java.util.Map;
import org.impulsegraph.api.util.ImpulseAssert;

/**
 * Multi-Lane Bit-Sliced Frontier representation (TYPE_LANE_FRONTIER = 0x10).
 * Supports 8/16/32/64-bit hardware lane tiers and per-relation counterfactual
 * edge trip masks. Pooled via {@link VmQueryContext} for zero-allocation
 * hot-path execution.
 */
public final class VmLaneFrontier {

	public enum LaneWidth {
		WIDTH_8(8), WIDTH_16(16), WIDTH_32(32), WIDTH_64(64);

		private final int maxLanes;

		LaneWidth(int maxLanes) {
			this.maxLanes = maxLanes;
		}

		public int maxLanes() {
			return maxLanes;
		}
	}

	/**
	 * Normative 17 seed-count boundaries for multi-lane width and batching
	 * verification.
	 */
	public static final int[] NORMATIVE_BOUNDARY_SEED_COUNTS = {0, 1, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 127,
			128, 129};

	public static LaneWidth selectLaneWidth(int k) {
		ImpulseAssert.testcase(200, k == 0);
		ImpulseAssert.testcase(201, k == 1);
		ImpulseAssert.testcase(207, k == 7);
		ImpulseAssert.testcase(208, k == 8);
		ImpulseAssert.testcase(209, k == 9);
		ImpulseAssert.testcase(215, k == 15);
		ImpulseAssert.testcase(216, k == 16);
		ImpulseAssert.testcase(217, k == 17);
		ImpulseAssert.testcase(231, k == 31);
		ImpulseAssert.testcase(232, k == 32);
		ImpulseAssert.testcase(233, k == 33);
		ImpulseAssert.testcase(263, k == 63);
		ImpulseAssert.testcase(264, k == 64);
		if (k <= 8) {
			return LaneWidth.WIDTH_8;
		}
		if (k <= 16) {
			return LaneWidth.WIDTH_16;
		}
		if (k <= 32) {
			return LaneWidth.WIDTH_32;
		}
		return LaneWidth.WIDTH_64;
	}

	private LaneWidth width;
	private int numLanes;
	private long activeMask;
	private int nodeCount;
	private long[] lanes;
	private final int[] tripRelIds;
	private final long[][] tripMasks;
	private final int[] tripMaskLengths;
	private int tripMaskCount;

	VmLaneFrontier(int[] tripRelIds, long[][] tripMasks, int[] tripMaskLengths) {
		this.width = LaneWidth.WIDTH_8;
		this.numLanes = 0;
		this.activeMask = 0L;
		this.nodeCount = 0;
		this.lanes = null;
		this.tripRelIds = tripRelIds;
		this.tripMasks = tripMasks;
		this.tripMaskLengths = tripMaskLengths;
		this.tripMaskCount = 0;
	}

	public VmLaneFrontier(LaneWidth width, int numLanes, long activeMask, int nodeCount, long[] lanes,
			Map<Integer, long[]> ignored) {
		this.width = width;
		this.numLanes = numLanes;
		this.activeMask = activeMask;
		this.nodeCount = nodeCount;
		this.lanes = lanes;
		this.tripRelIds = null;
		this.tripMasks = null;
		this.tripMaskLengths = null;
		this.tripMaskCount = 0;
	}

	void reset(LaneWidth width, int numLanes, long activeMask, int nodeCount, long[] lanesBuf) {
		this.width = width;
		this.numLanes = numLanes;
		this.activeMask = activeMask;
		this.nodeCount = nodeCount;
		this.lanes = lanesBuf;
		this.tripMaskCount = 0;
		Arrays.fill(lanesBuf, 0, nodeCount, 0L);
	}

	public LaneWidth width() {
		return width;
	}

	public int numLanes() {
		return numLanes;
	}

	public long activeMask() {
		return activeMask;
	}

	public int nodeCount() {
		return nodeCount;
	}

	public long[] lanes() {
		return lanes;
	}

	public long[] getEdgeTripMask(int relId) {
		if (tripRelIds == null) {
			return null;
		}
		for (int i = 0; i < tripMaskCount; i++) {
			if (tripRelIds[i] == relId) {
				return tripMasks[i];
			}
		}
		return null;
	}

	public int getEdgeTripMaskLength(int relId) {
		if (tripRelIds == null) {
			return 0;
		}
		for (int i = 0; i < tripMaskCount; i++) {
			if (tripRelIds[i] == relId) {
				return tripMaskLengths[i];
			}
		}
		return 0;
	}

	public void copyEdgeTripMasksFrom(VmQueryContext ctx, VmLaneFrontier src) {
		this.tripMaskCount = 0;
		if (src.tripRelIds == null || this.tripRelIds == null) {
			return;
		}
		int n = src.tripMaskCount;
		for (int i = 0; i < n; i++) {
			int relId = src.tripRelIds[i];
			int len = src.tripMaskLengths[i];
			long[] srcArr = src.tripMasks[i];
			long[] dstArr = this.tripMasks[i];
			if (dstArr == null || dstArr.length < len) {
				dstArr = ctx.allocateRawLongBuffer(Math.max(64, len));
				this.tripMasks[i] = dstArr;
			}
			System.arraycopy(srcArr, 0, dstArr, 0, len);
			this.tripRelIds[i] = relId;
			this.tripMaskLengths[i] = len;
		}
		this.tripMaskCount = n;
	}

	public long[] ensureEdgeTripMask(VmQueryContext ctx, int relId, int minEdges) {
		for (int i = 0; i < tripMaskCount; i++) {
			if (tripRelIds[i] == relId) {
				long[] cur = tripMasks[i];
				int oldLen = tripMaskLengths[i];
				if (cur.length < minEdges) {
					int newCap = Math.max(minEdges, cur.length << 1);
					long[] next = ctx.allocateRawLongBuffer(newCap);
					System.arraycopy(cur, 0, next, 0, oldLen);
					Arrays.fill(next, oldLen, minEdges, 0L);
					tripMasks[i] = next;
					tripMaskLengths[i] = minEdges;
					return next;
				}
				if (minEdges > oldLen) {
					Arrays.fill(cur, oldLen, minEdges, 0L);
					tripMaskLengths[i] = minEdges;
				}
				return cur;
			}
		}
		int slot = tripMaskCount;
		if (slot >= tripRelIds.length) {
			slot = tripRelIds.length - 1;
		} else {
			tripMaskCount++;
		}
		long[] buf = tripMasks[slot];
		int cap = Math.max(64, minEdges);
		if (buf == null || buf.length < minEdges) {
			buf = ctx.allocateRawLongBuffer(cap);
			tripMasks[slot] = buf;
		}
		Arrays.fill(buf, 0, minEdges, 0L);
		tripRelIds[slot] = relId;
		tripMaskLengths[slot] = minEdges;
		return buf;
	}
}
