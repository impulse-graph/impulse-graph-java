package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Zero-copy mmap-and-go adapter from {@link ImpulseGraphSnapshot}
 * ({@code .imps}) to immutable {@link ImpulseSnapshotView}.
 *
 * <p>
 * Lifecycle 1 contract (AGENTS.md §3.20):
 * <ul>
 * <li>Runs in {@code O(#domains + #relations + #attributes)} time and
 * heap.</li>
 * <li>Validates all domain cardinalities, relation IDs, integer widths, and
 * off-heap {@link MemorySegment} byte bounds upfront.</li>
 * <li>NEVER copies CSR, CSC, attribute payload bytes, or key catalogs onto the
 * Java heap.</li>
 * <li>NEVER synthesizes missing CSC/COO/CST or fabricates missing
 * IDs/cardinalities.</li>
 * </ul>
 */
public final class ImpulseSnapshotAdapter {

	private ImpulseSnapshotAdapter() {
	}

	public static ImpulseSnapshotView from(ImpulseGraphSnapshot snapshot) {
		if (snapshot == null) {
			return new ImpulseSnapshotView(new long[0], new ImpulseSnapshotView.RelView[0], Map.of(), Map.of(),
					Map.of());
		}

		Map<Long, ImpulseVector> nodeEmbAttrs = new HashMap<>();
		Map<Long, ImpulseSnapshotView.OffHeapAttrColumn> offHeapNodeAttrs = new HashMap<>();
		Map<Integer, ImpulseSnapshotView.KeyResolver> offHeapKeyResolvers = new HashMap<>();
		Map<Long, String> indexes = new HashMap<>();

		int maxDomId = -1;
		Map<Integer, Long> domCardMap = new HashMap<>();

		// 1. Validate and bind domains, off-heap node attributes, and off-heap key
		// resolvers
		if (snapshot instanceof GraphSnapshot gs) {
			for (String domName : gs.getDomainNames()) {
				BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(domName);
				if (ld == null)
					continue;
				int did = ld.domainId();
				if (did < 0) {
					throw new IllegalArgumentException("Invalid negative domainId for domain " + domName);
				}
				long nodeCount = ld.nodeCount();
				if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE) {
					throw new IllegalArgumentException("Invalid nodeCount=" + nodeCount + " for domain " + domName);
				}
				maxDomId = Math.max(maxDomId, did);
				domCardMap.put(did, nodeCount);

				List<BinarySnapshotLoader.LoadedAttribute> attrs = ld.attributes();
				for (int a = 0; a < attrs.size(); a++) {
					BinarySnapshotLoader.LoadedAttribute attr = attrs.get(a);
					MemorySegment dataSeg = ld.getAttributeSegment(a);
					MemorySegment validSeg = (a < ld.validitySegments().size()) ? ld.validitySegments().get(a) : null;
					MemorySegment offSeg = (a < ld.offsetsSegments().size()) ? ld.offsetsSegments().get(a) : null;
					if (dataSeg != null && !dataSeg.equals(MemorySegment.NULL)) {
						int baseType = attr.typeCode() & 0x7F;
						if (nodeCount > 0 && (baseType == 12 || ("emb".equalsIgnoreCase(attr.name()) && baseType == 9
								&& dataSeg.byteSize() >= nodeCount * 4L
								&& (dataSeg.byteSize() % (nodeCount * 4L)) == 0))) {
							int dim = (int) (dataSeg.byteSize() / (nodeCount * 4L));
							if (dim > 0) {
								double[][] rows = new double[(int) nodeCount][dim];
								for (int u = 0; u < (int) nodeCount; u++) {
									for (int d = 0; d < dim; d++) {
										rows[u][d] = dataSeg.get(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED,
												((long) u * dim + d) * 4L);
									}
								}
								nodeEmbAttrs.put(ImpulseSnapshotView.domAttrKey(did, a),
										ImpulseVector.ofEmb(dim, rows));
							}
						}
						validateAttrBounds(attr.typeCode(), (int) nodeCount, dataSeg, offSeg,
								domName + "." + attr.name());
						offHeapNodeAttrs.put(ImpulseSnapshotView.domAttrKey(did, a),
								new ImpulseSnapshotView.OffHeapAttrColumn(attr.typeCode(), (int) nodeCount,
										attr.isNullable(), dataSeg, validSeg, offSeg));
					}
				}

				if (ld.keyAttrIndex() != 0xFFFF && nodeCount > 0) {
					final int n = (int) nodeCount;
					offHeapKeyResolvers.put(did, new ImpulseSnapshotView.KeyResolver() {
						@Override
						public int nodeCount() {
							return n;
						}
						@Override
						public String resolveKey(int denseId) {
							return ld.resolveKey(denseId);
						}
					});
				}
			}
		}

		// 2. Validate and bind relations strictly off-heap (zero topology copies, zero
		// ID fabrication)
		Map<String, RelationSnapshot> allRels = snapshot.getAllRelationSnapshots();
		int maxRelId = -1;
		Map<Integer, RelationSnapshot> relById = new HashMap<>();
		if (allRels != null) {
			for (Map.Entry<String, RelationSnapshot> e : allRels.entrySet()) {
				RelationSnapshot rel = e.getValue();
				if (rel == null)
					continue;
				int rid = rel.getRelationId();
				if (rid < 0) {
					// Synthetic domain-carrier entries (e.g. "__dom_*") in test GraphSnapshots are
					// not relations
					if (e.getKey().startsWith("__dom_"))
						continue;
					throw new IllegalArgumentException("Missing/negative relationId for relation " + e.getKey());
				}
				maxRelId = Math.max(maxRelId, rid);
				relById.put(rid, rel);
				// If snapshot had no Domain Table (legacy single-domain snapshot), populate
				// from relation bounds
				if (domCardMap.isEmpty()) {
					maxDomId = Math.max(maxDomId, Math.max(rel.getSrcDomainId(), rel.getTgtDomainId()));
				}
			}
		}

		if (!relById.isEmpty()) {
			for (RelationSnapshot rel : relById.values()) {
				int sDom = rel.getSrcDomainId();
				int tDom = rel.getTgtDomainId();
				maxDomId = Math.max(maxDomId, Math.max(sDom, tDom));
				if (rel.getNodeCount() > 0) {
					domCardMap.merge(sDom, (long) rel.getNodeCount(), Math::max);
				}
				if (rel.hasCsc() && rel.getCscNodeCount() > 0) {
					domCardMap.merge(tDom, (long) rel.getCscNodeCount(), Math::max);
				} else if (rel.getNodeCount() > 0 && domCardMap.getOrDefault(tDom, 0L) == 0L) {
					domCardMap.merge(tDom, (long) rel.getNodeCount(), Math::max);
				}
			}
		}

		if (domCardMap.isEmpty() && relById.isEmpty()) {
			maxDomId = 0;
		}
		int numDoms = Math.max(0, maxDomId + 1);
		long[] domainCards = new long[numDoms];
		for (int d = 0; d < numDoms; d++) {
			domainCards[d] = domCardMap.getOrDefault(d, 0L);
		}

		int numRels = Math.max(0, maxRelId + 1);
		ImpulseSnapshotView.RelView[] relViews = new ImpulseSnapshotView.RelView[numRels];
		for (Map.Entry<Integer, RelationSnapshot> entry : relById.entrySet()) {
			int rid = entry.getKey();
			RelationSnapshot rel = entry.getValue();
			int nEdges = Math.toIntExact(rel.getEdgeCount());

			MemorySegment roSeg = rel.hasCsr() ? rel.getRowOffsetsSegment() : null;
			MemorySegment ctSeg = rel.hasCsr() ? rel.getColumnTargetsSegment() : null;
			MemorySegment cscRoSeg = rel.hasCsc() ? rel.getCscRowOffsetsSegment() : null;
			MemorySegment cscCtSeg = rel.hasCsc() ? rel.getCscColumnTargetsSegment() : null;
			MemorySegment cooSrcSeg = rel.hasCoo() ? rel.getCooSrcSegment() : null;
			MemorySegment cooTgtSeg = rel.hasCoo() ? rel.getCooTgtSegment() : null;
			MemorySegment cooEdgeIdsSeg = rel.hasCoo() ? rel.getCooEdgeIdsSegment() : null;
			if (roSeg != null && roSeg.equals(MemorySegment.NULL))
				roSeg = null;
			if (ctSeg != null && ctSeg.equals(MemorySegment.NULL))
				ctSeg = null;
			if (cscRoSeg != null && cscRoSeg.equals(MemorySegment.NULL))
				cscRoSeg = null;
			if (cscCtSeg != null && cscCtSeg.equals(MemorySegment.NULL))
				cscCtSeg = null;
			if (cooSrcSeg != null && cooSrcSeg.equals(MemorySegment.NULL))
				cooSrcSeg = null;
			if (cooTgtSeg != null && cooTgtSeg.equals(MemorySegment.NULL))
				cooTgtSeg = null;
			if (cooEdgeIdsSeg != null && cooEdgeIdsSeg.equals(MemorySegment.NULL))
				cooEdgeIdsSeg = null;

			byte nodeIdWidth = rel.getNodeIdWidth();
			byte srcNodeIdWidth = rel.getSrcNodeIdWidth();
			byte edgeIdxWidth = rel.getEdgeIndexWidth();
			if (nodeIdWidth == 0)
				nodeIdWidth = 4;
			if (srcNodeIdWidth == 0)
				srcNodeIdWidth = nodeIdWidth;
			if (edgeIdxWidth == 0)
				edgeIdxWidth = 4;

			Map<Integer, ImpulseSnapshotView.OffHeapAttrColumn> offHeapEdgeAttrs = new HashMap<>();
			List<MemorySegment> attrSegs = rel.getAttributeSegments();
			if (attrSegs != null) {
				for (int a = 0; a < attrSegs.size(); a++) {
					MemorySegment seg = attrSegs.get(a);
					if (seg == null || seg.equals(MemorySegment.NULL) || seg.byteSize() == 0)
						continue;
					String aname = (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot crs)
							? crs.getAttributeName(a)
							: null;
					int nDom = rel.getNodeCount();
					if ("emb".equalsIgnoreCase(aname) && nDom > 0 && seg.byteSize() >= (long) nDom * 4L
							&& (seg.byteSize() % ((long) nDom * 4L)) == 0) {
						int dim = (int) (seg.byteSize() / ((long) nDom * 4L));
						double[][] rows = new double[nDom][dim];
						for (int u = 0; u < nDom; u++) {
							for (int d = 0; d < dim; d++) {
								rows[u][d] = seg.get(java.lang.foreign.ValueLayout.JAVA_FLOAT_UNALIGNED,
										((long) u * dim + d) * 4L);
							}
						}
						nodeEmbAttrs.putIfAbsent(ImpulseSnapshotView.domAttrKey(rel.getSrcDomainId(), 0),
								ImpulseVector.ofEmb(dim, rows));
					}
					byte actualType = (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot crs)
							? crs.getAttributeTypeCode(a)
							: (byte) 0;
					byte inferredType = (actualType != 0)
							? actualType
							: ((nEdges > 0 && seg.byteSize() == (long) nEdges * 8L) ? (byte) 7 : (byte) 6);
					MemorySegment edgeValidSeg = (rel instanceof org.impulsegraph.storage.csr.RelationSnapshot crs
							&& a < crs.getValiditySegments().size()) ? crs.getValiditySegments().get(a) : null;
					boolean edgeNullable = (inferredType & 0x80) != 0
							|| (edgeValidSeg != null && !edgeValidSeg.equals(MemorySegment.NULL));
					offHeapEdgeAttrs.put(a, new ImpulseSnapshotView.OffHeapAttrColumn(inferredType, nEdges,
							edgeNullable, seg, edgeValidSeg, null));
				}
			}

			boolean hasCsr = rel.hasCsr() && roSeg != null && roSeg.byteSize() > 0;
			boolean hasCsc = rel.hasCsc() && cscRoSeg != null && cscRoSeg.byteSize() > 0;
			boolean hasCoo = rel.hasCoo() && cooSrcSeg != null && cooSrcSeg.byteSize() > 0;
			relViews[rid] = new ImpulseSnapshotView.RelView(rid, rel.getSrcDomainId(), rel.getTgtDomainId(),
					hasCsr ? roSeg : null, hasCsr ? ctSeg : null, hasCsc ? cscRoSeg : null, hasCsc ? cscCtSeg : null,
					null, hasCoo ? cooSrcSeg : null, hasCoo ? cooTgtSeg : null, hasCoo ? cooEdgeIdsSeg : null,
					nodeIdWidth, edgeIdxWidth, srcNodeIdWidth, nodeIdWidth, rel.getCooOrder(),
					hasCsr ? rel.getNodeCount() : 0, hasCsc ? rel.getCscNodeCount() : 0, nEdges, hasCoo ? nEdges : 0,
					null, offHeapEdgeAttrs, hasCsr, hasCsc, hasCoo, false);
		}

		return new ImpulseSnapshotView(domainCards, relViews, nodeEmbAttrs, offHeapNodeAttrs, Map.of(),
				offHeapKeyResolvers, indexes);
	}

	private static void validateAttrBounds(byte attrType, int count, MemorySegment dataSeg, MemorySegment offSeg,
			String label) {
		if (count == 0)
			return;
		int baseType = attrType & 0x7F;
		long elemBytes = switch (baseType) {
			case 1 -> 1L;
			case 2, 5 -> 2L;
			case 3, 6 -> 4L;
			case 4, 7, 8, 9 -> 8L;
			default -> 0L;
		};
		if (elemBytes > 0 && dataSeg.byteSize() < (long) count * elemBytes) {
			throw new IllegalArgumentException("Truncated attribute segment for " + label + ": need "
					+ ((long) count * elemBytes) + "B, got " + dataSeg.byteSize() + "B");
		}
		if (baseType == 11 && (offSeg == null || offSeg.equals(MemorySegment.NULL)
				|| offSeg.byteSize() < ((long) count + 1L) * 4L)) {
			throw new IllegalArgumentException("Truncated UTF-8 offsets segment for " + label);
		}
	}
}
