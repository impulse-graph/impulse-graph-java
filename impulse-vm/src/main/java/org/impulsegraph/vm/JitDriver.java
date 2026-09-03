package org.impulsegraph.vm;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

public class JitDriver {
	private final MethodHandle[] handlers;

	public JitDriver(MethodHandle[] handlers) {
		this.handlers = handlers;
	}

	public Object execute(VmQueryContext ctx, MemorySegment state, Object input, long maxPc) {
		int pc = 0;
		try {
			while (pc >= 0 && pc < maxPc) {
				// Signature of all handlers is: (VmQueryContext ctx, MemorySegment state,
				// Object input, int currentPc) -> int (nextPc)
				pc = (int) handlers[pc].invokeExact(ctx, state, input, pc);
			}
		} catch (Throwable t) {
			if (t instanceof RuntimeException re)
				throw re;
			throw new RuntimeException(t);
		}
		return ctx.getFinalResult();
	}
}
