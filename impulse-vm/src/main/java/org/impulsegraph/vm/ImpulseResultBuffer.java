package org.impulsegraph.vm;

/**
 * Caller-owned Result Buffer for deterministic two-buffer execution alongside
 * {@link ImpulseWorkspace} (scratch buffer), sized from
 * {@link ImpulseMemoryPlanner#plan(long[], ImpulseSnapshotView)}.
 */
public final class ImpulseResultBuffer {

	private final long capacityBytes;
	private final ImpulseVector[] slots;
	private int populatedMask;

	public ImpulseResultBuffer(ImpulseMemoryPlanner.MemoryPlan plan) {
		this.capacityBytes = plan.resultBytes();
		this.slots = new ImpulseVector[Math.max(16, plan.maxOutputSlots())];
	}

	public long capacityBytes() {
		return capacityBytes;
	}

	public void reset() {
		populatedMask = 0;
	}

	public void captureFrom(ImpulseWorkspace ws) {
		int m = ws.outputSlotMask;
		this.populatedMask = m;
		while (m != 0) {
			int s = Integer.numberOfTrailingZeros(m);
			slots[s] = ws.outputSlots[s];
			m &= (m - 1);
		}
	}

	public ImpulseVector getOutput(int slot) {
		if (slot < 0 || slot >= slots.length)
			return null;
		return ((populatedMask & (1 << slot)) != 0) ? slots[slot] : null;
	}
}
