package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.api.stats.AttributeStatistics;
import org.impulsegraph.api.stats.AttributeStatistics.Monotonicity;
import org.impulsegraph.api.stats.GraphStatistics;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature.IntervalBound;
import org.impulsegraph.compiler.ast.algebra.AlgebraicSignature.MorphismClass;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 Pass: Universal Algebraic Type & Property Inference.
 * Binds snapshot attribute zone maps, infers interval bounds, computes variance/monotonicity,
 * and annotates AST nodes with algebraic signatures.
 */
public final class AlgebraicTypeInferencePass implements CompilerPass {

    public static final AlgebraicTypeInferencePass INSTANCE = new AlgebraicTypeInferencePass();

    @Override
    public String name() {
        return "AlgebraicTypeInferencePass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        ImpulseGraphSnapshot snapshot = context.snapshot();
        GraphStatistics stats = snapshot != null ? snapshot.getGraphStatistics() : null;
        return inferScm(ast, stats);
    }

    private ImpScmNode inferScm(ImpScmNode node, GraphStatistics stats) {
        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> steps = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                steps.add(inferScm(step, stats));
            }
            return new ScmProgram(steps);
        }

        if (node instanceof ScmCelExpr celExpr) {
            CelAstNode rawCel = (CelAstNode) celExpr.celAst();
            if (rawCel != null) {
                CelAstNode annotatedCel = inferCel(rawCel, stats);
                return new ScmCelExpr(celExpr.rawText(), annotatedCel);
            }
            return celExpr;
        }

        if (node instanceof ScmWalk walk) {
            ImpScmNode optFilter = walk.filterPredicate() != null ? inferScm(walk.filterPredicate(), stats) : null;
            List<ImpScmNode> optSubs = new ArrayList<>();
            for (ImpScmNode sub : walk.subSteps()) {
                optSubs.add(inferScm(sub, stats));
            }
            return new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), optFilter, optSubs);
        }

        if (node instanceof ScmVectorFilter vf) {
            return new ScmVectorFilter(inferScm(vf.predicate(), stats));
        }

        return node;
    }

    public CelAstNode inferCel(CelAstNode node, GraphStatistics stats) {
        if (node == null) return null;

        // 1. Recursive bottom-up inference on children
        List<CelAstNode> inferredChildren = new ArrayList<>();
        for (CelAstNode child : node.children()) {
            inferredChildren.add(inferCel(child, stats));
        }

        CelAstNode nodeWithChildren = node.withChildren(inferredChildren);

        // 2. Synthesize signature based on node kind
        AlgebraicSignature sig = switch (nodeWithChildren.kind()) {
            case LITERAL_INT -> AlgebraicSignature.ofConstantInt(nodeWithChildren.intVal());
            case LITERAL_FLOAT -> AlgebraicSignature.ofConstantFloat(nodeWithChildren.floatVal());
            case LITERAL_BOOL -> AlgebraicSignature.ofConstantBool(nodeWithChildren.boolVal());
            case MEMBER_ACCESS -> inferMemberAccess(nodeWithChildren, stats);
            case IDENTIFIER -> inferIdentifier(nodeWithChildren, stats);
            case UNARY_OP -> inferUnaryOp(nodeWithChildren);
            case BINARY_OP -> inferBinaryOp(nodeWithChildren);
            case FUNCTION_CALL -> inferFunctionCall(nodeWithChildren);
            default -> nodeWithChildren.signature() != null ? nodeWithChildren.signature() : AlgebraicSignature.defaultGeneral();
        };

        return nodeWithChildren.withSignature(sig);
    }

    private AlgebraicSignature inferMemberAccess(CelAstNode node, GraphStatistics stats) {
        String field = node.text();
        if (stats != null) {
            AttributeStatistics attrStats = stats.getAttributeStatistics(field);
            if (attrStats != null) {
                IntervalBound bound = IntervalBound.ofInt(attrStats.minIntVal(), attrStats.maxIntVal());
                return new AlgebraicSignature(
                        bound,
                        MorphismClass.GENERAL,
                        AlgebraicSignature.ALG_NONE,
                        attrStats.monotonicity(),
                        AlgebraicSignature.HOMO_NONE,
                        true,
                        false,
                        false,
                        0,
                        0.0
                );
            }
        }
        return AlgebraicSignature.defaultGeneral();
    }

    private AlgebraicSignature inferIdentifier(CelAstNode node, GraphStatistics stats) {
        String name = node.text();
        if (stats != null) {
            AttributeStatistics attrStats = stats.getAttributeStatistics(name);
            if (attrStats != null) {
                IntervalBound bound = IntervalBound.ofInt(attrStats.minIntVal(), attrStats.maxIntVal());
                return new AlgebraicSignature(
                        bound,
                        MorphismClass.GENERAL,
                        AlgebraicSignature.ALG_NONE,
                        attrStats.monotonicity(),
                        AlgebraicSignature.HOMO_NONE,
                        true,
                        false,
                        false,
                        0,
                        0.0
                );
            }
        }
        return AlgebraicSignature.defaultGeneral();
    }

    private AlgebraicSignature inferUnaryOp(CelAstNode node) {
        if (node.children().isEmpty()) return AlgebraicSignature.defaultGeneral();
        AlgebraicSignature childSig = node.children().get(0).signature();
        if (childSig == null) childSig = AlgebraicSignature.defaultGeneral();

        String op = node.text();
        if ("-".equals(op) && childSig.interval().isBounded()) {
            IntervalBound b = childSig.interval();
            IntervalBound neg = new IntervalBound(-b.maxInt(), -b.minInt(), -b.maxFloat(), -b.minFloat(), true);
            Monotonicity mono = flipMonotonicity(childSig.monotonicity());
            return new AlgebraicSignature(
                    neg,
                    MorphismClass.GENERAL,
                    AlgebraicSignature.ALG_NONE,
                    mono,
                    AlgebraicSignature.HOMO_COMMUTES_WITH_MIN, // -max(X) == min(-X)
                    childSig.isPure(),
                    childSig.isConstantKnown(),
                    childSig.constantBoolVal(),
                    -childSig.constantIntVal(),
                    -childSig.constantFloatVal()
            );
        }

        if ("!".equals(op) && childSig.isConstantKnown()) {
            return AlgebraicSignature.ofConstantBool(!childSig.constantBoolVal());
        }

        return AlgebraicSignature.defaultGeneral();
    }

    private AlgebraicSignature inferBinaryOp(CelAstNode node) {
        if (node.children().size() < 2) return AlgebraicSignature.defaultGeneral();
        AlgebraicSignature left = node.children().get(0).signature();
        AlgebraicSignature right = node.children().get(1).signature();
        if (left == null) left = AlgebraicSignature.defaultGeneral();
        if (right == null) right = AlgebraicSignature.defaultGeneral();

        String op = node.text();

        // 1. Check interval bounds for arithmetic
        if (left.interval().isBounded() && right.interval().isBounded()) {
            IntervalBound l = left.interval();
            IntervalBound r = right.interval();

            if ("+".equals(op)) {
                IntervalBound sum = new IntervalBound(l.minInt() + r.minInt(), l.maxInt() + r.maxInt(), l.minFloat() + r.minFloat(), l.maxFloat() + r.maxFloat(), true);
                return new AlgebraicSignature(
                        sum,
                        MorphismClass.GENERAL,
                        AlgebraicSignature.ALG_SEMIGROUP | AlgebraicSignature.ALG_MONOID | AlgebraicSignature.ALG_COMMUTATIVE,
                        combineMonotonicity(left.monotonicity(), right.monotonicity()),
                        AlgebraicSignature.HOMO_COMMUTES_WITH_SUM,
                        left.isPure() && right.isPure(),
                        left.isConstantKnown() && right.isConstantKnown(),
                        false,
                        left.constantIntVal() + right.constantIntVal(),
                        left.constantFloatVal() + right.constantFloatVal()
                );
            }

            // Comparison bounds checking (Zone map evaluation)
            if (">".equals(op)) {
                if (l.minInt() > r.maxInt()) return AlgebraicSignature.ofConstantBool(true);
                if (l.maxInt() <= r.minInt()) return AlgebraicSignature.ofConstantBool(false);
            } else if (">=".equals(op)) {
                if (l.minInt() >= r.maxInt()) return AlgebraicSignature.ofConstantBool(true);
                if (l.maxInt() < r.minInt()) return AlgebraicSignature.ofConstantBool(false);
            } else if ("<".equals(op)) {
                if (l.maxInt() < r.minInt()) return AlgebraicSignature.ofConstantBool(true);
                if (l.minInt() >= r.maxInt()) return AlgebraicSignature.ofConstantBool(false);
            } else if ("<=".equals(op)) {
                if (l.maxInt() <= r.minInt()) return AlgebraicSignature.ofConstantBool(true);
                if (l.minInt() > r.maxInt()) return AlgebraicSignature.ofConstantBool(false);
            } else if ("==".equals(op)) {
                if (l.maxInt() < r.minInt() || l.minInt() > r.maxInt()) return AlgebraicSignature.ofConstantBool(false);
            } else if ("!=".equals(op)) {
                if (l.maxInt() < r.minInt() || l.minInt() > r.maxInt()) return AlgebraicSignature.ofConstantBool(true);
            }
        }

        // Boolean Conjunction / Disjunction Absorptive Laws
        if ("&&".equals(op)) {
            if ((left.isConstantKnown() && !left.constantBoolVal()) || (right.isConstantKnown() && !right.constantBoolVal())) {
                return AlgebraicSignature.ofConstantBool(false);
            }
            if (left.isConstantKnown() && left.constantBoolVal() && right.isConstantKnown() && right.constantBoolVal()) {
                return AlgebraicSignature.ofConstantBool(true);
            }
        } else if ("||".equals(op)) {
            if ((left.isConstantKnown() && left.constantBoolVal()) || (right.isConstantKnown() && right.constantBoolVal())) {
                return AlgebraicSignature.ofConstantBool(true);
            }
            if (left.isConstantKnown() && !left.constantBoolVal() && right.isConstantKnown() && !right.constantBoolVal()) {
                return AlgebraicSignature.ofConstantBool(false);
            }
        }

        return AlgebraicSignature.defaultGeneral();
    }

    private AlgebraicSignature inferFunctionCall(CelAstNode node) {
        String func = node.text().toLowerCase();

        // Monotonic Homomorphisms
        if (func.equals("log") || func.equals("exp") || func.equals("sqrt")) {
            return new AlgebraicSignature(
                    IntervalBound.UNBOUNDED,
                    MorphismClass.GENERAL,
                    AlgebraicSignature.ALG_NONE,
                    Monotonicity.MONO_STRICT_INC,
                    AlgebraicSignature.HOMO_COMMUTES_WITH_MAX | AlgebraicSignature.HOMO_COMMUTES_WITH_MIN,
                    true,
                    false,
                    false,
                    0,
                    0.0
            );
        }

        // Idempotent Commutative Semilattices (MAX, MIN)
        if (func.equals("max") || func.equals("min") || func.equals("argmin") || func.equals("argmax")) {
            return new AlgebraicSignature(
                    IntervalBound.UNBOUNDED,
                    MorphismClass.GENERAL,
                    AlgebraicSignature.ALG_SEMIGROUP | AlgebraicSignature.ALG_MONOID |
                    AlgebraicSignature.ALG_COMMUTATIVE | AlgebraicSignature.ALG_IDEMPOTENT,
                    Monotonicity.MONO_NONE,
                    AlgebraicSignature.HOMO_NONE,
                    true,
                    false,
                    false,
                    0,
                    0.0
            );
        }

        return AlgebraicSignature.defaultGeneral();
    }

    private Monotonicity flipMonotonicity(Monotonicity m) {
        if (m == null) return Monotonicity.MONO_NONE;
        return switch (m) {
            case MONO_STRICT_INC -> Monotonicity.MONO_STRICT_DEC;
            case MONO_WEAK_INC -> Monotonicity.MONO_WEAK_DEC;
            case MONO_STRICT_DEC -> Monotonicity.MONO_STRICT_INC;
            case MONO_WEAK_DEC -> Monotonicity.MONO_WEAK_INC;
            default -> m;
        };
    }

    private Monotonicity combineMonotonicity(Monotonicity a, Monotonicity b) {
        if (a == Monotonicity.MONO_CONSTANT) return b;
        if (b == Monotonicity.MONO_CONSTANT) return a;
        if (a == Monotonicity.MONO_STRICT_INC && b == Monotonicity.MONO_STRICT_INC) return Monotonicity.MONO_STRICT_INC;
        if (a == Monotonicity.MONO_STRICT_DEC && b == Monotonicity.MONO_STRICT_DEC) return Monotonicity.MONO_STRICT_DEC;
        return Monotonicity.MONO_NONE;
    }
}
