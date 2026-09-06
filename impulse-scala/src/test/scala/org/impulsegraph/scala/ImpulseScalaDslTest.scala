package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, ImpulseGraphSnapshot, ReturnType}
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImpulseScalaDslTest:

  @Test
  def testSimpleDslQueryConstruction(): Unit =
    // Clean Scala 3 receiver-less context function syntax
    val query = impulseQuery[Any] {
      input("USER", ArgType.SINGLE_NODE)
      walkEdge("userToGroup")
      walkTarget("GROUP")
    }.collect[Any](ReturnType.ROARING_BITSET)

    assertNotNull(query)
    val querySteps = query.steps
    assertEquals(3, querySteps.size())
    val ast = query.exportAst()
    assertTrue(ast.contains("userToGroup"))
    assertTrue(ast.contains("GROUP"))

  @Test
  def testInfixAndAttributeFilteringDsl(): Unit =
    val query = impulseQuery[java.lang.Double] {
      input("Load", ArgType.SINGLE_NODE)
      walkEdgeFilteredAttribute("powerLine", "voltage", ">", 110.0)
      filterNodeAttribute("current", "<=", 50.0)
      projectExpression("voltage", "*", "current")
    }.reduceSum[java.lang.Double]()

    assertNotNull(query)
    val ast = query.exportAst()
    assertTrue(ast.contains("powerLine"))
    assertTrue(ast.contains("voltage"))
    assertTrue(ast.contains("current"))
    assertTrue(ast.contains("reduce-sum"))

  @Test
  def testRepeatLoopDsl(): Unit =
    val query = impulseQuery[Any] {
      input("USER", ArgType.SINGLE_NODE)
      repeat(3) {
        walkEdge("friend")
      }
    }.collect[Any](ReturnType.ROARING_BITSET)

    assertNotNull(query)
    val ast = query.exportAst()
    assertTrue(ast.contains("friend"))

  @Test
  def testOpaqueTypes(): Unit =
    val nodeId = NodeId(42L)
    val relationName = RelationName("friend")
    assertEquals(42L, nodeId.value)
    assertEquals("friend", relationName.value)

  @Test
  def testExplicitParameterBackwardCompatibility(): Unit =
    val query = impulseQuery[Any] { (dsl: ImpulseQueryDsl[Any]) ?=>
      dsl.input("USER", NodeId(42L))
      dsl.walkEdge(RelationName("friend"))
    }.collect[Any](ReturnType.ROARING_BITSET)

    assertNotNull(query)
    assertTrue(query.exportAst().contains("friend"))
