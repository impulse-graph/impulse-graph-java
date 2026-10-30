package org.impulsegraph.compiler.cypher;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Issue #25 Item 4: Cypher Multi-Seed IN, Unbounded *, Undirected/Union, Quorum & Vector Lowering Tests")
public class CypherMultiLaneAndVectorCompilerTest {

	private static ImpulseGraphSnapshot createCypherSnapshot(Arena arena) {
		// 4 nodes:
		// KNOWS: 0 -> {2}, 1 -> {2, 3}, 2 -> {3}, 3 -> {}
		// LIKES: 0 -> {3}, 1 -> {}, 2 -> {}, 3 -> {}
		int[] kOff = {0, 1, 3, 4, 4};
		int[] kTgt = {2, 2, 3, 3};
		int[] lOff = {0, 1, 1, 1, 1};
		int[] lTgt = {3};
		float[] embs = {1.0f, 0.0f, 0.0f, 1.0f, 0.9f, 0.1f, 0.5f, 0.5f};
		MemorySegment embSeg = arena.allocate(embs.length * 4L);
		for (int i = 0; i < embs.length; i++) {
			embSeg.set(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) i * 4L, embs[i]);
		}
		RelationSnapshot kRel = new RelationSnapshot(arena, 4, 4, kOff, kTgt);
		// CSC for KNOWS: 0 <- {}, 1 <- {}, 2 <- {0, 1}, 3 <- {1, 2}
		int[] kCscOff = {0, 0, 0, 2, 4};
		int[] kCscTgt = {0, 1, 1, 2};
		MemorySegment kCscOffSeg = arena.allocate(kCscOff.length * 4L);
		for (int i = 0; i < kCscOff.length; i++) {
			kCscOffSeg.setAtIndex(ValueLayout.JAVA_INT, i, kCscOff[i]);
		}
		MemorySegment kCscTgtSeg = arena.allocate(kCscTgt.length * 4L);
		for (int i = 0; i < kCscTgt.length; i++) {
			kCscTgtSeg.setAtIndex(ValueLayout.JAVA_INT, i, kCscTgt[i]);
		}
		kRel.setCscSegments(kCscOffSeg, kCscTgtSeg);
		kRel.getAttributeSegments().add(embSeg);
		kRel.setAttributeNames(List.of("emb"));
		RelationSnapshot lRel = new RelationSnapshot(arena, 4, 1, lOff, lTgt);
		Map<String, RelationSnapshot> rels = new LinkedHashMap<>();
		rels.put("KNOWS", kRel);
		rels.put("LIKES", lRel);
		return new GraphSnapshot(arena, rels, Map.of());
	}

	@Test
	@DisplayName("Cypher WHERE a.id IN [0, 1] AND quorum >= 2 lowers to 0xC0 + 0xC3 + 0xC6 and executes on VM")
	public void testCypherMultiSeedInAndQuorum() {
		String q = "MATCH (a:User)-[:KNOWS]->(b:User) WHERE a.id IN [0, 1] AND quorum >= 2 RETURN b";
		CypherCompiler.CompilationResult comp = CypherCompiler.compile(q);
		List<ImpScmNode> steps = comp.ast().steps();
		assertThat(steps.get(0)).isInstanceOf(ScmLaneInitSeeds.class);
		assertThat(steps.get(1)).isInstanceOf(ScmLaneWalk.class);
		assertThat(steps.get(2)).isInstanceOf(ScmLaneFilterQuorum.class);

		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createCypherSnapshot(arena);
			CompiledQuery cq = CypherCompiler.compileToExecutable(q, snap, arena);
			Object res = cq.execute(snap, 0L, arena);
			assertThat(res).isInstanceOf(ImpulseBitSet.class);
			ImpulseBitSet bs = (ImpulseBitSet) res;
			// Both 0 and 1 reach node 2 -> quorum >= 2 selects only node 2
			assertThat(bs.get(2)).isTrue();
			assertThat(bs.get(3)).isFalse();
		}
	}

	@Test
	@DisplayName("Cypher unbounded Kleene star [:KNOWS*], undirected -[:KNOWS]-, and union -[:KNOWS|LIKES]-> lower and execute")
	public void testCypherKleeneStarUndirectedAndUnion() {
		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createCypherSnapshot(arena);

			// 1. Unbounded Kleene star [:KNOWS*] from seed 0 -> reaches 2 and 3
			String qStar = "MATCH (a:User)-[:KNOWS*]->(b:User) WHERE a.id = 0 RETURN b";
			CypherCompiler.CompilationResult compStar = CypherCompiler.compile(qStar);
			assertThat(compStar.ast().steps().get(0)).isInstanceOf(ScmFixpoint.class);
			CompiledQuery cqStar = CypherCompiler.compileToExecutable(qStar, snap, arena);
			ImpulseBitSet bsStar = (ImpulseBitSet) cqStar.execute(snap, 0L, arena);
			assertThat(bsStar.get(2)).isTrue();
			assertThat(bsStar.get(3)).isTrue();

			// 2. Union relation -[:KNOWS|LIKES]-> from seed 0 -> reaches 2 (via KNOWS) and
			// 3 (via LIKES)
			String qUnion = "MATCH (a:User)-[:KNOWS|LIKES]->(b:User) WHERE a.id = 0 RETURN b";
			CypherCompiler.CompilationResult compUnion = CypherCompiler.compile(qUnion);
			assertThat(compUnion.ast().steps().get(0)).isInstanceOf(ScmSetOp.class);
			CompiledQuery cqUnion = CypherCompiler.compileToExecutable(qUnion, snap, arena);
			ImpulseBitSet bsUnion = (ImpulseBitSet) cqUnion.execute(snap, 0L, arena);
			assertThat(bsUnion.get(2)).isTrue();
			assertThat(bsUnion.get(3)).isTrue();

			// 3. Undirected -[:KNOWS]- from seed 2 -> reaches 0, 1 (incoming) and 3
			// (outgoing)
			String qUndir = "MATCH (a:User)-[:KNOWS]-(b:User) WHERE a.id = 2 RETURN b";
			CompiledQuery cqUndir = CypherCompiler.compileToExecutable(qUndir, snap, arena);
			ImpulseBitSet bsUndir = (ImpulseBitSet) cqUndir.execute(snap, 2L, arena);
			assertThat(bsUndir.get(0)).isTrue();
			assertThat(bsUndir.get(1)).isTrue();
			assertThat(bsUndir.get(3)).isTrue();
		}
	}

	@Test
	@DisplayName("Cypher WHERE vector_ann(...) and ORDER BY vector_dot(...) lower to 0xCA and 0xCB")
	public void testCypherVectorAnnAndOrderByVectorDot() {
		String q = "MATCH (a:User)-[:KNOWS]->(b:User) WHERE vector_ann(a.emb, [1.0, 0.0], 2) AND quorum >= 1 RETURN b ORDER BY vector_dot(b.emb, [1.0, 0.0]) DESC";
		CypherCompiler.CompilationResult comp = CypherCompiler.compile(q);
		List<ImpScmNode> steps = comp.ast().steps();
		assertThat(steps.get(0)).isInstanceOf(ScmVectorAnnSearch.class);
		ScmVectorAnnSearch ann = (ScmVectorAnnSearch) steps.get(0);
		assertThat(ann.fusedLaneFrontier()).isTrue();
		assertThat(steps).anyMatch(s -> s instanceof ScmLaneWalk);
		assertThat(steps).anyMatch(s -> s instanceof ScmLaneFilterQuorum);
		assertThat(steps.get(steps.size() - 1)).isInstanceOf(ScmVectorRerankDot.class);

		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createCypherSnapshot(arena);
			CompiledQuery cq = CypherCompiler.compileToExecutable(q, snap, arena);
			Object res = cq.execute(snap, 0L, arena);
			assertThat(res).isInstanceOf(float[].class);
			float[] scores = (float[]) res;
			assertThat(scores).isNotEmpty();
			assertThat(scores[0]).isCloseTo(0.9f, within(1e-5f));
		}
	}
}
