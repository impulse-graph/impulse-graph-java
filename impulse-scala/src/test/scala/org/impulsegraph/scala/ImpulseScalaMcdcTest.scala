package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, ReturnType}
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.storage.csr.{DefaultSnapshotBuilder, GraphSnapshot, RelationSnapshot}
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertNotNull, assertTrue}
import org.junit.jupiter.api.Test

import java.lang.foreign.{Arena, ValueLayout}
import java.nio.file.Files
import java.util

class ImpulseScalaMcdcTest:

  private def allocInts(arena: Arena, values: Int*) =
    val seg = arena.allocate(ValueLayout.JAVA_INT.byteSize() * values.length, ValueLayout.JAVA_INT.byteAlignment())
    for (v, i) <- values.zipWithIndex do seg.setAtIndex(ValueLayout.JAVA_INT, i.toLong, v)
    seg

  private def createSyntheticGraph(arena: Arena): GraphSnapshot =
    val fRow = allocInts(arena, 0, 1, 2, 3, 3)
    val fCol = allocInts(arena, 1, 2, 3)
    val fCscRow = allocInts(arena, 0, 0, 1, 2, 3)
    val fCscCol = allocInts(arena, 0, 1, 2)
    val relFollows = new RelationSnapshot(arena, 4, 3, fRow, fCol, fCscRow, fCscCol)
    val rels = new util.HashMap[String, RelationSnapshot]()
    rels.put("follows", relFollows)
    val meta = new util.HashMap[String, String]()
    meta.put("domain.User.nodeCount", "4")
    meta.put("domain.0.name", "User")
    meta.put("custom.key", "v")
    new GraphSnapshot(arena, rels, meta)

  @Test
  def testAllDslMethodsBoxValueAndReductions(): Unit =
    val seededCount = impulseQuery[java.lang.Long] {
      input("User", NodeId(0L))
      bindParameter("i", 1)
      bindParameter("l", 2L)
      bindParameter("d", 3.5d)
      bindParameter("f", 1.5f)
      bindParameter("b", true)
      bindParameter("s", "abc")
      bindParameter("nid", NodeId(7L))
      bindParameter("other", List(1, 2))
      bindParameters(Map("k" -> 42))
      walkEdge(RelationName("follows"))
    }.collectCount()

    // Verify bindParameter rejects null value eagerly
    org.junit.jupiter.api.Assertions.assertThrows(
      classOf[NullPointerException],
      () =>
        impulseQuery[java.lang.Long] {
          input("User")
          bindParameter("n", null)
        }
    )

    assertNotNull(seededCount.getAst)
    assertTrue(seededCount.exportAst().contains("follows"))
    assertNotNull(seededCount.getOperationName)
    assertNotNull(seededCount.getParameters)
    assertTrue(seededCount.steps.nonEmpty)

    val seededBitSet = impulseQuery[ImpulseBitSet] {
      input("User", NodeId(0L))
      walkEdge(RelationName("follows"))
    }.collectBitSet()

    Arena.ofConfined().use { arena =>
      val snap = createSyntheticGraph(arena)
      assertEquals(java.lang.Long.valueOf(1L), snap(seededCount))
      val bs = snap(seededBitSet)
      assertEquals(1, bs.cardinality())
      assertTrue(bs.containsNode(1))
      assertFalse(bs.containsNode(0))
      assertEquals(1, seededBitSet(snap, java.lang.Long.valueOf(0L)).cardinality())
      assertTrue(snap.containsRelation("follows"))
      assertFalse(snap.containsRelation("missing"))
      assertEquals(Some("v"), snap.metadataOption("custom.key"))
      assertEquals(None, snap.metadataOption("missing.key"))
      assertEquals(Some(3L), snap.edgeCountOption("follows"))
      assertEquals(None, snap.edgeCountOption("missing"))
      assertTrue(snap.relationNames.contains("follows"))
      assertEquals(1, snap.size)
      assertEquals(Seq(1), bs.toScalaSeq)
      assertEquals(Set(1), bs.toScalaSet)
      assertNotNull(snap.domainNames)

      val stmtOk = snap.usingStatement("SELECT * FROM follows") { stmt =>
        assertNotNull(stmt)
        "ok"
      }
      assertEquals(scala.util.Success("ok"), stmtOk)
    }

    val allWalks = impulseQuery[AnyRef] {
      input("User")
      walkReverse(RelationName("follows"))
      walkBoth(RelationName("follows"))
      walkTarget("User")
      walkTarget(RelationName("User"))
      walkOutUnion("follows")
      walkInUnion("follows")
      walkBothUnion("follows")
      walkEdgeFiltered("follows", "x > 0")
      walkEdgeFiltered(RelationName("follows"), "x > 0")
      walkEdgeFilteredAttribute(RelationName("follows"), "age", ">", 10.0)
      walkEdgeWithCel("follows", "node.id >= 0")
      walkEdgeWithCel(RelationName("follows"), "node.id >= 0")
      walkReverseWithCel("follows", "node.id >= 0")
      walkReverseWithCel(RelationName("follows"), "node.id >= 0")
      walkEdgeWithState("follows", "dist + 1")
      filter("node.id >= 0")
      filterWithCel("node.id >= 0")
      projectState("1.0")
      repeatUntilStable {
        walkEdge("follows")
      }
      vectorRerank(Array(1.0f, 2.0f), 1, 0)
      extended {
        rebacCheck("view")
      }
    }.collect(ReturnType.ROARING_BITSET)
    val allAst = allWalks.exportAst()
    assertTrue(allAst.contains("follows"))
    assertTrue(allAst.contains("x > 0"))
    assertTrue(allAst.contains("node.id >= 0"))
    assertTrue(allAst.contains("dist + 1"))
    assertTrue(allAst.contains("view"))
    assertTrue(allWalks.steps.size >= 15)

    assertTrue(
      impulseQuery[java.lang.Double] { input("User"); walkEdge("follows"); projectState("1.0") }
        .reduceMax()
        .exportAst()
        .contains("reduce")
    )
    assertTrue(
      impulseQuery[java.lang.Double] { input("User"); walkEdge("follows"); projectState("1.0") }
        .reduceMin()
        .exportAst()
        .contains("reduce")
    )
    assertTrue(
      impulseQuery[java.lang.Double] { input("User"); walkEdge("follows"); projectState("1.0") }
        .reduceAvg()
        .exportAst()
        .contains("reduce")
    )
    assertTrue(impulseQuery[AnyRef] { input("User"); walkEdge("follows") }.reduceFirst().exportAst().contains("reduce"))
    assertTrue(
      impulseQuery[java.lang.Integer] { input("User"); walkEdge("follows") }
        .reduceArgMax()
        .exportAst()
        .contains("reduce")
    )
    assertTrue(
      impulseQuery[java.lang.Integer] { input("User"); walkEdge("follows") }
        .reduceArgMin()
        .exportAst()
        .contains("reduce")
    )

    val builderDsl = new ImpulseQueryDsl[ImpulseBitSet]()
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.input("User"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.bindParameter("p", 1))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.bindParameters(Map("q" -> 2)))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkEdge("follows"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkEdge(RelationName("follows")))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkReverse("follows"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkReverse(RelationName("follows")))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkBoth("follows"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkBoth(RelationName("follows")))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkTarget("User"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkTarget(RelationName("User")))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkOutUnion("follows"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkInUnion("follows"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkBothUnion("follows"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkEdgeWithCel("follows", "node.id >= 0"))
    org.junit.jupiter.api.Assertions.assertSame(
      builderDsl,
      builderDsl.walkEdgeWithCel(RelationName("follows"), "node.id >= 0")
    )
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkReverseWithCel("follows", "node.id >= 0"))
    org.junit.jupiter.api.Assertions.assertSame(
      builderDsl,
      builderDsl.walkReverseWithCel(RelationName("follows"), "node.id >= 0")
    )
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkEdgeWithState("follows", "dist + 1"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.projectState("1.0"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.filter("node.id >= 0"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.filterWithCel("node.id >= 0"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.vectorRerank(Array(1.0f, 2.0f), 1, 0))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.walkEdgeFiltered("follows", "x > 0"))
    org.junit.jupiter.api.Assertions.assertSame(
      builderDsl,
      builderDsl.walkEdgeFiltered(RelationName("follows"), "x > 0")
    )
    org.junit.jupiter.api.Assertions.assertSame(
      builderDsl,
      builderDsl.walkEdgeFilteredAttribute("follows", "age", ">", 10.0)
    )
    org.junit.jupiter.api.Assertions.assertSame(
      builderDsl,
      builderDsl.walkEdgeFilteredAttribute(RelationName("follows"), "age", ">", 10.0)
    )
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.filterNodeAttribute("age", ">", 10.0))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.projectExpression("age", "+", "weight"))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.repeat(1)(walkEdge("follows")))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.repeatUntilStable(walkEdge("follows")))
    org.junit.jupiter.api.Assertions.assertSame(builderDsl, builderDsl.extended(rebacCheck("view")))
    assertNotNull(builderDsl.build())

  @Test
  def testSnapshotLoadWithSnapshotAndDomainHelpers(): Unit =
    val rebac = buildRebacQuery("Doc", "viewer", "read")
    assertTrue(rebac.exportAst().contains("viewer"))

    val tmp = Files.createTempFile("scala-mcdc-snap", ".imps")
    try
      Arena.ofConfined().use { writeArena =>
        Files.write(tmp, DefaultSnapshotBuilder.writeSnapshotBytes(createSyntheticGraph(writeArena)))
      }
      val loaded = ImpulseSnapshots.load(tmp)
      loaded.close()
      val edgeCnt = ImpulseSnapshots.withSnapshot(tmp) { snap =>
        snap.getEdgeCount("follows")
      }
      assertEquals(scala.util.Success(3L), edgeCnt)
    finally Files.deleteIfExists(tmp)

extension [T <: AutoCloseable](resource: T)
  private def use[R](f: T => R): R =
    try f(resource)
    finally resource.close()
