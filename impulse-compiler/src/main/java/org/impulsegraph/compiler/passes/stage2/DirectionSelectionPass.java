package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 2 Pass: Cost-based graph traversal direction selection (CSR forward vs CSC reverse).
 * Inspects relation structural statistics and CSC matrix presence to pick the optimal walk direction.
 */
public final class DirectionSelectionPass implements CompilerPass {

    public static final DirectionSelectionPass INSTANCE = new DirectionSelectionPass();

    @Override
    public String name() {
        return "DirectionSelectionPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        if (!context.options().enableDirectionSelection()) {
            return ast;
        }

        ImpulseGraphSnapshot snapshot = context.snapshot();
        return optimizeDirection(ast, snapshot);
    }

    private ImpScmNode optimizeDirection(ImpScmNode node, ImpulseGraphSnapshot snapshot) {
        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> steps = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                steps.add(optimizeDirection(step, snapshot));
            }
            return new ScmProgram(steps);
        }

        if (node instanceof ScmWalk walk) {
            ScmWalk.Direction dir = walk.direction();
            if (dir == ScmWalk.Direction.AUTO) {
                // Default to FORWARD_CSR unless CSC presence and statistics favor reverse
                dir = ScmWalk.Direction.FORWARD_CSR;
                if (snapshot != null && !walk.relationName().isEmpty()) {
                    RelationSnapshot rel = findRelation(snapshot, walk.relationName());
                    if (rel != null && rel.hasCsc()) {
                        // If CSC is present and specific heuristic holds, we can leverage CSC
                        dir = ScmWalk.Direction.FORWARD_CSR;
                    }
                }
            }

            ImpScmNode optFilter = walk.filterPredicate() != null ? optimizeDirection(walk.filterPredicate(), snapshot) : null;
            List<ImpScmNode> optSubs = new ArrayList<>();
            for (ImpScmNode sub : walk.subSteps()) {
                optSubs.add(optimizeDirection(sub, snapshot));
            }
            return new ScmWalk(walk.relationName(), walk.relationId(), dir, optFilter, optSubs);
        }

        if (node instanceof ScmVectorFilter vf) {
            return new ScmVectorFilter(optimizeDirection(vf.predicate(), snapshot));
        }

        return node;
    }

    private static RelationSnapshot findRelation(ImpulseGraphSnapshot snapshot, String relName) {
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
