package org.impulsegraph.vm.v11;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts a real memory-mapped {@link ImpulseGraphSnapshot} ({@code .imps}) into a
 * {@link V11SnapshotView} for ImpulseVM v0.11.0 execution (mirrors C++
 * {@code build_snapshot_view_from_imps} in {@code v11_vm_dispatch.cpp}).
 */
public final class V11SnapshotAdapter {

    private V11SnapshotAdapter() {}

    /**
     * Converts an {@link ImpulseGraphSnapshot} into a reusable {@link V11SnapshotView}.
     * Call once per snapshot (not per query) to preserve {@code 0 B/query} steady-state execution.
     */
    public static V11SnapshotView from(ImpulseGraphSnapshot snapshot) {
        if (snapshot == null) {
            return new V11SnapshotView(new long[0], new V11SnapshotView.RelView[0],
                    Map.of(), Map.of(), Map.of());
        }

        // 1. Domains, node attributes, and Section 2 key catalogs
        Map<Long, V11Vector> nodeAttrs = new HashMap<>();
        Map<Integer, String[]> keyCatalogs = new HashMap<>();
        Map<Long, String> indexes = new HashMap<>();

        int maxDomId = -1;
        Map<Integer, Long> domCardMap = new HashMap<>();

        if (snapshot instanceof GraphSnapshot gs) {
            for (String domName : gs.getDomainNames()) {
                BinarySnapshotLoader.LoadedDomain ld = gs.getDomain(domName);
                if (ld == null) continue;
                int did = ld.domainId();
                maxDomId = Math.max(maxDomId, did);
                domCardMap.put(did, ld.nodeCount());

                // Decode node attributes for this domain
                List<BinarySnapshotLoader.LoadedAttribute> attrs = ld.attributes();
                for (int a = 0; a < attrs.size(); a++) {
                    BinarySnapshotLoader.LoadedAttribute attr = attrs.get(a);
                    MemorySegment dataSeg = ld.getAttributeSegment(a);
                    MemorySegment validSeg = (a < ld.validitySegments().size()) ? ld.validitySegments().get(a) : MemorySegment.NULL;
                    MemorySegment offSeg = (a < ld.offsetsSegments().size()) ? ld.offsetsSegments().get(a) : MemorySegment.NULL;
                    V11Vector vec = decodeAttribute(attr.typeCode(), ld.nodeCount(), dataSeg, validSeg, offSeg, attr.isNullable());
                    if (vec != null) {
                        nodeAttrs.put(V11SnapshotView.domAttrKey(did, a), vec);
                    }
                }

                // Decode key catalog if present
                if (ld.keyAttrIndex() != 0xFFFF && ld.nodeCount() > 0 && ld.nodeCount() <= Integer.MAX_VALUE) {
                    int n = (int) ld.nodeCount();
                    String[] keys = new String[n];
                    boolean anyKey = false;
                    for (int i = 0; i < n; i++) {
                        String k = ld.resolveKey(i);
                        keys[i] = k;
                        if (k != null) anyKey = true;
                    }
                    if (anyKey) {
                        keyCatalogs.put(did, keys);
                    }
                }
            }
        }

        // 2. Relations (CSR topology & edge attributes)
        Map<String, RelationSnapshot> allRels = snapshot.getAllRelationSnapshots();
        int maxRelId = -1;
        Map<Integer, RelationSnapshot> relById = new HashMap<>();
        int fallbackId = 0;
        if (allRels != null) {
            for (RelationSnapshot rel : allRels.values()) {
                if (rel == null) continue;
                int rid = rel.getRelationId() >= 0 ? rel.getRelationId() : fallbackId;
                fallbackId++;
                maxRelId = Math.max(maxRelId, rid);
                relById.put(rid, rel);
                maxDomId = Math.max(maxDomId, Math.max(rel.getSrcDomainId(), rel.getTgtDomainId()));
                domCardMap.putIfAbsent(rel.getSrcDomainId(), (long) rel.getNodeCount());
                if (rel.hasCsc()) {
                    domCardMap.putIfAbsent(rel.getTgtDomainId(), (long) Math.max(rel.getCscNodeCount(), rel.getNodeCount()));
                } else {
                    domCardMap.putIfAbsent(rel.getTgtDomainId(), (long) rel.getNodeCount());
                }
            }
        }

        int numDoms = Math.max(0, maxDomId + 1);
        long[] domainCards = new long[numDoms];
        for (int d = 0; d < numDoms; d++) {
            domainCards[d] = domCardMap.getOrDefault(d, 0L);
        }

        int numRels = Math.max(0, maxRelId + 1);
        V11SnapshotView.RelView[] relViews = new V11SnapshotView.RelView[numRels];
        for (Map.Entry<Integer, RelationSnapshot> entry : relById.entrySet()) {
            int rid = entry.getKey();
            RelationSnapshot rel = entry.getValue();
            int nRows = rel.getNodeCount() + 1;
            int nEdges = (int) rel.getEdgeCount();

            int[] rowOffsets = new int[nRows];
            long[] colTargets = new long[nEdges];
            MemorySegment roSeg = rel.getRowOffsetsSegment();
            MemorySegment ctSeg = rel.getColumnTargetsSegment();

            if (rel.hasCsr() && roSeg != null && !roSeg.equals(MemorySegment.NULL) && roSeg.byteSize() > 0) {
                for (int i = 0; i < nRows; i++) {
                    rowOffsets[i] = (int) rel.readEdgeIndex(roSeg, i);
                }
                if (ctSeg != null && !ctSeg.equals(MemorySegment.NULL) && nEdges > 0) {
                    for (int e = 0; e < nEdges; e++) {
                        colTargets[e] = Integer.toUnsignedLong(rel.readNodeId(ctSeg, e));
                    }
                }
            }

            Map<Integer, V11Vector> edgeAttrs = new HashMap<>();
            List<MemorySegment> attrSegs = rel.getAttributeSegments();
            if (attrSegs != null) {
                for (int a = 0; a < attrSegs.size(); a++) {
                    MemorySegment seg = attrSegs.get(a);
                    if (seg == null || seg.equals(MemorySegment.NULL) || seg.byteSize() == 0) continue;
                    // Default heuristic when raw edge attribute segment is exposed without explicit type tag:
                    // match element width against edge count (f32=4B, f64=8B)
                    if (nEdges > 0 && seg.byteSize() == (long) nEdges * 4L) {
                        double[] vals = new double[nEdges];
                        for (int e = 0; e < nEdges; e++) {
                            vals[e] = seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) e * 4L);
                        }
                        edgeAttrs.put(a, V11Vector.ofF64(vals, null));
                    } else if (nEdges > 0 && seg.byteSize() == (long) nEdges * 8L) {
                        double[] vals = new double[nEdges];
                        for (int e = 0; e < nEdges; e++) {
                            vals[e] = seg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) e * 8L);
                        }
                        edgeAttrs.put(a, V11Vector.ofF64(vals, null));
                    }
                }
            }

            relViews[rid] = new V11SnapshotView.RelView(
                    rid, rel.getSrcDomainId(), rel.getTgtDomainId(),
                    rowOffsets, colTargets, edgeAttrs,
                    rel.hasCsr(), rel.hasCsc(), false, false);
        }

        return new V11SnapshotView(domainCards, relViews, nodeAttrs, keyCatalogs, indexes);
    }

    private static V11Vector decodeAttribute(byte attrType, long nodeCount,
                                             MemorySegment dataSeg, MemorySegment validSeg,
                                             MemorySegment offSeg, boolean nullable) {
        if (nodeCount < 0 || nodeCount > Integer.MAX_VALUE) return null;
        int n = (int) nodeCount;
        int baseType = attrType & 0x7F;
        long[] validBits = null;
        if (nullable && validSeg != null && !validSeg.equals(MemorySegment.NULL) && n > 0) {
            int nWords = (n + 63) >>> 6;
            validBits = new long[nWords];
            for (int i = 0; i < n; i++) {
                long byteIdx = i >>> 3;
                if (byteIdx < validSeg.byteSize()) {
                    byte b = validSeg.get(ValueLayout.JAVA_BYTE, byteIdx);
                    if ((b & (1 << (i & 7))) != 0) {
                        validBits[i >>> 6] |= (1L << (i & 63));
                    }
                }
            }
        }
        if (dataSeg == null || dataSeg.equals(MemorySegment.NULL)) return null;
        long bytes = dataSeg.byteSize();

        // Signed integers: 1=i8, 2=i16, 3=i32, 4=i64
        if (baseType >= 1 && baseType <= 4) {
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                long v = 0L;
                if (baseType == 1 && i < bytes) v = dataSeg.get(ValueLayout.JAVA_BYTE, i);
                else if (baseType == 2 && (long) (i + 1) * 2 <= bytes) v = dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) i * 2);
                else if (baseType == 3 && (long) (i + 1) * 4 <= bytes) v = dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i * 4);
                else if (baseType == 4 && (long) (i + 1) * 8 <= bytes) v = dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) i * 8);
                out[i] = v;
            }
            return V11Vector.ofI64(out, validBits);
        }
        // Unsigned integers: 5=u8, 6=u16, 7=u32, 8=u64
        if (baseType >= 5 && baseType <= 8) {
            long[] out = new long[n];
            for (int i = 0; i < n; i++) {
                long v = 0L;
                if (baseType == 5 && i < bytes) v = Byte.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_BYTE, i));
                else if (baseType == 6 && (long) (i + 1) * 2 <= bytes) v = Short.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) i * 2));
                else if (baseType == 7 && (long) (i + 1) * 4 <= bytes) v = Integer.toUnsignedLong(dataSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i * 4));
                else if (baseType == 8 && (long) (i + 1) * 8 <= bytes) v = dataSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) i * 8);
                out[i] = v;
            }
            return V11Vector.ofU64(out, validBits);
        }
        // Floats: 9=f32, 10=f64
        if (baseType == 9 || baseType == 10) {
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                double v = 0.0;
                if (baseType == 9 && (long) (i + 1) * 4 <= bytes) v = dataSeg.get(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) i * 4);
                else if (baseType == 10 && (long) (i + 1) * 8 <= bytes) v = dataSeg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED, (long) i * 8);
                out[i] = v;
            }
            return V11Vector.ofF64(out, validBits);
        }
        // Strings: 11=UTF-8 with offsets segment
        if (baseType == 11 && offSeg != null && !offSeg.equals(MemorySegment.NULL) && offSeg.byteSize() >= (long) (n + 1) * 4L) {
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                if (validBits != null && ((validBits[i >>> 6] >>> (i & 63)) & 1L) == 0L) {
                    out[i] = null;
                    continue;
                }
                long start = Integer.toUnsignedLong(offSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i * 4L));
                long end = Integer.toUnsignedLong(offSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) (i + 1) * 4L));
                if (end >= start && end <= bytes) {
                    byte[] tmp = dataSeg.asSlice(start, end - start).toArray(ValueLayout.JAVA_BYTE);
                    out[i] = new String(tmp, StandardCharsets.UTF_8);
                } else {
                    out[i] = "";
                }
            }
            return V11Vector.ofStr(out);
        }
        return null;
    }
}
