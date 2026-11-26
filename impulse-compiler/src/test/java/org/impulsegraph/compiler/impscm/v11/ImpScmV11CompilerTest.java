package org.impulsegraph.compiler.impscm.v11;

import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.impulsegraph.vm.v11.*;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ImpScmV11Compiler} against the normative
 * {@code impulse-graph-spec/test-fixtures/impscm-v11} positive E2E and negative
 * static validation fixtures.
 */
class ImpScmV11CompilerTest {

	private static Path specFixtureDir() {
		Path p = Path.of("../spec/test-fixtures/impscm-v11");
		if (Files.isDirectory(p))
			return p;
		return Path.of("/Users/jesse/impulse/impulse-graph-spec/test-fixtures/impscm-v11");
	}

	private static GraphSnapshot buildRebacSnapshot(Arena arena) {
		// Domains: 0=User (2), 1=Group (3), 2=Resource (2)
		// MEMBER_OF (rel 0, 0->1): u0 -> g0
		// GROUP_INCLUDES (rel 1, 1->1): g0 -> g1 -> g2
		// CAN_ACCESS (rel 2, 1->2): g2 -> r1
		MemorySegment mOff = allocInts(arena, 0, 1, 1);
		MemorySegment mTgt = allocInts(arena, 0);
		RelationSnapshot rMember = new RelationSnapshot(arena, 2, 1, mOff, mTgt);
		rMember.setCatalogMetadata(0, 0, 1, (byte) 1, 0L, null);

		MemorySegment gOff = allocInts(arena, 0, 1, 2, 2);
		MemorySegment gTgt = allocInts(arena, 1, 2);
		RelationSnapshot rGroup = new RelationSnapshot(arena, 3, 2, gOff, gTgt);
		rGroup.setCatalogMetadata(1, 1, 1, (byte) 1, 0L, null);

		MemorySegment aOff = allocInts(arena, 0, 0, 0, 1);
		MemorySegment aTgt = allocInts(arena, 1);
		RelationSnapshot rAccess = new RelationSnapshot(arena, 3, 1, aOff, aTgt);
		rAccess.setCatalogMetadata(2, 1, 2, (byte) 1, 0L, null);

		Map<String, BinarySnapshotLoader.LoadedDomain> doms = Map.of("User",
				new BinarySnapshotLoader.LoadedDomain(0, "User", (byte) 0, 2L), "Group",
				new BinarySnapshotLoader.LoadedDomain(1, "Group", (byte) 0, 3L), "Resource",
				new BinarySnapshotLoader.LoadedDomain(2, "Resource", (byte) 0, 2L));
		return new GraphSnapshot(arena, Map.of("MEMBER_OF", rMember, "GROUP_INCLUDES", rGroup, "CAN_ACCESS", rAccess),
				Map.of(), Map.of(), doms);
	}

	private static GraphSnapshot buildChainSnapshot(Arena arena) {
		MemorySegment off = allocInts(arena, 0, 1, 2, 3, 4, 4);
		MemorySegment tgt = allocInts(arena, 1, 2, 3, 4);
		RelationSnapshot rel = new RelationSnapshot(arena, 5, 4, off, tgt);
		rel.setCatalogMetadata(0, 0, 0, (byte) 1, 0L, null);
		Map<String, BinarySnapshotLoader.LoadedDomain> doms = Map.of("Node",
				new BinarySnapshotLoader.LoadedDomain(0, "Node", (byte) 0, 5L));
		return new GraphSnapshot(arena, Map.of("NEXT", rel), Map.of(), Map.of(), doms);
	}

	private static MemorySegment allocInts(Arena arena, int... vals) {
		MemorySegment seg = arena.allocate(ValueLayout.JAVA_INT, vals.length);
		for (int i = 0; i < vals.length; i++)
			seg.setAtIndex(ValueLayout.JAVA_INT, i, vals[i]);
		return seg;
	}

	@Test
	void testRebacFixturePositiveAndOptParity() throws Exception {
		String src = Files.readString(specFixtureDir().resolve("01_rebac_reachability.impscm"));
		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildRebacSnapshot(arena);
			V11SnapshotView view = V11SnapshotAdapter.from(snap);

			var resOn = ImpScmV11Compiler.compile(src, snap, true);
			var resOff = ImpScmV11Compiler.compile(src, snap, false);

			int[] domCards = {2, 3, 2};
			V11Interpreter.SnapshotBinding binding = new V11Interpreter.SnapshotBinding(domCards, view);

			V11Workspace wsOn = new V11Workspace(16);
			wsOn.params.put(0, new V11Workspace.ParamScalar(V11IsaTables.SCALAR_U64, true, 0L)); // $user = 0
			wsOn.params.put(1, new V11Workspace.ParamScalar(V11IsaTables.SCALAR_U64, true, 1L)); // $resource = 1
			V11Interpreter.execute(resOn.codeWords(), binding, wsOn);
			assertEquals(V11IsaTables.IMPULSE_VM_OK, wsOn.status);
			assertEquals(1L, wsOn.outputs.get(0).i64Data[0]); // allowed = true

			V11Workspace wsOff = new V11Workspace(16);
			wsOff.params.put(0, new V11Workspace.ParamScalar(V11IsaTables.SCALAR_U64, true, 0L));
			wsOff.params.put(1, new V11Workspace.ParamScalar(V11IsaTables.SCALAR_U64, true, 1L));
			V11Interpreter.execute(resOff.codeWords(), binding, wsOff);
			assertEquals(V11IsaTables.IMPULSE_VM_OK, wsOff.status);
			assertEquals(wsOn.outputs.get(0).i64Data[0], wsOff.outputs.get(0).i64Data[0]);
		}
	}

	@Test
	void testChainFixturesPositive() throws Exception {
		String src2 = Files.readString(specFixtureDir().resolve("02_two_hop_and_set_algebra.impscm"));
		String src3 = Files.readString(specFixtureDir().resolve("03_fixpoint_reach_count.impscm"));
		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildChainSnapshot(arena);
			V11SnapshotView view = V11SnapshotAdapter.from(snap);
			V11Interpreter.SnapshotBinding binding = new V11Interpreter.SnapshotBinding(new int[]{5}, view);

			var r2 = ImpScmV11Compiler.compile(src2, snap, true);
			V11Workspace ws2 = new V11Workspace(16);
			ws2.params.put(0, new V11Workspace.ParamScalar(V11IsaTables.SCALAR_U64, true, 0L));
			V11Interpreter.execute(r2.codeWords(), binding, ws2);
			assertEquals(V11IsaTables.IMPULSE_VM_OK, ws2.status);
			assertEquals(1, ws2.outputs.get(0).length);
			assertEquals(2L, ws2.outputs.get(0).i64Data[0]);
			assertEquals(1L, ws2.outputs.get(1).i64Data[0]);

			var r3 = ImpScmV11Compiler.compile(src3, snap, true);
			V11Workspace ws3 = new V11Workspace(16);
			ws3.params.put(0, new V11Workspace.ParamScalar(V11IsaTables.SCALAR_U64, true, 1L));
			V11Interpreter.execute(r3.codeWords(), binding, ws3);
			assertEquals(V11IsaTables.IMPULSE_VM_OK, ws3.status);
			assertEquals(4L, ws3.outputs.get(0).i64Data[0]);
		}
	}

	@Test
	void testNegativeStaticValidationFixtures() throws Exception {
		String negDom = Files.readString(specFixtureDir().resolve("04_negative_domain_mismatch.impscm"));
		String negMix = Files.readString(specFixtureDir().resolve("05_negative_mixed_int_float.impscm"));
		String negEndo = Files.readString(specFixtureDir().resolve("06_negative_non_endo_transitive.impscm"));
		try (Arena arena = Arena.ofConfined()) {
			GraphSnapshot snap = buildRebacSnapshot(arena);
			var e1 = assertThrows(IllegalArgumentException.class, () -> ImpScmV11Compiler.compile(negDom, snap, true));
			assertTrue(e1.getMessage().startsWith("DOMAIN_MISMATCH"), e1.getMessage());

			var e2 = assertThrows(IllegalArgumentException.class, () -> ImpScmV11Compiler.compile(negMix, snap, true));
			assertTrue(e2.getMessage().startsWith("TYPE_MISMATCH"), e2.getMessage());

			var e3 = assertThrows(IllegalArgumentException.class, () -> ImpScmV11Compiler.compile(negEndo, snap, true));
			assertTrue(e3.getMessage().startsWith("DOMAIN_MISMATCH"), e3.getMessage());
		}
	}
}
