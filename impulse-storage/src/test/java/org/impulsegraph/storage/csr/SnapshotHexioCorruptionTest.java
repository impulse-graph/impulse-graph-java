package org.impulsegraph.storage.csr;

import org.impulsegraph.api.util.ImpulseAssert;
import org.impulsegraph.api.util.ImpulseTestControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite {@code test_hexio.c}-style off-heap binary snapshot corruption and
 * structural audit test suite for {@link BinarySnapshotLoader}.
 *
 * <p>
 * Builds a valid v0.9.0 {@code .imps} snapshot in memory via
 * {@link DefaultSnapshotBuilder} and applies targeted byte/hex mutations via
 * {@link MemorySegmentHexPatcher} without writing corrupt files to disk
 * (AGENTS.md Rules 3.6 &amp; 3.17).
 */
class SnapshotHexioCorruptionTest {

	private byte[] validSnapshotBytes;

	@BeforeEach
	void setUp() {
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseTestControl.setAuditEnabled(true);
		ImpulseTestControl.setNeverCorrupt(false);
		ImpulseAssert.resetCounters();
		try (Arena arena = Arena.ofConfined()) {
			// 4 nodes: 0 -> [1, 2], 1 -> [2, 3], 2 -> [3], 3 -> [] (5 edges)
			int[] offsets = new int[]{0, 2, 4, 5, 5};
			int[] targets = new int[]{1, 2, 2, 3, 3};
			RelationSnapshot follows = new RelationSnapshot(arena, 4, 5, offsets, targets);
			GraphSnapshot graph = new GraphSnapshot(arena, Map.of("follows", follows));

			DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder().withDomain(0, "User", (byte) 0x03, 4L)
					.withRelationDomain("follows", 0, 0).withCsc(true);
			validSnapshotBytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
					BinarySnapshotLoader.SNAPSHOT_MAGIC, (short) 9, graph, Map.of(), Map.of(), Map.of(), Map.of()));
		}
	}

	@AfterEach
	void tearDown() {
		ImpulseTestControl.setNeverCorrupt(false);
	}

	@Test
	@DisplayName("Valid v0.9.0 snapshot passes full structural audit under neverCorrupt=true")
	void testValidSnapshotPassesAuditUnderNeverCorrupt() {
		ImpulseTestControl.setNeverCorrupt(true);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = MemorySegmentHexPatcher.cloneBytes(arena, validSnapshotBytes);
			assertEquals("53504D49", MemorySegmentHexPatcher.readHex(seg, 0, 4));
			BinarySnapshotLoader.LoadedSnapshot snap = BinarySnapshotLoader.loadSnapshot(seg, arena, true);
			assertNotNull(snap);
			assertEquals(1, snap.domainCount());
			assertEquals(1, snap.relationCount());
			BinarySnapshotLoader.LoadedDomain dom = snap.getDomain(0);
			assertNotNull(dom);
			assertEquals("User", dom.name());
			assertEquals(4L, dom.nodeCount());
		}
	}

	@Test
	@DisplayName("Hexio magic corruption rejected cleanly (neverCorrupt=false) and traps (neverCorrupt=true)")
	void testMagicHeaderCorruption() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = MemorySegmentHexPatcher.cloneBytes(arena, validSnapshotBytes);
			MemorySegmentHexPatcher.writeHex(seg, 0, "DEADBEEF");
			assertEquals("DEADBEEF", MemorySegmentHexPatcher.readHex(seg, 0, 4));

			ImpulseTestControl.setNeverCorrupt(false);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(seg, arena, true));

			if (ImpulseAssert.isAssertionsEnabled()) {
				ImpulseTestControl.setNeverCorrupt(true);
				assertThrows(AssertionError.class, () -> BinarySnapshotLoader.loadSnapshot(seg, arena, true));
			}
		}
	}

	@Test
	@DisplayName("Hexio protocol version corruption rejected")
	void testUnsupportedVersionCorruption() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = MemorySegmentHexPatcher.cloneBytes(arena, validSnapshotBytes);
			MemorySegmentHexPatcher.writeU16Le(seg, 4, 0x0099);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(seg, arena, true));
		}
	}

	@Test
	@DisplayName("Truncated snapshot buffers (< 58 bytes and boundary 58 bytes) rejected")
	void testTruncatedHeaderRejected() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = MemorySegmentHexPatcher.cloneBytes(arena, validSnapshotBytes);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(seg.asSlice(0, 32), arena, true));
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(seg.asSlice(0, 57), arena, true));
			// Boundary 58-byte slice triggers testcase(segSize == 58) probe and fails on
			// directory offset
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(seg.asSlice(0, 58), arena, true));
			assertTrue(ImpulseTestControl.testcaseHits() > 0);
		}
	}

	@Test
	@DisplayName("Corrupt footerDirectoryOffset out of bounds rejected")
	void testCorruptFooterDirectoryOffset() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment seg = MemorySegmentHexPatcher.cloneBytes(arena, validSnapshotBytes);
			MemorySegmentHexPatcher.writeU64Le(seg, 30, seg.byteSize() + 4096L);
			assertThrows(IllegalArgumentException.class, () -> BinarySnapshotLoader.loadSnapshot(seg, arena, true));
		}
	}

	@Test
	@DisplayName("Corrupt Section 2 string pool (non-null byte 0, overflowing size, unterminated string)")
	void testCorruptStringPool() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment base = MemorySegmentHexPatcher.cloneBytes(arena, validSnapshotBytes);
			long dirOffset = base.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 30);
			if (dirOffset <= 0) {
				dirOffset = base.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 6);
			}

			// 1. Overflowing string_table_bytes
			MemorySegment segOverflow = MemorySegmentHexPatcher.cloneSegment(arena, base);
			MemorySegmentHexPatcher.writeU32Le(segOverflow, dirOffset, (int) segOverflow.byteSize() + 100);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segOverflow, arena, true));

			// 2. Non-zero byte 0 of string pool
			MemorySegment segByte0 = MemorySegmentHexPatcher.cloneSegment(arena, base);
			MemorySegmentHexPatcher.writeHex(segByte0, dirOffset + 4, "41");
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segByte0, arena, true));

			// 3. Unterminated string in string pool (wipe all non-zero bytes after offset 1
			// to 'X')
			int strPoolBytes = base.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), dirOffset);
			MemorySegment segUnterm = MemorySegmentHexPatcher.cloneSegment(arena, base);
			for (int i = 1; i < strPoolBytes; i++) {
				segUnterm.set(ValueLayout.JAVA_BYTE, dirOffset + 4 + i, (byte) 'X');
			}
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segUnterm, arena, true));
		}
	}

	@Test
	@DisplayName("Corrupt CSR row_offsets alignment, bounds, monotonicity, and target dense IDs rejected")
	void testCorruptCsrSectionAndTopologyInvariants() {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment base = MemorySegmentHexPatcher.cloneBytes(arena, validSnapshotBytes);
			BinarySnapshotLoader.LoadedSnapshot valid = BinarySnapshotLoader.loadSnapshot(base, arena, true);
			BinarySnapshotLoader.LoadedRelation rel = valid.getRelation(0);
			assertNotNull(rel);
			long csrRowOff = rel.csrRowOffOffset();
			long csrColIdx = rel.csrColIdxOffset();
			assertTrue(csrRowOff > 0 && (csrRowOff & 127L) == 0L);
			assertTrue(csrColIdx > 0 && (csrColIdx & 127L) == 0L);

			// 1. CSR row_offsets[0] != 0
			MemorySegment segBadStart = MemorySegmentHexPatcher.cloneSegment(arena, base);
			MemorySegmentHexPatcher.writeU32Le(segBadStart, csrRowOff, 1);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segBadStart, arena, true));

			// 2. CSR row_offsets[nodeCount] != edgeCount
			MemorySegment segBadEnd = MemorySegmentHexPatcher.cloneSegment(arena, base);
			MemorySegmentHexPatcher.writeU32Le(segBadEnd, csrRowOff + 4L * 4L, 99);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segBadEnd, arena, true));

			// 3. Non-monotonic CSR row_offsets (offsets[1] = 4 > offsets[2] = 2)
			MemorySegment segNonMono = MemorySegmentHexPatcher.cloneSegment(arena, base);
			MemorySegmentHexPatcher.writeU32Le(segNonMono, csrRowOff + 1L * 4L, 4);
			MemorySegmentHexPatcher.writeU32Le(segNonMono, csrRowOff + 2L * 4L, 2);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segNonMono, arena, true));

			// 4. Out-of-bounds target dense ID (target = 99 >= tgtDomainNodeCount = 4)
			MemorySegment segBadTarget = MemorySegmentHexPatcher.cloneSegment(arena, base);
			MemorySegmentHexPatcher.writeU32Le(segBadTarget, csrColIdx, 99);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segBadTarget, arena, true));

			// 5. Corrupt Relation Directory entry: unaligned csrRowOffOffset (csrRowOff +
			// 1)
			long dirOffset = base.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 30);
			if (dirOffset <= 0) {
				dirOffset = base.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 6);
			}
			int strPoolBytes = base.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), dirOffset);
			long afterPool = dirOffset + 4 + strPoolBytes;
			long domCatalogStart = (afterPool + 127L) & ~127L;
			long relCatalogStart = ((domCatalogStart + 16L) + 127L) & ~127L;
			// In Relation Directory Entry: csrRowOffOffset is at offset +40 bytes into the
			// 128-byte entry
			MemorySegment segUnaligned = MemorySegmentHexPatcher.cloneSegment(arena, base);
			MemorySegmentHexPatcher.writeU64Le(segUnaligned, relCatalogStart + 40L, csrRowOff + 1L);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segUnaligned, arena, true));

			// 6. Corrupt Relation Directory entry: overflowing csrRowOffBytes
			MemorySegment segOverflowSec = MemorySegmentHexPatcher.cloneSegment(arena, base);
			MemorySegmentHexPatcher.writeU64Le(segOverflowSec, relCatalogStart + 48L,
					segOverflowSec.byteSize() + 1024L);
			assertThrows(IllegalArgumentException.class,
					() -> BinarySnapshotLoader.loadSnapshot(segOverflowSec, arena, true));
		}
	}
}
