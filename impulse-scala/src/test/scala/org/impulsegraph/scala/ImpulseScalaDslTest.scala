package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, ImpulseGraphSnapshot, ReturnType}
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.*

class ImpulseScalaDslTest:

  @Test
  def testSimpleDslQueryConstruction(): Unit =
    val query = impulseQuery[Any] { dsl =>
      dsl.input("USER", ArgType.SINGLE_NODE)
      dsl.walkEdge("userToGroup")
      dsl.walkTarget("GROUP")
    }.collect[Any](ReturnType.ROARING_BITSET)

    assertNotNull(query)
    val steps = query.getSteps
    assertEquals(4, steps.size())
    assertEquals("INPUT", steps.get(0).op())
    assertEquals("WALK_EDGE", steps.get(1).op())
    assertEquals("userToGroup", steps.get(1).relation())
    assertEquals("WALK_TARGET", steps.get(2).op())
    assertEquals("GROUP", steps.get(2).relation())
    assertEquals("COLLECT", steps.get(3).op())

  @Test
  def testInfixAndAttributeFilteringDsl(): Unit =
    val query = impulseQuery[java.lang.Double] { dsl =>
      dsl.input("Load", ArgType.SINGLE_NODE)
      dsl.walkEdgeFilteredAttribute("powerLine", "voltage", ">", 110.0)
      dsl.filterNodeAttribute("current", "<=", 50.0)
      dsl.projectExpression("voltage", "*", "current")
    }.reduceSum[java.lang.Double]()

    assertNotNull(query)
    val steps = query.getSteps
    assertEquals(5, steps.size())
    assertEquals("WALK_EDGE_FILTERED", steps.get(1).op())
    assertEquals("powerLine:voltage:>:110.0", steps.get(1).relation())
    assertEquals("FILTER_NODE", steps.get(2).op())
    assertEquals("PROJECT_EXPRESSION", steps.get(3).op())
    assertEquals("REDUCE_SUM", steps.get(4).op())

  @Test
  def testRepeatLoopDsl(): Unit =
    val query = impulseQuery[Any] { dsl =>
      dsl.input("USER", ArgType.SINGLE_NODE)
      dsl.repeat(3) { subDsl =>
        subDsl.walkEdge("friend")
      }
    }.collect[Any](ReturnType.ROARING_BITSET)

    assertNotNull(query)
    val steps = query.getSteps
    assertEquals(3, steps.size())
    assertEquals("REPEAT", steps.get(1).op())
    assertEquals(3, steps.get(1).repeatCount())
    assertEquals(1, steps.get(1).subSteps().size())

  @Test
  def testOpaqueTypes(): Unit =
    val nodeId = DomainTypes.NodeId(42L)
    val relationName = DomainTypes.RelationName("friend")
    assertEquals(42L, nodeId.value)
    assertEquals("friend", relationName.value)

  @Test
  def testAsyncExecution(): Unit =
    val query = impulseQuery[Any] { dsl =>
      dsl.input("USER", ArgType.SINGLE_NODE)
    }.collect[Any](ReturnType.ROARING_BITSET)

    val snapshot: ImpulseGraphSnapshot = null
    val future = snapshot.executeAsync(query, 100L.asInstanceOf[AnyRef])
    val result = Await.result(future, 2.seconds)
    assertNotNull(result)
