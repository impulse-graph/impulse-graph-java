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
		int lastPc = 0;
		try {
			while (pc >= 0 && pc < maxPc) {
				lastPc = pc;
				// Signature of all handlers is: (VmQueryContext ctx, MemorySegment state,
				// Object input, int currentPc) -> int (nextPc)
				int nextPc = (int) handlers[pc].invokeExact(ctx, state, input, pc);
				if (nextPc == (int) maxPc) {
					// OP_HALT or block exit: according to VM spec §2.2, PC remains pointed at halting instruction
					break;
				}
				pc = nextPc;
			}
			VmStateLayout.PC_HANDLE.set(state, 0L, lastPc);
		} catch (Throwable t) {
			int currentPc = (int) VmStateLayout.PC_HANDLE.get(state, 0L);
			if (currentPc >= 0 && currentPc < maxPc && currentPc != lastPc + 1) {
				VmStateLayout.PC_HANDLE.set(state, 0L, lastPc);
			}
			if (t instanceof RuntimeException re)
				throw re;
			throw new RuntimeException(t);
		}
		return ctx.getFinalResult();
	}
}
