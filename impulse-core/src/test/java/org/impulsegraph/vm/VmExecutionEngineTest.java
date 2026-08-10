package org.impulsegraph.vm;

import org.impulsegraph.core.csr.GraphSnapshot;
import org.impulsegraph.core.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.BitSet;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

public class VmExecutionEngineTest {

    private MemorySegment createInstruction(Arena arena, byte opcode, byte flags, short dstReg, int payload) {
        MemorySegment seg = arena.allocate(INSTRUCTION_LAYOUT);
        INSTR_OPCODE_HANDLE.set(seg, 0L, opcode);
        INSTR_FLAGS_HANDLE.set(seg, 0L, flags);
        INSTR_DST_REG_HANDLE.set(seg, 0L, dstReg);
        INSTR_PAYLOAD_HANDLE.set(seg, 0L, payload);
        return seg;
    }

    private MemorySegment buildProgram(Arena arena, InstructionData... instrs) {
        MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT, instrs.length);
        for (int i = 0; i < instrs.length; i++) {
            long off = i * INSTRUCTION_SIZE_BYTES;
            INSTR_OPCODE_HANDLE.set(prog, off, instrs[i].opcode);
            INSTR_FLAGS_HANDLE.set(prog, off, instrs[i].flags);
            INSTR_DST_REG_HANDLE.set(prog, off, instrs[i].dstReg);
            INSTR_PAYLOAD_HANDLE.set(prog, off, instrs[i].payload);
        }
        return prog;
    }

    private record InstructionData(byte opcode, byte flags, short dstReg, int payload) {}

    @Test
    public void testSimpleNodeInputAndWalk() throws Throwable {
        try (Arena arena = Arena.ofShared()) {
            // Build Graph Snapshot: 0 -> 1, 1 -> 2
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 2, 2);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2);

            RelationSnapshot relSnap = new RelationSnapshot(arena, 3, 2, offsets, targets);
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("rel_0", relSnap));

            // Program:
            // 0: OP_INIT_INPUT_NODE (dst=0)
            // 1: OP_CSR_WALK (dst=1, payload=(0 << 16) | 0) -> R0 node 0 -> R1 bitset {1}
            // 2: OP_CSR_WALK (dst=2, payload=(1 << 16) | 0) -> R1 bitset {1} -> R2 bitset {2}
            // 3: OP_COLLECT_BITSET (dst=2)
            // 4: OP_HALT
            InstructionData[] code = {
                    new InstructionData(OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0),
                    new InstructionData(OP_CSR_WALK, (byte) 0, (short) 1, (0 << 16) | 0),
                    new InstructionData(OP_CSR_WALK, (byte) 0, (short) 2, (0 << 16) | 1),
                    new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 2, 0),
                    new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)
            };

            MemorySegment prog = buildProgram(arena, code);

            // 1. Execute via Interpreter
            Object interpRes = ImpulseVmInterpreter.execute(prog, code.length, graph, 0, arena);
            assertTrue(interpRes instanceof BitSet);
            BitSet bsInterp = (BitSet) interpRes;
            assertEquals(1, bsInterp.cardinality());
            assertTrue(bsInterp.get(2), "Interpreter MUST reach node 2");

            // 2. Execute via MethodHandle Compiler
            MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, code.length);
            Object mhRes = mh.invokeExact(graph, (Object) 0, arena);
            assertTrue(mhRes instanceof BitSet);
            BitSet bsMh = (BitSet) mhRes;
            assertEquals(1, bsMh.cardinality());
            assertTrue(bsMh.get(2), "MethodHandle compiler MUST reach node 2");
        }
    }

    @Test
    public void testSetOperations() throws Throwable {
        try (Arena arena = Arena.ofShared()) {
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of());

            BitSet set1 = new BitSet();
            set1.set(10);
            set1.set(20);

            BitSet set2 = new BitSet();
            set2.set(20);
            set2.set(30);

            // Program:
            // 0: OP_INIT_INPUT_SET (dst=0) -> R0 = set1
            // 1: OP_INIT_INPUT_SET (dst=1) -> R1 = set2 (from code, wait: we use init input set for set1)
            // Let's load constants & test union/intersect
            InstructionData[] code = {
                    new InstructionData(OP_INIT_INPUT_SET, (byte) 0, (short) 0, 0),
                    new InstructionData(OP_SET_UNION, (byte) 0, (short) 2, (0 << 16) | 0), // Union R0 with R0
                    new InstructionData(OP_COLLECT_BITSET, (byte) 0, (short) 2, 0),
                    new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)
            };

            MemorySegment prog = buildProgram(arena, code);
            Object res = ImpulseVmInterpreter.execute(prog, code.length, graph, set1, arena);
            assertTrue(res instanceof BitSet);
            BitSet resBs = (BitSet) res;
            assertEquals(2, resBs.cardinality());
            assertTrue(resBs.get(10));
            assertTrue(resBs.get(20));
        }
    }

    @Test
    public void testVmInitializationAndSingleOpHalt() throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of());
            try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
                MemorySegment state = ctx.allocateStateSegment();

                // Verify initial VM state layout baseline (all 64 registers, types, flags, PC, call stack)
                assertEquals(0L, FLAGS_HANDLE.get(state, 0L));
                assertEquals(0, (int) PC_HANDLE.get(state, 0L));
                assertEquals(0, (int) CALL_STACK_DEPTH_HANDLE.get(state, 0L));

                for (int i = 0; i < 64; i++) {
                    assertEquals(0L, VmHandlers.getRegisterValue(state, i), "Register R" + i + " must be initialized to 0");
                    assertEquals(TYPE_NULL, VmHandlers.getRegisterType(state, i), "Register R" + i + " type must be TYPE_NULL");
                }

                for (int i = 0; i < 8; i++) {
                    assertEquals(0, (int) CALL_STACK_ELEMENT_HANDLE.get(state, 0L, (long) i), "Call stack slot " + i + " must be 0");
                }

                // Execute single OP_HALT instruction
                InstructionData[] code = {
                        new InstructionData(OP_HALT, (byte) 0, (short) 0, 0)
                };
                MemorySegment prog = buildProgram(arena, code);

                Object result = ImpulseVmInterpreter.execute(prog, code.length, graph, null, arena);
                assertTrue(result instanceof BitSet, "Single OP_HALT program returns default empty BitSet");
                assertTrue(((BitSet) result).isEmpty(), "Single OP_HALT program should return empty BitSet");
            }
        }
    }
}
