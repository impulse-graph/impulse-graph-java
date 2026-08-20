package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelAstOptimizer;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 Pass: Folds compile-time static constants and algebraic identities.
 */
public final class ConstantFoldingPass implements CompilerPass {

    public static final ConstantFoldingPass INSTANCE = new ConstantFoldingPass();

    @Override
    public String name() {
        return "ConstantFoldingPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        if (!context.options().enableConstantFolding()) {
            return ast;
        }
        return foldNode(ast);
    }

    private ImpScmNode foldNode(ImpScmNode node) {
        if (node instanceof ScmCelExpr cel) {
            if (cel.celAst() != null) {
                CelAstNode optimized = CelAstOptimizer.optimize((CelAstNode) cel.celAst());
                return new ScmCelExpr(cel.rawText(), optimized);
            }
            return cel;
        }

        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> folded = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                folded.add(foldNode(step));
            }
            return new ScmProgram(folded);
        }

        if (node instanceof ScmWalk walk) {
            ImpScmNode foldFilter = walk.filterPredicate() != null ? foldNode(walk.filterPredicate()) : null;
            List<ImpScmNode> foldSubs = new ArrayList<>();
            for (ImpScmNode sub : walk.subSteps()) {
                foldSubs.add(foldNode(sub));
            }
            return new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), foldFilter, foldSubs);
        }

        if (node instanceof ScmVectorFilter vf) {
            return new ScmVectorFilter(foldNode(vf.predicate()));
        }

        if (node instanceof ScmList list) {
            List<ImpScmNode> folded = new ArrayList<>();
            for (ImpScmNode elem : list.elements()) {
                folded.add(foldNode(elem));
            }
            return new ScmList(folded);
        }

        return node;
    }
}
