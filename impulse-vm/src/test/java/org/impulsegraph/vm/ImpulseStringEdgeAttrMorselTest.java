package org.impulsegraph.vm;

import org.impulsegraph.spec.v0_11.ImpulseLayoutsV0_11;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD regression test for UTF-8 string edge attributes loaded through
 * ImpulseSnapshotAdapter (verifies offsetsSeg is wired from
 * RelationSnapshot.getOffsetsSegments() so WALK_MORSEL + GATHER_EDGE_ATTR.vec
 * does not throw NullPointerException).
 */
class ImpulseStringEdgeAttrMorselTest {

	private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, long imm32) {
		return ImpulseIsaTables.encode(op, flags, subop, dst, s1, s2, s3, (int) imm32);
	}

	@Test
	@DisplayName("WALK_MORSEL + GATHER_EDGE_ATTR.vec reads UTF-8 string edge attribute via ImpulseSnapshotAdapter without NPE")
	void testWalkMorselGatherStringEdgeAttrViaAdapter() {
		try (Arena arena = Arena.ofShared()) {
			// 3 nodes, 2 edges: 0 -> 1 ("direct"), 0 -> 2 ("proxy")
			MemorySegment roff = arena.allocate(4L * 4L, 8);
			int[] ro = {0, 2, 2, 2};
			for (int i = 0; i < ro.length; i++) {
				roff.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4L, ro[i]);
			}
			MemorySegment ctgt = arena.allocate(2L * 4L, 8);
			int[] ct = {1, 2};
			for (int i = 0; i < ct.length; i++) {
				ctgt.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4L, ct[i]);
			}

			byte[] strBytes = "directproxy".getBytes(StandardCharsets.UTF_8);
			MemorySegment strData = arena.allocate(strBytes.length, 8);
			MemorySegment.copy(strBytes, 0, strData, ValueLayout.JAVA_BYTE, 0, strBytes.length);

			MemorySegment strOff = arena.allocate(3L * 4L, 8);
			int[] offs = {0, 6, 11};
			for (int i = 0; i < offs.length; i++) {
				strOff.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4L, offs[i]);
			}

			RelationSnapshot rel = new RelationSnapshot(arena, 3, 2, roff, ctgt, MemorySegment.NULL, MemorySegment.NULL,
					List.of(strData), List.of(), (byte) 4, (byte) 4, (byte) 4);
			rel.setAttributeNames(List.of("label"));
			rel.setAttributeTypeCodes(List.of((byte) 11)); // UTF-8 STRING
			rel.setLoadedAttributes(List.of(), List.of(strOff));

			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("KNOWS", rel));
			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(graph);

			ImpulseSnapshotView.OffHeapAttrColumn edgeCol = view.relations[0].offHeapEdgeAttrs.get(0);
			assertNotNull(edgeCol, "String edge attribute column must be present");
			assertNotNull(edgeCol.offsetsSeg(), "offsetsSeg must be wired from RelationSnapshot.getOffsetsSegments()");
			assertEquals("direct", edgeCol.readString(0));
			assertEquals("proxy", edgeCol.readString(1));

			// WALK_MORSEL.fwd(F0=all(dom 0), rel=0):
			// M4 (V12) = GATHER_EDGE_ATTR.vec(M2=V10, rel=0, attr=0)
			// MORSEL_COMMIT.yield_frontier(F1, M1=V9, dom=0)
			// MORSEL_END
			// HALT
			long[] prog = new long[]{enc(0x21, 0, 0, 0, 0, 0, 0, 0), enc(0x35, 0, 0, 0, 0, 0, 0, (3L << 16)),
					enc(0x42, 0, 0, 12, 10, 0, 0, 0), enc(0x37, 0, 0, 1, 9, 0, 0, 0),
					enc(0x38, 0, 0, 0, 0, 0, 0, (3L << 16)), enc(0x00, 0, 0, 0, 0, 0, 0, 0),};

			ImpulseWorkspace ws = new ImpulseWorkspace(16);
			int[] domCards = {3};
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(domCards, view);
			int status = ImpulseInterpreter.executeValidated(prog, bind, ws, 10_000L);
			assertEquals(ImpulseIsaTables.IMPULSE_VM_OK, status, "pc=" + ws.pc + " status=" + status);
			assertArrayEquals(new int[]{1, 2}, ws.fRegs[1].toSortedArray());
		}
	}
}
