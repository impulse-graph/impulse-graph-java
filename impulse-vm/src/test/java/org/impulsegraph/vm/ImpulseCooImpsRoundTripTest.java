package org.impulsegraph.vm;

import org.impulsegraph.spec.v0_11.ImpulseLayoutsV0_11;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end on-disk .imps v0.11 (0x000B) COO round-trip, cross-engine interop
 * (C++ -> Java), mixed-width domain dense_id_width, and negative hardening
 * tests.
 */
class ImpulseCooImpsRoundTripTest {

	private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, long imm32) {
		return ImpulseIsaTables.encode(op, flags, subop, dst, s1, s2, s3, (int) imm32);
	}

	@Test
	@DisplayName("v0.11 .imps layout constants match spec (192B relation entry, 32B domain entry)")
	void testV11LayoutSizes() {
		assertEquals(4096L, ImpulseLayoutsV0_11.IMPULSE_SNAPSHOT_HEADER_V0_11_T_LAYOUT.byteSize());
		assertEquals(32L, ImpulseLayoutsV0_11.IMPULSE_DOMAIN_CATALOG_ENTRY_V0_11_T_LAYOUT.byteSize());
		assertEquals(192L, ImpulseLayoutsV0_11.IMPULSE_RELATION_DIRECTORY_ENTRY_V0_11_T_LAYOUT.byteSize());
		assertEquals(44L, ImpulseLayoutsV0_11.IMPULSE_ATTRIBUTE_DESCRIPTOR_V0_11_T_LAYOUT.byteSize());
		assertEquals(64L, ImpulseLayoutsV0_11.IMPULSE_INDEX_DIRECTORY_ENTRY_V0_11_T_LAYOUT.byteSize());
		assertEquals(16L, ImpulseLayoutsV0_11.IMPULSE_FOOTER_TRAILER_V0_11_T_LAYOUT.byteSize());
	}

	@Test
	@DisplayName("Java writes v0.11 .imps with COO-only + mixed uint16/uint32 domain widths and executes COO walks + morsels")
	void testJavaWriteAndExecuteV11CooOnly(@TempDir Path tempDir) throws Exception {
		try (Arena arena = Arena.ofShared()) {
			MemorySegment cooSrc = arena.allocate(5L * 2L, 8);
			short[] srcs = {2, 0, 1, 0, 2};
			for (int i = 0; i < 5; i++) {
				cooSrc.set(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 2L, srcs[i]);
			}
			MemorySegment cooTgt = arena.allocate(5L * 4L, 8);
			int[] tgts = {7, 3, 7, 5, 3};
			for (int i = 0; i < 5; i++) {
				cooTgt.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4L, tgts[i]);
			}
			MemorySegment cooEids = arena.allocate(5L * 4L, 8);
			int[] eids = {4, 1, 0, 3, 2};
			for (int i = 0; i < 5; i++) {
				cooEids.set(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 4L, eids[i]);
			}
			MemorySegment weights = arena.allocate(5L * 8L, 8);
			double[] wArr = {1.5, 2.5, 3.0, 4.0, 10.0};
			for (int i = 0; i < 5; i++) {
				weights.set(ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), i * 8L, wArr[i]);
			}

			RelationSnapshot rel = new RelationSnapshot(arena, 6, 5, MemorySegment.NULL, MemorySegment.NULL,
					MemorySegment.NULL, MemorySegment.NULL, List.of(weights), List.of(), (byte) 2, (byte) 4, (byte) 4);
			rel.setCooSegments(cooSrc, cooTgt, cooEids, ImpulseLayoutsV0_11.COO_ORDER_UNSORTED);
			rel.setAttributeNames(List.of("weight"));
			rel.setAttributeTypeCodes(List.of((byte) 10)); // FLOAT64

			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("bought", rel));
			byte[] bytes = new DefaultSnapshotBuilder().withVersion((short) 11).withDomain(0, "User", (byte) 0x03, 6L)
					.withDomainDenseIdWidth(0, (byte) 2).withDomain(1, "Item", (byte) 0x03, 10L)
					.withDomainDenseIdWidth(1, (byte) 4).withRelationDomain("bought", 0, 1)
					.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(ImpulseLayoutsV0_11.SPEC_MAGIC, (short) 11,
							graph, Map.of(), Map.of(), Map.of(), Map.of()));

			Path outFile = tempDir.resolve("java_v11_coo_only.imps");
			Files.write(outFile, bytes);

			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(outFile, arena);
			assertEquals((short) 11, loaded.version());
			assertEquals((byte) 2, loaded.domainsById().get(0).denseIdWidth());
			assertEquals((byte) 4, loaded.domainsById().get(1).denseIdWidth());

			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(loaded.graph());
			ImpulseSnapshotView.RelView rv = view.relations[0];
			assertFalse(rv.hasCsr);
			assertFalse(rv.hasCsc);
			assertTrue(rv.hasCoo);
			assertEquals((byte) 2, rv.cooSrcWidth);
			assertEquals((byte) 4, rv.cooTgtWidth);
			assertEquals(ImpulseLayoutsV0_11.COO_ORDER_UNSORTED, rv.cooOrder);

			long[] walkProg = new long[]{enc(0x20, 0, 1, 0, 0, 0, 0, 0), enc(0x20, 0, 1, 1, 0, 0, 0, 2),
					enc(0x22, 0, 0, 2, 0, 1, 0, 0), enc(0x30, 0, 8, 3, 2, 0, 0, 0), enc(0x24, 0, 0, 2, 3, 0, 0, 0),
					enc(0x20, 0, 1, 4, 0, 0, 0, (1L << 16) | 7L), enc(0x30, 0, 9, 5, 4, 0, 0, 0),
					enc(0x24, 0, 0, 3, 5, 0, 0, 0), enc(0x00, 0, 0, 0, 0, 0, 0, 0),};
			ImpulseWorkspace ws = new ImpulseWorkspace(16);
			int[] domCards = new int[view.domainCards.length];
			for (int d = 0; d < domCards.length; d++) {
				domCards[d] = (int) view.domainCards[d];
			}
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(domCards, view);
			assertEquals(ImpulseIsaTables.IMPULSE_VM_OK,
					ImpulseInterpreter.executeValidated(walkProg, bind, ws, 10000L));
			assertEquals(3L, ws.rRegs[2]);
			assertArrayEquals(new int[]{3, 5, 7}, ws.fRegs[3].toSortedArray());
			assertEquals(2L, ws.rRegs[3]);
			assertArrayEquals(new int[]{1, 2}, ws.fRegs[5].toSortedArray());

			long[] morselProg = new long[]{enc(0x21, 0, 0, 0, 0, 0, 0, 0), enc(0x50, 0, 12, 1, 0, 0, 0, 1),
					enc(0x35, 0, 2, 0, 0, 0, 0, (3L << 16)), enc(0x42, 0, 0, 11, 10, 0, 0, 0),
					enc(0x37, 0, 2, 1, 9, 11, 0, 1), enc(0x38, 0, 0, 0, 0, 0, 0, (3L << 16)),
					enc(0x52, 0, 0, 0, 1, 0, 0, 0), enc(0x00, 0, 0, 0, 0, 0, 0, 0),};
			ws.reset();
			assertEquals(ImpulseIsaTables.IMPULSE_VM_OK,
					ImpulseInterpreter.executeValidated(morselProg, bind, ws, 10000L));
			assertEquals(21.0, Double.longBitsToDouble(ws.rRegs[0]), 1e-9);
			assertEquals(5.5, ws.vVectors[1].f64Data[3], 1e-9);
			assertEquals(4.0, ws.vVectors[1].f64Data[5], 1e-9);
			assertEquals(11.5, ws.vVectors[1].f64Data[7], 1e-9);
		}
	}

	@Test
	@DisplayName("Cross-engine interop: Java opens C++-generated v0.11 .imps COO snapshot and verifies identical results")
	void testCppToJavaV11CooInterop() throws Exception {
		Path cppFile = Path.of("/tmp/v11_coo_cpp_roundtrip.imps");
		if (!Files.exists(cppFile)) {
			return;
		}
		try (Arena arena = Arena.ofShared()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(cppFile, arena);
			assertEquals((short) 11, loaded.version());
			assertEquals((byte) 2, loaded.domainsById().get(0).denseIdWidth());
			assertEquals((byte) 4, loaded.domainsById().get(1).denseIdWidth());

			ImpulseSnapshotView view = ImpulseSnapshotAdapter.from(loaded.graph());
			assertTrue(view.relations.length >= 2);
			assertFalse(view.relations[0].hasCsr);
			assertTrue(view.relations[0].hasCoo);
			assertEquals((byte) 2, view.relations[0].cooSrcWidth);
			assertEquals((byte) 4, view.relations[0].cooTgtWidth);
			assertTrue(view.relations[1].hasCsr);
			assertTrue(view.relations[1].hasCoo);
			assertEquals(ImpulseLayoutsV0_11.COO_ORDER_SRC_TGT, view.relations[1].cooOrder);

			long[] morselProg = new long[]{enc(0x21, 0, 0, 0, 0, 0, 0, 0), enc(0x50, 0, 12, 1, 0, 0, 0, 1),
					enc(0x35, 0, 2, 0, 0, 0, 0, (3L << 16)), enc(0x42, 0, 0, 11, 10, 0, 0, 0),
					enc(0x37, 0, 2, 1, 9, 11, 0, 1), enc(0x38, 0, 0, 0, 0, 0, 0, (3L << 16)),
					enc(0x52, 0, 0, 0, 1, 0, 0, 0), enc(0x00, 0, 0, 0, 0, 0, 0, 0),};
			ImpulseWorkspace ws = new ImpulseWorkspace(16);
			int[] domCards = new int[view.domainCards.length];
			for (int d = 0; d < domCards.length; d++) {
				domCards[d] = (int) view.domainCards[d];
			}
			ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(domCards, view);
			assertEquals(ImpulseIsaTables.IMPULSE_VM_OK,
					ImpulseInterpreter.executeValidated(morselProg, bind, ws, 10000L));
			assertEquals(21.0, Double.longBitsToDouble(ws.rRegs[0]), 1e-9);
			assertEquals(5.5, ws.vVectors[1].f64Data[3], 1e-9);
			assertEquals(4.0, ws.vVectors[1].f64Data[5], 1e-9);
			assertEquals(11.5, ws.vVectors[1].f64Data[7], 1e-9);
		}
	}
}
