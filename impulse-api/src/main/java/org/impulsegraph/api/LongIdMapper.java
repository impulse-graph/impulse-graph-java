package org.impulsegraph.api;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance thread-safe {@link IdMapper} for 64-bit Long external keys (e.g. database PKs, Snowflake IDs).
 */
public class LongIdMapper implements IdMapper<Long> {

    private final String domainType;
    private final ConcurrentHashMap<Long, Long> rawToDenseMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> denseToRawMap = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    public LongIdMapper(String domainType) {
        this.domainType = Objects.requireNonNull(domainType, "domainType must not be null");
    }

    @Override
    public String getDomainType() {
        return domainType;
    }

    @Override
    public long getOrAssignId(Long rawId) {
        Objects.requireNonNull(rawId, "rawId must not be null");
        return rawToDenseMap.computeIfAbsent(rawId, k -> {
            long newId = sequence.getAndIncrement();
            denseToRawMap.put(newId, k);
            return newId;
        });
    }

    @Override
    public void registerMapping(Long rawId, long denseId) {
        Objects.requireNonNull(rawId, "rawId must not be null");
        rawToDenseMap.put(rawId, denseId);
        denseToRawMap.put(denseId, rawId);
        sequence.updateAndGet(current -> Math.max(current, denseId + 1));
    }

    @Override
    public Long getExternalKey(long denseId) {
        return denseToRawMap.get(denseId);
    }

    @Override
    public Long getId(Long rawId) {
        return rawToDenseMap.get(rawId);
    }

    @Override
    public int size() {
        return rawToDenseMap.size();
    }

    @Override
    public void clear() {
        rawToDenseMap.clear();
        denseToRawMap.clear();
        sequence.set(1);
    }
}
