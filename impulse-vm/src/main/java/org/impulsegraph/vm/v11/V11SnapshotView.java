package org.impulsegraph.vm.v11;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * Unified Topology & Attribute View for ImpulseVM v0.11.0 execution (supporting both
 * zero-copy off-heap {@code .imps} {@link MemorySegment} views and in-memory compliance fixtures).
 */
public final class V11SnapshotView {

    public static final class RelView {
        public final int relId;
        public final int srcDom;
        public final int tgtDom;
        // In-memory compliance fixture arrays (null when backed by off-heap MemorySegments)
        public final int[] rowOffsets;
        public final long[] colTargets;
        public volatile int[] cscRowOffsets;
        public volatile int[] cscColSources;
        // Zero-copy off-heap .imps MemorySegments (null for in-memory fixtures)
        public final MemorySegment csrRowOffsetsSeg;
        public final MemorySegment csrColTargetsSeg;
        public final MemorySegment cscRowOffsetsSeg;
        public final MemorySegment cscColSourcesSeg;
        public final byte nodeIdWidth;
        public final byte edgeIdxWidth;
        public final int csrNodeCount;
        public final int cscNodeCount;
        public final int edgeCount;

        public final Map<Integer, V11Vector> edgeAttrs;
        public final boolean hasCsr;
        public final boolean hasCsc;
        public final boolean hasCoo;
        public final boolean hasDense;

        public RelView(int relId, int srcDom, int tgtDom, int[] rowOffsets, long[] colTargets,
                       Map<Integer, V11Vector> edgeAttrs, boolean hasCsr, boolean hasCsc,
                       boolean hasCoo, boolean hasDense) {
            this(relId, srcDom, tgtDom, rowOffsets, colTargets, null, null,
                    edgeAttrs, hasCsr, hasCsc, hasCoo, hasDense);
        }

        public RelView(int relId, int srcDom, int tgtDom, int[] rowOffsets, long[] colTargets,
                       int[] cscRowOffsets, int[] cscColSources,
                       Map<Integer, V11Vector> edgeAttrs, boolean hasCsr, boolean hasCsc,
                       boolean hasCoo, boolean hasDense) {
            this.relId = relId;
            this.srcDom = srcDom;
            this.tgtDom = tgtDom;
            this.rowOffsets = rowOffsets;
            this.colTargets = colTargets;
            this.cscRowOffsets = cscRowOffsets;
            this.cscColSources = cscColSources;
            this.csrRowOffsetsSeg = null;
            this.csrColTargetsSeg = null;
            this.cscRowOffsetsSeg = null;
            this.cscColSourcesSeg = null;
            this.nodeIdWidth = 4;
            this.edgeIdxWidth = 4;
            this.csrNodeCount = rowOffsets != null && rowOffsets.length > 0 ? rowOffsets.length - 1 : 0;
            this.cscNodeCount = cscRowOffsets != null && cscRowOffsets.length > 0 ? cscRowOffsets.length - 1 : 0;
            this.edgeCount = colTargets != null ? colTargets.length : 0;
            this.edgeAttrs = edgeAttrs != null ? edgeAttrs : new HashMap<>();
            this.hasCsr = hasCsr;
            this.hasCsc = hasCsc;
            this.hasCoo = hasCoo;
            this.hasDense = hasDense;
        }

        /** Zero-copy off-heap constructor wrapping mmap'd .imps CSR/CSC MemorySegments directly. */
        public RelView(int relId, int srcDom, int tgtDom,
                       MemorySegment csrRowOffsetsSeg, MemorySegment csrColTargetsSeg,
                       MemorySegment cscRowOffsetsSeg, MemorySegment cscColSourcesSeg,
                       byte nodeIdWidth, byte edgeIdxWidth,
                       int csrNodeCount, int cscNodeCount, int edgeCount,
                       Map<Integer, V11Vector> edgeAttrs, boolean hasCsr, boolean hasCsc) {
            this.relId = relId;
            this.srcDom = srcDom;
            this.tgtDom = tgtDom;
            this.rowOffsets = null;
            this.colTargets = null;
            this.cscRowOffsets = null;
            this.cscColSources = null;
            this.csrRowOffsetsSeg = csrRowOffsetsSeg;
            this.csrColTargetsSeg = csrColTargetsSeg;
            this.cscRowOffsetsSeg = cscRowOffsetsSeg;
            this.cscColSourcesSeg = cscColSourcesSeg;
            this.nodeIdWidth = nodeIdWidth > 0 ? nodeIdWidth : 4;
            this.edgeIdxWidth = edgeIdxWidth > 0 ? edgeIdxWidth : 4;
            this.csrNodeCount = csrNodeCount;
            this.cscNodeCount = cscNodeCount;
            this.edgeCount = edgeCount;
            this.edgeAttrs = edgeAttrs != null ? edgeAttrs : new HashMap<>();
            this.hasCsr = hasCsr;
            this.hasCsc = hasCsc;
            this.hasCoo = false;
            this.hasDense = false;
        }

        public boolean isOffHeap() {
            return csrRowOffsetsSeg != null;
        }

        public boolean hasOffHeapCsc() {
            return cscRowOffsetsSeg != null && cscColSourcesSeg != null;
        }

        public int csrRowCount() {
            return csrNodeCount;
        }

        public int cscRowCount() {
            if (cscRowOffsetsSeg != null) return cscNodeCount;
            return cscRowOffsets != null && cscRowOffsets.length > 0 ? cscRowOffsets.length - 1 : 0;
        }

        public int edgeCount() {
            return edgeCount;
        }

        public int csrRowOffset(int u) {
            if (csrRowOffsetsSeg != null) {
                return edgeIdxWidth == 8
                        ? (int) csrRowOffsetsSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) u << 3)
                        : csrRowOffsetsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
            }
            return rowOffsets[u];
        }

        public int csrColTarget(int e) {
            if (csrColTargetsSeg != null) {
                if (nodeIdWidth == 4) {
                    return csrColTargetsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
                } else if (nodeIdWidth == 2) {
                    return Short.toUnsignedInt(csrColTargetsSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) e << 1));
                } else {
                    return (int) csrColTargetsSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) e << 3);
                }
            }
            return (int) colTargets[e];
        }

        public int cscRowOffset(int u) {
            if (cscRowOffsetsSeg != null) {
                return edgeIdxWidth == 8
                        ? (int) cscRowOffsetsSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) u << 3)
                        : cscRowOffsetsSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) u << 2);
            }
            return cscRowOffsets[u];
        }

        public int cscColSource(int e) {
            if (cscColSourcesSeg != null) {
                if (nodeIdWidth == 4) {
                    return cscColSourcesSeg.get(ValueLayout.JAVA_INT_UNALIGNED, (long) e << 2);
                } else if (nodeIdWidth == 2) {
                    return Short.toUnsignedInt(cscColSourcesSeg.get(ValueLayout.JAVA_SHORT_UNALIGNED, (long) e << 1));
                } else {
                    return (int) cscColSourcesSeg.get(ValueLayout.JAVA_LONG_UNALIGNED, (long) e << 3);
                }
            }
            return cscColSources[e];
        }

        /** Ensures flat CSC is available for reverse walks (no-op when off-heap .imps CSC is present). */
        public void ensureCsc(int tgtCard) {
            if (hasOffHeapCsc() || (cscRowOffsets != null && cscColSources != null)) return;
            synchronized (this) {
                if (hasOffHeapCsc() || (cscRowOffsets != null && cscColSources != null)) return;
                int nTgt = Math.max(0, tgtCard);
                int nSrc = csrRowCount();
                int nEdges = edgeCount();
                int[] deg = new int[nTgt + 1];
                for (int u = 0; u < nSrc; u++) {
                    int s = csrRowOffset(u);
                    int end = csrRowOffset(u + 1);
                    for (int e = s; e < end; e++) {
                        int v = csrColTarget(e);
                        if (v >= 0 && v < nTgt) deg[v + 1]++;
                    }
                }
                for (int v = 0; v < nTgt; v++) deg[v + 1] += deg[v];
                int[] cur = deg.clone();
                int[] srcs = new int[nEdges];
                for (int u = 0; u < nSrc; u++) {
                    int s = csrRowOffset(u);
                    int end = csrRowOffset(u + 1);
                    for (int e = s; e < end; e++) {
                        int v = csrColTarget(e);
                        if (v >= 0 && v < nTgt) srcs[cur[v]++] = u;
                    }
                }
                this.cscColSources = srcs;
                this.cscRowOffsets = deg;
            }
        }
    }

    public final long[] domainCards;
    public final RelView[] relations;
    public final Map<Long, V11Vector> nodeAttrs; // key = ((long) dom << 32) | (attr & 0xFFFFFFFFL)
    public final Map<Integer, String[]> keyCatalogs;
    public final Map<Long, String> indexes;      // key = ((long) dom << 32) | (attr & 0xFFFFFFFFL)
    private final BiFunction<Integer, Integer, V11Vector> lazyNodeAttrLoader;
    private final IntFunction<String[]> lazyKeyCatalogLoader;

    public V11SnapshotView(long[] domainCards, RelView[] relations,
                           Map<Long, V11Vector> nodeAttrs,
                           Map<Integer, String[]> keyCatalogs,
                           Map<Long, String> indexes) {
        this(domainCards, relations, nodeAttrs, keyCatalogs, indexes, null, null);
    }

    public V11SnapshotView(long[] domainCards, RelView[] relations,
                           Map<Long, V11Vector> nodeAttrs,
                           Map<Integer, String[]> keyCatalogs,
                           Map<Long, String> indexes,
                           BiFunction<Integer, Integer, V11Vector> lazyNodeAttrLoader,
                           IntFunction<String[]> lazyKeyCatalogLoader) {
        this.domainCards = domainCards != null ? domainCards : new long[0];
        this.relations = relations != null ? relations : new RelView[0];
        this.nodeAttrs = nodeAttrs != null ? nodeAttrs : new ConcurrentHashMap<>();
        this.keyCatalogs = keyCatalogs != null ? keyCatalogs : new ConcurrentHashMap<>();
        this.indexes = indexes != null ? indexes : new HashMap<>();
        this.lazyNodeAttrLoader = lazyNodeAttrLoader;
        this.lazyKeyCatalogLoader = lazyKeyCatalogLoader;
    }

    public static long domAttrKey(int dom, int attr) {
        return ((long) dom << 32) | (attr & 0xFFFFFFFFL);
    }

    public V11Vector getNodeAttr(int dom, int attr) {
        long key = domAttrKey(dom, attr);
        V11Vector cached = nodeAttrs.get(key);
        if (cached != null || lazyNodeAttrLoader == null) return cached;
        V11Vector loaded = lazyNodeAttrLoader.apply(dom, attr);
        if (loaded != null) {
            nodeAttrs.put(key, loaded);
        }
        return loaded;
    }

    public boolean hasNodeAttr(int dom, int attr) {
        return getNodeAttr(dom, attr) != null;
    }

    public String[] getKeyCatalog(int dom) {
        String[] cached = keyCatalogs.get(dom);
        if (cached != null || lazyKeyCatalogLoader == null) return cached;
        String[] loaded = lazyKeyCatalogLoader.apply(dom);
        if (loaded != null) {
            keyCatalogs.put(dom, loaded);
        }
        return loaded;
    }

    public boolean hasKeyCatalog(int dom) {
        return getKeyCatalog(dom) != null;
    }

    public boolean hasIndex(int dom, int attr) {
        return indexes.containsKey(domAttrKey(dom, attr));
    }
}
