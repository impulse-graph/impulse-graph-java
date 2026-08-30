package org.impulsegraph.compiler.emitter;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.ScmProgram;
import org.impulsegraph.compiler.ast.ScmWalk;
import org.impulsegraph.compiler.ast.ScmCollect;
import org.impulsegraph.compiler.ast.ScmCelExpr;
import org.impulsegraph.compiler.registry.QueryCompilerEngine;
import org.impulsegraph.compiler.registry.QueryObject;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.VmRegisterType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class StreamShaderIntegrationTest {

    @Test
    @DisplayName("End-to-End Stream Shader Pipeline with CEL Filter")
    void testStreamShaderPipeline() {
        try (Arena arena = Arena.ofConfined()) {
            // Setup a 4-node graph: 0 -> 1, 0 -> 2, 0 -> 3
            int[] rowOffsets = {0, 3, 3, 3, 3};
            int[] colTargets = {1, 2, 3};

            MemorySegment rowSeg = arena.allocate((long) rowOffsets.length * 4);
            for (int i = 0; i < rowOffsets.length; i++) rowSeg.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsets[i]);

            MemorySegment colSeg = arena.allocate((long) colTargets.length * 4);
            for (int i = 0; i < colTargets.length; i++) colSeg.setAtIndex(ValueLayout.JAVA_INT, i, colTargets[i]);

            RelationSnapshot rel = new RelationSnapshot(arena, 4, 3, rowSeg, colSeg);
            ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("follows", rel));

            // Query: walk "follows", filter where target's age > 21
            ScmProgram ast = ScmProgram.of(
                    ScmWalk.forward("follows", new ScmCelExpr("age > 21")),
                    ScmCollect.bitset()
            );

            QueryCompilerEngine engine = new QueryCompilerEngine();
            QueryObject query = engine.compileStage1("test_stream", "", ast, CompilerOptions.DEFAULT);

            CompiledQuery compiled = engine.compileStage2(query, snapshot, arena, CompilerOptions.DEFAULT);
            assertNotNull(compiled);
            
            boolean foundStreamBlock = false;
            long instrCount = compiled.instructionCount();
            for (int i = 0; i < instrCount; i++) {
                byte opcode = compiled.programSegment().get(ValueLayout.JAVA_BYTE, i * 8L);
                if (opcode == VmRegisterType.OP_STREAM_FUNC_BEGIN) {
                    foundStreamBlock = true;
                }
            }
            assertTrue(foundStreamBlock, "Compiled bytecode must contain OP_STREAM_FUNC_BEGIN");
        }
    }
}
