package org.impulsegraph.vm.v11;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

/**
 * Test-only off-heap fixture builder for ImpulseVM v0.11.0 compliance and unit tests.
 *
 * <p>Packs in-memory test CSR arrays (and optionally CSC arrays when {@code buildCsc} is
 * explicitly requested) into off-heap {@link MemorySegment}s so production VM opcodes
 * maintain a single zero-copy off-heap execution path with zero topology synthesis.
 */
public final class V11OffHeapFixtureBuilder {

    private static final Arena SHARED_TEST_ARENA = Arena.ofAuto();

    private V11OffHeapFixtureBuilder() {}

    public static V11SnapshotView.RelView buildRel(
            int relId, int srcDom, int tgtDom, int tgtDomCard,
            int[] rowOff, long[] colTgt,
            Map<Integer, V11Vector> edgeAttrs,
            boolean hasCsr, boolean hasCsc, boolean hasCoo, boolean hasDense,
            boolean buildCscSegments) {
        return buildRel(SHARED_TEST_ARENA, relId, srcDom, tgtDom, tgtDomCard,
                rowOff, colTgt, edgeAttrs, hasCsr, hasCsc, hasCoo, hasDense, buildCscSegments);
    }

    public static V11SnapshotView.RelView buildRel(
            Arena arena,
            int relId, int srcDom, int tgtDom, int tgtDomCard,
            int[] rowOff, long[] colTgt,
            Map<Integer, V11Vector> edgeAttrs,
            boolean hasCsr, boolean hasCsc, boolean hasCoo, boolean hasDense,
            boolean buildCscSegments) {
        int nSrc = rowOff != null && rowOff.length > 0 ? rowOff.length - 1 : 0;
        int nEdges = colTgt != null ? colTgt.length : 0;

        MemorySegment csrOffSeg = arena.allocate((long) (nSrc + 1) * 4L, 8);
        for (int i = 0; i <= nSrc; i++) {
            csrOffSeg.set(ValueLayout.JAVA_INT_UNALIGNED, (long) i * 4L, rowOff[i]);
        }
        MemorySegment csrColSeg = arena.allocate(Math.max(4L, (long) nEdges * 4L), 8);
        for (int e = 0; e < nEdges; e++) {
            csrColSeg.set(ValueLayout.JAVA_INT_UNALIGNED, (long) e * 4L, (int) colTgt[e]);
        }

        MemorySegment cscOffSeg = null;
        MemorySegment cscSrcSeg = null;
        MemorySegment cscEidSeg = null;
        int cscNodeCount = 0;
        if (buildCscSegments) {
            int nTgt = Math.max(0, tgtDomCard);
            cscNodeCount = nTgt;
            int[] deg = new int[nTgt + 1];
            for (int u = 0; u < nSrc; u++) {
                int s = rowOff[u];
                int end = rowOff[u + 1];
                for (int e = s; e < end; e++) {
                    int v = (int) colTgt[e];
                    if (v >= 0 && v < nTgt) deg[v + 1]++;
                }
            }
            for (int v = 0; v < nTgt; v++) deg[v + 1] += deg[v];
            int[] cur = deg.clone();
            int[] srcs = new int[nEdges];
            int[] eids = new int[nEdges];
            for (int u = 0; u < nSrc; u++) {
                int s = rowOff[u];
                int end = rowOff[u + 1];
                for (int e = s; e < end; e++) {
                    int v = (int) colTgt[e];
                    if (v >= 0 && v < nTgt) {
                        int pos = cur[v]++;
                        srcs[pos] = u;
                        eids[pos] = e;
                    }
                }
            }
            cscOffSeg = arena.allocate((long) (nTgt + 1) * 4L, 8);
            for (int i = 0; i <= nTgt; i++) {
                cscOffSeg.set(ValueLayout.JAVA_INT_UNALIGNED, (long) i * 4L, deg[i]);
            }
            cscSrcSeg = arena.allocate(Math.max(4L, (long) nEdges * 4L), 8);
            cscEidSeg = arena.allocate(Math.max(4L, (long) nEdges * 4L), 8);
            for (int e = 0; e < nEdges; e++) {
                cscSrcSeg.set(ValueLayout.JAVA_INT_UNALIGNED, (long) e * 4L, srcs[e]);
                cscEidSeg.set(ValueLayout.JAVA_INT_UNALIGNED, (long) e * 4L, eids[e]);
            }
        }

        return new V11SnapshotView.RelView(
                relId, srcDom, tgtDom,
                csrOffSeg, csrColSeg, cscOffSeg, cscSrcSeg, cscEidSeg,
                (byte) 4, (byte) 4,
                nSrc, cscNodeCount, nEdges,
                edgeAttrs, null, hasCsr, hasCsc, hasCoo, hasDense);
    }
}
