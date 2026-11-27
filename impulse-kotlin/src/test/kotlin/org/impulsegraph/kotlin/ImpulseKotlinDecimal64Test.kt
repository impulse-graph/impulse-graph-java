package org.impulsegraph.kotlin

import org.impulsegraph.api.ArgType
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.compiler.cel.CelCompiler
import org.impulsegraph.storage.csr.BinarySnapshotLoader
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder
import org.impulsegraph.storage.csr.GraphSnapshot
import org.impulsegraph.storage.csr.RelationSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class ImpulseKotlinDecimal64Test {
    companion object {
        private const val TYPE_INT64: Byte = 0x04
        private const val TYPE_DECIMAL64: Byte = 0x15
        private const val TYPE_DECIMAL64_NULLABLE: Byte = (0x15 or 0x80).toByte()
    }

    @Test
    fun testDecimal10Scale2ConversionsAndOperators() {
        val fromStr = "123.45".toDecimal64(10, 2)
        assertEquals(12345L, fromStr.unscaled())
        assertEquals(10, fromStr.precision())
        assertEquals(2, fromStr.scale())
        assertEquals("123.45", fromStr.toDecimalString())
        assertEquals(BigDecimal("123.45"), fromStr.toBigDecimal())

        val inferred = "-0.05".toDecimal64()
        assertEquals(-5L, inferred.unscaled())
        assertEquals(2, inferred.scale())
        assertEquals("-0.05", inferred.toDecimalString())

        val fromBd = BigDecimal("19.995").toDecimal64(10, 2)
        assertEquals(2000L, fromBd.unscaled())
        assertEquals("20.00", fromBd.toDecimalString())

        val fromLong = 42L.toDecimal64(precision = 10, scale = 2)
        assertEquals(4200L, fromLong.unscaled())
        assertEquals("42.00", fromLong.toDecimalString())

        val fromLongDefault = 42L.toDecimal64()
        assertEquals(42L, fromLongDefault.unscaled())
        assertEquals(18, fromLongDefault.precision())
        assertEquals(0, fromLongDefault.scale())

        val fromUnscaled = 12345L.toDecimal64Unscaled(10, 2)
        assertEquals(fromStr, fromUnscaled)

        assertTrue(fromLong < fromStr)
        assertTrue(fromStr > fromBd)
        assertEquals(0, "42.00".toDecimal64(10, 2).compareTo(42L.toDecimal64()))
        assertEquals("-123.45".toDecimal64(10, 2), -fromStr)
        assertEquals("0.05".toDecimal64(10, 2), inferred.abs().rescale(10, 2))

        assertThrows(IllegalArgumentException::class.java) { "19.995".toDecimal64(10, 2) }
        assertThrows(ArithmeticException::class.java) { "100.00".toDecimal64(4, 2) }
        assertThrows(IllegalArgumentException::class.java) { 1L.toDecimal64(19, 2) }
    }

    @Test
    fun testDecimal18Scale2Above2Pow53ExactPrecision() {
        val above53 = "90071992547409.93".toDecimal64(18, 2)
        assertEquals(9_007_199_254_740_993L, above53.unscaled())
        assertEquals("90071992547409.93", above53.toDecimalString())
        assertEquals(BigDecimal("90071992547409.93"), above53.toBigDecimal())

        val fromBd = BigDecimal("90071992547409.93").toDecimal64(18, 2)
        assertEquals(above53, fromBd)

        val max18 = "9999999999999999.99".toDecimal64(18, 2)
        assertEquals(999_999_999_999_999_999L, max18.unscaled())
        assertEquals("9999999999999999.99", max18.toDecimalString())
        assertEquals(BigDecimal("9999999999999999.99"), max18.toBigDecimal())
        assertTrue(above53 < max18)
    }

    @Test
    fun testSnapshotBuilderNullable0x95CelCompileAndQueryRoundtrip() {
        val tmp = Files.createTempFile("kotlin-decimal64-test", ".imps")
        try {
            val balances =
                listOf(
                    "123.45".toDecimal64(10, 2),
                    "-999.99".toDecimal64(10, 2),
                    "0.00".toDecimal64(10, 2),
                    "5000000.00".toDecimal64(10, 2),
                )
            val notionals =
                listOf(
                    "90071992547409.93".toDecimal64(18, 2),
                    null,
                    "-90071992547409.93".toDecimal64(18, 2),
                    "9999999999999999.99".toDecimal64(18, 2),
                )
            val tiers = longArrayOf(1L, 2L, 3L, 4L)

            val balBuf = ByteBuffer.allocate(balances.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            balances.forEach { balBuf.putLong(it.unscaled()) }

            val tierBuf = ByteBuffer.allocate(tiers.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            tiers.forEach { tierBuf.putLong(it) }

            val notBuf = ByteBuffer.allocate(notionals.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            val notValidity = ByteArray(128)
            var validMask = 0
            notionals.forEachIndexed { idx, d ->
                if (d != null) {
                    notBuf.putLong(d.unscaled())
                    validMask = validMask or (1 shl idx)
                } else {
                    notBuf.putLong(0L)
                }
            }
            notValidity[0] = validMask.toByte()

            Arena.ofConfined().use { arena ->
                val rel = RelationSnapshot(arena, 4, 2, intArrayOf(0, 1, 2, 2, 2), intArrayOf(1, 2))
                val graph = GraphSnapshot(arena, mapOf("transfer" to rel))
                val builder =
                    DefaultSnapshotBuilder()
                        .withVersion(11.toShort())
                        .withDomain(0, "Account", 0x03.toByte(), 4L)
                        .withDomainAttribute(0, "balance", TYPE_DECIMAL64, 1, 10, 2, balBuf.array(), null, null)
                        .withDomainAttribute(
                            0,
                            "notional",
                            TYPE_DECIMAL64_NULLABLE,
                            1,
                            18,
                            2,
                            notBuf.array(),
                            null,
                            notValidity,
                        )
                        .withDomainAttribute(0, "tier", TYPE_INT64, 1, 0, 0, tierBuf.array(), null, null)

                val bytes =
                    builder.build(
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
                Files.write(tmp, bytes)

                val loaded = BinarySnapshotLoader.loadSnapshot(tmp, arena)
                val dom = loaded.getDomain("Account")
                assertNotNull(dom)

                val balAttr = dom.getAttribute("balance")
                assertEquals(TYPE_DECIMAL64, balAttr.typeCode())
                assertFalse(balAttr.isNullable)
                assertEquals(10, balAttr.precision())
                assertEquals(2, balAttr.scale())

                val notAttr = dom.getAttribute("notional")
                assertEquals(TYPE_DECIMAL64, notAttr.typeCode())
                assertTrue(notAttr.isNullable)
                assertEquals(18, notAttr.precision())
                assertEquals(2, notAttr.scale())
                val validityByte = dom.getValiditySegment("notional").get(ValueLayout.JAVA_BYTE, 0L).toInt()
                assertEquals(0x0D, validityByte and 0xFF)
            }

            // Query DSL + CEL compiler verification (exact decimal("...", P, S))
            val minBalance = "100.00".toDecimal64(10, 2)
            val minNotional = "90071992547409.93".toDecimal64(18, 2)

            val filterQuery =
                impulseQuery<ImpulseBitSet> {
                    input("Account", NodeId(0L))
                    bindParameter("@minNotional", minNotional)
                    filterNodeAttribute("balance", ">=", minBalance)
                    walkEdgeFilteredAttribute(RelationName("transfer"), "fee", "<=", "1.25".toDecimal64(10, 2))
                }.collectBitSet()

            assertEquals(minNotional, filterQuery.parameters["@minNotional"])
            val ast = filterQuery.exportAst()
            assertTrue(ast.contains("node.balance >= decimal(\"100.00\", 10, 2)"))
            assertTrue(ast.contains("edge.fee <= decimal(\"1.25\", 10, 2)"))

            val compiledNodeCel = CelCompiler.compileToImpScheme("node.balance >= decimal(\"100.00\", 10, 2)")
            assertTrue(compiledNodeCel.contains("(dec \"100.00\" 10 2)"))
            val compiledEdgeCel = CelCompiler.compileToImpScheme("edge.fee <= decimal(\"1.25\", 10, 2)")
            assertTrue(compiledEdgeCel.contains("(dec \"1.25\" 10 2)"))

            val walkQuery =
                impulseQuery<ImpulseBitSet> {
                    input("Account", ArgType.SINGLE_NODE)
                    walkEdge("transfer")
                }.collectBitSet()

            ImpulseSnapshots.withSnapshot(tmp) { snap ->
                // Exact non-null & nullable reads (including auto-metadata and explicit-metadata overloads)
                assertEquals(balances[0], snap.getDomainDecimal64("Account", "balance", 0L))
                assertEquals(balances[3], snap.getDomainDecimal64("Account", "balance", 3L, 10, 2))
                assertEquals(notionals[0], snap.getDomainDecimal64("Account", "notional", 0L))
                assertNull(snap.getDomainDecimal64("Account", "notional", 1L))
                assertNull(snap.getDomainDecimal64("Account", "notional", 1L, 18, 2))
                assertEquals(notionals[3], snap.getDomainDecimal64("Account", "notional", 3L, 18, 2))

                // Bounds, type, and precision/scale mismatch validation
                assertThrows(IllegalArgumentException::class.java) {
                    snap.getDomainDecimal64("Account", "balance", -1L)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    snap.getDomainDecimal64("Account", "balance", 4L)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    snap.getDomainDecimal64("Account", "tier", 0L)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    snap.getDomainDecimal64("Account", "balance", 0L, 10, 4)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    snap.getDomainDecimal64("Account", "notional", 1L, 10, 2)
                }

                // Statement bindDecimal64 preserves typed Decimal64
                snap.useStatement("FROM Account -> transfer") { stmt ->
                    stmt.bindNode("id", 0L).bindDecimal64("minNotional", minNotional)
                    assertEquals(setOf(1), stmt.executeBitSet().toSet())
                }

                assertEquals(setOf(1), snap(walkQuery, 0L).toSet())
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
