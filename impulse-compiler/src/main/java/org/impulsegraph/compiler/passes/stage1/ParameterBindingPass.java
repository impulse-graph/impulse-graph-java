package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Stage 1 Pass: Parameter Binding & Constant Substitution.
 * Injects compile-time bound parameter values (e.g. @p1 -> "FRUIT", @minVoltage -> 0.95)
 * into CEL AST expressions prior to algebraic property inference and zone map pruning.
 */
public final class ParameterBindingPass implements CompilerPass {

    public static final ParameterBindingPass INSTANCE = new ParameterBindingPass();

    @Override
    public String name() {
        return "ParameterBindingPass";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) return null;
        Map<String, Object> params = context.parameters();
        if (params == null || params.isEmpty()) {
            return ast;
        }
        return bindScm(ast, params);
    }

    private ImpScmNode bindScm(ImpScmNode node, Map<String, Object> params) {
        if (node instanceof ScmProgram prog) {
            List<ImpScmNode> steps = new ArrayList<>();
            for (ImpScmNode step : prog.steps()) {
                steps.add(bindScm(step, params));
            }
            return new ScmProgram(steps);
        }

        if (node instanceof ScmCelExpr celExpr) {
            CelAstNode cel = (CelAstNode) celExpr.celAst();
            if (cel != null) {
                CelAstNode boundCel = bindCel(cel, params);
                return new ScmCelExpr(celExpr.rawText(), boundCel);
            }
            return celExpr;
        }

        if (node instanceof ScmWalk walk) {
            ImpScmNode filter = walk.filterPredicate() != null ? bindScm(walk.filterPredicate(), params) : null;
            List<ImpScmNode> subs = new ArrayList<>();
            for (ImpScmNode sub : walk.subSteps()) {
                subs.add(bindScm(sub, params));
            }
            return new ScmWalk(walk.relationName(), walk.relationId(), walk.direction(), filter, subs);
        }

        if (node instanceof ScmVectorFilter vf) {
            return new ScmVectorFilter(bindScm(vf.predicate(), params));
        }

        return node;
    }

    public CelAstNode bindCel(CelAstNode node, Map<String, Object> params) {
        if (node == null) return null;

        if (node.kind() == CelAstNode.Kind.PARAMETER_REF) {
            String paramName = node.text();
            Object val = lookupParam(paramName, params);
            if (val != null) {
                return convertToLiteral(val);
            }
        }

        List<CelAstNode> boundChildren = new ArrayList<>();
        for (CelAstNode child : node.children()) {
            boundChildren.add(bindCel(child, params));
        }

        return node.withChildren(boundChildren);
    }

    private Object lookupParam(String key, Map<String, Object> params) {
        if (params.containsKey(key)) return params.get(key);

        String rawKey = key.startsWith("@") ? key.substring(1) : key;
        if (params.containsKey(rawKey)) return params.get(rawKey);

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String k = entry.getKey();
            if (k.equalsIgnoreCase(key) || k.equalsIgnoreCase(rawKey)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private CelAstNode convertToLiteral(Object val) {
        if (val instanceof Integer i) {
            return CelAstNode.makeInt(i.longValue());
        }
        if (val instanceof Long l) {
            return CelAstNode.makeInt(l);
        }
        if (val instanceof Double d) {
            return CelAstNode.makeFloat(d);
        }
        if (val instanceof Float f) {
            return CelAstNode.makeFloat(f.doubleValue());
        }
        if (val instanceof Boolean b) {
            return CelAstNode.makeBool(b);
        }
        if (val instanceof String s) {
            return CelAstNode.makeString(s);
        }
        return CelAstNode.makeString(String.valueOf(val));
    }
}
