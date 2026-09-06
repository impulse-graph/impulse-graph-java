package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, ImpulseGraphQuery, ImpulseQueryBuilder, ReturnType}
import scala.annotation.targetName

class ImpulseQueryDsl[R](private val builder: ImpulseQueryBuilder[R] = new ImpulseQueryBuilder[R]()):

  def input(entityType: String, argType: ArgType = ArgType.SINGLE_NODE): ImpulseQueryDsl[R] =
    builder.input(entityType, argType)
    this

  def input(entityType: String, seed: NodeId): ImpulseQueryDsl[R] =
    builder.input(entityType, ArgType.SINGLE_NODE)
    this

  infix def walkEdge(relationName: String): ImpulseQueryDsl[R] =
    builder.walkEdge(relationName)
    this

  @targetName("walkEdgeRel")
  infix def walkEdge(relationName: RelationName): ImpulseQueryDsl[R] =
    builder.walkEdge(relationName.value)
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

  def reduceSum[T](): ImpulseGraphQuery[T] = builder.reduceSum[T]()
  def reduceMax[T](): ImpulseGraphQuery[T] = builder.reduceMax[T]()
  def reduceMin[T](): ImpulseGraphQuery[T] = builder.reduceMin[T]()
  def reduceAvg[T](): ImpulseGraphQuery[T] = builder.reduceAvg[T]()
  def reduceFirst[T](): ImpulseGraphQuery[T] = builder.reduceFirst[T]()

  def collect[T](returnType: ReturnType): ImpulseGraphQuery[T] = builder.collect[T](returnType)

  def build(): ImpulseQueryBuilder[R] = builder

class ExtendedOpsDsl[R](private val extOps: ImpulseQueryBuilder.ExtendedOps[R]):
  def islandDetect(src1Reg: Int, src2Reg: Int): Unit = extOps.islandDetect(src1Reg, src2Reg)
  def rebacCheck(permission: String): Unit = extOps.rebacCheck(permission)
  def motifMatch3(): Unit = extOps.motifMatch3()

// Context Functions for receiver-less Scala 3 DSL syntax
def input(entityType: String, argType: ArgType = ArgType.SINGLE_NODE)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.input(entityType, argType)

def input(entityType: String, seed: NodeId)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.input(entityType, seed)

def walkEdge(relationName: String)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdge(relationName)

@targetName("walkEdgeRel")
def walkEdge(relationName: RelationName)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.walkEdge(relationName)

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

def repeat(count: Int)(fn: ImpulseQueryDsl[?] ?=> Unit)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.asInstanceOf[ImpulseQueryDsl[Any]].repeat(count)(fn)

def repeatUntilStable(fn: ImpulseQueryDsl[?] ?=> Unit)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.asInstanceOf[ImpulseQueryDsl[Any]].repeatUntilStable(fn)

def extended(fn: ExtendedOpsDsl[?] ?=> Unit)(using dsl: ImpulseQueryDsl[?]): Unit =
  dsl.asInstanceOf[ImpulseQueryDsl[Any]].extended(fn)

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
