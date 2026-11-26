package org.impulsegraph.vm.v11;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable, zero-copy off-heap Topology &amp; Attribute View for ImpulseVM
 * v0.11.0 execution.
 *
 * <p>
 * All CSR/CSC topology reads go exclusively through off-heap
 * {@link MemorySegment}s (zero heap topology arrays and zero runtime
 * CSC/COO/CST synthesis). Test fixtures pack into off-heap segments via
 * {@code V11OffHeapFixtureBuilder} at fixture-construction time.
 */
public final class V11SnapshotView {

	/**
	 * Immutable off-heap attribute column descriptor (zero full-column heap
	 * copies).
	 */
	public record OffHeapAttrColumn(byte typeCode, int count, boolean nullable, MemorySegment dataSeg,
			MemorySegment validSeg, MemorySegment offsetsSeg) {

		public boolean isValidAt(int idx) {
			if (!nullable || validSeg == null || validSeg.equals(MemorySegment.NULL))
				return true;
			long byteIdx = idx >>> 3;
			if (byteIdx >= validSeg.byteSize())
				return false;
			return (validSeg.get(ValueLayout.JAVA_BYTE, byteIdx) & (1 << (idx & 7))) != 0;
		}

		public long readI64(int idx) {
			int base = typeCode & 0x7F;
			return switch (base) {
				case 1 -> dataSeg.get(ValueLayout.JAVA_BYTE, idx);
				case 2 -> dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) idx << 1);
				case 3 -> dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) idx << 2);
				case 4 -> dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) idx << 3);
				case 5 -> Byte.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_BYTE, idx));
				case 6 -> Short.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) idx << 1));
				case 7 -> Integer.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) idx << 2));
				case 8 -> dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) idx << 3);
				default -> throw new IllegalStateException("Non-integer attribute typeCode: " + base);
			};
		}

		public double readF64(int idx) {
			int base = typeCode & 0x7F;
			return switch (base) {
				case 9 -> dataSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) idx << 2);
				case 10 -> dataSeg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) idx << 3);
				default -> (double) readI64(idx);
			};
		}

		public String readString(int idx) {
			if (!isValidAt(idx))
				return null;
			long start = Integer.toUnsignedLong(offsetsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) idx << 2));
			long end = Integer.toUnsignedLong(offsetsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (idx + 1) << 2));
			if (end < start || end > dataSeg.byteSize()) {
				throw new IllegalStateException("Out-of-bounds UTF-8 string offset [" + start + ", " + end + ")");
			}
			byte[] tmp = dataSeg.asSlice(start, end - start).toArray(ValueLayout.JAVA_BYTE);
			return new String(tmp, StandardCharsets.UTF_8);
		}

		public int elemTag() {
			int base = typeCode & 0x7F;
			if (base >= 1 && base <= 4)
				return V11Vector.TAG_I64;
			if (base >= 5 && base <= 8)
				return V11Vector.TAG_U64;
			if (base == 9 || base == 10)
				return V11Vector.TAG_F64;
			if (base == 11)
				return V11Vector.TAG_STR;
			return V11Vector.TAG_I64;
		}
	}

	/**
	 * Off-heap key resolver interface (avoids materializing String[N] key catalogs
	 * on heap).
	 */
	public interface KeyResolver {
		int nodeCount();
		String resolveKey(int denseId);
	}

	public static final class RelView {
		public final int relId;
		public final int srcDom;
		public final int tgtDom;
		public final MemorySegment csrRowOffsetsSeg;
		public final MemorySegment csrColTargetsSeg;
		public final MemorySegment cscRowOffsetsSeg;
		public final MemorySegment cscColSourcesSeg;
		public final MemorySegment cscEdgeIdsSeg; // optional (test fixtures with reverse edge-attr ops)
		public final MemorySegment cooSrcSeg; // optional off-heap COO source IDs [E]
		public final MemorySegment cooTgtSeg; // optional off-heap COO target IDs [E]
		public final MemorySegment cooEdgeIdsSeg; // optional off-heap COO -> canonical edge IDs [E]
		public final byte nodeIdWidth;
		public final byte edgeIdxWidth;
		public final byte cooSrcWidth;
		public final byte cooTgtWidth;
		public final byte cooOrder; // 0=UNSORTED, 1=SRC, 2=SRC_TGT, 3=TGT, 4=TGT_SRC
		public final int csrNodeCount;
		public final int cscNodeCount;
		public final int edgeCount;
		public final int cooEdgeCount;

		public final Map<Integer, V11Vector> edgeAttrs;
		public final Map<Integer, OffHeapAttrColumn> offHeapEdgeAttrs;
		public final boolean hasCsr;
		public final boolean hasCsc;
		public final boolean hasCoo;
		public final boolean hasDense;

		public RelView(int relId, int srcDom, int tgtDom, MemorySegment csrRowOffsetsSeg,
				MemorySegment csrColTargetsSeg, MemorySegment cscRowOffsetsSeg, MemorySegment cscColSourcesSeg,
				byte nodeIdWidth, byte edgeIdxWidth, int csrNodeCount, int cscNodeCount, int edgeCount,
				Map<Integer, OffHeapAttrColumn> offHeapEdgeAttrs, boolean hasCsr, boolean hasCsc) {
			this(relId, srcDom, tgtDom, csrRowOffsetsSeg, csrColTargetsSeg, cscRowOffsetsSeg, cscColSourcesSeg, null,
					nodeIdWidth, edgeIdxWidth, csrNodeCount, cscNodeCount, edgeCount, null, offHeapEdgeAttrs, hasCsr,
					hasCsc, false, false);
		}

		public RelView(int relId, int srcDom, int tgtDom, MemorySegment csrRowOffsetsSeg,
				MemorySegment csrColTargetsSeg, MemorySegment cscRowOffsetsSeg, MemorySegment cscColSourcesSeg,
				MemorySegment cscEdgeIdsSeg, byte nodeIdWidth, byte edgeIdxWidth, int csrNodeCount, int cscNodeCount,
				int edgeCount, Map<Integer, V11Vector> edgeAttrs, Map<Integer, OffHeapAttrColumn> offHeapEdgeAttrs,
				boolean hasCsr, boolean hasCsc, boolean hasCoo, boolean hasDense) {
			this(relId, srcDom, tgtDom, csrRowOffsetsSeg, csrColTargetsSeg, cscRowOffsetsSeg, cscColSourcesSeg,
					cscEdgeIdsSeg, null, null, null, nodeIdWidth, edgeIdxWidth, nodeIdWidth, nodeIdWidth, (byte) 0,
					csrNodeCount, cscNodeCount, edgeCount, 0, edgeAttrs, offHeapEdgeAttrs, hasCsr, hasCsc, hasCoo,
					hasDense);
		}

		public RelView(int relId, int srcDom, int tgtDom, MemorySegment csrRowOffsetsSeg,
				MemorySegment csrColTargetsSeg, MemorySegment cscRowOffsetsSeg, MemorySegment cscColSourcesSeg,
				MemorySegment cscEdgeIdsSeg, MemorySegment cooSrcSeg, MemorySegment cooTgtSeg,
				MemorySegment cooEdgeIdsSeg, byte nodeIdWidth, byte edgeIdxWidth, byte cooSrcWidth, byte cooTgtWidth,
				byte cooOrder, int csrNodeCount, int cscNodeCount, int edgeCount, int cooEdgeCount,
				Map<Integer, V11Vector> edgeAttrs, Map<Integer, OffHeapAttrColumn> offHeapEdgeAttrs, boolean hasCsr,
				boolean hasCsc, boolean hasCoo, boolean hasDense) {
			if (relId < 0) {
				throw new IllegalArgumentException("Invalid negative relationId: " + relId);
			}
			if (nodeIdWidth != 2 && nodeIdWidth != 4 && nodeIdWidth != 8) {
				throw new IllegalArgumentException("Unsupported nodeIdWidth=" + nodeIdWidth + " (must be 2, 4, or 8)");
			}
			if (edgeIdxWidth != 4 && edgeIdxWidth != 8) {
				throw new IllegalArgumentException("Unsupported edgeIdxWidth=" + edgeIdxWidth + " (must be 4 or 8)");
			}
			if (cooSrcWidth != 2 && cooSrcWidth != 4 && cooSrcWidth != 8) {
				throw new IllegalArgumentException("Unsupported cooSrcWidth=" + cooSrcWidth + " (must be 2, 4, or 8)");
			}
			if (cooTgtWidth != 2 && cooTgtWidth != 4 && cooTgtWidth != 8) {
				throw new IllegalArgumentException("Unsupported cooTgtWidth=" + cooTgtWidth + " (must be 2, 4, or 8)");
			}
			if (csrNodeCount < 0 || cscNodeCount < 0 || edgeCount < 0 || cooEdgeCount < 0) {
				throw new IllegalArgumentException("Negative topology counts for relId=" + relId);
			}
			if (hasCsr) {
				long needRo = (long) (csrNodeCount + 1) * edgeIdxWidth;
				if (csrRowOffsetsSeg == null || csrRowOffsetsSeg.byteSize() < needRo) {
					throw new IllegalArgumentException("Truncated CSR rowOffsets for relId=" + relId);
				}
				long needCt = (long) edgeCount * nodeIdWidth;
				if (edgeCount > 0 && (csrColTargetsSeg == null || csrColTargetsSeg.byteSize() < needCt)) {
					throw new IllegalArgumentException("Truncated CSR colTargets for relId=" + relId);
				}
			}
			if (cscRowOffsetsSeg != null) {
				long needCscRo = (long) (cscNodeCount + 1) * edgeIdxWidth;
				if (cscRowOffsetsSeg.byteSize() < needCscRo) {
					throw new IllegalArgumentException("Truncated CSC rowOffsets for relId=" + relId);
				}
				long needCscCt = (long) edgeCount * cooSrcWidth;
				if (edgeCount > 0 && (cscColSourcesSeg == null || cscColSourcesSeg.byteSize() < needCscCt)) {
					throw new IllegalArgumentException("Truncated CSC colSources for relId=" + relId);
				}
			}
			if (cooSrcSeg != null && cooEdgeCount > 0) {
				if (cooSrcSeg.byteSize() < (long) cooEdgeCount * cooSrcWidth || cooTgtSeg == null
						|| cooTgtSeg.byteSize() < (long) cooEdgeCount * cooTgtWidth) {
					throw new IllegalArgumentException("Truncated COO segments for relId=" + relId);
				}
				if (cooEdgeIdsSeg != null && cooEdgeIdsSeg.byteSize() < (long) cooEdgeCount * edgeIdxWidth) {
					throw new IllegalArgumentException("Truncated COO edgeIds segment for relId=" + relId);
				}
			}
			this.relId = relId;
			this.srcDom = srcDom;
			this.tgtDom = tgtDom;
			this.csrRowOffsetsSeg = csrRowOffsetsSeg;
			this.csrColTargetsSeg = csrColTargetsSeg;
			this.cscRowOffsetsSeg = cscRowOffsetsSeg;
			this.cscColSourcesSeg = cscColSourcesSeg;
			this.cscEdgeIdsSeg = cscEdgeIdsSeg;
			this.cooSrcSeg = cooSrcSeg;
			this.cooTgtSeg = cooTgtSeg;
			this.cooEdgeIdsSeg = cooEdgeIdsSeg;
			this.nodeIdWidth = nodeIdWidth;
			this.edgeIdxWidth = edgeIdxWidth;
			this.cooSrcWidth = cooSrcWidth;
			this.cooTgtWidth = cooTgtWidth;
			this.cooOrder = cooOrder;
			this.csrNodeCount = csrNodeCount;
			this.cscNodeCount = cscNodeCount;
			this.edgeCount = edgeCount;
			this.cooEdgeCount = cooEdgeCount;
			this.edgeAttrs = edgeAttrs != null ? Collections.unmodifiableMap(edgeAttrs) : Map.of();
			this.offHeapEdgeAttrs = offHeapEdgeAttrs != null ? Collections.unmodifiableMap(offHeapEdgeAttrs) : Map.of();
			this.hasCsr = hasCsr;
			this.hasCsc = hasCsc;
			this.hasCoo = hasCoo;
			this.hasDense = hasDense;
		}

		public boolean hasCooSegments() {
			return hasCoo && (cooEdgeCount == 0 || (cooSrcSeg != null && cooTgtSeg != null));
		}

		public int cooEdgeCount() {
			return cooEdgeCount;
		}

		public int cooSrc(int i) {
			return switch (cooSrcWidth) {
				case 4 -> cooSrcSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i << 2);
				case 2 -> Short.toUnsignedInt(cooSrcSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) i << 1));
				case 8 -> Math.toIntExact(cooSrcSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) i << 3));
				default -> throw new IllegalStateException("Unsupported cooSrcWidth=" + cooSrcWidth);
			};
		}

		public int cooTgt(int i) {
			return switch (cooTgtWidth) {
				case 4 -> cooTgtSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i << 2);
				case 2 -> Short.toUnsignedInt(cooTgtSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) i << 1));
				case 8 -> Math.toIntExact(cooTgtSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) i << 3));
				default -> throw new IllegalStateException("Unsupported cooTgtWidth=" + cooTgtWidth);
			};
		}

		public int cooEdgeId(int i) {
			if (cooEdgeIdsSeg == null)
				return i;
			return edgeIdxWidth == 8
					? Math.toIntExact(cooEdgeIdsSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) i << 3))
					: cooEdgeIdsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i << 2);
		}

		public boolean hasCscSegments() {
			return cscRowOffsetsSeg != null && (edgeCount == 0 || cscColSourcesSeg != null);
		}

		public boolean hasCsrSegments() {
			return csrRowOffsetsSeg != null && (edgeCount == 0 || csrColTargetsSeg != null);
		}

		public int csrRowCount() {
			return csrNodeCount;
		}

		public int cscRowCount() {
			return cscNodeCount;
		}

		public int edgeCount() {
			return edgeCount;
		}

		public int csrRowOffset(int u) {
			return edgeIdxWidth == 8
					? Math.toIntExact(csrRowOffsetsSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) u << 3))
					: csrRowOffsetsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
		}

		public int csrColTarget(int e) {
			return switch (nodeIdWidth) {
				case 4 -> csrColTargetsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
				case 2 -> Short.toUnsignedInt(csrColTargetsSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) e << 1));
				case 8 -> Math.toIntExact(csrColTargetsSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) e << 3));
				default -> throw new IllegalStateException("Unsupported nodeIdWidth=" + nodeIdWidth);
			};
		}

		public int cscRowOffset(int u) {
			return edgeIdxWidth == 8
					? Math.toIntExact(cscRowOffsetsSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) u << 3))
					: cscRowOffsetsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
		}

		public int cscColSource(int e) {
			return switch (nodeIdWidth) {
				case 4 -> cscColSourcesSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
				case 2 -> Short.toUnsignedInt(cscColSourcesSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) e << 1));
				case 8 -> Math.toIntExact(cscColSourcesSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) e << 3));
				default -> throw new IllegalStateException("Unsupported nodeIdWidth=" + nodeIdWidth);
			};
		}

		public int cscEdgeId(int cscPos) {
			if (cscEdgeIdsSeg != null) {
				return cscEdgeIdsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) cscPos << 2);
			}
			return cscPos;
		}
	}

	public final long[] domainCards;
	public final RelView[] relations;
	public final Map<Long, V11Vector> nodeAttrs;
	public final Map<Long, OffHeapAttrColumn> offHeapNodeAttrs;
	public final Map<Integer, String[]> keyCatalogs;
	public final Map<Integer, KeyResolver> offHeapKeyResolvers;
	public final Map<Long, String> indexes;

	public V11SnapshotView(long[] domainCards, RelView[] relations, Map<Long, V11Vector> nodeAttrs,
			Map<Integer, String[]> keyCatalogs, Map<Long, String> indexes) {
		this(domainCards, relations, nodeAttrs, Map.of(), keyCatalogs, Map.of(), indexes);
	}

	public V11SnapshotView(long[] domainCards, RelView[] relations, Map<Long, V11Vector> nodeAttrs,
			Map<Long, OffHeapAttrColumn> offHeapNodeAttrs, Map<Integer, String[]> keyCatalogs,
			Map<Integer, KeyResolver> offHeapKeyResolvers, Map<Long, String> indexes) {
		this.domainCards = domainCards != null ? domainCards : new long[0];
		this.relations = relations != null ? relations : new RelView[0];
		this.nodeAttrs = nodeAttrs != null ? Collections.unmodifiableMap(nodeAttrs) : Map.of();
		this.offHeapNodeAttrs = offHeapNodeAttrs != null ? Collections.unmodifiableMap(offHeapNodeAttrs) : Map.of();
		this.keyCatalogs = keyCatalogs != null ? Collections.unmodifiableMap(keyCatalogs) : Map.of();
		this.offHeapKeyResolvers = offHeapKeyResolvers != null
				? Collections.unmodifiableMap(offHeapKeyResolvers)
				: Map.of();
		this.indexes = indexes != null ? Collections.unmodifiableMap(indexes) : Map.of();
	}

	public static long domAttrKey(int dom, int attr) {
		return ((long) dom << 32) | (attr & 0xFFFFFFFFL);
	}

	public V11Vector getNodeAttr(int dom, int attr) {
		return nodeAttrs.get(domAttrKey(dom, attr));
	}

	public OffHeapAttrColumn getOffHeapNodeAttr(int dom, int attr) {
		return offHeapNodeAttrs.get(domAttrKey(dom, attr));
	}

	public boolean hasNodeAttr(int dom, int attr) {
		long key = domAttrKey(dom, attr);
		return nodeAttrs.containsKey(key) || offHeapNodeAttrs.containsKey(key);
	}

	public String[] getKeyCatalog(int dom) {
		return keyCatalogs.get(dom);
	}

	public KeyResolver getOffHeapKeyResolver(int dom) {
		return offHeapKeyResolvers.get(dom);
	}

	public boolean hasKeyCatalog(int dom) {
		return keyCatalogs.containsKey(dom) || offHeapKeyResolvers.containsKey(dom);
	}

	public boolean hasIndex(int dom, int attr) {
		return indexes.containsKey(domAttrKey(dom, attr));
	}
}
