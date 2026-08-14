package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-level ImpScheme query program consisting of ordered pipeline steps.
 */
public record ScmProgram(List<ImpScmNode> steps) implements ImpScmNode {

    public ScmProgram(List<ImpScmNode> steps) {
        this.steps = steps != null ? Collections.unmodifiableList(new ArrayList<>(steps)) : List.of();
    }

    public static ScmProgram of(ImpScmNode... steps) {
        return new ScmProgram(List.of(steps));
    }

    public static ScmProgram ofList(List<ImpScmNode> steps) {
        return new ScmProgram(steps);
    }

    public ScmProgram withSteps(List<ImpScmNode> newSteps) {
        return new ScmProgram(newSteps);
    }

    @Override
    public String toScmString() {
        StringBuilder sb = new StringBuilder("(program");
        for (ImpScmNode step : steps) {
            sb.append("\n  ").append(step.toScmString().replace("\n", "\n  "));
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitProgram(this);
    }
}
