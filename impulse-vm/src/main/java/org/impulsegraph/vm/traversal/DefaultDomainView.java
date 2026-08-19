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

    private final java.util.Map<String, Long> keyToIdMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Long, String> idToKeyMap = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public DomainView registerKey(String key, long denseId) {
        if (key != null) {
            keyToIdMap.put(key, denseId);
            idToKeyMap.put(denseId, key);
        }
        return this;
    }

    @Override
    public DomainView registerKeys(java.util.Map<String, Long> keyMap) {
        if (keyMap != null) {
            for (var entry : keyMap.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    registerKey(entry.getKey(), entry.getValue());
                }
            }
        }
        return this;
    }

    @Override
    public long toDenseId(String key) {
        if (key == null) return -1;
        Long mapped = keyToIdMap.get(key);
        if (mapped != null) return mapped;

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
        String mapped = idToKeyMap.get(denseId);
        if (mapped != null) return mapped;
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
