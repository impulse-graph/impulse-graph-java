package org.impulsegraph.vm;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

public class JitDriver {
	private final MethodHandle[] handlers;
	private final MemorySegment programSeg;
	private final VmHandlers.Instruction[] decodedInstructions;

	public JitDriver(MethodHandle[] handlers) {
		this(handlers, null, null);
	}

	public JitDriver(MethodHandle[] handlers, MemorySegment programSeg, VmHandlers.Instruction[] decodedInstructions) {
		this.handlers = handlers;
		this.programSeg = programSeg;
		this.decodedInstructions = decodedInstructions;
	}

	public Object execute(VmQueryContext ctx, MemorySegment state, Object input, long maxPc) {
		int pc = 0;
		int lastPc = 0;
		final VmHandlers.Instruction[] decoded = this.decodedInstructions;
		final MemorySegment prog = this.programSeg;
		final MethodHandle[] mhArr = this.handlers;
		try {
			while (pc >= 0 && pc < maxPc) {
				lastPc = pc;
				int nextPc;
				if (decoded != null && (mhArr == null || mhArr[pc] == null)) {
					nextPc = ImpulseMethodHandleCompiler.executeDecoded(prog, maxPc, decoded[pc], ctx, state, input,
							pc);
				} else {
					nextPc = (int) mhArr[pc].invokeExact(ctx, state, input, pc);
				}
				if (nextPc == (int) maxPc) {
					// OP_HALT or block exit: according to VM spec §2.2, PC remains pointed at
					// halting instruction
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
