package org.impulsegraph.vm.traversal;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import org.impulsegraph.api.traversal.DomainView;
import org.impulsegraph.api.traversal.Traversal;

import java.lang.foreign.Arena;
import java.util.Objects;

/**
 * Domain Anchor Context implementation.
 */
public class DefaultDomainView implements DomainView {

    private final ImpulseGraphSnapshot snapshot;
    private final String domainName;
    private final int domainId;
    private final long nodeCount;

    public DefaultDomainView(ImpulseGraphSnapshot snapshot, String domainName, int domainId, long nodeCount) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
        this.domainName = (domainName != null) ? domainName : "node";
        this.domainId = domainId;
        this.nodeCount = nodeCount;
    }

    @Override
    public String domainName() {
        return domainName;
    }

    @Override
    public int domainId() {
        return domainId;
    }

    @Override
    public long nodeCount() {
        return nodeCount;
    }

    @Override
    public Traversal<ImpulseBitSet> all() {
        ImpulseBitSet bs = new OffHeapBitSet(Arena.ofAuto(), (int) Math.max(1, nodeCount));
        for (int i = 0; i < nodeCount; i++) {
            bs.set(i);
        }
        return new DefaultTraversal<>(snapshot, domainName, bs);
    }

    @Override
    public Traversal<ImpulseBitSet> from(long nodeId) {
        return new DefaultTraversal<>(snapshot, domainName, nodeId);
    }

    @Override
    public Traversal<ImpulseBitSet> from(long... nodeIds) {
        return new DefaultTraversal<>(snapshot, domainName, nodeIds);
    }

    @Override
    public Traversal<ImpulseBitSet> from(ImpulseBitSet bitset) {
        return new DefaultTraversal<>(snapshot, domainName, bitset);
    }
}
