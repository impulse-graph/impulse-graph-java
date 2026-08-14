package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2 Pass: Interleaves high-selectivity vector property filters directly into graph walk steps.
 * Enables execution via OP_CSR_WALK_FILTERED or fused SIMD kernels.
 */
public final class FilterPushdownPass implements CompilerPass {

    public static final FilterPushdownPass INSTANCE = new FilterPushdownPass();

    @Override
    public String name() {
        return "FilterPushdownPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        if (!context.options().enableFilterPushdown()) {
            return ast;
        }

        if (ast instanceof ScmProgram prog) {
            List<ImpScmNode> steps = new ArrayList<>(prog.steps());
            List<ImpScmNode> newSteps = new ArrayList<>();

            for (int i = 0; i < steps.size(); i++) {
                ImpScmNode curr = steps.get(i);

                if (curr instanceof ScmWalk walk && walk.filterPredicate() == null && i + 1 < steps.size()) {
                    ImpScmNode next = steps.get(i + 1);
                    if (next instanceof ScmVectorFilter vf) {
                        // Fuse filter into walk step
                        ScmWalk fused = new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), vf.predicate(), walk.subSteps());
                        newSteps.add(fused);
                        i++; // Skip the fused vector filter step
                        continue;
                    }
                }

                newSteps.add(curr);
            }
            return new ScmProgram(newSteps);
        }

        return ast;
    }
}
