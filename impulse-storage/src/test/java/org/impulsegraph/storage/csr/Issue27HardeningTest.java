package org.impulsegraph.storage.csr;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Issue #27: BinarySnapshotLoader Hardening Verification")
class Issue27HardeningTest {

	private static int computeCrc16(byte[] data, int off, int len) {
		int crc = 0xFFFF;
		for (int i = off; i < off + len; i++) {
			crc ^= (data[i] & 0xFF) << 8;
			for (int j = 0; j < 8; j++) {
				if ((crc & 0x8000) != 0) {
					crc = (crc << 1) ^ 0x1021;
				} else {
					crc <<= 1;
				}
			}
		}
		return crc & 0xFFFF;
	}

	@Test
	@DisplayName("Valid snapshot passes CRC check and required_features validation")
	void testValidSnapshotLoads() throws Exception {
		Arena arena = Arena.ofShared();
		RelationSnapshot rel = new RelationSnapshot(arena, 2, 2, new int[]{0, 1, 2}, new int[]{10, 11});
		GraphSnapshot graph = new GraphSnapshot(arena, Map.of("u2g", rel));
		byte[] snapBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);

		BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapBytes, arena);
		assertNotNull(loaded);
		assertEquals(1, loaded.relationCount());
		loaded.close();
	}

	@Test
	@DisplayName("Header CRC-16 mismatch is unconditionally rejected")
	void testHeaderCrcMismatchRejected() {
		try (Arena arena = Arena.ofShared()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 2, 2, new int[]{0, 1, 2}, new int[]{10, 11});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("u2g", rel));
			byte[] snapBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);

			// Corrupt CRC at offset 0x3E
			snapBytes[0x3E] ^= (byte) 0xFF;

			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
				BinarySnapshotLoader.loadSnapshot(snapBytes, arena);
			});
			assertTrue(ex.getMessage().contains("Header CRC-16 mismatch"),
					"Expected CRC mismatch message, got: " + ex.getMessage());
		}
	}

	@Test
	@DisplayName("Unknown required_features bitmask is rejected")
	void testUnknownRequiredFeaturesRejected() {
		try (Arena arena = Arena.ofShared()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 2, 2, new int[]{0, 1, 2}, new int[]{10, 11});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("u2g", rel));
			byte[] snapBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);

			// Set unknown required_feature flag (e.g. 0x8000000000000000L or 0x10L) at
			// offset 22
			ByteBuffer bb = ByteBuffer.wrap(snapBytes).order(ByteOrder.LITTLE_ENDIAN);
			long unknownFeatures = 0x8000000000000000L | 1L;
			bb.putLong(22, unknownFeatures);

			// Recompute valid CRC over corrupted required_features so CRC passes and
			// required_features fails
			int crc = computeCrc16(snapBytes, 0, 0x3E);
			bb.putShort(0x3E, (short) crc);

			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
				BinarySnapshotLoader.loadSnapshot(snapBytes, arena);
			});
			assertTrue(ex.getMessage().contains("Unsupported required_features bitmask"),
					"Expected Unsupported required_features message, got: " + ex.getMessage());
		}
	}

	@Test
	@DisplayName("Undersized CSR row_offsets segment is rejected with IllegalArgumentException")
	void testUndersizedCsrRowOffsetsRejected() {
		try (Arena arena = Arena.ofShared()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 2, 2, new int[]{0, 1, 2}, new int[]{10, 11});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("u2g", rel));
			byte[] snapBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);

			ByteBuffer bb = ByteBuffer.wrap(snapBytes).order(ByteOrder.LITTLE_ENDIAN);
			// Section 2 directory table is at offset 4096.
			// String table header (4 bytes) + pool (128 bytes aligned)
			// Domain table (1 domain = 16 bytes, 128 bytes aligned)
			// Relation table starts at rel table offset
			int strBytes = bb.getInt(4096);
			int strEnd = 4096 + 4 + strBytes;
			int domStart = (strEnd + 127) & ~127;
			int domCount = Short.toUnsignedInt(bb.getShort(10));
			int domEnd = domStart + domCount * 16;
			int relStart = (domEnd + 127) & ~127;

			// In Relation Entry (128 bytes):
			// offset 0: relId (2)
			// offset 2: srcDomId (2)
			// offset 4: tgtDomId (2)
			// offset 6: encodingId (1)
			// offset 7: nodeIdWidth (1)
			// offset 8: edgeIndexWidth (1)
			// offset 9: reserved1 (3)
			// offset 12: relNameOff (4)
			// offset 16: nodeCount (8)
			// offset 24: edgeCount (8)
			// offset 32: secFeatures (8)
			// offset 40: csrRowOffOffset (8)
			// offset 48: csrRowOffBytes (8)
			// offset 56: csrColIdxOffset (8)
			// offset 64: csrColIdxBytes (8)
			// Corrupt csrRowOffBytes to be 4 bytes instead of (nodeCount + 1)*4 = 12 bytes
			bb.putLong(relStart + 48, 4L);

			// Recompute header CRC
			int crc = computeCrc16(snapBytes, 0, 0x3E);
			bb.putShort(0x3E, (short) crc);

			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
				BinarySnapshotLoader.loadSnapshot(snapBytes, arena);
			});
			assertTrue(ex.getMessage().contains("CSR row_offsets segment undersized"),
					"Expected undersized CSR row_offsets message, got: " + ex.getMessage());
		}
	}

	@Test
	@DisplayName("Undersized CSR col_idx targets segment is rejected with IllegalArgumentException")
	void testUndersizedCsrTargetsRejected() {
		try (Arena arena = Arena.ofShared()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 2, 2, new int[]{0, 1, 2}, new int[]{10, 11});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("u2g", rel));
			byte[] snapBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);

			ByteBuffer bb = ByteBuffer.wrap(snapBytes).order(ByteOrder.LITTLE_ENDIAN);
			int strBytes = bb.getInt(4096);
			int strEnd = 4096 + 4 + strBytes;
			int domStart = (strEnd + 127) & ~127;
			int domCount = Short.toUnsignedInt(bb.getShort(10));
			int domEnd = domStart + domCount * 16;
			int relStart = (domEnd + 127) & ~127;

			// Corrupt csrColIdxBytes (offset 64) to be 4 bytes instead of edgeCount*4 = 8
			// bytes
			bb.putLong(relStart + 64, 4L);

			int crc = computeCrc16(snapBytes, 0, 0x3E);
			bb.putShort(0x3E, (short) crc);

			IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
				BinarySnapshotLoader.loadSnapshot(snapBytes, arena);
			});
			assertTrue(ex.getMessage().contains("CSR col_idx targets segment undersized"),
					"Expected undersized targets message, got: " + ex.getMessage());
		}
	}
}
