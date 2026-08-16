package org.impulsegraph.core.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeIdentityOverlay Thread-Safe Identifier Mapping Test Suite")
class NodeIdentityOverlayTest {

    @Test
    @DisplayName("Verify sequence starts at baseSnapshot node count")
    void testSequenceInitializedFromBase() {
        int baseNodeCount = 1000;
        NodeIdentityOverlay overlay = new NodeIdentityOverlay(baseNodeCount);

        assertEquals(baseNodeCount, overlay.getBaseNodeCount(0));
        assertEquals(baseNodeCount, overlay.getNodeCount(0));

        int id1 = overlay.getOrAssignId("user:1001");
        assertEquals(1000, id1, "First overlay dense ID must equal baseNodeCount");
        assertTrue(overlay.isOverlayNode(0, id1));

        int id2 = overlay.getOrAssignId("user:1002");
        assertEquals(1001, id2);
        assertTrue(overlay.isOverlayNode(0, id2));

        // Re-requesting existing ID should return same dense ID
        assertEquals(1000, overlay.getOrAssignId("user:1001"));
        assertEquals(1002, overlay.getNodeCount(0));
    }

    @Test
    @DisplayName("Verify bidirectional mapping between external keys and dense IDs")
    void testBidirectionalMapping() {
        NodeIdentityOverlay overlay = new NodeIdentityOverlay(0);

        UUID uuidKey = UUID.randomUUID();
        String strKey = "entity/account/42";
        Long longKey = 9988776655L;

        int id1 = overlay.getOrAssignId(uuidKey);
        int id2 = overlay.getOrAssignId(strKey);
        int id3 = overlay.getOrAssignId(longKey);

        assertEquals(0, id1);
        assertEquals(1, id2);
        assertEquals(2, id3);

        assertEquals(uuidKey, overlay.getExternalId(id1));
        assertEquals(strKey, overlay.getExternalId(id2));
        assertEquals(longKey, overlay.getExternalId(id3));
        assertNull(overlay.getExternalId(999));

        assertEquals(id1, overlay.getDenseId(uuidKey));
        assertEquals(id2, overlay.getDenseId(strKey));
        assertEquals(id3, overlay.getDenseId(longKey));
        assertEquals(-1, overlay.getDenseId("nonexistent"));
    }

    @Test
    @DisplayName("Verify dense ID boundary validations")
    void testDenseIdValidation() {
        NodeIdentityOverlay overlay = new NodeIdentityOverlay(10);

        // Dense IDs 0..9 are valid base IDs
        for (int i = 0; i < 10; i++) {
            final int id = i;
            assertTrue(overlay.isValidDenseId(id));
            assertDoesNotThrow(() -> overlay.validateDenseId(id));
            assertFalse(overlay.isOverlayNode(0, id));
        }

        // Out of bounds ID
        assertFalse(overlay.isValidDenseId(-1));
        assertFalse(overlay.isValidDenseId(10));
        assertThrows(IllegalArgumentException.class, () -> overlay.validateDenseId(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> overlay.validateDenseId(10));

        // Adding an overlay node expands valid range
        int newId = overlay.getOrAssignId("newNode");
        assertEquals(10, newId);
        assertTrue(overlay.isValidDenseId(10));
        assertTrue(overlay.isOverlayNode(0, 10));
        assertFalse(overlay.isValidDenseId(11));
    }

    @Test
    @DisplayName("Verify multi-domain support and domain isolation")
    void testMultiDomainIsolation() {
        Map<Integer, Integer> domainCounts = Map.of(0, 100, 1, 500);
        NodeIdentityOverlay overlay = new NodeIdentityOverlay(domainCounts);

        assertEquals(100, overlay.getBaseNodeCount(0));
        assertEquals(500, overlay.getBaseNodeCount(1));

        int dom0Id = overlay.getOrAssignId(0, "sharedKey");
        int dom1Id = overlay.getOrAssignId(1, "sharedKey");

        assertEquals(100, dom0Id);
        assertEquals(500, dom1Id);

        assertEquals("sharedKey", overlay.getExternalId(0, dom0Id));
        assertEquals("sharedKey", overlay.getExternalId(1, dom1Id));
        assertEquals(101, overlay.getNodeCount(0));
        assertEquals(501, overlay.getNodeCount(1));
    }

    @Test
    @DisplayName("Verify high-concurrency multi-threaded getOrAssignId operations")
    void testConcurrentGetOrAssignId() throws InterruptedException {
        int numThreads = 16;
        int keysPerThread = 2000;
        int totalExpectedKeys = numThreads * keysPerThread;

        NodeIdentityOverlay overlay = new NodeIdentityOverlay(0);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(numThreads);
        Set<Integer> assignedIds = ConcurrentHashMap.newKeySet();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startGate.await();
                    for (int k = 0; k < keysPerThread; k++) {
                        String key = "thread-" + threadId + "-key-" + k;
                        int denseId = overlay.getOrAssignId(key);
                        assignedIds.add(denseId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertTrue(doneGate.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(totalExpectedKeys, assignedIds.size(), "Every unique key must receive a unique dense ID without collision");
        assertEquals(totalExpectedKeys, overlay.getNodeCount(0));
        assertEquals(totalExpectedKeys, overlay.size(0));
    }
}
