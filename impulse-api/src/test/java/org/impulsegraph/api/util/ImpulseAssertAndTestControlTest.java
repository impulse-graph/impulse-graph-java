package org.impulsegraph.api.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ImpulseAssert} and {@link ImpulseTestControl} verifying
 * SQLite-style 3-mode assertion semantics ({@code check},
 * {@code checkWellFormed}, {@code audit}, {@code always}, {@code never},
 * {@code testcase}) and {@code sqlite3_test_control} coordination.
 */
class ImpulseAssertAndTestControlTest {

	@BeforeEach
	void setUp() {
		ImpulseTestControl.resetAll();
	}

	@AfterEach
	void tearDown() {
		ImpulseTestControl.resetAll();
	}

	@Test
	@DisplayName("Tier-1 check() traps when assertions enabled and is a no-op when disabled")
	void testTier1Check() {
		ImpulseTestControl.setAssertionsEnabled(true);
		assertDoesNotThrow(() -> ImpulseAssert.check(true, "should pass"));
		assertDoesNotThrow(() -> ImpulseAssert.check(true, () -> "should pass supplier"));

		AssertionError err1 = assertThrows(AssertionError.class, () -> ImpulseAssert.check(false, "invariant broken"));
		assertTrue(err1.getMessage().contains("invariant broken"));

		AssertionError err2 = assertThrows(AssertionError.class,
				() -> ImpulseAssert.check(false, () -> "supplier invariant broken"));
		assertTrue(err2.getMessage().contains("supplier invariant broken"));

		ImpulseTestControl.setAssertionsEnabled(false);
		assertDoesNotThrow(() -> ImpulseAssert.check(false, "ignored when disabled"));
	}

	@Test
	@DisplayName("Tier-1b checkWellFormed() respects SQLITE_TESTCTRL_NEVER_CORRUPT")
	void testCheckWellFormedNeverCorrupt() {
		ImpulseTestControl.setAssertionsEnabled(true);

		// When neverCorrupt == false (default for untrusted inputs), checkWellFormed
		// does not trap
		ImpulseTestControl.setNeverCorrupt(false);
		assertFalse(ImpulseTestControl.isNeverCorrupt());
		assertDoesNotThrow(() -> ImpulseAssert.checkWellFormed(false, "corrupt input allowed"));
		assertDoesNotThrow(() -> ImpulseAssert.checkWellFormed(false, () -> "corrupt supplier allowed"));

		// When neverCorrupt == true, checkWellFormed traps on false
		ImpulseTestControl.setNeverCorrupt(true);
		assertTrue(ImpulseTestControl.isNeverCorrupt());
		assertDoesNotThrow(() -> ImpulseAssert.checkWellFormed(true, "valid input"));
		assertThrows(AssertionError.class, () -> ImpulseAssert.checkWellFormed(false, "corrupt snapshot header"));
		assertThrows(AssertionError.class,
				() -> ImpulseAssert.checkWellFormed(false, () -> "corrupt snapshot section"));
	}

	@Test
	@DisplayName("Tier-2 audit() skips O(N) supplier when disabled and traps when enabled")
	void testTier2AuditLazyEvaluation() {
		AtomicInteger evaluations = new AtomicInteger(0);
		ImpulseTestControl.setAuditEnabled(false);
		assertFalse(ImpulseTestControl.isAuditEnabled());

		ImpulseAssert.audit(() -> {
			evaluations.incrementAndGet();
			return false;
		}, "should not run when audit disabled");
		assertEquals(0, evaluations.get(), "Audit supplier must not execute when audit is disabled");

		ImpulseTestControl.setAuditEnabled(true);
		assertTrue(ImpulseTestControl.isAuditEnabled());
		ImpulseAssert.audit(() -> {
			evaluations.incrementAndGet();
			return true;
		}, "audit passes");
		assertEquals(1, evaluations.get());

		assertThrows(AssertionError.class, () -> ImpulseAssert.audit(() -> false, "audit failed"));
	}

	@Test
	@DisplayName("SQLite ALWAYS(X) and NEVER(X) 3-mode semantics (Debug, Coverage, Release)")
	void testAlwaysAndNeverThreeModes() {
		// 1. Debug mode (assertions enabled)
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseTestControl.setCoverageMode(false);
		assertTrue(ImpulseAssert.always(true));
		assertThrows(AssertionError.class, () -> ImpulseAssert.always(false));
		assertFalse(ImpulseAssert.never(false));
		assertThrows(AssertionError.class, () -> ImpulseAssert.never(true));

		// 2. Coverage mode (assertions disabled, coverage mode enabled)
		ImpulseTestControl.setAssertionsEnabled(false);
		ImpulseTestControl.setCoverageMode(true);
		assertTrue(ImpulseAssert.always(true));
		assertTrue(ImpulseAssert.always(false), "In coverage mode, always() returns true");
		assertFalse(ImpulseAssert.never(false));
		assertFalse(ImpulseAssert.never(true), "In coverage mode, never() returns false");

		// 3. Release mode (both disabled: pass-through ductile behavior)
		ImpulseTestControl.setAssertionsEnabled(false);
		ImpulseTestControl.setCoverageMode(false);
		assertTrue(ImpulseAssert.always(true));
		assertFalse(ImpulseAssert.always(false));
		assertFalse(ImpulseAssert.never(false));
		assertTrue(ImpulseAssert.never(true));
	}

	@Test
	@DisplayName("SQLite testcase(X) boundary bitmask and hit counters")
	void testTestcaseBoundaryProbes() {
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseAssert.resetCounters();

		assertEquals(0, ImpulseAssert.testcaseMask(42));
		ImpulseAssert.testcase(42, "boundary N==64", false);
		assertEquals(2, ImpulseAssert.testcaseMask(42), "Bit 1 (2) records false observation");
		assertEquals(0L, ImpulseAssert.testcaseTrueCount(42));

		ImpulseAssert.testcase(42, "boundary N==64", true);
		assertEquals(3, ImpulseAssert.testcaseMask(42), "Mask 3 (1|2) records both true and false");
		assertEquals(1L, ImpulseAssert.testcaseTrueCount(42));
		assertEquals("boundary N==64", ImpulseAssert.testcaseDescription(42));
		assertTrue(ImpulseTestControl.testcaseHits() >= 42L);

		Map<Integer, Integer> snap = ImpulseAssert.testcaseSnapshot();
		assertEquals(3, snap.get(42));
	}

	@Test
	@DisplayName("ImpulseTestControl optimization bitmask (SQLITE_TESTCTRL_OPTIMIZATIONS)")
	void testOptimizationBitmaskControl() {
		assertEquals(0, ImpulseTestControl.getDisabledOptimizationsMask());
		assertFalse(ImpulseTestControl.isOptimizationDisabled(1 << 3));

		ImpulseTestControl.setDisabledOptimizationsMask((1 << 3) | (1 << 12));
		assertTrue(ImpulseTestControl.isOptimizationDisabled(1 << 3));
		assertTrue(ImpulseTestControl.isOptimizationDisabled(1 << 12));
		assertFalse(ImpulseTestControl.isOptimizationDisabled(1 << 4));

		ImpulseTestControl.resetAll();
		assertEquals(0, ImpulseTestControl.getDisabledOptimizationsMask());
	}
}
