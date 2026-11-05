package org.impulsegraph.vm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Issue #28: ImpulseVM Security Hardening Verification")
class Issue28HardeningTest {

	@Test
	@DisplayName("ImpulseVmValidator validates stream shader sub-opcode operand bounds")
	void testStreamShaderSubOpcodeValidation() {
		// 1. Valid stream math opcode (dst=0, low=1, high=2 all < 16)
		VmHandlers.Instruction validMath = new VmHandlers.Instruction((byte) 0xA5, (byte) 0, 0, (2 << 16) | 1);
		assertEquals("IMPULSE_VM_OK", ImpulseVmValidator.validate(new VmHandlers.Instruction[]{validMath}));

		// 2. Invalid sDst >= 16
		VmHandlers.Instruction invalidDst = new VmHandlers.Instruction((byte) 0xA5, (byte) 0, 16, (2 << 16) | 1);
		assertEquals("IMPULSE_VM_ERR_INVALID_REGISTER",
				ImpulseVmValidator.validate(new VmHandlers.Instruction[]{invalidDst}));

		// 3. Invalid sPayloadLow >= 16 for math
		VmHandlers.Instruction invalidLow = new VmHandlers.Instruction((byte) 0xA5, (byte) 0, 0, (2 << 16) | 16);
		assertEquals("IMPULSE_VM_ERR_INVALID_REGISTER",
				ImpulseVmValidator.validate(new VmHandlers.Instruction[]{invalidLow}));

		// 4. Invalid sPayloadHigh >= 16 for math
		VmHandlers.Instruction invalidHigh = new VmHandlers.Instruction((byte) 0xA5, (byte) 0, 0, (16 << 16) | 1);
		assertEquals("IMPULSE_VM_ERR_INVALID_REGISTER",
				ImpulseVmValidator.validate(new VmHandlers.Instruction[]{invalidHigh}));

		// 5. Opcode 0xBF (OP_STREAM_SCATTER_REDUCE) is valid with attrId < 64
		VmHandlers.Instruction validScatter = new VmHandlers.Instruction((byte) 0xBF, (byte) 0, 5, 10);
		assertEquals("IMPULSE_VM_OK", ImpulseVmValidator.validate(new VmHandlers.Instruction[]{validScatter}));

		// 6. Opcode 0xBF with attrId >= 64 is invalid register
		VmHandlers.Instruction invalidScatter = new VmHandlers.Instruction((byte) 0xBF, (byte) 0, 5, 64);
		assertEquals("IMPULSE_VM_ERR_INVALID_REGISTER",
				ImpulseVmValidator.validate(new VmHandlers.Instruction[]{invalidScatter}));

		// 7. OP_STREAM_REDUCE (0xA8) with globalReg >= 64 is invalid register
		VmHandlers.Instruction invalidReduce = new VmHandlers.Instruction((byte) 0xA8, (byte) 0, 5, 64);
		assertEquals("IMPULSE_VM_ERR_INVALID_REGISTER",
				ImpulseVmValidator.validate(new VmHandlers.Instruction[]{invalidReduce}));
	}

	@Test
	@DisplayName("Undersized program segment throws IMPULSE_VM_ERR_OUT_OF_BOUNDS")
	void testUndersizedProgramSegment() {
		try (Arena arena = Arena.ofConfined()) {
			// Allocate only 8 bytes for 2 instructions (requires 16 bytes)
			MemorySegment undersized = arena.allocate(8L);

			IllegalStateException ex1 = assertThrows(IllegalStateException.class, () -> {
				ImpulseVmInterpreter.execute(undersized, 2L, null, null, arena);
			});
			assertTrue(ex1.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));

			IllegalStateException ex2 = assertThrows(IllegalStateException.class, () -> {
				ImpulseMethodHandleCompiler.compileDriver(undersized, 2L);
			});
			assertTrue(ex2.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_BOUNDS"));
		}
	}

	@Test
	@DisplayName("Fuel exhaustion stops execution with IMPULSE_VM_ERR_OUT_OF_FUEL")
	void testFuelExhaustion() {
		try (Arena arena = Arena.ofConfined()) {
			// Program: 10 NOP instructions (0x01)
			MemorySegment prog = arena.allocate(80L);
			for (int i = 0; i < 10; i++) {
				prog.set(ValueLayout.JAVA_BYTE, i * 8L, (byte) 0x01); // OP_NOP
			}

			try (VmQueryContext ctx = VmQueryContext.acquire(null, arena)) {
				ctx.setFuel(5L); // Only 5 steps allowed
				assertTrue(ctx.isFuelEnabled());
				assertEquals(5L, ctx.getMaxSteps());

				IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
					for (int i = 0; i < 10; i++) {
						ctx.consumeFuel();
					}
				});
				assertTrue(ex.getMessage().contains("IMPULSE_VM_ERR_OUT_OF_FUEL"));
			}
		}
	}

	@Test
	@DisplayName("VmQueryContext.initMockGraph rejects malformed inputs")
	void testInitMockGraphHardening() {
		try (Arena arena = Arena.ofConfined()) {
			try (VmQueryContext ctx = VmQueryContext.acquire(null, arena)) {
				// 1. Invalid nodeCount > 1_048_576
				assertThrows(IllegalArgumentException.class, () -> {
					ctx.initMockGraph(0, 0, 2_000_000);
				});

				// 2. Negative nodeCount
				assertThrows(IllegalArgumentException.class, () -> {
					ctx.initMockGraph(0, 0, -1);
				});

				// 3. inlineDataSeg == null
				assertThrows(IllegalStateException.class, () -> {
					ctx.initMockGraph(0, 0, 5);
				});

				// Set inline segment
				MemorySegment inlineSeg = arena.allocate(100L);
				ctx.setInlineData(inlineSeg, 100L);

				// 4. offsets[0] != 0
				inlineSeg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN), 0L, 5);
				assertThrows(IllegalArgumentException.class, () -> {
					ctx.initMockGraph(0, 0, 2);
				});

				// 5. Non-monotonic offsets (offsets[0]=0, offsets[1]=5, offsets[2]=3)
				inlineSeg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN), 0L, 0);
				inlineSeg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN), 4L, 5);
				inlineSeg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN), 8L, 3);
				assertThrows(IllegalArgumentException.class, () -> {
					ctx.initMockGraph(0, 0, 2);
				});

				// 6. Target ID out of bounds [0, 1048576)
				inlineSeg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN), 0L, 0);
				inlineSeg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN), 4L, 1);
				// target at offset 8:
				inlineSeg.set(ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN), 8L, 1_048_576); // out of bounds
				assertThrows(IllegalArgumentException.class, () -> {
					ctx.initMockGraph(0, 0, 1);
				});
			}
		}
	}
}
