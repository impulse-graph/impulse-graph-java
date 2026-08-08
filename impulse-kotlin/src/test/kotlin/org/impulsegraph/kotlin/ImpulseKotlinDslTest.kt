package org.impulsegraph.kotlin

import kotlinx.coroutines.test.runTest
import org.impulsegraph.api.ArgType
import org.impulsegraph.api.ImpulseGraphSnapshot
import org.impulsegraph.api.ReturnType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImpulseKotlinDslTest {

    @Test
    fun testSimpleDslQueryConstruction() {
        val query = impulseQuery<Any> {
            input("USER", ArgType.SINGLE_NODE)
            walkEdge("userToGroup")
            walkTarget("GROUP")
        }.collect<Any>(ReturnType.ROARING_BITSET)

        assertNotNull(query)
        val steps = query.steps
        assertEquals(4, steps.size)
        assertEquals("INPUT", steps[0].op())
        assertEquals("WALK_EDGE", steps[1].op())
        assertEquals("userToGroup", steps[1].relation())
        assertEquals("WALK_TARGET", steps[2].op())
        assertEquals("GROUP", steps[2].relation())
        assertEquals("COLLECT", steps[3].op())
    }

    @Test
    fun testInfixAndAttributeFilteringDsl() {
        val query = impulseQuery<Double> {
            input("Load", ArgType.SINGLE_NODE)
            walkEdgeFilteredAttribute("powerLine", "voltage", ">", 110.0)
            filterNodeAttribute("current", "<=", 50.0)
            projectExpression("voltage", "*", "current")
        }.reduceSum<Double>()

        assertNotNull(query)
        val steps = query.steps
        assertEquals(5, steps.size)
        assertEquals("WALK_EDGE_FILTERED", steps[1].op())
        assertEquals("powerLine:voltage:>:110.0", steps[1].relation())
        assertEquals("FILTER_NODE", steps[2].op())
        assertEquals("current:<=:50.0", steps[2].relation())
        assertEquals("PROJECT_EXPRESSION", steps[3].op())
        assertEquals("REDUCE_SUM", steps[4].op())
    }

    @Test
    fun testRepeatLoopDsl() {
        val query = impulseQuery<Any> {
            input("USER", ArgType.SINGLE_NODE)
            repeat(3) {
                walkEdge("friend")
            }
        }.collect<Any>(ReturnType.ROARING_BITSET)

        assertNotNull(query)
        val steps = query.steps
        assertEquals(3, steps.size)
        assertEquals("REPEAT", steps[1].op())
        assertEquals(3, steps[1].repeatCount())
        assertEquals(1, steps[1].subSteps().size)
        assertEquals("WALK_EDGE", steps[1].subSteps()[0].op())
        assertEquals("friend", steps[1].subSteps()[0].relation())
    }

    @Test
    fun testExtendedOpsDsl() {
        val query = impulseQuery<Boolean> {
            input("USER", ArgType.SINGLE_NODE)
            walkEdge("memberOf")
            extended {
                rebacCheck("view_doc")
            }
        }.collect<Boolean>(ReturnType.EXISTS)

        assertNotNull(query)
        val steps = query.steps
        assertEquals(4, steps.size)
        assertEquals("REBAC_CHECK", steps[2].op())
        assertEquals("view_doc", steps[2].relation())
    }

    @Test
    fun testDomainHelpers() {
        val rebacQuery = buildRebacQuery("Document", "owner", "edit")
        assertNotNull(rebacQuery)
        assertEquals(4, rebacQuery.steps.size)

        val hopQuery = buildNHopQuery("Node", "connectedTo", 4)
        assertNotNull(hopQuery)
        val repeatStep = hopQuery.steps[1]
        assertEquals("REPEAT", repeatStep.op())
        assertEquals(4, repeatStep.repeatCount())
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
        val query = impulseQuery<Any> {
            input("USER", ArgType.SINGLE_NODE)
        }.collect<Any>(ReturnType.ROARING_BITSET)

        val snapshot: ImpulseGraphSnapshot? = null
        val result = query.execute(snapshot, 100L)
        assertNotNull(result)
    }

    @Test
    fun testAsyncExecutionExtension() = runTest {
        val query = impulseQuery<Any> {
            input("USER", ArgType.SINGLE_NODE)
        }.collect<Any>(ReturnType.ROARING_BITSET)

        val snapshot: ImpulseGraphSnapshot? = null
        val result = query.execute(snapshot, 200L)
        assertNotNull(result)
    }
}
