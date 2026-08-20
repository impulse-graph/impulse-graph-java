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
                    MethodType.methodType(Object.class, MemorySegment.class, long.class, ImpulseGraphSnapshot.class, Object.class, Arena.class, java.util.List.class)
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
        return compile(programSeg, instructionCount, null);
    }

    public static MethodHandle compile(MemorySegment programSeg, long instructionCount, java.util.List<String> stringPool) {
        if (programSeg == null || instructionCount <= 0) {
            throw new IllegalArgumentException("Invalid program segment or zero instruction count");
        }
        
        // Method type is: (MemorySegment, long, ImpulseGraphSnapshot, Object, Arena, List)
        // insertArguments at pos 0 with (programSeg, instructionCount) -> (ImpulseGraphSnapshot, Object, Arena, List)
        MethodHandle mh = MethodHandles.insertArguments(INTERPRETER_EXECUTE_MH, 0, programSeg, instructionCount);
        
        // bindTo binds the first reference argument. Our remaining arguments are:
        // (ImpulseGraphSnapshot, Object, Arena, List)
        // So bindTo would bind the snapshot! We do NOT want that.
        // We want to bind the LAST argument (List).
        return MethodHandles.insertArguments(mh, 3, stringPool);
    }
}
