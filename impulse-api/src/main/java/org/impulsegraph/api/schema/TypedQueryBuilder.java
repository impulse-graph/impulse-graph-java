package org.impulsegraph.api.schema;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ReturnType;

import java.util.Objects;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;

/**
 * Strongly-typed base class for generated query builders.
 * Wraps an underlying {@link ImpulseQueryBuilder} while providing type-safe methods and IDE auto-complete.
 *
 * @param <E> Current entity type (e.g. User, Group)
 * @param <R> Target return type
 */
public class TypedQueryBuilder<E, R> {

    protected final ImpulseQueryBuilder<R> builder;

    public TypedQueryBuilder(ImpulseQueryBuilder<R> builder) {
        this.builder = Objects.requireNonNull(builder, "builder must not be null");
    }

    public TypedQueryBuilder(String entityType, ArgType argType) {
        this.builder = new ImpulseQueryBuilder<>();
        this.builder.input(entityType, argType);
    }

    public ImpulseQueryBuilder<R> getUnderlyingBuilder() {
        return builder;
    }

    @SuppressWarnings("unchecked")
    public ImpulseGraphQuery<ImpulseBitSet> collectRoaringBitset() {
        return builder.collect(ReturnType.ROARING_BITSET);
    }

    @SuppressWarnings("unchecked")
    public ImpulseGraphQuery<Long> collectCount() {
        return builder.collect(ReturnType.COUNT);
    }

    @SuppressWarnings("unchecked")
    public ImpulseGraphQuery<Double> reduceSum() {
        return builder.reduceSum();
    }

    @SuppressWarnings("unchecked")
    public ImpulseGraphQuery<Object> reduceFirst() {
        return builder.reduceFirst();
    }

    public String exportAst() {
        return ImpulseQueryBuilder.exportAst(builder.getSteps());
    }
}
