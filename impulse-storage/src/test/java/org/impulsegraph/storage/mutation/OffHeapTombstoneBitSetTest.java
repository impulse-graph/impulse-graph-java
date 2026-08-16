package org.impulsegraph.storage.mutation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OffHeapTombstoneBitSet Java 25 FFM Off-Heap Test Suite")
class OffHeapTombstoneBitSetTest {

    @Test
    @DisplayName("Verify 128-byte hardware memory alignment")
    void test128ByteAlignment() {
        try (Arena arena = Arena.ofConfined()) {
            OffHeapTombstoneBitSet bitSet = new OffHeapTombstoneBitSet(arena, 1024);
            assertTrue(bitSet.is128ByteAligned(), "Memory segment must be aligned to 128-byte boundary");
            assertEquals(0, bitSet.segment().address() % 128, "Memory address modulo 128 must be 0");
            assertTrue(bitSet.isEmpty());
            assertEquals(0, bitSet.cardinality());
        }
    }

    @Test
    @DisplayName("Verify basic bit set, get, clear, and nextSetBit operations")
    void testBasicBitOperations() {
        try (Arena arena = Arena.ofConfined()) {
            OffHeapTombstoneBitSet bitSet = new OffHeapTombstoneBitSet(arena, 100_000);

            assertFalse(bitSet.get(0));
            assertFalse(bitSet.get(63));
            assertFalse(bitSet.get(64));
            assertFalse(bitSet.get(99_999));

            bitSet.set(0);
            bitSet.set(63);
            bitSet.set(64);
            bitSet.set(128);
            bitSet.set(99_999);

            assertTrue(bitSet.get(0));
            assertTrue(bitSet.get(63));
            assertTrue(bitSet.get(64));
            assertTrue(bitSet.get(128));
            assertTrue(bitSet.get(99_999));
            assertFalse(bitSet.get(1));
            assertFalse(bitSet.get(62));
            assertFalse(bitSet.get(65));

            assertEquals(5, bitSet.cardinality());
            assertFalse(bitSet.isEmpty());

            assertEquals(0, bitSet.nextSetBit(0));
            assertEquals(63, bitSet.nextSetBit(1));
            assertEquals(64, bitSet.nextSetBit(64));
            assertEquals(128, bitSet.nextSetBit(65));
            assertEquals(99_999, bitSet.nextSetBit(129));
            assertEquals(-1, bitSet.nextSetBit(100_000));

            bitSet.clear(64);
            assertFalse(bitSet.get(64));
            assertEquals(4, bitSet.cardinality());

            bitSet.clear();
            assertTrue(bitSet.isEmpty());
            assertEquals(0, bitSet.cardinality());
        }
    }

    @Test
    @DisplayName("Verify multi-threaded lock-free atomic bit set operations")
    void testConcurrentAtomicBitSetting() throws InterruptedException {
        try (Arena arena = Arena.ofShared()) {
            int numThreads = 16;
            int bitsPerThread = 5_000;
            int totalBits = numThreads * bitsPerThread;

            OffHeapTombstoneBitSet bitSet = new OffHeapTombstoneBitSet(arena, totalBits);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startGate.await();
                        for (int i = 0; i < bitsPerThread; i++) {
                            int bitIndex = threadId + (i * numThreads);
                            bitSet.set(bitIndex);
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

            assertEquals(totalBits, bitSet.cardinality(), "All concurrent bit sets must be recorded without data loss");
            for (int i = 0; i < totalBits; i++) {
                assertTrue(bitSet.get(i), "Bit " + i + " must be set");
            }
        }
    }

    @Test
    @DisplayName("Verify SIMD vectorized masking and AND-NOT operations")
    void testSimdVectorizedMasking() {
        try (Arena arena = Arena.ofConfined()) {
            int bitCount = 2048;
            int wordCount = (bitCount + 63) / 64;

            OffHeapTombstoneBitSet tombstones = new OffHeapTombstoneBitSet(arena, bitCount);
            MemorySegment activeBits = arena.allocate((long) wordCount * Long.BYTES, 128);
            MemorySegment resultBits = arena.allocate((long) wordCount * Long.BYTES, 128);

            // Populate activeBits: all bits set (all 1s)
            for (int i = 0; i < wordCount; i++) {
                activeBits.setAtIndex(ValueLayout.JAVA_LONG, i, -1L);
            }

            // Set even bits in tombstones
            for (int i = 0; i < bitCount; i += 2) {
                tombstones.set(i);
            }
            assertEquals(bitCount / 2, tombstones.cardinality());

            // Apply SIMD tombstone mask: result = active & (~tombstones)
            tombstones.applyTombstoneMaskSimd(activeBits, resultBits, wordCount);

            // Verify result: only odd bits should remain set
            for (int i = 0; i < bitCount; i++) {
                int wordIdx = i / 64;
                long word = resultBits.getAtIndex(ValueLayout.JAVA_LONG, wordIdx);
                boolean isSet = (word & (1L << (i % 64))) != 0;
                if (i % 2 == 0) {
                    assertFalse(isSet, "Even bit " + i + " must be cleared by tombstone mask");
                } else {
                    assertTrue(isSet, "Odd bit " + i + " must remain set");
                }
            }
        }
    }

    @Test
    @DisplayName("Verify boundary checks and exceptions")
    void testOutOfBoundsHandling() {
        try (Arena arena = Arena.ofConfined()) {
            OffHeapTombstoneBitSet bitSet = new OffHeapTombstoneBitSet(arena, 100);

            assertThrows(IndexOutOfBoundsException.class, () -> bitSet.set(-1));
            assertThrows(IndexOutOfBoundsException.class, () -> bitSet.set(100));

            assertFalse(bitSet.get(-1));
            assertFalse(bitSet.get(100));

            assertDoesNotThrow(() -> bitSet.clear(-1));
            assertDoesNotThrow(() -> bitSet.clear(100));
        }
    }
}
