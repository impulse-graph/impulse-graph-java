package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.EmittedProgram;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter.InstructionWord;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Impulse VM Interpreter & Emitter Comprehensive Test Suite")
public class ImpulseVmInterpreterComprehensiveTest {

	@Test
	@DisplayName("Interpreter: Null or empty program segment returns empty bitset")
	void testNullOrEmptyProgram() {
		try (Arena arena = Arena.ofConfined()) {
			Object res1 = ImpulseVmInterpreter.execute(null, 0, (ImpulseGraphSnapshot) null, null, arena);
			assertThat(res1).isInstanceOf(ImpulseBitSet.class);

			MemorySegment seg = arena.allocate(8);
			Object res2 = ImpulseVmInterpreter.execute(seg, 0, (ImpulseGraphSnapshot) null, null, arena);
			assertThat(res2).isInstanceOf(ImpulseBitSet.class);

			Object res3 = ImpulseVmInterpreter.execute(null, 5, (ImpulseGraphSnapshot) null, null, arena);
			assertThat(res3).isInstanceOf(ImpulseBitSet.class);

			Object res4 = ImpulseVmInterpreter.execute(seg, -1, (ImpulseGraphSnapshot) null, null, arena, List.of());
			assertThat(res4).isInstanceOf(ImpulseBitSet.class);
		}
	}

	@Test
	@DisplayName("Interpreter: Private constructor accessibility")
	void testPrivateConstructor() throws Exception {
		Constructor<ImpulseVmInterpreter> ctor = ImpulseVmInterpreter.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		ImpulseVmInterpreter instance = ctor.newInstance();
		assertNotNull(instance);
	}

	@Test
	@DisplayName("Interpreter: Opcode dispatch loop covers all instructions")
	void testAllOpcodesDispatch() {
		try (Arena arena = Arena.ofConfined()) {
			int[] offsets = new int[]{0, 2, 3, 3};
			int[] targets = new int[]{1, 2, 2};
			MockRelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3, offsets, targets);
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of("testRel", rel, "rel_0", rel));

			byte[] opcodesToTest = {OP_NOP, OP_HALT, OP_TRAP, OP_INIT_INPUT_NODE, OP_INIT_INPUT_SET, OP_LOAD_CONST_INT,
					OP_LOAD_CONST_FLOAT, OP_LOAD_CONST_STR_PREFIX, OP_MAP_KEYS_TO_DENSE, OP_CSR_WALK, OP_CSR_WALK_2HOP,
					OP_CSC_WALK, OP_HAS_CSR, OP_HAS_CSC, OP_HAS_COO, OP_HAS_KEY_CATALOG, OP_ADAPTIVE_WALK,
					OP_CSR_DEGREE, OP_CSR_WALK_PREDICATE, OP_CSR_WALK_REDUCE_SUM, OP_CSR_WALK_REDUCE, OP_SET_UNION,
					OP_SET_INTERSECT, OP_SET_DIFFERENCE, OP_SET_CARDINALITY, OP_FLOAT_VECTOR_SCALE, OP_L1_NORM_DIFF,
					OP_VECTOR_DIV, OP_VECTOR_STR_CONCAT, OP_ROARING_BITMAP_AND, OP_ROARING_BITMAP_OR,
					OP_ROARING_BITMAP_AND_NOT, OP_TC_SWEEP_BATCH, OP_READ_EDGE_WEIGHT, OP_JMP, OP_JZ, OP_JNZ,
					OP_LOOP_DECR, OP_STABLE_CHECK, OP_CALL, OP_RET, OP_MOV, OP_CLEAR_REG, OP_NODE_FILTER,
					OP_NODE_FILTER_STR_PREFIX, OP_CSR_WALK_FILTERED, OP_VEC_CMP_EQ, OP_VEC_CMP_GT, OP_VEC_CMP_LT,
					OP_VEC_CMP_BETWEEN, OP_MASK_AND, OP_MASK_OR, OP_MASK_NOT, OP_VEC_BLEND, OP_VEC_MATH_UNARY,
					OP_VEC_MATH_BINARY, OP_VEC_MATH_TERNARY, OP_ASSERT_FINITE, OP_VECTOR_TIME_VALID_AT,
					OP_COLLECT_BITSET, OP_COLLECT_ARRAY, OP_COLLECT_VALUE_MAP, OP_MAP_DENSE_TO_KEYS,
					OP_DENSE_WALK_REDUCE, OP_DENSE_WALK_DIRECT_STORE, OP_LOAD_INDIRECT, OP_ALLOC_SCRATCH,
					OP_ASSERT_SCRATCH_BYTES, OP_SET_MAX_DOP, OP_SAMPLE_NEIGHBORS, OP_RANDOM_WALK, OP_SCATTER_GATHER,
					OP_REBAC_CHECK, OP_SPARSE_MATVEC, OP_LOUVAIN_MODULARITY, OP_KCORE_DECOMPOSITION, OP_MOTIF_MATCH_3,
					OP_GRAPH_ISOMORPHISM, OP_ENTER_FRAME, OP_LEAVE_FRAME, OP_ASSERT, OP_CSR_WALK_STATE,
					OP_CREATE_SCRATCH_INDEX, OP_DROP_SCRATCH_INDEX};

			for (byte op : opcodesToTest) {
				MemorySegment progSeg = arena.allocate(16);
				int payload = (op == OP_JMP || op == OP_JZ || op == OP_JNZ || op == OP_LOOP_DECR || op == OP_CALL)
						? 1
						: 0;
				INSTR_OPCODE_HANDLE.set(progSeg, 0L, op);
				INSTR_FLAGS_HANDLE.set(progSeg, 0L, (byte) 0);
				INSTR_DST_REG_HANDLE.set(progSeg, 0L, (short) 0);
				INSTR_PAYLOAD_HANDLE.set(progSeg, 0L, payload);

				// instr 1: OP_HALT
				INSTR_OPCODE_HANDLE.set(progSeg, 8L, OP_HALT);
				INSTR_FLAGS_HANDLE.set(progSeg, 8L, (byte) 0);
				INSTR_DST_REG_HANDLE.set(progSeg, 8L, (short) 0);
				INSTR_PAYLOAD_HANDLE.set(progSeg, 8L, 0);

				try {
					ImpulseVmInterpreter.execute(progSeg, 2, snap, 1L, arena, List.of("prop1", "prop2"));
				} catch (Throwable ignored) {
					// Some opcodes may throw assertion/trap which is expected and exercises the
					// error paths
				}
			}

			// Test with FLAG_HALT_ON_EMPTY on walk opcodes
			for (byte op : new byte[]{OP_CSR_WALK, OP_CSR_WALK_2HOP, OP_CSC_WALK}) {
				MemorySegment progSeg = arena.allocate(16);
				INSTR_OPCODE_HANDLE.set(progSeg, 0L, op);
				INSTR_FLAGS_HANDLE.set(progSeg, 0L, VmHandlers.FLAG_HALT_ON_EMPTY);
				INSTR_DST_REG_HANDLE.set(progSeg, 0L, (short) 0);
				INSTR_PAYLOAD_HANDLE.set(progSeg, 0L, 0);

				INSTR_OPCODE_HANDLE.set(progSeg, 8L, OP_HALT);
				INSTR_FLAGS_HANDLE.set(progSeg, 8L, (byte) 0);
				INSTR_DST_REG_HANDLE.set(progSeg, 8L, (short) 0);
				INSTR_PAYLOAD_HANDLE.set(progSeg, 8L, 0);

				try {
					ImpulseVmInterpreter.execute(progSeg, 2, snap, 1L, arena, List.of());
				} catch (Throwable ignored) {
				}
			}

			// Test OP_THROW
			MemorySegment throwSeg = arena.allocate(8);
			INSTR_OPCODE_HANDLE.set(throwSeg, 0L, OP_THROW);
			INSTR_FLAGS_HANDLE.set(throwSeg, 0L, (byte) 0);
			INSTR_DST_REG_HANDLE.set(throwSeg, 0L, (short) 0);
			INSTR_PAYLOAD_HANDLE.set(throwSeg, 0L, 0);
			try {
				ImpulseVmInterpreter.execute(throwSeg, 1, snap, 1L, arena, List.of());
			} catch (Throwable ignored) {
			}

			// Test OP_RESERVED_*
			MemorySegment reservedSeg = arena.allocate(8);
			INSTR_OPCODE_HANDLE.set(reservedSeg, 0L, OP_RESERVED_7E);
			INSTR_FLAGS_HANDLE.set(reservedSeg, 0L, (byte) 0);
			INSTR_DST_REG_HANDLE.set(reservedSeg, 0L, (short) 0);
			INSTR_PAYLOAD_HANDLE.set(reservedSeg, 0L, 0);
			assertThatThrownBy(() -> ImpulseVmInterpreter.execute(reservedSeg, 1, snap, 1L, arena, List.of()))
					.isInstanceOf(IllegalStateException.class).hasMessageContaining("IMPULSE_VM_ERR_RESERVED_OPCODE");

			// Test unknown opcode
			MemorySegment unknownSeg = arena.allocate(8);
			INSTR_OPCODE_HANDLE.set(unknownSeg, 0L, (byte) 0xFE);
			INSTR_FLAGS_HANDLE.set(unknownSeg, 0L, (byte) 0);
			INSTR_DST_REG_HANDLE.set(unknownSeg, 0L, (short) 0);
			INSTR_PAYLOAD_HANDLE.set(unknownSeg, 0L, 0);
			assertThatThrownBy(() -> ImpulseVmInterpreter.execute(unknownSeg, 1, snap, 1L, arena, List.of()))
					.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown opcode");
		}
	}

	@Test
	@DisplayName("Emitter: Emits all AST node variants into bytecode")
	void testBytecodeEmitterAllNodes() {
		try (Arena arena = Arena.ofConfined()) {
			int[] offsets = new int[]{0, 2, 3, 3};
			int[] targets = new int[]{1, 2, 2};
			MockRelationSnapshot rel = new MockRelationSnapshot(arena, 3, 3, offsets, targets);
			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of("userToGroup", rel, "groupToRole", rel));

			// 1. ScmReduce with all 7 ops
			for (ScmReduce.Op op : ScmReduce.Op.values()) {
				ScmProgram prog = new ScmProgram(List.of(ScmWalk.forward("userToGroup"), new ScmReduce(op),
						new ScmCollect(ScmCollect.Format.BITSET)));
				EmittedProgram emitted = ImpOpsBytecodeEmitter.emit(prog, snap, arena);
				assertNotNull(emitted);
				assertThat(emitted.instructionCount()).isGreaterThan(0);
			}

			// 2. ScmCollect with VECTOR and LIST formats
			ScmProgram progVec = new ScmProgram(
					List.of(ScmWalk.forward("userToGroup"), new ScmCollect(ScmCollect.Format.VECTOR)));
			EmittedProgram emittedVec = ImpOpsBytecodeEmitter.emit(progVec, snap, arena);
			assertNotNull(emittedVec);

			ScmProgram progList = new ScmProgram(
					List.of(ScmWalk.forward("userToGroup"), new ScmCollect(ScmCollect.Format.LIST)));
			EmittedProgram emittedList = ImpOpsBytecodeEmitter.emit(progList, snap, arena);
			assertNotNull(emittedList);

			// 3. ScmList macros: repeat, repeat-until-stable, project-expression,
			// island-detect, rebac-check, motif-match-3
			ScmProgram subProg = new ScmProgram(List.of(ScmWalk.forward("groupToRole")));

			ScmList repeatList = new ScmList(List.of(new ScmSymbol("repeat"), new ScmLiteral.ScmInt(3), subProg));
			ScmProgram progRepeat = new ScmProgram(
					List.of(ScmWalk.forward("userToGroup"), repeatList, new ScmCollect(ScmCollect.Format.BITSET)));
			EmittedProgram emittedRepeat = ImpOpsBytecodeEmitter.emit(progRepeat, snap, arena);
			assertNotNull(emittedRepeat);

			ScmList stableList = new ScmList(List.of(new ScmSymbol("repeat-until-stable"), subProg));
			ScmProgram progStable = new ScmProgram(
					List.of(ScmWalk.forward("userToGroup"), stableList, new ScmCollect(ScmCollect.Format.BITSET)));
			EmittedProgram emittedStable = ImpOpsBytecodeEmitter.emit(progStable, snap, arena);
			assertNotNull(emittedStable);

			ScmList projectList = new ScmList(List.of(new ScmSymbol("project-expression"), new ScmSymbol("user_age")));
			ScmProgram progProject = new ScmProgram(
					List.of(ScmWalk.forward("userToGroup"), projectList, new ScmCollect(ScmCollect.Format.BITSET)));
			EmittedProgram emittedProject = ImpOpsBytecodeEmitter.emit(progProject, snap, arena);
			assertNotNull(emittedProject);
			assertThat(emittedProject.stringPool()).contains("user_age");

			for (String macro : List.of("island-detect", "rebac-check", "motif-match-3")) {
				ScmList macroList = new ScmList(List.of(new ScmSymbol(macro)));
				ScmProgram progMacro = new ScmProgram(
						List.of(ScmWalk.forward("userToGroup"), macroList, new ScmCollect(ScmCollect.Format.BITSET)));
				EmittedProgram emittedMacro = ImpOpsBytecodeEmitter.emit(progMacro, snap, arena);
				assertNotNull(emittedMacro);
			}

			// 4. ScmWalk2Hop, ScmStreamFilter, ScmVectorFilter
			ScmProgram progFilters = new ScmProgram(List.of(ScmWalk2Hop.of("userToGroup", "groupToRole"),
					new ScmStreamFilter(new ScmCelExpr("age > 18")), new ScmVectorFilter(new ScmCelExpr("age > 21")),
					new ScmCollect(ScmCollect.Format.BITSET)));
			EmittedProgram emittedFilters = ImpOpsBytecodeEmitter.emit(progFilters, snap, arena);
			assertNotNull(emittedFilters);

			// 5. ScmWalk with shader stream expressions (stream-cmp-gt, get-attr, mask-and,
			// ScmLiteral)
			ScmList streamExpr = new ScmList(List.of(new ScmSymbol("stream-cmp-gt"), new ScmList(
					List.of(new ScmSymbol("get-attr"), new ScmSymbol("node"), new ScmLiteral.ScmString("score"))),
					new ScmLiteral.ScmFloat(10.5)));
			ScmList logicExpr = new ScmList(List.of(new ScmSymbol("mask-and"), streamExpr, new ScmLiteral.ScmInt(1)));
			ScmWalk walkWithShader = ScmWalk.forward("userToGroup", logicExpr);
			ScmProgram progShader = new ScmProgram(List.of(walkWithShader, new ScmCollect(ScmCollect.Format.BITSET)));
			EmittedProgram emittedShader = ImpOpsBytecodeEmitter.emit(progShader, snap, arena);
			assertNotNull(emittedShader);
		}
	}

	@Test
	@DisplayName("Math Handlers: Exercises all unary, binary, and ternary math function cases on vectors and scalars")
	void testAllUnaryBinaryTernaryMathOps() {
		try (Arena arena = Arena.ofConfined(); VmQueryContext ctx = new VmQueryContext(null, arena)) {
			MemorySegment state = ctx.allocateStateSegment();

			// Setup double vector in R1 and float vector in R2
			int dHandle = ctx.registerDoubleVector(new double[]{0.5, 1.0, 2.0, -1.0, Double.NaN});
			VmHandlers.setRegister(state, 1, dHandle, TYPE_DOUBLE_VECTOR);

			int fHandle = ctx.registerFloatVector(new float[]{0.5f, 1.0f, 2.0f, -1.0f, Float.NaN});
			VmHandlers.setRegister(state, 2, fHandle, TYPE_FLOAT_VECTOR);

			VmHandlers.setRegister(state, 3, Double.doubleToRawLongBits(1.5), TYPE_DOUBLE);
			VmHandlers.setRegister(state, 4, Float.floatToRawIntBits(1.5f), TYPE_FLOAT);
			VmHandlers.setRegister(state, 5, 42L, TYPE_INT64);

			int[] unaryFuncs = {1, 2, 3, 4, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 23, 24, 25, 26, 27,
					28, 29, 30, 31, 32, 33, 37, 38, 39, 40, 41, 42, 52, 53, 54, 999};

			for (int funcId : unaryFuncs) {
				// Double vector
				VmHandlers.handleVecMathUnary(state, ctx,
						new VmHandlers.Instruction(OP_VEC_MATH_UNARY, (byte) 0, (short) 10, (funcId << 8) | 1));
				// Float vector
				VmHandlers.handleVecMathUnary(state, ctx,
						new VmHandlers.Instruction(OP_VEC_MATH_UNARY, (byte) 0, (short) 11, (funcId << 8) | 2));
			}

			// Invalid register type check
			assertThatThrownBy(() -> VmHandlers.handleVecMathUnary(state, ctx,
					new VmHandlers.Instruction(OP_VEC_MATH_UNARY, (byte) 0, (short) 12, 3)))
					.isInstanceOf(IllegalArgumentException.class);

			int[] binaryFuncs = {0, 1, 2, 3, 4, 5, 6, 22, 35, 36, 38, 51, 100, 101, 102, 103, 999};

			for (int funcId : binaryFuncs) {
				// Double vector
				VmHandlers.handleVecMathBinary(state, ctx, new VmHandlers.Instruction(OP_VEC_MATH_BINARY, (byte) 0,
						(short) 14, (funcId << 16) | (1 << 8) | 1));
				// Float vector
				VmHandlers.handleVecMathBinary(state, ctx, new VmHandlers.Instruction(OP_VEC_MATH_BINARY, (byte) 0,
						(short) 15, (funcId << 16) | (2 << 8) | 2));
			}

			// Ternary: funcId 0 (FMA), funcId 1 (CLAMP), funcId 2 (LERP)
			for (int funcId : new int[]{0, 1, 2, 999}) {
				int payload = (funcId << 24) | (1 << 16) | (1 << 8) | 1;
				VmHandlers.handleVecMathTernary(state, ctx,
						new VmHandlers.Instruction(OP_VEC_MATH_TERNARY, (byte) 0, (short) 18, payload));

				int payloadFloat = (funcId << 24) | (2 << 16) | (2 << 8) | 2;
				VmHandlers.handleVecMathTernary(state, ctx,
						new VmHandlers.Instruction(OP_VEC_MATH_TERNARY, (byte) 0, (short) 19, payloadFloat));
			}

			// Extract validity and coalesce
			VmHandlers.handleExtractValidity(state, ctx,
					new VmHandlers.Instruction((byte) 0x3C, (byte) 0, (short) 20, 1));
			VmHandlers.handleCoalesce(state, ctx,
					new VmHandlers.Instruction((byte) 0x3B, (byte) 0, (short) 21, (2 << 16) | 2));

			// Set ops with empty vs non-empty
			int bsHandle1 = ctx.acquireBitset();
			ctx.getBitset(bsHandle1).set(1);
			int bsHandle2 = ctx.acquireBitset();
			ctx.getBitset(bsHandle2).set(2);
			VmHandlers.setRegister(state, 22, bsHandle1, TYPE_BITSET_HANDLE);
			VmHandlers.setRegister(state, 23, bsHandle2, TYPE_BITSET_HANDLE);

			VmHandlers.handleSetIntersect(state, ctx,
					new VmHandlers.Instruction(OP_SET_INTERSECT, (byte) 0, (short) 24, (23 << 16) | 22));
			VmHandlers.handleSetDifference(state, ctx,
					new VmHandlers.Instruction(OP_SET_DIFFERENCE, (byte) 0, (short) 25, (23 << 16) | 22));
		}
	}
}
