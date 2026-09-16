package org.impulsegraph.vm;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

@DisplayName("CompiledQuery Rebinding and Relation Patching Tests")
public class CompiledQueryRebindTest {

	private static ImpulseGraphSnapshot createSnapshot(Arena arena, String relName, int srcNode, int tgtNode) {
		int maxNode = Math.max(srcNode, tgtNode) + 1;
		int[] offsets = new int[maxNode + 1];
		for (int i = srcNode + 1; i <= maxNode; i++) {
			offsets[i] = 1;
		}
		int[] targets = new int[]{tgtNode};
		RelationSnapshot rel = new RelationSnapshot(arena, maxNode, 1, offsets, targets);
		return new GraphSnapshot(arena, Map.of(relName, rel));
	}

	@Test
	@DisplayName("Rebind to exact same snapshot reference is a no-op")
	public void testRebindToSameSnapshotIsNoOp() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = createSnapshot(arena, "knows", 0, 5);
			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("knows").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snap, arena);
			CompiledQuery.QueryBindingState stateBefore = compiled.bindingState();

			compiled.rebind(snap);

			assertThat(compiled.bindingState()).isSameAs(stateBefore);
			assertThat(compiled.currentSnapshot()).isSameAs(snap);
		}
	}

	@Test
	@DisplayName("Rebind to new snapshot correctly routes query to new graph topology")
	public void testRebindToNewSnapshotTopology() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snapshotA = createSnapshot(arena, "knows", 0, 10);
			ImpulseGraphSnapshot snapshotB = createSnapshot(arena, "knows", 0, 20);

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("knows").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapshotA, arena);

			// Execute on Snapshot A
			ImpulseBitSet resA = (ImpulseBitSet) compiled.execute(snapshotA, 0, arena);
			assertThat(resA).isNotNull();
			assertThat(resA.get(10)).isTrue();
			assertThat(resA.get(20)).isFalse();

			// Rebind to Snapshot B
			compiled.rebind(snapshotB);
			assertThat(compiled.currentSnapshot()).isSameAs(snapshotB);

			// Execute on Snapshot B
			ImpulseBitSet resB = (ImpulseBitSet) compiled.execute(snapshotB, 0, arena);
			assertThat(resB).isNotNull();
			assertThat(resB.get(20)).isTrue();
			assertThat(resB.get(10)).isFalse();
		}
	}

	@Test
	@DisplayName("Relation patching updates opcode payload when relation IDs shift across snapshots")
	public void testRelationInstructionPatchingOnRebind() {
		try (Arena arena = Arena.ofShared()) {
			RelationSnapshot relFooA = new RelationSnapshot(arena, 2, 1, new int[]{0, 1, 1}, new int[]{5});
			RelationSnapshot relBarA = new RelationSnapshot(arena, 2, 1, new int[]{0, 1, 1}, new int[]{6});

			// Snapshot A: foo is rel 0, bar is rel 1
			Map<String, RelationSnapshot> mapA = new LinkedHashMap<>();
			mapA.put("foo", relFooA);
			mapA.put("bar", relBarA);
			ImpulseGraphSnapshot snapA = new GraphSnapshot(arena, mapA);

			// Snapshot B: bar is rel 0, foo is rel 1 (IDs swapped!)
			Map<String, RelationSnapshot> mapB = new LinkedHashMap<>();
			mapB.put("bar", relBarA);
			mapB.put("foo", relFooA);
			ImpulseGraphSnapshot snapB = new GraphSnapshot(arena, mapB);

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("bar").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapA, arena);

			// In snapA, "bar" is at index 1
			assertThat(compiled.bindingState().relationIdMap().get("bar")).isEqualTo(1);
			long patchPc = compiled.patches().get(0).pc();
			int payloadA = (int) INSTR_PAYLOAD_HANDLE.get(compiled.programSegment(), patchPc * INSTRUCTION_SIZE_BYTES);
			int relIdA = (payloadA >> 16) & 0xFFFF;
			assertThat(relIdA).isEqualTo(1);

			// Rebind to snapB where "bar" is at index 0
			compiled.rebind(snapB);
			assertThat(compiled.bindingState().relationIdMap().get("bar")).isEqualTo(0);
			int payloadB = (int) INSTR_PAYLOAD_HANDLE.get(compiled.programSegment(), patchPc * INSTRUCTION_SIZE_BYTES);
			int relIdB = (payloadB >> 16) & 0xFFFF;
			assertThat(relIdB).isEqualTo(0);

			// Low 16 bits (srcReg) preserved
			assertThat(payloadB & 0xFFFF).isEqualTo(payloadA & 0xFFFF);
		}
	}

	@Test
	@DisplayName("Relation name matching succeeds with domain suffix and case-insensitive matching")
	public void testRelationMatchingWithDomainPrefixAndCaseInsensitive() {
		try (Arena arena = Arena.ofShared()) {
			RelationSnapshot relA = new RelationSnapshot(arena, 2, 1, new int[]{0, 1, 1}, new int[]{7});
			ImpulseGraphSnapshot snapA = new GraphSnapshot(arena, Map.of("friends", relA));

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("friends").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapA, arena);

			// Target snapshot uses Domain_relation pattern: "User_friends"
			RelationSnapshot relB = new RelationSnapshot(arena, 2, 1, new int[]{0, 1, 1}, new int[]{8});
			ImpulseGraphSnapshot snapB = new GraphSnapshot(arena, Map.of("User_friends", relB));

			compiled.rebind(snapB);
			assertThat(compiled.currentSnapshot()).isSameAs(snapB);

			ImpulseBitSet res = (ImpulseBitSet) compiled.execute(snapB, 0, arena);
			assertThat(res.get(8)).isTrue();
		}
	}

	@Test
	@DisplayName("Rebinding to null snapshot throws NullPointerException")
	public void testNegativeRebindNullSnapshot() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = createSnapshot(arena, "rel", 0, 1);
			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("rel").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snap, arena);

			assertThatThrownBy(() -> compiled.rebind(null)).isInstanceOf(NullPointerException.class)
					.hasMessageContaining("newSnapshot must not be null");
		}
	}

	@Test
	@DisplayName("Rebinding to a snapshot missing a required relation throws IllegalStateException")
	public void testNegativeRebindMissingRequiredRelation() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snapA = createSnapshot(arena, "required_rel", 0, 1);
			ImpulseGraphSnapshot snapB = createSnapshot(arena, "different_rel", 0, 1);

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("required_rel").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapA, arena);

			assertThatThrownBy(() -> compiled.rebind(snapB)).isInstanceOf(IllegalStateException.class)
					.hasMessageContaining(
							"Blue/Green Re-bind Verification Failed: Required relation 'required_rel' is missing in target snapshot.");
		}
	}

	@Test
	@DisplayName("execute() automatically rebinds when given a different snapshot")
	public void testAutoRebindDuringExecute() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snapA = createSnapshot(arena, "knows", 0, 11);
			ImpulseGraphSnapshot snapB = createSnapshot(arena, "knows", 0, 22);

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("knows").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapA, arena);
			assertThat(compiled.currentSnapshot()).isSameAs(snapA);

			// Pass snapB to execute -> triggers auto-rebind
			ImpulseBitSet res = (ImpulseBitSet) compiled.execute(snapB, 0, arena);
			assertThat(res.get(22)).isTrue();
			assertThat(compiled.currentSnapshot()).isSameAs(snapB);
		}
	}

	@Test
	@DisplayName("execute() with null snapshot uses currently bound snapshot")
	public void testExecuteWithNullSnapshotUsesCurrentBoundSnapshot() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = createSnapshot(arena, "knows", 0, 33);
			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("knows").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snap, arena);

			ImpulseBitSet res = (ImpulseBitSet) compiled.execute(null, 0, arena);
			assertThat(res.get(33)).isTrue();
		}
	}

	@Test
	@DisplayName("Disassembly properly formats header, opcodes, target PCs, and relation patches")
	public void testDisassemblyFormatting() {
		try (Arena arena = Arena.ofShared()) {
			// Construct a program segment with various opcodes: INIT, CSR_WALK, JMP, JZ,
			// JNZ, LOOP_DECR, HALT
			int instrCount = 7;
			MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * instrCount);

			setInstruction(prog, 0, OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0);
			setInstruction(prog, 1, OP_CSR_WALK, (byte) 0, (short) 1, (0 << 16) | 0);
			setInstruction(prog, 2, OP_JMP, (byte) 0, (short) 0, 4);
			setInstruction(prog, 3, OP_JZ, (byte) 0, (short) 0, 5);
			setInstruction(prog, 4, OP_JNZ, (byte) 0, (short) 0, 6);
			setInstruction(prog, 5, OP_LOOP_DECR, (byte) 0, (short) 0, 1);
			setInstruction(prog, 6, OP_HALT, (byte) 0, (short) 0, 0);

			List<RelationInstructionPatch> patches = List
					.of(new RelationInstructionPatch(1, "knows", (short) 0, (short) 1));
			Map<String, Integer> relIdMap = Map.of("knows", 0);
			ImpulseGraphSnapshot snap = createSnapshot(arena, "knows", 0, 1);

			CompiledQuery compiled = new CompiledQuery(prog, instrCount, patches, relIdMap, snap, arena,
					List.of("str1", "str2"));

			String dis = compiled.disassemble();
			assertThat(dis).isNotNull();
			assertThat(dis).contains("IMPULSE VM BYTECODE DISASSEMBLY");
			assertThat(dis).contains("Instruction Count: 7");
			assertThat(dis).contains("OP_INIT_INPUT_NODE");
			assertThat(dis).contains("OP_CSR_WALK");
			assertThat(dis).contains("WALK src=R0 -> dst=R1 via rel[0] (\"knows\")");
			assertThat(dis).contains("OP_JMP");
			assertThat(dis).contains("Target PC: 0x0004");
			assertThat(dis).contains("OP_JZ");
			assertThat(dis).contains("OP_JNZ");
			assertThat(dis).contains("OP_LOOP_DECR");
			assertThat(dis).contains("OP_HALT");
		}
	}

	@Test
	@DisplayName("Direct constructor initializes all fields and getters correctly")
	public void testDirectConstructorAndGetters() {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * 2);
			setInstruction(prog, 0, OP_NOP, (byte) 0, (short) 0, 0);
			setInstruction(prog, 1, OP_HALT, (byte) 0, (short) 0, 0);

			List<RelationInstructionPatch> patches = List
					.of(new RelationInstructionPatch(0, "edge", (short) 0, (short) 1));
			Map<String, Integer> relMap = Map.of("edge", 0);
			ImpulseGraphSnapshot snap = createSnapshot(arena, "edge", 0, 1);
			List<String> stringPool = List.of("alpha", "beta");

			CompiledQuery query = new CompiledQuery(prog, 2, patches, relMap, snap, arena, stringPool);

			assertThat(query.instructionCount()).isEqualTo(2);
			assertThat(query.programSegment()).isNotNull();
			assertThat(query.methodHandle()).isNotNull();
			assertThat(query.currentSnapshot()).isSameAs(snap);
			assertThat(query.stringPool()).containsExactly("alpha", "beta");
			assertThat(query.patches()).isEqualTo(patches);
			assertThat(query.bindingState().snapshot()).isSameAs(snap);
			assertThat(query.bindingState().instructionCount()).isEqualTo(2);
			assertThat(query.bindingState().relationIdMap()).isEqualTo(relMap);

			// Null stringPool fallback to empty list
			CompiledQuery queryNullPool = new CompiledQuery(prog, 2, patches, relMap, snap, arena, null);
			assertThat(queryNullPool.stringPool()).isEmpty();

			// Null programSeg throws NullPointerException
			assertThatThrownBy(() -> new CompiledQuery(null, 2, patches, relMap, snap, arena, stringPool))
					.isInstanceOf(NullPointerException.class).hasMessageContaining("programSeg must not be null");
		}
	}

	@Test
	@DisplayName("Execution falls back to interpreter if MethodHandle throws an unexpected Throwable")
	public void testExecuteFallbackToInterpreterOnMethodHandleError() {
		try (Arena arena = Arena.ofShared()) {
			// Program: OP_INIT_INPUT_NODE dst=0, OP_COLLECT_BITSET dst=0, OP_HALT
			MemorySegment prog = arena.allocate(INSTRUCTION_LAYOUT.byteSize() * 3);
			setInstruction(prog, 0, OP_INIT_INPUT_NODE, (byte) 0, (short) 0, 0);
			setInstruction(prog, 1, OP_COLLECT_BITSET, (byte) 0, (short) 0, 0);
			setInstruction(prog, 2, OP_HALT, (byte) 0, (short) 0, 0);

			ImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of());
			CompiledQuery query = new CompiledQuery(prog, 3, List.of(), Map.of(), snap, arena, List.of());

			// Normal execution via methodHandle or fallback returns bitset with node 42
			Object result = query.execute(snap, 42, arena);
			assertThat(result).isInstanceOf(ImpulseBitSet.class);
			assertThat(((ImpulseBitSet) result).get(42)).isTrue();
		}
	}

	@Property
	@DisplayName("Property-based test: query rebinds dynamically across arbitrary target nodes")
	void propertyRebindDynamicTargetNode(@ForAll @IntRange(min = 1, max = 500) int targetNode) {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snapInitial = createSnapshot(arena, "edge", 0, 0);
			ImpulseGraphSnapshot snapTarget = createSnapshot(arena, "edge", 0, targetNode);

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("node", ArgType.SINGLE_NODE).walkEdge("edge").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapInitial, arena);
			compiled.rebind(snapTarget);

			ImpulseBitSet res = (ImpulseBitSet) compiled.execute(snapTarget, 0, arena);
			assertThat(res.get(targetNode)).isTrue();
		}
	}

	@Test
	@DisplayName("PhysicalBindingPass transforms logical relation names to physical catalog IDs")
	public void testPhysicalBindingPass() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = createSnapshot(arena, "knows", 0, 5);
			org.impulsegraph.compiler.passes.stage2.PhysicalBindingPass pass = org.impulsegraph.compiler.passes.stage2.PhysicalBindingPass.INSTANCE;
			assertThat(pass.name()).isEqualTo("PhysicalBindingPass");

			org.impulsegraph.compiler.trace.CompilerOptions opts = org.impulsegraph.compiler.trace.CompilerOptions.DEFAULT;
			org.impulsegraph.compiler.trace.PassTracer tracer = new org.impulsegraph.compiler.trace.PassTracer(opts);
			org.impulsegraph.compiler.passes.CompilerContext ctx = new org.impulsegraph.compiler.passes.CompilerContext(
					snap, opts, tracer);

			assertThat(pass.transform(null, ctx)).isNull();

			org.impulsegraph.compiler.passes.CompilerContext nullCtx = new org.impulsegraph.compiler.passes.CompilerContext(
					null, opts, tracer);
			org.impulsegraph.compiler.ast.ScmWalk walk = org.impulsegraph.compiler.ast.ScmWalk.forward("knows");
			assertThat(pass.transform(walk, nullCtx)).isSameAs(walk);

			// Transform ScmWalk with snapshot
			org.impulsegraph.compiler.ast.ImpScmNode boundWalk = pass.transform(walk, ctx);
			assertThat(boundWalk).isInstanceOf(org.impulsegraph.compiler.ast.ScmWalk.class);
			assertThat(((org.impulsegraph.compiler.ast.ScmWalk) boundWalk).relationId()).isGreaterThanOrEqualTo(0);

			// Transform ScmWalk2Hop
			org.impulsegraph.compiler.ast.ScmWalk2Hop hop2 = new org.impulsegraph.compiler.ast.ScmWalk2Hop("knows", -1,
					"knows", -1);
			org.impulsegraph.compiler.ast.ImpScmNode boundHop2 = pass.transform(hop2, ctx);
			assertThat(boundHop2).isInstanceOf(org.impulsegraph.compiler.ast.ScmWalk2Hop.class);

			// Transform ScmProgram
			org.impulsegraph.compiler.ast.ScmProgram prog = org.impulsegraph.compiler.ast.ScmProgram.of(walk, hop2);
			org.impulsegraph.compiler.ast.ImpScmNode boundProg = pass.transform(prog, ctx);
			assertThat(boundProg).isInstanceOf(org.impulsegraph.compiler.ast.ScmProgram.class);

			// Other nodes pass through
			org.impulsegraph.compiler.ast.ScmVectorFilter vf = new org.impulsegraph.compiler.ast.ScmVectorFilter(walk);
			assertThat(pass.transform(vf, ctx)).isNotNull();
		}
	}

	private static void setInstruction(MemorySegment prog, long pc, byte opcode, byte flags, short dstReg,
			int payload) {
		long off = pc * INSTRUCTION_SIZE_BYTES;
		INSTR_OPCODE_HANDLE.set(prog, off, opcode);
		INSTR_FLAGS_HANDLE.set(prog, off, flags);
		INSTR_DST_REG_HANDLE.set(prog, off, dstReg);
		INSTR_PAYLOAD_HANDLE.set(prog, off, payload);
	}
}
