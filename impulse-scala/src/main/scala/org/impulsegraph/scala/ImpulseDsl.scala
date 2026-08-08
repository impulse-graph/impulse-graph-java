package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, ImpulseGraphQuery, ImpulseQueryBuilder, ReturnType}
import java.util.function.Function

class ImpulseQueryDsl[R](private val builder: ImpulseQueryBuilder[R] = new ImpulseQueryBuilder[R]()):

  def input(entityType: String, argType: ArgType = ArgType.SINGLE_NODE): ImpulseQueryDsl[R] =
    builder.input(entityType, argType)
    this

  def walkEdge(relationName: String): ImpulseQueryDsl[R] =
    builder.walkEdge(relationName)
    this

  def walkEdgeFiltered(relationName: String, filterLabel: String): ImpulseQueryDsl[R] =
    builder.walkEdgeFiltered(relationName, filterLabel)
    this

  def walkEdgeFilteredAttribute(relationName: String, attributeName: String, op: String, value: Double): ImpulseQueryDsl[R] =
    builder.walkEdgeFilteredAttribute(relationName, attributeName, op, value)
    this

  def walkTarget(relationName: String): ImpulseQueryDsl[R] =
    builder.walkTarget(relationName)
    this

  def filterNodeAttribute(attributeName: String, op: String, value: Double): ImpulseQueryDsl[R] =
    builder.filterNodeAttribute(attributeName, op, value)
    this

  def projectExpression(nodeAttribute: String, operator: String, edgeAttribute: String): ImpulseQueryDsl[R] =
    builder.projectExpression(nodeAttribute, operator, edgeAttribute)
    this

  def repeat(count: Int)(fn: ImpulseQueryDsl[R] => Unit): ImpulseQueryDsl[R] =
    builder.repeat(new Function[ImpulseQueryBuilder[R], ImpulseQueryBuilder[R]]:
      override def apply(subBuilder: ImpulseQueryBuilder[R]): ImpulseQueryBuilder[R] =
        val subDsl = new ImpulseQueryDsl[R](subBuilder)
        fn(subDsl)
        subBuilder
    , count)
    this

  def repeatUntilStable(fn: ImpulseQueryDsl[R] => Unit): ImpulseQueryDsl[R] =
    builder.repeatUntilStable(new Function[ImpulseQueryBuilder[R], ImpulseQueryBuilder[R]]:
      override def apply(subBuilder: ImpulseQueryBuilder[R]): ImpulseQueryBuilder[R] =
        val subDsl = new ImpulseQueryDsl[R](subBuilder)
        fn(subDsl)
        subBuilder
    )
    this

  def extended(fn: ExtendedOpsDsl[R] => Unit): ImpulseQueryDsl[R] =
    val extOps = builder.extended()
    fn(new ExtendedOpsDsl[R](extOps))
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

def impulseQuery[R](fn: ImpulseQueryDsl[R] => Unit): ImpulseQueryDsl[R] =
  val dsl = new ImpulseQueryDsl[R]()
  fn(dsl)
  dsl
