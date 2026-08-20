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

    private static final java.util.Map<ImpulseGraphSnapshot, java.util.Map<String, DomainView>> DOMAIN_CACHE = new java.util.WeakHashMap<>();

    public static synchronized DomainView getOrCreate(ImpulseGraphSnapshot snapshot, String domainName, int domainId, long nodeCount) {
        var domainMap = DOMAIN_CACHE.computeIfAbsent(snapshot, k -> new java.util.concurrent.ConcurrentHashMap<>());
        String key = (domainName != null) ? domainName : "default";
        return domainMap.computeIfAbsent(key, k -> new DefaultDomainView(snapshot, k, domainId, nodeCount));
    }

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
    public long toDenseId(String key) {
        if (key == null) return -1;

        // Check if snapshot metadata contains key mapping
        String metaKey = "domain." + domainName + ".key." + key;
        String metaVal = snapshot.getMetadata(metaKey);
        if (metaVal != null) {
            try {
                long val = Long.parseLong(metaVal);
                if (val >= 0 && (nodeCount <= 0 || val < nodeCount)) return val;
            } catch (NumberFormatException ignored) {}
        }

        // Fallback 1: Parse direct numeric integer
        try {
            long val = Long.parseLong(key);
            if (val >= 0 && (nodeCount <= 0 || val < nodeCount)) return val;
        } catch (NumberFormatException ignored) {}

        // Fallback 2: Parse domain prefix (e.g. "User_42", "User#42", "Compound_10")
        int sep = Math.max(key.lastIndexOf('_'), key.lastIndexOf('#'));
        if (sep >= 0 && sep < key.length() - 1) {
            try {
                long val = Long.parseLong(key.substring(sep + 1));
                if (val >= 0 && (nodeCount <= 0 || val < nodeCount)) return val;
            } catch (NumberFormatException ignored) {}
        }

        return -1;
    }

    @Override
    public String toKey(long denseId) {
        String metaKey = "domain." + domainName + ".id." + denseId;
        String metaVal = snapshot.getMetadata(metaKey);
        if (metaVal != null && !metaVal.isBlank()) return metaVal;
        return domainName + "_" + denseId;
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
