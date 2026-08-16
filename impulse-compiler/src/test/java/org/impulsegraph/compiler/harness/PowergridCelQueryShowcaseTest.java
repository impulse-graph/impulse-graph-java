package org.impulsegraph.compiler.harness;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;


import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.compiler.ast.*;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Showcase of Complex Power Grid Stability and Transmission Analysis Queries with CEL Expressions
 * and Dynamic Parameter Binding (@minVoltage, @maxVoltage, @maxRating, @criticalMva).
 */
public class PowergridCelQueryShowcaseTest {

    @Test
    @DisplayName("Query 1: Critical Bus Voltage Violation Detection (node.vm < @minVoltage || node.vm > @maxVoltage)")
    void testCriticalBusVoltageViolationQuery() {
        // Mock Bus Voltage Magnitude (vm in p.u.) Zone Map: Min=0.90, Max=1.12
        AttributeStatistics vmStats = new AttributeStatistics(
                "vm", 0, 0, 0.90, 1.12, "", "", 0, 1354,
                AttributeStatistics.Monotonicity.MONO_NONE, false
        );

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rows = arena.allocate(8L);
            MemorySegment cols = arena.allocate(4L);
            org.impulsegraph.storage.csr.RelationSnapshot branchRel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 1, 1, rows, cols);
            ImpulseGraphSnapshot snapshot = new org.impulsegraph.storage.csr.GraphSnapshot(arena, Map.of("Branch", branchRel));
            snapshot.getGraphStatistics().putAttributeStatistics("vm", vmStats);

            // Fluent Query with CEL Expression and Parameter Binding
            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .walkEdge("Branch")
                    .filterWithCel("node.vm < @minVoltage || node.vm > @maxVoltage")
                    .bindParameter("@minVoltage", 0.95)
                    .bindParameter("@maxVoltage", 1.05)
                    .collectBitSet();

            assertNotNull(query);
            assertEquals(0.95, query.getParameters().get("@minVoltage"));
            assertEquals(1.05, query.getParameters().get("@maxVoltage"));

            // Compile through full algebraic pipeline
            CompilerOptions opts = CompilerOptions.builder()
                    .withTracing(true)
                    .withParameters(query.getParameters())
                    .build();
            CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

            CelAstNode parsedCel = CelParser.parse("node.vm < @minVoltage || node.vm > @maxVoltage");
            ScmProgram rawAst = ScmProgram.of(
                    ScmWalk.forward("Branch", new ScmCelExpr("node.vm < @minVoltage || node.vm > @maxVoltage", parsedCel)),
                    ScmCollect.bitset()
            );

            ImpScmNode ast = ctx.executePass(PreBindValidator.INSTANCE, rawAst);
            ast = ctx.executePass(ParameterBindingPass.INSTANCE, ast);
            ast = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
            ast = ctx.executePass(DirectionSelectionPass.INSTANCE, ast);
            ast = ctx.executePass(PhysicalBindingPass.INSTANCE, ast);

            assertNotNull(ast);
            String scm = ast.toScmString();
            // Verify @minVoltage and @maxVoltage were bound to 0.95 and 1.05
            assertTrue(scm.contains("0.95") || scm.contains("0.950"));
            assertTrue(scm.contains("1.05") || scm.contains("1.050"));
            System.out.println("[Powergrid Query 1 ImpScheme]:\n" + scm);
        }
    }

    @Test
    @DisplayName("Query 2: Transmission Line Thermal MVA Overload Alert (edge.status == 1 && edge.mva_flow > @maxRating)")
    void testTransmissionLineThermalOverloadQuery() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rows = arena.allocate(8L);
            MemorySegment cols = arena.allocate(4L);
            org.impulsegraph.storage.csr.RelationSnapshot branchRel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 1, 1, rows, cols);
            ImpulseGraphSnapshot snapshot = new org.impulsegraph.storage.csr.GraphSnapshot(arena, Map.of("Branch", branchRel));

            // Fluent Query: Detect energized lines exceeding 250 MVA rating
            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .walkEdgeWithCel("Branch", "edge.status == 1 && edge.mva_flow > @maxRating")
                    .bindParameter("@maxRating", 250.0)
                    .collectBitSet();

            CompilerOptions opts = CompilerOptions.builder()
                    .withTracing(true)
                    .withParameters(query.getParameters())
                    .build();
            CompilerContext ctx = new CompilerContext(snapshot, opts, new PassTracer(opts));

            CelAstNode parsedCel = CelParser.parse("edge.status == 1 && edge.mva_flow > @maxRating");
            ScmProgram rawAst = ScmProgram.of(
                    ScmWalk.forward("Branch", new ScmCelExpr("edge.status == 1 && edge.mva_flow > @maxRating", parsedCel)),
                    ScmCollect.bitset()
            );

            ImpScmNode ast = ctx.executePass(PreBindValidator.INSTANCE, rawAst);
            ast = ctx.executePass(ParameterBindingPass.INSTANCE, ast);
            ast = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, ast);
            ast = ctx.executePass(PhysicalBindingPass.INSTANCE, ast);

            assertNotNull(ast);
            String scm = ast.toScmString();
            assertTrue(scm.contains("250.0"));
            System.out.println("[Powergrid Query 2 ImpScheme]:\n" + scm);
        }
    }

    @Test
    @DisplayName("Query 3: N-1 Outage Contingency Multi-Hop Energized Reachability")
    void testN1ContingencyMultiHopReachability() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rows = arena.allocate(8L);
            MemorySegment cols = arena.allocate(4L);
            org.impulsegraph.storage.csr.RelationSnapshot branchRel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 1, 1, rows, cols);
            ImpulseGraphSnapshot snapshot = new org.impulsegraph.storage.csr.GraphSnapshot(arena, Map.of("Branch", branchRel));

            // Multi-Hop Transitive Islanding Frontier over closed breakers (edge.status == 1)
            ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                    .repeatUntilStable(b -> b.walkEdgeWithCel("Branch", "edge.status == 1 && edge.x_pu < @maxReactance"))
                    .bindParameter("@maxReactance", 0.15)
                    .collectBitSet();

            assertNotNull(query);
            assertEquals(0.15, query.getParameters().get("@maxReactance"));

            System.out.println("[Powergrid Query 3 AST Export]:\n" + query.exportAst());
        }
    }
}
