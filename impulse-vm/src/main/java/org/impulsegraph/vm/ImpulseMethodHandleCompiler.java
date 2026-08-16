package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * MethodHandle Compiler for Impulse VM.
 * Compiles off-heap VM bytecode into a MethodHandle pipeline for sub-microsecond JIT execution.
 */
public final class ImpulseMethodHandleCompiler {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final MethodHandle INTERPRETER_EXECUTE_MH;

    static {
        try {
            INTERPRETER_EXECUTE_MH = LOOKUP.findStatic(
                    ImpulseVmInterpreter.class,
                    "execute",
                    MethodType.methodType(Object.class, MemorySegment.class, long.class, ImpulseGraphSnapshot.class, Object.class, Arena.class)
            );
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ImpulseMethodHandleCompiler() {}

    /**
     * Compile program instructions into a bound MethodHandle.
     * Invoking the returned MethodHandle (ImpulseGraphSnapshot, Object input, Arena) -> Object
     * executes the pre-bound bytecode program.
     */
    public static MethodHandle compile(MemorySegment programSeg, long instructionCount) {
        if (programSeg == null || instructionCount <= 0) {
            throw new IllegalArgumentException("Invalid program segment or zero instruction count");
        }
        // Bind programSeg and instructionCount arguments (first two parameters)
        return MethodHandles.insertArguments(INTERPRETER_EXECUTE_MH, 0, programSeg, instructionCount);
    }
}
