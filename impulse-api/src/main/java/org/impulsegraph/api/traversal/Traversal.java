package org.impulsegraph.api.traversal;

import org.impulsegraph.api.bitset.ImpulseBitSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Fluent Kleisli Frontier Traversal Pipeline.
 *
 * <p>Represents monadic frontier propagation &lt;Domain, State&gt; -&gt; &lt;Domain', State'&gt;.</p>
 *
 * @param <T> Final collected return type
 */
public interface Traversal<T> {

    /**
     * Filters active frontier nodes via CEL predicate expression.
     */
    Traversal<T> filter(String celPredicate);

    /**
     * Projects or mutates attached state vector values on active nodes.
     */
    Traversal<T> project(String projectionExpr);

    /**
     * Traverses outgoing edges along the given relation, defaulting to OR reduction.
     */
    Traversal<T> out(String relation);

    /**
     * Traverses outgoing edges along the given relation, reducing converging paths via the given monoid.
     */
    Traversal<T> out(String relation, Reducer reducer);

    /**
     * Traverses incoming edges along the given relation (via CSC), defaulting to OR reduction.
     */
    Traversal<T> in(String relation);

    /**
     * Traverses incoming edges along the given relation, reducing converging paths via the given monoid.
     */
    Traversal<T> in(String relation, Reducer reducer);

    /**
     * Binds a named dynamic parameter for CEL filter expressions.
     */
    Traversal<T> withParam(String key, Object value);

    /**
     * Fixed-point loop: repeats step until frontier set converges (Frontier_{t+1} == Frontier_t).
     */
    Traversal<T> repeatUntilStable(Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step);

    /**
     * Repeats step exactly n times.
     */
    Traversal<T> repeat(int iterations, Function<Traversal<ImpulseBitSet>, Traversal<ImpulseBitSet>> step);

    /**
     * Terminal step: executes the pipeline and collects into type T.
     */
    T collect();

    /**
     * Terminal step: returns total cardinality of surviving target nodes.
     */
    long count();

    /**
     * Terminal step: returns target dense IDs as a List of Longs.
     */
    List<Long> toList();

    /**
     * Terminal step: returns target dense IDs as a Set of Longs.
     */
    Set<Long> toSet();

    /**
     * Terminal step: returns target frontier as a zero-copy off-heap {@link ImpulseBitSet}.
     */
    ImpulseBitSet toBitSet();

    /**
     * Disassembles the traversal pipeline into human-readable ImpAsm bytecode assembly.
     */
    String toImpAsm();
}
