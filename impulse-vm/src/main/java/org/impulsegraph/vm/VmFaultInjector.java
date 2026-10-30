package org.impulsegraph.vm;

/**
 * Deterministic Countdown Fault Injector for ImpulseVM & Snapshot Builders
 * (SQLite MemFault / faultsimStep / faultsimConfig pattern in src/test_malloc.c
 * & src/fault.c).
 */
public final class VmFaultInjector {

	private static final VmFaultInjector GLOBAL = new VmFaultInjector();

	private boolean enabled = false;
	private int delay = 0;
	private int repeat = 0;
	private int benignDepth = 0;
	private int totalFaults = 0;
	private int benignFaults = 0;

	public VmFaultInjector() {
	}

	public static VmFaultInjector global() {
		return GLOBAL;
	}

	/**
	 * Configures deterministic countdown fault injection: the next {@code delay}
	 * allocations succeed, followed by {@code repeat} simulated failures.
	 */
	public synchronized void configure(int delay, int repeat) {
		this.delay = Math.max(0, delay);
		this.repeat = Math.max(0, repeat);
		this.enabled = repeat > 0;
		this.benignDepth = 0;
		this.totalFaults = 0;
		this.benignFaults = 0;
	}

	public synchronized void disable() {
		this.enabled = false;
		this.delay = 0;
		this.repeat = 0;
		this.benignDepth = 0;
	}

	public synchronized void beginBenignFailure() {
		this.benignDepth++;
	}

	public synchronized void endBenignFailure() {
		if (this.benignDepth > 0) {
			this.benignDepth--;
		}
	}

	public synchronized boolean isBenign() {
		return this.benignDepth > 0;
	}

	/**
	 * Advances the fault countdown by one step.
	 *
	 * @return {@code true} if a fault should be injected at this allocation/IO
	 *         site.
	 */
	public synchronized boolean shouldFail() {
		if (!enabled) {
			return false;
		}
		if (delay > 0) {
			delay--;
			return false;
		}
		if (repeat > 0) {
			repeat--;
			totalFaults++;
			if (benignDepth > 0) {
				benignFaults++;
			}
			if (repeat == 0) {
				enabled = false;
			}
			return true;
		}
		return false;
	}

	/**
	 * Convenience method that throws {@code IMPULSE_VM_ERR_OUT_OF_BOUNDS} on
	 * non-benign fault trigger.
	 *
	 * @return {@code true} if a benign failure occurred (caller should take
	 *         fallback path), {@code false} if no fault occurred.
	 */
	public synchronized boolean stepOrThrow(String siteDescription) {
		if (shouldFail()) {
			if (benignDepth > 0) {
				return true;
			}
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS: injected fault at " + siteDescription);
		}
		return false;
	}

	public synchronized int failuresSeen() {
		return totalFaults;
	}

	public synchronized int benignFailuresSeen() {
		return benignFaults;
	}

	public synchronized int pendingDelay() {
		return delay;
	}
}
