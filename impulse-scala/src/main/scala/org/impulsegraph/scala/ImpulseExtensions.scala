package org.impulsegraph.scala

import org.impulsegraph.api.{ImpulseGraphQuery, ImpulseGraphSnapshot, RelationSnapshot}
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.api.meta.{AttributeMeta, DomainMeta, IndexMeta, SnapshotHeader}
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

  def metadataMap: Map[String, String] =
    Option(snapshot.getMetadataMap).map(_.asScala.toMap).getOrElse(Map.empty)

  def headerOption: Option[SnapshotHeader] =
    Option(snapshot.getHeader)

  def fileSizeBytes: Long =
    snapshot.getFileSizeBytes

  def domains: Seq[DomainMeta] =
    Option(snapshot.getDomains).map(_.asScala.toSeq).getOrElse(Seq.empty)

  def domainMetaOption(name: String): Option[DomainMeta] =
    Option(snapshot.getDomainMeta(name))

  def domainMetaOption(domainId: Int): Option[DomainMeta] =
    Option(snapshot.getDomainMeta(domainId))

  def domainNameOption(domainId: Int): Option[String] =
    Option(snapshot.getDomainName(domainId))

  def relationSourceDomainNameOption(relationName: String): Option[String] =
    Option(snapshot.getRelationSourceDomainName(relationName))

  def relationTargetDomainNameOption(relationName: String): Option[String] =
    Option(snapshot.getRelationTargetDomainName(relationName))

  def secondaryIndices: Seq[IndexMeta] =
    Option(snapshot.getSecondaryIndexMetas).map(_.asScala.toSeq).getOrElse(Seq.empty)

  def sha256Checksum: String =
    Option(snapshot.getSha256Checksum).getOrElse("")

  def relationOption(name: String): Option[RelationSnapshot] =
    Option(snapshot.getRelationSnapshot(name))

  def relationOption(relationId: Int): Option[RelationSnapshot] =
    Option(snapshot.getRelationSnapshot(relationId))

  def relations: Map[String, RelationSnapshot] =
    Option(snapshot.getAllRelationSnapshots).map(_.asScala.toMap).getOrElse(Map.empty)

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

extension (rel: RelationSnapshot)
  def attributeMetas: Seq[AttributeMeta] =
    Option(rel.getAttributeMetas).map(_.asScala.toSeq).getOrElse(Seq.empty)

  def attributeMetaOption(name: String): Option[AttributeMeta] =
    attributeMetas.find(_.name() == name)

  def componentIds: Seq[Int] =
    Option(rel.getComponentIds).map(_.toSeq).getOrElse(Seq.empty)

extension (domain: DomainMeta)
  def attributeMetas: Seq[AttributeMeta] =
    Option(domain.attributes()).map(_.asScala.toSeq).getOrElse(Seq.empty)

  def attributeMetaOption(name: String): Option[AttributeMeta] =
    attributeMetas.find(_.name() == name)

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
