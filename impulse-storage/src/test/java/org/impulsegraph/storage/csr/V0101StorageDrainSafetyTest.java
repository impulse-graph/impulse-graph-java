package org.impulsegraph.storage.csr;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD & regression tests for impulse-graph-java v0.10.1 Wave 2 (#74, #75).
 */
class V0101StorageDrainSafetyTest {

	@Test
	void testIssue75CloseDoesNotForceCloseUnderActiveReaders() {
		AtomicBoolean closed = new AtomicBoolean(false);
		AtomicReference<Throwable> reported = new AtomicReference<>();
		AutoCloseable resource = () -> closed.set(true);

		SnapshotSwapManager<AutoCloseable> mgr = new SnapshotSwapManager<>(resource, 50, 50, TimeUnit.MILLISECONDS,
				reported::set);
		SnapshotSwapManager.Holder<AutoCloseable> held = mgr.acquireCurrent();
		assertNotNull(held);
		assertEquals(1, held.getActiveReaders());

		assertThrows(IllegalStateException.class, mgr::close,
				"close() must fail loudly instead of force-closing an Arena under active readers");
		assertFalse(closed.get(), "Resource must NOT be closed while a reader still holds a reference");
		assertNotNull(reported.get(), "errorHandler must receive the drain timeout error");

		held.release();
	}

	@Test
	void testIssue75SwapTimeoutReportsAndSkipsUnsafeClose() throws Exception {
		AtomicBoolean oldClosed = new AtomicBoolean(false);
		CountDownLatch errLatch = new CountDownLatch(1);
		AtomicReference<Throwable> reported = new AtomicReference<>();

		SnapshotSwapManager<AutoCloseable> mgr = new SnapshotSwapManager<>(() -> oldClosed.set(true), 40, 40,
				TimeUnit.MILLISECONDS, err -> {
					reported.set(err);
					errLatch.countDown();
				});
		SnapshotSwapManager.Holder<AutoCloseable> held = mgr.acquireCurrent();
		assertNotNull(held);

		mgr.swap(() -> {
		});
		assertTrue(errLatch.await(2, TimeUnit.SECONDS), "swap() drain must time out instead of spinning forever");
		assertFalse(oldClosed.get(), "swap() must not close old resource while activeReaders > 0");
		assertInstanceOf(IllegalStateException.class, reported.get());

		held.release();
		mgr.close();
	}

	@Test
	void testIssue75ClosePropagatesCloseExceptionToErrorHandler() {
		AtomicReference<Throwable> reported = new AtomicReference<>();
		SnapshotSwapManager<AutoCloseable> mgr = new SnapshotSwapManager<>(() -> {
			throw new RuntimeException("boom-close");
		}, 50, 50, TimeUnit.MILLISECONDS, reported::set);

		mgr.close();
		assertNotNull(reported.get());
		assertEquals("boom-close", reported.get().getMessage());
	}
}
