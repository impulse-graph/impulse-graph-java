package org.impulsegraph.compiler.emitter;

import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.passes.stage2.RegisterAllocationPass;
import org.impulsegraph.compiler.passes.stage2.RegisterAllocationPass.RegisterAssignment;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.vm.CompiledQuery;
import org.impulsegraph.vm.RelationInstructionPatch;

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
            List<InstructionWord> instructionList,
            List<String> stringPool
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
        List<String> stringPool = new ArrayList<>();

        RegisterAssignment regAlloc = RegisterAllocationPass.allocate(ast);

        short currentReg = 0;
        boolean firstStepEmitted = false;

        if (ast instanceof ScmProgram prog) {
            for (ImpScmNode step : prog.steps()) {
                short srcReg = regAlloc.srcRegisters().getOrDefault(step, currentReg);
                short dstReg = regAlloc.dstRegisters().getOrDefault(step, (short) (1 - currentReg));
                currentReg = dstReg;

                if (step instanceof ScmWalk walk) {
                    int relId = walk.relationId();
                    String logicalRelName = walk.relationName();
                    if (relId < 0 && !logicalRelName.isEmpty() && snapshot != null) {
                        relId = resolveRelationId(snapshot, logicalRelName);
                    }
                    if (relId >= 0) {
                        relationIdMap.put(logicalRelName, relId);
                    }

                    long pc = instrList.size();
                    patches.add(new RelationInstructionPatch(pc, logicalRelName, srcReg, dstReg));

                    byte opcode = walk.direction() == ScmWalk.Direction.REVERSE_CSC ? OP_CSC_WALK
                            : walk.filterPredicate() != null ? OP_CSR_WALK_FILTERED : OP_CSR_WALK;

                    byte flags = FLAG_HALT_ON_EMPTY;
                    if (!firstStepEmitted) {
                        flags |= FLAG_INPUT_SEED;
                        firstStepEmitted = true;
                    }

                    int payload = ((relId & 0xFFFF) << 16) | (srcReg & 0xFFFF);
                    instrList.add(new InstructionWord(opcode, flags, dstReg, payload));
                } else if (step instanceof ScmWalk2Hop hop2) {
                    int rel1Id = hop2.relation1Id();
                    int rel2Id = hop2.relation2Id();
                    if (rel1Id < 0 && !hop2.relation1Name().isEmpty() && snapshot != null) {
                        rel1Id = resolveRelationId(snapshot, hop2.relation1Name());
                    }
                    if (rel2Id < 0 && !hop2.relation2Name().isEmpty() && snapshot != null) {
                        rel2Id = resolveRelationId(snapshot, hop2.relation2Name());
                    }
                    if (rel1Id >= 0) relationIdMap.put(hop2.relation1Name(), rel1Id);
                    if (rel2Id >= 0) relationIdMap.put(hop2.relation2Name(), rel2Id);

                    byte flags = FLAG_HALT_ON_EMPTY;
                    if (!firstStepEmitted) {
                        flags |= FLAG_INPUT_SEED;
                        firstStepEmitted = true;
                    }

                    int payload = ((rel2Id & 0xFFFF) << 16) | (rel1Id & 0xFFFF);
                    instrList.add(new InstructionWord(OP_CSR_WALK_2HOP, flags, dstReg, payload));
                } else if (step instanceof ScmVectorFilter) {
                    byte flags = 0;
                    if (!firstStepEmitted) {
                        flags |= FLAG_INPUT_SEED;
                        firstStepEmitted = true;
                    }
                    int payload = ((srcReg & 0xFFFF) << 16) | (srcReg & 0xFFFF);
                    instrList.add(new InstructionWord(OP_NODE_FILTER, flags, dstReg, payload));
                } else if (step instanceof ScmReduce red) {
                    byte opcode = switch (red.op()) {
                        case FIRST -> OP_REDUCE;
                        case SUM, COUNT -> OP_VECTOR_REDUCE_SUM;
                        case MAX -> OP_VECTOR_REDUCE_MAX;
                        case MIN -> OP_VECTOR_REDUCE_MIN;
                        case ARGMAX -> OP_VECTOR_REDUCE_ARGMAX;
                        case ARGMIN -> OP_VECTOR_REDUCE_ARGMIN;
                    };
                    instrList.add(new InstructionWord(opcode, (byte) 0, srcReg, (srcReg << 16) | srcReg));
                } else if (step instanceof ScmCollect collect) {
                    byte flags = 0;
                    if (!firstStepEmitted) {
                        flags |= FLAG_INPUT_SEED;
                        firstStepEmitted = true;
                    }
                    byte opcode = (collect.format() == ScmCollect.Format.VECTOR || collect.format() == ScmCollect.Format.LIST)
                            ? OP_COLLECT_ARRAY : OP_COLLECT_BITSET;
                    instrList.add(new InstructionWord(opcode, flags, srcReg, (srcReg << 16) | srcReg));
                } else if (step instanceof ScmList list && !list.elements().isEmpty()) {
                    ImpScmNode head = list.elements().get(0);
                    String opName = head instanceof ScmSymbol sym ? sym.name() : "";

                    if ("repeat".equalsIgnoreCase(opName)) {
                        int repeatCount = 1;
                        if (list.elements().size() > 1 && list.elements().get(1) instanceof ScmLiteral.ScmInt cnt) {
                            repeatCount = (int) cnt.value();
                        }
                        short countReg = (short) (currentReg + 2);
                        instrList.add(new InstructionWord(OP_LOAD_CONST_INT, (byte) 0, countReg, repeatCount));
                        long loopStartPc = instrList.size();

                        if (list.elements().size() > 2 && list.elements().get(2) instanceof ScmProgram subProg) {
                            currentReg = emitSubSteps(subProg.steps(), snapshot, instrList, patches, relationIdMap, currentReg);
                        }
                        instrList.add(new InstructionWord(OP_LOOP_DECR, (byte) 0, countReg, (int) loopStartPc));
                    } else if ("repeat-until-stable".equalsIgnoreCase(opName)) {
                        long loopStartPc = instrList.size();
                        if (list.elements().size() > 1 && list.elements().get(1) instanceof ScmProgram subProg) {
                            currentReg = emitSubSteps(subProg.steps(), snapshot, instrList, patches, relationIdMap, currentReg);
                        }
                        instrList.add(new InstructionWord(OP_STABLE_CHECK, (byte) 0, currentReg, 0));
                        instrList.add(new InstructionWord(OP_JNZ, (byte) 0, (short) 0, (int) loopStartPc));
                    } else if ("project-expression".equalsIgnoreCase(opName)) {
                        String attrName = "";
                        if (list.elements().size() > 1 && list.elements().get(1) instanceof ScmSymbol sym) {
                            attrName = sym.name();
                        }
                        if (!stringPool.contains(attrName)) {
                            stringPool.add(attrName);
                        }
                        int nameIdx = stringPool.indexOf(attrName);
                        int payload = ((srcReg & 0xFFFF) << 16) | (nameIdx & 0xFFFF);
                        instrList.add(new InstructionWord(OP_VECTOR_LOAD_ATTR, (byte) 0, dstReg, payload));
                    } else if ("island-detect".equalsIgnoreCase(opName)) {
                        instrList.add(new InstructionWord(OP_ISLAND_DETECT, (byte) 0, dstReg, 0));
                    } else if ("rebac-check".equalsIgnoreCase(opName)) {
                        instrList.add(new InstructionWord(OP_REBAC_CHECK, (byte) 0, dstReg, 0));
                    } else if ("motif-match-3".equalsIgnoreCase(opName)) {
                        instrList.add(new InstructionWord(OP_MOTIF_MATCH_3, (byte) 0, dstReg, 0));
                    }
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

        return new EmittedProgram(programSeg, count, patches, relationIdMap, instrList, stringPool);
    }

    private static short emitSubSteps(List<ImpScmNode> subSteps, ImpulseGraphSnapshot snapshot,
                                      List<InstructionWord> instrList,
                                      List<RelationInstructionPatch> patches,
                                      Map<String, Integer> relationIdMap,
                                      short startReg) {
        short currentReg = startReg;
        for (ImpScmNode step : subSteps) {
            if (step instanceof ScmWalk walk) {
                int relId = walk.relationId();
                String logicalRelName = walk.relationName();
                if (relId < 0 && !logicalRelName.isEmpty() && snapshot != null) {
                    relId = resolveRelationId(snapshot, logicalRelName);
                }
                if (relId >= 0) {
                    relationIdMap.put(logicalRelName, relId);
                }

                short srcReg = currentReg;
                short dstReg = (short) (1 - currentReg);
                currentReg = dstReg;

                long pc = instrList.size();
                patches.add(new RelationInstructionPatch(pc, logicalRelName, srcReg, dstReg));

                int payload = ((relId & 0xFFFF) << 16) | (srcReg & 0xFFFF);
                instrList.add(new InstructionWord(OP_CSR_WALK, (byte) 0, dstReg, payload));
            }
        }
        return currentReg;
    }

    private static int resolveRelationId(ImpulseGraphSnapshot snapshot, String relName) {
        if (snapshot == null || relName == null) return 0;
        var map = snapshot.getAllRelationSnapshots();
        if (map.containsKey(relName)) {
            int idx = 0;
            for (String key : map.keySet()) {
                if (key.equalsIgnoreCase(relName)) return idx;
                idx++;
            }
        }
        int idx = 0;
        for (String key : map.keySet()) {
            if (key.endsWith("_" + relName) || key.endsWith(relName) || key.toLowerCase().endsWith(relName.toLowerCase())) {
                return idx;
            }
            idx++;
        }
        return 0;
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
                emitted.stringPool()
        );
    }
}
