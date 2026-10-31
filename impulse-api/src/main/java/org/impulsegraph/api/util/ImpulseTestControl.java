package org.impulsegraph.api.util;

/**
 * Runtime test-control interface modeled on SQLite's
 * {@code sqlite3_test_control(op, ...)} ({@code src/main.c:4450-4884}).
 *
 * <p>
 * Provides a single deterministic coordination point for enabling/disabling:
 * <ul>
 * <li>{@code SQLITE_TESTCTRL_NEVER_CORRUPT}: well-formed snapshot & bytecode
 * invariant assertions</li>
 * <li>{@code SQLITE_TESTCTRL_OPTIMIZATIONS}: bitmask of disabled compiler/VM
 * optimizations</li>
 * <li>{@code SQLITE_TESTCTRL_ASSERT}: Tier-1 invariant checks and Tier-2 O(N)
 * structural audits</li>
 * <li>{@code SQLITE_TESTCTRL_VDBE_COVERAGE}: bytecode branch and boundary
 * {@code testcase()} probes</li>
 * </ul>
 */
public final class ImpulseTestControl {

	private static volatile int disabledOptimizationsMask = Integer.getInteger("impulse.compiler.disabledOpts", 0);

	private ImpulseTestControl() {
	}

	public static void setNeverCorrupt(boolean neverCorrupt) {
		ImpulseAssert.setNeverCorrupt(neverCorrupt);
	}

	public static boolean isNeverCorrupt() {
		return ImpulseAssert.isNeverCorrupt();
	}

	public static void setAssertionsEnabled(boolean enabled) {
		ImpulseAssert.setAssertionsEnabled(enabled);
	}

	public static boolean isAssertionsEnabled() {
		return ImpulseAssert.isAssertionsEnabled();
	}

	public static void setAuditEnabled(boolean enabled) {
		ImpulseAssert.setAuditEnabled(enabled);
	}

	public static boolean isAuditEnabled() {
		return ImpulseAssert.isAuditEnabled();
	}

	public static void setCoverageMode(boolean enabled) {
		ImpulseAssert.setCoverageMode(enabled);
	}

	public static boolean isCoverageMode() {
		return ImpulseAssert.isCoverageMode();
	}

	/**
	 * SQLite {@code SQLITE_TESTCTRL_OPTIMIZATIONS} equivalent: sets the bitmask of
	 * disabled compiler passes and VM execution optimizations.
	 */
	public static void setDisabledOptimizationsMask(int mask) {
		disabledOptimizationsMask = mask;
	}

	public static int getDisabledOptimizationsMask() {
		return disabledOptimizationsMask;
	}

	public static boolean isOptimizationDisabled(int flagBit) {
		return (disabledOptimizationsMask & flagBit) != 0;
	}

	public static long testcaseHits() {
		return ImpulseAssert.coverageCounterSum();
	}

	/** Resets all test control flags and coverage counters to baseline defaults. */
	public static void resetAll() {
		ImpulseAssert.setAssertionsEnabled(ImpulseAssert.ASSERTIONS_ENABLED_PROP);
		ImpulseAssert.setAuditEnabled(ImpulseAssert.AUDIT_ENABLED_PROP);
		ImpulseAssert.setCoverageMode(ImpulseAssert.COVERAGE_TEST_PROP);
		ImpulseAssert.setNeverCorrupt(Boolean.getBoolean("impulse.debug.neverCorrupt"));
		ImpulseAssert.resetCoverage();
		disabledOptimizationsMask = Integer.getInteger("impulse.compiler.disabledOpts", 0);
	}
}
