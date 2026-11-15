package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, ImpulseGraphQuery, ImpulseGraphSnapshot, ImpulseQueryBuilder, ReturnType}
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.compiler.ast.ImpScmNode
import scala.annotation.targetName
import scala.jdk.CollectionConverters.*

private[scala] final class SeededImpulseGraphQuery[R](
    private val delegate: ImpulseGraphQuery[R],
    private val defaultSeed: Long
) extends ImpulseGraphQuery[R]:
  override def execute(snapshot: ImpulseGraphSnapshot, input: AnyRef): R =
    val effectiveInput: AnyRef = if input == null then java.lang.Long.valueOf(defaultSeed) else input
    delegate.execute(snapshot, effectiveInput)
  override def getAst: ImpScmNode = delegate.getAst
  override def getOperationName: String = delegate.getOperationName
  override def getParameters: java.util.Map[String, AnyRef] = delegate.getParameters
  override def exportAst(): String = delegate.exportAst()

class ImpulseQueryDsl[R](private val builder: ImpulseQueryBuilder[R] = new ImpulseQueryBuilder[R]()):
  private var boundSeed: Option[Long] = None

  def input(entityType: String, argType: ArgType = ArgType.SINGLE_NODE): ImpulseQueryDsl[R] =
    builder.input(entityType, argType)
    this

  def input(entityType: String, seed: NodeId): ImpulseQueryDsl[R] =
    boundSeed = Some(seed.value)
    builder.input(entityType, ArgType.SINGLE_NODE)
    this

  def bindParameter(name: String, value: Any): ImpulseQueryDsl[R] =
    builder.bindParameter(name, boxValue(value))
    this

  def bindParameters(params: Map[String, Any]): ImpulseQueryDsl[R] =
    val boxed = params.view.mapValues(boxValue).toMap.asJava
    builder.bindParameters(boxed)
    this

  infix def walkEdge(relationName: String): ImpulseQueryDsl[R] =
    builder.walkEdge(relationName)
    this

  @targetName("walkEdgeRel")
  infix def walkEdge(relationName: RelationName): ImpulseQueryDsl[R] =
    builder.walkEdge(relationName.value)
    this

  infix def walkReverse(relationName: String): ImpulseQueryDsl[R] =
    builder.walkReverse(relationName)
    this

  @targetName("walkReverseRel")
  infix def walkReverse(relationName: RelationName): ImpulseQueryDsl[R] =
    builder.walkReverse(relationName.value)
    this

  infix def walkBoth(relationName: String): ImpulseQueryDsl[R] =
    builder.walkBoth(relationName)
    this

  @targetName("walkBothRel")
  infix def walkBoth(relationName: RelationName): ImpulseQueryDsl[R] =
    builder.walkBoth(relationName.value)
    this

  def walkOutUnion(relationNames: String*): ImpulseQueryDsl[R] =
    builder.walkOutUnion(relationNames*)
    this

  def walkInUnion(relationNames: String*): ImpulseQueryDsl[R] =
    builder.walkInUnion(relationNames*)
    this

  def walkBothUnion(relationNames: String*): ImpulseQueryDsl[R] =
    builder.walkBothUnion(relationNames*)
    this

  def walkEdgeWithCel(relationName: String, celExpr: String): ImpulseQueryDsl[R] =
    builder.walkEdgeWithCel(relationName, celExpr)
    this

  @targetName("walkEdgeWithCelRel")
  def walkEdgeWithCel(relationName: RelationName, celExpr: String): ImpulseQueryDsl[R] =
    builder.walkEdgeWithCel(relationName.value, celExpr)
    this

  def walkReverseWithCel(relationName: String, celExpr: String): ImpulseQueryDsl[R] =
    builder.walkReverseWithCel(relationName, celExpr)
    this

  @targetName("walkReverseWithCelRel")
  def walkReverseWithCel(relationName: RelationName, celExpr: String): ImpulseQueryDsl[R] =
    builder.walkReverseWithCel(relationName.value, celExpr)
    this

  def walkEdgeWithState(relationName: String, stateProjections: String): ImpulseQueryDsl[R] =
    builder.walkEdgeWithState(relationName, stateProjections)
    this

  def projectState(projectionExpr: String): ImpulseQueryDsl[R] =
    builder.projectState(projectionExpr)
    this

  def filterWithCel(celExpr: String): ImpulseQueryDsl[R] =
    builder.filterWithCel(celExpr)
    this

  def filter(celExpr: String): ImpulseQueryDsl[R] =
    builder.filter(celExpr)
    this

  def vectorRerank(queryVector: Array[Float], attrId: Int, metric: Int): ImpulseQueryDsl[R] =
    builder.vectorRerank(queryVector, attrId, metric)
    this

  def walkEdgeFiltered(relationName: String, filterLabel: String): ImpulseQueryDsl[R] =
    builder.walkEdgeFiltered(relationName, filterLabel)
    this

  @targetName("walkEdgeFilteredRel")
  def walkEdgeFiltered(relationName: RelationName, filterLabel: String): ImpulseQueryDsl[R] =
    builder.walkEdgeFiltered(relationName.value, filterLabel)
    this

  def walkEdgeFilteredAttribute(
      relationName: String,
      attributeName: String,
      op: String,
      value: Double
  ): ImpulseQueryDsl[R] =
    builder.walkEdgeFilteredAttribute(relationName, attributeName, op, value)
    this

  @targetName("walkEdgeFilteredAttributeRel")
  def walkEdgeFilteredAttribute(
      relationName: RelationName,
      attributeName: String,
      op: String,
      value: Double
  ): ImpulseQueryDsl[R] =
    builder.walkEdgeFilteredAttribute(relationName.value, attributeName, op, value)
    this

  infix def walkTarget(relationName: String): ImpulseQueryDsl[R] =
    builder.walkTarget(relationName)
    this

  @targetName("walkTargetRel")
  infix def walkTarget(relationName: RelationName): ImpulseQueryDsl[R] =
    builder.walkTarget(relationName.value)
    this

  def filterNodeAttribute(attributeName: String, op: String, value: Double): ImpulseQueryDsl[R] =
    builder.filterNodeAttribute(attributeName, op, value)
    this

  def projectExpression(nodeAttribute: String, operator: String, edgeAttribute: String): ImpulseQueryDsl[R] =
    builder.projectExpression(nodeAttribute, operator, edgeAttribute)
    this

  def repeat(count: Int)(fn: ImpulseQueryDsl[R] ?=> Unit): ImpulseQueryDsl[R] =
    builder.repeat(
      subBuilder => {
        val subDsl = new ImpulseQueryDsl[R](subBuilder)
        fn(using subDsl)
        subBuilder
      },
      count
    )
    this

  def repeatUntilStable(fn: ImpulseQueryDsl[R] ?=> Unit): ImpulseQueryDsl[R] =
    builder.repeatUntilStable(subBuilder => {
      val subDsl = new ImpulseQueryDsl[R](subBuilder)
      fn(using subDsl)
      subBuilder
    })
    this

  def extended(fn: ExtendedOpsDsl[R] ?=> Unit): ImpulseQueryDsl[R] =
    val extOps = builder.extended()
    val extDsl = new ExtendedOpsDsl[R](extOps)
    fn(using extDsl)
    this

  private def wrapSeed[T](query: ImpulseGraphQuery[T]): ImpulseGraphQuery[T] =
    boundSeed match
      case Some(seed) => new SeededImpulseGraphQuery[T](query, seed)
      case None       => query

  def reduceSum(): ImpulseGraphQuery[java.lang.Double] = wrapSeed(builder.reduceSum())
  def reduceMax(): ImpulseGraphQuery[java.lang.Double] = wrapSeed(builder.reduceMax())
  def reduceMin(): ImpulseGraphQuery[java.lang.Double] = wrapSeed(builder.reduceMin())
  def reduceAvg(): ImpulseGraphQuery[java.lang.Double] = wrapSeed(builder.reduceAvg())
  def reduceFirst(): ImpulseGraphQuery[AnyRef] = wrapSeed(builder.reduceFirst())
  def reduceArgMax(): ImpulseGraphQuery[Integer] = wrapSeed(builder.reduceArgMax())
  def reduceArgMin(): ImpulseGraphQuery[Integer] = wrapSeed(builder.reduceArgMin())

  def collect(returnType: ReturnType): ImpulseGraphQuery[R] = wrapSeed(builder.collect[R](returnType))
  def collectBitSet(): ImpulseGraphQuery[ImpulseBitSet] = wrapSeed(
    builder.collect[ImpulseBitSet](ReturnType.DENSE_BITSET)
  )
  def collectCount(): ImpulseGraphQuery[java.lang.Long] = wrapSeed(builder.collect[java.lang.Long](ReturnType.COUNT))

  def build(): ImpulseQueryBuilder[R] = builder

private[scala] def boxValue(v: Any): AnyRef = v match
  case null       => null
  case x: AnyRef  => x
  case x: Long    => java.lang.Long.valueOf(x)
  case x: Int     => java.lang.Integer.valueOf(x)
  case x: Double  => java.lang.Double.valueOf(x)
  case x: Float   => java.lang.Float.valueOf(x)
  case x: Boolean => java.lang.Boolean.valueOf(x)
  case x: Short   => java.lang.Short.valueOf(x)
  case x: Byte    => java.lang.Byte.valueOf(x)

class ExtendedOpsDsl[R](private val extOps: ImpulseQueryBuilder.ExtendedOps[R]):
  def islandDetect(src1Reg: Int, src2Reg: Int): Unit = extOps.islandDetect(src1Reg, src2Reg)
  def rebacCheck(permission: String): Unit = extOps.rebacCheck(permission)
  def motifMatch3(): Unit = extOps.motifMatch3()

// Context Functions for receiver-less Scala 3 DSL syntax
def input(entityType: String, argType: ArgType = ArgType.SINGLE_NODE)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.input(entityType, argType)

def input(entityType: String, seed: NodeId)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.input(entityType, seed)

def bindParameter(name: String, value: Any)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.bindParameter(name, value)

def bindParameters(params: Map[String, Any])(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.bindParameters(params)

def walkEdge(relationName: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdge(relationName)

@targetName("walkEdgeRel")
def walkEdge(relationName: RelationName)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdge(relationName)

def walkReverse(relationName: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkReverse(relationName)

@targetName("walkReverseRel")
def walkReverse(relationName: RelationName)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkReverse(relationName)

def walkBoth(relationName: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkBoth(relationName)

@targetName("walkBothRel")
def walkBoth(relationName: RelationName)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkBoth(relationName)

def walkOutUnion(relationNames: String*)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkOutUnion(relationNames*)

def walkInUnion(relationNames: String*)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkInUnion(relationNames*)

def walkBothUnion(relationNames: String*)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkBothUnion(relationNames*)

def walkEdgeWithCel(relationName: String, celExpr: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdgeWithCel(relationName, celExpr)

def walkReverseWithCel(relationName: String, celExpr: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkReverseWithCel(relationName, celExpr)

def walkEdgeWithState(relationName: String, stateProjections: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdgeWithState(relationName, stateProjections)

def projectState(projectionExpr: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.projectState(projectionExpr)

def filterWithCel(celExpr: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.filterWithCel(celExpr)

def filter(celExpr: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.filter(celExpr)

def vectorRerank(queryVector: Array[Float], attrId: Int, metric: Int)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.vectorRerank(queryVector, attrId, metric)

def walkTarget(relationName: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkTarget(relationName)

@targetName("walkTargetRel")
def walkTarget(relationName: RelationName)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkTarget(relationName)

def walkEdgeFiltered(relationName: String, filterLabel: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdgeFiltered(relationName, filterLabel)

@targetName("walkEdgeFilteredRel")
def walkEdgeFiltered(relationName: RelationName, filterLabel: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdgeFiltered(relationName, filterLabel)

def walkEdgeFilteredAttribute(
    relationName: String,
    attributeName: String,
    op: String,
    value: Double
)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdgeFilteredAttribute(relationName, attributeName, op, value)

@targetName("walkEdgeFilteredAttributeRel")
def walkEdgeFilteredAttribute(
    relationName: RelationName,
    attributeName: String,
    op: String,
    value: Double
)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdgeFilteredAttribute(relationName, attributeName, op, value)

def filterNodeAttribute(attributeName: String, op: String, value: Double)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.filterNodeAttribute(attributeName, op, value)

def projectExpression(nodeAttribute: String, operator: String, edgeAttribute: String)(using
    dsl: ImpulseQueryDsl[?]
): Unit =
  dsl.projectExpression(nodeAttribute, operator, edgeAttribute)

def repeat[R](count: Int)(fn: ImpulseQueryDsl[R] ?=> Unit)(using dsl: ImpulseQueryDsl[R]): Unit =
  dsl.repeat(count)(fn)

def repeatUntilStable[R](fn: ImpulseQueryDsl[R] ?=> Unit)(using dsl: ImpulseQueryDsl[R]): Unit =
  dsl.repeatUntilStable(fn)

def extended[R](fn: ExtendedOpsDsl[R] ?=> Unit)(using dsl: ImpulseQueryDsl[R]): Unit =
  dsl.extended(fn)

def rebacCheck(permission: String)(using ext: ExtendedOpsDsl[?]): Unit =
  ext.rebacCheck(permission)

def islandDetect(src1Reg: Int, src2Reg: Int)(using ext: ExtendedOpsDsl[?]): Unit =
  ext.islandDetect(src1Reg, src2Reg)

def motifMatch3()(using ext: ExtendedOpsDsl[?]): Unit =
  ext.motifMatch3()

def impulseQuery[R](fn: ImpulseQueryDsl[R] ?=> Unit): ImpulseQueryDsl[R] =
  val dsl = new ImpulseQueryDsl[R]()
  fn(using dsl)
  dsl
