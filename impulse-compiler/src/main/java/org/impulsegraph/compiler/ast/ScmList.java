package org.impulsegraph.compiler.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Generic homoiconic S-expression list (e.g. (+ 2 (* 3 4)) or (vec-cmp-gt a 10)).
 */
public record ScmList(List<ImpScmNode> elements) implements ImpScmNode {

    public ScmList(List<ImpScmNode> elements) {
        this.elements = elements != null ? Collections.unmodifiableList(new ArrayList<>(elements)) : List.of();
    }

    public static ScmList of(ImpScmNode... elements) {
        return new ScmList(List.of(elements));
    }

    public static ScmList ofList(List<? extends ImpScmNode> elements) {
        return new ScmList(new ArrayList<>(elements));
    }

    @Override
    public String toScmString() {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) sb.append(" ");
            sb.append(elements.get(i).toScmString());
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public <R> R accept(ImpScmVisitor<R> visitor) {
        return visitor.visitList(this);
    }
}
