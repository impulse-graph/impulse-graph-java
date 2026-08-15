package org.impulsegraph.compiler.harness;

import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive Verification Suite for Impulse Graph Compiler Optimization Rules.
 * Contains 2-3 dedicated unit tests per optimization rule, inspecting the exact AST output after every pass.
 */
public class OptimizationRulesCoverageTest {

    // =========================================================================
    // Rule 1: Zone Map & Interval Bound Pruning (3 Tests)
    // =========================================================================
    @Nested
    @DisplayName("Rule 1: Zone Map & Interval Bound Pruning")
    class Rule1ZoneMapPruningTests {

        @Test
        @DisplayName("1a. Upper Bound Exceeded: node.age > 250 with max(age)==114 prunes dead traversal branch")
        void testUpperBoundExceededPrunesBranch() {
            AttributeStatistics ageStats = new AttributeStatistics(
                    "age", 18, 114, 18.0, 114.0, "", "", 0, 96,
                    AttributeStatistics.Monotonicity.MONO_NONE, false
            );

            try (Arena arena = Arena.ofConfined()) {
                RelationSnapshot rel = createMockRelation(arena, 2, 2);
                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("users", rel));
                snapshot.getGraphStatistics().putAttributeStatistics("age", ageStats);

                ScmProgram ast = ScmProgram.of(
                        ScmWalk.forward("users", new ScmCelExpr("node.age > 250", CelParser.parse("node.age > 250"))),
                        ScmCollect.bitset()
                );

                CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
                PassTracer tracer = new PassTracer(opts);
                CompilerContext ctx = new CompilerContext(snapshot, opts, tracer);

                ImpScmNode step1 = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
                ImpScmNode step2 = ctx.executePass(ZoneMapPruningPass.INSTANCE, step1);

                assertNotNull(step2);
                assertInstanceOf(ScmProgram.class, step2);
                ScmProgram prog = (ScmProgram) step2;
                // Dead traversal branch pruned: only terminal collect remains
                assertEquals(1, prog.steps().size());
                assertInstanceOf(ScmCollect.class, prog.steps().get(0));

                System.out.println(tracer.generateTraceReport());
            }
        }

        @Test
        @DisplayName("1b. Lower Bound Subsumed: node.age < 10 with min(age)==18 prunes dead traversal branch")
        void testLowerBoundSubsumedPrunesBranch() {
            AttributeStatistics ageStats = new AttributeStatistics(
                    "age", 18, 114, 18.0, 114.0, "", "", 0, 96,
                    AttributeStatistics.Monotonicity.MONO_NONE, false
            );

            try (Arena arena = Arena.ofConfined()) {
                RelationSnapshot rel = createMockRelation(arena, 2, 2);
                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("users", rel));
                snapshot.getGraphStatistics().putAttributeStatistics("age", ageStats);

                ScmProgram ast = ScmProgram.of(
                        ScmWalk.forward("users", new ScmCelExpr("node.age < 10", CelParser.parse("node.age < 10"))),
                        ScmCollect.bitset()
                );

                CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
                PassTracer tracer = new PassTracer(opts);
                CompilerContext ctx = new CompilerContext(snapshot, opts, tracer);

                ImpScmNode step1 = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
                ImpScmNode step2 = ctx.executePass(ZoneMapPruningPass.INSTANCE, step1);

                assertNotNull(step2);
                assertInstanceOf(ScmProgram.class, step2);
                ScmProgram prog = (ScmProgram) step2;
                assertEquals(1, prog.steps().size());
                assertInstanceOf(ScmCollect.class, prog.steps().get(0));
            }
        }

        @Test
        @DisplayName("1c. Universal Satisfaction: node.age >= 18 with min(age)==18 strips inner filter")
        void testUniversalSatisfactionStripsFilter() {
            AttributeStatistics ageStats = new AttributeStatistics(
                    "age", 18, 114, 18.0, 114.0, "", "", 0, 96,
                    AttributeStatistics.Monotonicity.MONO_NONE, false
            );

            try (Arena arena = Arena.ofConfined()) {
                RelationSnapshot rel = createMockRelation(arena, 2, 2);
                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("users", rel));
                snapshot.getGraphStatistics().putAttributeStatistics("age", ageStats);

                ScmProgram ast = ScmProgram.of(
                        ScmWalk.forward("users", new ScmCelExpr("node.age >= 18", CelParser.parse("node.age >= 18"))),
                        ScmCollect.bitset()
                );

                CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
                PassTracer tracer = new PassTracer(opts);
                CompilerContext ctx = new CompilerContext(snapshot, opts, tracer);

                ImpScmNode step1 = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
                ImpScmNode step2 = ctx.executePass(ZoneMapPruningPass.INSTANCE, step1);

                assertNotNull(step2);
                assertInstanceOf(ScmProgram.class, step2);
                ScmProgram prog = (ScmProgram) step2;
                assertEquals(2, prog.steps().size());
                ScmWalk walk = (ScmWalk) prog.steps().get(0);
                // Filter stripped: provably 100% true, zero runtime overhead!
                assertNull(walk.filterPredicate());
            }
        }
    }

    // =========================================================================
    // Rule 2: Monotonic Homomorphism Commutation & Inversion (3 Tests)
    // =========================================================================
    @Nested
    @DisplayName("Rule 2: Monotonic Homomorphism Commutation & Inversion")
    class Rule2MonotonicHomomorphismTests {

        @Test
        @DisplayName("2a. Monotonic Increasing Commutation: max(log(v)) -> log(max(v))")
        void testMaxLogCommutation() {
            CelAstNode cel = CelParser.parse("max(log(v))");
            CelAstNode inferred = AlgebraicTypeInferencePass.INSTANCE.inferCel(cel, new GraphStatistics());
            CelAstNode optimized = MonotonicHomomorphismPass.INSTANCE.optimizeCel(inferred);

            assertNotNull(optimized);
            assertEquals("log", optimized.text());
            assertEquals("max", optimized.children().get(0).text());
            assertEquals("v", optimized.children().get(0).children().get(0).text());
        }

        @Test
        @DisplayName("2b. Monotonic Increasing Commutation: min(sqrt(v)) -> sqrt(min(v))")
        void testMinSqrtCommutation() {
            CelAstNode cel = CelParser.parse("min(sqrt(v))");
            CelAstNode inferred = AlgebraicTypeInferencePass.INSTANCE.inferCel(cel, new GraphStatistics());
            CelAstNode optimized = MonotonicHomomorphismPass.INSTANCE.optimizeCel(inferred);

            assertNotNull(optimized);
            assertEquals("sqrt", optimized.text());
            assertEquals("min", optimized.children().get(0).text());
            assertEquals("v", optimized.children().get(0).children().get(0).text());
        }

        @Test
        @DisplayName("2c. Monotonic Inverting Meet-Join: max(-v) -> -min(v) and min(-v) -> -max(v)")
        void testMeetJoinInversion() {
            // max(-v) -> -min(v)
            CelAstNode maxNeg = CelParser.parse("max(-v)");
            CelAstNode optMaxNeg = MonotonicHomomorphismPass.INSTANCE.optimizeCel(
                    AlgebraicTypeInferencePass.INSTANCE.inferCel(maxNeg, new GraphStatistics()));
            assertEquals("-", optMaxNeg.text());
            assertEquals("min", optMaxNeg.children().get(0).text());

            // min(-v) -> -max(v)
            CelAstNode minNeg = CelParser.parse("min(-v)");
            CelAstNode optMinNeg = MonotonicHomomorphismPass.INSTANCE.optimizeCel(
                    AlgebraicTypeInferencePass.INSTANCE.inferCel(minNeg, new GraphStatistics()));
            assertEquals("-", optMinNeg.text());
            assertEquals("max", optMinNeg.children().get(0).text());
        }
    }

    // =========================================================================
    // Rule 3: Injective Path Deduplication Bypass (3 Tests)
    // =========================================================================
    @Nested
    @DisplayName("Rule 3: Injective Path Deduplication Bypass")
    class Rule3InjectiveBypassTests {

        @Test
        @DisplayName("3a. Single-Hop Injective Relation (1:M): DISTINCT rewritten to BITSET")
        void testSingleHopInjectiveBypass() {
            try (Arena arena = Arena.ofConfined()) {
                // Injective relation: InDegree <= 1
                int[] rowOffsets = {0, 2, 4};
                int[] colTargets = {0, 1, 2, 3};
                RelationSnapshot rel = createMockRelation(arena, rowOffsets, colTargets);
                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("parentToChildren", rel));

                assertTrue(rel.getStatistics().isInjective());

                ScmProgram ast = ScmProgram.of(
                        ScmWalk.forward("parentToChildren"),
                        ScmCollect.distinct()
                );

                CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
                CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

                ImpScmNode opt = ctx.executePass(InjectiveDeduplicationBypassPass.INSTANCE, ast);
                assertNotNull(opt);
                ScmCollect col = (ScmCollect) ((ScmProgram) opt).steps().get(1);
                assertEquals(ScmCollect.Format.BITSET, col.format());
            }
        }

        @Test
        @DisplayName("3b. Multi-Hop Injective Composition (1:M o 1:1): DISTINCT rewritten to BITSET")
        void testMultiHopInjectiveCompositionBypass() {
            try (Arena arena = Arena.ofConfined()) {
                int[] rowOffsets1 = {0, 2, 4};
                int[] colTargets1 = {0, 1, 2, 3};
                RelationSnapshot rel1 = createMockRelation(arena, rowOffsets1, colTargets1);

                int[] rowOffsets2 = {0, 1, 2, 3, 4};
                int[] colTargets2 = {10, 11, 12, 13};
                RelationSnapshot rel2 = createMockRelation(arena, rowOffsets2, colTargets2);

                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("step1", rel1, "step2", rel2));

                ScmProgram ast = ScmProgram.of(
                        ScmWalk.forward("step1"),
                        ScmWalk.forward("step2"),
                        ScmCollect.distinct()
                );

                CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
                CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

                ImpScmNode opt = ctx.executePass(InjectiveDeduplicationBypassPass.INSTANCE, ast);
                assertNotNull(opt);
                ScmCollect col = (ScmCollect) ((ScmProgram) opt).steps().get(2);
                assertEquals(ScmCollect.Format.BITSET, col.format());
            }
        }

        @Test
        @DisplayName("3c. Negative Case (M:N Relation): DISTINCT preserved for non-injective paths")
        void testManyToManyPreservesDistinct() {
            try (Arena arena = Arena.ofConfined()) {
                // Non-injective relation: Target 0 has in-degree 2
                int[] rowOffsets = {0, 2, 4};
                int[] colTargets = {0, 1, 0, 2};
                RelationSnapshot rel = createMockRelation(arena, rowOffsets, colTargets);
                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("userFriends", rel));

                assertFalse(rel.getStatistics().isInjective());

                ScmProgram ast = ScmProgram.of(
                        ScmWalk.forward("userFriends"),
                        ScmCollect.distinct()
                );

                CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
                CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

                ImpScmNode opt = ctx.executePass(InjectiveDeduplicationBypassPass.INSTANCE, ast);
                assertNotNull(opt);
                ScmCollect col = (ScmCollect) ((ScmProgram) opt).steps().get(1);
                // Correct: DISTINCT is preserved!
                assertEquals(ScmCollect.Format.DISTINCT, col.format());
            }
        }
    }

    // =========================================================================
    // Rule 4: Virtual Relation Coproduct Decomposition (3 Tests)
    // =========================================================================
    @Nested
    @DisplayName("Rule 4: Virtual Relation Coproduct Decomposition")
    class Rule4VirtualRelationTests {

        @Test
        @DisplayName("4a. Coproduct Decomposition: in_section decomposes into in_section_fruit + in_section_bread")
        void testCoproductDecomposition() {
            try (Arena arena = Arena.ofConfined()) {
                RelationSnapshot secFruit = createMockRelation(arena, 1, 1);
                RelationSnapshot secBread = createMockRelation(arena, 1, 1);

                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of(
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
                ScmWalk vrWalk = (ScmWalk) ((ScmProgram) decomp).steps().get(0);
                assertEquals(2, vrWalk.subSteps().size());
                assertEquals("in_section_bread", ((ScmWalk) vrWalk.subSteps().get(0)).relationName());
                assertEquals("in_section_fruit", ((ScmWalk) vrWalk.subSteps().get(1)).relationName());
            }
        }

        @Test
        @DisplayName("4b. Three-Way Coproduct Decomposition: in_section decomposes into fruit + bread + dairy")
        void testThreeWayCoproductDecomposition() {
            try (Arena arena = Arena.ofConfined()) {
                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of(
                        "in_section_fruit", createMockRelation(arena, 1, 1),
                        "in_section_bread", createMockRelation(arena, 1, 1),
                        "in_section_dairy", createMockRelation(arena, 1, 1)
                ));

                ScmProgram ast = ScmProgram.of(
                        ScmWalk.forward("in_section"),
                        ScmCollect.bitset()
                );

                CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
                CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

                ImpScmNode decomp = ctx.executePass(VirtualRelationDecompositionPass.INSTANCE, ast);
                assertNotNull(decomp);
                ScmWalk vrWalk = (ScmWalk) ((ScmProgram) decomp).steps().get(0);
                assertEquals(3, vrWalk.subSteps().size());
                assertEquals("in_section_bread", ((ScmWalk) vrWalk.subSteps().get(0)).relationName());
                assertEquals("in_section_dairy", ((ScmWalk) vrWalk.subSteps().get(1)).relationName());
                assertEquals("in_section_fruit", ((ScmWalk) vrWalk.subSteps().get(2)).relationName());
            }
        }

        @Test
        @DisplayName("4c. Physical Binding after Coproduct Decomposition resolves all relation IDs")
        void testPhysicalBindingAfterDecomposition() {
            try (Arena arena = Arena.ofConfined()) {
                GraphSnapshot snapshot = new GraphSnapshot(arena, Map.of(
                        "in_section_fruit", createMockRelation(arena, 1, 1),
                        "in_section_bread", createMockRelation(arena, 1, 1)
                ));

                ScmProgram ast = ScmProgram.of(
                        ScmWalk.forward("in_section"),
                        ScmCollect.bitset()
                );

                CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
                CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

                ImpScmNode decomp = ctx.executePass(VirtualRelationDecompositionPass.INSTANCE, ast);
                ImpScmNode bound = ctx.executePass(PhysicalBindingPass.INSTANCE, decomp);
                assertNotNull(bound);
                String scm = bound.toScmString();
                assertTrue(scm.contains("csr-walk"));
            }
        }
    }

    // =========================================================================
    // Rule 5: Parameter Binding & Constant Substitution (3 Tests)
    // =========================================================================
    @Nested
    @DisplayName("Rule 5: Parameter Binding & Constant Substitution")
    class Rule5ParameterBindingTests {

        @Test
        @DisplayName("5a. String Parameter Binding: section == @P1 with @P1='FRUIT' substitutes literal 'FRUIT'")
        void testStringParameterBinding() {
            CelAstNode cel = CelParser.parse("section == @P1");
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("items", new ScmCelExpr("section == @P1", cel)),
                    ScmCollect.bitset()
            );

            CompilerOptions opts = CompilerOptions.builder()
                    .withParameter("@P1", "FRUIT")
                    .build();
            CompilerContext ctx = new CompilerContext(null, opts, new PassTracer(opts));

            ImpScmNode bound = ctx.executePass(ParameterBindingPass.INSTANCE, ast);
            assertNotNull(bound);
            String scm = bound.toScmString();
            assertTrue(scm.contains("\"FRUIT\""));
        }

        @Test
        @DisplayName("5b. Numeric Double Parameter Binding: node.vm < @minVoltage with @minVoltage=0.95")
        void testNumericDoubleParameterBinding() {
            CelAstNode cel = CelParser.parse("node.vm < @minVoltage");
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("bus", new ScmCelExpr("node.vm < @minVoltage", cel)),
                    ScmCollect.bitset()
            );

            CompilerOptions opts = CompilerOptions.builder()
                    .withParameter("@minVoltage", 0.95)
                    .build();
            CompilerContext ctx = new CompilerContext(null, opts, new PassTracer(opts));

            ImpScmNode bound = ctx.executePass(ParameterBindingPass.INSTANCE, ast);
            assertNotNull(bound);
            String scm = bound.toScmString();
            assertTrue(scm.contains("0.95") || scm.contains("0.950"));
        }

        @Test
        @DisplayName("5c. Compound Expression with Multiple Parameters: status == @st && age >= @minAge")
        void testCompoundExpressionMultipleParameters() {
            CelAstNode cel = CelParser.parse("status == @st && age >= @minAge");
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("user", new ScmCelExpr("status == @st && age >= @minAge", cel)),
                    ScmCollect.bitset()
            );

            CompilerOptions opts = CompilerOptions.builder()
                    .withParameter("@st", 1)
                    .withParameter("@minAge", 21)
                    .build();
            CompilerContext ctx = new CompilerContext(null, opts, new PassTracer(opts));

            ImpScmNode bound = ctx.executePass(ParameterBindingPass.INSTANCE, ast);
            assertNotNull(bound);
            String scm = bound.toScmString();
            assertTrue(scm.contains("1"));
            assertTrue(scm.contains("21"));
        }
    }

    // =========================================================================
    // Rule 6: Constant Folding & AST Normalization (3 Tests)
    // =========================================================================
    @Nested
    @DisplayName("Rule 6: Constant Folding & AST Normalization")
    class Rule6ConstantFoldingTests {

        @Test
        @DisplayName("6a. Integer Arithmetic Folding: 21 + 0 -> 21, 10 * 5 -> 50")
        void testArithmeticFolding() {
            CelAstNode cel = CelParser.parse("21 + 0");
            CelAstNode folded = org.impulsegraph.compiler.cel.CelAstOptimizer.optimize(cel);
            assertEquals(CelAstNode.Kind.LITERAL_INT, folded.kind());
            assertEquals(21, folded.intVal());

            CelAstNode celMul = CelParser.parse("10 * 5");
            CelAstNode foldedMul = org.impulsegraph.compiler.cel.CelAstOptimizer.optimize(celMul);
            assertEquals(CelAstNode.Kind.LITERAL_INT, foldedMul.kind());
            assertEquals(50, foldedMul.intVal());
        }

        @Test
        @DisplayName("6b. Double Negation Folding: !(!active) -> active")
        void testDoubleNegationFolding() {
            CelAstNode cel = CelParser.parse("!(!active)");
            CelAstNode folded = org.impulsegraph.compiler.cel.CelAstOptimizer.optimize(cel);
            assertEquals(CelAstNode.Kind.IDENTIFIER, folded.kind());
            assertEquals("active", folded.text());
        }

        @Test
        @DisplayName("6c. String Concatenation Folding: 'user_' + 'profile' -> 'user_profile'")
        void testStringConcatenationFolding() {
            CelAstNode cel = CelParser.parse("\"user_\" + \"profile\"");
            CelAstNode folded = org.impulsegraph.compiler.cel.CelAstOptimizer.optimize(cel);
            assertEquals(CelAstNode.Kind.LITERAL_STRING, folded.kind());
            assertEquals("user_profile", folded.strVal());
        }
    }

    // Helper to create mock off-heap relation
    private static RelationSnapshot createMockRelation(Arena arena, int nodes, int edges) {
        MemorySegment rows = arena.allocate((long) (nodes + 1) * ValueLayout.JAVA_INT.byteSize());
        MemorySegment cols = arena.allocate((long) edges * ValueLayout.JAVA_INT.byteSize());
        return new RelationSnapshot(arena, nodes, edges, rows, cols);
    }

    private static RelationSnapshot createMockRelation(Arena arena, int[] rowOffsets, int[] colTargets) {
        MemorySegment rowSeg = arena.allocate((long) rowOffsets.length * ValueLayout.JAVA_INT.byteSize());
        for (int i = 0; i < rowOffsets.length; i++) rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsets[i]);

        MemorySegment colSeg = arena.allocate((long) colTargets.length * ValueLayout.JAVA_INT.byteSize());
        for (int i = 0; i < colTargets.length; i++) colSeg.setAtIndex(ValueLayout.JAVA_INT, i, colTargets[i]);

        return new RelationSnapshot(arena, rowOffsets.length - 1, colTargets.length, rowSeg, colSeg);
    }
}
