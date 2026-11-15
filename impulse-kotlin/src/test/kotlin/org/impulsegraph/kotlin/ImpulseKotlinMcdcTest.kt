package org.impulsegraph.kotlin

import kotlinx.coroutines.test.runTest
import org.impulsegraph.api.ArgType
import org.impulsegraph.api.ReturnType
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder
import org.impulsegraph.storage.csr.GraphSnapshot
import org.impulsegraph.storage.csr.RelationSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class ImpulseKotlinMcdcTest {
    private fun createSyntheticGraph(arena: Arena): GraphSnapshot {
        val fRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 2, 3, 3)
        val fCol = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 3)
        val fCscRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 1, 2, 3)
        val fCscCol = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 2)
        val relFollows = RelationSnapshot(arena, 4, 3, fRow, fCol, fCscRow, fCscCol)

        val rels = mapOf("follows" to relFollows)
        val meta =
            mapOf(
                "domain.User.nodeCount" to "4",
                "domain.0.name" to "User",
            )
        return GraphSnapshot(arena, rels, meta)
    }

    @Test
    fun testAllReductionsAndCollectCountAndSeededMetadata() {
        val qCount =
            impulseQuery<Long> {
                input("User", NodeId(0L))
                walkEdge("follows")
            }.collectCount()
        assertNotNull(qCount.getAst())
        assertTrue(qCount.exportAst().contains("follows"))
        assertNotNull(qCount.operationName)
        assertNotNull(qCount.parameters)
        assertTrue(qCount.steps.isNotEmpty())

        val qBitSet =
            impulseQuery<ImpulseBitSet> {
                input("User", NodeId(0L))
                walkEdge("follows")
            }.collectBitSet()

        Arena.ofConfined().use { arena ->
            val snap = createSyntheticGraph(arena)
            assertEquals(1, snap(qBitSet).cardinality())
        }

        val maxQ =
            impulseQuery<Double> {
                input("User", ArgType.SINGLE_NODE)
                walkEdge("follows")
                projectState("1.0")
            }.reduceMax()
        val minQ =
            impulseQuery<Double> {
                input("User", ArgType.SINGLE_NODE)
                walkEdge("follows")
                projectState("1.0")
            }.reduceMin()
        val avgQ =
            impulseQuery<Double> {
                input("User", ArgType.SINGLE_NODE)
                walkEdge("follows")
                projectState("1.0")
            }.reduceAvg()
        val firstQ =
            impulseQuery<Any> {
                input("User", ArgType.SINGLE_NODE)
                walkEdge("follows")
            }.reduceFirst()
        val argMaxQ =
            impulseQuery<Int> {
                input("User", ArgType.SINGLE_NODE)
                walkEdge("follows")
            }.reduceArgMax()
        val argMinQ =
            impulseQuery<Int> {
                input("User", ArgType.SINGLE_NODE)
                walkEdge("follows")
            }.reduceArgMin()

        assertTrue(maxQ.exportAst().contains("reduce"))
        assertTrue(minQ.exportAst().contains("reduce"))
        assertTrue(avgQ.exportAst().contains("reduce"))
        assertTrue(firstQ.exportAst().contains("reduce"))
        assertTrue(argMaxQ.exportAst().contains("reduce"))
        assertTrue(argMinQ.exportAst().contains("reduce"))
    }

    @Test
    fun testAllWalksFiltersBindingsAndRepeatUntilStable() {
        val q =
            impulseQuery<Any> {
                input("User", ArgType.SINGLE_NODE)
                bindParameter("p1", 10)
                bindParameters(mapOf("p2" to "val"))
                walkEdgeFiltered("follows", "score > 0")
                walkEdgeWithCel("follows", "node.id >= 0")
                walkReverseWithCel("follows", "node.id >= 0")
                walkEdgeWithState("follows", "dist + 1")
                walkOutUnion("follows")
                walkInUnion("follows")
                walkBothUnion("follows")
                filter("node.id >= 0")
                filterWithCel("node.id >= 0")
                repeatUntilStable {
                    walkEdge("follows")
                }
                vectorRerank(floatArrayOf(1.0f, 2.0f), 1, 0)
            }.collect(ReturnType.ROARING_BITSET)

        assertNotNull(q)
        val ast = q.exportAst()
        assertTrue(ast.contains("follows"))
        assertTrue(ast.contains("score > 0"))
        assertTrue(ast.contains("node.id >= 0"))
        assertTrue(ast.contains("dist + 1"))
        assertEquals(10, q.parameters["p1"])
        assertEquals("val", q.parameters["p2"])
        assertTrue(q.steps.size >= 11)

        val dsl = ImpulseQueryDsl<ImpulseBitSet>()
        dsl.input("User", ArgType.SINGLE_NODE).walkEdge("follows")
        assertNotNull(dsl.build())
    }

    @Test
    fun testSnapshotLoadUseStatementAndAsyncLifecycle() =
        runTest {
            val tmp = Files.createTempFile("kotlin-mcdc-snap", ".imps")
            try {
                Arena.ofConfined().use { writeArena ->
                    Files.write(tmp, DefaultSnapshotBuilder.writeSnapshotBytes(createSyntheticGraph(writeArena)))
                }

                ImpulseSnapshots.load(tmp).use { snap ->
                    assertTrue(snap.awaitDrainedAsync(1L, TimeUnit.SECONDS))
                    assertTrue(snap.awaitDrainedAsync(100.milliseconds))

                    val stmtVal =
                        snap.useStatement("SELECT * FROM follows") { stmt ->
                            assertNotNull(stmt)
                            "ok"
                        }
                    assertEquals("ok", stmtVal)
                }

                val loadedPath = ImpulseSnapshots.load(tmp)
                loadedPath.drainAndCloseAsync(100.milliseconds)

                val viaWith =
                    ImpulseSnapshots.withSnapshot(tmp) { snap ->
                        snap["follows"]
                    }
                assertEquals(3L, viaWith)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }

    @Test
    fun testDomainHelpersAndEmptyBitSetSequence() {
        val zeroHop = buildNHopQuery("User", "follows", 0)
        assertTrue(zeroHop.exportAst().contains("collect"))

        val rebac = buildRebacQuery("Doc", "viewer", "read")
        assertTrue(rebac.steps.isNotEmpty())

        Arena.ofConfined().use { arena ->
            val snap = createSyntheticGraph(arena)
            val emptyRes = snap.domainView("User").from(3L).out("follows").collect()
            assertTrue(emptyRes.toList().isEmpty())
            assertFalse(0 in emptyRes)

            val multiRes =
                snap.execute(
                    impulseQuery<ImpulseBitSet> {
                        input("User", ArgType.SINGLE_NODE)
                        repeat(2) { walkEdge("follows") }
                    }.collectBitSet(),
                    0L,
                )
            assertEquals(listOf(2), multiRes.toList())
        }
    }
}
