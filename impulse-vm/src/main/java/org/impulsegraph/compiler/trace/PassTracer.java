package org.impulsegraph.compiler.trace;

import org.impulsegraph.compiler.ast.ImpScmNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Diagnostic pass execution tracer recording multi-pass S-expression transformations.
 */
public final class PassTracer {

    public record PassTraceEntry(
            String passName,
            ImpScmNode beforeAst,
            ImpScmNode afterAst,
            long durationNanos
    ) {}

    private final List<PassTraceEntry> entries = new ArrayList<>();
    private final CompilerOptions options;

    public PassTracer(CompilerOptions options) {
        this.options = options != null ? options : CompilerOptions.DEFAULT;
    }

    public void record(String passName, ImpScmNode beforeAst, ImpScmNode afterAst, long durationNanos) {
        PassTraceEntry entry = new PassTraceEntry(passName, beforeAst, afterAst, durationNanos);
        entries.add(entry);

        if (options.enableTracing() && options.traceListener() != null) {
            options.traceListener().onPassComplete(passName, beforeAst, afterAst, durationNanos);
        }
    }

    public List<PassTraceEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public String generateTraceReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("=========================================================================\n");
        sb.append("                 IMPULSE COMPILER MULTI-PASS TRACE REPORT               \n");
        sb.append("=========================================================================\n");

        long totalNanos = 0;
        for (int i = 0; i < entries.size(); i++) {
            PassTraceEntry e = entries.get(i);
            totalNanos += e.durationNanos();
            sb.append(String.format("Pass #%02d: %-32s [%.3f ms]\n", i + 1, e.passName(), e.durationNanos() / 1_000_000.0));
            sb.append("  AST Output:\n");
            String astStr = e.afterAst() != null ? e.afterAst().toScmString() : "()";
            for (String line : astStr.split("\n")) {
                sb.append("    ").append(line).append("\n");
            }
            sb.append("-------------------------------------------------------------------------\n");
        }

        sb.append(String.format("Total Compilation Pipeline Duration: %.3f ms across %d passes\n",
                totalNanos / 1_000_000.0, entries.size()));
        sb.append("=========================================================================\n");
        return sb.toString();
    }
}
