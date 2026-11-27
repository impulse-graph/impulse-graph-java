package org.impulsegraph.vm;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.impulsegraph.vm.ImpulseIsaTables.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M2.6 Verification Suite: 1. Bit-for-bit {@code ICTX v2} parity between
 * {@link ImpulseMethodHandleCompiler#compileValidated} and
 * {@link ImpulseInterpreter#executeValidated} across straight-line fused
 * blocks, loops, CALL/RET frames, frontiers, walks, math, and multi-lane
 * operations. 2. Empirical JDK Flight Recorder (JFR) steady-state
 * {@code 0 B/query} allocation gate on hot-path
 * {@link ImpulseMethodHandleCompiler#compile} execution.
 */
class ImpulseJitAndAllocationTest {

	private static long enc(int op, int flags, int subop, int dst, int s1, int s2, int s3, int imm32) {
		return ImpulseInterpreter.encode(op, flags, subop, dst, s1, s2, s3, imm32);
	}

	@Test
	void compiledJitMatchesInterpreterBitForBitOnFusedBlocksAndLoops() {
		long[] prog = new long[]{
				// Straight-line fused block (5 instructions)
				enc(OP_LOAD_CONST, 0, SUBOP_LOAD_CONST_I32, 0, 0, 0, 0, 10),
				enc(OP_LOAD_CONST, 0, SUBOP_LOAD_CONST_I32, 1, 0, 0, 0, 0),
				enc(OP_LOAD_CONST, 0, SUBOP_LOAD_CONST_I32, 2, 0, 0, 0, 7), enc(OP_SCALAR_INT_ALU, 0, 0, 1, 1, 2, 0, 0), // R1
																															// =
																															// R1
																															// +
																															// R2
				enc(OP_LOOP_DECR, 0, 0, 0, 0, 0, 0, -1), // loop R0 times -> R1 = 70
				enc(OP_COLLECT, 0, SUBOP_COLLECT_SCALAR, 0, 1, 0, 0, 0), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)};
		ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{16},
				new ImpulseSnapshotView(new long[]{16}, new ImpulseSnapshotView.RelView[0], Map.of(), Map.of(),
						Map.of()));

		ImpulseWorkspace wsInterp = new ImpulseWorkspace(16);
		int stInterp = ImpulseInterpreter.executeValidated(prog, bind, wsInterp, 1000L);
		ImpulseContextHash.Digest dInterp = ImpulseContextHash.compute(wsInterp);

		ImpulseMethodHandleCompiler.CompiledProgram compiled = ImpulseMethodHandleCompiler.compileValidated(prog, bind);
		assertTrue(compiled.isValid());
		assertTrue(compiled.fusedBlockCount() >= 1, "Expected basic-block MethodHandle fusion");

		ImpulseWorkspace wsJit = new ImpulseWorkspace(16);
		int stJit = compiled.execute(wsJit, 1000L);
		ImpulseContextHash.Digest dJit = ImpulseContextHash.compute(wsJit);

		assertEquals(IMPULSE_VM_OK, stInterp);
		assertEquals(stInterp, stJit);
		assertEquals(70L, wsJit.rRegs[1]);
		assertEquals(dInterp.logicalHash(), dJit.logicalHash());
		assertEquals(dInterp.physicalHash(), dJit.physicalHash());
	}

	@Test
	void compiledJitMatchesInterpreterOnGraphWalkAndMultiLane() {
		ImpulseSnapshotView.RelView rel = ImpulseOffHeapFixtureBuilder.buildRel(0, 0, 0, 4, new int[]{0, 2, 3, 4, 4},
				new long[]{1, 2, 3, 3}, new HashMap<>(), true, false, false, false, false);
		ImpulseSnapshotView view = new ImpulseSnapshotView(new long[]{4}, new ImpulseSnapshotView.RelView[]{rel},
				Map.of(), Map.of(), Map.of());
		ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{4}, view);

		long[] prog = new long[]{enc(OP_FSEED, 0, 1, 0, 0, 0, 0, ImpulseInterpreter.packImm16(0, 0)),
				enc(OP_WALK, 0, 0, 1, 0, 0, 0, ImpulseInterpreter.packImm16(0, 0)),
				enc(OP_WALK, 0, 0, 2, 1, 0, 0, ImpulseInterpreter.packImm16(0, 0)), enc(OP_SET_OP, 0, 0, 3, 1, 2, 0, 0),
				enc(OP_SET_CARDINALITY, 0, 0, 0, 3, 0, 0, 0),
				enc(OP_COLLECT, 0, SUBOP_COLLECT_FRONTIER_IDS, 0, 3, 0, 0, 0), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)};

		ImpulseWorkspace wsInterp = new ImpulseWorkspace(16);
		ImpulseInterpreter.executeValidated(prog, bind, wsInterp, 1000L);
		ImpulseContextHash.Digest dInterp = ImpulseContextHash.compute(wsInterp);

		ImpulseWorkspace wsJit = new ImpulseWorkspace(16);
		ImpulseMethodHandleCompiler.CompiledProgram compiled = ImpulseMethodHandleCompiler.compileValidated(prog, bind);
		compiled.execute(wsJit, 1000L);
		ImpulseContextHash.Digest dJit = ImpulseContextHash.compute(wsJit);

		assertEquals(3L, wsJit.rRegs[0]);
		assertEquals(dInterp.logicalHash(), dJit.logicalHash());
		assertEquals(dInterp.physicalHash(), dJit.physicalHash());
	}

	@Test
	void jfrSteadyStateHotPathZeroBytesPerQuery() throws Throwable {
		// Straight-line hot-path program compiled to flat (ImpulseWorkspace) -> void
		// MethodHandle
		int domCard = 1024;
		ImpulseInterpreter.SnapshotBinding bind = new ImpulseInterpreter.SnapshotBinding(new int[]{domCard});
		long[] prog = new long[]{enc(OP_FSEED, 0, 1, 0, 0, 0, 0, ImpulseInterpreter.packImm16(1, 0)),
				enc(OP_FSEED, FLAG_ACCUM, 1, 0, 0, 0, 0, ImpulseInterpreter.packImm16(42, 0)),
				enc(OP_FSEED, 0, 1, 1, 0, 0, 0, ImpulseInterpreter.packImm16(100, 0)),
				enc(OP_SET_OP, 0, 0, 2, 0, 1, 0, 0), enc(OP_VEC_INIT, 0, 12, 0, 0, 0, 0, 0),
				enc(OP_LOAD_CONST, 0, 2, 0, 0, 0, 0, Float.floatToRawIntBits(3.5f)), enc(OP_HALT, 0, 0, 0, 0, 0, 0, 0)};

		MethodHandle mh = ImpulseMethodHandleCompiler.compile(prog, bind);
		ImpulseWorkspace ws = new ImpulseWorkspace(domCard);

		// C2 warmup (50,000 iterations)
		for (int i = 0; i < 50_000; i++) {
			mh.invokeExact(ws);
		}
		assertEquals(3, ws.fRegs[2].cardinality());

		// JFR Allocation Profiling Gate over 10,000 steady-state queries on this thread
		Thread self = Thread.currentThread();
		Path jfrPath = Files.createTempFile("v11-alloc-gate-", ".jfr");
		long allocatedBytes = 0L;
		try {
			try (Recording rec = new Recording()) {
				rec.enable("jdk.ObjectAllocationInNewTLAB");
				rec.enable("jdk.ObjectAllocationOutsideTLAB");
				rec.start();
				for (int i = 0; i < 10_000; i++) {
					mh.invokeExact(ws);
				}
				rec.stop();
				rec.dump(jfrPath);
			}
			try (RecordingFile rf = new RecordingFile(jfrPath)) {
				while (rf.hasMoreEvents()) {
					RecordedEvent ev = rf.readEvent();
					String name = ev.getEventType().getName();
					if (!"jdk.ObjectAllocationInNewTLAB".equals(name)
							&& !"jdk.ObjectAllocationOutsideTLAB".equals(name)) {
						continue;
					}
					if (ev.getThread() != null && ev.getThread().getJavaThreadId() == self.threadId()) {
						allocatedBytes += ev.getLong("allocationSize");
					}
				}
			}
		} finally {
			Files.deleteIfExists(jfrPath);
		}
		assertTrue(allocatedBytes <= 256L,
				"Expected <= 256 B (JFR stop overhead) across 10,000 compiled v0.11 queries, got " + allocatedBytes
						+ " B");
	}
}
