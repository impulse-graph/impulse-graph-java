package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 Pass: Zone Map Pruning & Dead Code Elimination.
 * Evaluates predicates against precomputed attribute zone maps:
 * 1. If predicate is provably FALSE (e.g. u.age > 250 when max(age)==114), prunes traversal branch.
 * 2. If predicate is provably TRUE for 100% of rows, strips the filter from the inner traversal loop.
 */
public final class ZoneMapPruningPass implements CompilerPass {

    public static final ZoneMapPruningPass INSTANCE = new ZoneMapPruningPass();

    @Override
    public String name() {
        return "ZoneMapPruningPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        ImpulseGraphSnapshot snapshot = context.snapshot();
        GraphStatistics stats = snapshot != null ? snapshot.getGraphStatistics() : null;
        return pruneScm(ast, stats);
    }

    private ImpScmNode pruneScm(ImpScmNode node, GraphStatistics stats) {
        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> prunedSteps = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                ImpScmNode p = pruneScm(step, stats);
                if (p != null) {
                    prunedSteps.add(p);
                }
            }
            return new ScmProgram(prunedSteps);
        }

        if (node instanceof ScmCelExpr celExpr) {
            CelAstNode cel = (CelAstNode) celExpr.celAst();
            if (cel != null) {
                CelAstNode prunedCel = pruneCel(cel);
                if (prunedCel.signature() != null && prunedCel.signature().isConstantKnown()) {
                    if (!prunedCel.signature().constantBoolVal()) {
                        return ScmSymbol.of("constant-false");
                    } else {
                        return ScmSymbol.of("constant-true");
                    }
                }
                if (prunedCel.kind() == CelAstNode.Kind.LITERAL_BOOL) {
                    return prunedCel.boolVal() ? ScmSymbol.of("constant-true") : ScmSymbol.of("constant-false");
                }
                return new ScmCelExpr(celExpr.rawText(), prunedCel);
            }
            return celExpr;
        }

        if (node instanceof ScmList list && list.elements().size() >= 3) {
            ImpScmNode folded = foldScmListPredicate(list, stats);
            if (folded != null) {
                return folded;
            }
        }

        if (node instanceof ScmWalk walk) {
            ImpScmNode filter = walk.filterPredicate();
            if (filter != null) {
                ImpScmNode prunedFilter = pruneScm(filter, stats);
                // If filter is constant false, the walk produces empty set
                if (isConstantFalse(prunedFilter)) {
                    return null; // Pruned completely from execution pipeline!
                }
                // If filter is constant true, strip the filter completely (zero inner-loop overhead!)
                if (isConstantTrue(prunedFilter)) {
                    prunedFilter = null;
                }
                List<ImpScmNode> subs = new ArrayList<>();
                for (ImpScmNode sub : walk.subSteps()) {
                    ImpScmNode ps = pruneScm(sub, stats);
                    if (ps != null) subs.add(ps);
                }
                return new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), prunedFilter, subs);
            }
            return walk;
        }

        if (node instanceof ScmVectorFilter vf) {
            ImpScmNode pruned = pruneScm(vf.predicate(), stats);
            if (isConstantFalse(pruned)) {
                return null; // Whole filter step pruned
            }
            if (isConstantTrue(pruned)) {
                return null; // Stripped completely
            }
            return new ScmVectorFilter(pruned);
        }

        return node;
    }

    private ImpScmNode foldScmListPredicate(ScmList list, GraphStatistics stats) {
        if (stats == null || list.elements().size() < 3) return null;

        String op = list.elements().get(0).toScmString();
        ImpScmNode lhs = list.elements().get(1);
        ImpScmNode rhs = list.elements().get(2);

        String attrName = extractAttrName(lhs);
        if (attrName != null && rhs instanceof ScmLiteral.ScmInt intLit) {
            AttributeStatistics attrStats = stats.getAttributeStatistics(attrName);
            if (attrStats != null) {
                long val = intLit.value();
                long min = attrStats.minIntVal();
                long max = attrStats.maxIntVal();

                if (op.contains("gt") || op.contains(">")) {
                    if (val >= max) return ScmLiteral.ofBool(false);
                    if (val < min) return ScmLiteral.ofBool(true);
                } else if (op.contains("gte") || op.contains(">=")) {
                    if (val > max) return ScmLiteral.ofBool(false);
                    if (val <= min) return ScmLiteral.ofBool(true);
                } else if (op.contains("lt") || op.contains("<")) {
                    if (val <= min) return ScmLiteral.ofBool(false);
                    if (val > max) return ScmLiteral.ofBool(true);
                } else if (op.contains("lte") || op.contains("<=")) {
                    if (val < min) return ScmLiteral.ofBool(false);
                    if (val >= max) return ScmLiteral.ofBool(true);
                } else if (op.contains("eq") || op.contains("==")) {
                    if (val < min || val > max) return ScmLiteral.ofBool(false);
                }
            }
        }

        return null;
    }

    private String extractAttrName(ImpScmNode node) {
        if (node instanceof ScmList list && list.elements().size() >= 3) {
            String sym = list.elements().get(0).toScmString();
            if (sym.contains("get-attr")) {
                String raw = list.elements().get(2).toScmString();
                return raw.replace("\"", "").trim();
            }
        }
        return null;
    }

    public CelAstNode pruneCel(CelAstNode node) {
        if (node == null) return null;

        // Top-level constant fold
        AlgebraicSignature sig = node.signature();
        if (sig != null && sig.isConstantKnown()) {
            return CelAstNode.makeBool(sig.constantBoolVal());
        }

        List<CelAstNode> children = new ArrayList<>();
        for (CelAstNode child : node.children()) {
            children.add(pruneCel(child));
        }

        CelAstNode result = node.withChildren(children);

        // Boolean absorptive pruning
        if (result.kind() == CelAstNode.Kind.BINARY_OP) {
            String op = result.text();
            if (children.size() >= 2) {
                CelAstNode left = children.get(0);
                CelAstNode right = children.get(1);

                if ("&&".equals(op)) {
                    if (isFalse(left) || isFalse(right)) return CelAstNode.makeBool(false);
                    if (isTrue(left)) return right;
                    if (isTrue(right)) return left;
                } else if ("||".equals(op)) {
                    if (isTrue(left) || isTrue(right)) return CelAstNode.makeBool(true);
                    if (isFalse(left)) return right;
                    if (isFalse(right)) return left;
                }
            }
        }

        return result;
    }

    private boolean isTrue(CelAstNode node) {
        return node.kind() == CelAstNode.Kind.LITERAL_BOOL && node.boolVal();
    }

    private boolean isFalse(CelAstNode node) {
        return node.kind() == CelAstNode.Kind.LITERAL_BOOL && !node.boolVal();
    }

    private boolean isConstantFalse(ImpScmNode node) {
        if (node instanceof ScmSymbol s) return "constant-false".equals(s.name()) || "#f".equals(s.name());
        if (node instanceof ScmLiteral.ScmBool b) return !b.value();
        return false;
    }

    private boolean isConstantTrue(ImpScmNode node) {
        if (node instanceof ScmSymbol s) return "constant-true".equals(s.name()) || "#t".equals(s.name());
        if (node instanceof ScmLiteral.ScmBool b) return b.value();
        return false;
    }
}
