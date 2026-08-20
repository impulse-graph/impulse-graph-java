package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.ImpulseQueryBuilder.StepNode;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * AST-to-Bytecode Compiler for Impulse Graph Engine VM.
 * <p>
 * Translates high-level Fluent Builder AST query pipelines ({@link StepNode}) into native
 * 8-byte off-heap bytecode instruction memory segments ({@code impulse_instruction_t})
 * for sub-microsecond JIT execution via {@link ImpulseMethodHandleCompiler} or {@link ImpulseVmInterpreter}.
 * </p>
 *
 * <p><b>Blue/Green Re-binding Support:</b> Physical relation IDs are bound to instructions via a
 * patch table. When graph snapshots are blue/green swapped, {@link CompiledQuery#rebind(ImpulseGraphSnapshot)}
 * verifies that all required relations exist in the new snapshot and patches relation IDs without
 * full AST recompilation.</p>
 */
public final class ImpulseQueryCompiler {

    private ImpulseQueryCompiler() {}

    /**
     * Patch entry for dynamic physical relation binding during blue/green snapshot swaps.
     */
    public record RelationInstructionPatch(long pc, String logicalRelationName, short srcReg, short dstReg) {}

    /**
     * Immutable value state holding the active snapshot binding, compiled MethodHandle, instruction segment,
     * and relation ID mappings.
     */
    public record QueryBindingState(
            ImpulseGraphSnapshot snapshot,
            MethodHandle methodHandle,
            MemorySegment programSeg,
            long instructionCount,
            Map<String, Integer> relationIdMap
    ) {}

    /**
     * Holds a compiled VM program, its binding table, JIT MethodHandle, and disassembler tools.
     * Uses an {@link java.util.concurrent.atomic.AtomicReference} over {@link QueryBindingState} for zero-delay lock-free reads.
     */
    public static final class CompiledQuery {
        private final List<RelationInstructionPatch> patches;
        private final Arena arena;
        private final java.util.concurrent.atomic.AtomicReference<QueryBindingState> bindingState;
        private final java.util.List<String> stringPool;

        public CompiledQuery(MemorySegment programSeg, long instructionCount, List<RelationInstructionPatch> patches, Map<String, Integer> relationIdMap, ImpulseGraphSnapshot initialSnapshot, Arena arena, java.util.List<String> stringPool) {
            this.stringPool = stringPool;
            Objects.requireNonNull(programSeg, "programSeg must not be null");
            this.patches = List.copyOf(patches);
            this.arena = arena;
            MethodHandle mh = ImpulseMethodHandleCompiler.compile(programSeg, instructionCount, this.stringPool);
            this.bindingState = new java.util.concurrent.atomic.AtomicReference<>(
                    new QueryBindingState(initialSnapshot, mh, programSeg, instructionCount, new HashMap<>(relationIdMap))
            );
        }

        public QueryBindingState bindingState() {
            return bindingState.get();
        }

        public MemorySegment programSegment() {
            return bindingState.get().programSeg();
        }

        public long instructionCount() {
            return bindingState.get().instructionCount();
        }

        public MethodHandle methodHandle() {
            return bindingState.get().methodHandle();
        }

        public ImpulseGraphSnapshot currentSnapshot() {
            return bindingState.get().snapshot();
        }

        /**
         * Re-binds this compiled query to a new blue/green swapped snapshot using an atomic reference update.
         * Verifies that all required relations exist in the new snapshot, patches bytecode relation payloads,
         * compiles a new JIT MethodHandle, and atomically swaps the binding state for zero-delay query execution.
         *
         * @param newSnapshot target snapshot after blue/green swap
         */
        public void rebind(ImpulseGraphSnapshot newSnapshot) {
            Objects.requireNonNull(newSnapshot, "newSnapshot must not be null");
            QueryBindingState currentState = bindingState.get();
            if (currentState.snapshot() == newSnapshot) {
                return; // Already bound
            }

            // 1. Verification Step: Verify required logical relations exist in new snapshot
            Map<String, Integer> newRelationIdMap = new HashMap<>();
            for (RelationInstructionPatch patch : patches) {
                String logicalName = patch.logicalRelationName();
                RelationSnapshot rel = findRelationInSnapshot(newSnapshot, logicalName);
                if (rel == null) {
                    throw new IllegalStateException("Blue/Green Re-bind Verification Failed: Required relation '"
                            + logicalName + "' is missing in target snapshot.");
                }
                int newRelId = resolveRelationId(newSnapshot, logicalName);
                newRelationIdMap.put(logicalName, newRelId);
            }

            // 2. Clone instruction program segment to avoid mutating active concurrent memory segment
            MemorySegment oldProg = currentState.programSeg();
            MemorySegment newProgSeg = arena.allocate(INSTRUCTION_LAYOUT, currentState.instructionCount());
            newProgSeg.copyFrom(oldProg);

            // 3. Patch Bytecode Instructions in new program segment
            for (RelationInstructionPatch patch : patches) {
                int newRelId = newRelationIdMap.get(patch.logicalRelationName());
                int newPayload = ((newRelId & 0xFFFF) << 16) | (patch.srcReg() & 0xFFFF);
                long off = patch.pc() * INSTRUCTION_SIZE_BYTES;
                INSTR_PAYLOAD_HANDLE.set(newProgSeg, off, newPayload);
            }

            // 4. Compile new MethodHandle and perform Atomic Swap
            MethodHandle newMh = ImpulseMethodHandleCompiler.compile(newProgSeg, currentState.instructionCount(), this.stringPool);
            QueryBindingState newState = new QueryBindingState(
                    newSnapshot, newMh, newProgSeg, currentState.instructionCount(), newRelationIdMap
            );
            this.bindingState.set(newState);
        }

        /**
         * Executes the compiled query against a graph snapshot with lock-free atomic state reads
         * and active query reference counting.
         */
        public Object execute(ImpulseGraphSnapshot snapshot, Object input, Arena executionArena) {
            QueryBindingState state = bindingState.get();
            if (snapshot != null && snapshot != state.snapshot()) {
                rebind(snapshot);
                state = bindingState.get();
            }

            ImpulseGraphSnapshot targetSnapshot = state.snapshot();
            if (targetSnapshot != null) {
                targetSnapshot.enterQuery();
            }
            try {
                if (state.methodHandle() != null) {
                    return state.methodHandle().invoke(targetSnapshot, input, executionArena);
                }
                return ImpulseVmInterpreter.execute(state.programSeg(), state.instructionCount(), targetSnapshot, input, executionArena, this.stringPool);
            } catch (Throwable e) {
                return ImpulseVmInterpreter.execute(state.programSeg(), state.instructionCount(), targetSnapshot, input, executionArena, this.stringPool);
            } finally {
                if (targetSnapshot != null) {
                    targetSnapshot.exitQuery();
                }
            }
        }

        /**
         * Disassembles the compiled bytecode instructions into human-readable assembly code.
         */
        public String disassemble() {
            QueryBindingState state = bindingState.get();
            long instructionCount = state.instructionCount();
            MemorySegment programSeg = state.programSeg();

            StringBuilder sb = new StringBuilder();
            sb.append("=========================================================================\n");
            sb.append("                  IMPULSE VM BYTECODE DISASSEMBLY                       \n");
            sb.append("=========================================================================\n");
            sb.append("Instruction Count: ").append(instructionCount).append("\n");
            sb.append("Program Memory Segment Size: ").append(programSeg.byteSize()).append(" bytes\n");
            sb.append("-------------------------------------------------------------------------\n");

            Map<Long, String> patchMap = new HashMap<>();
            for (RelationInstructionPatch patch : patches) {
                patchMap.put(patch.pc(), patch.logicalRelationName());
            }

            for (long pc = 0; pc < instructionCount; pc++) {
                VmHandlers.Instruction instr = VmHandlers.decodeInstruction(programSeg, pc);
                String opName = getOpcodeName(instr.opcode());
                sb.append(String.format("0x%04X: %-24s [flags=0x%02X, dst=R%-2d, payload=0x%08X (%d)]",
                        pc, opName, instr.flags(), instr.dstReg(), instr.payload(), instr.payload()));

                if (instr.opcode() == OP_CSR_WALK) {
                    int srcReg = instr.payload() & 0xFFFF;
                    int relId = (instr.payload() >> 16) & 0xFFFF;
                    String relName = patchMap.getOrDefault(pc, "rel_" + relId);
                    sb.append(String.format(" -> WALK src=R%d -> dst=R%d via rel[%d] (\"%s\")",
                            srcReg, instr.dstReg(), relId, relName));
                } else if (instr.opcode() == OP_JMP || instr.opcode() == OP_JZ || instr.opcode() == OP_JNZ || instr.opcode() == OP_LOOP_DECR) {
                    sb.append(String.format(" -> Target PC: 0x%04X", instr.payload() & 0xFFFFFFFFL));
                }
                sb.append("\n");
            }
            sb.append("=========================================================================\n");
            return sb.toString();
        }
    }

    /**
     * Compiles an AST pipeline into a {@link CompiledQuery} using off-heap memory.
     */
    public static CompiledQuery compile(List<StepNode> astSteps, ImpulseGraphSnapshot snapshot, Arena arena) {
        Objects.requireNonNull(astSteps, "astSteps must not be null");
        Objects.requireNonNull(arena, "arena must not be null");

        List<InstructionBuilderData> instrList = new ArrayList<>();
        List<RelationInstructionPatch> patches = new ArrayList<>();
        Map<String, Integer> relationIdMap = new HashMap<>();
        java.util.List<String> stringPool = new java.util.ArrayList<>();

        short currentReg = 0;
        short inputReg = 0;

        // Process Steps
        for (int i = 0; i < astSteps.size(); i++) {
            StepNode step = astSteps.get(i);
            String op = step.op();

            if ("INPUT".equalsIgnoreCase(op)) {
                if (step.argType() == org.impulsegraph.api.ArgType.ROARING_BITSET || step.argType() == org.impulsegraph.api.ArgType.NODE_ARRAY) {
                    instrList.add(new InstructionBuilderData(OP_INIT_INPUT_SET, (byte) 0, inputReg, 0));
                } else {
                    instrList.add(new InstructionBuilderData(OP_INIT_INPUT_NODE, (byte) 0, inputReg, 0));
                }
            } else if ("FILTER_NODE".equalsIgnoreCase(op)) {
                short srcReg = currentReg;
                short dstReg = (short) (currentReg + 1);
                currentReg = dstReg;
                int payload = ((srcReg & 0xFFFF) << 16);
                instrList.add(new InstructionBuilderData(OP_NODE_FILTER, (byte) 0, dstReg, payload));
                        } else if ("PROJECT_EXPRESSION".equalsIgnoreCase(op)) {
                short srcReg = currentReg;
                short dstReg = (short) (currentReg + 1);
                currentReg = dstReg;
                String attrName = step.relation().split(":")[0];
                if (!stringPool.contains(attrName)) {
                    stringPool.add(attrName);
                }
                int nameIdx = stringPool.indexOf(attrName);
                int payload = ((srcReg & 0xFFFF) << 16) | (nameIdx & 0xFFFF);
                instrList.add(new InstructionBuilderData(OP_VECTOR_LOAD_ATTR, (byte) 0, dstReg, payload));
            } else if (op != null && op.startsWith("REDUCE")) {
                short dstReg = currentReg;
                byte opcode = "REDUCE_FIRST".equalsIgnoreCase(op) ? OP_REDUCE : OP_VECTOR_REDUCE_SUM;
                if ("REDUCE_ARGMAX".equalsIgnoreCase(op)) opcode = OP_VECTOR_REDUCE_ARGMAX;
                instrList.add(new InstructionBuilderData(opcode, (byte) 0, dstReg, 0));
            } else if ("ISLAND_DETECT".equalsIgnoreCase(op)) {
                short dstReg = (short) (currentReg + 1);
                currentReg = dstReg;
                instrList.add(new InstructionBuilderData(OP_ISLAND_DETECT, (byte) 0, dstReg, 0));
            } else if ("REBAC_CHECK".equalsIgnoreCase(op)) {
                short dstReg = (short) (currentReg + 1);
                currentReg = dstReg;
                instrList.add(new InstructionBuilderData(OP_REBAC_CHECK, (byte) 0, dstReg, 0));
            } else if ("MOTIF_MATCH_3".equalsIgnoreCase(op)) {
                short dstReg = (short) (currentReg + 1);
                currentReg = dstReg;
                instrList.add(new InstructionBuilderData(OP_MOTIF_MATCH_3, (byte) 0, dstReg, 0));
            } else if (op != null && op.startsWith("WALK")) {
                String logicalRelName = step.relation();
                if (logicalRelName != null && logicalRelName.contains(":")) {
                    logicalRelName = logicalRelName.split(":")[0];
                }

                short srcReg = currentReg;
                short dstReg = (short) (currentReg + 1);
                currentReg = dstReg;

                int relId = resolveRelationId(snapshot, logicalRelName);
                relationIdMap.put(logicalRelName, relId);

                long pc = instrList.size();
                patches.add(new RelationInstructionPatch(pc, logicalRelName, srcReg, dstReg));

                byte opcode = op.contains("FILTERED") ? OP_CSR_WALK_FILTERED : OP_CSR_WALK;
                int payload = ((relId & 0xFFFF) << 16) | (srcReg & 0xFFFF);
                instrList.add(new InstructionBuilderData(opcode, (byte) 0, dstReg, payload));
            } else if ("REPEAT".equalsIgnoreCase(op)) {
                int repeatCount = step.repeatCount();
                short countReg = (short) (currentReg + 2);

                // Load loop counter constant into countReg
                instrList.add(new InstructionBuilderData(OP_LOAD_CONST_INT, (byte) 0, countReg, repeatCount));

                long loopStartPc = instrList.size();
                currentReg = compileSubSteps(step.subSteps(), snapshot, instrList, patches, relationIdMap, currentReg);

                // Emit OP_LOOP_DECR jumping back to loopStartPc
                instrList.add(new InstructionBuilderData(OP_LOOP_DECR, (byte) 0, countReg, (int) loopStartPc));
            } else if ("REPEAT_UNTIL_STABLE".equalsIgnoreCase(op)) {
                long loopStartPc = instrList.size();
                currentReg = compileSubSteps(step.subSteps(), snapshot, instrList, patches, relationIdMap, currentReg);

                // Emit OP_STABLE_CHECK on current result register
                instrList.add(new InstructionBuilderData(OP_STABLE_CHECK, (byte) 0, currentReg, 0));

                // Emit OP_JNZ back to loopStartPc if not stable
                instrList.add(new InstructionBuilderData(OP_JNZ, (byte) 0, (short) 0, (int) loopStartPc));
            } else if ("COLLECT".equalsIgnoreCase(op)) {
                instrList.add(new InstructionBuilderData(OP_COLLECT_BITSET, (byte) 0, currentReg, 0));
            }
        }

        // Always end program with OP_HALT
        instrList.add(new InstructionBuilderData(OP_HALT, (byte) 0, (short) 0, 0));

        // Build off-heap memory segment for instructions
        long count = instrList.size();
        MemorySegment programSeg = arena.allocate(INSTRUCTION_LAYOUT, count);

        for (int i = 0; i < count; i++) {
            long off = i * INSTRUCTION_SIZE_BYTES;
            InstructionBuilderData data = instrList.get(i);
            INSTR_OPCODE_HANDLE.set(programSeg, off, data.opcode);
            INSTR_FLAGS_HANDLE.set(programSeg, off, data.flags);
            INSTR_DST_REG_HANDLE.set(programSeg, off, data.dstReg);
            INSTR_PAYLOAD_HANDLE.set(programSeg, off, data.payload);
        }

        return new CompiledQuery(programSeg, count, patches, relationIdMap, snapshot, arena, stringPool);
    }

    private static short compileSubSteps(List<StepNode> subSteps, ImpulseGraphSnapshot snapshot,
                                         List<InstructionBuilderData> instrList,
                                         List<RelationInstructionPatch> patches,
                                         Map<String, Integer> relationIdMap,
                                         short startReg) {
        short currentReg = startReg;
        for (StepNode step : subSteps) {
            String op = step.op();
            if (op != null && op.startsWith("WALK")) {
                String logicalRelName = step.relation();
                if (logicalRelName != null && logicalRelName.contains(":")) {
                    logicalRelName = logicalRelName.split(":")[0];
                }

                short srcReg = currentReg;
                short dstReg = (short) (currentReg + 1);
                currentReg = dstReg;

                int relId = resolveRelationId(snapshot, logicalRelName);
                relationIdMap.put(logicalRelName, relId);

                long pc = instrList.size();
                patches.add(new RelationInstructionPatch(pc, logicalRelName, srcReg, dstReg));

                int payload = ((relId & 0xFFFF) << 16) | (srcReg & 0xFFFF);
                instrList.add(new InstructionBuilderData(OP_CSR_WALK, (byte) 0, dstReg, payload));
            }
        }
        return currentReg;
    }

    private static int resolveRelationId(ImpulseGraphSnapshot snapshot, String relName) {
        if (snapshot == null || relName == null) return 0;

        // Try exact key lookup in relationMap
        var map = snapshot.getAllRelationSnapshots();
        if (map.containsKey(relName)) {
            int idx = 0;
            for (String key : map.keySet()) {
                if (key.equalsIgnoreCase(relName)) return idx;
                idx++;
            }
        }

        // Try suffix or substring match (e.g. "rel_0_userToGroup" matching "userToGroup")
        int idx = 0;
        for (String key : map.keySet()) {
            if (key.endsWith("_" + relName) || key.endsWith(relName) || key.toLowerCase().endsWith(relName.toLowerCase())) {
                return idx;
            }
            idx++;
        }

        return 0;
    }

    private static RelationSnapshot findRelationInSnapshot(ImpulseGraphSnapshot snapshot, String relName) {
        if (snapshot == null || relName == null) return null;
        RelationSnapshot rel = snapshot.getRelationSnapshot(relName);
        if (rel != null) return rel;

        for (Map.Entry<String, RelationSnapshot> entry : snapshot.getAllRelationSnapshots().entrySet()) {
            if (entry.getKey().endsWith("_" + relName) || entry.getKey().equalsIgnoreCase(relName)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Disassembles an arbitrary AST query against an optional graph snapshot.
     */
    public static String disassembleQuery(ImpulseGraphQuery<?> query, ImpulseGraphSnapshot snapshot) {
        if (query == null) return "Query is null";
        ImpulseGraphSnapshot graph = snapshot;
        try (Arena arena = Arena.ofConfined()) {
            CompiledQuery compiled = compile(query.getSteps(), graph, arena);
            return compiled.disassemble();
        }
    }

    private record InstructionBuilderData(byte opcode, byte flags, short dstReg, int payload) {}

    private static String getOpcodeName(byte opcode) {
        return switch (opcode) {
            case OP_NOP -> "OP_NOP";
            case OP_INIT_INPUT_NODE -> "OP_INIT_INPUT_NODE";
            case OP_INIT_INPUT_SET -> "OP_INIT_INPUT_SET";
            case OP_LOAD_CONST_INT -> "OP_LOAD_CONST_INT";
            case OP_LOAD_CONST_FLOAT -> "OP_LOAD_CONST_FLOAT";
            case OP_CSR_WALK -> "OP_CSR_WALK";
            case OP_CSR_WALK_FILTERED -> "OP_CSR_WALK_FILTERED";
            case OP_SET_UNION -> "OP_SET_UNION";
            case OP_SET_INTERSECT -> "OP_SET_INTERSECT";
            case OP_SET_DIFFERENCE -> "OP_SET_DIFFERENCE";
            case OP_SET_CARDINALITY -> "OP_SET_CARDINALITY";
            case OP_FLOAT_VECTOR_SCALE -> "OP_FLOAT_VECTOR_SCALE";
            case OP_L1_NORM_DIFF -> "OP_L1_NORM_DIFF";
            case OP_TC_SWEEP_BATCH -> "OP_TC_SWEEP_BATCH";
            case OP_READ_EDGE_WEIGHT -> "OP_READ_EDGE_WEIGHT";
            case OP_JMP -> "OP_JMP";
            case OP_JZ -> "OP_JZ";
            case OP_JNZ -> "OP_JNZ";
            case OP_LOOP_DECR -> "OP_LOOP_DECR";
            case OP_STABLE_CHECK -> "OP_STABLE_CHECK";
            case OP_MOV -> "OP_MOV";
            case OP_CLEAR_REG -> "OP_CLEAR_REG";
            case OP_ISLAND_DETECT -> "OP_ISLAND_DETECT";
            case OP_REBAC_CHECK -> "OP_REBAC_CHECK";
            case OP_MOTIF_MATCH_3 -> "OP_MOTIF_MATCH_3";
            case OP_NODE_FILTER -> "OP_NODE_FILTER";
            case OP_VECTOR_LOAD_ATTR -> "OP_VECTOR_LOAD_ATTR";
            case OP_VECTOR_REDUCE_SUM -> "OP_VECTOR_REDUCE_SUM";
            case OP_REDUCE -> "OP_REDUCE";
            case OP_COLLECT_BITSET -> "OP_COLLECT_BITSET";
            case OP_HALT -> "OP_HALT";
            default -> "OP_UNKNOWN_0x" + Integer.toHexString(opcode & 0xFF).toUpperCase();
        };
    }
}
