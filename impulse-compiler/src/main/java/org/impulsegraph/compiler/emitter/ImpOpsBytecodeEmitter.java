package org.impulsegraph.compiler.emitter;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.stage2.RegisterAllocationPass;
import org.impulsegraph.compiler.passes.stage2.RegisterAllocationPass.RegisterAssignment;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.vm.ImpulseMethodHandleCompiler;
import org.impulsegraph.vm.ImpulseQueryCompiler.CompiledQuery;
import org.impulsegraph.vm.ImpulseQueryCompiler.RelationInstructionPatch;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Low-level bytecode emitter compiling bound ImpScheme ASTs into native 8-byte off-heap impOps instructions.
 */
public final class ImpOpsBytecodeEmitter {

    public record InstructionWord(byte opcode, byte flags, short dstReg, int payload) {}

    public record EmittedProgram(
            MemorySegment programSegment,
            long instructionCount,
            List<RelationInstructionPatch> patches,
            Map<String, Integer> relationIdMap,
            List<InstructionWord> instructionList
    ) {}

    private ImpOpsBytecodeEmitter() {}

    public static final byte FLAG_HALT_ON_EMPTY = 0x01;
    public static final byte FLAG_INPUT_SEED = 0x02;

    public static EmittedProgram emit(ImpScmNode ast, ImpulseGraphSnapshot snapshot, Arena arena) {
        Objects.requireNonNull(ast, "ast must not be null");
        Objects.requireNonNull(arena, "arena must not be null");

        List<InstructionWord> instrList = new ArrayList<>();
        List<RelationInstructionPatch> patches = new ArrayList<>();
        Map<String, Integer> relationIdMap = new HashMap<>();

        RegisterAssignment regAlloc = RegisterAllocationPass.allocate(ast);

        short currentReg = 0;
        boolean firstWalkEmitted = false;

        if (ast instanceof ScmProgram prog) {
            for (ImpScmNode step : prog.steps()) {
                short srcReg = regAlloc.srcRegisters().getOrDefault(step, currentReg);
                short dstReg = regAlloc.dstRegisters().getOrDefault(step, (short) (1 - currentReg));
                currentReg = dstReg;

                if (step instanceof ScmWalk walk) {
                    int relId = walk.relationId();
                    String logicalRelName = walk.relationName();
                    if (relId >= 0) {
                        relationIdMap.put(logicalRelName, relId);
                    }

                    long pc = instrList.size();
                    patches.add(new RelationInstructionPatch(pc, logicalRelName, srcReg, dstReg));

                    byte opcode = walk.direction() == ScmWalk.Direction.REVERSE_CSC ? OP_CSC_WALK
                            : walk.filterPredicate() != null ? OP_CSR_WALK_FILTERED : OP_CSR_WALK;

                    byte flags = FLAG_HALT_ON_EMPTY;
                    if (!firstWalkEmitted) {
                        flags |= FLAG_INPUT_SEED;
                        firstWalkEmitted = true;
                    }

                    int payload = ((relId & 0xFFFF) << 16) | (srcReg & 0xFFFF);
                    instrList.add(new InstructionWord(opcode, flags, dstReg, payload));
                } else if (step instanceof ScmWalk2Hop hop2) {
                    int rel1Id = hop2.relation1Id();
                    int rel2Id = hop2.relation2Id();
                    if (rel1Id >= 0) relationIdMap.put(hop2.relation1Name(), rel1Id);
                    if (rel2Id >= 0) relationIdMap.put(hop2.relation2Name(), rel2Id);

                    byte flags = FLAG_HALT_ON_EMPTY;
                    if (!firstWalkEmitted) {
                        flags |= FLAG_INPUT_SEED;
                        firstWalkEmitted = true;
                    }

                    int payload = ((rel2Id & 0xFFFF) << 16) | (rel1Id & 0xFFFF);
                    instrList.add(new InstructionWord(OP_CSR_WALK_2HOP, flags, dstReg, payload));
                } else if (step instanceof ScmVectorFilter) {
                    int payload = ((srcReg & 0xFFFF) << 16);
                    instrList.add(new InstructionWord(OP_NODE_FILTER, (byte) 0, dstReg, payload));
                } else if (step instanceof ScmReduce red) {
                    byte opcode = red.op() == ScmReduce.Op.FIRST ? OP_REDUCE : OP_VECTOR_REDUCE_SUM;
                    instrList.add(new InstructionWord(opcode, (byte) 0, dstReg, 0));
                } else if (step instanceof ScmCollect) {
                    instrList.add(new InstructionWord(OP_COLLECT_BITSET, (byte) 0, srcReg, 0));
                }
            }
        }

        // Always end program with OP_HALT
        instrList.add(new InstructionWord(OP_HALT, (byte) 0, (short) 0, 0));

        // Allocate off-heap instruction memory segment
        long count = instrList.size();
        MemorySegment programSeg = arena.allocate(INSTRUCTION_LAYOUT, count);

        for (int i = 0; i < count; i++) {
            long off = (long) i * 8; // 8-byte instruction layout
            InstructionWord word = instrList.get(i);
            INSTR_OPCODE_HANDLE.set(programSeg, off, word.opcode());
            INSTR_FLAGS_HANDLE.set(programSeg, off, word.flags());
            INSTR_DST_REG_HANDLE.set(programSeg, off, word.dstReg());
            INSTR_PAYLOAD_HANDLE.set(programSeg, off, word.payload());
        }

        return new EmittedProgram(programSeg, count, patches, relationIdMap, instrList);
    }

    public static CompiledQuery compileToExecutable(ImpScmNode ast, ImpulseGraphSnapshot snapshot, Arena arena) {
        EmittedProgram emitted = emit(ast, snapshot, arena);
        return new CompiledQuery(
                emitted.programSegment(),
                emitted.instructionCount(),
                emitted.patches(),
                emitted.relationIdMap(),
                snapshot,
                arena,
                new java.util.ArrayList<>()
        );
    }
}
