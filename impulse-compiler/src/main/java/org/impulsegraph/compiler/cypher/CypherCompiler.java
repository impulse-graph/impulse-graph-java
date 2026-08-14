package org.impulsegraph.compiler.cypher;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.cel.CelAstNode;
import org.impulsegraph.compiler.cel.CelParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lowers parsed Cypher AST queries into optimized ImpScheme S-Expression AST (ScmProgram).
 */
public final class CypherCompiler {

    public record CompilationResult(
            ScmProgram ast,
            String seedVariable,
            String seedParameterOrValue
    ) {}

    private CypherCompiler() {}

    /**
     * Parse openCypher text and compile into ImpScheme AST.
     */
    public static CompilationResult compile(String cypherQuery) {
        Objects.requireNonNull(cypherQuery, "cypherQuery must not be null");
        CypherParser.CypherQuery parsed = CypherParser.parse(cypherQuery);
        return compile(parsed);
    }

    /**
     * Lowers a structured CypherQuery to ScmProgram.
     */
    public static CompilationResult compile(CypherParser.CypherQuery query) {
        List<ImpScmNode> steps = new ArrayList<>();
        String seedVar = query.path().startNode().variable();
        String seedParamOrVal = null;

        // 1. Identify seed node constraint from WHERE predicates
        for (CypherParser.WherePredicate pred : query.wherePredicates()) {
            if (pred.targetVar().equals(seedVar)) {
                seedParamOrVal = pred.valueOrParam();
            }
        }

        // 2. Lower Path Steps
        for (CypherParser.PathStep step : query.path().steps()) {
            CypherParser.EdgePattern edge = step.edge();
            String rel = edge.relationName();
            boolean isForward = edge.isForward();

            // Look for attached edge predicates
            ScmCelExpr predicate = null;
            if (edge.variable() != null) {
                for (CypherParser.WherePredicate pred : query.wherePredicates()) {
                    if (pred.targetVar().equals(edge.variable())) {
                        String celSource = "edge." + pred.field() + " " + pred.op() + " " + pred.valueOrParam();
                        CelAstNode celAst = CelParser.parse(celSource);
                        predicate = new ScmCelExpr(celSource, celAst);
                    }
                }
            }

            int hops = edge.maxHops();
            for (int h = 0; h < hops; h++) {
                if (isForward) {
                    steps.add(predicate != null ? ScmWalk.forward(rel, predicate) : ScmWalk.forward(rel));
                } else {
                    steps.add(predicate != null ? ScmWalk.reverse(rel, predicate) : ScmWalk.reverse(rel));
                }
            }
        }

        // 3. Lower Return Projection
        if (query.projection().isCount()) {
            steps.add(ScmCollect.scalar());
        } else {
            steps.add(ScmCollect.bitset());
        }

        ScmProgram program = ScmProgram.ofList(steps);
        return new CompilationResult(program, seedVar, seedParamOrVal);
    }
}
