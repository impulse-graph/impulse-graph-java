package org.impulsegraph.vm.v11;

/**
 * Caller-owned Result Buffer for deterministic two-buffer execution alongside
 * {@link V11Workspace} (scratch buffer), sized from
 * {@link V11MemoryPlanner#plan(long[], V11SnapshotView)}.
 */
public final class V11ResultBuffer {

	private final long capacityBytes;
	private final V11Vector[] slots;
	private int populatedMask;

	public V11ResultBuffer(V11MemoryPlanner.MemoryPlan plan) {
		this.capacityBytes = plan.resultBytes();
		this.slots = new V11Vector[Math.max(16, plan.maxOutputSlots())];
	}

	public long capacityBytes() {
		return capacityBytes;
	}

	public void reset() {
		populatedMask = 0;
	}

	public void captureFrom(V11Workspace ws) {
		int m = ws.outputSlotMask;
		this.populatedMask = m;
		while (m != 0) {
			int s = Integer.numberOfTrailingZeros(m);
			slots[s] = ws.outputSlots[s];
			m &= (m - 1);
		}
	}

	public V11Vector getOutput(int slot) {
		if (slot < 0 || slot >= slots.length)
			return null;
		return ((populatedMask & (1 << slot)) != 0) ? slots[slot] : null;
	}
}
