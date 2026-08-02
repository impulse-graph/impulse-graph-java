package org.impulsegraph.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IdMapper Multi-Type Key Test Suite")
class IdMapperTest {

    @Test
    @DisplayName("Test UuidIdMapper bi-directional resolution")
    void testUuidIdMapper() {
        IdMapper<UUID> mapper = new UuidIdMapper("USER");
        assertEquals("USER", mapper.getDomainType());

        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();

        long id1 = mapper.getOrAssignId(u1);
        long id2 = mapper.getOrAssignId(u2);
        assertNotEquals(id1, id2);
        assertEquals(id1, mapper.getOrAssignId(u1)); // Idempotent

        assertEquals(u1, mapper.getExternalKey(id1));
        assertEquals(u2, mapper.getExternalKey(id2));
        assertEquals(2, mapper.size());
    }

    @Test
    @DisplayName("Test StringIdMapper bi-directional resolution")
    void testStringIdMapper() {
        IdMapper<String> mapper = new StringIdMapper("GROUP");
        assertEquals("GROUP", mapper.getDomainType());

        String s1 = "GROUP#ENGINEERING";
        String s2 = "GROUP#PRODUCT";

        long id1 = mapper.getOrAssignId(s1);
        long id2 = mapper.getOrAssignId(s2);
        assertNotEquals(id1, id2);

        assertEquals(s1, mapper.getExternalKey(id1));
        assertEquals(s2, mapper.getExternalKey(id2));
        assertEquals(2, mapper.size());
    }

    @Test
    @DisplayName("Test LongIdMapper bi-directional resolution")
    void testLongIdMapper() {
        IdMapper<Long> mapper = new LongIdMapper("ACCOUNT");
        assertEquals("ACCOUNT", mapper.getDomainType());

        long raw1 = 184920492810482910L;
        long raw2 = 184920492810482911L;

        long dense1 = mapper.getOrAssignId(raw1);
        long dense2 = mapper.getOrAssignId(raw2);
        assertNotEquals(dense1, dense2);

        assertEquals(raw1, mapper.getExternalKey(dense1));
        assertEquals(raw2, mapper.getExternalKey(dense2));
    }

    @Test
    @DisplayName("Test BytesIdMapper bi-directional resolution")
    void testBytesIdMapper() {
        IdMapper<byte[]> mapper = new BytesIdMapper("HASH");
        assertEquals("HASH", mapper.getDomainType());

        byte[] b1 = new byte[]{0x01, 0x02, 0x03, 0x04};
        byte[] b2 = new byte[]{0x05, 0x06, 0x07, 0x08};

        long dense1 = mapper.getOrAssignId(b1);
        long dense2 = mapper.getOrAssignId(b2);
        assertNotEquals(dense1, dense2);

        assertArrayEquals(b1, mapper.getExternalKey(dense1));
        assertArrayEquals(b2, mapper.getExternalKey(dense2));
    }
}
