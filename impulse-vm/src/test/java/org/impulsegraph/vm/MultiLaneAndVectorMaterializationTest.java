package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.compiler.ast.parser.ImpScmParser;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.statement.ImpulseStatementImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.impulsegraph.vm.VmRegisterType.*;
import static org.impulsegraph.vm.VmStateLayout.*;

@DisplayName("Issue #25 Item 1: Multi-Lane & Vector Result Materialization Tests")
public class MultiLaneAndVectorMaterializationTest {

	private static ImpulseGraphSnapshot createSnapshotWithEmbeddings(Arena arena) {
		// 4 nodes: 0 -> {1, 2}, 1 -> {2, 3}, 2 -> {3}, 3 -> {}
		int[] offsets = {0, 2, 4, 5, 5};
		int[] targets = {1, 2, 2, 3, 3};
		// 2D embeddings per node (4 * 2 = 8 floats):
		// Node 0: [1.0, 0.0]
		// Node 1: [0.8, 0.6]
		// Node 2: [0.0, 1.0]
		// Node 3: [-1.0, -1.0]
		float[] embs = {1.0f, 0.0f, 0.8f, 0.6f, 0.0f, 1.0f, -1.0f, -1.0f};
		MemorySegment embSeg = arena.allocate(embs.length * 4L);
		for (int i = 0; i < embs.length; i++) {
			embSeg.set(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) i * 4L, embs[i]);
		}
		RelationSnapshot rel = new RelationSnapshot(arena, 4, 5, offsets, targets);
		rel.getAttributeSegments().add(embSeg);
		rel.setAttributeNames(List.of("emb"));
		Map<String, RelationSnapshot> relMap = new HashMap<>();
		relMap.put("knows", rel);
		Map<String, String> meta = Map.of("key.User.0", "alice", "key.User.1", "bob", "key.User.2", "carol",
				"key.User.3", "dave");
		return new GraphSnapshot(arena, relMap, meta);
	}

	@Test
	@DisplayName("0xCA OP_VECTOR_ANN_SEARCH supports flags=0 (BitSet) and flags=1 (fused T_LANE_FRONTIER)")
	public void testVectorAnnSearchBitSetAndFusedLaneFrontier() {
		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createSnapshotWithEmbeddings(arena);

			// 1. flags=0 -> T_BITSET_HANDLE with top-2 closest to [1.0, 0.0] -> nodes 0 and
			// 1
			ScmProgram pBitset = new ScmProgram(List
					.of(ScmVectorAnnSearch.fromInlineVector(List.of(1.0f, 0.0f), 2, 0, false), ScmCollect.bitset()));
			CompiledQuery cqBs = ImpOpsBytecodeEmitter.compileToExecutable(pBitset, snap, arena);
			Object resBs = cqBs.execute(snap, 0L, arena);
			assertThat(resBs).isInstanceOf(ImpulseBitSet.class);
			ImpulseBitSet bs = (ImpulseBitSet) resBs;
			assertThat(bs.get(0)).isTrue();
			assertThat(bs.get(1)).isTrue();
			assertThat(bs.get(2)).isFalse();

			// 2. flags=1 -> fused T_LANE_FRONTIER where lane 0 = node 0 (closest), lane 1 =
			// node 1 (2nd closest)
			ScmProgram pFused = new ScmProgram(
					List.of(ScmVectorAnnSearch.fromInlineVector(List.of(1.0f, 0.0f), 2, 0, true)));
			try (ImpulseStatement stmt = ImpulseStatementImpl.fromAst(snap, pFused)) {
				long[] laneMasks = stmt.executeLaneFrontier();
				assertThat(laneMasks.length).isGreaterThanOrEqualTo(4);
				assertThat(laneMasks[0]).isEqualTo(1L << 0);
				assertThat(laneMasks[1]).isEqualTo(1L << 1);
				assertThat(laneMasks[2]).isEqualTo(0L);
			}
		}
	}

	@Test
	@DisplayName("0xCB OP_VECTOR_RERANK_DOT evaluates Dot, negative L2, Cosine, and Hamming metrics over BitSet and LaneFrontier")
	public void testVectorRerankDotAllMetricsAndFrontierTypes() {
		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createSnapshotWithEmbeddings(arena);

			// Rerank over multi-lane frontier seeded with [0, 1, 2, 3] against query [1.0,
			// 0.0]
			// Metric 0: Dot -> [1.0, 0.8, 0.0, -1.0]
			ScmProgram pDot = new ScmProgram(List.of(ScmLaneInitSeeds.of(0, 1, 2, 3),
					ScmVectorRerankDot.fromInlineVector(List.of(1.0f, 0.0f), 0, 0)));
			try (ImpulseStatement stmt = ImpulseStatementImpl.fromAst(snap, pDot)) {
				float[] scores = stmt.executeFloatVector();
				assertThat(scores).hasSize(4);
				assertThat(scores[0]).isCloseTo(1.0f, within(1e-5f));
				assertThat(scores[1]).isCloseTo(0.8f, within(1e-5f));
				assertThat(scores[2]).isCloseTo(0.0f, within(1e-5f));
				assertThat(scores[3]).isCloseTo(-1.0f, within(1e-5f));
			}

			// Metric 1: Negative squared L2 -> node 0: 0.0, node 1: -(0.04+0.36)=-0.4, node
			// 2: -2.0, node 3: -(4+1)=-5.0
			ScmProgram pL2 = new ScmProgram(List.of(ScmLaneInitSeeds.of(0, 1, 2, 3),
					ScmVectorRerankDot.fromInlineVector(List.of(1.0f, 0.0f), 0, 1)));
			try (ImpulseStatement stmt = ImpulseStatementImpl.fromAst(snap, pL2)) {
				float[] scores = stmt.executeFloatVector();
				assertThat(scores[0]).isCloseTo(0.0f, within(1e-5f));
				assertThat(scores[1]).isCloseTo(-0.4f, within(1e-5f));
				assertThat(scores[2]).isCloseTo(-2.0f, within(1e-5f));
				assertThat(scores[3]).isCloseTo(-5.0f, within(1e-5f));
			}

			// Metric 2: Cosine over BitSet frontier (from ANN search)
			ScmProgram pCos = new ScmProgram(
					List.of(ScmVectorAnnSearch.fromInlineVector(List.of(1.0f, 0.0f), 3, 0, false),
							ScmVectorRerankDot.fromInlineVector(List.of(1.0f, 0.0f), 0, 2)));
			try (ImpulseStatement stmt = ImpulseStatementImpl.fromAst(snap, pCos)) {
				float[] scores = stmt.executeFloatVector();
				assertThat(scores).hasSize(3);
				assertThat(scores[0]).isCloseTo(1.0f, within(1e-5f));
				assertThat(scores[1]).isCloseTo(0.8f, within(1e-5f));
				assertThat(scores[2]).isCloseTo(0.0f, within(1e-5f));
			}

			// Metric 3: Negative Hamming (sign-bit mismatch count)
			ScmProgram pHam = new ScmProgram(
					List.of(ScmLaneInitSeeds.of(0, 3), ScmVectorRerankDot.fromInlineVector(List.of(1.0f, 0.0f), 0, 3)));
			try (ImpulseStatement stmt = ImpulseStatementImpl.fromAst(snap, pHam)) {
				float[] scores = stmt.executeFloatVector();
				assertThat(scores).hasSize(2);
				assertThat(scores[0]).isCloseTo(0.0f, within(1e-5f));
				assertThat(scores[1]).isCloseTo(-2.0f, within(1e-5f));
			}
		}
	}

	@Test
	@DisplayName("0x90..0x93 Materialization handlers extract active nodes from T_LANE_FRONTIER, T_NODE_VECTOR, T_UINT64_VECTOR, and T_BITSET_HANDLE")
	public void testMaterializationOpcodesFromMultiLaneAndVectors() {
		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createSnapshotWithEmbeddings(arena);

			// 1. 0x90 OP_COLLECT_BITSET from T_LANE_FRONTIER
			ScmProgram p90 = new ScmProgram(
					List.of(ScmLaneInitSeeds.of(0, 2), ScmLaneWalk.forward("knows", false), ScmCollect.bitset()));
			try (ImpulseStatement stmt = ImpulseStatementImpl.fromAst(snap, p90)) {
				ImpulseBitSet bs = stmt.executeBitSet();
				assertThat(bs.get(1)).isTrue();
				assertThat(bs.get(2)).isTrue();
				assertThat(bs.get(3)).isTrue();
				assertThat(stmt.executeNodeVector()).containsExactly(1L, 2L, 3L);
			}

			// 2. 0x91 OP_COLLECT_ARRAY from T_LANE_FRONTIER
			ScmProgram p91 = new ScmProgram(
					List.of(ScmLaneInitSeeds.of(0, 2), ScmLaneWalk.forward("knows", false), ScmCollect.vector()));
			try (ImpulseStatement stmt = ImpulseStatementImpl.fromAst(snap, p91)) {
				assertThat(stmt.executeNodeVector()).containsExactly(1L, 2L, 3L);
			}

			// 3. 0x92 OP_MAP_DENSE_TO_KEYS and 0x93 OP_COLLECT_VALUE_MAP via direct VM
			// execution
			MemorySegment state = arena.allocate(VM_STATE_LAYOUT);
			VmQueryContext ctx = new VmQueryContext(snap, arena);
			ctx.setStringPool(List.of("User"));
			int hLf = ctx.acquireLaneFrontier(VmLaneFrontier.LaneWidth.WIDTH_64, 2, 0b11L, 4);
			VmLaneFrontier lf = ctx.getLaneFrontier(hLf);
			lf.lanes()[1] = 0b01L;
			lf.lanes()[3] = 0b10L;
			VmHandlers.setRegister(state, 1, hLf, TYPE_LANE_FRONTIER);

			// 0x92 OP_MAP_DENSE_TO_KEYS on R1 (T_LANE_FRONTIER) -> R2 (T_STRING_VECTOR)
			VmHandlers.handleMapDenseToKeys(state, ctx,
					new VmHandlers.Instruction((byte) 0x92, (byte) 0, (short) 2, 1));
			assertThat(VmHandlers.getRegisterType(state, 2)).isEqualTo(TYPE_STRING_VECTOR);
			@SuppressWarnings("unchecked")
			List<String> keys = (List<String>) ctx.getFinalResult();
			assertThat(keys).containsExactly("bob", "dave");

			// 0x93 OP_COLLECT_VALUE_MAP on R1 (nodes) + R3 (float scores) -> R4
			// (T_VALUE_MAP)
			int hFv = ctx.acquireFloatVector(2);
			float[] fv = ctx.getFloatVector(hFv);
			fv[0] = 0.75f;
			fv[1] = 0.25f;
			VmHandlers.setRegister(state, 3, hFv, TYPE_FLOAT_VECTOR);
			int payload93 = (1 & 0xFF) | ((3 & 0xFF) << 8);
			VmHandlers.handleCollectValueMap(state, ctx,
					new VmHandlers.Instruction((byte) 0x93, (byte) 0, (short) 4, payload93));
			assertThat(VmHandlers.getRegisterType(state, 4)).isEqualTo(TYPE_VALUE_MAP);
			@SuppressWarnings("unchecked")
			Map<Integer, Object> vmap = (Map<Integer, Object>) ctx.getFinalResult();
			assertThat(vmap).containsEntry(1, 0.75f).containsEntry(3, 0.25f);
		}
	}

	@Test
	@DisplayName("ImpScmParser parses and roundtrips all 13 new multi-lane, vector, fixpoint, and set-op AST nodes")
	public void testImpScmParserRoundtripForNewAstNodes() {
		String scm = """
				(program
				  (lane-init-seeds (0 1 2) 4)
				  (lane-init-groups ((0 1) (2 3)) 4)
				  (lane-set-edge-mask "knows" (0 1))
				  (lane-csr-walk "knows" #f #t)
				  (lane-csc-walk "knows")
				  (lane-prune-mask 0 0 0 (3))
				  (lane-filter-quorum 2)
				  (lane-reduce-and 0)
				  (lane-reduce-attr 0 1)
				  (lane-set-cover 2 3 #t)
				  (vector-ann-search (1.0 0.0) 2 0 #t)
				  (vector-rerank-dot (1.0 0.0) 0 2)
				  (fixpoint "knows" 10)
				  (set-union (csr-walk "knows"))
				  (collect-keys))
				""";
		ImpScmNode parsed = ImpScmParser.parse(scm);
		assertThat(parsed).isInstanceOf(ScmProgram.class);
		ScmProgram prog = (ScmProgram) parsed;
		assertThat(prog.steps()).hasSize(15);
		assertThat(prog.steps().get(0)).isInstanceOf(ScmLaneInitSeeds.class);
		assertThat(prog.steps().get(1)).isInstanceOf(ScmLaneInitGroups.class);
		assertThat(prog.steps().get(2)).isInstanceOf(ScmLaneSetEdgeMask.class);
		assertThat(prog.steps().get(3)).isInstanceOf(ScmLaneWalk.class);
		assertThat(prog.steps().get(5)).isInstanceOf(ScmLanePruneMask.class);
		assertThat(prog.steps().get(6)).isInstanceOf(ScmLaneFilterQuorum.class);
		assertThat(prog.steps().get(7)).isInstanceOf(ScmLaneReduceAnd.class);
		assertThat(prog.steps().get(8)).isInstanceOf(ScmLaneReduceAttr.class);
		assertThat(prog.steps().get(9)).isInstanceOf(ScmLaneSetCover.class);
		assertThat(prog.steps().get(10)).isInstanceOf(ScmVectorAnnSearch.class);
		assertThat(prog.steps().get(11)).isInstanceOf(ScmVectorRerankDot.class);
		assertThat(prog.steps().get(12)).isInstanceOf(ScmFixpoint.class);
		assertThat(prog.steps().get(13)).isInstanceOf(ScmSetOp.class);
		assertThat(prog.steps().get(14)).isEqualTo(ScmCollect.keys());

		// Roundtrip toScmString -> parse
		ImpScmNode reparsed = ImpScmParser.parse(prog.toScmString());
		assertThat(reparsed).isEqualTo(prog);
	}
}
