package org.impulsegraph.api;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance thread-safe {@link IdMapper} for binary byte array external keys.
 */
public class BytesIdMapper implements IdMapper<byte[]> {

    private static final class ByteArrayWrapper {
        private final byte[] data;
        private final int hashCode;

        public ByteArrayWrapper(byte[] data) {
            this.data = Objects.requireNonNull(data, "data must not be null");
            this.hashCode = Arrays.hashCode(data);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ByteArrayWrapper that = (ByteArrayWrapper) o;
            return Arrays.equals(data, that.data);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        public byte[] getData() {
            return data;
        }
    }

    private final String domainType;
    private final ConcurrentHashMap<ByteArrayWrapper, Long> bytesToIdMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, byte[]> idToBytesMap = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    public BytesIdMapper(String domainType) {
        this.domainType = Objects.requireNonNull(domainType, "domainType must not be null");
    }

    @Override
    public String getDomainType() {
        return domainType;
    }

    @Override
    public long getOrAssignId(byte[] key) {
        Objects.requireNonNull(key, "key must not be null");
        ByteArrayWrapper wrapper = new ByteArrayWrapper(key);
        return bytesToIdMap.computeIfAbsent(wrapper, k -> {
            long newId = sequence.getAndIncrement();
            idToBytesMap.put(newId, k.getData());
            return newId;
        });
    }

    @Override
    public void registerMapping(byte[] key, long denseId) {
        Objects.requireNonNull(key, "key must not be null");
        ByteArrayWrapper wrapper = new ByteArrayWrapper(key);
        bytesToIdMap.put(wrapper, denseId);
        idToBytesMap.put(denseId, key);
        sequence.updateAndGet(current -> Math.max(current, denseId + 1));
    }

    @Override
    public byte[] getExternalKey(long denseId) {
        return idToBytesMap.get(denseId);
    }

    @Override
    public Long getId(byte[] key) {
        if (key == null) return null;
        return bytesToIdMap.get(new ByteArrayWrapper(key));
    }

    @Override
    public int size() {
        return bytesToIdMap.size();
    }

    @Override
    public void clear() {
        bytesToIdMap.clear();
        idToBytesMap.clear();
        sequence.set(1);
    }
}
