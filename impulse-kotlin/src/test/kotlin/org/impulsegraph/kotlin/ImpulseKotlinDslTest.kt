package org.impulsegraph.kotlin

import kotlinx.coroutines.test.runTest
import org.impulsegraph.api.ArgType
import org.impulsegraph.api.ImpulseGraphSnapshot
import org.impulsegraph.api.ReturnType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpulseKotlinDslTest {
    @Test
    fun testSimpleDslQueryConstruction() {
        val query =
            impulseQuery<Any> {
                input("USER", NodeId(42L))
                walkEdge(RelationName("userToGroup"))
                walkTarget(RelationName("GROUP"))
            }.collect<Any>(ReturnType.ROARING_BITSET)

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
            }.reduceSum<Double>()

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
            }.collect<Any>(ReturnType.ROARING_BITSET)

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
            }.collect<Boolean>(ReturnType.EXISTS)

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
    }

    @Test
    fun testValueClasses() {
        val nodeId = NodeId(42L)
        val relationName = RelationName("friend")
        assertEquals(42L, nodeId.value)
        assertEquals("friend", relationName.value)
    }

    @Test
    fun testQueryInvokeOperator() {
        val query =
            impulseQuery<Any> {
                input("USER", ArgType.SINGLE_NODE)
            }.collect<Any>(ReturnType.ROARING_BITSET)

        val snapshot: ImpulseGraphSnapshot? = null
        assertThrows(IllegalArgumentException::class.java) {
            query(snapshot, 100L)
        }
    }

    @Test
    fun testAsyncExecutionExtension() =
        runTest {
            val query =
                impulseQuery<Any> {
                    input("USER", ArgType.SINGLE_NODE)
                }.collect<Any>(ReturnType.ROARING_BITSET)

            val snapshot: ImpulseGraphSnapshot? = null
            assertThrows(IllegalArgumentException::class.java) {
                query.execute(snapshot, 200L)
            }
        }
}
