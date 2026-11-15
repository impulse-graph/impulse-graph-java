package org.impulsegraph.vm;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

/**
 * Jazzer coverage-guided fuzz target for {@link ImpulseVmInterpreter}.
 * Generates arbitrary 8-byte instruction sequences over a small off-heap
 * {@link GraphSnapshot} and verifies bounded, crash-free VM execution.
 */
class ImpulseVmBytecodeFuzzTest {

	private static final int MAX_INSTRUCTIONS = 32;

	@FuzzTest(maxDuration = "5s")
	void fuzzExecuteBytecode(FuzzedDataProvider data) {
		int instrCount = data.consumeInt(1, MAX_INSTRUCTIONS);
		byte[] rawBytes = data.consumeBytes(instrCount * 8);
		int actualInstrs = Math.max(1, rawBytes.length / 8);

		try (Arena arena = Arena.ofShared()) {
			MemorySegment offsets = arena.allocate(ValueLayout.JAVA_INT, 5);
			offsets.setAtIndex(ValueLayout.JAVA_INT, 0, 0);
			offsets.setAtIndex(ValueLayout.JAVA_INT, 1, 2);
			offsets.setAtIndex(ValueLayout.JAVA_INT, 2, 3);
			offsets.setAtIndex(ValueLayout.JAVA_INT, 3, 4);
			offsets.setAtIndex(ValueLayout.JAVA_INT, 4, 4);

			MemorySegment targets = arena.allocate(ValueLayout.JAVA_INT, 4);
			targets.setAtIndex(ValueLayout.JAVA_INT, 0, 1);
			targets.setAtIndex(ValueLayout.JAVA_INT, 1, 2);
			targets.setAtIndex(ValueLayout.JAVA_INT, 2, 3);
			targets.setAtIndex(ValueLayout.JAVA_INT, 3, 0);

			RelationSnapshot rel = new RelationSnapshot(arena, 4, 4, offsets, targets);
			ImpulseGraphSnapshot snap = new GraphSnapshot(arena, Map.of("rel_0", rel));

			MemorySegment prog = arena.allocate((long) (actualInstrs + 1) * 8L, 8);
			for (int i = 0; i < actualInstrs * 8; i++) {
				prog.set(ValueLayout.JAVA_BYTE, i, i < rawBytes.length ? rawBytes[i] : (byte) 0);
			}
			// Append OP_HALT (0xFF) as safety sentinel
			prog.set(ValueLayout.JAVA_BYTE, (long) actualInstrs * 8L, (byte) 0xFF);

			ImpulseVmInterpreter.executeWithFuel(prog, actualInstrs + 1L, snap, 0, arena, 512L);
		} catch (IllegalArgumentException | IllegalStateException | ArithmeticException
				| UnsupportedOperationException expected) {
			// Expected VM validation / trap / arithmetic rejections on random bytecode
		}
	}
}
