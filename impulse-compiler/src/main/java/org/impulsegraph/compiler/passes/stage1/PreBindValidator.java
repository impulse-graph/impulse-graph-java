package org.impulsegraph.compiler.passes.stage1;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.cel.CelParser;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.CompilerPass;

/**
 * Stage 1 Pass: Pre-bind syntactic and structural validation.
 * Rejects malformed queries, invalid operator arities, and syntax errors before physical snapshot binding.
 */
public final class PreBindValidator implements CompilerPass {

    public static final PreBindValidator INSTANCE = new PreBindValidator();

    @Override
    public String name() {
        return "PreBindValidator";
    }

    @Override
    public ImpScmNode transform(ImpScmNode ast, CompilerContext context) {
        if (ast == null) {
            throw new IllegalArgumentException("Pre-Bind Validation Error: AST cannot be null");
        }
        validate(ast);
        return ast;
    }

    private void validate(ImpScmNode node) {
        if (node instanceof ScmProgram prog) {
            if (prog.steps().isEmpty()) {
                throw new IllegalArgumentException("Pre-Bind Validation Error: Program contains no execution steps");
            }
            for (ImpScmNode step : prog.steps()) {
                validate(step);
            }
        } else if (node instanceof ScmWalk walk) {
            if (walk.relationName().isEmpty() && walk.relationId() < 0) {
                throw new IllegalArgumentException("Pre-Bind Validation Error: Walk step missing relation specification");
            }
            if (walk.filterPredicate() != null) {
                validate(walk.filterPredicate());
            }
            for (ImpScmNode sub : walk.subSteps()) {
                validate(sub);
            }
        } else if (node instanceof ScmCelExpr cel) {
            if (cel.celAst() == null && !cel.rawText().isEmpty()) {
                try {
                    CelParser.parse(cel.rawText());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Pre-Bind Validation Error: Invalid CEL syntax in '"
                            + cel.rawText() + "': " + e.getMessage(), e);
                }
            }
        } else if (node instanceof ScmVectorFilter vf) {
            if (vf.predicate() == null) {
                throw new IllegalArgumentException("Pre-Bind Validation Error: Vector filter missing predicate");
            }
            validate(vf.predicate());
        }
    }
}
