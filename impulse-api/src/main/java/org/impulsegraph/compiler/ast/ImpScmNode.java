package org.impulsegraph.compiler.ast;

/**
 * Root sealed interface for all ImpScheme (ImpScm) AST nodes. Homoiconic
 * intermediate representation between high-level query frontends and native
 * impOps VM bytecode.
 */
public sealed interface ImpScmNode permits ScmProgram, ScmWalk, ScmWalk2Hop, ScmVectorFilter, ScmStreamFilter,
		ScmStreamProject, ScmCelExpr, ScmReduce, ScmCollect, ScmLiteral, ScmSymbol, ScmList, ScmLaneInitSeeds,
		ScmLaneInitGroups, ScmLaneSetEdgeMask, ScmLaneWalk, ScmLanePruneMask, ScmLaneFilterQuorum, ScmLaneReduceAnd,
		ScmLaneReduceAttr, ScmLaneSetCover, ScmVectorAnnSearch, ScmVectorRerankDot, ScmFixpoint, ScmSetOp {

	String toScmString();

	<R> R accept(ImpScmVisitor<R> visitor);
}
