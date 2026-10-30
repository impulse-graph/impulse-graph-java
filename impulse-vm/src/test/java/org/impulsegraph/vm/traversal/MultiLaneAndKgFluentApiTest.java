package org.impulsegraph.vm.traversal;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.KeyPair;
import org.impulsegraph.api.traversal.MultiLaneResult;
import org.impulsegraph.api.traversal.NodePair;
import org.impulsegraph.api.traversal.ScoredKey;
import org.impulsegraph.api.traversal.ScoredNode;
import org.impulsegraph.api.traversal.SteinerSubgraph;
import org.impulsegraph.api.traversal.Traversal;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Issue #25 Item 5: Multi-Lane, Knowledge Graph & GraphRAG Fluent API Tests")
public class MultiLaneAndKgFluentApiTest {

	private static ImpulseGraphSnapshot createKgSnapshot(Arena arena) {
		// 5 nodes:
		// knows: 0 -> {2}, 1 -> {2, 3}, 2 -> {4}, 3 -> {4}, 4 -> {}
		// cites: 0 -> {3}, 1 -> {3}, 2 -> {}, 3 -> {}, 4 -> {}
		int[] knowsOffsets = {0, 1, 3, 4, 5, 5};
		int[] knowsTargets = {2, 2, 3, 4, 4};
		int[] citesOffsets = {0, 1, 2, 2, 2, 2};
		int[] citesTargets = {3, 3};

		// 2D embeddings for 5 nodes:
		// 0: [1.0, 0.0], 1: [0.0, 1.0], 2: [0.9, 0.1], 3: [0.6, 0.8], 4: [0.5, 0.5]
		float[] embs = {1.0f, 0.0f, 0.0f, 1.0f, 0.9f, 0.1f, 0.6f, 0.8f, 0.5f, 0.5f};
		MemorySegment embSeg = arena.allocate(embs.length * 4L);
		for (int i = 0; i < embs.length; i++) {
			embSeg.set(ValueLayout.JAVA_FLOAT_UNALIGNED, (long) i * 4L, embs[i]);
		}

		RelationSnapshot knowsRel = new RelationSnapshot(arena, 5, 5, knowsOffsets, knowsTargets);
		// CSC for knows: 0 <- {}, 1 <- {}, 2 <- {0, 1}, 3 <- {1}, 4 <- {2, 3}
		int[] knowsCscOff = {0, 0, 0, 2, 3, 5};
		int[] knowsCscTgt = {0, 1, 1, 2, 3};
		MemorySegment cscOffSeg = arena.allocate(knowsCscOff.length * 4L);
		for (int i = 0; i < knowsCscOff.length; i++) {
			cscOffSeg.setAtIndex(ValueLayout.JAVA_INT, i, knowsCscOff[i]);
		}
		MemorySegment cscTgtSeg = arena.allocate(knowsCscTgt.length * 4L);
		for (int i = 0; i < knowsCscTgt.length; i++) {
			cscTgtSeg.setAtIndex(ValueLayout.JAVA_INT, i, knowsCscTgt[i]);
		}
		knowsRel.setCscSegments(cscOffSeg, cscTgtSeg);
		knowsRel.getAttributeSegments().add(embSeg);
		knowsRel.setAttributeNames(List.of("emb"));
		RelationSnapshot citesRel = new RelationSnapshot(arena, 5, 2, citesOffsets, citesTargets);

		Map<String, RelationSnapshot> relMap = new LinkedHashMap<>();
		relMap.put("knows", knowsRel);
		relMap.put("cites", citesRel);

		Map<String, String> meta = new HashMap<>();
		meta.put("key.Entity.0", "drug_A");
		meta.put("key.Entity.1", "drug_B");
		meta.put("key.Entity.2", "gene_X");
		meta.put("key.Entity.3", "gene_Y");
		meta.put("key.Entity.4", "disease_Z");

		return new GraphSnapshot(arena, relMap, meta);
	}

	@Test
	@DisplayName("DomainView.multiLane().fromSeeds / fromKeySeeds executes 64-lane traversal, quorum filtering, and Steiner subgraph extraction")
	public void testMultiLaneSeedsQuorumAndSteinerSubgraph() {
		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createKgSnapshot(arena);
			DomainView entity = new DefaultDomainView(snap, "Entity", 0, 5);

			// Seed from drug_A (0) and drug_B (1), walk 1 hop along "knows":
			// 0 reaches {2} (lane 0), 1 reaches {2, 3} (lane 1) -> node 2 has mask 0b11
			// (quorum 2), node 3 has mask 0b10 (quorum 1)
			MultiLaneResult res = entity.multiLane().fromKeySeeds("drug_A", "drug_B").out("knows").collect();
			assertThat(res.laneCount()).isEqualTo(2);
			assertThat(res.laneMaskFor(2)).isEqualTo(0b11L);
			assertThat(res.quorumFor(2)).isEqualTo(2);
			assertThat(res.laneMaskFor(3)).isEqualTo(0b10L);
			assertThat(res.quorumFor(3)).isEqualTo(1);
			assertThat(res.nodesWithQuorum(2)).containsExactly(2L);
			assertThat(res.keysWithQuorum(2)).extracting(ScoredKey::key).containsExactly("gene_X");
			assertThat(res.allReachedLanesMask()).isEqualTo(0b11L);
			assertThat(res.unreachedLanesMask()).isEqualTo(0L);
			assertThat(res.universalBridgeLanesMask()).isEqualTo(0b10L);

			// Steiner subgraph extraction with minConceptQuorum = 2
			SteinerSubgraph steiner = entity.multiLane().fromKeySeeds("drug_A", "drug_B").out("knows")
					.toSteinerSubgraph(2);
			assertThat(steiner.bridgeNodes()).containsExactly(2L);
			assertThat(steiner.allActiveNodes()).contains(0L, 1L, 2L, 3L);
			assertThat(steiner.edges()).isNotEmpty();
			assertThat(steiner.triples()).extracting(SteinerSubgraph.SteinerTripleKey::targetKey).contains("gene_X");

			// Set cover & topKWithQuorum on MultiLaneResult
			assertThat(res.findGreedySetCover(2)).containsExactly(2L);
			assertThat(res.findGreedyKeySetCover(2)).containsExactly("gene_X");
			List<ScoredNode> topScored = res.topKWithQuorum(new float[]{1.0f, 0.0f}, 1, 2);
			assertThat(topScored).isNotEmpty();
			assertThat(topScored.get(0).nodeId()).isEqualTo(2L);
			assertThat(topScored.get(0).quorum()).isEqualTo(2);
			List<ScoredKey> topKeyScored = res.topKKeys(new float[]{1.0f, 0.0f}, 2);
			assertThat(topKeyScored.get(0).key()).isEqualTo("gene_X");
		}
	}

	@Test
	@DisplayName("DomainView.multiLane().fromKeyGroups and findKeySetCoverPairs find exact 2-node covers")
	public void testMultiLaneGroupsAndPairSetCover() {
		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createKgSnapshot(arena);
			DomainView entity = new DefaultDomainView(snap, "Entity", 0, 5);

			// Group 0: drug_A (0), Group 1: drug_B (1)
			Map<String, List<String>> groups = new LinkedHashMap<>();
			groups.put("groupA", List.of("drug_A"));
			groups.put("groupB", List.of("drug_B"));

			MultiLaneResult res = entity.multiLane().fromKeyGroups(groups).collect();
			assertThat(res.laneMaskFor(0)).isEqualTo(0b01L);
			assertThat(res.laneMaskFor(1)).isEqualTo(0b10L);

			// Exact 2-node pair cover over {0, 1} covering both groups
			List<NodePair> pairs = res.findSetCoverPairs();
			assertThat(pairs).containsExactly(new NodePair(0L, 1L));
			List<KeyPair> keyPairs = res.findKeySetCoverPairs();
			assertThat(keyPairs).containsExactly(new KeyPair("drug_A", "drug_B"));
		}
	}

	@Test
	@DisplayName("DomainView.vectorSearch and Traversal multi-relation / undirected hops + rerank().topKKeys()")
	public void testVectorSearchAndMultiRelationTraversalWithRerank() {
		try (Arena arena = Arena.ofConfined()) {
			ImpulseGraphSnapshot snap = createKgSnapshot(arena);
			DomainView entity = new DefaultDomainView(snap, "Entity", 0, 5);

			// 1. Vector ANN search seed -> top-2 closest to [1.0, 0.0] -> nodes 0 and 2
			List<String> annKeys = entity.vectorSearch("emb", new float[]{1.0f, 0.0f}, 2).toKeyList();
			assertThat(annKeys).containsExactlyInAnyOrder("drug_A", "gene_X");

			// 2. Multi-relation .out("knows", "cites") from node 0 -> reaches 2 (via knows)
			// and 3 (via cites)
			List<Long> multiRelTargets = entity.from(0L).out("knows", "cites").toList();
			assertThat(multiRelTargets).containsExactly(2L, 3L);

			// 3. Undirected .both("knows") from node 2 -> reaches 0, 1 (incoming) and 4
			// (outgoing)
			List<Long> bothTargets = entity.from(2L).both("knows").toList();
			assertThat(bothTargets).containsExactly(0L, 1L, 4L);

			// 4. Rerank reached nodes {2, 3} with query [1.0, 0.0] -> node 2 (0.9) > node 3
			// (0.6)
			Traversal<ImpulseBitSet> reranked = entity.from(0L).out("knows", "cites").rerank(new float[]{1.0f, 0.0f});
			List<ScoredNode> topNodes = reranked.topK(2);
			assertThat(topNodes).hasSize(2);
			assertThat(topNodes.get(0).nodeId()).isEqualTo(2L);
			assertThat(topNodes.get(0).score()).isCloseTo(0.9f, within(1e-5f));
			assertThat(topNodes.get(1).nodeId()).isEqualTo(3L);
			assertThat(topNodes.get(1).score()).isCloseTo(0.6f, within(1e-5f));

			List<ScoredKey> topKeys = reranked.topKKeys(1);
			assertThat(topKeys).hasSize(1);
			assertThat(topKeys.get(0).key()).isEqualTo("gene_X");
		}
	}
}
