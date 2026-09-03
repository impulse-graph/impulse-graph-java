package org.impulsegraph.compiler.ast;

public record ScmStreamProject(ImpScmNode expr) implements ImpScmNode {
	@Override
	public String toScmString() {
		return "(stream-project " + expr.toScmString() + ")";
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		return null;
	}
}
