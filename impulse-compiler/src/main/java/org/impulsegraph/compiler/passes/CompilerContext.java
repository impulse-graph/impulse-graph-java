package org.impulsegraph.compiler.passes;

import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import java.util.Map;

/**
 * Contextual state passed across compiler transformation passes.
 */
public final class CompilerContext {
    private final ImpulseGraphSnapshot snapshot;
    private final CompilerOptions options;
    private final PassTracer tracer;

    public CompilerContext(ImpulseGraphSnapshot snapshot, CompilerOptions options, PassTracer tracer) {
        this.snapshot = snapshot;
        this.options = options != null ? options : CompilerOptions.DEFAULT;
        this.tracer = tracer != null ? tracer : new PassTracer(this.options);
    }

    public ImpulseGraphSnapshot snapshot() { return snapshot; }
    public CompilerOptions options() { return options; }
    public PassTracer tracer() { return tracer; }
    public Map<String, Object> parameters() { return options.parameters(); }

    public ImpScmNode executePass(CompilerPass pass, ImpScmNode input) {
        long start = System.nanoTime();
        ImpScmNode output = pass.transform(input, this);
        long duration = System.nanoTime() - start;
        tracer.record(pass.name(), input, output, duration);
        return output;
    }
}
