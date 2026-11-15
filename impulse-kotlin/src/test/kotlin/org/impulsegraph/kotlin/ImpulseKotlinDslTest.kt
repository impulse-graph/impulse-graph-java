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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files

class ImpulseKotlinDslTest {
    private fun createSyntheticGraph(arena: Arena): GraphSnapshot {
        val fRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 2, 3, 3)
        val fCol = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 3)
        val fCscRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 1, 2, 3)
        val fCscCol = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 2)
        val relFollows = RelationSnapshot(arena, 4, 3, fRow, fCol, fCscRow, fCscCol)

        val u2gRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 2, 2, 2)
        val u2gCol = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2)
        val u2gCscRow = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 0, 1, 2, 2)
        val u2gCscCol = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1)
        val relU2g = RelationSnapshot(arena, 4, 2, u2gRow, u2gCol, u2gCscRow, u2gCscCol)

        val rels =
            mapOf(
                "follows" to relFollows,
                "userToGroup" to relU2g,
            )
        val meta =
            mapOf(
                "domain.User.nodeCount" to "4",
                "domain.Group.nodeCount" to "4",
                "domain.0.name" to "User",
                "domain.1.name" to "Group",
            )
        return GraphSnapshot(arena, rels, meta)
    }

    @Test
    fun testSimpleDslQueryConstruction() {
        val query =
            impulseQuery<Any> {
                input("USER", NodeId(42L))
                walkEdge(RelationName("userToGroup"))
                walkTarget(RelationName("GROUP"))
            }.collect(ReturnType.ROARING_BITSET)

        assertNotNull(query)
        val steps = query.steps
        assertEquals(3, steps.size)
        val ast = query.exportAst()
        assertTrue(ast.contains("userToGroup"))
        assertTrue(ast.contains("GROUP"))
    }

    @Test
    fun testInfixAndAttributeFilteringDsl() {
        val query =
            impulseQuery<Double> {
                input("Load", ArgType.SINGLE_NODE)
                walkEdgeFilteredAttribute("powerLine", "voltage", ">", 110.0)
                filterNodeAttribute("current", "<=", 50.0)
                projectExpression("voltage", "*", "current")
            }.reduceSum()

        assertNotNull(query)
        val ast = query.exportAst()
        assertTrue(ast.contains("powerLine"))
        assertTrue(ast.contains("voltage"))
        assertTrue(ast.contains("current"))
        assertTrue(ast.contains("reduce-sum"))
    }

    @Test
    fun testRepeatLoopDsl() {
        val query =
            impulseQuery<Any> {
                input("USER", ArgType.SINGLE_NODE)
                repeat(3) {
                    walkEdge("friend")
                }
            }.collect(ReturnType.ROARING_BITSET)

        assertNotNull(query)
        val ast = query.exportAst()
        assertTrue(ast.contains("friend"))
    }

    @Test
    fun testExtendedOpsDsl() {
        val query =
            impulseQuery<Boolean> {
                input("USER", ArgType.SINGLE_NODE)
                walkEdge("memberOf")
                extended {
                    rebacCheck("view_doc")
                }
            }.collect(ReturnType.EXISTS)

        assertNotNull(query)
        val ast = query.exportAst()
        assertTrue(ast.contains("view_doc"))
    }

    @Test
    fun testDomainHelpers() {
        val rebacQuery = buildRebacQuery("Document", "owner", "edit")
        assertNotNull(rebacQuery)
        assertTrue(rebacQuery.exportAst().contains("owner"))
        assertTrue(rebacQuery.exportAst().contains("edit"))

        val hopQuery = buildNHopQuery("Node", "connectedTo", 4)
        assertNotNull(hopQuery)
        assertTrue(hopQuery.exportAst().contains("connectedTo"))

        assertThrows(IllegalArgumentException::class.java) {
            buildNHopQuery("Node", "connectedTo", -1)
        }
    }

    @Test
    fun testValueClasses() {
        val nodeId = NodeId(42L)
        val relationName = RelationName("friend")
        assertEquals(42L, nodeId.value)
        assertEquals("friend", relationName.value)
    }

    @Test
    fun testSeededExecutionSnapshotOperatorsAndBitSetExtensions() {
        Arena.ofConfined().use { arena ->
            val snap = createSyntheticGraph(arena)
            assertEquals(3L, snap["follows"])
            assertNull(snap["nonExistent"])
            assertTrue("follows" in snap)
            assertFalse("nonExistent" in snap)
            assertEquals(2, snap.size)

            val seededQuery =
                impulseQuery<ImpulseBitSet> {
                    input("User", NodeId(0L))
                    walkEdge(RelationName("follows"))
                }.collectBitSet()

            val seededRes = snap(seededQuery)
            assertEquals(setOf(1), seededRes.toSet())
            assertTrue(1 in seededRes)
            assertFalse(2 in seededRes)
            assertEquals(listOf(2), seededQuery(snap, 1L).toList())

            val bothQuery =
                impulseQuery<ImpulseBitSet> {
                    input("User", ArgType.SINGLE_NODE)
                    walkBoth("follows")
                }.collectBitSet()
            assertEquals(setOf(0, 2), snap.execute(bothQuery, 1L).toSet())

            val reverseQuery =
                impulseQuery<ImpulseBitSet> {
                    input("User", ArgType.SINGLE_NODE)
                    walkReverse("follows")
                }.collectBitSet()
            assertEquals(setOf(1), snap.execute(reverseQuery, 2L).toSet())

            val dvRes = snap.domainView("User").from(0L).out("follows").collect()
            assertEquals(setOf(1), dvRes.toSet())
        }
    }

    @Test
    fun testAsyncExecutionAndWithSnapshotLifecycle() =
        runTest {
            val tmp = Files.createTempFile("kotlin-snap-test", ".imps")
            try {
                Arena.ofConfined().use { writeArena ->
                    Files.write(tmp, DefaultSnapshotBuilder.writeSnapshotBytes(createSyntheticGraph(writeArena)))
                }

                val twoHop =
                    impulseQuery<ImpulseBitSet> {
                        input("User", ArgType.SINGLE_NODE)
                        repeat(2) {
                            walkEdge("follows")
                        }
                    }.collectBitSet()

                val res =
                    ImpulseSnapshots.withSnapshot(tmp) { snap ->
                        snap.executeAsync(twoHop, 0L).toSet()
                    }
                assertEquals(setOf(2), res)
            } finally {
                Files.deleteIfExists(tmp)
            }
        }
}
