package org.impulsegraph.compiler.impk;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.*;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.CompiledQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Issue #25 Item 3: ImpK Vector ANN/Rerank & 64-Lane SpMM Lowering Tests")
public class ImpKVectorAndMultiLaneCompilerTest {

	private static ImpulseGraphSnapshot createImpKSnapshot(Arena arena) {
		int[] offsets = {0, 2, 3, 3};
		int[] targets = {1, 2, 2};
		float[] embs = {1.0f, 0.0f, 0.8f, 0.6f, 0.0f, 1.0f};
		MemorySegment embSeg = arena.allocate(embs.length * 4L);
		for (int i = 0; i < embs.length; i++) {
			embSeg.set(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) i * 4L, embs[i]);
		}
		RelationSnapshot rel = new RelationSnapshot(arena, 3, 3, offsets, targets);
		rel.getAttributeSegments().add(embSeg);
		rel.setAttributeNames(List.of("emb"));
		return new GraphSnapshot(arena, Map.of("A", rel), Map.of());
	}

	@Test
	@DisplayName("ImpKCompiler lowers ann_search, rerank_dot (dot, l2, cosine, hamming), and topk")
	public void testImpKVectorAnnAndRerankLowering() {
		String script = """
				cands = ann_search([1.0, 0.0], 2, 0, false)
				scores = rerank_dot(cands, [1.0, 0.0], 0, cosine)
				best = topk(scores, 1)
				""";
		List<ImpKStatement> stmts = ImpKCompiler.parse(script);
		assertThat(stmts).hasSize(3);
		assertThat(stmts.get(0).opType()).isEqualTo(ImpKOpType.VECTOR_ANN_SEARCH);
		assertThat(stmts.get(1).opType()).isEqualTo(ImpKOpType.VECTOR_RERANK_DOT);
		assertThat(stmts.get(1).intParam2()).isEqualTo(2); // cosine = 2
		assertThat(stmts.get(2).opType()).isEqualTo(ImpKOpType.TOP_K);

		ScmProgram prog = ImpKCompiler.lowerToProgram(stmts);
		assertThat(prog.steps().get(0)).isInstanceOf(ScmVectorAnnSearch.class);
		assertThat(prog.steps().get(1)).isInstanceOf(ScmVectorRerankDot.class);
		assertThat(prog.steps().get(2)).isInstanceOf(ScmReduce.class);

		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createImpKSnapshot(arena);
			CompiledQuery cq = ImpKCompiler.compileToExecutable(
					"cands = ann_search([1.0, 0.0], 2, 0, false)\nscores = rerank_dot(cands, [1.0, 0.0], 0, dot)", snap,
					arena);
			Object res = cq.execute(snap, 0L, arena);
			assertThat(res).isInstanceOf(float[].class);
			float[] scores = (float[]) res;
			assertThat(scores).hasSize(2);
			assertThat(scores[0]).isCloseTo(1.0f, within(1e-5f));
			assertThat(scores[1]).isCloseTo(0.8f, within(1e-5f));
		}
	}

	@Test
	@DisplayName("ImpKCompiler lowers 64-lane boolean SpMM primitives (lane_seeds, lane_groups, lane_edge_mask, lane_spmm, lane_quorum, lane_reduce_and, lane_reduce_attr, lane_set_cover)")
	public void testImpKMultiLaneSpMMLowering() {
		String script = """
				F0 = lane_seeds([0, 1])
				F1 = lane_edge_mask(A, [0, 0])
				F2 = lane_spmm(A, F0, true)
				F3 = lane_spmm_csc(A, F2, false)
				F4 = lane_prune(F3, 1, 1, 0)
				B = lane_quorum(F4, 2)
				M = lane_reduce_and(F4)
				S = lane_reduce_attr(F4, 0, 1)
				C = lane_set_cover(F4, 1)
				""";
		ScmProgram prog = ImpKCompiler.compile(script);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLaneInitSeeds);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLaneSetEdgeMask);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLaneWalk lw && lw.fixedPoint());
		assertThat(prog.steps())
				.anyMatch(s -> s instanceof ScmLaneWalk lw && lw.direction() == ScmWalk.Direction.REVERSE_CSC);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLanePruneMask);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLaneFilterQuorum);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLaneReduceAnd);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLaneReduceAttr);
		assertThat(prog.steps()).anyMatch(s -> s instanceof ScmLaneSetCover);

		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createImpKSnapshot(arena);
			CompiledQuery cq = ImpKCompiler.compileToExecutable(
					"F0 = lane_seeds([0, 1])\nF1 = lane_spmm(A, F0, false)\nB = lane_quorum(F1, 2)", snap, arena);
			Object res = cq.execute(snap, 0L, arena);
			assertThat(res).isInstanceOf(ImpulseBitSet.class);
			ImpulseBitSet bs = (ImpulseBitSet) res;
			// Both 0 and 1 have an edge to node 2 -> node 2 has quorum 2
			assertThat(bs.get(2)).isTrue();
			assertThat(bs.get(1)).isFalse();
		}
	}
}
