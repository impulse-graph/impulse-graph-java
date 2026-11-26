package org.impulsegraph.vm.v11;

/**
 * Deterministic Two-Buffer Memory Planner for ImpulseVM v0.11.0 (Java).
 *
 * <p>
 * Mirrors C++ {@code impulse_vm_plan_memory_v11}
 * ({@code v11_memory_planner.cpp}): given a bound
 * {@code (program, snapshotView)}, deterministically computes the required
 * caller-owned {@code scratchBytes} and {@code resultBytes} before execution.
 */
public final class V11MemoryPlanner {

	private static final long ALIGN_128 = 128L;

	public record MemoryPlan(long scratchBytes, long resultBytes, int maxDomainCard, int maxOutputSlots) {
	}

	private V11MemoryPlanner() {
	}

	private static long align128(long bytes) {
		return (bytes + ALIGN_128 - 1L) & ~(ALIGN_128 - 1L);
	}

	public static MemoryPlan plan(long[] program, V11SnapshotView view) {
		int maxDom = 64;
		if (view != null && view.domainCards != null) {
			for (long c : view.domainCards) {
				if (c > maxDom && c <= Integer.MAX_VALUE) {
					maxDom = (int) c;
				}
			}
		}
		int bitsetWords = Math.max(1, (maxDom + 63) >>> 6);
		long denseBitsetBytes = align128((long) bitsetWords * 8L);

		int maxOutputSlot = 0;
		long resultBytes = 0L;
		int frontierOps = 0;
		int vectorOps = 0;

		if (program != null) {
			for (long w : program) {
				int op = (int) (w & 0xFFL);
				int subop = (int) ((w >>> 8) & 0xFL);
				int immLo = (int) ((w >>> 32) & 0xFFFFL);
				if (op == V11IsaTables.OP_COLLECT) {
					maxOutputSlot = Math.max(maxOutputSlot, immLo + 1);
					resultBytes += switch (subop) {
						case V11IsaTables.SUBOP_COLLECT_FRONTIER_BITSET -> denseBitsetBytes * 2L;
						case V11IsaTables.SUBOP_COLLECT_FRONTIER_IDS -> align128((long) maxDom * 8L) + denseBitsetBytes;
						case V11IsaTables.SUBOP_COLLECT_SCALAR -> ALIGN_128;
						default -> align128((long) maxDom * 8L) + denseBitsetBytes;
					};
				} else if (op >= 0x20 && op <= 0x3B) {
					frontierOps++;
				} else if (op >= 0x40 && op <= 0x6F) {
					vectorOps++;
				}
			}
		}

		// Deterministic scratch upper bound: 8 global frontier registers + BFS/arena
		// queues + vector scratch
		long frontierBank = 8L * denseBitsetBytes + 8L * align128(256L);
		long queueScratch = (frontierOps > 0 ? 4L : 1L) * align128((long) maxDom * 4L);
		long vecScratch = (vectorOps > 0 ? 4L : 0L) * align128((long) maxDom * 8L);
		long scratchBytes = align128(frontierBank + queueScratch + vecScratch + 4096L);
		resultBytes = align128(Math.max(ALIGN_128, resultBytes));

		return new MemoryPlan(scratchBytes, resultBytes, maxDom, Math.max(1, maxOutputSlot));
	}
}
