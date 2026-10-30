package org.impulsegraph.api.util;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;

/**
 * Zero-overhead, C2-JIT-friendly invariant assertion and boundary coverage
 * primitives modeled on SQLite's sqliteInt.h (assert, CORRUPT_DB, ALWAYS,
 * NEVER, testcase).
 *
 * <p>
 * Why not Java's built-in {@code assert} keyword?
 * <ol>
 * <li>{@code javac} injects a synthetic {@code $assertionsDisabled} static
 * field and a conditional branch ({@code ifeq}/{@code ifne}) at every call
 * site, polluting JaCoCo and {@code javap} branch coverage metrics.</li>
 * <li>Caller methods in {@code VmHandlers} and {@code ImpulseVmInterpreter}
 * accumulate bytecode bloat that can exceed HotSpot {@code FreqInlineSize} (325
 * bytes).</li>
 * <li>By moving the check inside static methods gated by
 * {@code static final boolean} or {@link ImpulseTestControl} flags, caller
 * bytecode stays minimal and clean.</li>
 * </ol>
 */
public final class ImpulseAssert {

	/**
	 * Tier 1: Standard internal invariant assertions
	 * (-Dimpulse.debug.assertions=true).
	 */
	public static final boolean ASSERTIONS_ENABLED_PROP = Boolean.getBoolean("impulse.debug.assertions");

	/** Alias for {@link #ASSERTIONS_ENABLED_PROP}. */
	public static final boolean ENABLE_ASSERTIONS = ASSERTIONS_ENABLED_PROP;

	/**
	 * Tier 2: Expensive O(N) structural audit assertions
	 * (-Dimpulse.debug.audit=true).
	 */
	public static final boolean AUDIT_ENABLED_PROP = Boolean.getBoolean("impulse.debug.audit");

	/** Alias for {@link #AUDIT_ENABLED_PROP}. */
	public static final boolean ENABLE_AUDIT = AUDIT_ENABLED_PROP;

	/**
	 * Coverage mode (-Dimpulse.coverage.test=true): makes ALWAYS/NEVER pass-through
	 * constants.
	 */
	public static final boolean COVERAGE_TEST_PROP = Boolean.getBoolean("impulse.coverage.test");

	private static volatile boolean runtimeAssertionsEnabled = ASSERTIONS_ENABLED_PROP;
	private static volatile boolean runtimeAuditEnabled = AUDIT_ENABLED_PROP;
	private static volatile boolean runtimeCoverageMode = COVERAGE_TEST_PROP;

	/**
	 * SQLite SQLITE_TESTCTRL_NEVER_CORRUPT equivalent. False by default (untrusted
	 * snapshots / fuzzing may be corrupt). Set to true in positive compliance
	 * suites to enforce well-formedness invariants.
	 */
	private static volatile boolean neverCorrupt = Boolean.getBoolean("impulse.debug.neverCorrupt");

	private static final LongAdder COVERAGE_COUNTER = new LongAdder();
	private static final ConcurrentHashMap<Integer, Integer> TESTCASE_BITMAP = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Integer, LongAdder> TESTCASE_TRUE_COUNTS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Integer, String> TESTCASE_DESCRIPTIONS = new ConcurrentHashMap<>();

	private ImpulseAssert() {
	}

	public static boolean isAssertionsEnabled() {
		return ASSERTIONS_ENABLED_PROP || runtimeAssertionsEnabled;
	}

	public static void setAssertionsEnabled(boolean enabled) {
		runtimeAssertionsEnabled = enabled;
	}

	public static boolean isAuditEnabled() {
		return AUDIT_ENABLED_PROP || runtimeAuditEnabled;
	}

	public static void setAuditEnabled(boolean enabled) {
		runtimeAuditEnabled = enabled;
	}

	public static boolean isCoverageMode() {
		return COVERAGE_TEST_PROP || runtimeCoverageMode;
	}

	public static void setCoverageMode(boolean enabled) {
		runtimeCoverageMode = enabled;
	}

	public static void setNeverCorrupt(boolean value) {
		neverCorrupt = value;
	}

	public static boolean isNeverCorrupt() {
		return neverCorrupt;
	}

	/**
	 * Tier 1: Internal invariant check (SQLite {@code assert(X)} /
	 * {@code IMPULSE_ASSERT(X)}). Must never depend on untrusted external input
	 * unless guarded by {@link #checkWellFormed}.
	 */
	public static void check(boolean cond, String msg) {
		if (isAssertionsEnabled() && !cond) {
			fail("IMPULSE_ASSERT", msg);
		}
	}

	public static void check(boolean cond, java.util.function.Supplier<String> msgSupplier) {
		if (isAssertionsEnabled() && !cond) {
			fail("IMPULSE_ASSERT", msgSupplier.get());
		}
	}

	/**
	 * Tier 1b: Well-formed snapshot/bytecode invariant (SQLite
	 * {@code assert(X || CORRUPT_DB)}). Only traps when both assertions and
	 * {@code neverCorrupt} are enabled; otherwise allows the caller's normal error
	 * return path to handle corrupt input gracefully.
	 */
	public static void checkWellFormed(boolean cond, String msg) {
		if (isAssertionsEnabled() && neverCorrupt && !cond) {
			fail("IMPULSE_WELL_FORMED_ASSERT", msg);
		}
	}

	public static void checkWellFormed(boolean cond, java.util.function.Supplier<String> msgSupplier) {
		if (isAssertionsEnabled() && neverCorrupt && !cond) {
			fail("IMPULSE_WELL_FORMED_ASSERT", msgSupplier.get());
		}
	}

	/**
	 * Tier 2: Expensive O(N) structural invariant check
	 * ({@code IMPULSE_AUDIT_ASSERT}). Uses a {@link BooleanSupplier} so O(N)
	 * traversal is skipped entirely when disabled.
	 */
	public static void audit(BooleanSupplier checkFn, String msg) {
		if (isAuditEnabled() && !checkFn.getAsBoolean()) {
			fail("IMPULSE_AUDIT_ASSERT", msg);
		}
	}

	/**
	 * SQLite {@code ALWAYS(X)} equivalent:
	 * <ul>
	 * <li>Assertions enabled: traps if {@code !cond}.</li>
	 * <li>Coverage mode: returns {@code true} so defensive fallback is not counted
	 * as missed.</li>
	 * <li>Release mode: returns {@code cond} (self-healing / ductile
	 * behavior).</li>
	 * </ul>
	 */
	public static boolean always(boolean cond) {
		if (isAssertionsEnabled() && !cond) {
			fail("IMPULSE_ALWAYS", "Expected always-true condition evaluated to false");
		}
		return isCoverageMode() || cond;
	}

	/**
	 * SQLite {@code NEVER(X)} equivalent:
	 * <ul>
	 * <li>Assertions enabled: traps if {@code cond}.</li>
	 * <li>Coverage mode: returns {@code false}.</li>
	 * <li>Release mode: returns {@code cond} (self-healing / ductile
	 * behavior).</li>
	 * </ul>
	 */
	public static boolean never(boolean cond) {
		if (isAssertionsEnabled() && cond) {
			fail("IMPULSE_NEVER", "Expected never-true condition evaluated to true");
		}
		return !isCoverageMode() && cond;
	}

	/**
	 * SQLite {@code testcase(X)} equivalent with default siteId {@code 1}.
	 */
	public static void testcase(boolean cond) {
		testcase(1, cond);
	}

	/**
	 * SQLite {@code testcase(X)} equivalent: records boundary condition evaluation
	 * (bit 0 = {@code true} observed, bit 1 = {@code false} observed; mask
	 * {@code 0x03} = both sides hit).
	 */
	public static void testcase(int siteId, boolean cond) {
		if (isAssertionsEnabled() || isCoverageMode()) {
			if (cond) {
				COVERAGE_COUNTER.add(siteId);
				TESTCASE_TRUE_COUNTS.computeIfAbsent(siteId, k -> new LongAdder()).increment();
			}
			TESTCASE_BITMAP.merge(siteId, cond ? 1 : 2, (a, b) -> a | b);
		}
	}

	/**
	 * SQLite {@code testcase(X)} with a human-readable boundary description.
	 */
	public static void testcase(int siteId, String description, boolean cond) {
		if (isAssertionsEnabled() || isCoverageMode()) {
			if (description != null) {
				TESTCASE_DESCRIPTIONS.putIfAbsent(siteId, description);
			}
			testcase(siteId, cond);
		}
	}

	/**
	 * Returns the number of times {@code cond == true} was observed at
	 * {@code siteId}.
	 */
	public static long testcaseTrueCount(int siteId) {
		LongAdder adder = TESTCASE_TRUE_COUNTS.get(siteId);
		return adder != null ? adder.sum() : 0L;
	}

	/**
	 * Returns the observed bitmask at {@code siteId} (1 = true seen, 2 = false
	 * seen, 3 = both seen).
	 */
	public static int testcaseMask(int siteId) {
		return TESTCASE_BITMAP.getOrDefault(siteId, 0);
	}

	/** Returns a sorted snapshot of all recorded testcase site bitmasks. */
	public static Map<Integer, Integer> testcaseSnapshot() {
		return Collections.unmodifiableMap(new TreeMap<>(TESTCASE_BITMAP));
	}

	/**
	 * Returns the description registered for {@code siteId}, or empty string if
	 * none.
	 */
	public static String testcaseDescription(int siteId) {
		return TESTCASE_DESCRIPTIONS.getOrDefault(siteId, "");
	}

	public static long coverageCounterSum() {
		return COVERAGE_COUNTER.sum();
	}

	public static void resetCoverage() {
		COVERAGE_COUNTER.reset();
		TESTCASE_BITMAP.clear();
		TESTCASE_TRUE_COUNTS.clear();
		TESTCASE_DESCRIPTIONS.clear();
	}

	public static void resetCounters() {
		resetCoverage();
	}

	private static void fail(String type, String msg) {
		throw new AssertionError("[" + type + " FAILED] " + msg);
	}
}
