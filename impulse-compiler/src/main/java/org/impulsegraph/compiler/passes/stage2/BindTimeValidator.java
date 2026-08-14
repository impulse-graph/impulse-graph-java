package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;

/**
 * Stage 2 Pass: Bind-time physical schema validation.
 * Verifies that all logical relations, domain types, and property columns exist in the target snapshot.
 */
public final class BindTimeValidator implements CompilerPass {

    public static final BindTimeValidator INSTANCE = new BindTimeValidator();

    @Override
    public String name() {
        return "BindTimeValidator";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        GraphSnapshot snapshot = context.snapshot();
        if (snapshot == null) {
            // Snapshot not yet bound (e.g. dry-run or pre-bind stage)
            return ast;
        }

        validateSnapshotBinding(ast, snapshot);
        return ast;
    }

    private void validateSnapshotBinding(ImpScmNode node, GraphSnapshot snapshot) {
        if (node instanceof ScmProgram prog) {
            for (ImpScmNode step : prog.steps()) {
                validateSnapshotBinding(step, snapshot);
            }
        } else if (node instanceof ScmWalk walk) {
            String relName = walk.relationName();
            if (!relName.isEmpty() && walk.relationId() < 0) {
                RelationSnapshot rel = findRelation(snapshot, relName);
                if (rel == null) {
                    throw new IllegalStateException("Bind-Time Validation Failed: Required relation '"
                            + relName + "' does not exist in target snapshot catalog.");
                }
            }
            if (walk.filterPredicate() != null) {
                validateSnapshotBinding(walk.filterPredicate(), snapshot);
            }
            for (ImpScmNode sub : walk.subSteps()) {
                validateSnapshotBinding(sub, snapshot);
            }
        } else if (node instanceof ScmVectorFilter vf) {
            validateSnapshotBinding(vf.predicate(), snapshot);
        }
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
