package org.impulsegraph.vm.v11;

import java.util.HashMap;
import java.util.Map;

/**
 * Unified Topology & Attribute View for ImpulseVM v0.11.0 execution (supporting both
 * in-memory compliance fixtures and bound {@code .imps} snapshots).
 */
public final class V11SnapshotView {

    public static final class RelView {
        public final int relId;
        public final int srcDom;
        public final int tgtDom;
        public final int[] rowOffsets;
        public final long[] colTargets;
        public final Map<Integer, V11Vector> edgeAttrs;
        public final boolean hasCsr;
        public final boolean hasCsc;
        public final boolean hasCoo;
        public final boolean hasDense;

        public RelView(int relId, int srcDom, int tgtDom, int[] rowOffsets, long[] colTargets,
                       Map<Integer, V11Vector> edgeAttrs, boolean hasCsr, boolean hasCsc,
                       boolean hasCoo, boolean hasDense) {
            this.relId = relId;
            this.srcDom = srcDom;
            this.tgtDom = tgtDom;
            this.rowOffsets = rowOffsets;
            this.colTargets = colTargets;
            this.edgeAttrs = edgeAttrs != null ? edgeAttrs : new HashMap<>();
            this.hasCsr = hasCsr;
            this.hasCsc = hasCsc;
            this.hasCoo = hasCoo;
            this.hasDense = hasDense;
        }
    }

    public final long[] domainCards;
    public final RelView[] relations;
    public final Map<Long, V11Vector> nodeAttrs; // key = ((long) dom << 32) | (attr & 0xFFFFFFFFL)
    public final Map<Integer, String[]> keyCatalogs;
    public final Map<Long, String> indexes;      // key = ((long) dom << 32) | (attr & 0xFFFFFFFFL)

    public V11SnapshotView(long[] domainCards, RelView[] relations,
                           Map<Long, V11Vector> nodeAttrs,
                           Map<Integer, String[]> keyCatalogs,
                           Map<Long, String> indexes) {
        this.domainCards = domainCards != null ? domainCards : new long[0];
        this.relations = relations != null ? relations : new RelView[0];
        this.nodeAttrs = nodeAttrs != null ? nodeAttrs : new HashMap<>();
        this.keyCatalogs = keyCatalogs != null ? keyCatalogs : new HashMap<>();
        this.indexes = indexes != null ? indexes : new HashMap<>();
    }

    public static long domAttrKey(int dom, int attr) {
        return ((long) dom << 32) | (attr & 0xFFFFFFFFL);
    }

    public V11Vector getNodeAttr(int dom, int attr) {
        return nodeAttrs.get(domAttrKey(dom, attr));
    }

    public boolean hasNodeAttr(int dom, int attr) {
        return nodeAttrs.containsKey(domAttrKey(dom, attr));
    }

    public boolean hasIndex(int dom, int attr) {
        return indexes.containsKey(domAttrKey(dom, attr));
    }
}
