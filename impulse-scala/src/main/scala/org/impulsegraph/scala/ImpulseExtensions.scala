package org.impulsegraph.scala

import org.impulsegraph.api.{ImpulseGraphQuery, ImpulseGraphSnapshot}
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.api.spi.ImpulseEngineRegistry
import org.impulsegraph.api.statement.ImpulseStatement
import org.impulsegraph.api.traversal.DomainView
import org.impulsegraph.compiler.ast.ImpScmNode
import java.lang.foreign.Arena
import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

object ImpulseSnapshots:
  def load(path: Path): ImpulseGraphSnapshot =
    ImpulseGraphSnapshot.load(path)

  def load(path: Path, arena: Arena): ImpulseGraphSnapshot =
    ImpulseEngineRegistry.getProvider.loadSnapshot(path, arena)

  def withSnapshot[T](path: Path)(fn: ImpulseGraphSnapshot => T): Try[T] = Try {
    val arena = Arena.ofShared()
    val snap =
      try load(path, arena)
      catch
        case t: Throwable =>
          arena.close()
          throw t
    try fn(snap)
    finally snap.close()
  }

extension (snapshot: ImpulseGraphSnapshot)
  def apply(relationName: String): Long = snapshot.getEdgeCount(relationName)

  def edgeCountOption(relationName: String): Option[Long] =
    if snapshot.getRelationNames.contains(relationName) then Some(snapshot.getEdgeCount(relationName))
    else None

  def metadataOption(key: String): Option[String] =
    Option(snapshot.getMetadata(key))

  def domainNames: Set[String] =
    snapshot.getDomainNames.asScala.toSet

  def relationNames: Set[String] =
    snapshot.getRelationNames.asScala.toSet

  def domainView(domainName: String): DomainView =
    snapshot.domain(domainName)

  def usingStatement[T](query: String)(fn: ImpulseStatement => T): Try[T] =
    Using(snapshot.prepare(query))(fn)

  def apply[R](query: ImpulseGraphQuery[R]): R = query.execute(snapshot, null)
  def apply[R](query: ImpulseGraphQuery[R], input: Any): R = query.execute(snapshot, boxValue(input))

  def containsRelation(relationName: String): Boolean = snapshot.getRelationNames.contains(relationName)

  def size: Int = snapshot.getRelationCount

  def execute[R](query: ImpulseGraphQuery[R], input: Any = null): R =
    query.execute(snapshot, boxValue(input))

  def executeAsync[R](query: ImpulseGraphQuery[R], input: Any = null)(using ec: ExecutionContext): Future[R] =
    val boxed = boxValue(input)
    Future(blocking(query.execute(snapshot, boxed)))

extension [R](query: ImpulseGraphQuery[R])
  def apply(snapshot: ImpulseGraphSnapshot): R = query.execute(snapshot, null)
  def apply(snapshot: ImpulseGraphSnapshot, input: Any): R = query.execute(snapshot, boxValue(input))
  def steps: Seq[ImpScmNode] =
    query.getAst match
      case p: org.impulsegraph.compiler.ast.ScmProgram => p.steps.asScala.toSeq
      case _                                           => Seq.empty

extension (bitset: ImpulseBitSet)
  def toScalaSeq: Seq[Int] =
    val buf = List.newBuilder[Int]
    val it = bitset.iterator()
    while it.hasNext do buf += it.next().intValue()
    buf.result()

  def toScalaSet: Set[Int] =
    toScalaSeq.toSet

  def containsNode(nodeId: Int): Boolean =
    bitset.get(nodeId)
