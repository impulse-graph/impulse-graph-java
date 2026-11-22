package org.impulsegraph.storage.csr;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Generic thread-safe A/B pointer swap manager for off-heap graph containers
 * ({@link RelationSnapshot} and {@link GraphSnapshot}). Tracks active readers
 * per epoch so old off-heap Arenas can be safely closed.
 */
public class SnapshotSwapManager<T extends AutoCloseable> implements AutoCloseable {

	private static final System.Logger LOGGER = System.getLogger(SnapshotSwapManager.class.getName());
	private static final long DEFAULT_SWAP_DRAIN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
	private static final long DEFAULT_CLOSE_DRAIN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);

	public static class Holder<T extends AutoCloseable> {
		private final T resource;
		private final AtomicInteger activeReaders = new AtomicInteger(0);

		public Holder(T resource) {
			this.resource = resource;
		}

		public T getResource() {
			return resource;
		}

		public void retain() {
			activeReaders.incrementAndGet();
		}

		public void release() {
			int remaining = activeReaders.decrementAndGet();
			if (remaining < 0) {
				throw new IllegalStateException("Negative reader count for Holder");
			}
		}

		public int getActiveReaders() {
			return activeReaders.get();
		}
	}

	private final AtomicReference<Holder<T>> currentHolder = new AtomicReference<>();
	private final long swapDrainTimeoutNanos;
	private final long closeDrainTimeoutNanos;
	private final Consumer<Throwable> errorHandler;

	public SnapshotSwapManager(T initialResource) {
		this(initialResource, DEFAULT_SWAP_DRAIN_TIMEOUT_NANOS, DEFAULT_CLOSE_DRAIN_TIMEOUT_NANOS,
				ex -> LOGGER.log(System.Logger.Level.WARNING, "SnapshotSwapManager lifecycle error", ex));
	}

	public SnapshotSwapManager(T initialResource, long swapDrainTimeout, long closeDrainTimeout, TimeUnit unit,
			Consumer<Throwable> errorHandler) {
		this(initialResource, unit.toNanos(swapDrainTimeout), unit.toNanos(closeDrainTimeout),
				Objects.requireNonNull(errorHandler, "errorHandler must not be null"));
	}

	private SnapshotSwapManager(T initialResource, long swapDrainTimeoutNanos, long closeDrainTimeoutNanos,
			Consumer<Throwable> errorHandler) {
		this.swapDrainTimeoutNanos = Math.max(0L, swapDrainTimeoutNanos);
		this.closeDrainTimeoutNanos = Math.max(0L, closeDrainTimeoutNanos);
		this.errorHandler = errorHandler;
		if (initialResource != null) {
			currentHolder.set(new Holder<>(initialResource));
		}
	}

	/**
	 * Obtains the current resource and increments its reader reference count. The
	 * caller MUST invoke {@link Holder#release()} when finished reading.
	 */
	public Holder<T> acquireCurrent() {
		while (true) {
			Holder<T> holder = currentHolder.get();
			if (holder == null) {
				return null;
			}
			holder.retain();
			if (currentHolder.get() == holder) {
				return holder;
			}
			holder.release();
		}
	}

	public T getCurrent() {
		Holder<T> holder = currentHolder.get();
		return holder != null ? holder.getResource() : null;
	}

	/**
	 * Atomically swaps the active resource to a new instance and schedules bounded
	 * draining and closing of the previous one.
	 */
	public void swap(T newResource) {
		Holder<T> newHolder = new Holder<>(newResource);
		Holder<T> oldHolder = currentHolder.getAndSet(newHolder);

		if (oldHolder != null) {
			Thread.startVirtualThread(() -> {
				long deadlineNanos = System.nanoTime() + swapDrainTimeoutNanos;
				while (oldHolder.getActiveReaders() > 0) {
					if (System.nanoTime() >= deadlineNanos) {
						errorHandler.accept(new IllegalStateException(
								"Timed out waiting to drain previous snapshot holder; activeReaders="
										+ oldHolder.getActiveReaders() + " (skipping unsafe close)"));
						return;
					}
					LockSupport.parkNanos(100_000L);
				}
				try {
					oldHolder.getResource().close();
				} catch (Exception e) {
					errorHandler.accept(e);
				}
			});
		}
	}

	@Override
	public void close() {
		Holder<T> holder = currentHolder.getAndSet(null);
		if (holder != null) {
			long deadlineNanos = System.nanoTime() + closeDrainTimeoutNanos;
			while (holder.getActiveReaders() > 0 && System.nanoTime() < deadlineNanos) {
				LockSupport.parkNanos(100_000L);
			}
			int remaining = holder.getActiveReaders();
			if (remaining > 0) {
				IllegalStateException err = new IllegalStateException(
						"Cannot close snapshot while " + remaining + " reader(s) remain active after drain timeout");
				errorHandler.accept(err);
				throw err;
			}
			try {
				holder.getResource().close();
			} catch (Exception e) {
				errorHandler.accept(e);
			}
		}
	}
}
