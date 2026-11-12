package org.impulsegraph.vm.harness;

import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedAttribute;
import org.impulsegraph.storage.csr.BinarySnapshotLoader.LoadedDomain;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KleisliJsonQueryRunnerTest {

	private static GraphSnapshot buildSpecV010TestGraph(Arena arena) {
		// Domain 0: Bus (4 nodes: 0, 1, 2, 3)
		// - bus_num: int32 PK [100, 101, 102, 103]
		// - shunt_gs: nullable float64 [10.0, -5.0, NULL, 20.0] (validity bits 0b1011 =
		// 0x0B)
		// - vm_f16: float16 [1.00, 1.05, 0.96, 1.02]
		// - bus_label: var_string ["SUB_A_0", "SUB_A_1", "SUB_B_2", "SUB_A_3"]
		// - emb_f16: float16 VEC4
		MemorySegment busNumSeg = arena.allocateFrom(ValueLayout.JAVA_INT, 100, 101, 102, 103);

		MemorySegment shuntData = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, 10.0, -5.0, 999.0, 20.0);
		MemorySegment shuntVal = arena.allocate(8);
		shuntVal.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b0000_1011); // node 2 is NULL

		short[] vmF16Arr = {Float.floatToFloat16(1.00f), Float.floatToFloat16(1.05f), Float.floatToFloat16(0.96f),
				Float.floatToFloat16(1.02f)};
		MemorySegment vmF16Seg = arena.allocateFrom(ValueLayout.JAVA_SHORT, vmF16Arr);

		String[] labels = {"SUB_A_0", "SUB_A_1", "SUB_B_2", "SUB_A_3"};
		byte[][] labelBytes = new byte[4][];
		int totalStrBytes = 0;
		for (int i = 0; i < 4; i++) {
			labelBytes[i] = labels[i].getBytes(StandardCharsets.UTF_8);
			totalStrBytes += labelBytes[i].length;
		}
		MemorySegment labelOffs = arena.allocate(5 * 4L);
		MemorySegment labelData = arena.allocate(totalStrBytes);
		int cursor = 0;
		for (int i = 0; i < 4; i++) {
			labelOffs.set(ValueLayout.JAVA_INT_UNALIGNED, i * 4L, cursor);
			MemorySegment.copy(labelBytes[i], 0, labelData, ValueLayout.JAVA_BYTE, cursor, labelBytes[i].length);
			cursor += labelBytes[i].length;
		}
		labelOffs.set(ValueLayout.JAVA_INT_UNALIGNED, 16L, cursor);

		float[][] vecs = {{1.0f, 0.0f, 0.0f, 0.0f}, {0.5f, 0.5f, 0.0f, 0.0f}, {0.0f, 1.0f, 0.0f, 0.0f},
				{0.8f, 0.2f, 0.0f, 0.0f}};
		MemorySegment embF16Seg = arena.allocate(4 * 4 * 2L);
		for (int u = 0; u < 4; u++) {
			for (int d = 0; d < 4; d++) {
				embF16Seg.set(ValueLayout.JAVA_SHORT_UNALIGNED, (u * 4L + d) * 2L, Float.floatToFloat16(vecs[u][d]));
			}
		}

		List<LoadedAttribute> busAttrs = List.of(
				new LoadedAttribute("bus_num", (byte) 0x03, 1, 0, 0, 0, busNumSeg.byteSize(), 0, 0),
				new LoadedAttribute("shunt_gs", (byte) (0x07 | 0x80), 1, 0, shuntVal.byteSize(), 0,
						shuntData.byteSize(), 0, 0),
				new LoadedAttribute("vm_f16", (byte) 0x05, 1, 0, 0, 0, vmF16Seg.byteSize(), 0, 0),
				new LoadedAttribute("bus_label", (byte) 0x0B, 1, 0, 0, 0, labelData.byteSize(), 0,
						labelOffs.byteSize()),
				new LoadedAttribute("emb_f16", (byte) 0x05, 4, 0, 0, 0, embF16Seg.byteSize(), 0, 0));
		LoadedDomain busDom = new LoadedDomain(0, "Bus", (byte) 0x03, 4L, 0, busAttrs,
				List.of(busNumSeg, shuntData, vmF16Seg, labelData, embF16Seg),
				List.of(MemorySegment.NULL, shuntVal, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL),
				List.of(MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, labelOffs, MemorySegment.NULL));

		// Domain 1: Zone (2 nodes: 0="Z_NORTH", 1="Z_SOUTH")
		byte[] z0 = "Z_NORTH".getBytes(StandardCharsets.UTF_8);
		byte[] z1 = "Z_SOUTH".getBytes(StandardCharsets.UTF_8);
		MemorySegment zOffs = arena.allocateFrom(ValueLayout.JAVA_INT, 0, z0.length, z0.length + z1.length);
		MemorySegment zData = arena.allocate(z0.length + z1.length);
		MemorySegment.copy(z0, 0, zData, ValueLayout.JAVA_BYTE, 0, z0.length);
		MemorySegment.copy(z1, 0, zData, ValueLayout.JAVA_BYTE, z0.length, z1.length);
		LoadedDomain zoneDom = new LoadedDomain(
				1, "Zone", (byte) 0x0B, 2L, 0, List.of(new LoadedAttribute("zone_code", (byte) 0x0B, 1, 0, 0, 0,
						zData.byteSize(), 0, zOffs.byteSize())),
				List.of(zData), List.of(MemorySegment.NULL), List.of(zOffs));

		// Relation 0: AC_LINE (0->1, 1->2) with nullable float64 rate_a [150.0, NULL]
		MemorySegment acRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 2, 2, 2);
		MemorySegment acCol = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2);
		MemorySegment acCscRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 1, 2, 2);
		MemorySegment acCscCol = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1);
		MemorySegment rateData = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, 150.0, 999.0);
		MemorySegment rateVal = arena.allocate(8);
		rateVal.set(ValueLayout.JAVA_BYTE, 0, (byte) 0b01);
		RelationSnapshot acLine = new RelationSnapshot(arena, 4, 2, acRow, acCol, acCscRow, acCscCol, List.of(rateData),
				List.of(rateVal), (byte) 4, (byte) 4);
		acLine.setAttributeNames(List.of("rate_a"));
		acLine.setAttributeTypeCodes(List.of((byte) (0x07 | 0x80)));
		acLine.setLoadedAttributes(List.of(new LoadedAttribute("rate_a", (byte) (0x07 | 0x80), 1, 0, rateVal.byteSize(),
				0, rateData.byteSize(), 0, 0)), List.of(MemorySegment.NULL));
		acLine.setTargetNodeCount(4);
		acLine.setCatalogMetadata(0, 0, 0, (byte) 0, 0L, null);

		// Relation 1: TRANSFORMER (2->3)
		MemorySegment trRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 0, 1, 1);
		MemorySegment trCol = arena.allocateFrom(ValueLayout.JAVA_INT, 3);
		MemorySegment trCscRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 0, 0, 1);
		MemorySegment trCscCol = arena.allocateFrom(ValueLayout.JAVA_INT, 2);
		RelationSnapshot transformer = new RelationSnapshot(arena, 4, 1, trRow, trCol, trCscRow, trCscCol, List.of(),
				List.of(), (byte) 4, (byte) 4);
		transformer.setTargetNodeCount(4);
		transformer.setCatalogMetadata(1, 0, 0, (byte) 0, 0L, null);

		// Relation 2: GRID_BRANCH virtual super-relation over [AC_LINE(0),
		// TRANSFORMER(1)]
		RelationSnapshot gridBranch = new RelationSnapshot(arena, 4, 3, MemorySegment.NULL, MemorySegment.NULL,
				MemorySegment.NULL, MemorySegment.NULL, List.of(), List.of(), (byte) 4, (byte) 4);
		gridBranch.setTargetNodeCount(4);
		gridBranch.setCatalogMetadata(2, 0, 0, (byte) 0, 0x04L, new int[]{0, 1});
		gridBranch.setComponentRelations(new RelationSnapshot[]{acLine, transformer});

		// Relation 3: BUS_IN_ZONE flat M:1 u16 (0->0, 1->0, 2->1, 3->0xFFFF sentinel)
		MemorySegment bizFwd = arena.allocateFrom(ValueLayout.JAVA_SHORT, (short) 0, (short) 0, (short) 1,
				(short) 0xFFFF);
		MemorySegment bizCscRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 3);
		MemorySegment bizCscCol = arena.allocateFrom(ValueLayout.JAVA_SHORT, (short) 0, (short) 1, (short) 2);
		RelationSnapshot busInZone = new RelationSnapshot(arena, 4, 3, MemorySegment.NULL, bizFwd, bizCscRow, bizCscCol,
				List.of(), List.of(), (byte) 2, (byte) 4);
		busInZone.setTargetNodeCount(2);
		busInZone.setCatalogMetadata(3, 0, 1, (byte) 1, 0L, null);

		Map<String, RelationSnapshot> rels = new LinkedHashMap<>();
		rels.put("AC_LINE", acLine);
		rels.put("TRANSFORMER", transformer);
		rels.put("GRID_BRANCH", gridBranch);
		rels.put("BUS_IN_ZONE", busInZone);

		Map<String, LoadedDomain> doms = new LinkedHashMap<>();
		doms.put("Bus", busDom);
		doms.put("Zone", zoneDom);

		return new GraphSnapshot(arena, rels, Map.of(), Map.of(), doms);
	}

	@Test
	void testKleeneThreeValuedNullSemanticsAndTypes() {
		try (GraphSnapshot snap = buildSpecV010TestGraph(Arena.ofConfined())) {
			// != must NOT match NULL row 2!
			String rNeq = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q1\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"all\"},"
							+ "\"steps\":[{\"op\":\"filter\",\"cel\":\"shunt_gs != 999.0\"}],"
							+ "\"terminal\":{\"op\":\"nodes\"}}");
			assertEquals("{\"id\":\"q1\",\"status\":\"ok\",\"result_type\":\"nodes\",\"nodes\":[0,1,3]}", rNeq);

			// is_null / is_not_null
			String rNull = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q2\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"all\"},"
							+ "\"steps\":[{\"op\":\"filter\",\"cel\":\"is_null(shunt_gs)\"}],"
							+ "\"terminal\":{\"op\":\"nodes\"}}");
			assertEquals("{\"id\":\"q2\",\"status\":\"ok\",\"result_type\":\"nodes\",\"nodes\":[2]}", rNull);

			// !(NULL > 100.0) -> NULL -> falsy (node 2 excluded!)
			String rNotNull = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q3\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"all\"},"
							+ "\"steps\":[{\"op\":\"filter\",\"cel\":\"!(shunt_gs > 100.0)\"}],"
							+ "\"terminal\":{\"op\":\"nodes\"}}");
			assertEquals("{\"id\":\"q3\",\"status\":\"ok\",\"result_type\":\"nodes\",\"nodes\":[0,1,3]}", rNotNull);

			// starts_with + float16
			String rStrF16 = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q4\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"all\"},"
							+ "\"steps\":[{\"op\":\"filter\",\"cel\":\"starts_with(bus_label, \\\"SUB_A\\\") && vm_f16 > 1.01\"}],"
							+ "\"terminal\":{\"op\":\"keys\"}}");
			assertEquals("{\"id\":\"q4\",\"status\":\"ok\",\"result_type\":\"keys\",\"keys\":[\"101\",\"103\"]}",
					rStrF16);
		}
	}

	@Test
	void testVirtualSuperRelationFlatSentinelsEdgeFilterAndCrossDomain() {
		try (GraphSnapshot snap = buildSpecV010TestGraph(Arena.ofConfined())) {
			// Virtual GRID_BRANCH fixpoint_out from seed [0] reaches [0, 1, 2, 3]
			String rFp = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q5\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"seeds\",\"seeds\":[0]},"
							+ "\"steps\":[{\"op\":\"fixpoint_out\",\"relation\":\"GRID_BRANCH\",\"max_hops\":5}],"
							+ "\"terminal\":{\"op\":\"nodes\"}}");
			assertEquals("{\"id\":\"q5\",\"status\":\"ok\",\"result_type\":\"nodes\",\"nodes\":[0,1,2,3]}", rFp);

			// Flat M:1 BUS_IN_ZONE from [3] skips 0xFFFF sentinel -> empty
			String rSentinel = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q6\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"seeds\",\"seeds\":[3]},"
							+ "\"steps\":[{\"op\":\"out\",\"relation\":\"BUS_IN_ZONE\"}],"
							+ "\"terminal\":{\"op\":\"keys\"}}");
			assertEquals("{\"id\":\"q6\",\"status\":\"ok\",\"result_type\":\"keys\",\"keys\":[]}", rSentinel);

			// Cross-domain Bus -> Zone keys
			String rCross = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q7\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"seeds\",\"seeds\":[1,2,3]},"
							+ "\"steps\":[{\"op\":\"out\",\"relation\":\"BUS_IN_ZONE\"}],"
							+ "\"terminal\":{\"op\":\"keys\"}}");
			assertEquals(
					"{\"id\":\"q7\",\"status\":\"ok\",\"result_type\":\"keys\",\"keys\":[\"Z_NORTH\",\"Z_SOUTH\"]}",
					rCross);

			// Edge-filtered out_where with Kleene NULL on edge 1->2
			String rEdgeWhere = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q8\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"seeds\",\"seeds\":[0,1]},"
							+ "\"steps\":[{\"op\":\"out_where\",\"relation\":\"AC_LINE\",\"cel\":\"rate_a != 500.0\"}],"
							+ "\"terminal\":{\"op\":\"nodes\"}}");
			assertEquals("{\"id\":\"q8\",\"status\":\"ok\",\"result_type\":\"nodes\",\"nodes\":[1]}", rEdgeWhere);
		}
	}

	@Test
	void testMultiLaneSetCoverScalarReductionsVectorTopKAndErrors() {
		try (GraphSnapshot snap = buildSpecV010TestGraph(Arena.ofConfined())) {
			// Scalar reductions: all-NULL sum = 0.0, all-NULL min = null
			String rAllNullSum = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q9\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"seeds\",\"seeds\":[2]},"
							+ "\"steps\":[],\"terminal\":{\"op\":\"sum\",\"attr\":\"shunt_gs\"}}");
			assertEquals("{\"id\":\"q9\",\"status\":\"ok\",\"result_type\":\"scalar\",\"scalar\":0.0}", rAllNullSum);

			String rAllNullMin = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q10\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"seeds\",\"seeds\":[2]},"
							+ "\"steps\":[],\"terminal\":{\"op\":\"min\",\"attr\":\"shunt_gs\"}}");
			assertEquals("{\"id\":\"q10\",\"status\":\"ok\",\"result_type\":\"scalar\",\"scalar\":null}", rAllNullMin);

			// Multi-lane seeds [0, 2] -> BUS_IN_ZONE -> lane_masks & set_cover
			String rLanes = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q11\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"multilane_seeds\",\"seeds\":[0,2]},"
							+ "\"steps\":[{\"op\":\"out\",\"relation\":\"BUS_IN_ZONE\"}],"
							+ "\"terminal\":{\"op\":\"lane_masks\"}}");
			assertEquals("{\"id\":\"q11\",\"status\":\"ok\",\"result_type\":\"lane_masks\",\"lanes\":[[0,1],[1,2]]}",
					rLanes);

			String rCover = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q12\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"multilane_seeds\",\"seeds\":[0,1]},"
							+ "\"steps\":[{\"op\":\"out\",\"relation\":\"BUS_IN_ZONE\"}],"
							+ "\"terminal\":{\"op\":\"set_cover\",\"target_mask\":3}}");
			assertEquals("{\"id\":\"q12\",\"status\":\"ok\",\"result_type\":\"nodes\",\"nodes\":[0]}", rCover);

			// Exact top_k over float16 VEC4
			String rTopK = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q13\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"all\"},\"steps\":[],"
							+ "\"terminal\":{\"op\":\"top_k\",\"attr\":\"emb_f16\",\"query_vec\":[1.0,0.0,0.0,0.0],\"metric\":0,\"top_k\":2}}");
			assertTrue(rTopK.contains("\"result_type\":\"top_k\",\"nodes\":[0,3]"), rTopK);

			// Unsupported op & malformed JSON return clean protocol responses
			String rUnsup = KleisliJsonQueryRunner.executeJsonLine(snap,
					"{\"id\":\"q14\",\"anchor\":{\"domain\":\"Bus\",\"mode\":\"all\"},\"steps\":[],"
							+ "\"terminal\":{\"op\":\"nonexistent_op\"}}");
			assertTrue(rUnsup.contains("\"status\":\"unsupported\""), rUnsup);

			String rErr = KleisliJsonQueryRunner.executeJsonLine(snap, "{not valid json");
			assertTrue(rErr.contains("\"status\":\"error\""), rErr);
		}
	}
}
