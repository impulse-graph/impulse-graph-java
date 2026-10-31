package org.impulsegraph.compiler.ast;

import java.util.Objects;

/**
 * Set algebra operation step over active bitsets ({@code UNION},
 * {@code INTERSECT}, {@code DIFF}).
 */
public record ScmSetOp(Kind kind, ImpScmNode lhs, ImpScmNode rhs) implements ImpScmNode {

	public enum Kind {
		UNION, INTERSECT, DIFF
	}

	public ScmSetOp {
		Objects.requireNonNull(kind, "kind must not be null");
		Objects.requireNonNull(rhs, "rhs must not be null");
	}

	public ScmSetOp(Kind kind, ImpScmNode rhs) {
		this(kind, null, rhs);
	}

	public static ScmSetOp union(ImpScmNode rhs) {
		return new ScmSetOp(Kind.UNION, null, rhs);
	}

	public static ScmSetOp union(ImpScmNode lhs, ImpScmNode rhs) {
		return new ScmSetOp(Kind.UNION, lhs, rhs);
	}

	public static ScmSetOp intersect(ImpScmNode rhs) {
		return new ScmSetOp(Kind.INTERSECT, null, rhs);
	}

	public static ScmSetOp intersect(ImpScmNode lhs, ImpScmNode rhs) {
		return new ScmSetOp(Kind.INTERSECT, lhs, rhs);
	}

	public static ScmSetOp diff(ImpScmNode rhs) {
		return new ScmSetOp(Kind.DIFF, null, rhs);
	}

	public static ScmSetOp diff(ImpScmNode lhs, ImpScmNode rhs) {
		return new ScmSetOp(Kind.DIFF, lhs, rhs);
	}

	@Override
	public String toScmString() {
		String opName = switch (kind) {
			case UNION -> "set-union";
			case INTERSECT -> "set-intersect";
			case DIFF -> "set-diff";
		};
		if (lhs != null) {
			return "(" + opName + " " + lhs.toScmString() + " " + rhs.toScmString() + ")";
		}
		return "(" + opName + " " + rhs.toScmString() + ")";
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return visitor.visitSetOp(this);
	}
}
