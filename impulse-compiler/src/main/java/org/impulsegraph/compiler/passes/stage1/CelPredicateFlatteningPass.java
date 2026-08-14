package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.parser.ImpScmParser;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelCompiler;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 Pass: Flattens raw CEL expressions into structured ImpScheme vector predicate ASTs.
 */
public final class CelPredicateFlatteningPass implements CompilerPass {

    public static final CelPredicateFlatteningPass INSTANCE = new CelPredicateFlatteningPass();

    @Override
    public String name() {
        return "CelPredicateFlatteningPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        return flattenNode(ast);
    }

    private ImpScmNode flattenNode(ImpScmNode node) {
        if (node instanceof ScmCelExpr cel) {
            CelAstNode celAst = cel.celAst();
            if (celAst == null) {
                celAst = CelParser.parse(cel.rawText());
            }
            String scmStr = CelCompiler.toImpScheme(celAst);
            ImpScmNode parsed = ImpScmParser.parse(scmStr);
            return parsed != null ? parsed : node;
        }

        if (node instanceof ScmVectorFilter vf) {
            return new ScmVectorFilter(flattenNode(vf.predicate()));
        }

        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> flattened = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                flattened.add(flattenNode(step));
            }
            return new ScmProgram(flattened);
        }

        if (node instanceof ScmWalk walk) {
            ImpScmNode flatFilter = walk.filterPredicate() != null ? flattenNode(walk.filterPredicate()) : null;
            List<ImpScmNode> flatSubs = new ArrayList<>();
            for (ImpScmNode sub : walk.subSteps()) {
                flatSubs.add(flattenNode(sub));
            }
            return new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), flatFilter, flatSubs);
        }

        if (node instanceof ScmList list) {
            List<ImpScmNode> flattened = new ArrayList<>();
            for (ImpScmNode elem : list.elements()) {
                flattened.add(flattenNode(elem));
            }
            return new ScmList(flattened);
        }

        return node;
    }
}
