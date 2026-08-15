package org.impulsegraph.compiler.ast;

/**
 * Root sealed interface for all ImpScheme (ImpScm) AST nodes.
 * Homoiconic intermediate representation between high-level query frontends and native impOps VM bytecode.
 */
public sealed interface ImpScmNode permits
        ScmProgram,
        ScmWalk,
        ScmWalk2Hop,
        ScmVectorFilter,
        ScmCelExpr,
        ScmReduce,
        ScmCollect,
        ScmLiteral,
        ScmSymbol,
        ScmList {

    String toScmString();

    <R> R accept(ImpScmVisitor<R> visitor);
}
