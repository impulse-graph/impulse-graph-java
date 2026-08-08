package org.impulsegraph.kotlin

import org.impulsegraph.api.ArgType
import org.impulsegraph.api.ImpulseGraphQuery
import org.impulsegraph.api.ImpulseQueryBuilder
import org.impulsegraph.api.ReturnType

@DslMarker
annotation class ImpulseQueryDslMarker

/**
 * Type-safe, idiomatic Kotlin DSL builder for constructing Impulse Graph queries.
 */
@ImpulseQueryDslMarker
class ImpulseQueryDsl<R>(
    private val builder: ImpulseQueryBuilder<R> = ImpulseQueryBuilder()
) {

    /**
     * Define the input entity type and argument type for the query pipeline.
     */
    fun input(entityType: String, argType: ArgType = ArgType.SINGLE_NODE): ImpulseQueryDsl<R> {
        builder.input(entityType, argType)
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
     * Walk edge with filter label.
     */
    fun walkEdgeFiltered(relationName: String, filterLabel: String): ImpulseQueryDsl<R> {
        builder.walkEdgeFiltered(relationName, filterLabel)
        return this
    }

    /**
     * Add a filtered CSR edge walk step based on numeric edge attribute comparison.
     */
    fun walkEdgeFilteredAttribute(relationName: String, attributeName: String, op: String, value: Double): ImpulseQueryDsl<R> {
        builder.walkEdgeFilteredAttribute(relationName, attributeName, op, value)
        return this
    }

    /**
     * Walk to target relation domain nodes.
     */
    infix fun walkTarget(relationName: String): ImpulseQueryDsl<R> {
        builder.walkTarget(relationName)
        return this
    }

    /**
     * Filter active candidate node set by comparing a node attribute against a numeric threshold.
     */
    fun filterNodeAttribute(attributeName: String, op: String, value: Double): ImpulseQueryDsl<R> {
        builder.filterNodeAttribute(attributeName, op, value)
        return this
    }

    /**
     * Add a map-reduce projection expression combining node and edge attributes.
     */
    fun projectExpression(nodeAttribute: String, operator: String, edgeAttribute: String): ImpulseQueryDsl<R> {
        builder.projectExpression(nodeAttribute, operator, edgeAttribute)
        return this
    }

    /**
     * Repeat a sub-query pipeline a fixed number of times.
     */
    fun repeat(count: Int, block: ImpulseQueryDsl<R>.() -> Unit): ImpulseQueryDsl<R> {
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

    /**
     * Terminal step: sum reduction over projected values.
     */
    fun <T> reduceSum(): ImpulseGraphQuery<T> = builder.reduceSum()

    /**
     * Terminal step: max reduction over projected values.
     */
    fun <T> reduceMax(): ImpulseGraphQuery<T> = builder.reduceMax()

    /**
     * Terminal step: min reduction over projected values.
     */
    fun <T> reduceMin(): ImpulseGraphQuery<T> = builder.reduceMin()

    /**
     * Terminal step: average reduction over projected values.
     */
    fun <T> reduceAvg(): ImpulseGraphQuery<T> = builder.reduceAvg()

    /**
     * Terminal step: first element reduction.
     */
    fun <T> reduceFirst(): ImpulseGraphQuery<T> = builder.reduceFirst()

    /**
     * Terminal collect step: materialize final result in the requested return type format.
     */
    fun <T> collect(returnType: ReturnType): ImpulseGraphQuery<T> = builder.collect(returnType)

    /**
     * Underlying Java query builder.
     */
    fun build(): ImpulseQueryBuilder<R> = builder
}

/**
 * Extended domain opcodes DSL wrapper.
 */
class ExtendedOpsDsl<R>(private val extOps: ImpulseQueryBuilder.ExtendedOps<R>) {
    fun islandDetect(src1Reg: Int, src2Reg: Int) {
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
