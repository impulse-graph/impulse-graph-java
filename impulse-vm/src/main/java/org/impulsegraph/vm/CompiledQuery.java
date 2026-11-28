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

	private final ImpulseCompiledQuery vmDelegate;

	public static CompiledQuery fromBytecode(ImpScmNode ast, ImpulseGraphSnapshot initialSnapshot) {
		return new CompiledQuery(new ImpulseCompiledQuery(ast, initialSnapshot));
	}

	private CompiledQuery(ImpulseCompiledQuery vmDelegate) {
		this.vmDelegate = Objects.requireNonNull(vmDelegate, "vmDelegate must not be null");
	}

	public ImpulseCompiledQuery v11() {
		return vmDelegate;
	}

	public long instructionCount() {
		return vmDelegate.instructionCount();
	}

	public ImpulseGraphSnapshot currentSnapshot() {
		return vmDelegate.currentSnapshot();
	}

	public void rebind(ImpulseGraphSnapshot newSnapshot) {
		vmDelegate.rebind(newSnapshot);
	}

	public Object execute(ImpulseGraphSnapshot snapshot, Object input, Arena executionArena) {
		return vmDelegate.execute(snapshot, input, executionArena);
	}

	public String disassemble() {
		return vmDelegate.disassemble();
	}
}
