package org.impulsegraph.vm.traversal;

import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.traversal.Reducer;
import org.impulsegraph.api.traversal.Traversal;
import org.impulsegraph.vm.DefaultImpulseQueryEvaluator;

import java.util.*;
import java.util.function.Function;

/**
 * High-performance Kleisli Frontier Traversal Pipeline implementation.
 */
public class DefaultTraversal<T> implements Traversal<T> {

    private final ImpulseGraphSnapshot snapshot;
    private final String currentDomain;
    private final Object seedInput;
    private final ImpulseQueryBuilder<T> builder;

    public DefaultTraversal(ImpulseGraphSnapshot snapshot, String currentDomain, Object seedInput) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.currentDomain = (currentDomain != null) ? currentDomain : "node";
        this.seedInput = seedInput;
        this.builder = new ImpulseQueryBuilder<>();
        this.builder.input(this.currentDomain, org.impulsegraph.api.ArgType.ROARING_BITSET);
    }

    private DefaultTraversal(ImpulseGraphSnapshot snapshot, String currentDomain, Object seedInput, ImpulseQueryBuilder<T> builder) {
        this.snapshot = snapshot;
        this.currentDomain = currentDomain;
        this.seedInput = seedInput;
        this.builder = builder;
    }

    @Override
    public Traversal<T> filter(String celPredicate) {
        builder.filter(celPredicate);
        return this;
    }

    @Override
    public Traversal<T> project(String projectionExpr) {
        // State projection placeholder
        return this;
    }

    @Override
    public Traversal<T> out(String relation) {
        return out(relation, Reducer.OR);
    }

    @Override
    public Traversal<T> out(String relation, Reducer reducer) {
        builder.walkEdge(relation);
        return this;
    }

    @Override
    public Traversal<T> in(String relation) {
        return in(relation, Reducer.OR);
    }

    @Override
    public Traversal<T> in(String relation, Reducer reducer) {
        builder.walkEdge(relation);
        return this;
    }

    @Override
    public Traversal<T> withParam(String key, Object value) {
        // parameter binding
        return this;
    }

    @Override
    public Traversal<T> repeatUntilStable(Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step) {
        builder.repeatUntilStable((Function<ImpulseQueryBuilder<T>, ImpulseQueryBuilder<T>>) (Function<?, ?>) subBuilder -> {
            DefaultTraversal<ImpulseBitSet> subTrav = new DefaultTraversal<>(snapshot, currentDomain, null, (ImpulseQueryBuilder<ImpulseBitSet>) (ImpulseQueryBuilder<?>) subBuilder);
            step.apply(subTrav);
            return subBuilder;
        });
        return this;
    }

    @Override
    public Traversal<T> repeat(int iterations, Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step) {
        builder.repeat(iterations, (Function<ImpulseQueryBuilder<T>, ImpulseQueryBuilder<T>>) (Function<?, ?>) subBuilder -> {
            DefaultTraversal<ImpulseBitSet> subTrav = new DefaultTraversal<>(snapshot, currentDomain, null, (ImpulseQueryBuilder<ImpulseBitSet>) (ImpulseQueryBuilder<?>) subBuilder);
            step.apply(subTrav);
            return subBuilder;
        });
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T collect() {
        ImpulseGraphQuery<?> query = builder.collectRoaringBitset();
        return (T) DefaultImpulseQueryEvaluator.getInstance().evaluate((ImpulseGraphQuery) query, snapshot, seedInput);
    }

    @Override
    public long count() {
        ImpulseBitSet bs = toBitSet();
        return (bs != null) ? bs.cardinality() : 0L;
    }

    @Override
    public List<Long> toList() {
        ImpulseBitSet bs = toBitSet();
        if (bs == null) return List.of();
        List<Long> list = new ArrayList<>();
        for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
            list.add((long) i);
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public Set<Long> toSet() {
        ImpulseBitSet bs = toBitSet();
        if (bs == null) return Set.of();
        Set<Long> set = new LinkedHashSet<>();
        for (int i = bs.nextSetBit(0); i >= 0; i = bs.nextSetBit(i + 1)) {
            set.add((long) i);
        }
        return Collections.unmodifiableSet(set);
    }

    @Override
    public List<String> toKeyList() {
        List<Long> ids = toList();
        org.impulsegraph.api.traversal.DomainView dv = snapshot.domain(currentDomain);
        List<String> keys = new ArrayList<>(ids.size());
        for (long id : ids) {
            keys.add(dv.toKey(id));
        }
        return Collections.unmodifiableList(keys);
    }

    @Override
    public Set<String> toKeySet() {
        Set<Long> ids = toSet();
        org.impulsegraph.api.traversal.DomainView dv = snapshot.domain(currentDomain);
        Set<String> keys = new LinkedHashSet<>(ids.size());
        for (long id : ids) {
            keys.add(dv.toKey(id));
        }
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public ImpulseBitSet toBitSet() {
        ImpulseGraphQuery<ImpulseBitSet> query = builder.collectRoaringBitset();
        return DefaultImpulseQueryEvaluator.getInstance().evaluate(query, snapshot, seedInput);
    }

    @Override
    public String toImpAsm() {
        ImpulseGraphQuery<ImpulseBitSet> query = builder.collectRoaringBitset();
        return query.disassemble(snapshot);
    }
}
