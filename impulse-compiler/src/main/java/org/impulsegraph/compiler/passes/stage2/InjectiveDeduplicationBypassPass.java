package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.api.stats.RelationStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2 Pass: Injective Deduplication Bypass.
 * Injective paths (where all constituent relations have InDegree <= 1) preserve uniqueness.
 * Replaces expensive distinct deduplication passes with direct streaming collects.
 */
public final class InjectiveDeduplicationBypassPass implements CompilerPass {

    public static final InjectiveDeduplicationBypassPass INSTANCE = new InjectiveDeduplicationBypassPass();

    @Override
    public String name() {
        return "InjectiveDeduplicationBypassPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        GraphSnapshot snapshot = context.snapshot();
        if (snapshot == null) return ast;

        if (ast instanceof ScmProgram prog) {
            boolean allInjective = true;
            boolean hasWalk = false;

            for (ImpScmNode step : prog.steps()) {
                if (step instanceof ScmWalk walk) {
                    hasWalk = true;
                    RelationSnapshot rel = findRelation(snapshot, walk.relationName());
                    if (rel != null) {
                        RelationStatistics stats = rel.getStatistics();
                        if (stats != null && !stats.isInjective()) {
                            allInjective = false;
                            break;
                        }
                    } else {
                        allInjective = false;
                        break;
                    }
                }
            }

            if (hasWalk && allInjective) {
                // Injective path guaranteed: Rewrite distinct collection into direct bitset/vector collect
                List<ImpScmNode> optSteps = new ArrayList<>();
                for (ImpScmNode step : prog.steps()) {
                    if (step instanceof ScmCollect collect && collect.format() == ScmCollect.Format.DISTINCT) {
                        optSteps.add(ScmCollect.bitset()); // Bypass deduplication sort/bitset overhead!
                    } else {
                        optSteps.add(step);
                    }
                }
                return new ScmProgram(optSteps);
            }
        }

        return ast;
    }

    private static RelationSnapshot findRelation(GraphSnapshot snapshot, String relName) {
        if (snapshot == null || relName == null) return null;
        RelationSnapshot rel = snapshot.getRelationSnapshot(relName);
        if (rel != null) return rel;

        for (var entry : snapshot.getAllRelationSnapshots().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(relName) ||
                entry.getKey().endsWith("_" + relName) ||
                entry.getKey().toLowerCase().endsWith(relName.toLowerCase())) {
                return entry.getValue();
            }
        }
        return null;
    }
}
