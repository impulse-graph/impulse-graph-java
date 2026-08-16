package org.impulsegraph.storage.csr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DeltaLayer Thread-Safe Live Mutation Overlay Test Suite")
class DeltaLayerTest {

    @Test
    @DisplayName("Verify edge additions, tombstones, and clear operations")
    void testDeltaLayerAdditionsAndTombstones() {
        DeltaLayer delta = new DeltaLayer();
        assertEquals(0, delta.getMutationCount());

        delta.addEdge(1, 100);
        delta.addEdge(1, 101);
        assertEquals(2, delta.getMutationCount());
        assertArrayEquals(new int[]{100, 101}, delta.getAdditions(1));

        delta.removeEdge(1, 100);
        assertTrue(delta.isTombstoned(1, 100));
        assertFalse(delta.isTombstoned(1, 101));

        delta.clear();
        assertEquals(0, delta.getMutationCount());
        assertEquals(0, delta.getAdditions(1).length);
    }
}
