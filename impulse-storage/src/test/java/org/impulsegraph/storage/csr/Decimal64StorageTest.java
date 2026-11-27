package org.impulsegraph.storage.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DECIMAL64 (0x15) Storage Roundtrip & Hardening Tests")
class Decimal64StorageTest {

	private static final byte TYPE_DECIMAL64 = (byte) 0x15;
	private static final byte TYPE_DECIMAL64_NULLABLE = (byte) (0x15 | 0x80);

	@Test
	@DisplayName("Roundtrip valid DECIMAL64(10, 2) non-nullable and nullable domain attributes")
	void testDecimal64RoundTrip(@TempDir Path tempDir) throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 4, 2, new int[]{0, 1, 2, 2, 2}, new int[]{1, 2});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("rel", rel));

			long[] balances = {12345L, -99999L, 0L, 500000000L}; // 123.45, -999.99, 0.00, 5000000.00
			ByteBuffer balBuf = ByteBuffer.allocate(balances.length * 8).order(ByteOrder.LITTLE_ENDIAN);
			for (long v : balances) {
				balBuf.putLong(v);
			}

			// Nullable amounts: [10050, NULL, -250, 77777] -> validity bits 0, 2, 3 set = 0b1101 = 0x0D
			byte[] amtValidity = new byte[128];
			amtValidity[0] = (byte) 0x0D;
			long[] amounts = {10050L, 0L, -250L, 77777L};
			ByteBuffer amtBuf = ByteBuffer.allocate(amounts.length * 8).order(ByteOrder.LITTLE_ENDIAN);
			for (long v : amounts) {
				amtBuf.putLong(v);
			}

			DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder().withVersion((short) 11)
					.withDomain(0, "Account", (byte) 0x03, 4L)
					.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, balBuf.array(), null, null)
					.withDomainAttribute(0, "amount", TYPE_DECIMAL64_NULLABLE, 1, 10, 2, amtBuf.array(), null,
							amtValidity);

			byte[] snapshotBytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11,
					graph, Map.of(), Map.of(), Map.of(), Map.of()));
			Path path = tempDir.resolve("decimal64_roundtrip.imps");
			Files.write(path, snapshotBytes);

			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(path, arena);
			BinarySnapshotLoader.LoadedDomain dom = loaded.getDomain("Account");
			assertThat(dom).isNotNull();
			assertThat(dom.attrCount()).isEqualTo(2);

			BinarySnapshotLoader.LoadedAttribute balAttr = dom.getAttribute("balance");
			assertThat(balAttr).isNotNull();
			assertThat(balAttr.typeCode()).isEqualTo(TYPE_DECIMAL64);
			assertThat(balAttr.isNullable()).isFalse();
			assertThat(balAttr.precision()).isEqualTo(10);
			assertThat(balAttr.scale()).isEqualTo(2);
			assertThat(balAttr.dataOffset() & 127L).isZero();
			assertThat(balAttr.dataBytes()).isEqualTo(32L);

			MemorySegment balSeg = dom.getAttributeSegment("balance");
			ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < balances.length; i++) {
				assertThat(balSeg.get(leLong, i * 8L)).isEqualTo(balances[i]);
			}

			BinarySnapshotLoader.LoadedAttribute amtAttr = dom.getAttribute("amount");
			assertThat(amtAttr).isNotNull();
			assertThat(amtAttr.typeCode()).isEqualTo(TYPE_DECIMAL64);
			assertThat(amtAttr.isNullable()).isTrue();
			assertThat(amtAttr.precision()).isEqualTo(10);
			assertThat(amtAttr.scale()).isEqualTo(2);
			assertThat(amtAttr.validityOffset() & 127L).isZero();
			assertThat(amtAttr.dataOffset() & 127L).isZero();

			MemorySegment amtVal = dom.getValiditySegment("amount");
			assertThat(amtVal.get(ValueLayout.JAVA_BYTE, 0L)).isEqualTo((byte) 0x0D);
			MemorySegment amtSeg = dom.getAttributeSegment("amount");
			for (int i = 0; i < amounts.length; i++) {
				assertThat(amtSeg.get(leLong, i * 8L)).isEqualTo(amounts[i]);
			}
		}
	}

	@Test
	@DisplayName("DefaultSnapshotBuilder preserves DECIMAL64 precision, scale, and dimension on relation attributes")
	void testRelationDecimal64ReserializePreservesPrecisionScale() {
		try (Arena arena = Arena.ofConfined()) {
			long[] weights = {1500L, -250L};
			MemorySegment weightSeg = arena.allocate(weights.length * 8L, 8);
			ValueLayout.OfLong leLong = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < weights.length; i++) {
				weightSeg.set(leLong, i * 8L, weights[i]);
			}
			MemorySegment rowSeg = arena.allocate(5 * 4L, 4);
			rowSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
			rowSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 1);
			rowSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 2);
			rowSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 3, 2);
			rowSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 4, 2);
			MemorySegment colSeg = arena.allocate(2 * 4L, 4);
			colSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 1);
			colSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 2);

			RelationSnapshot rel = new RelationSnapshot(arena, 4, 2, rowSeg, colSeg, MemorySegment.NULL,
					MemorySegment.NULL, java.util.List.of(weightSeg), java.util.List.of(MemorySegment.NULL), (byte) 4,
					(byte) 4, (byte) 4);
			rel.setAttributeNames(java.util.List.of("fee"));
			rel.setAttributeTypeCodes(java.util.List.of(TYPE_DECIMAL64));
			rel.setLoadedAttributes(
					java.util.List.of(new BinarySnapshotLoader.LoadedAttribute("fee", TYPE_DECIMAL64, 1, 12, 4, 0, 0, 0,
							16, 0, 0)),
					java.util.List.of(MemorySegment.NULL));

			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("TRANSFER", rel));
			byte[] snap1 = new DefaultSnapshotBuilder().withVersion((short) 11)
					.withDomain(0, "Account", (byte) 0x03, 4L)
					.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11, graph, Map.of(),
							Map.of(), Map.of(), Map.of()));

			BinarySnapshotLoader.LoadedSnapshot loaded1 = BinarySnapshotLoader.loadSnapshot(snap1, arena);
			BinarySnapshotLoader.LoadedAttribute fee1 = loaded1.getRelation(0).attributes().get(0);
			assertThat(fee1.typeCode()).isEqualTo(TYPE_DECIMAL64);
			assertThat(fee1.precision()).isEqualTo(12);
			assertThat(fee1.scale()).isEqualTo(4);
			assertThat(fee1.dimension()).isEqualTo(1);

			// Re-serialize loaded1 -> snap2 and confirm metadata still preserved
			byte[] snap2 = new DefaultSnapshotBuilder().withVersion((short) 11).build(loaded1);
			BinarySnapshotLoader.LoadedSnapshot loaded2 = BinarySnapshotLoader.loadSnapshot(snap2, arena);
			BinarySnapshotLoader.LoadedAttribute fee2 = loaded2.getRelation(0).attributes().get(0);
			assertThat(fee2.typeCode()).isEqualTo(TYPE_DECIMAL64);
			assertThat(fee2.precision()).isEqualTo(12);
			assertThat(fee2.scale()).isEqualTo(4);
			assertThat(fee2.dimension()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("Reject DECIMAL64 with precision=0 on open")
	void testRejectPrecisionZero() {
		assertInvalidDecimal64Rejected(0, 0, 32, false);
	}

	@Test
	@DisplayName("Reject DECIMAL64 with precision=19 on open")
	void testRejectPrecisionNineteen() {
		assertInvalidDecimal64Rejected(19, 2, 32, false);
	}

	@Test
	@DisplayName("Reject DECIMAL64 with scale > precision on open")
	void testRejectScaleGreaterThanPrecision() {
		assertInvalidDecimal64Rejected(5, 6, 32, false);
	}

	@Test
	@DisplayName("Reject DECIMAL64 with truncated payload (non-nullable)")
	void testRejectTruncatedPayloadNonNullable() {
		assertInvalidDecimal64Rejected(10, 2, 24, false); // 4 rows require >= 32 bytes
	}

	@Test
	@DisplayName("Reject DECIMAL64 with truncated payload (nullable)")
	void testRejectTruncatedPayloadNullable() {
		assertInvalidDecimal64Rejected(10, 2, 24, true); // 4 rows require >= 32 bytes after validity bitmap
	}

	private void assertInvalidDecimal64Rejected(int precision, int scale, int payloadBytes, boolean nullable) {
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 4, 0, new int[]{0, 0, 0, 0, 0}, new int[]{});
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("rel", rel));
			byte[] data = new byte[payloadBytes];
			byte[] validity = nullable ? new byte[128] : null;
			byte tc = nullable ? TYPE_DECIMAL64_NULLABLE : TYPE_DECIMAL64;

			byte[] snapshotBytes = new DefaultSnapshotBuilder().withVersion((short) 11)
					.withDomain(0, "Account", (byte) 0x03, 4L)
					.withDomainAttribute(0, "bad_dec", tc, 1, precision, scale, data, null, validity)
					.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11, graph, Map.of(),
							Map.of(), Map.of(), Map.of()));

			assertThatThrownBy(() -> BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}
}
