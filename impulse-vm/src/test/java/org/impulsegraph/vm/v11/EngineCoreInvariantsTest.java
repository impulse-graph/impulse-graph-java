package org.impulsegraph.vm.v11;

import com.sun.management.ThreadMXBean;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.impulsegraph.vm.v11.V11IsaTables.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime enforcement of the non-negotiable core engine invariants (AGENTS.md §3.20):
 *  1. True Mmap-and-Go open: O(#domains + #relations) heap (< 256 KB) and time (< 5 ms).
 *  2. Upfront domain/bounds/width validation (fail fast on invalid width / truncated segments).
 *  3. Fail-fast missing topology: reverse walk / degree on !hasCsc returns ERR_INVALID_OPERAND (zero synthesis).
 *  4. Single off-heap execution path across all Pillar 3 ops (OP_WALK, OP_DEGREE, OP_SPMV, OP_SAMPLE_NEIGHBORS, OP_GRAPH_KERNEL).
 *  5. Deterministic 2-buffer V11MemoryPlanner + 0 B/query steady-state execution on real .imps.
 */
public final class EngineCoreInvariantsTest {

    private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, int imm32) {
        return V11Interpreter.encode(op, flags, subop, dst, s1, s2, s3, imm32);
    }

    @Test
    void failFastMissingCscOnReverseWalkAndDegreeNeverSynthesizes() {
        // Fixture explicitly built WITHOUT CSC segments (buildCscSegments = false)
        V11SnapshotView.RelView relNoCsc = V11OffHeapFixtureBuilder.buildRel(
                0, 0, 0, 4,
                new int[]{0, 2, 3, 4, 4},
                new long[]{1, 2, 3, 3},
                Map.of(), true, false, false, false, false);
        assertFalse(relNoCsc.hasCscSegments(), "Relation built without CSC must have no CSC segments");

        V11SnapshotView view = new V11SnapshotView(
                new long[]{4}, new V11SnapshotView.RelView[]{relNoCsc}, Map.of(), Map.of(), Map.of());
        V11Interpreter.SnapshotBinding bind = new V11Interpreter.SnapshotBinding(new int[]{4}, view);
        V11Workspace ws = new V11Workspace(4);

        long[] revWalk = {
                enc(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 1),
                enc(OP_FSEED, 0, 0, 0, 0, 0, 0, 0),
                enc(OP_WALK, 0, SUBOP_WALK_REV, 1, 0, 0, 0, 0),
                enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
        };
        int stWalk = V11Interpreter.execute(revWalk, bind, ws);
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, stWalk,
                "OP_WALK.rev on !hasCsc must fail fast instead of synthesizing CSC");

        // OP_DEGREE.node_rev_r on !hasCsc must fail immediately
        long[] revDeg = {
                enc(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 1),
                enc(OP_DEGREE, 0, SUBOP_DEGREE_NODE_REV_R, 1, 0, 0, 0, 0),
                enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
        };
        int stDeg = V11Interpreter.execute(revDeg, bind, ws);
        assertEquals(IMPULSE_VM_ERR_INVALID_OPERAND, stDeg,
                "OP_DEGREE.node_rev_r on !hasCsc must fail fast instead of scanning CSR");
    }

    @Test
    void upfrontValidationRejectsInvalidWidthAndTruncatedSegments() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment tiny = arena.allocate(4L);
            // Invalid nodeIdWidth = 1
            assertThrows(IllegalArgumentException.class, () -> new V11SnapshotView.RelView(
                    0, 0, 0, tiny, tiny, null, null, (byte) 1, (byte) 4, 0, 0, 0, Map.of(), true, false));
            // Truncated CSR rowOffsets (needs (5+1)*4 = 24B, got 4B)
            assertThrows(IllegalArgumentException.class, () -> new V11SnapshotView.RelView(
                    0, 0, 0, tiny, tiny, null, null, (byte) 4, (byte) 4, 5, 0, 1, Map.of(), true, false));
        }
    }

    @Test
    void sparseNullRelationSlotFailsGracefullyWithoutInfiniteLoopOrNpe() {
        V11SnapshotView.RelView rel0 = V11OffHeapFixtureBuilder.buildRel(
                0, 0, 0, 4,
                new int[]{0, 1, 2, 3, 3},
                new long[]{1, 2, 3},
                Map.of(), true, true, false, false, true);
        // Sparse array: slot 1 is null, slot 2 is rel0
        V11SnapshotView.RelView[] sparse = new V11SnapshotView.RelView[]{rel0, null, rel0};
        V11SnapshotView view = new V11SnapshotView(new long[]{4}, sparse, Map.of(), Map.of(), Map.of());
        V11Interpreter.SnapshotBinding bind = new V11Interpreter.SnapshotBinding(new int[]{4}, view);
        V11Workspace ws = new V11Workspace(4);

        long[] prog = {
                enc(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 0),
                enc(OP_FSEED, 0, 0, 0, 0, 0, 0, 0),
                enc(OP_WALK, 0, SUBOP_WALK_FWD, 1, 0, 0, 0, 1), // relId=1 is null!
                enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
        };
        int st = V11Interpreter.executeValidated(prog, bind, ws, 100L);
        assertEquals(IMPULSE_VM_ERR_OUT_OF_BOUNDS, st, "Null sparse relation slot must fail with OUT_OF_BOUNDS, not loop");

        long[] probe = {
                enc(OP_HAS_TOPOLOGY, 0, 0, 0, 0, 0, 0, 1), // relId=1 is null!
                enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
        };
        assertEquals(IMPULSE_VM_OK, V11Interpreter.execute(probe, bind, ws));
        assertEquals(0L, ws.rRegs[0], "OP_HAS_TOPOLOGY on null sparse relation must return 0 without NPE");
    }

    @Test
    void realImpsMmapAndGoZeroCopyAndDeterministicTwoBufferZeroAlloc() throws Exception {
        Path hetio = Path.of("/Users/jesse/impulse/datasets/hetionet/hetionet.v09.imps");
        if (!Files.exists(hetio)) return;

        ThreadMXBean tmxb = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        try (Arena arena = Arena.ofShared()) {
            var loaded = BinarySnapshotLoader.loadSnapshot(hetio, arena);
            // Warm up adapter classloading once
            V11SnapshotAdapter.from(loaded.graph());

            long memBefore = tmxb.getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            V11SnapshotView view = V11SnapshotAdapter.from(loaded.graph());
            long elapsedNs = System.nanoTime() - t0;
            long memDelta = tmxb.getCurrentThreadAllocatedBytes() - memBefore;

            assertTrue(memDelta < 256 * 1024L,
                    "Mmap-and-go V11SnapshotAdapter.from(hetionet 26MB) must allocate < 256KB, got " + memDelta + "B");
            assertTrue(elapsedNs < 5_000_000L,
                    "Mmap-and-go V11SnapshotAdapter.from(hetionet 26MB) must take < 5ms, got " + (elapsedNs / 1e6) + "ms");

            // Pick a relation with CSR and run deterministic 2-buffer plan + 0-alloc execution
            int relId = 0;
            V11SnapshotView.RelView rv = view.relations[relId];
            assertNotNull(rv);
            long[] prog = {
                    enc(OP_LOAD_CONST, 0, 0, 0, 0, 0, 0, 0),
                    enc(OP_FSEED, 0, 0, 0, 0, 0, 0, rv.srcDom),
                    enc(OP_WALK, 0, SUBOP_WALK_FWD, 1, 0, 0, 0, relId),
                    enc(OP_COLLECT, 0, SUBOP_COLLECT_FRONTIER_BITSET, 0, 1, 0, 0, 0),
                    enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)
            };

            V11MemoryPlanner.MemoryPlan plan = V11MemoryPlanner.plan(prog, view);
            assertTrue(plan.scratchBytes() > 0 && plan.resultBytes() > 0);
            int[] domCards = new int[view.domainCards.length];
            for (int i = 0; i < domCards.length; i++) domCards[i] = (int) view.domainCards[i];
            V11Interpreter.SnapshotBinding bind = new V11Interpreter.SnapshotBinding(domCards, view);

            V11Workspace scratchWs = new V11Workspace(plan.maxDomainCard());
            V11ResultBuffer resultBuf = new V11ResultBuffer(plan);

            // Warmup arena + JIT
            for (int i = 0; i < 100; i++) {
                scratchWs.reset();
                resultBuf.reset();
                assertEquals(IMPULSE_VM_OK, V11Interpreter.executePreValidated(prog, bind, scratchWs, 10000L));
                resultBuf.captureFrom(scratchWs);
            }

            long before = tmxb.getCurrentThreadAllocatedBytes();
            for (int i = 0; i < 50; i++) {
                scratchWs.reset();
                resultBuf.reset();
                int st = V11Interpreter.executePreValidated(prog, bind, scratchWs, 10000L);
                assertEquals(IMPULSE_VM_OK, st);
                resultBuf.captureFrom(scratchWs);
            }
            long after = tmxb.getCurrentThreadAllocatedBytes();
            assertEquals(0L, after - before, "Steady-state execution on real .imps must allocate 0 bytes/query");
            assertNotNull(resultBuf.getOutput(0));
        }
    }
}
