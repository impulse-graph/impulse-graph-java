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
 * Unit tests for {@link VmInvariants} (SQLite {@code memAboutToChange} &amp;
 * {@code vdbeRecordCompareDebug} patterns) and {@link VmBranchCoverageRecorder}
 * (SQLite {@code VdbeCoverage} / {@code test_vdbecov.c} pattern).
 */
class VmInvariantsAndCoverageTest {

	private Arena arena;
	private MemorySegment state;
	private VmQueryContext ctx;

	@BeforeEach
	void setUp() {
		ImpulseTestControl.setAssertionsEnabled(true);
		ImpulseTestControl.setAuditEnabled(true);
		VmBranchCoverageRecorder.resetGlobal();
		arena = Arena.ofConfined();
		state = arena.allocate(VmStateLayout.STATE_SIZE_BYTES, 64);
		state.fill((byte) 0);
		MockRelationSnapshot rel = new MockRelationSnapshot(arena, 4, 3, new int[]{0, 1, 2, 3, 3}, new int[]{1, 2, 3});
		MockImpulseGraphSnapshot snap = new MockImpulseGraphSnapshot(Map.of("rel0", rel)).withSlot(0, rel);
		ctx = new VmQueryContext(snap, Arena.ofConfined());
	}

	@AfterEach
	void tearDown() {
		if (ctx != null) {
			ctx.close();
		}
		if (arena != null && arena.scope().isAlive()) {
			arena.close();
		}
		VmBranchCoverageRecorder.resetGlobal();
		ImpulseTestControl.resetAll();
	}

	@Test
	@DisplayName("VmInvariants catches dirty upper-32-bit TYPE_FLOAT, invalid flags, and corrupt call_stack_depth")
	void testVmInvariantsCatchesCorruptRegisterAndStateFields() {
		// Clean state passes
		assertDoesNotThrow(() -> VmInvariants.verifyPostStep(state, ctx));

		// 1. Non-zero upper 32 bits in TYPE_FLOAT register
		VmHandlers.setRegister(state, 5, (1L << 40) | Float.floatToRawIntBits(1.0f), TYPE_FLOAT);
		AssertionError errFloat = assertThrows(AssertionError.class, () -> VmInvariants.verifyPostStep(state, ctx));
		assertTrue(errFloat.getMessage().contains("upper 32 bits"));
		VmHandlers.setRegister(state, 5, 0L, TYPE_NULL);

		// 2. Undefined status flag bit (bit 7 = 0x80)
		VmStateLayout.FLAGS_HANDLE.set(state, 0L, 0x80L);
		AssertionError errFlags = assertThrows(AssertionError.class, () -> VmInvariants.verifyPostStep(state, ctx));
		assertTrue(errFlags.getMessage().contains("Undefined status flag bits"));
		VmStateLayout.FLAGS_HANDLE.set(state, 0L, 0L);

		// 3. Call stack depth > 8
		VmStateLayout.CALL_STACK_DEPTH_HANDLE.set(state, 0L, 9);
		AssertionError errDepth = assertThrows(AssertionError.class, () -> VmInvariants.verifyPostStep(state, ctx));
		assertTrue(errDepth.getMessage().contains("Call stack depth"));
		VmStateLayout.CALL_STACK_DEPTH_HANDLE.set(state, 0L, 0);

		// 4. Unknown register type tag (0x55)
		VmHandlers.setRegister(state, 3, 0L, (byte) 0x55);
		AssertionError errTag = assertThrows(AssertionError.class, () -> VmInvariants.verifyPostStep(state, ctx));
		assertTrue(errTag.getMessage().contains("unknown/corrupt type tag"));
		VmHandlers.setRegister(state, 3, 0L, TYPE_NULL);
	}

	@Test
	@DisplayName("VmInvariants catches multi-lane active_mask leakage and 1L<<64 shift wrap bug")
	void testMultiLaneActiveMaskPurityAnd64BitShiftSafety() {
		// Verify makeActiveMaskU64(0..64) avoids Java's (1L << 64) == 1L wrap bug
		assertEquals(0L, VmRegType.makeActiveMaskU64(0));
		assertEquals(1L, VmRegType.makeActiveMaskU64(1));
		assertEquals(0xFFL, VmRegType.makeActiveMaskU64(8));
		assertEquals(0xFFFFL, VmRegType.makeActiveMaskU64(16));
		assertEquals(0xFFFFFFFFL, VmRegType.makeActiveMaskU64(32));
		assertEquals(0x7FFFFFFFFFFFFFFFL, VmRegType.makeActiveMaskU64(63));
		assertEquals(-1L, VmRegType.makeActiveMaskU64(64));

		// Inject a polluted lane bit outside activeMask (numLanes=3 -> activeMask=0x07,
		// but lanes[0]=0x0F)
		VmLaneFrontier polluted = new VmLaneFrontier(VmLaneFrontier.LaneWidth.WIDTH_8, 3, 0x07L, 4,
				new long[]{0x0FL, 0L, 0L, 0L}, null);
		int h = ctx.registerLaneFrontier(polluted);
		VmHandlers.setRegister(state, 1, h, TYPE_LANE_FRONTIER);

		AssertionError err = assertThrows(AssertionError.class, () -> VmInvariants.verifyPostStep(state, ctx));
		assertTrue(err.getMessage().contains("active_mask or width boundary invariant violated"));
	}

	@Test
	@DisplayName("VmInvariants.verifyArgminArgmaxTieBreaking enforces lowest-index witness on ties")
	void testArgminArgmaxTieBreakingOracle() {
		float[] tiedMin = new float[]{10.0f, 3.0f, 3.0f, 5.0f};
		assertDoesNotThrow(() -> VmInvariants.verifyArgminArgmaxTieBreaking(true, tiedMin, 1L));
		assertThrows(AssertionError.class, () -> VmInvariants.verifyArgminArgmaxTieBreaking(true, tiedMin, 2L));

		float[] tiedMax = new float[]{2.0f, 9.0f, 9.0f, 4.0f};
		assertDoesNotThrow(() -> VmInvariants.verifyArgminArgmaxTieBreaking(false, tiedMax, 1L));
		assertThrows(AssertionError.class, () -> VmInvariants.verifyArgminArgmaxTieBreaking(false, tiedMax, 2L));
	}

	@Test
	@DisplayName("VmBranchCoverageRecorder tracks 2-way (0x03) and 3-way (0x07) branches & traps on ALWAYS/NEVER violations")
	void testVmBranchCoverageRecorderTwoWayAndThreeWay() {
		VmBranchCoverageRecorder rec = ctx.getCoverageRecorder();
		assertNotNull(rec);

		// 1. Two-way branch (OP_JZ 0x51 at pc=10)
		rec.recordBranch(10, 0x51, 1, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
				VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, 0);
		VmBranchCoverageRecorder.SiteKey key2Way = new VmBranchCoverageRecorder.SiteKey(10, 0x51, 1);
		assertFalse(rec.snapshotLocal().get(key2Way).isFullyCovered());

		rec.recordBranch(10, 0x51, 1, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
				VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, 1);
		assertTrue(rec.snapshotLocal().get(key2Way).isFullyCovered());
		assertEquals(0x03, rec.snapshotLocal().get(key2Way).observedMask());

		// 2. Three-way branch (OP_LOOP_DECR 0x53 at pc=20: >0, ==0, <0)
		VmBranchCoverageRecorder.SiteKey key3Way = new VmBranchCoverageRecorder.SiteKey(20, 0x53, 1);
		rec.recordBranch(20, 0x53, 1, VmBranchCoverageRecorder.BranchKind.THREE_WAY,
				VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, 0);
		rec.recordBranch(20, 0x53, 1, VmBranchCoverageRecorder.BranchKind.THREE_WAY,
				VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, 1);
		assertFalse(rec.snapshotLocal().get(key3Way).isFullyCovered());
		rec.recordBranch(20, 0x53, 1, VmBranchCoverageRecorder.BranchKind.THREE_WAY,
				VmBranchCoverageRecorder.BranchExpectation.COVERAGE_BOTH, 2);
		assertTrue(rec.snapshotLocal().get(key3Way).isFullyCovered());
		assertEquals(0x07, rec.snapshotLocal().get(key3Way).observedMask());

		// 3. COVERAGE_ALWAYS_TAKEN traps if branchIndex != 0
		assertDoesNotThrow(() -> rec.recordBranch(30, 0x51, 1, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
				VmBranchCoverageRecorder.BranchExpectation.COVERAGE_ALWAYS_TAKEN, 0));
		assertThrows(AssertionError.class,
				() -> rec.recordBranch(30, 0x51, 1, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
						VmBranchCoverageRecorder.BranchExpectation.COVERAGE_ALWAYS_TAKEN, 1));

		// 4. COVERAGE_NEVER_TAKEN traps if branchIndex == 0
		assertDoesNotThrow(() -> rec.recordBranch(40, 0x51, 1, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
				VmBranchCoverageRecorder.BranchExpectation.COVERAGE_NEVER_TAKEN, 1));
		assertThrows(AssertionError.class,
				() -> rec.recordBranch(40, 0x51, 1, VmBranchCoverageRecorder.BranchKind.TWO_WAY,
						VmBranchCoverageRecorder.BranchExpectation.COVERAGE_NEVER_TAKEN, 0));
	}

	@Test
	@DisplayName("Bytecode audit: ZERO heap allocations and ZERO autoboxing in all VM opcode handlers")
	void testZeroHotPathOpcodeBytecodeAllocationsAndAutoboxing() throws Exception {
		java.nio.file.Path vmPkgDir = java.nio.file.Paths
				.get(VmHandlers.class.getProtectionDomain().getCodeSource().getLocation().toURI())
				.resolve("org/impulsegraph/vm");
		String[] targets = {"VmHandlers.class", "VmMultiLaneHandlers.class", "ShaderOps.class", "VmLaneFrontier.class",
				"ImpulseMethodHandleCompiler.class"};
		java.util.Set<String> excludedSubstrings = java.util.Set.of("static {};", "<init>", "$values()",
				"decodeInstruction", "decodeExtendedInstruction", "compile(", "compileDriver(", "bindInstruction(",
				"wrapVoidHandler(", "getSkipHandler(", "fallbackExecute(", "executeCompiled(");

		java.util.List<String> violations = new java.util.ArrayList<>();
		int checkedMethods = 0;

		for (String target : targets) {
			java.nio.file.Path classFile = vmPkgDir.resolve(target);
			assertTrue(java.nio.file.Files.exists(classFile), "Missing class file: " + classFile);
			Process proc = new ProcessBuilder("javap", "-c", "-p", classFile.toString()).redirectErrorStream(true)
					.start();
			String out = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			assertEquals(0, proc.waitFor(), "javap failed on " + classFile);

			String curMethod = null;
			for (String line : out.split("\\R")) {
				if (line.matches(
						"^  (public|private|protected|static|final|synchronized|native|abstract|org\\.|void|int|long|boolean|float|double|byte|java\\.).*;")) {
					curMethod = line.trim();
					String mRef = curMethod;
					if (excludedSubstrings.stream().noneMatch(mRef::contains)) {
						checkedMethods++;
					}
					continue;
				}
				if (curMethod == null) {
					continue;
				}
				String mRef = curMethod;
				if (excludedSubstrings.stream().anyMatch(mRef::contains)) {
					continue;
				}
				if (line.matches(".*\\b(new|newarray|anewarray|multianewarray)\\b.*") && !line.contains("Exception")
						&& !line.contains("Error")) {
					violations.add(target + " :: " + curMethod + " -> " + line.trim());
				}
				if (line.contains("valueOf:") && (line.contains("Integer.") || line.contains("Long.")
						|| line.contains("Double.") || line.contains("Float.") || line.contains("Boolean."))) {
					violations.add(target + " :: " + curMethod + " -> BOXING: " + line.trim());
				}
				if (line.contains("Arrays.copyOf")) {
					violations.add(target + " :: " + curMethod + " -> COPYOF: " + line.trim());
				}
			}
		}

		assertTrue(checkedMethods >= 100, "Expected at least 100 opcode handler methods, checked " + checkedMethods);
		assertTrue(violations.isEmpty(), () -> "Found hot-path heap allocations or autoboxing in VM opcode handlers:\n"
				+ String.join("\n", violations));
	}
}
