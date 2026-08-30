package org.impulsegraph.compiler.harness;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.CompiledQuery;

import java.lang.foreign.Arena;
import java.util.Map;
import java.util.HexFormat;

public class CrossCompilerParityHarness {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("{\"status\": \"error\", \"error\": \"Usage: java CrossCompilerParityHarness <type> <expr>\"}");
            System.exit(1);
        }
        
        String type = args[0]; // "filter" or "project"
        String expr = args[1];
        
        try (Arena arena = Arena.ofConfined()) {
            RelationSnapshot rel = new RelationSnapshot(arena, 4, 3, arena.allocate(16), arena.allocate(12));
            ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("ROAD", rel));
            
            org.impulsegraph.compiler.registry.QueryCompilerEngine engine = new org.impulsegraph.compiler.registry.QueryCompilerEngine();
            org.impulsegraph.compiler.ast.ScmProgram ast;
            
            if (type.equals("project")) {
                ast = org.impulsegraph.compiler.ast.ScmProgram.of(
                    org.impulsegraph.compiler.ast.ScmWalk.forward("ROAD").withShaderSteps(java.util.List.of(
                        new org.impulsegraph.compiler.ast.ScmStreamProject(new org.impulsegraph.compiler.ast.ScmCelExpr(expr))
                    )),
                    org.impulsegraph.compiler.ast.ScmCollect.bitset()
                );
            } else {
                ast = org.impulsegraph.compiler.ast.ScmProgram.of(
                    org.impulsegraph.compiler.ast.ScmWalk.forward("ROAD", new org.impulsegraph.compiler.ast.ScmCelExpr(expr)),
                    org.impulsegraph.compiler.ast.ScmCollect.bitset()
                );
            }
            
            org.impulsegraph.compiler.registry.QueryObject q = engine.compileStage1("test", "", ast, org.impulsegraph.compiler.trace.CompilerOptions.DEFAULT);
            CompiledQuery cq = engine.compileStage2(q, snapshot, arena, org.impulsegraph.compiler.trace.CompilerOptions.DEFAULT);
            
            byte[] bytes = cq.programSegment().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            System.out.println("{\"status\": \"ok\", \"hex\": \"" + HexFormat.of().formatHex(bytes) + "\"}");
            
        } catch (Exception e) {
            System.err.println("{\"status\": \"error\", \"error\": \"" + e.getMessage() + "\"}");
            System.exit(1);
        }
    }
}
