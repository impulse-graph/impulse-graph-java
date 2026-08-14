package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 Pass: Canonicalizes AST structure, flattens associative nested operations,
 * and standardizes S-expression step structures.
 */
public final class AstNormalizationPass implements CompilerPass {

    public static final AstNormalizationPass INSTANCE = new AstNormalizationPass();

    @Override
    public String name() {
        return "AstNormalizationPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        return normalizeNode(ast);
    }

    private ImpScmNode normalizeNode(ImpScmNode node) {
        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> normSteps = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                normSteps.add(normalizeNode(step));
            }
            return new ScmProgram(normSteps);
        }

        if (node instanceof ScmWalk walk) {
            ImpScmNode normFilter = walk.filterPredicate() != null ? normalizeNode(walk.filterPredicate()) : null;
            List<ImpScmNode> normSubs = new ArrayList<>();
            for (ImpScmNode sub : walk.subSteps()) {
                normSubs.add(normalizeNode(sub));
            }
            return new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), normFilter, normSubs);
        }

        if (node instanceof ScmVectorFilter vf) {
            return new ScmVectorFilter(normalizeNode(vf.predicate()));
        }

        if (node instanceof ScmList list) {
            List<ImpScmNode> normElems = new ArrayList<>();
            for (ImpScmNode elem : list.elements()) {
                normElems.add(normalizeNode(elem));
            }
            return new ScmList(normElems);
        }

        return node;
    }
}
