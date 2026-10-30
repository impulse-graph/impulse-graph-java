package org.impulsegraph.vm;

import java.util.Map;
import java.util.TreeMap;
import org.impulsegraph.api.util.ImpulseAssert;

/**
 * Bytecode-Level Branch Coverage Recorder for ImpulseVM (SQLite VdbeCoverage /
 * test_vdbecov.c pattern). Tracks 2-way (0x03) and 3-way (0x07) branch
 * decisions per PC and enforces COVERAGE_BOTH, COVERAGE_ALWAYS_TAKEN, and
 * COVERAGE_NEVER_TAKEN constraints.
 */
public final class VmBranchCoverageRecorder {

	public enum BranchKind {
		TWO_WAY(0x03), THREE_WAY(0x07);

		private final int fullMask;

		BranchKind(int fullMask) {
			this.fullMask = fullMask;
		}

		public int fullMask() {
			return fullMask;
		}
	}

	public enum BranchExpectation {
		COVERAGE_BOTH, COVERAGE_ALWAYS_TAKEN, COVERAGE_NEVER_TAKEN
	}

	public record SiteKey(int pc, int opcode, int siteId) implements Comparable<SiteKey> {
		@Override
		public int compareTo(SiteKey o) {
			int c = Integer.compare(this.pc, o.pc);
			if (c != 0)
				return c;
			c = Integer.compare(this.opcode, o.opcode);
			if (c != 0)
				return c;
			return Integer.compare(this.siteId, o.siteId);
		}
	}

	public static final class SiteEntry {
		private final BranchKind kind;
		private BranchExpectation expectation;
		private int observedMask;

		public SiteEntry(BranchKind kind, BranchExpectation expectation, int observedMask) {
			this.kind = kind;
			this.expectation = expectation;
			this.observedMask = observedMask;
		}

		public BranchKind kind() {
			return kind;
		}

		public BranchExpectation expectation() {
			return expectation;
		}

		public int observedMask() {
			return observedMask;
		}

		public boolean isFullyCovered() {
			return switch (expectation) {
				case COVERAGE_BOTH -> (observedMask & kind.fullMask()) == kind.fullMask();
				case COVERAGE_ALWAYS_TAKEN -> (observedMask & 0x01) != 0 && (observedMask & 0x02) == 0;
				case COVERAGE_NEVER_TAKEN -> (observedMask & 0x02) != 0 && (observedMask & 0x01) == 0;
			};
		}
	}

	private static final Map<SiteKey, SiteEntry> GLOBAL_TABLE = new TreeMap<>();

	private final Map<SiteKey, SiteEntry> localTable = new TreeMap<>();

	public VmBranchCoverageRecorder() {
	}

	public static synchronized void resetGlobal() {
		GLOBAL_TABLE.clear();
	}

	public synchronized void resetLocal() {
		localTable.clear();
	}

	/**
	 * Records a branch decision at {@code (pc, opcode, siteId)}:
	 * {@code branchIndex == 0} (bit 0 = 0x01, taken / primary path),
	 * {@code branchIndex == 1} (bit 1 = 0x02, fall-through / secondary path),
	 * {@code branchIndex == 2} (bit 2 = 0x04, tertiary path for 3-way branches).
	 */
	public void recordBranch(int pc, int opcode, int siteId, BranchKind kind, BranchExpectation expectation,
			int branchIndex) {
		int bit = 1 << branchIndex;
		if (expectation == BranchExpectation.COVERAGE_ALWAYS_TAKEN && branchIndex != 0) {
			ImpulseAssert.check(false, "VmBranchCoverageRecorder: COVERAGE_ALWAYS_TAKEN violated at pc=" + pc
					+ ", opcode=0x" + Integer.toHexString(opcode) + ", site=" + siteId);
		} else if (expectation == BranchExpectation.COVERAGE_NEVER_TAKEN && branchIndex == 0) {
			ImpulseAssert.check(false, "VmBranchCoverageRecorder: COVERAGE_NEVER_TAKEN violated at pc=" + pc
					+ ", opcode=0x" + Integer.toHexString(opcode) + ", site=" + siteId);
		}
		SiteKey key = new SiteKey(pc, opcode & 0xFF, siteId);
		synchronized (this) {
			SiteEntry entry = localTable.get(key);
			if (entry == null) {
				localTable.put(key, new SiteEntry(kind, expectation, bit));
			} else {
				entry.observedMask |= bit;
				entry.expectation = expectation;
			}
		}
		synchronized (VmBranchCoverageRecorder.class) {
			SiteEntry gEntry = GLOBAL_TABLE.get(key);
			if (gEntry == null) {
				GLOBAL_TABLE.put(key, new SiteEntry(kind, expectation, bit));
			} else {
				gEntry.observedMask |= bit;
				gEntry.expectation = expectation;
			}
		}
	}

	public static void recordGlobalBranch(int pc, int opcode, int siteId, BranchKind kind,
			BranchExpectation expectation, int branchIndex) {
		int bit = 1 << branchIndex;
		if (expectation == BranchExpectation.COVERAGE_ALWAYS_TAKEN && branchIndex != 0) {
			ImpulseAssert.check(false, "VmBranchCoverageRecorder: COVERAGE_ALWAYS_TAKEN violated at pc=" + pc
					+ ", opcode=0x" + Integer.toHexString(opcode) + ", site=" + siteId);
		} else if (expectation == BranchExpectation.COVERAGE_NEVER_TAKEN && branchIndex == 0) {
			ImpulseAssert.check(false, "VmBranchCoverageRecorder: COVERAGE_NEVER_TAKEN violated at pc=" + pc
					+ ", opcode=0x" + Integer.toHexString(opcode) + ", site=" + siteId);
		}
		SiteKey key = new SiteKey(pc, opcode & 0xFF, siteId);
		synchronized (VmBranchCoverageRecorder.class) {
			SiteEntry gEntry = GLOBAL_TABLE.get(key);
			if (gEntry == null) {
				GLOBAL_TABLE.put(key, new SiteEntry(kind, expectation, bit));
			} else {
				gEntry.observedMask |= bit;
				gEntry.expectation = expectation;
			}
		}
	}

	public synchronized Map<SiteKey, SiteEntry> snapshotLocal() {
		return new TreeMap<>(localTable);
	}

	public static synchronized Map<SiteKey, SiteEntry> snapshotGlobal() {
		return new TreeMap<>(GLOBAL_TABLE);
	}
}
