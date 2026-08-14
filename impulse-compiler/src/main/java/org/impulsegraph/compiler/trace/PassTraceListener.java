package org.impulsegraph.compiler.trace;

import org.impulsegraph.compiler.ast.ImpScmNode;

/**
 * Listener interface for compiler pass lifecycle tracing.
 */
@FunctionalInterface
public interface PassTraceListener {

    void onPassComplete(String passName, ImpScmNode beforeAst, ImpScmNode afterAst, long durationNanos);

    PassTraceListener NOOP = (name, before, after, dur) -> {};

    PassTraceListener SYSTEM_OUT = (name, before, after, dur) -> {
        System.out.printf("[ImpCompiler Trace] Pass: %-28s (took %.3f ms)%n", name, dur / 1_000_000.0);
        System.out.println("  -> Output AST: " + (after != null ? after.toScmString().replace("\n", "\n     ") : "null"));
    };
}
