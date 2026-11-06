package org.impulsegraph.storage.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Nullable Attributes Storage Validation")
class NullabilityStorageTest {

	static Path ensureTc37NullableSnapshot() throws IOException {
		Path specFile = Paths.get("target", "test-fixtures", "tc37_nullable_padded_bitmap", "snapshot.imps");
		if (Files.exists(specFile)) {
			return specFile;
		}
		Files.createDirectories(specFile.getParent());
		try (Arena buildArena = Arena.ofConfined()) {
			MemorySegment voltageData = buildArena.allocate(5000L * Float.BYTES);
			for (int i = 0; i < 5000; i++) {
				voltageData.setAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, i, 1.0f);
			}
			MemorySegment validityBitmap = buildArena.allocate(768L);
			validityBitmap.set(ValueLayout.JAVA_LONG_UNALIGNED, 0, 0xFFFFFFFFFFFFFFFFL);

			RelationSnapshot busRel = new RelationSnapshot(buildArena, 1354, 5000, MemorySegment.NULL,
					MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, List.of(voltageData),
					List.of(validityBitmap), (byte) 4, (byte) 4);
			busRel.setAttributeNames(List.of("voltage"));
			busRel.setAttributeTypeCodes(List.of((byte) 0x86));

			GraphSnapshot graph = new GraphSnapshot(buildArena, Map.of("Bus", busRel));
			byte[] bytes = new DefaultSnapshotBuilder().withDomain(0, "Bus", (byte) 0x03, 1354L)
					.withRelationDomain("Bus", 0, 0)
					.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(BinarySnapshotLoader.SNAPSHOT_MAGIC,
							(short) 9, graph, Map.of(), Map.of(), Map.of(), Map.of()));
			Files.write(specFile, bytes);
		}
		return specFile;
	}

	@Test
	void testLoadNullableAttributeFromTestVector() throws Exception {
		Path specDir = ensureTc37NullableSnapshot();

		byte[] snapshotBytes = Files.readAllBytes(specDir);
		try (Arena arena = Arena.ofShared()) {
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena, false);
			GraphSnapshot graph = (GraphSnapshot) loaded.graph();
			RelationSnapshot rel = graph.getRelationSnapshot("Bus");

			assertNotNull(rel, "Relation 'Bus' should exist");
			List<MemorySegment> validitySegments = rel.getValiditySegments();

			assertNotNull(validitySegments, "Validity segments should not be null");
			assertFalse(validitySegments.isEmpty(), "Should have at least one validity segment");

			MemorySegment validity = validitySegments.get(0);

			// We set the first 64 nodes to valid (0xFFFFFFFFFFFFFFFF)
			long first64 = validity.get(ValueLayout.JAVA_LONG_UNALIGNED, 0);
			assertEquals(0xFFFFFFFFFFFFFFFFL, first64, "First 64 bits should be valid (1)");

			// Node 100 should be null (0)
			// Bit 100 is in the second long (bytes 8..15)
			long next64 = validity.get(ValueLayout.JAVA_LONG_UNALIGNED, 8);
			assertEquals(0L, next64, "Remaining nodes should be null (0)");
		}
	}
}
