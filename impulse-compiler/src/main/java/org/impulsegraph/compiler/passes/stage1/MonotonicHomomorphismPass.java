package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 Pass: Monotonic Homomorphism Pushdown.
 * Exploit join-semilattice homomorphisms to commute monotonic functions across aggregations:
 * max(f(X)) -> f(max(X))   (when f is strictly monotonic increasing)
 * max(g(X)) -> g(min(X))   (when g is strictly monotonic decreasing)
 */
public final class MonotonicHomomorphismPass implements CompilerPass {

    public static final MonotonicHomomorphismPass INSTANCE = new MonotonicHomomorphismPass();

    @Override
    public String name() {
        return "MonotonicHomomorphismPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        return optimizeScm(ast);
    }

    private ImpScmNode optimizeScm(ImpScmNode node) {
        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> steps = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                steps.add(optimizeScm(step));
            }
            return new ScmProgram(steps);
        }

        if (node instanceof ScmCelExpr celExpr) {
            CelAstNode cel = (CelAstNode) celExpr.celAst();
            if (cel != null) {
                CelAstNode optCel = optimizeCel(cel);
                return new ScmCelExpr(celExpr.rawText(), optCel);
            }
            return celExpr;
        }

        return node;
    }

    public CelAstNode optimizeCel(CelAstNode node) {
        if (node == null) return null;

        List<CelAstNode> children = new ArrayList<>();
        for (CelAstNode child : node.children()) {
            children.add(optimizeCel(child));
        }

        CelAstNode result = node.withChildren(children);

        // Pattern: max(f(x)) or min(f(x))
        if (result.kind() == CelAstNode.Kind.FUNCTION_CALL) {
            String func = result.text().toLowerCase();
            if (("max".equals(func) || "min".equals(func)) && children.size() == 1) {
                CelAstNode arg = children.get(0);

                // Case 1: max(log(x)) -> log(max(x))
                if (arg.kind() == CelAstNode.Kind.FUNCTION_CALL && arg.signature() != null) {
                    if (arg.signature().commutesWithMax() || arg.signature().monotonicity() == Monotonicity.MONO_STRICT_INC) {
                        String innerFunc = arg.text();
                        CelAstNode innerArg = arg.children().isEmpty() ? null : arg.children().get(0);
                        if (innerArg != null) {
                            CelAstNode newAgg = CelAstNode.makeCall(func, List.of(innerArg));
                            return CelAstNode.makeCall(innerFunc, List.of(newAgg));
                        }
                    }
                }

                // Case 2: max(-x) -> -min(x) (Decreasing monotonicity meet-join inversion)
                if (arg.kind() == CelAstNode.Kind.UNARY_OP && "-".equals(arg.text()) && !arg.children().isEmpty()) {
                    String dualFunc = "max".equals(func) ? "min" : "max";
                    CelAstNode innerArg = arg.children().get(0);
                    CelAstNode newAgg = CelAstNode.makeCall(dualFunc, List.of(innerArg));
                    return CelAstNode.makeUnary("-", newAgg);
                }
            }
        }

        return result;
    }
}
