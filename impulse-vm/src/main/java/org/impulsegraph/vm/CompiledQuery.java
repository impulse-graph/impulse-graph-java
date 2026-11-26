package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.compiler.ast.ImpScmNode;

import java.lang.foreign.Arena;
import java.util.Objects;

/**
 * Immutable compiled ImpulseVM execution plan backed by the v0.11 2-buffer
 * execution engine, with lock-free blue/green snapshot re-binding and
 * disassembly.
 */
public final class CompiledQuery {

	private final ImpulseCompiledQuery v11Delegate;

	public static CompiledQuery fromV11(ImpScmNode ast, ImpulseGraphSnapshot initialSnapshot) {
		return new CompiledQuery(new ImpulseCompiledQuery(ast, initialSnapshot));
	}

	private CompiledQuery(ImpulseCompiledQuery v11Delegate) {
		this.v11Delegate = Objects.requireNonNull(v11Delegate, "v11Delegate must not be null");
	}

	public ImpulseCompiledQuery v11() {
		return v11Delegate;
	}

	public long instructionCount() {
		return v11Delegate.instructionCount();
	}

	public ImpulseGraphSnapshot currentSnapshot() {
		return v11Delegate.currentSnapshot();
	}

	public void rebind(ImpulseGraphSnapshot newSnapshot) {
		v11Delegate.rebind(newSnapshot);
	}

	public Object execute(ImpulseGraphSnapshot snapshot, Object input, Arena executionArena) {
		return v11Delegate.execute(snapshot, input, executionArena);
	}

	public String disassemble() {
		return v11Delegate.disassemble();
	}
}
