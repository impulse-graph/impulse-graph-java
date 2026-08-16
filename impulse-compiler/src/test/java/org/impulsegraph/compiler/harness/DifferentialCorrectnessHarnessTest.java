package org.impulsegraph.compiler.harness;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test Harness for Universal Algebraic Compiler Optimizations.
 * Verifies mathematical equivalence and correctness of algebraic passes:
 * - Zone Map Pruning & Dead Code Elimination
 * - Monotonic Homomorphism Pushdown (max(log(x)) -> log(max(x)))
 * - Injective Deduplication Bypass
 * - Virtual Super-Relation Coproduct Decomposition
 */
public class DifferentialCorrectnessHarnessTest {

    @Test
    @DisplayName("Verification 1: Zone Map Pruning (u.age > 250 folds to false & prunes traversal)")
    void testZoneMapPruning() {
        // Mock AttributeStatistics for "age": Min=18, Max=114
        AttributeStatistics ageStats = new AttributeStatistics(
                "age", 18, 114, 18.0, 114.0, "", "", 0, 96,
                AttributeStatistics.Monotonicity.MONO_NONE, false
        );

        GraphStatistics graphStats = new GraphStatistics();
        graphStats.putAttributeStatistics("age", ageStats);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rows = arena.allocate(8L);
            MemorySegment cols = arena.allocate(4L);
            RelationSnapshot rel = new RelationSnapshot(arena, 1, 1, rows, cols);
            ImpulseGraphSnapshot snapshot = new ImpulseGraphSnapshot(arena, Map.of("userToGroup", rel));
            snapshot.getGraphStatistics().putAttributeStatistics("age", ageStats);

            // Query with impossible predicate: node.age > 250
            CelAstNode parsedCel = CelParser.parse("node.age > 250");
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("userToGroup", new ScmCelExpr("node.age > 250", parsedCel)),
                    ScmCollect.bitset()
            );

            CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
            PassTracer tracer = new PassTracer(opts);
            CompilerContext ctx = new CompilerContext(snapshot, opts, tracer);

            // Step 1: Infer types and bounds
            ImpScmNode annotated = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
            assertNotNull(annotated);

            // Step 2: Prune based on zone maps
            ImpScmNode pruned = ctx.executePass(ZoneMapPruningPass.INSTANCE, annotated);
            assertNotNull(pruned);

            assertInstanceOf(ScmProgram.class, pruned);
            ScmProgram prunedProg = (ScmProgram) pruned;

            // The impossible walk branch should have been pruned out of the pipeline!
            assertTrue(prunedProg.steps().isEmpty() || prunedProg.steps().size() == 1);
        }
    }

    @Test
    @DisplayName("Verification 2: Monotonic Homomorphism Pushdown (max(log(v)) -> log(max(v)))")
    void testMonotonicHomomorphismPushdown() {
        CelAstNode cel = CelParser.parse("max(log(v))");

        GraphStatistics stats = new GraphStatistics();
        CelAstNode annotated = AlgebraicTypeInferencePass.INSTANCE.inferCel(cel, stats);

        // Apply Monotonic Homomorphism Pushdown
        CelAstNode optimized = MonotonicHomomorphismPass.INSTANCE.optimizeCel(annotated);

        assertNotNull(optimized);
        assertEquals(CelAstNode.Kind.FUNCTION_CALL, optimized.kind());
        assertEquals("log", optimized.text()); // Outer function is now log!

        assertEquals(1, optimized.children().size());
        CelAstNode inner = optimized.children().get(0);
        assertEquals(CelAstNode.Kind.FUNCTION_CALL, inner.kind());
        assertEquals("max", inner.text()); // Inner aggregator is now max!
        assertEquals("v", inner.children().get(0).text());
    }

    @Test
    @DisplayName("Verification 3: Inverting Monotonic Meet-Join Pushdown (max(-v) -> -min(v))")
    void testInvertingMonotonicPushdown() {
        CelAstNode cel = CelParser.parse("max(-v)");

        GraphStatistics stats = new GraphStatistics();
        CelAstNode annotated = AlgebraicTypeInferencePass.INSTANCE.inferCel(cel, stats);

        CelAstNode optimized = MonotonicHomomorphismPass.INSTANCE.optimizeCel(annotated);

        assertNotNull(optimized);
        assertEquals(CelAstNode.Kind.UNARY_OP, optimized.kind());
        assertEquals("-", optimized.text()); // Outer is unary minus

        CelAstNode inner = optimized.children().get(0);
        assertEquals(CelAstNode.Kind.FUNCTION_CALL, inner.kind());
        assertEquals("min", inner.text()); // Inner is min!
    }

    @Test
    @DisplayName("Verification 4: Injective Path Deduplication Bypass (Skip DISTINCT)")
    void testInjectiveDeduplicationBypass() {
        try (Arena arena = Arena.ofConfined()) {
            // Build an injective relation (One-to-Many: InDegree <= 1)
            int[] rowOffsets = {0, 2, 4};
            int[] colTargets = {0, 1, 2, 3}; // Distinct target node sets!

            MemorySegment rowSeg = arena.allocate((long) rowOffsets.length * ValueLayout.JAVA_INT.byteSize());
            for (int i = 0; i < rowOffsets.length; i++) rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsets[i]);

            MemorySegment colSeg = arena.allocate((long) colTargets.length * ValueLayout.JAVA_INT.byteSize());
            for (int i = 0; i < colTargets.length; i++) colSeg.setAtIndex(ValueLayout.JAVA_INT, i, colTargets[i]);

            RelationSnapshot rel = new RelationSnapshot(arena, 2, 4, rowSeg, colSeg);
            ImpulseGraphSnapshot snapshot = new ImpulseGraphSnapshot(arena, Map.of("parentToChildren", rel));

            assertTrue(rel.getStatistics().isInjective(), "Relation must be classified as Injective");

            // Query with distinct collect
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("parentToChildren"),
                    ScmCollect.distinct()
            );

            CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
            CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

            ImpScmNode opt = ctx.executePass(InjectiveDeduplicationBypassPass.INSTANCE, ast);

            assertNotNull(opt);
            assertInstanceOf(ScmProgram.class, opt);
            ScmProgram optProg = (ScmProgram) opt;

            assertEquals(2, optProg.steps().size());
            assertInstanceOf(ScmCollect.class, optProg.steps().get(1));
            ScmCollect col = (ScmCollect) optProg.steps().get(1);

            // Verified: DISTINCT was safely rewritten into bitset streaming collect!
            assertEquals(ScmCollect.Format.BITSET, col.format());
        }
    }

    @Test
    @DisplayName("Verification 5: Virtual Relation Coproduct Decomposition")
    void testVirtualRelationDecomposition() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rows = arena.allocate(8L);
            MemorySegment cols = arena.allocate(4L);

            RelationSnapshot secFruit = new RelationSnapshot(arena, 1, 1, rows, cols);
            RelationSnapshot secBread = new RelationSnapshot(arena, 1, 1, rows, cols);

            ImpulseGraphSnapshot snapshot = new ImpulseGraphSnapshot(arena, Map.of(
                    "in_section_fruit", secFruit,
                    "in_section_bread", secBread
            ));

            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("in_section"),
                    ScmCollect.bitset()
            );

            CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
            CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

            ImpScmNode decomp = ctx.executePass(VirtualRelationDecompositionPass.INSTANCE, ast);

            assertNotNull(decomp);
            assertInstanceOf(ScmProgram.class, decomp);
            ScmProgram prog = (ScmProgram) decomp;

            assertInstanceOf(ScmWalk.class, prog.steps().get(0));
            ScmWalk vrWalk = (ScmWalk) prog.steps().get(0);

            // Verified: Decomposed into 2 constituent walks (bread and fruit)
            assertEquals(2, vrWalk.subSteps().size());
            assertEquals("in_section_bread", ((ScmWalk) vrWalk.subSteps().get(0)).relationName());
            assertEquals("in_section_fruit", ((ScmWalk) vrWalk.subSteps().get(1)).relationName());
        }
    }
}
