package org.impulsegraph.api;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance thread-safe {@link IdMapper} for String external business keys.
 */
public class StringIdMapper implements IdMapper<String> {

    private final String domainType;
    private final ConcurrentHashMap<String, Long> stringToIdMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, String> idToStringMap = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    public StringIdMapper(String domainType) {
        this.domainType = Objects.requireNonNull(domainType, "domainType must not be null");
    }

    @Override
    public String getDomainType() {
        return domainType;
    }

    @Override
    public long getOrAssignId(String key) {
        Objects.requireNonNull(key, "key must not be null");
        return stringToIdMap.computeIfAbsent(key, k -> {
            long newId = sequence.getAndIncrement();
            idToStringMap.put(newId, k);
            return newId;
        });
    }

    @Override
    public void registerMapping(String key, long denseId) {
        Objects.requireNonNull(key, "key must not be null");
        stringToIdMap.put(key, denseId);
        idToStringMap.put(denseId, key);
        sequence.updateAndGet(current -> Math.max(current, denseId + 1));
    }

    @Override
    public String getExternalKey(long denseId) {
        return idToStringMap.get(denseId);
    }

    @Override
    public Long getId(String key) {
        return stringToIdMap.get(key);
    }

    @Override
    public int size() {
        return stringToIdMap.size();
    }

    @Override
    public void clear() {
        stringToIdMap.clear();
        idToStringMap.clear();
        sequence.set(1);
    }
}
