package org.impulsegraph.scala

import org.impulsegraph.api.{ArgType, ImpulseGraphQuery, ReturnType}

object DomainTypes:
  opaque type NodeId = Long
  object NodeId:
    def apply(value: Long): NodeId = value
    extension (id: NodeId) def value: Long = id

  opaque type RelationName = String
  object RelationName:
    def apply(value: String): RelationName = value
    extension (rel: RelationName) def value: String = rel

def buildRebacQuery(
    entityType: String,
    relationName: String,
    permission: String
): ImpulseGraphQuery[java.lang.Boolean] =
  impulseQuery[java.lang.Boolean] { dsl =>
    dsl.input(entityType, ArgType.SINGLE_NODE)
    dsl.walkEdge(relationName)
    dsl.extended(_.rebacCheck(permission))
  }.collect(ReturnType.EXISTS)
