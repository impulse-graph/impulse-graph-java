package org.impulsegraph.api;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance thread-safe {@link IdMapper} for 128-bit UUID external keys.
 */
public class UuidIdMapper implements IdMapper<UUID> {

    private final String domainType;
    private final ConcurrentHashMap<UUID, Long> uuidToIdMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, UUID> idToUuidMap = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    public UuidIdMapper(String domainType) {
        this.domainType = Objects.requireNonNull(domainType, "domainType must not be null");
    }

    @Override
    public String getDomainType() {
        return domainType;
    }

    @Override
    public long getOrAssignId(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        return uuidToIdMap.computeIfAbsent(uuid, k -> {
            long newId = sequence.getAndIncrement();
            idToUuidMap.put(newId, k);
            return newId;
        });
    }

    @Override
    public void registerMapping(UUID uuid, long denseId) {
        Objects.requireNonNull(uuid, "uuid must not be null");
        uuidToIdMap.put(uuid, denseId);
        idToUuidMap.put(denseId, uuid);
        sequence.updateAndGet(current -> Math.max(current, denseId + 1));
    }

    @Override
    public UUID getExternalKey(long denseId) {
        return idToUuidMap.get(denseId);
    }

    @Override
    public Long getId(UUID uuid) {
        return uuidToIdMap.get(uuid);
    }

    @Override
    public int size() {
        return uuidToIdMap.size();
    }

    @Override
    public void clear() {
        uuidToIdMap.clear();
        idToUuidMap.clear();
        sequence.set(1);
    }
}
