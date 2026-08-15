package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.api.config.OptimizerConfig;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2 Optimization Pass: Multi-Hop Kernel Fusion.
 * <p>
 * Rewrites two consecutive forward CSR walks {@code (csr-walk rel1)} and {@code (csr-walk rel2)}
 * into a single fused 2-hop traversal {@code (csr-walk-2hop rel1 rel2)} when the intermediate
 * relation multiplicity is below {@link OptimizerConfig#FUSED_2HOP_MAX_MULTIPLICITY_THRESHOLD} (1.5).
 * </p>
 * <p>
 * Governed by {@link OptimizerConfig#ENABLE_EXPERIMENTAL_2HOP_FUSION} / {@code CompilerOptions#enableExperimental2HopFusion()}.
 * </p>
 */
public final class KernelFusionPass implements CompilerPass {

    public static final KernelFusionPass INSTANCE = new KernelFusionPass();

    @Override
    public String name() {
        return "KernelFusionPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        if (!context.options().enableExperimental2HopFusion()) {
            return ast;
        }

        if (ast instanceof ScmProgram prog) {
            List<ImpScmNode> originalSteps = prog.steps();
            List<ImpScmNode> fusedSteps = new ArrayList<>();
            GraphSnapshot snapshot = context.snapshot();

            int i = 0;
            while (i < originalSteps.size()) {
                ImpScmNode current = originalSteps.get(i);

                if (i + 1 < originalSteps.size() && current instanceof ScmWalk w1 && originalSteps.get(i + 1) instanceof ScmWalk w2) {
                    if (canFuse(w1, w2, snapshot)) {
                        fusedSteps.add(new ScmWalk2Hop(
                                w1.relationName(), w1.relationId(),
                                w2.relationName(), w2.relationId()
                        ));
                        i += 2;
                        continue;
                    }
                }

                fusedSteps.add(current);
                i++;
            }

            return new ScmProgram(fusedSteps);
        }

        return ast;
    }

    private static boolean canFuse(ScmWalk w1, ScmWalk w2, GraphSnapshot snapshot) {
        // Both walks must be forward CSR without filter predicates or sub-steps
        if (w1.direction() != ScmWalk.Direction.FORWARD_CSR || w1.filterPredicate() != null || !w1.subSteps().isEmpty()) {
            return false;
        }
        if (w2.direction() != ScmWalk.Direction.FORWARD_CSR || w2.filterPredicate() != null || !w2.subSteps().isEmpty()) {
            return false;
        }

        // Check multiplicity threshold on intermediate relation
        if (snapshot != null) {
            org.impulsegraph.api.stats.RelationStatistics stats = null;
            if (snapshot.getGraphStatistics() != null) {
                stats = snapshot.getGraphStatistics().getRelationStatistics(w1.relationName());
            }
            if (stats == null) {
                RelationSnapshot rel1 = snapshot.getRelationSnapshot(w1.relationName());
                if (rel1 != null) {
                    stats = rel1.getStatistics();
                }
            }
            if (stats != null) {
                if (stats.getMultiplicity() == org.impulsegraph.api.stats.RelationStatistics.Multiplicity.MANY_TO_MANY
                        && stats.getAvgInDegree() > OptimizerConfig.FUSED_2HOP_MAX_MULTIPLICITY_THRESHOLD) {
                    return false;
                }
                return stats.getAvgInDegree() <= OptimizerConfig.FUSED_2HOP_MAX_MULTIPLICITY_THRESHOLD
                        || stats.getMultiplicity() == org.impulsegraph.api.stats.RelationStatistics.Multiplicity.ONE_TO_ONE
                        || stats.getMultiplicity() == org.impulsegraph.api.stats.RelationStatistics.Multiplicity.ONE_TO_MANY;
            }
        }

        return true;
    }
}
