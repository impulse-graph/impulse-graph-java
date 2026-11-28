package org.impulsegraph.scala

import org.impulsegraph.storage.csr.{BinarySnapshotLoader, DefaultSnapshotBuilder, GraphSnapshot, RelationSnapshot}
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import java.lang.foreign.Arena
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.Files
import java.security.MessageDigest
import java.util
import java.util.HexFormat

class ImpulseScalaMetadataTest:

  @Test
  def testMetadataIntrospectionExtensions(): Unit =
    val tmp = Files.createTempFile("scala-metadata-test", ".imps")
    try
      val tierBuf = ByteBuffer.allocate(4 * 8).order(ByteOrder.LITTLE_ENDIAN)
      Array(10L, 20L, 30L, 40L).foreach(tierBuf.putLong)

      val bytes =
        val buildArena = Arena.ofConfined()
        try
          val rel = new RelationSnapshot(buildArena, 4, 2, Array(0, 1, 2, 2, 2), Array(1, 2))
          val graph = new GraphSnapshot(buildArena, util.Map.of("transfer", rel))
          new DefaultSnapshotBuilder()
            .withVersion(11.toShort)
            .withDomain(0, "Account", 0x03.toByte, 4L)
            .withDomain(1, "Merchant", 0x03.toByte, 4L)
            .withDomainAttribute(0, "tier", 0x04.toByte, 1, 0, 0, tierBuf.array(), null, null)
            .withDomainZoneMapIndex(0, "tier")
            .withRelationDomain("transfer", 0, 1)
            .withMetadata("owner", "scala-suite")
            .build(
              new BinarySnapshotLoader.DefaultLoadedSnapshot(
                0x494d5053,
                11.toShort,
                graph,
                util.Collections.emptyMap(),
                util.Collections.emptyMap(),
                util.Collections.emptyMap(),
                util.Collections.emptyMap()
              )
            )
        finally buildArena.close()

      Files.write(tmp, bytes)
      val expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

      val result = ImpulseSnapshots.withSnapshot(tmp) { snap =>
        val hdr = snap.headerOption
        assertTrue(hdr.isDefined)
        assertEquals(0x494d5053, hdr.get.magic())
        assertEquals(11, hdr.get.version())
        assertEquals(bytes.length.toLong, snap.fileSizeBytes)

        assertEquals(2, snap.domains.size)
        val acct = snap.domainMetaOption("Account").get
        assertEquals(Some(acct), snap.domainMetaOption(0))
        assertEquals(Some("Account"), snap.domainNameOption(0))
        assertEquals(Some("Merchant"), snap.domainNameOption(1))
        assertTrue(snap.domainNameOption(999).isEmpty)
        assertTrue(snap.domainNameOption(-1).isEmpty)
        assertEquals(Some("Account"), snap.relationSourceDomainNameOption("transfer"))
        assertEquals(Some("Merchant"), snap.relationTargetDomainNameOption("transfer"))
        assertTrue(snap.relationSourceDomainNameOption("missing").isEmpty)
        assertTrue(snap.relationTargetDomainNameOption("missing").isEmpty)
        assertTrue(snap.domainMetaOption("Missing").isEmpty)
        assertTrue(snap.domainMetaOption(999).isEmpty)
        assertTrue(snap.domainMetaOption(-1).isEmpty)
        assertEquals("tier", acct.attributeMetas.head.name())
        assertEquals(Some(acct.attributeMetas.head), acct.attributeMetaOption("tier"))
        assertTrue(acct.attributeMetaOption("missing").isEmpty)

        assertEquals(1, snap.secondaryIndices.size)
        assertEquals(0, snap.secondaryIndices.head.domainId())
        assertEquals(1, snap.relations.size)
        val rel = snap.relationOption("transfer").get
        assertEquals(Some(rel), snap.relationOption(rel.getRelationId))
        assertEquals(2L, rel.getEdgeCount)
        assertFalse(rel.isVirtual)
        assertTrue(rel.attributeMetas.isEmpty)
        assertTrue(rel.attributeMetaOption("missing").isEmpty)
        assertTrue(rel.componentIds.isEmpty)
        assertTrue(snap.relationOption("missing").isEmpty)
        assertTrue(snap.relationOption(999).isEmpty)
        assertTrue(snap.relationOption(-1).isEmpty)
        assertEquals(Some("scala-suite"), snap.metadataOption("owner"))
        assertTrue(snap.metadataOption("missing").isEmpty)
        assertEquals(Some("scala-suite"), snap.metadataMap.get("owner"))
        assertEquals(expectedSha, snap.sha256Checksum)
      }
      result.get

      val memArena = Arena.ofConfined()
      try
        val rel = new RelationSnapshot(memArena, 2, 1, Array(0, 1, 1), Array(1))
        rel.setCatalogMetadata(5, 0, 1, 0.toByte, 0x04L, Array(1, 2))
        rel.setLoadedAttributes(
          util.List.of(
            new BinarySnapshotLoader.LoadedAttribute("weight", 0x06.toByte, 1, 0, 0, 0L, 0L, 128L, 8L, 0L, 0L)
          ),
          util.Collections.emptyList()
        )
        assertEquals(Seq(1, 2), rel.componentIds)
        assertEquals(1, rel.attributeMetas.size)
        assertEquals("weight", rel.attributeMetaOption("weight").get.name())
        assertTrue(rel.attributeMetaOption("other").isEmpty)
        val memSnap = new GraphSnapshot(memArena, util.Map.of("e", rel))
        assertTrue(memSnap.headerOption.isEmpty)
        assertEquals(0L, memSnap.fileSizeBytes)
        assertTrue(memSnap.domains.isEmpty)
        assertTrue(memSnap.secondaryIndices.isEmpty)
        assertEquals("", memSnap.sha256Checksum)
      finally memArena.close()
    finally Files.deleteIfExists(tmp)
