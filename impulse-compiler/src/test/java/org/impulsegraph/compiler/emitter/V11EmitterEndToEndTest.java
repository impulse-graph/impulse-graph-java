package org.impulsegraph.compiler.emitter;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.registry.QueryCompilerEngine;
import org.impulsegraph.compiler.registry.QueryObject;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.v11.*;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for M4.1 + M4.2: {@link V11SnapshotAdapter}, {@link V11ImpbContainer},
 * and {@link ImpOpsV11BytecodeEmitter} via {@link QueryCompilerEngine#compileStage2V11}.
 */
class V11EmitterEndToEndTest {

    /** Builds a 5-node directed chain 0->1->2->3->4 in a real off-heap GraphSnapshot. */
    private static GraphSnapshot buildChainSnapshot(Arena arena) {
        // Offsets: [0, 1, 2, 3, 4, 4] (5 nodes, 4 edges)
        MemorySegment off = arena.allocate(ValueLayout.JAVA_INT, 6);
        int[] offsets = {0, 1, 2, 3, 4, 4};
        for (int i = 0; i < offsets.length; i++) off.setAtIndex(ValueLayout.JAVA_INT, i, offsets[i]);

        MemorySegment tgt = arena.allocate(ValueLayout.JAVA_INT, 4);
        int[] targets = {1, 2, 3, 4};
        for (int i = 0; i < targets.length; i++) tgt.setAtIndex(ValueLayout.JAVA_INT, i, targets[i]);

        RelationSnapshot rel = new RelationSnapshot(arena, 5, 4, off, tgt);
        return new GraphSnapshot(arena, Map.of("NEXT", rel));
    }

    @Test
    void testTwoHopAndFixpointAndReduceEndToEndOnV11() {
        try (Arena arena = Arena.ofConfined()) {
            ImpulseGraphSnapshot snap = buildChainSnapshot(arena);
            V11SnapshotView view = V11SnapshotAdapter.from(snap);
            assertEquals(1, view.domainCards.length);
            assertEquals(5L, view.domainCards[0]);

            QueryCompilerEngine engine = new QueryCompilerEngine();

            // 1. 2-hop walk from seed {0} -> {2}
            ScmProgram twoHop = ScmProgram.of(
                    ScmWalk.forward("NEXT"),
                    ScmWalk.forward("NEXT"),
                    ScmCollect.list()
            );
            QueryObject qOn = engine.compileStage1("twoHop", "", twoHop, CompilerOptions.DEFAULT);
            var progOn = engine.compileStage2V11(qOn, snap, CompilerOptions.DEFAULT);
            var progOff = engine.compileStage2V11(qOn, snap,
                    CompilerOptions.builder().withDisabledPassesMask(CompilerOptions.OPT_ALL_DISABLED).build());

            // Verify .impb roundtrip
            V11ImpbContainer decoded = V11ImpbContainer.decode(progOn.impbBytes());
            assertArrayEquals(progOn.codeWords(), decoded.codeWords);

            V11Workspace wsOn = new V11Workspace(16);
            wsOn.params.put(0, new V11Workspace.ParamFrontier(0, new long[]{0L}));
            assertEquals(V11IsaTables.IMPULSE_VM_OK, progOn.executeInterpreted(view, wsOn, 100_000L));
            V11Vector outOn = wsOn.outputs.get(0);
            assertNotNull(outOn);
            assertEquals(1, outOn.length);
            assertEquals(2L, outOn.i64Data[0]);

            // Verify opt-ON == opt-OFF
            V11Workspace wsOff = new V11Workspace(16);
            wsOff.params.put(0, new V11Workspace.ParamFrontier(0, new long[]{0L}));
            assertEquals(V11IsaTables.IMPULSE_VM_OK, progOff.executeInterpreted(view, wsOff, 100_000L));
            V11Vector outOff = wsOff.outputs.get(0);
            assertNotNull(outOff);
            assertEquals(outOn.length, outOff.length);
            assertEquals(outOn.i64Data[0], outOff.i64Data[0]);

            // 2. Fixpoint Kleene* from seed {1} -> {1, 2, 3, 4} + COUNT reduction -> scalar 4
            ScmProgram fixCount = ScmProgram.of(
                    new ScmFixpoint(ScmProgram.of(ScmWalk.forward("NEXT"))),
                    new ScmReduce(ScmReduce.Op.COUNT),
                    ScmCollect.bitset()
            );
            var progFix = ImpOpsV11BytecodeEmitter.emit(fixCount, snap);
            V11Workspace wsFix = new V11Workspace(16);
            wsFix.params.put(0, new V11Workspace.ParamFrontier(0, new long[]{1L}));
            assertEquals(V11IsaTables.IMPULSE_VM_OK, progFix.executeInterpreted(view, wsFix, 100_000L));
            V11Vector outFix = wsFix.outputs.get(0);
            assertNotNull(outFix);
            assertEquals(1, outFix.length);
            assertEquals(4L, outFix.i64Data[0]);
        }
    }

    @Test
    void testMultiLaneQuorumEndToEndOnV11() {
        try (Arena arena = Arena.ofConfined()) {
            // 4-node graph: 0->2, 1->2, 1->3
            MemorySegment off = arena.allocate(ValueLayout.JAVA_INT, 5);
            int[] offsets = {0, 1, 3, 3, 3};
            for (int i = 0; i < offsets.length; i++) off.setAtIndex(ValueLayout.JAVA_INT, i, offsets[i]);
            MemorySegment tgt = arena.allocate(ValueLayout.JAVA_INT, 3);
            int[] targets = {2, 2, 3};
            for (int i = 0; i < targets.length; i++) tgt.setAtIndex(ValueLayout.JAVA_INT, i, targets[i]);

            RelationSnapshot rel = new RelationSnapshot(arena, 4, 3, off, tgt);
            ImpulseGraphSnapshot snap = new GraphSnapshot(arena, Map.of("FOLLOWS", rel));
            V11SnapshotView view = V11SnapshotAdapter.from(snap);

            ScmProgram quorumProg = ScmProgram.of(
                    new ScmLaneInitSeeds(List.of(0L, 1L), 0),
                    new ScmLaneWalk("FOLLOWS", -1, ScmWalk.Direction.FORWARD_CSR, false),
                    new ScmLaneFilterQuorum(2, false),
                    ScmCollect.list()
            );
            var compiled = ImpOpsV11BytecodeEmitter.emit(quorumProg, snap);
            assertFalse(compiled.container().constPool.isEmpty(), "inline seeds should be stored in .impb const_pool");

            V11Workspace ws = new V11Workspace(16);
            assertEquals(V11IsaTables.IMPULSE_VM_OK, compiled.executeInterpreted(view, ws, 100_000L));
            V11Vector out = ws.outputs.get(0);
            assertNotNull(out);
            assertEquals(1, out.length);
            assertEquals(2L, out.i64Data[0]);
        }
    }
}
