package org.impulsegraph.scala

import org.impulsegraph.api.{ImpulseGraphQuery, ImpulseGraphSnapshot}
import scala.concurrent.{ExecutionContext, Future}

extension (snapshot: ImpulseGraphSnapshot)
  def apply(relationName: String): Long = snapshot.getEdgeCount(relationName)

  def apply[R](query: ImpulseGraphQuery[R]): R = query.execute(snapshot, null)
  def apply[R](query: ImpulseGraphQuery[R], input: AnyRef): R = query.execute(snapshot, input)

  def containsRelation(relationName: String): Boolean = snapshot.getRelationNames.contains(relationName)

  def size: Int = snapshot.getRelationCount

  def execute[R](query: ImpulseGraphQuery[R], input: AnyRef = null): R =
    query.execute(snapshot, input)

  def executeAsync[R](query: ImpulseGraphQuery[R], input: AnyRef = null)(using ec: ExecutionContext): Future[R] =
    Future(query.execute(snapshot, input))

extension [R](query: ImpulseGraphQuery[R])
  def apply(snapshot: ImpulseGraphSnapshot): R = query.execute(snapshot, null)
  def apply(snapshot: ImpulseGraphSnapshot, input: AnyRef): R = query.execute(snapshot, input)
  def steps: java.util.List[org.impulsegraph.compiler.ast.ImpScmNode] =
    query.getAst match
      case p: org.impulsegraph.compiler.ast.ScmProgram => p.steps
      case _                                           => java.util.Collections.emptyList()
