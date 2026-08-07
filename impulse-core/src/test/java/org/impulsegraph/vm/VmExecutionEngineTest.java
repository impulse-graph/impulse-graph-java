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
                    new InstructionData(OP_CSR_WALK, (byte) 0, (short) 2, (1 << 16) | 0),
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
}
