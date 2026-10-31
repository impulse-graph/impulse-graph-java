package org.impulsegraph.vm;

import org.impulsegraph.api.util.ImpulseTestControl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Map;

import static org.impulsegraph.vm.VmRegisterType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SQLite {@code faultsimStep} / {@code test_malloc.c} deterministic countdown
 * fault-injection test suite for {@link VmFaultInjector} and
 * {@link VmQueryContext}.
 */
class VmFaultInjectionCountdownTest {

	private Arena arena;
	private MemorySegment state;
	private MockImpulseGraphSnapshot snap;

	@BeforeEach
	void setUp() {
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseTestControl.setAuditEnabled(true);
		VmFaultInjector.global().disable();
		arena = Arena.ofConfined();
		state = arena.allocate(VmStateLayout.STATE_SIZE_BYTES, 64);
		state.fill((byte) 0);
		MockRelationSnapshot rel = new MockRelationSnapshot(arena, 6, 6, new int[]{0, 2, 4, 5, 6, 6, 6},
				new int[]{1, 2, 2, 3, 4, 5});
		snap = new MockImpulseGraphSnapshot(Map.of("rel0", rel)).withSlot(0, rel);
	}

	@AfterEach
	void tearDown() {
		VmFaultInjector.global().disable();
		if (arena != null && arena.scope().isAlive()) {
			arena.close();
		}
		ImpulseTestControl.resetAll();
	}

	/**
	 * Executes a multi-step workload that performs 6 arena/scratch allocations: 1.
	 * OP_ALLOC_SCRATCH (allocateScratch) 2. OP_INIT_INPUT_SET (acquireBitset) 3.
	 * OP_CSR_WALK (acquireBitset) 4. OP_LANE_INIT_SEEDS (registerLaneFrontier) 5.
	 * OP_LANE_CSR_WALK (registerLaneFrontier) 6. OP_LANE_REDUCE_ATTR
	 * (registerFloatVector)
	 */
	private void runMultiStepAllocationPipeline(VmQueryContext ctx) {
		state.fill((byte) 0);
		// Step 1: allocate scratch memory
		VmHandlers.handleAllocScratch(state, ctx, new VmHandlers.Instruction((byte) 0x73, (byte) 0, 10, 256));
		// Step 2: acquire initial bitset
		int hSeed = ctx.acquireBitset();
		ctx.getBitset(hSeed).set(0);
		ctx.getBitset(hSeed).set(1);
		VmHandlers.setRegister(state, 1, hSeed, TYPE_BITSET_HANDLE);
		// Step 3: 1-bit CSR walk
		VmHandlers.handleCsrWalk(state, ctx, new VmHandlers.Instruction((byte) 0x10, (byte) 0, 2, 1));
		// Step 4: multi-lane init seeds from bitset R1
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, new VmHandlers.Instruction((byte) 0xC0, (byte) 0, 3, 1),
				0);
		// Step 5: multi-lane CSR walk
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, new VmHandlers.Instruction((byte) 0xC3, (byte) 0, 4, 3),
				1);
		// Step 6: multi-lane attribute reduction
		VmMultiLaneHandlers.handleMultiLaneOpcode(state, ctx, new VmHandlers.Instruction((byte) 0xC8, (byte) 0, 5, 4),
				2);
		VmInvariants.verifyPostStep(state, ctx);
	}

	@Test
	@DisplayName("Deterministic countdown loop (delay = 0..N) injects fault at every allocation point without corruption")
	void testDeterministicCountdownLoopAcrossPipeline() {
		int faultedIterations = 0;
		for (int delay = 0; delay < 32; delay++) {
			try (VmQueryContext ctx = new VmQueryContext(snap, Arena.ofConfined())) {
				VmFaultInjector injector = ctx.getFaultInjector();
				injector.configure(delay, 1);

				try {
					runMultiStepAllocationPipeline(ctx);
					// Pipeline succeeded: verify we reached the end of the allocation chain (0
					// faults triggered)
					assertEquals(0, injector.failuresSeen(),
							"When pipeline completes normally at delay=" + delay + ", failuresSeen must be 0");
					break;
				} catch (IllegalStateException ex) {
					assertTrue(ex.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"),
							"Injected fault must report IMPULSE_VM_ERR_OUT_OF_BOUNDS, got: " + ex.getMessage());
					assertEquals(1, injector.failuresSeen());
					faultedIterations++;
				}
			}
		}
		assertEquals(6, faultedIterations,
				"Exactly 6 distinct allocation sites in the pipeline must be exercised by the countdown loop");
	}

	@Test
	@DisplayName("Benign failure bracket (beginBenignFailure / endBenignFailure) records benign fault without throwing")
	void testBenignFailureBracket() {
		VmFaultInjector injector = new VmFaultInjector();
		injector.configure(1, 2);

		// Delay 1: first call succeeds
		assertFalse(injector.stepOrThrow("alloc-0"));
		assertEquals(0, injector.failuresSeen());

		// Second call inside benign bracket: returns true instead of throwing
		injector.beginBenignFailure();
		assertTrue(injector.isBenign());
		assertTrue(injector.stepOrThrow("optional-cache-alloc"));
		assertEquals(1, injector.failuresSeen());
		assertEquals(1, injector.benignFailuresSeen());
		injector.endBenignFailure();
		assertFalse(injector.isBenign());

		// Third call outside benign bracket: throws IMPULSE_VM_ERR_OUT_OF_BOUNDS
		IllegalStateException ex = assertThrows(IllegalStateException.class,
				() -> injector.stepOrThrow("mandatory-scratch-alloc"));
		assertTrue(ex.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));
		assertEquals(2, injector.failuresSeen());
		assertEquals(1, injector.benignFailuresSeen());

		// Repeat exhausted (repeat=2): subsequent calls succeed
		assertFalse(injector.stepOrThrow("alloc-after-repeat"));
	}
}
