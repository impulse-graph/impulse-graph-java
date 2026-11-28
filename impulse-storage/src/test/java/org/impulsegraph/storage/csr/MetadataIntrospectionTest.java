package org.impulsegraph.storage.csr;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.meta.AttributeMeta;
import org.impulsegraph.api.meta.DomainMeta;
import org.impulsegraph.api.meta.IndexMeta;
import org.impulsegraph.api.meta.SnapshotHeader;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataIntrospectionTest {

	private static final byte TYPE_INT64 = 0x04;
	private static final byte TYPE_DECIMAL64 = 0x15;
	private static final byte TYPE_DECIMAL64_NULLABLE = (byte) (0x15 | 0x80);

	@Test
	void testHeaderDomainsAttributesIndicesMetadataAndSha256() throws Exception {
		Path tmp = Files.createTempFile("java-metadata-introspection", ".imps");
		try {
			ByteBuffer balBuf = ByteBuffer.allocate(4 * 8).order(ByteOrder.LITTLE_ENDIAN);
			balBuf.putLong(12345L).putLong(-99999L).putLong(0L).putLong(500000000L);

			ByteBuffer notBuf = ByteBuffer.allocate(4 * 8).order(ByteOrder.LITTLE_ENDIAN);
			notBuf.putLong(1000L).putLong(0L).putLong(3000L).putLong(4000L);
			byte[] notValidity = new byte[128];
			notValidity[0] = (byte) 0x0D; // nodes 0, 2, 3 valid; node 1 null

			ByteBuffer tierBuf = ByteBuffer.allocate(4 * 8).order(ByteOrder.LITTLE_ENDIAN);
			tierBuf.putLong(1L).putLong(2L).putLong(3L).putLong(4L);

			byte[] snapshotBytes;
			try (Arena buildArena = Arena.ofConfined()) {
				java.lang.foreign.MemorySegment rowOff = buildArena.allocate(5L * 4L, 4L);
				int[] ro = {0, 1, 2, 2, 2};
				for (int i = 0; i < ro.length; i++) {
					rowOff.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i, ro[i]);
				}
				java.lang.foreign.MemorySegment colTgt = buildArena.allocate(2L * 4L, 4L);
				colTgt.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 0, 1);
				colTgt.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, 1, 2);
				java.lang.foreign.MemorySegment edgeWeight = buildArena.allocate(2L * 4L, 4L);
				edgeWeight.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0, 1.5f);
				edgeWeight.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, 1, 2.5f);
				RelationSnapshot rel = new RelationSnapshot(buildArena, 4, 2, rowOff, colTgt, List.of(edgeWeight));
				rel.setAttributeNames(List.of("weight"));
				rel.setAttributeTypeCodes(List.of((byte) 0x06));
				GraphSnapshot buildGraph = new GraphSnapshot(buildArena, Map.of("transfer", rel));

				DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder().withVersion((short) 11)
						.withDomain(0, "Account", (byte) 0x03, 4L).withDomainDenseIdWidth(0, (byte) 4)
						.withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, balBuf.array(), null, null)
						.withDomainAttribute(0, "notional", TYPE_DECIMAL64_NULLABLE, 1, 18, 2, notBuf.array(), null,
								notValidity)
						.withDomainAttribute(0, "tier", TYPE_INT64, 1, 0, 0, tierBuf.array(), null, null)
						.withDomainZoneMapIndex(0, "tier").withRelationDomain("transfer", 0, 0).withCsc(true)
						.withCoo(true).withMetadata("owner", "finance-core").withMetadata("env", "test");

				snapshotBytes = builder.build(new BinarySnapshotLoader.DefaultLoadedSnapshot(0x494D5053, (short) 11,
						buildGraph, Map.of(), Map.of(), Map.of(), Map.of()));
			}
			Files.write(tmp, snapshotBytes);

			String expectedSha256 = HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(snapshotBytes));

			try (Arena loadArena = Arena.ofConfined()) {
				BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(tmp, loadArena);
				ImpulseGraphSnapshot snap = loaded.graph();

				// Page 0 header
				SnapshotHeader hdr = snap.getHeader();
				assertNotNull(hdr);
				assertEquals(0x494D5053, hdr.magic());
				assertEquals(11, hdr.version());
				assertTrue(hdr.dataOffset() >= 4096L);
				assertEquals(1, hdr.domainCount());
				assertEquals(1, hdr.relationCount());
				assertEquals(1, hdr.indexCount());
				assertEquals(snapshotBytes.length, hdr.fileSizeBytes());
				assertEquals(snapshotBytes.length, snap.getFileSizeBytes());
				assertEquals(16, hdr.snapshotUuid().length);

				// Defensive copy on snapshotUuid
				byte[] uuidCopy = hdr.snapshotUuid();
				byte orig0 = uuidCopy[0];
				uuidCopy[0] = (byte) (orig0 ^ 0xFF);
				assertEquals(orig0, hdr.snapshotUuid()[0]);

				// Domains & domain attributes
				List<DomainMeta> domains = snap.getDomains();
				assertEquals(1, domains.size());
				DomainMeta account = snap.getDomainMeta("Account");
				assertNotNull(account);
				assertEquals(account, snap.getDomainMeta(0));
				assertNull(snap.getDomainMeta("Missing"));
				assertNull(snap.getDomainMeta(99));
				assertEquals(0, account.domainId());
				assertEquals("Account", account.name());
				assertEquals(4L, account.nodeCount());
				assertEquals((byte) 4, account.denseIdWidth());
				assertEquals(3, account.attributes().size());

				AttributeMeta bal = account.attributes().get(0);
				assertEquals("balance", bal.name());
				assertEquals(TYPE_DECIMAL64, bal.typeCode());
				assertEquals(TYPE_DECIMAL64, bal.baseTypeCode());
				assertFalse(bal.nullable());
				assertEquals(10, bal.precision());
				assertEquals(2, bal.scale());
				assertEquals(0L, bal.validityBytes());
				assertTrue(bal.dataBytes() >= 32L);

				AttributeMeta notional = account.attributes().get(1);
				assertEquals("notional", notional.name());
				assertEquals(TYPE_DECIMAL64_NULLABLE, notional.typeCode());
				assertEquals(TYPE_DECIMAL64, notional.baseTypeCode());
				assertTrue(notional.nullable());
				assertEquals(18, notional.precision());
				assertEquals(2, notional.scale());
				assertTrue(notional.validityOffset() > 0L);
				assertTrue(notional.validityBytes() > 0L);

				AttributeMeta tier = account.attributes().get(2);
				assertEquals("tier", tier.name());
				assertEquals(TYPE_INT64, tier.typeCode());
				assertFalse(tier.nullable());

				// SnapshotHeader value equality & hex formatting
				SnapshotHeader hdrClone = new SnapshotHeader(hdr.magic(), hdr.version(), hdr.dataOffset(),
						hdr.domainCount(), hdr.relationCount(), hdr.indexCount(), hdr.timestampMs(),
						hdr.requiredFeatures(), hdr.footerDirectoryOffset(), hdr.footerDirectoryBytes(),
						hdr.fileSizeBytes(), hdr.headerChecksum(), hdr.snapshotUuid());
				assertEquals(hdr, hdrClone);
				assertEquals(hdr.hashCode(), hdrClone.hashCode());
				assertEquals(32, hdr.snapshotUuidHex().length());
				assertTrue(hdr.toString().contains(hdr.snapshotUuidHex()));
				assertEquals(hdr.timestampMs(), loaded.timestampMs());
				assertEquals(hdr.requiredFeatures(), loaded.globalFeatures());

				// Immutability checks
				assertThrows(UnsupportedOperationException.class, () -> domains.add(account));
				assertThrows(UnsupportedOperationException.class, () -> account.attributes().add(bal));
				assertThrows(UnsupportedOperationException.class, () -> snap.getMetadataMap().put("x", "y"));

				// Secondary indices
				List<IndexMeta> indices = snap.getSecondaryIndexMetas();
				assertEquals(1, indices.size());
				assertThrows(UnsupportedOperationException.class, () -> indices.add(indices.get(0)));
				IndexMeta idx = indices.get(0);
				assertEquals(0, idx.domainId());
				assertTrue(idx.dataBytes() > 0L);

				// Relation introspection
				org.impulsegraph.api.RelationSnapshot relSnap = snap.getRelationSnapshot("transfer");
				assertNotNull(relSnap);
				assertEquals(0, relSnap.getSrcDomainId());
				assertEquals(0, relSnap.getTgtDomainId());
				assertTrue(relSnap.hasCsc());
				assertTrue(relSnap.hasCoo());
				assertFalse(relSnap.isVirtual());
				assertEquals(0, relSnap.getComponentIds().length);
				assertEquals(1, relSnap.getAttributeMetas().size());
				AttributeMeta edgeAttr = relSnap.getAttributeMetas().get(0);
				assertEquals("weight", edgeAttr.name());
				assertEquals((byte) 0x06, edgeAttr.typeCode());
				assertFalse(edgeAttr.nullable());
				assertTrue(edgeAttr.dataBytes() >= 8L);

				// Section 6 metadata
				assertEquals("finance-core", snap.getMetadata("owner"));
				assertEquals("test", snap.getMetadata("env"));
				assertNull(snap.getMetadata("nonexistent"));
				assertNull(snap.getMetadata(null));
				assertNull(loaded.getMetadata(null));
				assertEquals(account, snap.getDomainMeta("account"));
				assertEquals("finance-core", snap.getMetadataMap().get("owner"));

				// Streaming SHA-256 matches raw file bytes (both GraphSnapshot and
				// LoadedSnapshot)
				assertEquals(expectedSha256, snap.getSha256Checksum());
				assertEquals(expectedSha256, snap.getSha256Checksum()); // cached path
				assertEquals(expectedSha256, loaded.getSha256Checksum());
			}

			// Closed-snapshot SHA-256 must fail cleanly if uncached
			ImpulseGraphSnapshot closedSnap;
			try (Arena closedArena = Arena.ofConfined()) {
				closedSnap = BinarySnapshotLoader.loadSnapshot(tmp, closedArena).graph();
			}
			assertThrows(IllegalStateException.class, closedSnap::getSha256Checksum);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	@Test
	void testInMemorySnapshotDefaultsAndVirtualRelationMetadata() {
		try (Arena arena = Arena.ofConfined()) {
			RelationSnapshot rel = new RelationSnapshot(arena, 2, 1, new int[]{0, 1, 1}, new int[]{1});
			rel.setCatalogMetadata(7, 1, 2, (byte) 1, 0x04L, new int[]{3, 4});
			rel.setLoadedAttributes(List.of(
					new BinarySnapshotLoader.LoadedAttribute("weight", (byte) 0x06, 1, 0, 0, 0L, 0L, 128L, 8L, 0L, 0L)),
					List.of());

			assertTrue(rel.isVirtual());
			assertEquals((byte) 1, rel.getEncodingId());
			assertEquals(0x04L, rel.getSectionFeatures());
			int[] comps = rel.getComponentIds();
			assertArrayEquals(new int[]{3, 4}, comps);
			comps[0] = 999;
			assertArrayEquals(new int[]{3, 4}, rel.getComponentIds());
			assertEquals(1, rel.getAttributeMetas().size());
			assertEquals("weight", rel.getAttributeMetas().get(0).name());
			assertThrows(UnsupportedOperationException.class,
					() -> rel.getAttributeMetas().add(rel.getAttributeMetas().get(0)));

			GraphSnapshot memSnap = new GraphSnapshot(arena, Map.of("virtualRel", rel));
			assertNull(memSnap.getHeader());
			assertEquals(0L, memSnap.getFileSizeBytes());
			assertEquals("", memSnap.getSha256Checksum());
			assertTrue(memSnap.getDomains().isEmpty());
			assertTrue(memSnap.getSecondaryIndexMetas().isEmpty());
			assertNull(memSnap.getDomainMeta("Any"));
			assertNull(memSnap.getDomainMeta(0));
		}
	}

	@Test
	void testMultiDomainCatalogOrderAndDuplicateNamePreservation() throws Exception {
		Path tmp = Files.createTempFile("java-multidomain-order", ".imps");
		try {
			byte[] snapshotBytes;
			try (Arena buildArena = Arena.ofConfined()) {
				RelationSnapshot rel = new RelationSnapshot(buildArena, 2, 1, new int[]{0, 1, 1}, new int[]{1});
				GraphSnapshot buildGraph = new GraphSnapshot(buildArena, Map.of("edge", rel));
				snapshotBytes = new DefaultSnapshotBuilder().withVersion((short) 11)
						.withDomain(0, "Zebra", (byte) 0x03, 2L).withDomain(1, "Alpha", (byte) 0x03, 3L)
						.withDomain(2, "Middle", (byte) 0x03, 4L).withDomain(3, "Alpha", (byte) 0x03, 5L)
						.withRelationDomain("edge", 0, 1).build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
								0x494D5053, (short) 11, buildGraph, Map.of(), Map.of(), Map.of(), Map.of()));
			}
			Files.write(tmp, snapshotBytes);
			try (Arena loadArena = Arena.ofConfined()) {
				ImpulseGraphSnapshot snap = BinarySnapshotLoader.loadSnapshot(tmp, loadArena).graph();
				List<DomainMeta> doms = snap.getDomains();
				assertEquals(4, doms.size());
				assertEquals(0, doms.get(0).domainId());
				assertEquals("Zebra", doms.get(0).name());
				assertEquals(1, doms.get(1).domainId());
				assertEquals("Alpha", doms.get(1).name());
				assertEquals(2, doms.get(2).domainId());
				assertEquals("Middle", doms.get(2).name());
				assertEquals(3, doms.get(3).domainId());
				assertEquals("Alpha", doms.get(3).name());
				assertEquals(3L, snap.getDomainMeta(1).nodeCount());
				assertEquals(5L, snap.getDomainMeta(3).nodeCount());
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}
}
