package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;

import java.util.HashMap;
import java.util.Map;

/**
 * Stage 2 Pass: Linear scan register allocator for ImpulseVM registers (R0..R63).
 */
public final class RegisterAllocationPass implements CompilerPass {

    public static final RegisterAllocationPass INSTANCE = new RegisterAllocationPass();

    public record RegisterAssignment(
            Map<ImpScmNode, Short> srcRegisters,
            Map<ImpScmNode, Short> dstRegisters,
            int maxRegistersUsed
    ) {}

    @Override
    public String name() {
        return "RegisterAllocationPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        // Compute register assignments
        RegisterAssignment assignment = allocate(ast);
        // Returns unchanged AST; register assignment metadata is attached or queried by emitter
        return ast;
    }

    public static RegisterAssignment allocate(ImpScmNode ast) {
        Map<ImpScmNode, Short> srcMap = new HashMap<>();
        Map<ImpScmNode, Short> dstMap = new HashMap<>();
        short currentReg = 0;
        int maxReg = 1;

        if (ast instanceof ScmProgram prog) {
            for (ImpScmNode step : prog.steps()) {
                if (step instanceof ScmWalk || step instanceof ScmWalk2Hop || step instanceof ScmVectorFilter) {
                    short src = currentReg;
                    // Ping-pong between register 0 and 1 for linear traversal chains
                    short dst = (short) (1 - currentReg);
                    srcMap.put(step, src);
                    dstMap.put(step, dst);
                    currentReg = dst;
                    maxReg = Math.max(maxReg, 2);
                } else if (step instanceof ScmReduce || step instanceof ScmCollect) {
                    srcMap.put(step, currentReg);
                    dstMap.put(step, currentReg);
                } else {
                    short src = currentReg;
                    short dst = (short) (1 - currentReg);
                    srcMap.put(step, src);
                    dstMap.put(step, dst);
                    currentReg = dst;
                    maxReg = Math.max(maxReg, 2);
                }
            }
        }

        return new RegisterAssignment(srcMap, dstMap, maxReg);
    }
}
