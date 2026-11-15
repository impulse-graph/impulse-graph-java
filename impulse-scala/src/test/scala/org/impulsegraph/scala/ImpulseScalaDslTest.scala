package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, ImpulseGraphSnapshot, ReturnType}
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.storage.csr.{DefaultSnapshotBuilder, GraphSnapshot, RelationSnapshot}
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

import java.lang.foreign.{Arena, ValueLayout}
import java.nio.file.Files
import java.util
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class ImpulseScalaDslTest:

  private def allocInts(arena: Arena, values: Int*) =
    val seg = arena.allocate(ValueLayout.JAVA_INT.byteSize() * values.length, ValueLayout.JAVA_INT.byteAlignment())
    for (v, i) <- values.zipWithIndex do seg.setAtIndex(ValueLayout.JAVA_INT, i.toLong, v)
    seg

  private def createSyntheticGraph(arena: Arena): GraphSnapshot =
    // follows: 0 -> 1, 1 -> 2, 2 -> 3
    val fRow = allocInts(arena, 0, 1, 2, 3, 3)
    val fCol = allocInts(arena, 1, 2, 3)
    val fCscRow = allocInts(arena, 0, 0, 1, 2, 3)
    val fCscCol = allocInts(arena, 0, 1, 2)
    val relFollows = new RelationSnapshot(arena, 4, 3, fRow, fCol, fCscRow, fCscCol)

    // userToGroup: 0 -> 1, 1 -> 2
    val u2gRow = allocInts(arena, 0, 1, 2, 2, 2)
    val u2gCol = allocInts(arena, 1, 2)
    val u2gCscRow = allocInts(arena, 0, 0, 1, 2, 2)
    val u2gCscCol = allocInts(arena, 0, 1)
    val relU2g = new RelationSnapshot(arena, 4, 2, u2gRow, u2gCol, u2gCscRow, u2gCscCol)

    val rels = new util.HashMap[String, RelationSnapshot]()
    rels.put("follows", relFollows)
    rels.put("userToGroup", relU2g)

    val meta = new util.HashMap[String, String]()
    meta.put("domain.User.nodeCount", "4")
    meta.put("domain.Group.nodeCount", "4")
    meta.put("domain.0.name", "User")
    meta.put("domain.1.name", "Group")
    new GraphSnapshot(arena, rels, meta)

  @Test
  def testSimpleDslQueryConstruction(): Unit =
    val query = impulseQuery[Any] {
      input("USER", ArgType.SINGLE_NODE)
      walkEdge("userToGroup")
      walkTarget("GROUP")
    }.collect(ReturnType.ROARING_BITSET)

    assertNotNull(query)
    val querySteps = query.steps
    assertEquals(3, querySteps.size)
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
    }.reduceSum()

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
    }.collect(ReturnType.ROARING_BITSET)

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
  def testExplicitParameterAndSeededExecution(): Unit =
    val arena = Arena.ofConfined()
    try
      val snap: ImpulseGraphSnapshot = createSyntheticGraph(arena)
      val seededQuery = impulseQuery[ImpulseBitSet] { (dsl: ImpulseQueryDsl[ImpulseBitSet]) ?=>
        dsl.input("User", NodeId(0L))
        dsl.walkEdge(RelationName("follows"))
      }.collectBitSet()

      val res = snap.execute(seededQuery)
      assertEquals(Set(1), res.toScalaSet)
      assertEquals(Set(2), snap.execute(seededQuery, 1L).toScalaSet)
    finally arena.close()

  @Test
  def testSnapshotExecutionAsyncWalkBothAndDomainView(): Unit =
    val arena = Arena.ofShared()
    try
      val snap: ImpulseGraphSnapshot = createSyntheticGraph(arena)
      assertEquals(Some(3L), snap.edgeCountOption("follows"))
      assertEquals(None, snap.edgeCountOption("missing"))
      assertTrue(snap.containsRelation("follows"))
      assertEquals(2, snap.size)
      assertTrue(snap.relationNames.contains("follows"))

      val twoHop = impulseQuery[ImpulseBitSet] {
        input("User", ArgType.SINGLE_NODE)
        repeat(2) {
          walkEdge("follows")
        }
      }.collectBitSet()

      val asyncRes = Await.result(snap.executeAsync(twoHop, 0L), 5.seconds)
      assertEquals(Seq(2), asyncRes.toScalaSeq)

      val bothQuery = impulseQuery[ImpulseBitSet] {
        input("User", ArgType.SINGLE_NODE)
        walkBoth("follows")
      }.collectBitSet()
      assertEquals(Set(0, 2), snap(bothQuery, 1L).toScalaSet)

      val reverseQuery = impulseQuery[ImpulseBitSet] {
        input("User", ArgType.SINGLE_NODE)
        walkReverse("follows")
      }.collectBitSet()
      assertEquals(Set(1), reverseQuery(snap, 2L).toScalaSet)

      val dv = snap.domainView("User")
      assertEquals(Set(1), dv.from(0L).out("follows").collect().toScalaSet)
    finally arena.close()

  @Test
  def testWithSnapshotFileLoadAndClose(): Unit =
    val tmp = Files.createTempFile("scala-snap-test", ".imps")
    try
      val writeArena = Arena.ofConfined()
      try
        val bytes = DefaultSnapshotBuilder.writeSnapshotBytes(createSyntheticGraph(writeArena))
        Files.write(tmp, bytes)
      finally writeArena.close()

      val result = ImpulseSnapshots.withSnapshot(tmp) { snap =>
        val q = impulseQuery[ImpulseBitSet] {
          input("User", NodeId(0L))
          walkEdge("follows")
        }.collectBitSet()
        snap.execute(q).toScalaSet
      }
      assertTrue(result.isSuccess)
      assertEquals(Set(1), result.get)
    finally Files.deleteIfExists(tmp)
