package org.impulsegraph.compiler.passes;

import org.impulsegraph.compiler.ast.ImpScmNode;

/**
 * Standard interface for ImpScheme compiler AST optimization and transformation passes.
 */
public interface CompilerPass {

    String name();

    ImpScmNode transform(ImpScmNode ast, CompilerContext context);
}
