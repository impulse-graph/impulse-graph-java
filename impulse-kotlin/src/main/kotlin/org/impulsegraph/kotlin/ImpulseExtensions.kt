package org.impulsegraph.kotlin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.impulsegraph.api.ImpulseGraph
import org.impulsegraph.api.ImpulseGraphQuery
import org.impulsegraph.api.ImpulseGraphSnapshot
import java.util.concurrent.TimeUnit

/**
 * Execute an [ImpulseGraphQuery] against an immutable graph snapshot.
 */
fun <R> ImpulseGraphSnapshot.execute(query: ImpulseGraphQuery<R>, input: Any? = null): R {
    return query.execute(this, input)
}

/**
 * Execute an [ImpulseGraphQuery] against a live overlay graph.
 */
fun <R> ImpulseGraph.execute(query: ImpulseGraphQuery<R>, input: Any? = null): R {
    return query.execute(this, input)
}

/**
 * Invoke operator allowing queries to be called like functions: `val result = query(snapshot, input)`.
 */
operator fun <R> ImpulseGraphQuery<R>.invoke(snapshot: ImpulseGraphSnapshot, input: Any? = null): R {
    return execute(snapshot, input)
}

/**
 * Invoke operator allowing queries to be called like functions on live graphs: `val result = query(liveGraph, input)`.
 */
operator fun <R> ImpulseGraphQuery<R>.invoke(liveGraph: ImpulseGraph, input: Any? = null): R {
    return execute(liveGraph, input)
}

/**
 * Get edge count for a relation using array access indexing syntax: `val edges = snapshot["userToGroup"]`.
 */
operator fun ImpulseGraphSnapshot.get(relationName: String): Long {
    return getEdgeCount(relationName)
}

/**
 * Check if a relation name is present in this snapshot: `"userToGroup" in snapshot`.
 */
operator fun ImpulseGraphSnapshot.contains(relationName: String): Boolean {
    return relationNames.contains(relationName)
}

/**
 * Total relation count in this snapshot.
 */
val ImpulseGraphSnapshot.size: Int
    get() = relationCount

/**
 * Asynchronously execute a query using Kotlin Coroutines on a worker dispatcher.
 */
suspend fun <R> ImpulseGraphSnapshot.executeAsync(
    query: ImpulseGraphQuery<R>,
    input: Any? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): R = withContext(dispatcher) {
    query.execute(this@executeAsync, input)
}

/**
 * Asynchronously await query draining on a worker dispatcher.
 */
suspend fun ImpulseGraphSnapshot.awaitDrainedAsync(
    timeout: Long,
    unit: TimeUnit = TimeUnit.SECONDS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
): Boolean = withContext(dispatcher) {
    awaitDrained(timeout, unit)
}
