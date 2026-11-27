package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, Decimal64}
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.storage.csr.{BinarySnapshotLoader, DefaultSnapshotBuilder, GraphSnapshot, RelationSnapshot}
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import java.lang.foreign.{Arena, ValueLayout}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.Files
import java.util

class ImpulseScalaDecimal64Test:

  private val TypeDecimal64: Byte = 0x15.toByte
  private val TypeDecimal64Nullable: Byte = (0x15 | 0x80).toByte

  @Test
  def testDecimal10Scale2ConversionsAndOrdering(): Unit =
    val fromStr = "123.45".toDecimal64(10, 2)
    assertEquals(12345L, fromStr.unscaled)
    assertEquals(10, fromStr.precision)
    assertEquals(2, fromStr.scale)
    assertEquals("123.45", fromStr.toDecimalString)
    assertEquals(BigDecimal("123.45"), fromStr.toScalaBigDecimal)
    assertEquals(new java.math.BigDecimal("123.45"), fromStr.toBigDecimal)

    val inferred = "-0.05".toDecimal64
    assertEquals(-5L, inferred.unscaled)
    assertEquals(2, inferred.scale)

    val fromScalaBd = BigDecimal("19.995").toDecimal64(10, 2)
    assertEquals(2000L, fromScalaBd.unscaled)
    assertEquals("20.00", fromScalaBd.toDecimalString)

    val fromJavaBd = new java.math.BigDecimal("-19.995").toDecimal64(10, 2)
    assertEquals(-2000L, fromJavaBd.unscaled)

    val fromLong = 42L.toDecimal64(10, 2)
    assertEquals(4200L, fromLong.unscaled)
    assertEquals("42.00", fromLong.toDecimalString)

    val fromLongDefault = 42L.toDecimal64()
    assertEquals(42L, fromLongDefault.unscaled)
    assertEquals(18, fromLongDefault.precision)
    assertEquals(0, fromLongDefault.scale)

    assertEquals(fromStr, 12345L.toDecimal64Unscaled(10, 2))
    assertTrue(fromScalaBd < fromLong)
    assertTrue(fromStr > fromLong)
    assertEquals("-123.45".toDecimal64(10, 2), -fromStr)
    assertEquals("0.05".toDecimal64(10, 2), inferred.abs.rescale(10, 2))

    val sorted = List(fromStr, inferred, fromScalaBd, fromLong).sorted
    assertEquals(List(inferred, fromScalaBd, fromLong, fromStr), sorted)

    assertThrows(classOf[IllegalArgumentException], () => "19.995".toDecimal64(10, 2))
    assertThrows(classOf[ArithmeticException], () => "100.00".toDecimal64(4, 2))

  @Test
  def testDecimal18Scale2Above2Pow53ExactPrecision(): Unit =
    val above53 = "90071992547409.93".toDecimal64(18, 2)
    assertEquals(9_007_199_254_740_993L, above53.unscaled)
    assertEquals("90071992547409.93", above53.toDecimalString)
    assertEquals(BigDecimal("90071992547409.93"), above53.toScalaBigDecimal)

    val fromBd = BigDecimal("90071992547409.93").toDecimal64(18, 2)
    assertEquals(above53, fromBd)

    val max18 = "9999999999999999.99".toDecimal64(18, 2)
    assertEquals(999_999_999_999_999_999L, max18.unscaled)
    assertEquals("9999999999999999.99", max18.toDecimalString)
    assertTrue(above53 < max18)

  @Test
  def testSnapshotBuilderAndQueryRoundtrip(): Unit =
    val tmp = Files.createTempFile("scala-decimal64-test", ".imps")
    try
      val balances = Array(
        "123.45".toDecimal64(10, 2),
        "-999.99".toDecimal64(10, 2),
        "0.00".toDecimal64(10, 2),
        "5000000.00".toDecimal64(10, 2)
      )
      val notionals: Array[Option[Decimal64]] = Array(
        Some("90071992547409.93".toDecimal64(18, 2)),
        None,
        Some("-90071992547409.93".toDecimal64(18, 2)),
        Some("9999999999999999.99".toDecimal64(18, 2))
      )

      val balBuf = ByteBuffer.allocate(balances.length * 8).order(ByteOrder.LITTLE_ENDIAN)
      balances.foreach(d => balBuf.putLong(d.unscaled))

      val notBuf = ByteBuffer.allocate(notionals.length * 8).order(ByteOrder.LITTLE_ENDIAN)
      val notValidity = new Array[Byte](128)
      var mask = 0
      for (opt, idx) <- notionals.zipWithIndex do
        opt match
          case Some(d) =>
            notBuf.putLong(d.unscaled)
            mask |= (1 << idx)
          case None =>
            notBuf.putLong(0L)
      notValidity(0) = mask.toByte

      val arena = Arena.ofConfined()
      try
        val rel = new RelationSnapshot(arena, 4, 2, Array(0, 1, 2, 2, 2), Array(1, 2))
        val relMap = new util.HashMap[String, RelationSnapshot]()
        relMap.put("transfer", rel)
        val graph = new GraphSnapshot(arena, relMap)

        val builder = new DefaultSnapshotBuilder()
          .withVersion(11.toShort)
          .withDomain(0, "Account", 0x03.toByte, 4L)
          .withDomainAttribute(0, "balance", TypeDecimal64, 1, 10, 2, balBuf.array(), null, null)
          .withDomainAttribute(0, "notional", TypeDecimal64Nullable, 1, 18, 2, notBuf.array(), null, notValidity)

        val bytes = builder.build(
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
        Files.write(tmp, bytes)

        val loaded = BinarySnapshotLoader.loadSnapshot(tmp, arena)
        val dom = loaded.getDomain("Account")
        assertNotNull(dom)
        assertTrue(dom.getAttribute("notional").isNullable)
        assertEquals(0x0d, dom.getValiditySegment("notional").get(ValueLayout.JAVA_BYTE, 0L) & 0xff)
      finally arena.close()

      val minBalance = "100.00".toDecimal64(10, 2)
      val minNotional = "90071992547409.93".toDecimal64(18, 2)

      val filterQuery = impulseQuery[ImpulseBitSet] {
        input("Account", NodeId(0L))
        bindParameter("@minNotional", minNotional)
        filterNodeAttribute("balance", ">=", minBalance)
        walkEdgeFilteredAttribute(RelationName("transfer"), "fee", "<=", "1.25".toDecimal64(10, 2))
      }.collectBitSet()

      assertEquals(minNotional, filterQuery.getParameters.get("@minNotional"))
      val ast = filterQuery.exportAst()
      assertTrue(ast.contains("node.balance >= 100.00"))
      assertTrue(ast.contains("edge.fee <= 1.25"))

      val walkQuery = impulseQuery[ImpulseBitSet] {
        input("Account", ArgType.SINGLE_NODE)
        walkEdge("transfer")
      }.collectBitSet()

      val roundtrip = ImpulseSnapshots.withSnapshot(tmp) { snap =>
        assertEquals(balances(0), snap.domainDecimal64("Account", "balance", 0L, 10, 2))
        assertEquals(balances(3), snap.domainDecimal64("Account", "balance", 3L, 10, 2))
        assertEquals(notionals(0).get, snap.domainDecimal64("Account", "notional", 0L, 18, 2))
        assertEquals(notionals(3).get, snap.domainDecimal64("Account", "notional", 3L, 18, 2))
        snap(walkQuery, 0L).toScalaSet
      }
      assertTrue(roundtrip.isSuccess)
      assertEquals(Set(1), roundtrip.get)
    finally Files.deleteIfExists(tmp)
