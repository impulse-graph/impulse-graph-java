package org.impulsegraph.compiler.passes.stage2;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.core.csr.GraphSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 Pass: Binds logical relation names to physical 16-bit relation catalog IDs.
 */
public final class PhysicalBindingPass implements CompilerPass {

    public static final PhysicalBindingPass INSTANCE = new PhysicalBindingPass();

    @Override
    public String name() {
        return "PhysicalBindingPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        GraphSnapshot snapshot = context.snapshot();
        if (snapshot == null) {
            return ast;
        }
        return bindNode(ast, snapshot);
    }

    private ImpScmNode bindNode(ImpScmNode node, GraphSnapshot snapshot) {
        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> bound = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                bound.add(bindNode(step, snapshot));
            }
            return new ScmProgram(bound);
        }

        if (node instanceof ScmWalk walk) {
            int relId = walk.relationId();
            if (relId < 0 && !walk.relationName().isEmpty()) {
                relId = resolveRelationId(snapshot, walk.relationName());
            }

            ImpScmNode boundFilter = walk.filterPredicate() != null ? bindNode(walk.filterPredicate(), snapshot) : null;
            List<ImpScmNode> boundSubs = new ArrayList<>();
            for (ImpScmNode sub : walk.subSteps()) {
                boundSubs.add(bindNode(sub, snapshot));
            }
            return new ScmWalk(walk.relationName(), relId, walk.direction(), boundFilter, boundSubs);
        }

        if (node instanceof ScmWalk2Hop hop2) {
            int id1 = hop2.relation1Id();
            if (id1 < 0 && !hop2.relation1Name().isEmpty()) {
                id1 = resolveRelationId(snapshot, hop2.relation1Name());
            }
            int id2 = hop2.relation2Id();
            if (id2 < 0 && !hop2.relation2Name().isEmpty()) {
                id2 = resolveRelationId(snapshot, hop2.relation2Name());
            }
            return hop2.withPhysicalIds(id1, id2);
        }

        if (node instanceof ScmVectorFilter vf) {
            return new ScmVectorFilter(bindNode(vf.predicate(), snapshot));
        }

        return node;
    }

    private static int resolveRelationId(GraphSnapshot snapshot, String relName) {
        if (snapshot == null || relName == null) return 0;
        Map<String, ?> map = snapshot.getAllRelationSnapshots();

        int idx = 0;
        for (String key : map.keySet()) {
            if (key.equalsIgnoreCase(relName)) return idx;
            idx++;
        }

        idx = 0;
        for (String key : map.keySet()) {
            if (key.endsWith("_" + relName) || key.endsWith(relName) || key.toLowerCase().endsWith(relName.toLowerCase())) {
                return idx;
            }
            idx++;
        }

        return 0;
    }
}
