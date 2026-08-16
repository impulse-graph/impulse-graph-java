package org.impulsegraph.compiler.passes;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.compiler.ast.*;
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
 * Unit tests for Stage 1 and Stage 2 compiler passes.
 */
public class CompilerPassTest {

    @Test
    @DisplayName("Stage 1: AstNormalization & ConstantFolding & CelPredicateFlattening")
    void testStage1Passes() {
        ScmProgram raw = ScmProgram.of(
                ScmWalk.forward("userToGroup"),
                new ScmCelExpr("node.age >= 21 + 0", null),
                ScmCollect.bitset()
        );

        CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
        PassTracer tracer = new PassTracer(opts);
        CompilerContext ctx = new CompilerContext(null, opts, tracer);

        ImpScmNode ast = ctx.executePass(PreBindValidator.INSTANCE, raw);
        ast = ctx.executePass(AstNormalizationPass.INSTANCE, ast);
        ast = ctx.executePass(ConstantFoldingPass.INSTANCE, ast);
        ast = ctx.executePass(CelPredicateFlatteningPass.INSTANCE, ast);

        assertNotNull(ast);
        String scm = ast.toScmString();
        assertTrue(scm.contains("vec-cmp-gte"));
        assertTrue(scm.contains("21"));
        assertFalse(scm.contains("+ 0")); // constant folded
    }

    @Test
    @DisplayName("Stage 2: FilterPushdown & PhysicalBinding & RegisterAllocation")
    void testStage2Passes() {
        try (Arena arena = Arena.ofConfined()) {
            // Build a mock ImpulseGraphSnapshot with relation "userToGroup"
            int[] rowOffsets = {0, 2, 3};
            int[] colTargets = {1, 2, 0};

            MemorySegment rowSeg = arena.allocate((long) rowOffsets.length * ValueLayout.JAVA_INT.byteSize());
            for (int i = 0; i < rowOffsets.length; i++) rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsets[i]);

            MemorySegment colSeg = arena.allocate((long) colTargets.length * ValueLayout.JAVA_INT.byteSize());
            for (int i = 0; i < colTargets.length; i++) colSeg.setAtIndex(ValueLayout.JAVA_INT, i, colTargets[i]);

            RelationSnapshot rel = new RelationSnapshot(arena, 3, 3, rowSeg, colSeg);
            ImpulseGraphSnapshot snapshot = new ImpulseGraphSnapshot(arena, Map.of("userToGroup", rel));

            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("userToGroup"),
                    ScmVectorFilter.of(ScmSymbol.of("filter_active")),
                    ScmCollect.bitset()
            );

            CompilerOptions opts = CompilerOptions.builder().withTracing(true).build();
            PassTracer tracer = new PassTracer(opts);
            CompilerContext ctx = new CompilerContext(snapshot, opts, tracer);

            ImpScmNode bound = ctx.executePass(BindTimeValidator.INSTANCE, ast);
            bound = ctx.executePass(DirectionSelectionPass.INSTANCE, bound);
            bound = ctx.executePass(FilterPushdownPass.INSTANCE, bound);
            bound = ctx.executePass(PhysicalBindingPass.INSTANCE, bound);
            bound = ctx.executePass(RegisterAllocationPass.INSTANCE, bound);

            assertNotNull(bound);
            assertInstanceOf(ScmProgram.class, bound);
            ScmProgram prog = (ScmProgram) bound;

            // Filter pushdown should fuse ScmVectorFilter into ScmWalk
            assertEquals(2, prog.steps().size());
            assertInstanceOf(ScmWalk.class, prog.steps().get(0));
            ScmWalk fusedWalk = (ScmWalk) prog.steps().get(0);
            assertNotNull(fusedWalk.filterPredicate());
            assertEquals(0, fusedWalk.relationId()); // physically bound
        }
    }
}
