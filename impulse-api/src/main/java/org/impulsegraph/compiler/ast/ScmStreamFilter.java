package org.impulsegraph.compiler.ast;

public record ScmStreamFilter(ImpScmNode predicate) implements ImpScmNode {
	@Override
	public String toScmString() {
		return "(stream-filter " + predicate.toScmString() + ")";
	}

	@Override
	public <R> R accept(ImpScmVisitor<R> visitor) {
		// default fallback if not implemented by visitor
		return null;
	}
}
