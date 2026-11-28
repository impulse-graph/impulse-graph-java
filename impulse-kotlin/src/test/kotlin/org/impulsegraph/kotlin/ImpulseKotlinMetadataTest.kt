package org.impulsegraph.kotlin

import org.impulsegraph.storage.csr.BinarySnapshotLoader
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder
import org.impulsegraph.storage.csr.GraphSnapshot
import org.impulsegraph.storage.csr.RelationSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat

class ImpulseKotlinMetadataTest {
    @Test
    fun testMetadataIntrospectionExtensions() {
        val tmp = Files.createTempFile("kotlin-metadata-test", ".imps")
        try {
            val tierBuf = ByteBuffer.allocate(4 * 8).order(ByteOrder.LITTLE_ENDIAN)
            for (v in longArrayOf(10L, 20L, 30L, 40L)) tierBuf.putLong(v)

            val bytes =
                Arena.ofConfined().use { buildArena ->
                    val rel = RelationSnapshot(buildArena, 4, 2, intArrayOf(0, 1, 2, 2, 2), intArrayOf(1, 2))
                    val graph = GraphSnapshot(buildArena, mapOf("transfer" to rel))
                    DefaultSnapshotBuilder()
                        .withVersion(11.toShort())
                        .withDomain(0, "Account", 0x03.toByte(), 4L)
                        .withDomain(1, "Merchant", 0x03.toByte(), 4L)
                        .withDomainAttribute(0, "tier", 0x04.toByte(), 1, 0, 0, tierBuf.array(), null, null)
                        .withDomainZoneMapIndex(0, "tier")
                        .withRelationDomain("transfer", 0, 1)
                        .withMetadata("owner", "kotlin-suite")
                        .build(
                            BinarySnapshotLoader.DefaultLoadedSnapshot(
                                0x494D5053,
                                11.toShort(),
                                graph,
                                emptyMap(),
                                emptyMap(),
                                emptyMap(),
                                emptyMap(),
                            ),
                        )
                }
            Files.write(tmp, bytes)
            val expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

            ImpulseSnapshots.withSnapshot(tmp) { snap ->
                val hdr = snap.snapshotHeader
                assertNotNull(hdr)
                assertEquals(0x494D5053, hdr!!.magic())
                assertEquals(11, hdr.version())
                assertEquals(bytes.size.toLong(), snap.fileSizeBytes)

                assertEquals(2, snap.domainMetas.size)
                val acct = snap.domainMeta("Account")
                assertNotNull(acct)
                assertEquals(acct, snap.domainMeta(0))
                assertEquals("Account", snap.domainName(0))
                assertEquals("Merchant", snap.domainName(1))
                assertNull(snap.domainName(999))
                assertNull(snap.domainName(-1))
                assertEquals("Account", snap.relationSourceDomainName("transfer"))
                assertEquals("Merchant", snap.relationTargetDomainName("transfer"))
                assertNull(snap.relationSourceDomainName("missing"))
                assertNull(snap.relationTargetDomainName("missing"))
                assertNull(snap.domainMeta("NoSuch"))
                assertNull(snap.domainMeta(999))
                assertNull(snap.domainMeta(-1))
                assertEquals(1, acct!!.attributes().size)
                assertEquals("tier", acct.attributeMeta("tier")!!.name())
                assertNull(acct.attributeMeta("missing"))
                assertFalse(acct.attributes()[0].nullable())

                assertEquals(1, snap.indexMetas.size)
                val idx = snap.indexMetas[0]
                assertEquals(0, idx.domainId())
                assertTrue(idx.dataBytes() > 0L)
                assertEquals(1, snap.relations.size)
                val rel = snap.relation("transfer")
                assertNotNull(rel)
                assertEquals(rel, snap.relation(rel!!.relationId))
                assertEquals(2L, rel.edgeCount)
                assertEquals(0, rel.srcDomainId)
                assertEquals(1, rel.tgtDomainId)
                assertTrue(rel.attributeMetaList.isEmpty())
                assertNull(rel.attributeMeta("missing"))
                assertNull(snap.relation("missing"))
                assertNull(snap.relation(999))
                assertNull(snap.relation(-1))
                assertEquals("kotlin-suite", snap.metadata("owner"))
                assertNull(snap.metadata("missing"))
                assertEquals("kotlin-suite", snap.metadata["owner"])
                assertEquals(expectedSha, snap.sha256)
                assertTrue(snap.sha256.length == 64)
            }

            // In-memory synthetic snapshot defaults + positive RelationSnapshot.attributeMeta
            Arena.ofConfined().use { arena ->
                val rel = RelationSnapshot(arena, 2, 1, intArrayOf(0, 1, 1), intArrayOf(1))
                rel.setLoadedAttributes(
                    listOf(
                        BinarySnapshotLoader.LoadedAttribute(
                            "weight",
                            0x06.toByte(),
                            1,
                            0,
                            0,
                            0L,
                            0L,
                            128L,
                            8L,
                            0L,
                            0L,
                        ),
                    ),
                    emptyList(),
                )
                assertEquals(1, rel.attributeMetaList.size)
                assertEquals("weight", rel.attributeMeta("weight")!!.name())
                assertNull(rel.attributeMeta("other"))
                val memSnap = GraphSnapshot(arena, mapOf("e" to rel))
                assertNull(memSnap.snapshotHeader)
                assertEquals(0L, memSnap.fileSizeBytes)
                assertTrue(memSnap.domainMetas.isEmpty())
                val memIndices: List<org.impulsegraph.api.meta.IndexMeta> = memSnap.indexMetas
                assertTrue(memIndices.isEmpty())
                assertTrue(memSnap.metadata.isEmpty())
                assertEquals("", memSnap.sha256)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
