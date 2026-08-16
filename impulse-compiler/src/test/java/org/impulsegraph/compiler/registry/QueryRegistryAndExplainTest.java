package org.impulsegraph.compiler.registry;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;


import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.explain.QueryExplainer;
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
 * Tests for QueryRegistry, Blue/Green pre-flight validation gate, and QueryExplainer.
 */
public class QueryRegistryAndExplainTest {

    @Test
    @DisplayName("QueryRegistry Registration & Blue/Green Validation Gate")
    void testRegistryAndBlueGreenGate() {
        QueryRegistry registry = new QueryRegistry();

        ScmProgram queryAst = ScmProgram.of(
                ScmWalk.forward("userToGroup"),
                ScmCollect.bitset()
        );

        QueryObject q1 = registry.register("user_groups", "MATCH (u:User)-[:userToGroup]->(g:Group)", queryAst);
        assertNotNull(q1);
        assertEquals("user_groups", q1.name());

        try (Arena arena = Arena.ofConfined()) {
            // 1. Candidate Snapshot WITH required relation -> validation passes
            int[] rowOffsets = {0, 1};
            int[] colTargets = {0};
            MemorySegment rowSeg = arena.allocate(8);
            MemorySegment colSeg = arena.allocate(4);
            rowSeg.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
            rowSeg.setAtIndex(ValueLayout.JAVA_INT, 1, 1);
            colSeg.setAtIndex(ValueLayout.JAVA_INT, 0, 0);

            org.impulsegraph.storage.csr.RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 1, 1, rowSeg, colSeg);
            ImpulseGraphSnapshot validSnapshot = new org.impulsegraph.storage.csr.GraphSnapshot(arena, Map.of("userToGroup", rel));

            Map<String, CompiledQuery> boundMap = registry.validateAndBindAll(validSnapshot, arena);
            assertEquals(1, boundMap.size());
            assertTrue(boundMap.containsKey("user_groups"));

            // 2. Candidate Snapshot MISSING required relation -> validation fails fast
            ImpulseGraphSnapshot invalidSnapshot = new org.impulsegraph.storage.csr.GraphSnapshot(arena, Map.of("otherRelation", rel));
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    registry.validateAndBindAll(invalidSnapshot, arena));
            assertTrue(ex.getMessage().contains("Blue/Green Swap Pre-Flight Gate Rejected"));

            // 3. Test Explain output
            String explainReport = QueryExplainer.explain(q1, validSnapshot);
            assertNotNull(explainReport);
            assertTrue(explainReport.contains("IMPULSE QUERY EXPLAIN DIAGNOSTIC REPORT"));
            assertTrue(explainReport.contains("IMPULSE VM BYTECODE DISASSEMBLY"));
            assertTrue(explainReport.contains("OP_CSR_WALK"));
        }
    }
}
