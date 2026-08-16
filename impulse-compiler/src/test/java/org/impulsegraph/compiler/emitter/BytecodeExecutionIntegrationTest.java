package org.impulsegraph.compiler.emitter;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.registry.QueryCompilerEngine;
import org.impulsegraph.compiler.registry.QueryObject;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.vm.ImpulseQueryCompiler.CompiledQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test validating execution of compiled ImpScheme queries.
 */
public class BytecodeExecutionIntegrationTest {

    @Test
    @DisplayName("End-to-End ImpScheme Query Compilation and JIT MethodHandle Execution")
    void testEndToEndQueryExecution() {
        try (Arena arena = Arena.ofConfined()) {
            // Setup a 4-node graph: 0 -> 1, 0 -> 2, 1 -> 3
            int[] rowOffsets = {0, 2, 3, 3, 3};
            int[] colTargets = {1, 2, 3};

            MemorySegment rowSeg = arena.allocate((long) rowOffsets.length * 4);
            for (int i = 0; i < rowOffsets.length; i++) rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsets[i]);

            MemorySegment colSeg = arena.allocate((long) colTargets.length * 4);
            for (int i = 0; i < colTargets.length; i++) colSeg.setAtIndex(ValueLayout.JAVA_INT, i, colTargets[i]);

            RelationSnapshot rel = new RelationSnapshot(arena, 4, 3, rowSeg, colSeg);
            ImpulseGraphSnapshot snapshot = new ImpulseGraphSnapshot(arena, Map.of("follows", rel));

            // AST: (program (csr-walk "follows") (collect-bitset))
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("follows"),
                    ScmCollect.bitset()
            );

            QueryCompilerEngine engine = new QueryCompilerEngine();
            QueryObject query = engine.compileStage1("test_walk", "", ast, CompilerOptions.DEFAULT);

            CompiledQuery compiled = engine.compileStage2(query, snapshot, arena, CompilerOptions.DEFAULT);
            assertNotNull(compiled);

            // Execute starting from node 0
            Object result = compiled.execute(snapshot, 0, arena);
            assertNotNull(result);
            assertInstanceOf(ImpulseBitSet.class, result);

            ImpulseBitSet bitset = (ImpulseBitSet) result;
            assertTrue(bitset.get(1), "Should reach node 1");
            assertTrue(bitset.get(2), "Should reach node 2");
            assertFalse(bitset.get(3), "Should not reach node 3 in 1 hop");
            assertFalse(bitset.get(0), "Should not reach node 0");

            // Plan cache verification: second compileStage2 call should hit cache
            long hitsBefore = engine.planCacheMetrics().cacheHits();
            CompiledQuery cachedPlan = engine.compileStage2(query, snapshot, arena, CompilerOptions.DEFAULT);
            assertSame(compiled, cachedPlan);
            assertEquals(hitsBefore + 1, engine.planCacheMetrics().cacheHits());
        }
    }
}
