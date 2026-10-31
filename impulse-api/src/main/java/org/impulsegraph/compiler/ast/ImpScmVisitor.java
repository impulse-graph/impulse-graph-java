package org.impulsegraph.compiler.ast;

/**
 * Visitor interface for traversing and transforming ImpScheme (ImpScm) AST
 * nodes.
 */
public interface ImpScmVisitor<R> {
	R visitProgram(ScmProgram node);
	R visitWalk(ScmWalk node);
	default R visitWalk2Hop(ScmWalk2Hop node) {
		return null;
	}
	default R visitLaneInitSeeds(ScmLaneInitSeeds node) {
		return null;
	}
	default R visitLaneInitGroups(ScmLaneInitGroups node) {
		return null;
	}
	default R visitLaneSetEdgeMask(ScmLaneSetEdgeMask node) {
		return null;
	}
	default R visitLaneWalk(ScmLaneWalk node) {
		return null;
	}
	default R visitLanePruneMask(ScmLanePruneMask node) {
		return null;
	}
	default R visitLaneFilterQuorum(ScmLaneFilterQuorum node) {
		return null;
	}
	default R visitLaneReduceAnd(ScmLaneReduceAnd node) {
		return null;
	}
	default R visitLaneReduceAttr(ScmLaneReduceAttr node) {
		return null;
	}
	default R visitLaneSetCover(ScmLaneSetCover node) {
		return null;
	}
	default R visitVectorAnnSearch(ScmVectorAnnSearch node) {
		return null;
	}
	default R visitVectorRerankDot(ScmVectorRerankDot node) {
		return null;
	}
	default R visitFixpoint(ScmFixpoint node) {
		return null;
	}
	default R visitSetOp(ScmSetOp node) {
		return null;
	}
	R visitVectorFilter(ScmVectorFilter node);
	R visitCelExpr(ScmCelExpr node);
	R visitReduce(ScmReduce node);
	R visitCollect(ScmCollect node);
	R visitLiteral(ScmLiteral node);
	R visitSymbol(ScmSymbol node);
	R visitList(ScmList node);
}
