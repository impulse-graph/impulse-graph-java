package org.impulsegraph.kotlin

import org.impulsegraph.api.ArgType
import org.impulsegraph.api.ImpulseGraphQuery
import org.impulsegraph.api.ReturnType

/**
 * Value class representing a node entity identifier to enforce strong typing without allocation overhead.
 */
@JvmInline
value class NodeId(val value: Long)

/**
 * Value class representing a relation name.
 */
@JvmInline
value class RelationName(val value: String)

/**
 * Helper to quickly build a Relationship-Based Access Control (ReBAC) permission traversal query.
 */
fun buildRebacQuery(
    entityType: String,
    relationName: String,
    permission: String
): ImpulseGraphQuery<Boolean> {
    return impulseQuery<Boolean> {
        input(entityType, ArgType.SINGLE_NODE)
        walkEdge(relationName)
        extended { rebacCheck(permission) }
    }.collect(ReturnType.EXISTS)
}

/**
 * Helper to construct a fixed N-hop traversal pipeline.
 */
fun buildNHopQuery(
    entityType: String,
    relationName: String,
    hops: Int
): ImpulseGraphQuery<Any> {
    return impulseQuery<Any> {
        input(entityType, ArgType.SINGLE_NODE)
        repeat(hops) {
            walkEdge(relationName)
        }
    }.collect(ReturnType.ROARING_BITSET)
}
