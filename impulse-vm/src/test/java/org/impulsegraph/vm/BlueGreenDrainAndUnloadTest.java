package org.impulsegraph.vm;
import org.impulsegraph.api.ImpulseGraphSnapshot;

import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;


import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ReturnType;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class BlueGreenDrainAndUnloadTest {

    @Test
    public void testActiveQueryTrackingAndDrain() throws Exception {
        Arena arena = Arena.ofShared();
        MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 1);
        MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 5);
        RelationSnapshot rel = new org.impulsegraph.storage.csr.RelationSnapshot(arena, 2, 1, offsets, targets);
        ImpulseGraphSnapshot snapshot = new GraphSnapshot(arena, Map.of("userToGroup", rel));

        assertEquals(0, snapshot.getActiveQueryCount());
        assertTrue(snapshot.isDrained());

        // Simulate query entry
        snapshot.enterQuery();
        assertEquals(1, snapshot.getActiveQueryCount());
        assertFalse(snapshot.isDrained());

        snapshot.enterQuery();
        assertEquals(2, snapshot.getActiveQueryCount());

        snapshot.exitQuery();
        assertEquals(1, snapshot.getActiveQueryCount());

        snapshot.exitQuery();
        assertEquals(0, snapshot.getActiveQueryCount());
        assertTrue(snapshot.isDrained());

        // Test drainAndClose
        snapshot.drainAndClose(1, TimeUnit.SECONDS);
        assertFalse(arena.scope().isAlive(), "Arena MUST be closed after drainAndClose");
    }

    @Test
    public void testConcurrentZeroDelaySwapsAndDrainUnload() throws Exception {
        Arena arenaA = Arena.ofShared();
        MemorySegment offsetsA = arenaA.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 1);
        MemorySegment targetsA = arenaA.allocateFrom(ValueLayout.JAVA_INT, 10);
        RelationSnapshot relA = new org.impulsegraph.storage.csr.RelationSnapshot(arenaA, 2, 1, offsetsA, targetsA);
        ImpulseGraphSnapshot snapshotA = new GraphSnapshot(arenaA, Map.of("userToGroup", relA));

        Arena arenaB = Arena.ofShared();
        MemorySegment offsetsB = arenaB.allocateFrom(ValueLayout.JAVA_INT, 0, 1, 1);
        MemorySegment targetsB = arenaB.allocateFrom(ValueLayout.JAVA_INT, 20);
        RelationSnapshot relB = new org.impulsegraph.storage.csr.RelationSnapshot(arenaB, 2, 1, offsetsB, targetsB);
        ImpulseGraphSnapshot snapshotB = new GraphSnapshot(arenaB, Map.of("userToGroup", relB));

        ImpulseGraphQuery<ImpulseBitSet> query = ImpulseGraphQuery.<ImpulseBitSet>builder()
                .input("USER", ArgType.SINGLE_NODE)
                .walkEdge("userToGroup")
                .collect(ReturnType.ROARING_BITSET);

        Arena queryCompilerArena = Arena.ofShared();
        CompiledQuery compiled = DefaultImpulseQueryEvaluator.compileAst(query.getAst(), snapshotA, queryCompilerArena);

        int numThreads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    while (running.get()) {
                        Object res = compiled.execute(null, 0, queryCompilerArena);
                        assertNotNull(res);
                        assertTrue(res instanceof ImpulseBitSet);
                    }
                } catch (Exception e) {
                    fail("Query execution failed during blue/green swap: " + e.getMessage());
                }
            });
        }

        startLatch.countDown();

        // Perform Blue/Green Swaps concurrently with query execution
        for (int swap = 0; swap < 50; swap++) {
            ImpulseGraphSnapshot target = (swap % 2 == 0) ? snapshotB : snapshotA;
            compiled.rebind(target);
            Thread.sleep(2);
        }

        running.set(false);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        // Rebind finally to Snapshot B so Snapshot A is completely unattached
        compiled.rebind(snapshotB);

        // Await draining of Snapshot A and close
        assertTrue(snapshotA.awaitDrained(2, TimeUnit.SECONDS));
        snapshotA.drainAndClose(2, TimeUnit.SECONDS);
        assertFalse(arenaA.scope().isAlive(), "Snapshot A arena MUST be closed");

        // Verify Snapshot B continues to operate cleanly
        ImpulseBitSet finalRes = (ImpulseBitSet) compiled.execute(snapshotB, 0, queryCompilerArena);
        assertTrue(finalRes.get(20), "Final query on Snapshot B must reach target 20");

        snapshotB.drainAndClose(2, TimeUnit.SECONDS);
        assertFalse(arenaB.scope().isAlive(), "Snapshot B arena MUST be closed");

        queryCompilerArena.close();
        assertFalse(queryCompilerArena.scope().isAlive());
    }
}
