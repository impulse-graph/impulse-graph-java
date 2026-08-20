package org.impulsegraph.compiler.ast;

/**
 * Visitor interface for traversing and transforming ImpScheme (ImpScm) AST nodes.
 */
public interface ImpScmVisitor<R> {
    R visitProgram(ScmProgram node);
    R visitWalk(ScmWalk node);
    default R visitWalk2Hop(ScmWalk2Hop node) { return null; }
    R visitVectorFilter(ScmVectorFilter node);
    R visitCelExpr(ScmCelExpr node);
    R visitReduce(ScmReduce node);
    R visitCollect(ScmCollect node);
    R visitLiteral(ScmLiteral node);
    R visitSymbol(ScmSymbol node);
    R visitList(ScmList node);
}
