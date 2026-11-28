package org.impulsegraph.kotlin

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.impulsegraph.api.ImpulseGraphQuery
import org.impulsegraph.api.ImpulseGraphSnapshot
import org.impulsegraph.api.RelationSnapshot
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.api.meta.AttributeMeta
import org.impulsegraph.api.meta.DomainMeta
import org.impulsegraph.api.meta.IndexMeta
import org.impulsegraph.api.meta.SnapshotHeader
import org.impulsegraph.api.spi.ImpulseEngineRegistry
import org.impulsegraph.api.statement.ImpulseStatement
import org.impulsegraph.api.traversal.DomainView
import org.impulsegraph.compiler.ast.ImpScmNode
import org.impulsegraph.compiler.ast.ScmProgram
import java.lang.foreign.Arena
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

object ImpulseSnapshots {
    fun load(path: Path): ImpulseGraphSnapshot = ImpulseGraphSnapshot.load(path)

    fun load(
        path: Path,
        arena: Arena,
    ): ImpulseGraphSnapshot = ImpulseEngineRegistry.getProvider().loadSnapshot(path, arena)

    inline fun <T> withSnapshot(
        path: Path,
        block: (ImpulseGraphSnapshot) -> T,
    ): T {
        val arena = Arena.ofShared()
        val snap =
            try {
                load(path, arena)
            } catch (t: Throwable) {
                arena.close()
                throw t
            }
        return snap.use(block)
    }
}

/**
 * Execute an [ImpulseGraphQuery] against an immutable graph snapshot.
 */
fun <R> ImpulseGraphSnapshot.execute(
    query: ImpulseGraphQuery<R>,
    input: Any? = null,
): R = query.execute(this, input)

/**
 * Invoke operator allowing queries to be executed directly on snapshots: `val result = snapshot(query, input)`.
 */
operator fun <R> ImpulseGraphSnapshot.invoke(
    query: ImpulseGraphQuery<R>,
    input: Any? = null,
): R = execute(query, input)

/**
 * Invoke operator allowing queries to be called like functions: `val result = query(snapshot, input)`.
 */
operator fun <R> ImpulseGraphQuery<R>.invoke(
    snapshot: ImpulseGraphSnapshot,
    input: Any? = null,
): R = execute(snapshot, input)

/**
 * Extension property to access pipeline AST steps.
 */
val <R> ImpulseGraphQuery<R>.steps: List<ImpScmNode>
    get() = (getAst() as? ScmProgram)?.steps() ?: emptyList()

/**
 * Get edge count for a relation using array access indexing syntax, or `null` if the relation does not exist.
 */
operator fun ImpulseGraphSnapshot.get(relationName: String): Long? =
    if (relationNames.contains(relationName)) getEdgeCount(relationName) else null

/**
 * Check if a relation name is present in this snapshot: `"userToGroup" in snapshot`.
 */
operator fun ImpulseGraphSnapshot.contains(relationName: String): Boolean = relationNames.contains(relationName)

/**
 * Total relation count in this snapshot.
 */
val ImpulseGraphSnapshot.size: Int
    get() = relationCount

val ImpulseGraphSnapshot.snapshotHeader: SnapshotHeader?
    get() = header

val ImpulseGraphSnapshot.domainMetas: List<DomainMeta>
    get() = domains ?: emptyList()

val ImpulseGraphSnapshot.indexMetas: List<IndexMeta>
    get() = secondaryIndexMetas ?: emptyList()

val ImpulseGraphSnapshot.metadata: Map<String, String>
    get() = metadataMap ?: emptyMap()

val ImpulseGraphSnapshot.sha256: String
    get() = sha256Checksum ?: ""

val ImpulseGraphSnapshot.relations: Map<String, RelationSnapshot>
    get() = allRelationSnapshots ?: emptyMap()

fun ImpulseGraphSnapshot.relation(name: String): RelationSnapshot? = getRelationSnapshot(name)

fun ImpulseGraphSnapshot.relation(relationId: Int): RelationSnapshot? = getRelationSnapshot(relationId)

fun ImpulseGraphSnapshot.domainMeta(name: String): DomainMeta? = getDomainMeta(name)

fun ImpulseGraphSnapshot.domainMeta(domainId: Int): DomainMeta? = getDomainMeta(domainId)

fun ImpulseGraphSnapshot.domainName(domainId: Int): String? = getDomainName(domainId)

fun ImpulseGraphSnapshot.relationSourceDomainName(relationName: String): String? = getRelationSourceDomainName(relationName)

fun ImpulseGraphSnapshot.relationTargetDomainName(relationName: String): String? = getRelationTargetDomainName(relationName)

fun ImpulseGraphSnapshot.metadata(key: String): String? = getMetadata(key)

val RelationSnapshot.attributeMetaList: List<AttributeMeta>
    get() = attributeMetas ?: emptyList()

fun RelationSnapshot.attributeMeta(name: String): AttributeMeta? = attributeMetaList.firstOrNull { it.name() == name }

fun DomainMeta.attributeMeta(name: String): AttributeMeta? = attributes().firstOrNull { it.name() == name }

fun ImpulseGraphSnapshot.domainView(domainName: String): DomainView = domain(domainName)

inline fun <T> ImpulseGraphSnapshot.useStatement(
    query: String,
    block: (ImpulseStatement) -> T,
): T = prepare(query).use(block)

/**
 * Asynchronously execute a query using Kotlin Coroutines on a worker dispatcher.
 */
suspend fun <R> ImpulseGraphSnapshot.executeAsync(
    query: ImpulseGraphQuery<R>,
    input: Any? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): R =
    withContext(dispatcher) {
        query.execute(this@executeAsync, input)
    }

/**
 * Asynchronously await query draining on a worker dispatcher.
 */
suspend fun ImpulseGraphSnapshot.awaitDrainedAsync(
    timeout: Long,
    unit: TimeUnit = TimeUnit.SECONDS,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean =
    withContext(dispatcher) {
        awaitDrained(timeout, unit)
    }

suspend fun ImpulseGraphSnapshot.awaitDrainedAsync(
    timeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Boolean = awaitDrainedAsync(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS, dispatcher)

suspend fun ImpulseGraphSnapshot.drainAndCloseAsync(
    timeout: Duration,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): Unit =
    withContext(dispatcher) {
        drainAndClose(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

fun ImpulseBitSet.asSequence(): Sequence<Int> =
    sequence {
        val it = this@asSequence.iterator()
        while (it.hasNext()) {
            yield(it.next())
        }
    }

operator fun ImpulseBitSet.contains(nodeId: Int): Boolean = get(nodeId)

fun ImpulseBitSet.toList(): List<Int> = asSequence().toList()

fun ImpulseBitSet.toSet(): Set<Int> = asSequence().toSet()

fun ImpulseBitSet.toIntArray(): IntArray = toList().toIntArray()
