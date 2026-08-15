package org.impulsegraph.compiler.ast.parser;

import org.impulsegraph.compiler.ast.ImpScmNode;

/**
 * Pretty-printer and serializer for ImpScheme ASTs.
 */
public final class ImpScmSerializer {

    private ImpScmSerializer() {}

    public static String serialize(ImpScmNode node) {
        if (node == null) return "()";
        return node.toScmString();
    }
}
