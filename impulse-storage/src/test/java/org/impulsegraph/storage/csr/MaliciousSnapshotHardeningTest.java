package org.impulsegraph.storage.csr;

import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.util.ImpulseAssert;
import org.impulsegraph.api.util.ImpulseTestControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive adversarial binary hardening and SQLite HexIO-style byte
 * mutation test suite for {@link BinarySnapshotLoader} and
 * {@link RelationSnapshot}.
 */
@DisplayName("Malicious & Malformed .imps Binary Parsing Hardening Suite")
class MaliciousSnapshotHardeningTest {

	private byte[] richV010Bytes;

	@BeforeEach
	void setUp() {
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseTestControl.setAuditEnabled(true);
		ImpulseTestControl.setNeverCorrupt(false);
		ImpulseAssert.resetCounters();
		richV010Bytes = buildRichV010Snapshot();
	}

	@AfterEach
	void tearDown() {
		ImpulseTestControl.resetAll();
	}

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

	private static void recomputeHeaderCrc(byte[] snap) {
		if (snap.length >= 64) {
			int crc = computeCrc16(snap, 0, 0x3E);
			ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN).putShort(0x3E, (short) crc);
		}
	}

	private static void recomputeHeaderCrc(MemorySegment seg) {
		if (seg.byteSize() >= 64) {
			byte[] hdr = new byte[0x3E];
			MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, hdr, 0, 0x3E);
			int crc = computeCrc16(hdr, 0, 0x3E);
			MemorySegmentHexPatcher.writeU16Le(seg, 0x3E, crc);
		}
	}

	/**
	 * Builds a rich multi-domain v0.10.0 {@code .imps} snapshot in memory
	 * containing:
	 * <ul>
	 * <li>Domain 0 ("User", 4 nodes): VarString primary key ("user_id"), INT64
	 * ("age"), nullable FLOAT32 ("score")</li>
	 * <li>Domain 1 ("Item", 3 nodes): INT64 primary key ("item_id")</li>
	 * <li>Relation 0 ("likes", User -> Item, 4 edges, CSR + CSC + FLOAT32
	 * weight)</li>
	 * <li>Relation 1 ("follows", User -> User, 3 edges, CSR + CSC)</li>
	 * <li>Relation 2 ("social", User -> User, virtual super-relation referencing
	 * R1)</li>
	 * <li>Secondary Index 0 (B-Tree on Domain 0 attr 1)</li>
	 * <li>Metadata footer with relation degree JSON stats</li>
	 * </ul>
	 */
	private static byte[] buildRichV010Snapshot() {
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot likes = new RelationSnapshot(arena, 4, 4, new int[]{0, 2, 3, 4, 4}, new int[]{0, 1, 2, 1});
			likes.setTargetNodeCount(3);
			MemorySegment weightSeg = arena.allocate(16, 8);
			for (int i = 0; i < 4; i++) {
				weightSeg.setAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, i, 1.5f + i);
			}
			likes.getAttributeSegments().add(weightSeg);
			likes.setAttributeNames(List.of("weight"));
			likes.setAttributeTypeCodes(List.of((byte) 0x06));

			RelationSnapshot follows = new RelationSnapshot(arena, 4, 3, new int[]{0, 1, 2, 3, 3}, new int[]{1, 2, 0});
			RelationSnapshot social = new RelationSnapshot(arena, 4, 0, MemorySegment.NULL, MemorySegment.NULL);
			social.setCatalogMetadata(2, 0, 0, (byte) 0, 0x04L, new int[]{1});

			Map<String, RelationSnapshot> relMap = new LinkedHashMap<>();
			relMap.put("likes", likes);
			relMap.put("follows", follows);
			relMap.put("social", social);

			Map<String, String> meta = new LinkedHashMap<>();
			meta.put("suite", "hardening");
			// TreeSet relation order: 0="follows", 1="likes", 2="social"
			meta.put("impulse.stats.relation.1.out_degree",
					"{\"max\":2,\"p50\":1,\"p90\":2,\"p99\":2,\"zero_count\":1}");

			GraphSnapshot graph = new GraphSnapshot(arena, relMap, meta);

			DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder().withVersion((short) 10).withCsc(true)
					.withDomain(0, "User", (byte) 0x0B, 4L).withDomain(1, "Item", (byte) 0x04, 3L)
					.withRelationDomain("likes", 0, 1).withRelationDomain("follows", 0, 0)
					.withRelationDomain("social", 0, 0);

			builder.withDomainKeyAttribute(0, "user_id", (byte) 0x0B, List.of("u0", "u1", "u2", "u3"));
			ByteBuffer ageBuf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
			ageBuf.putLong(21L).putLong(34L).putLong(29L).putLong(45L);
			builder.withDomainAttribute(0, "age", (byte) 0x04, 1, ageBuf.array(), null, null);

			byte[] scoreVal = new byte[128];
			scoreVal[0] = 0x0D;
			ByteBuffer scoreBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			scoreBuf.putFloat(1.0f).putFloat(0.0f).putFloat(2.5f).putFloat(3.5f);
			builder.withDomainAttribute(0, "score", (byte) (0x06 | 0x80), 1, scoreBuf.array(), null, scoreVal);

			builder.withDomainKeyAttribute(1, "item_id", (byte) 0x04, List.of("100", "200", "300"));

			byte[] raw = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
					BinarySnapshotLoader.SNAPSHOT_MAGIC, (short) 10, graph, Map.of(), Map.of(), Map.of(), meta));
			// Patch relation 2 ("social", after "follows" [128B] and "likes" [128+44=172B])
			// as virtual -> R0 ("follows")
			CatalogOffsets cat = locateCatalog(raw);
			int socialRelPos = (int) cat.relStart + 128 + 172;
			patchInlineVirtualEntry(raw, socialRelPos, new int[]{0});
			return raw;
		}
	}

	private static void patchInlineVirtualEntry(byte[] snap, int relPos, int[] compIds) {
		ByteBuffer bb = ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN);
		bb.putLong(relPos + 32, 0x04L); // section_features bit 2 = virtual
		bb.putShort(relPos + 106, (short) compIds.length);
		for (int i = 0; i < compIds.length; i++) {
			bb.putShort(relPos + 108 + i * 2, (short) compIds[i]);
		}
	}

	private record CatalogOffsets(long dirOffset, int strPoolBytes, long domStart, long relStart, long idxStart) {
	}

	private static CatalogOffsets locateCatalog(byte[] snap) {
		ByteBuffer bb = ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN);
		int ver = Short.toUnsignedInt(bb.getShort(4));
		long dataOff = Integer.toUnsignedLong(bb.getInt(6));
		long footerDirOff = bb.getLong(30);
		long dirOff = footerDirOff > 0 ? footerDirOff : dataOff;
		int domCount = Short.toUnsignedInt(bb.getShort(10));
		int relCount = Short.toUnsignedInt(bb.getShort(12));

		int strBytes = bb.getInt((int) dirOff);
		long pos = 4L + strBytes;
		if (pos % 128 != 0) {
			pos += 128 - (pos % 128);
		}
		long domStart = dirOff + pos;
		for (int i = 0; i < domCount; i++) {
			if (ver == 10) {
				int ac = Short.toUnsignedInt(bb.getShort((int) (dirOff + pos + 16)));
				pos += 32L + ac * 44L;
			} else {
				pos += 16L;
			}
		}
		if (pos % 128 != 0) {
			pos += 128 - (pos % 128);
		}
		long relStart = dirOff + pos;
		for (int j = 0; j < relCount; j++) {
			int rac = Short.toUnsignedInt(bb.getShort((int) (dirOff + pos + 104)));
			pos += 128L + rac * 44L;
		}
		if (pos % 128 != 0) {
			pos += 128 - (pos % 128);
		}
		long idxStart = dirOff + pos;
		return new CatalogOffsets(dirOff, strBytes, domStart, relStart, idxStart);
	}

	@Test
	@DisplayName("Baseline rich v0.10 snapshot passes strict audit under neverCorrupt=true")
	void testRichBaselinePassesNeverCorruptAudit() {
		ImpulseTestControl.setNeverCorrupt(true);
		try (Arena arena = Arena.ofConfined()) {
			BinarySnapshotLoader.LoadedSnapshot snap = BinarySnapshotLoader.loadSnapshot(richV010Bytes, arena, true);
			assertEquals(2, snap.domainCount());
			assertEquals(3, snap.relationCount());
			assertEquals("u0", snap.getDomain(0).resolveKey(0));
			assertEquals("u3", snap.getDomain(0).resolveKey(3));
			assertNull(snap.getDomain(0).resolveKey(4));
			assertNull(snap.getDomain(0).resolveKey(-1));
			assertEquals("100", snap.getDomain(1).resolveKey(0));

			RelationSnapshot social = snap.graph().getRelationSnapshot("social");
			assertTrue(social.isVirtual());
			assertArrayEquals(new int[]{1}, social.getTargets(0));
			assertEquals(2, snap.graph().getRelationSnapshot("likes").getStatistics().getMaxDegree());
		}
	}

	@Test
	@DisplayName("1. Header attacks: truncated buffers, bad magic, bad version, CRC-16, required_features, negative dataOffset & footerDirectoryOffset")
	void testHeaderAttacks() {
		try (Arena arena = Arena.ofConfined()) {
			// Truncated headers: 0, 15, 57, 58, 63, 65 bytes
			for (int len : new int[]{0, 15, 57, 58, 63, 65}) {
				byte[] truncated = java.util.Arrays.copyOf(richV010Bytes, len);
				assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(truncated, arena),
						"Expected rejection for truncated length " + len);
			}

			// Bad magic
			byte[] badMagic = richV010Bytes.clone();
			ByteBuffer.wrap(badMagic).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0xBAD0CAFE);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badMagic, arena));

			// Unsupported versions
			for (short badVer : new short[]{0, 1, 8, 11, (short) 0xFFFF}) {
				byte[] snap = richV010Bytes.clone();
				ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN).putShort(4, badVer);
				recomputeHeaderCrc(snap);
				assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(snap, arena));
			}

			// Bad CRC-16
			byte[] badCrc = richV010Bytes.clone();
			badCrc[0x3E] ^= 0x55;
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badCrc, arena));

			// Unknown required_features
			byte[] badReqFeat = richV010Bytes.clone();
			ByteBuffer.wrap(badReqFeat).order(ByteOrder.LITTLE_ENDIAN).putLong(22, 0x100L);
			recomputeHeaderCrc(badReqFeat);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badReqFeat, arena));

			// Corrupt negative data_offset (0x80000000 -> unsigned 2GB > segSize) and small
			// data_offset (16 < 66)
			for (int badDataOff : new int[]{0, 16, 63, 0x80000000}) {
				byte[] snap = richV010Bytes.clone();
				ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN).putInt(6, badDataOff);
				recomputeHeaderCrc(snap);
				assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(snap, arena));
			}

			// Negative and out-of-bounds footerDirectoryOffset & footerDirectoryBytes
			for (long badFdo : new long[]{-1L, Long.MIN_VALUE, 32L, richV010Bytes.length,
					richV010Bytes.length + 4096L}) {
				byte[] snap = richV010Bytes.clone();
				ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN).putLong(30, badFdo);
				recomputeHeaderCrc(snap);
				assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(snap, arena));
			}

			// Non-zero footerDirectoryBytes when footerDirectoryOffset == 0, or overflowing
			// footerDirectoryBytes
			for (long badFdb : new long[]{-1L, 128L, Long.MAX_VALUE}) {
				byte[] snap = richV010Bytes.clone();
				ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN).putLong(38, badFdb);
				recomputeHeaderCrc(snap);
				assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(snap, arena));
			}
		}
	}

	@Test
	@DisplayName("2. Section 2 String Pool attacks: zero/negative/overflow size, missing NUL, invalid UTF-8 sequences, out-of-bounds nameOff")
	void testStringPoolAttacks() {
		CatalogOffsets cat = locateCatalog(richV010Bytes);
		try (Arena arena = Arena.ofConfined()) {
			// strPoolBytes <= 0 and strPoolBytes > remaining
			for (int badLen : new int[]{0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, richV010Bytes.length}) {
				byte[] snap = richV010Bytes.clone();
				ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN).putInt((int) cat.dirOffset, badLen);
				assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(snap, arena));
			}

			// poolBytes[0] != 0
			byte[] nonZeroFirst = richV010Bytes.clone();
			nonZeroFirst[(int) cat.dirOffset + 4] = 'A';
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(nonZeroFirst, arena));

			// Missing trailing NUL byte at end of string pool
			byte[] noTrailingNul = richV010Bytes.clone();
			noTrailingNul[(int) cat.dirOffset + 4 + cat.strPoolBytes - 1] = 'Z';
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(noTrailingNul, arena));

			// Invalid UTF-8 sequences inside string pool:
			// (a) 0xFF invalid byte, (b) overlong NUL 0xC0 0x80, (c) UTF-16 surrogate 0xED
			// 0xA0 0x80, (d) truncated 3-byte 0xE2 0x82
			byte[][] invalidUtf8Cases = new byte[][]{new byte[]{(byte) 0xFF}, new byte[]{(byte) 0xC0, (byte) 0x80},
					new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, new byte[]{(byte) 0xE2, (byte) 0x82}};
			for (byte[] badSeq : invalidUtf8Cases) {
				byte[] snap = richV010Bytes.clone();
				System.arraycopy(badSeq, 0, snap, (int) cat.dirOffset + 5, badSeq.length);
				assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(snap, arena));
			}

			// Out-of-bounds nameOff on Domain 0 (offset +4 in 32-byte v0.10 domain entry)
			for (int badNameOff : new int[]{-1, cat.strPoolBytes, Integer.MAX_VALUE}) {
				byte[] snap = richV010Bytes.clone();
				ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN).putInt((int) cat.domStart + 4, badNameOff);
				assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(snap, arena));
			}
		}
	}

	@Test
	@DisplayName("3. Truncated catalog tables & alignment padding rejected instead of silent break")
	void testTruncatedCatalogLoopsRejected() {
		CatalogOffsets cat = locateCatalog(richV010Bytes);
		try (Arena arena = Arena.ofConfined()) {
			// Claim domainCount = 50 when buffer truncated inside domain table
			byte[] truncDom = java.util.Arrays.copyOf(richV010Bytes, (int) cat.domStart + 40);
			ByteBuffer.wrap(truncDom).order(ByteOrder.LITTLE_ENDIAN).putShort(10, (short) 5);
			recomputeHeaderCrc(truncDom);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(truncDom, arena));

			// Claim domain attrCount = 10 when buffer truncated mid-attribute
			byte[] truncDomAttr = java.util.Arrays.copyOf(richV010Bytes, (int) cat.domStart + 60);
			ByteBuffer.wrap(truncDomAttr).order(ByteOrder.LITTLE_ENDIAN).putShort((int) cat.domStart + 16, (short) 10);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(truncDomAttr, arena));

			// Claim relationCount = 10 when buffer truncated inside relation table
			byte[] truncRel = java.util.Arrays.copyOf(richV010Bytes, (int) cat.relStart + 140);
			ByteBuffer.wrap(truncRel).order(ByteOrder.LITTLE_ENDIAN).putShort(12, (short) 10);
			recomputeHeaderCrc(truncRel);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(truncRel, arena));

			// Claim relation attrCount = 10 when buffer truncated inside relation
			// attributes
			byte[] truncRelAttr = java.util.Arrays.copyOf(richV010Bytes, (int) cat.relStart + 150);
			ByteBuffer.wrap(truncRelAttr).order(ByteOrder.LITTLE_ENDIAN).putShort((int) cat.relStart + 104, (short) 10);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(truncRelAttr, arena));

			// Claim indexCount = 5 when buffer ends before 5 index entries
			byte[] truncIdx = java.util.Arrays.copyOf(richV010Bytes, (int) cat.idxStart + 32);
			ByteBuffer.wrap(truncIdx).order(ByteOrder.LITTLE_ENDIAN).putShort(0x40, (short) 5);
			recomputeHeaderCrc(truncIdx);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(truncIdx, arena));

			// Truncate right inside 128-byte alignment padding after string pool
			byte[] truncPad = java.util.Arrays.copyOf(richV010Bytes, (int) cat.dirOffset + 4 + cat.strPoolBytes + 3);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(truncPad, arena));
		}
	}

	@Test
	@DisplayName("4. 64-bit integer overflow & narrowing attacks (Long.MAX_VALUE validity bitmap, > Integer.MAX_VALUE narrowing, section bounds)")
	void testIntegerOverflowAndNarrowingAttacks() {
		CatalogOffsets cat = locateCatalog(richV010Bytes);
		try (Arena arena = Arena.ofConfined()) {
			// Direct helper probe for Long.MAX_VALUE bitmap math
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.computePaddedValidityBytes(Long.MAX_VALUE, "test"));
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.computePaddedValidityBytes(Long.MAX_VALUE - 10L, "test"));
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.computePaddedValidityBytes(-1L, "test"));

			// Domain nodeCount = Long.MAX_VALUE on domain with nullable attribute -> must
			// NOT wrap paddedBitmapBytes negative!
			byte[] overflowBitmapSnap = richV010Bytes.clone();
			ByteBuffer.wrap(overflowBitmapSnap).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.domStart + 8,
					Long.MAX_VALUE);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(overflowBitmapSnap, arena));

			// Domain nodeCount = 0x1_0000_0002L (> Integer.MAX_VALUE narrowing attack)
			byte[] narrowDomSnap = richV010Bytes.clone();
			ByteBuffer.wrap(narrowDomSnap).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.domStart + 8,
					0x1_0000_0002L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(narrowDomSnap, arena));

			// Relation nodeCount = 0x1_0000_0002L and edgeCount = 0x1_0000_0002L
			byte[] narrowRelNode = richV010Bytes.clone();
			ByteBuffer.wrap(narrowRelNode).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.relStart + 16,
					0x1_0000_0002L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(narrowRelNode, arena));

			byte[] narrowRelEdge = richV010Bytes.clone();
			ByteBuffer.wrap(narrowRelEdge).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.relStart + 24,
					0x1_0000_0002L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(narrowRelEdge, arena));

			// Section offset + length 64-bit overflow: csrRowOffOffset = Long.MAX_VALUE -
			// 63 (or aligned near MAX_VALUE) + bytes = 128
			byte[] overflowSec = richV010Bytes.clone();
			long alignedNearMax = Long.MAX_VALUE & ~127L;
			ByteBuffer.wrap(overflowSec).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.relStart + 40,
					alignedNearMax);
			ByteBuffer.wrap(overflowSec).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.relStart + 48, 256L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(overflowSec, arena));

			// Unaligned section offset (4100)
			byte[] unalignedSec = richV010Bytes.clone();
			ByteBuffer.wrap(unalignedSec).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.relStart + 40, 4100L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(unalignedSec, arena));

			// offset == 0 && bytes > 0
			byte[] zeroOffNonZeroLen = richV010Bytes.clone();
			ByteBuffer.wrap(zeroOffNonZeroLen).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.relStart + 40, 0L);
			ByteBuffer.wrap(zeroOffNonZeroLen).order(ByteOrder.LITTLE_ENDIAN).putLong((int) cat.relStart + 48, 128L);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(zeroOffNonZeroLen, arena));

			// Invalid nodeIdWidth / edgeIndexWidth / encodingId / undeclared domain
			// reference
			byte[] badWidth = richV010Bytes.clone();
			badWidth[(int) cat.relStart + 7] = 3; // nodeIdWidth = 3
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badWidth, arena));

			byte[] badEdgeWidth = richV010Bytes.clone();
			badEdgeWidth[(int) cat.relStart + 8] = 5; // edgeIndexWidth = 5
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badEdgeWidth, arena));

			byte[] badEncoding = richV010Bytes.clone();
			badEncoding[(int) cat.relStart + 6] = 99; // encodingId = 99
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badEncoding, arena));

			byte[] badDomRef = richV010Bytes.clone();
			ByteBuffer.wrap(badDomRef).order(ByteOrder.LITTLE_ENDIAN).putShort((int) cat.relStart + 2, (short) 99);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badDomRef, arena));
		}
	}

	@Test
	@DisplayName("5. Corrupt CSR & CSC topology invariants rejected")
	void testCorruptCsrAndCscTopologyRejected() {
		CatalogOffsets cat = locateCatalog(richV010Bytes);
		ByteBuffer baseBb = ByteBuffer.wrap(richV010Bytes).order(ByteOrder.LITTLE_ENDIAN);
		// Relation 1 is "likes" (User[4] -> Item[3]), located 128 bytes after relation
		// 0 ("follows")
		int likesRelPos = (int) cat.relStart + 128;
		long csrRowOff = baseBb.getLong(likesRelPos + 40);
		long csrColIdx = baseBb.getLong(likesRelPos + 56);
		long cscRowOff = baseBb.getLong(likesRelPos + 72);
		long cscColIdx = baseBb.getLong(likesRelPos + 88);

		try (Arena arena = Arena.ofConfined()) {
			// CSR row_offsets[0] != 0
			byte[] badCsrStart = richV010Bytes.clone();
			ByteBuffer.wrap(badCsrStart).order(ByteOrder.LITTLE_ENDIAN).putInt((int) csrRowOff, 1);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badCsrStart, arena));

			// CSR row_offsets[N] != edgeCount
			byte[] badCsrEnd = richV010Bytes.clone();
			ByteBuffer.wrap(badCsrEnd).order(ByteOrder.LITTLE_ENDIAN).putInt((int) csrRowOff + 4 * 4, 99);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badCsrEnd, arena));

			// Non-monotonic CSR row_offsets[2] < row_offsets[1]
			byte[] nonMonoCsr = richV010Bytes.clone();
			ByteBuffer.wrap(nonMonoCsr).order(ByteOrder.LITTLE_ENDIAN).putInt((int) csrRowOff + 4, 3);
			ByteBuffer.wrap(nonMonoCsr).order(ByteOrder.LITTLE_ENDIAN).putInt((int) csrRowOff + 8, 1);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(nonMonoCsr, arena));

			// Out-of-bounds CSR target dense ID (tgt = 3 >= Item.nodeCount = 3)
			byte[] oobTarget = richV010Bytes.clone();
			ByteBuffer.wrap(oobTarget).order(ByteOrder.LITTLE_ENDIAN).putInt((int) csrColIdx, 3);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(oobTarget, arena));

			// Non-monotonic CSC row_offsets
			byte[] nonMonoCsc = richV010Bytes.clone();
			ByteBuffer.wrap(nonMonoCsc).order(ByteOrder.LITTLE_ENDIAN).putInt((int) cscRowOff + 4, 3);
			ByteBuffer.wrap(nonMonoCsc).order(ByteOrder.LITTLE_ENDIAN).putInt((int) cscRowOff + 8, 1);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(nonMonoCsc, arena));

			// Out-of-bounds CSC source dense ID (src = 4 >= User.nodeCount = 4)
			byte[] oobCscSrc = richV010Bytes.clone();
			ByteBuffer.wrap(oobCscSrc).order(ByteOrder.LITTLE_ENDIAN).putInt((int) cscColIdx, 4);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(oobCscSrc, arena));

			// Raw COO (encodingId = 6): undersized csrRowOffBytes (edgeCount=4 -> needs
			// 16B, patch to 12B)
			byte[] cooShort = richV010Bytes.clone();
			cooShort[likesRelPos + 6] = 6; // encodingId = 6 (TPU_BCOO / COO)
			ByteBuffer.wrap(cooShort).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 32, 0L); // disable CSC
			ByteBuffer.wrap(cooShort).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 72, 0L);
			ByteBuffer.wrap(cooShort).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 80, 0L);
			ByteBuffer.wrap(cooShort).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 88, 0L);
			ByteBuffer.wrap(cooShort).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 96, 0L);
			ByteBuffer.wrap(cooShort).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 48, 12L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(cooShort, arena));

			// Raw COO (encodingId = 6): out-of-bounds source node ID (src = 4 >=
			// User.nodeCount = 4)
			byte[] cooOobSrc = richV010Bytes.clone();
			cooOobSrc[likesRelPos + 6] = 6;
			ByteBuffer.wrap(cooOobSrc).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 32, 0L);
			ByteBuffer.wrap(cooOobSrc).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 72, 0L);
			ByteBuffer.wrap(cooOobSrc).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 80, 0L);
			ByteBuffer.wrap(cooOobSrc).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 88, 0L);
			ByteBuffer.wrap(cooOobSrc).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 96, 0L);
			ByteBuffer.wrap(cooOobSrc).order(ByteOrder.LITTLE_ENDIAN).putLong(likesRelPos + 48, 16L);
			ByteBuffer.wrap(cooOobSrc).order(ByteOrder.LITTLE_ENDIAN).putInt((int) csrRowOff, 4);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(cooOobSrc, arena));
		}
	}

	@Test
	@DisplayName("6. Corrupt Nullable, Fixed-Width, and Variable-Length Attributes + invalid UTF-8 in VarString resolveKey")
	void testCorruptAttributesAndVarStringKey() {
		CatalogOffsets cat = locateCatalog(richV010Bytes);
		// Domain 0 has 3 attributes starting at domStart + 32:
		// Attr 0 (domStart + 32): user_id (VarString 0x0B)
		// Attr 1 (domStart + 76): age (INT64 0x04)
		// Attr 2 (domStart + 120): score (Nullable FLOAT32 0x86)
		long attr0Pos = cat.domStart + 32L;
		long attr1Pos = cat.domStart + 76L;
		long attr2Pos = cat.domStart + 120L;
		ByteBuffer baseBb = ByteBuffer.wrap(richV010Bytes).order(ByteOrder.LITTLE_ENDIAN);
		long varStrDataOff = baseBb.getLong((int) attr0Pos + 12);
		long varStrOffsOff = baseBb.getLong((int) attr0Pos + 28);

		try (Arena arena = Arena.ofConfined()) {
			// Out-of-bounds keyAttrIndex on Domain 0 (attrCount = 3, set keyAttrIndex = 3)
			byte[] badKeyAttrIdx = richV010Bytes.clone();
			ByteBuffer.wrap(badKeyAttrIdx).order(ByteOrder.LITTLE_ENDIAN).putShort((int) cat.domStart + 18, (short) 3);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badKeyAttrIdx, arena));

			// Nullable attribute dataBytes < paddedBitmapBytes (128)
			byte[] smallNullable = richV010Bytes.clone();
			ByteBuffer.wrap(smallNullable).order(ByteOrder.LITTLE_ENDIAN).putLong((int) attr2Pos + 20, 64L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(smallNullable, arena));

			// Fixed-width INT64 attribute undersized: dataBytes = 24 < 4 * 8 = 32
			byte[] smallFixed = richV010Bytes.clone();
			ByteBuffer.wrap(smallFixed).order(ByteOrder.LITTLE_ENDIAN).putLong((int) attr1Pos + 20, 24L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(smallFixed, arena));

			// VarString undersized offsBytes: 12 < (4 + 1) * 4 = 20
			byte[] smallVarOffs = richV010Bytes.clone();
			ByteBuffer.wrap(smallVarOffs).order(ByteOrder.LITTLE_ENDIAN).putLong((int) attr0Pos + 36, 12L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(smallVarOffs, arena));

			// VarString offsets[0] != 0
			byte[] nonZeroStartOff = richV010Bytes.clone();
			ByteBuffer.wrap(nonZeroStartOff).order(ByteOrder.LITTLE_ENDIAN).putInt((int) varStrOffsOff, 1);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(nonZeroStartOff, arena));

			// Non-monotonic VarString offsets (offsets[1] = 5 > offsets[2] = 4)
			byte[] nonMonoVarOff = richV010Bytes.clone();
			ByteBuffer.wrap(nonMonoVarOff).order(ByteOrder.LITTLE_ENDIAN).putInt((int) varStrOffsOff + 4, 5);
			ByteBuffer.wrap(nonMonoVarOff).order(ByteOrder.LITTLE_ENDIAN).putInt((int) varStrOffsOff + 8, 4);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(nonMonoVarOff, arena));

			// VarString offsets[N] > dataBytes
			byte[] overflowVarOff = richV010Bytes.clone();
			ByteBuffer.wrap(overflowVarOff).order(ByteOrder.LITTLE_ENDIAN).putInt((int) varStrOffsOff + 16, 999);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(overflowVarOff, arena));

			// Invalid UTF-8 in VarString primary key payload -> resolveKey must reject with
			// IllegalArgumentException!
			byte[] badKeyUtf8 = richV010Bytes.clone();
			badKeyUtf8[(int) varStrDataOff] = (byte) 0xFF;
			badKeyUtf8[(int) varStrDataOff + 1] = (byte) 0xFE;
			BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(badKeyUtf8, arena);
			BinarySnapshotLoader.LoadedDomain userDom = loaded.getDomain(0);
			assertThrows(IllegalArgumentException.class, () -> userDom.resolveKey(0));
			assertThrows(IllegalArgumentException.class, () -> userDom.resolveDenseId("u0"));
		}
	}

	@Test
	@DisplayName("7. Virtual Super-Relation attacks: self-cycle, 2-hop cycle, 3-hop cycle, nonexistent child ID, odd overflow csrColIdxBytes")
	void testVirtualSuperRelationAttacks() {
		try (Arena arena = Arena.ofConfined()) {
			// 7a. Direct self-cycle: R0 -> R0
			byte[] selfCycleBytes = buildVirtualTestSnapshot(arena, new int[]{0}, null, null);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(selfCycleBytes, arena));

			// 7b. 2-hop cycle: R0 -> R1 -> R0
			byte[] twoHopBytes = buildVirtualTestSnapshot(arena, new int[]{1}, new int[]{0}, null);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(twoHopBytes, arena));

			// 7c. 3-hop cycle: R0 -> R1 -> R2 -> R0
			byte[] threeHopBytes = buildVirtualTestSnapshot(arena, new int[]{1}, new int[]{2}, new int[]{0});
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(threeHopBytes, arena));

			// 7d. Nonexistent child relation ID: R0 -> R99
			byte[] missingChildBytes = buildVirtualTestSnapshot(arena, new int[]{99}, null, null);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(missingChildBytes, arena));

			// 7e. Odd csrColIdxBytes on overflow virtual component list (11 components ->
			// overflow list in csrColIdx)
			byte[] validOverflow = buildVirtualTestSnapshot(arena, new int[]{1, 2, 1, 2, 1, 2, 1, 2, 1, 2, 1}, null,
					null);
			// Verify valid 11-component virtual DAG loads cleanly first
			BinarySnapshotLoader.LoadedSnapshot okSnap = BinarySnapshotLoader.loadSnapshot(validOverflow, arena);
			assertEquals(11, okSnap.graph().getRelationSnapshot("r0").getComponentRelations().length);

			// Now patch R0's csrColIdxBytes (+64 in R0 directory entry) to an odd number
			// (23 bytes)
			CatalogOffsets vCat = locateCatalog(validOverflow);
			byte[] oddOverflow = validOverflow.clone();
			ByteBuffer.wrap(oddOverflow).order(ByteOrder.LITTLE_ENDIAN).putLong((int) vCat.relStart + 64, 23L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(oddOverflow, arena));
		}
	}

	private static byte[] buildVirtualTestSnapshot(Arena arena, int[] r0Comps, int[] r1Comps, int[] r2Comps) {
		RelationSnapshot r0 = new RelationSnapshot(arena, 2, 0, MemorySegment.NULL, MemorySegment.NULL);
		RelationSnapshot r1 = new RelationSnapshot(arena, 2, 0, MemorySegment.NULL, MemorySegment.NULL);
		RelationSnapshot r2 = new RelationSnapshot(arena, 2, 0, MemorySegment.NULL, MemorySegment.NULL);
		Map<String, RelationSnapshot> rels = new LinkedHashMap<>();
		rels.put("r0", r0);
		rels.put("r1", r1);
		rels.put("r2", r2);
		GraphSnapshot g = new GraphSnapshot(arena, rels);
		byte[] base = new DefaultSnapshotBuilder().withVersion((short) 10).withDomain(0, "Node", (byte) 0x03, 2L)
				.withRelationDomain("r0", 0, 0).withRelationDomain("r1", 0, 0).withRelationDomain("r2", 0, 0)
				.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(BinarySnapshotLoader.SNAPSHOT_MAGIC, (short) 10,
						g, Map.of(), Map.of(), Map.of(), Map.of()));

		CatalogOffsets cat = locateCatalog(base);
		int[][] allComps = new int[][]{r0Comps, r1Comps, r2Comps};
		// Strip trailing metadata footer so any appended 128B-aligned overflow array
		// stays cleanly at EOF
		ByteBuffer baseBb = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN);
		long footerLen = baseBb.getLong(base.length - 16);
		int bodyLen = (int) (base.length - footerLen);
		int alignedBodyLen = (bodyLen + 127) & ~127;

		int extraBytes = 0;
		for (int[] comps : allComps) {
			if (comps != null && comps.length > 10) {
				extraBytes += ((comps.length * 2 + 127) & ~127);
			}
		}

		byte[] out = new byte[alignedBodyLen + extraBytes];
		System.arraycopy(base, 0, out, 0, bodyLen);
		ByteBuffer outBb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);

		int nextOverflowOff = alignedBodyLen;
		for (int idx = 0; idx < 3; idx++) {
			int[] comps = allComps[idx];
			if (comps == null) {
				continue;
			}
			int relPos = (int) cat.relStart + idx * 128;
			if (comps.length <= 10) {
				patchInlineVirtualEntry(out, relPos, comps);
			} else {
				outBb.putLong(relPos + 32, 0x04L);
				outBb.putLong(relPos + 56, nextOverflowOff);
				outBb.putLong(relPos + 64, comps.length * 2L);
				outBb.putShort(relPos + 106, (short) comps.length);
				outBb.putShort(relPos + 108, (short) 0xFFFF);
				for (int k = 0; k < comps.length; k++) {
					outBb.putShort(nextOverflowOff + k * 2, (short) comps[k]);
				}
				nextOverflowOff += ((comps.length * 2 + 127) & ~127);
			}
		}
		return out;
	}

	@Test
	@DisplayName("8. Footer Metadata attacks: vlen=-1 (NegativeArraySizeException regression), vlen=MAX_VALUE, count=-5, count=1_000_000, oversized stats integer")
	void testFooterMetadataAttacks() {
		try (Arena arena = Arena.ofConfined()) {
			// Locate trailer at EOF - 16
			ByteBuffer baseBb = ByteBuffer.wrap(richV010Bytes).order(ByteOrder.LITTLE_ENDIAN);
			long footerLen = baseBb.getLong(richV010Bytes.length - 16);
			int metaStart = (int) (richV010Bytes.length - footerLen);
			int klen0 = Short.toUnsignedInt(baseBb.getShort(metaStart + 4));
			int vlen0Pos = metaStart + 4 + 2 + klen0;

			// vlen = -1 -> previously threw NegativeArraySizeException!
			byte[] negVlen = richV010Bytes.clone();
			ByteBuffer.wrap(negVlen).order(ByteOrder.LITTLE_ENDIAN).putInt(vlen0Pos, -1);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(negVlen, arena));

			// vlen = Integer.MAX_VALUE
			byte[] maxVlen = richV010Bytes.clone();
			ByteBuffer.wrap(maxVlen).order(ByteOrder.LITTLE_ENDIAN).putInt(vlen0Pos, Integer.MAX_VALUE);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(maxVlen, arena));

			// count = -5
			byte[] negCount = richV010Bytes.clone();
			ByteBuffer.wrap(negCount).order(ByteOrder.LITTLE_ENDIAN).putInt(metaStart, -5);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(negCount, arena));

			// count = 1_000_000 on small footer buffer
			byte[] hugeCount = richV010Bytes.clone();
			ByteBuffer.wrap(hugeCount).order(ByteOrder.LITTLE_ENDIAN).putInt(metaStart, 1_000_000);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(hugeCount, arena));

			// Oversized integer in impulse.stats.relation.0.out_degree JSON ->
			// getStatistics() must NOT throw NumberFormatException
			RelationSnapshot rs = new RelationSnapshot(arena, 4, 4, new int[]{0, 1, 2, 3, 4}, new int[]{1, 2, 3, 0});
			rs.injectMetadata(
					"{\"max\":99999999999999999999999999,\"p50\":99999999999999,\"zero_count\":99999999999999}");
			assertDoesNotThrow(rs::getStatistics);
			assertEquals(Integer.MAX_VALUE, rs.getStatistics().getMaxDegree());
		}
	}

	@Test
	@DisplayName("9. Secondary Index Directory attacks: nonexistent domId/relId, out-of-bounds attrIdx, unaligned dataOff, unknown idxType")
	void testSecondaryIndexDirectoryAttacks() throws Exception {
		try (Arena arena = Arena.ofConfined()) {
			byte[] validWithIndex = buildSnapshotWithCustomIndex(0, 0xFFFF, 1, (byte) 1, 0L, 0L);
			BinarySnapshotLoader.LoadedSnapshot ok = BinarySnapshotLoader.loadSnapshot(validWithIndex, arena);
			assertEquals(1, ok.indices().size());

			// Nonexistent domId (relId == 0xFFFF, domId = 99)
			byte[] badDom = buildSnapshotWithCustomIndex(99, 0xFFFF, 0, (byte) 1, 0L, 0L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badDom, arena));

			// Nonexistent relId (relId = 77)
			byte[] badRel = buildSnapshotWithCustomIndex(0, 77, 0, (byte) 1, 0L, 0L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badRel, arena));

			// Out-of-bounds domain attrIdx (Domain 0 has 3 attrs -> attrIdx = 3)
			byte[] badDomAttr = buildSnapshotWithCustomIndex(0, 0xFFFF, 3, (byte) 1, 0L, 0L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badDomAttr, arena));

			// Out-of-bounds relation attrIdx (Relation 0 has 1 attr -> attrIdx = 2)
			byte[] badRelAttr = buildSnapshotWithCustomIndex(0, 0, 2, (byte) 1, 0L, 0L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badRelAttr, arena));

			// Unaligned dataOff (4100)
			byte[] unalignedIdx = buildSnapshotWithCustomIndex(0, 0xFFFF, 1, (byte) 1, 4100L, 128L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(unalignedIdx, arena));

			// Unknown idxType (0 and 9)
			byte[] badType0 = buildSnapshotWithCustomIndex(0, 0xFFFF, 1, (byte) 0, 0L, 0L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badType0, arena));
			byte[] badType9 = buildSnapshotWithCustomIndex(0, 0xFFFF, 1, (byte) 9, 0L, 0L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badType9, arena));
		}
	}

	/**
	 * Injects a 64-byte Secondary Index Directory entry into {@code richV010Bytes}
	 * at {@code idxStart} (which lies inside the 4KB-padded Section 2 directory
	 * region before data payloads) and sets {@code index_count = 1}.
	 */
	private byte[] buildSnapshotWithCustomIndex(int domId, int relId, int attrIdx, byte idxType, long dataOff,
			long dataBytes) throws Exception {
		CatalogOffsets cat = locateCatalog(richV010Bytes);
		byte[] snap = richV010Bytes.clone();
		ByteBuffer bb = ByteBuffer.wrap(snap).order(ByteOrder.LITTLE_ENDIAN);
		bb.putShort(0x40, (short) 1); // index_count = 1 (at byte 64, after CRC-16 range 0..61)

		int p = (int) cat.idxStart;
		bb.putInt(p, 0); // index_id
		bb.putShort(p + 4, (short) domId);
		bb.putShort(p + 6, (short) relId);
		bb.putShort(p + 8, (short) attrIdx);
		bb.put(p + 10, idxType);
		bb.put(p + 11, (byte) 0);
		bb.putInt(p + 12, 0); // name_offset = ""
		bb.putLong(p + 16, dataOff);
		bb.putLong(p + 24, dataBytes);
		bb.putLong(p + 32, 0L);
		return snap;
	}

	@Test
	@DisplayName("Defensive RelationSnapshot public constructor bounds safety against unvalidated segments")
	void testDirectRelationSnapshotDefensiveBounds() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment tinyRowOff = arena.allocate(12, 4); // too small for nodeCount=4 (needs 20B)
			// Offsets: [0, 5, 2] -> non-monotonic AND exceeds tinyColTargets!
			tinyRowOff.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 0, 0);
			tinyRowOff.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 1, 5);
			tinyRowOff.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, 2, 2);
			MemorySegment tinyColTargets = arena.allocate(8, 4); // only 2 ints, while node 0 claims end=5!
			MemorySegment tinyAttr = arena.allocate(4, 4);

			RelationSnapshot unvalidated = new RelationSnapshot(arena, 4, 5, tinyRowOff, tinyColTargets, tinyRowOff,
					tinyColTargets);
			OffHeapBitSet bs = new OffHeapBitSet(arena, 16);

			for (int u = 0; u < 4; u++) {
				final int node = u;
				assertDoesNotThrow(() -> unvalidated.getDegree(node));
				assertDoesNotThrow(() -> unvalidated.getTargets(node));
				assertDoesNotThrow(() -> unvalidated.getInDegree(node));
				assertDoesNotThrow(() -> unvalidated.getInTargets(node));
				assertDoesNotThrow(() -> unvalidated.copyTargetsSimd(node, bs));
				assertDoesNotThrow(() -> unvalidated.copyInTargetsSimd(node, bs));
				assertDoesNotThrow(() -> unvalidated.copyTargetsSimdFilteredFloat(node, tinyAttr, 0.5f,
						org.impulsegraph.api.RelationSnapshot.CMP_GT, bs));
			}
		}
	}

	@Test
	@DisplayName("Legacy v2.4 branch hardening: truncated entries, overflowing nameOff+nameLen, negative/overflow counts")
	void testLegacyV24Hardening() throws Exception {
		// Build minimal synthetic v2.4 buffer (header + 1 domain + 1 relation)
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteBuffer hdr = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		hdr.putInt(0, BinarySnapshotLoader.SNAPSHOT_MAGIC);
		hdr.putShort(4, (short) 0x0204);
		hdr.putInt(6, 64); // dataOffset = 64
		hdr.putShort(10, (short) 1); // domainCount = 1
		hdr.putShort(12, (short) 1); // relationCount = 1
		out.write(hdr.array());

		ByteBuffer dom = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
		dom.putShort(0, (short) 0);
		dom.put(2, (byte) 3);
		dom.putLong(4, 2L); // dNodeCount = 2
		dom.putInt(44, 0); // nameOff = 0
		dom.putShort(48, (short) 0); // nameLen = 0
		out.write(dom.array());

		ByteBuffer rel = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
		rel.putShort(0, (short) 0);
		rel.putShort(2, (short) 0);
		rel.put(4, (byte) 0);
		rel.putLong(5, 2L); // nodeCount
		rel.putLong(13, 0L); // edgeCount
		out.write(rel.array());

		byte[] validLegacy = out.toByteArray();
		try (Arena arena = Arena.ofConfined()) {
			assertNotNull(BinarySnapshotLoader.loadSnapshot(validLegacy, arena));

			// Overflowing nameOff = Integer.MAX_VALUE, nameLen = 10 -> signed int overflow
			// in 32-bit!
			byte[] badName = validLegacy.clone();
			ByteBuffer.wrap(badName).order(ByteOrder.LITTLE_ENDIAN).putInt(64 + 44, Integer.MAX_VALUE);
			ByteBuffer.wrap(badName).order(ByteOrder.LITTLE_ENDIAN).putShort(64 + 48, (short) 10);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badName, arena));

			// dNodeCount > Integer.MAX_VALUE
			byte[] badDomNodes = validLegacy.clone();
			ByteBuffer.wrap(badDomNodes).order(ByteOrder.LITTLE_ENDIAN).putLong(64 + 4, 0x1_0000_0001L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(badDomNodes, arena));

			// Truncated legacy relation entry
			byte[] truncLegacy = java.util.Arrays.copyOf(validLegacy, 64 + 64 + 40);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(truncLegacy, arena));
		}
	}

	@Test
	@DisplayName("10. SQLite-style 500-iteration deterministic byte-corruption fuzz loop (zero non-IAE crashes)")
	void testDeterministic500IterationByteCorruptionFuzzLoop() {
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseTestControl.setAuditEnabled(true);
		ImpulseTestControl.setNeverCorrupt(false);

		byte[] baseWithIndex;
		try {
			baseWithIndex = buildSnapshotWithCustomIndex(0, 0xFFFF, 1, (byte) 1, 0L, 0L);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		CatalogOffsets fuzzCat = locateCatalog(baseWithIndex);
		int activeDirEnd = (int) fuzzCat.idxStart + 32;
		int footerStart = baseWithIndex.length - (int) ByteBuffer.wrap(baseWithIndex).order(ByteOrder.LITTLE_ENDIAN)
				.getLong(baseWithIndex.length - 16);
		int[][] activeRanges = new int[][]{{0, 66}, // Header
				{(int) fuzzCat.dirOffset, activeDirEnd}, // String pool + Domain/Relation/Index catalogs
				{footerStart, baseWithIndex.length} // Footer metadata + trailer
		};

		SplittableRandom rng = new SplittableRandom(0x494D5053_2026L);
		int rejectedCount = 0;
		int acceptedCount = 0;

		for (int iter = 0; iter < 500; iter++) {
			final int currentIter = iter;
			byte[] mutated = baseWithIndex.clone();
			int mutationMode = rng.nextInt(5);

			if (mutationMode == 0) {
				// Random truncation across header, directory, payload, and footer boundaries
				int newLen = rng.nextInt(mutated.length);
				mutated = java.util.Arrays.copyOf(mutated, newLen);
			} else {
				int numMutations = 1 + rng.nextInt(4);
				for (int m = 0; m < numMutations; m++) {
					int pos;
					if (mutationMode <= 3) {
						int[] range = activeRanges[rng.nextInt(activeRanges.length)];
						pos = range[0] + rng.nextInt(Math.max(1, range[1] - range[0]));
					} else {
						pos = rng.nextInt(mutated.length);
					}
					int kind = rng.nextInt(4);
					if (kind == 0) {
						mutated[pos] ^= (byte) (1 << rng.nextInt(8));
					} else if (kind == 1) {
						mutated[pos] = (byte) (rng.nextBoolean() ? 0xFF : 0x80);
					} else if (kind == 2 && pos + 4 <= mutated.length) {
						int[] interesting32 = new int[]{-1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0x7FFF_FFFE};
						ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN).putInt(pos,
								interesting32[rng.nextInt(interesting32.length)]);
					} else if (pos + 8 <= mutated.length) {
						long[] interesting64 = new long[]{-1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x1_0000_0002L,
								Long.MAX_VALUE - 32L};
						ByteBuffer.wrap(mutated).order(ByteOrder.LITTLE_ENDIAN).putLong(pos,
								interesting64[rng.nextInt(interesting64.length)]);
					}
				}
			}

			// Recompute header CRC-16 on half of all iterations so parser internals beyond
			// header CRC are exercised
			if ((iter & 1) == 0 && mutated.length >= 64) {
				recomputeHeaderCrc(mutated);
			}

			try (Arena arena = Arena.ofConfined()) {
				MemorySegment seg = MemorySegmentHexPatcher.cloneBytes(arena, mutated);
				BinarySnapshotLoader.LoadedSnapshot snap = BinarySnapshotLoader.loadSnapshot(seg, arena, true);
				acceptedCount++;

				// Exercise post-load accessors: degree, targets, in-targets, key lookup,
				// statistics
				for (BinarySnapshotLoader.LoadedDomain dom : snap.domainsById().values()) {
					long checkNodes = Math.min(dom.nodeCount(), 8L);
					for (long id = 0; id < checkNodes; id++) {
						dom.resolveKey(id);
					}
				}
				for (org.impulsegraph.api.RelationSnapshot rel : snap.graph().getAllRelationSnapshots().values()) {
					rel.getStatistics();
					int checkN = Math.min(rel.getNodeCount(), 8);
					for (int u = 0; u < checkN; u++) {
						rel.getDegree(u);
						rel.getTargets(u);
						rel.getInDegree(u);
						rel.getInTargets(u);
					}
				}
			} catch (IllegalArgumentException expectedRejection) {
				rejectedCount++;
			} catch (Throwable unexpected) {
				fail("Iteration " + currentIter + " threw forbidden exception " + unexpected.getClass().getName() + ": "
						+ unexpected.getMessage(), unexpected);
			}
		}

		assertTrue(rejectedCount > 250, "Expected majority of adversarial mutations to be rejected, rejected="
				+ rejectedCount + ", accepted=" + acceptedCount);
	}
}
