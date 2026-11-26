package org.impulsegraph.vm;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CompiledQuery Blue/Green Rebinding Tests (v0.11)")
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
			compiled.rebind(snap);
			assertThat(compiled.currentSnapshot()).isSameAs(snap);
		}
	}

	@Test
	@DisplayName("Rebind to new snapshot routes query to new graph topology and permuted relation IDs")
	public void testRebindToNewSnapshotTopologyAndPermutedRelationIds() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snapshotA = createSnapshot(arena, "knows", 0, 10);

			// Snapshot B puts "other" at relId 0 and "knows" at relId 1 (0 -> 20)
			int[] offOther = {0, 1, 1, 1, 1, 1};
			int[] tgtOther = {4};
			RelationSnapshot relOther = new RelationSnapshot(arena, 5, 1, offOther, tgtOther);
			int[] offKnows = new int[22];
			for (int i = 1; i <= 21; i++)
				offKnows[i] = 1;
			RelationSnapshot relKnows = new RelationSnapshot(arena, 21, 1, offKnows, new int[]{20});
			Map<String, RelationSnapshot> mapB = new LinkedHashMap<>();
			mapB.put("other", relOther);
			mapB.put("knows", relKnows);
			ImpulseGraphSnapshot snapshotB = new GraphSnapshot(arena, mapB);

			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("knows").collect(ReturnType.ROARING_BITSET);

			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapshotA, arena);

			ImpulseBitSet resA = (ImpulseBitSet) compiled.execute(snapshotA, 0, arena);
			assertThat(resA.get(10)).isTrue();
			assertThat(resA.get(20)).isFalse();

			compiled.rebind(snapshotB);
			assertThat(compiled.currentSnapshot()).isSameAs(snapshotB);

			ImpulseBitSet resB = (ImpulseBitSet) compiled.execute(snapshotB, 0, arena);
			assertThat(resB.get(20)).isTrue();
			assertThat(resB.get(10)).isFalse();
		}
	}

	@Test
	@DisplayName("Rebind rejects null snapshot")
	public void testRebindRejectsNull() {
		try (Arena arena = Arena.ofShared()) {
			ImpulseGraphSnapshot snap = createSnapshot(arena, "knows", 0, 5);
			ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
					.input("USER", ArgType.SINGLE_NODE).walkEdge("knows").collect(ReturnType.ROARING_BITSET);
			CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snap, arena);
			assertThatThrownBy(() -> compiled.rebind(null)).isInstanceOf(NullPointerException.class);
		}
	}
}
