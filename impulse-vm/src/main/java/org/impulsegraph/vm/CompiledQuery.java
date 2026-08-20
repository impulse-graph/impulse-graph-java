package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

/**
 * Immutable compiled VM execution plan with zero-copy MethodHandle JIT compilation,
 * physical relation patch table, lock-free snapshot re-binding, and disassembly.
 */
public final class CompiledQuery {

    public record QueryBindingState(
            ImpulseGraphSnapshot snapshot,
            MethodHandle methodHandle,
            MemorySegment programSeg,
            long instructionCount,
            Map<String, Integer> relationIdMap
    ) {}

    private final List<RelationInstructionPatch> patches;
    private final Arena arena;
    private final AtomicReference<QueryBindingState> bindingState;
    private final List<String> stringPool;

    public CompiledQuery(MemorySegment programSeg, long instructionCount, List<RelationInstructionPatch> patches,
                         Map<String, Integer> relationIdMap, ImpulseGraphSnapshot initialSnapshot, Arena arena,
                         List<String> stringPool) {
        this.stringPool = stringPool != null ? List.copyOf(stringPool) : List.of();
        Objects.requireNonNull(programSeg, "programSeg must not be null");
        this.patches = List.copyOf(patches);
        this.arena = arena;
        MethodHandle mh = ImpulseMethodHandleCompiler.compile(programSeg, instructionCount, this.stringPool);
        this.bindingState = new AtomicReference<>(
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

    public List<String> stringPool() {
        return stringPool;
    }

    public List<RelationInstructionPatch> patches() {
        return patches;
    }

    /**
     * Re-binds this compiled query to a new blue/green swapped snapshot using an atomic reference update.
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
        long instrCount = state.instructionCount();
        MemorySegment programSeg = state.programSeg();

        StringBuilder sb = new StringBuilder();
        sb.append("=========================================================================\n");
        sb.append("                  IMPULSE VM BYTECODE DISASSEMBLY                       \n");
        sb.append("=========================================================================\n");
        sb.append("Instruction Count: ").append(instrCount).append("\n");
        sb.append("Program Memory Segment Size: ").append(programSeg.byteSize()).append(" bytes\n");
        sb.append("-------------------------------------------------------------------------\n");

        Map<Long, String> patchMap = new HashMap<>();
        for (RelationInstructionPatch patch : patches) {
            patchMap.put(patch.pc(), patch.logicalRelationName());
        }

        for (long pc = 0; pc < instrCount; pc++) {
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

    private static String getOpcodeName(byte opcode) {
        return switch (opcode) {
            case OP_NOP -> "OP_NOP";
            case OP_INIT_INPUT_NODE -> "OP_INIT_INPUT_NODE";
            case OP_INIT_INPUT_SET -> "OP_INIT_INPUT_SET";
            case OP_LOAD_CONST_INT -> "OP_LOAD_CONST_INT";
            case OP_LOAD_CONST_FLOAT -> "OP_LOAD_CONST_FLOAT";
            case OP_CSR_WALK -> "OP_CSR_WALK";
            case OP_CSR_WALK_FILTERED -> "OP_CSR_WALK_FILTERED";
            case OP_CSR_WALK_2HOP -> "OP_CSR_WALK_2HOP";
            case OP_CSC_WALK -> "OP_CSC_WALK";
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
            case OP_VECTOR_REDUCE_MAX -> "OP_VECTOR_REDUCE_MAX";
            case OP_VECTOR_REDUCE_MIN -> "OP_VECTOR_REDUCE_MIN";
            case OP_VECTOR_REDUCE_ARGMAX -> "OP_VECTOR_REDUCE_ARGMAX";
            case OP_VECTOR_REDUCE_ARGMIN -> "OP_VECTOR_REDUCE_ARGMIN";
            case OP_REDUCE -> "OP_REDUCE";
            case OP_COLLECT_BITSET -> "OP_COLLECT_BITSET";
            case OP_COLLECT_ARRAY -> "OP_COLLECT_ARRAY";
            case OP_HALT -> "OP_HALT";
            default -> "OP_UNKNOWN_0x" + Integer.toHexString(opcode & 0xFF).toUpperCase();
        };
    }
}
