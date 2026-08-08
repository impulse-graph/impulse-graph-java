package org.impulsegraph.scala

import org.impulsegraph.api.{ImpulseGraph, ImpulseGraphQuery, ImpulseGraphSnapshot}
import scala.concurrent.{ExecutionContext, Future}

extension (snapshot: ImpulseGraphSnapshot)
  def apply(relationName: String): Long = snapshot.getEdgeCount(relationName)

  def containsRelation(relationName: String): Boolean = snapshot.getRelationNames.contains(relationName)

  def size: Int = snapshot.getRelationCount

  def execute[R](query: ImpulseGraphQuery[R], input: AnyRef = null): R =
    query.execute(snapshot, input)

  def executeAsync[R](query: ImpulseGraphQuery[R], input: AnyRef = null)(using ec: ExecutionContext): Future[R] =
    Future(query.execute(snapshot, input))

extension [R](query: ImpulseGraphQuery[R])
  def apply(snapshot: ImpulseGraphSnapshot, input: AnyRef): R = query.execute(snapshot, input)
  def apply(liveGraph: ImpulseGraph, input: AnyRef): R = query.execute(liveGraph, input)
