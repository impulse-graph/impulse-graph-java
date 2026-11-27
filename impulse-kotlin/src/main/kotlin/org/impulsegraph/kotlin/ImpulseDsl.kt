package org.impulsegraph.kotlin

import org.impulsegraph.api.ArgType
import org.impulsegraph.api.Decimal64
import org.impulsegraph.api.ImpulseGraphQuery
import org.impulsegraph.api.ImpulseGraphSnapshot
import org.impulsegraph.api.ImpulseQueryBuilder
import org.impulsegraph.api.ReturnType
import org.impulsegraph.api.bitset.ImpulseBitSet
import org.impulsegraph.compiler.ast.ImpScmNode

@DslMarker
annotation class ImpulseQueryDslMarker

private class SeededImpulseGraphQuery<R>(
    private val delegate: ImpulseGraphQuery<R>,
    private val defaultSeed: Long,
) : ImpulseGraphQuery<R> {
    override fun execute(
        snapshot: ImpulseGraphSnapshot,
        input: Any?,
    ): R = delegate.execute(snapshot, input ?: defaultSeed)

    override fun getAst(): ImpScmNode = delegate.ast

    override fun getOperationName(): String = delegate.operationName

    override fun getParameters(): Map<String, Any> = delegate.parameters

    override fun exportAst(): String = delegate.exportAst()
}

/**
 * Type-safe, idiomatic Kotlin DSL builder for constructing Impulse Graph queries.
 */
@ImpulseQueryDslMarker
class ImpulseQueryDsl<R>(
    private val builder: ImpulseQueryBuilder<R> = ImpulseQueryBuilder(),
) {
    private var boundSeed: Long? = null

    /**
     * Define the input entity type and argument type for the query pipeline.
     */
    fun input(
        entityType: String,
        argType: ArgType = ArgType.SINGLE_NODE,
    ): ImpulseQueryDsl<R> {
        builder.input(entityType, argType)
        return this
    }

    /**
     * Define the input entity type and bind a default [NodeId] seed.
     */
    fun input(
        entityType: String,
        seed: NodeId,
    ): ImpulseQueryDsl<R> {
        boundSeed = seed.value
        builder.input(entityType, ArgType.SINGLE_NODE)
        return this
    }

    fun bindParameter(
        name: String,
        value: Any,
    ): ImpulseQueryDsl<R> {
        builder.bindParameter(name, value)
        return this
    }

    fun bindParameters(params: Map<String, Any>): ImpulseQueryDsl<R> {
        builder.bindParameters(params)
        return this
    }

    /**
     * Add a CSR forward edge walk step over the specified relation name.
     */
    infix fun walkEdge(relationName: String): ImpulseQueryDsl<R> {
        builder.walkEdge(relationName)
        return this
    }

    /**
     * Add a CSR forward edge walk step over a typed [RelationName].
     */
    infix fun walkEdge(relationName: RelationName): ImpulseQueryDsl<R> {
        builder.walkEdge(relationName.value)
        return this
    }

    infix fun walkReverse(relationName: String): ImpulseQueryDsl<R> {
        builder.walkReverse(relationName)
        return this
    }

    infix fun walkReverse(relationName: RelationName): ImpulseQueryDsl<R> {
        builder.walkReverse(relationName.value)
        return this
    }

    infix fun walkBoth(relationName: String): ImpulseQueryDsl<R> {
        builder.walkBoth(relationName)
        return this
    }

    infix fun walkBoth(relationName: RelationName): ImpulseQueryDsl<R> {
        builder.walkBoth(relationName.value)
        return this
    }

    fun walkOutUnion(vararg relationNames: String): ImpulseQueryDsl<R> {
        builder.walkOutUnion(*relationNames)
        return this
    }

    fun walkInUnion(vararg relationNames: String): ImpulseQueryDsl<R> {
        builder.walkInUnion(*relationNames)
        return this
    }

    fun walkBothUnion(vararg relationNames: String): ImpulseQueryDsl<R> {
        builder.walkBothUnion(*relationNames)
        return this
    }

    fun walkEdgeWithCel(
        relationName: String,
        celExpr: String,
    ): ImpulseQueryDsl<R> {
        builder.walkEdgeWithCel(relationName, celExpr)
        return this
    }

    fun walkEdgeWithCel(
        relationName: RelationName,
        celExpr: String,
    ): ImpulseQueryDsl<R> {
        builder.walkEdgeWithCel(relationName.value, celExpr)
        return this
    }

    fun walkReverseWithCel(
        relationName: String,
        celExpr: String,
    ): ImpulseQueryDsl<R> {
        builder.walkReverseWithCel(relationName, celExpr)
        return this
    }

    fun walkReverseWithCel(
        relationName: RelationName,
        celExpr: String,
    ): ImpulseQueryDsl<R> {
        builder.walkReverseWithCel(relationName.value, celExpr)
        return this
    }

    fun walkEdgeWithState(
        relationName: String,
        stateProjections: String,
    ): ImpulseQueryDsl<R> {
        builder.walkEdgeWithState(relationName, stateProjections)
        return this
    }

    fun projectState(projectionExpr: String): ImpulseQueryDsl<R> {
        builder.projectState(projectionExpr)
        return this
    }

    fun filterWithCel(celExpr: String): ImpulseQueryDsl<R> {
        builder.filterWithCel(celExpr)
        return this
    }

    fun filter(celExpr: String): ImpulseQueryDsl<R> {
        builder.filter(celExpr)
        return this
    }

    fun vectorRerank(
        queryVector: FloatArray,
        attrId: Int,
        metric: Int,
    ): ImpulseQueryDsl<R> {
        builder.vectorRerank(queryVector, attrId, metric)
        return this
    }

    /**
     * Walk edge with filter label.
     */
    fun walkEdgeFiltered(
        relationName: String,
        filterLabel: String,
    ): ImpulseQueryDsl<R> {
        builder.walkEdgeFiltered(relationName, filterLabel)
        return this
    }

    fun walkEdgeFiltered(
        relationName: RelationName,
        filterLabel: String,
    ): ImpulseQueryDsl<R> {
        builder.walkEdgeFiltered(relationName.value, filterLabel)
        return this
    }

    /**
     * Add a filtered CSR edge walk step based on numeric edge attribute comparison.
     */
    fun walkEdgeFilteredAttribute(
        relationName: String,
        attributeName: String,
        op: String,
        value: Double,
    ): ImpulseQueryDsl<R> {
        builder.walkEdgeFilteredAttribute(relationName, attributeName, op, value)
        return this
    }

    fun walkEdgeFilteredAttribute(
        relationName: RelationName,
        attributeName: String,
        op: String,
        value: Double,
    ): ImpulseQueryDsl<R> {
        builder.walkEdgeFilteredAttribute(relationName.value, attributeName, op, value)
        return this
    }

    /**
     * Add a filtered CSR edge walk step comparing a `DECIMAL64` edge attribute against an exact [Decimal64] threshold.
     */
    fun walkEdgeFilteredAttribute(
        relationName: String,
        attributeName: String,
        op: String,
        value: Decimal64,
    ): ImpulseQueryDsl<R> {
        builder.walkEdgeWithCel(
            relationName,
            "edge.$attributeName $op decimal(\"${value.toDecimalString()}\", ${value.precision()}, ${value.scale()})",
        )
        return this
    }

    fun walkEdgeFilteredAttribute(
        relationName: RelationName,
        attributeName: String,
        op: String,
        value: Decimal64,
    ): ImpulseQueryDsl<R> = walkEdgeFilteredAttribute(relationName.value, attributeName, op, value)

    /**
     * Walk to target relation domain nodes.
     */
    infix fun walkTarget(relationName: String): ImpulseQueryDsl<R> {
        builder.walkTarget(relationName)
        return this
    }

    /**
     * Walk to target relation domain nodes over a typed [RelationName].
     */
    infix fun walkTarget(relationName: RelationName): ImpulseQueryDsl<R> {
        builder.walkTarget(relationName.value)
        return this
    }

    /**
     * Filter active candidate node set by comparing a node attribute against a numeric threshold.
     */
    fun filterNodeAttribute(
        attributeName: String,
        op: String,
        value: Double,
    ): ImpulseQueryDsl<R> {
        builder.filterNodeAttribute(attributeName, op, value)
        return this
    }

    /**
     * Filter active candidate node set by comparing a `DECIMAL64` node attribute against an exact [Decimal64] threshold.
     */
    fun filterNodeAttribute(
        attributeName: String,
        op: String,
        value: Decimal64,
    ): ImpulseQueryDsl<R> {
        builder.filterWithCel(
            "node.$attributeName $op decimal(\"${value.toDecimalString()}\", ${value.precision()}, ${value.scale()})",
        )
        return this
    }

    /**
     * Add a map-reduce projection expression combining node and edge attributes.
     */
    fun projectExpression(
        nodeAttribute: String,
        operator: String,
        edgeAttribute: String,
    ): ImpulseQueryDsl<R> {
        builder.projectExpression(nodeAttribute, operator, edgeAttribute)
        return this
    }

    /**
     * Repeat a sub-query pipeline a fixed number of times.
     */
    fun repeat(
        count: Int,
        block: ImpulseQueryDsl<R>.() -> Unit,
    ): ImpulseQueryDsl<R> {
        builder.repeat({ subBuilder ->
            val dsl = ImpulseQueryDsl(subBuilder)
            dsl.block()
            subBuilder
        }, count)
        return this
    }

    /**
     * Repeat a sub-query pipeline until candidate set generation converges (reaches a fixed point).
     */
    fun repeatUntilStable(block: ImpulseQueryDsl<R>.() -> Unit): ImpulseQueryDsl<R> {
        builder.repeatUntilStable { subBuilder ->
            val dsl = ImpulseQueryDsl(subBuilder)
            dsl.block()
            subBuilder
        }
        return this
    }

    /**
     * Access domain-specific extended opcodes (ReBAC, PowerGrid island detection, motifs).
     */
    fun extended(block: ExtendedOpsDsl<R>.() -> Unit): ImpulseQueryDsl<R> {
        val extOps = builder.extended()
        val extDsl = ExtendedOpsDsl(extOps)
        extDsl.block()
        return this
    }

    private fun <T> wrapSeed(query: ImpulseGraphQuery<T>): ImpulseGraphQuery<T> {
        val seed = boundSeed
        return if (seed != null) SeededImpulseGraphQuery(query, seed) else query
    }

    /**
     * Terminal step: sum reduction over projected values.
     */
    fun reduceSum(): ImpulseGraphQuery<Double> = wrapSeed(builder.reduceSum())

    /**
     * Terminal step: max reduction over projected values.
     */
    fun reduceMax(): ImpulseGraphQuery<Double> = wrapSeed(builder.reduceMax())

    /**
     * Terminal step: min reduction over projected values.
     */
    fun reduceMin(): ImpulseGraphQuery<Double> = wrapSeed(builder.reduceMin())

    /**
     * Terminal step: average reduction over projected values.
     */
    fun reduceAvg(): ImpulseGraphQuery<Double> = wrapSeed(builder.reduceAvg())

    /**
     * Terminal step: first element reduction.
     */
    fun reduceFirst(): ImpulseGraphQuery<Any> = wrapSeed(builder.reduceFirst())

    fun reduceArgMax(): ImpulseGraphQuery<Int> = wrapSeed(builder.reduceArgMax())

    fun reduceArgMin(): ImpulseGraphQuery<Int> = wrapSeed(builder.reduceArgMin())

    /**
     * Terminal collect step: materialize final result in the requested return type format.
     */
    fun collect(returnType: ReturnType): ImpulseGraphQuery<R> = wrapSeed(builder.collect(returnType))

    fun collectBitSet(): ImpulseGraphQuery<ImpulseBitSet> = wrapSeed(builder.collect(ReturnType.DENSE_BITSET))

    fun collectCount(): ImpulseGraphQuery<Long> = wrapSeed(builder.collect(ReturnType.COUNT))

    /**
     * Underlying Java query builder.
     */
    fun build(): ImpulseQueryBuilder<R> = builder
}

/**
 * Extended domain opcodes DSL wrapper.
 */
@ImpulseQueryDslMarker
class ExtendedOpsDsl<R>(private val extOps: ImpulseQueryBuilder.ExtendedOps<R>) {
    fun islandDetect(
        src1Reg: Int,
        src2Reg: Int,
    ) {
        extOps.islandDetect(src1Reg, src2Reg)
    }

    fun rebacCheck(permission: String) {
        extOps.rebacCheck(permission)
    }

    fun motifMatch3() {
        extOps.motifMatch3()
    }
}

/**
 * Top-level idiomatic builder function for constructing an Impulse Graph query pipeline.
 */
inline fun <R> impulseQuery(block: ImpulseQueryDsl<R>.() -> Unit): ImpulseQueryDsl<R> {
    val dsl = ImpulseQueryDsl<R>()
    dsl.block()
    return dsl
}
