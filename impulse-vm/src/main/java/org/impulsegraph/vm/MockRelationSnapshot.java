package org.impulsegraph.vm;

import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;

public class MockRelationSnapshot implements RelationSnapshot {
	private final int nodeCount;
	private final long edgeCount;
	private final MemorySegment rowOffsets;
	private final MemorySegment colTargets;
	private MemorySegment cscRowOffsets;
	private MemorySegment cscColTargets;

	public MockRelationSnapshot(Arena arena, int nodeCount, long edgeCount, int[] offsets, int[] targets) {
		this.nodeCount = nodeCount;
		this.edgeCount = edgeCount;
		this.rowOffsets = arena.allocate((long) offsets.length * 4).copyFrom(MemorySegment.ofArray(offsets));
		this.colTargets = arena.allocate((long) targets.length * 4).copyFrom(MemorySegment.ofArray(targets));
		this.cscRowOffsets = this.rowOffsets;
		this.cscColTargets = this.colTargets;
	}

	public void setCsc(MemorySegment cscRowOffsets, MemorySegment cscColTargets) {
		this.cscRowOffsets = cscRowOffsets;
		this.cscColTargets = cscColTargets;
	}

	@Override
	public long getEdgeCount() {
		return edgeCount;
	}
	@Override
	public MemorySegment getRowOffsetsSegment() {
		return rowOffsets;
	}
	@Override
	public MemorySegment getColumnTargetsSegment() {
		return colTargets;
	}
	@Override
	public MemorySegment getCscColumnTargetsSegment() {
		return cscColTargets;
	}
	@Override
	public MemorySegment getCscRowOffsetsSegment() {
		return cscRowOffsets;
	}
	@Override
	public boolean hasCsc() {
		return cscRowOffsets != null;
	}
	@Override
	public boolean hasCsr() {
		return rowOffsets != null;
	}
	@Override
	public int getNodeCount() {
		return nodeCount;
	}
	@Override
	public int getDegree(int nodeId) {
		if (rowOffsets == null || nodeId < 0 || nodeId >= nodeCount)
			return 0;
		int start = rowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) nodeId);
		int end = rowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) (nodeId + 1));
		return Math.max(0, end - start);
	}
	@Override
	public int getInDegree(int nodeId) {
		if (cscRowOffsets == null || nodeId < 0 || nodeId >= nodeCount)
			return 0;
		int start = cscRowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) nodeId);
		int end = cscRowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) (nodeId + 1));
		return Math.max(0, end - start);
	}
	@Override
	public int[] getTargets(int nodeId) {
		return new int[0];
	}
	@Override
	public void copyTargetsSimd(int nodeId, ImpulseBitSet frontier) {
		if (rowOffsets == null || colTargets == null || nodeId < 0 || nodeId >= nodeCount)
			return;
		int start = rowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) nodeId);
		int end = rowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) (nodeId + 1));
		for (int i = start; i < end; i++) {
			int target = colTargets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) i);
			frontier.set(target);
		}
	}
	@Override
	public void copyInTargetsSimd(int nodeId, ImpulseBitSet frontier) {
		if (cscRowOffsets == null || cscColTargets == null || nodeId < 0 || nodeId >= nodeCount)
			return;
		int start = cscRowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) nodeId);
		int end = cscRowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) (nodeId + 1));
		for (int i = start; i < end; i++) {
			int target = cscColTargets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (long) i);
			frontier.set(target);
		}
	}
	@Override
	public void copyTargetsSimdFilteredFloat(int nodeId, MemorySegment attrSegment, float threshold, byte cmpOp,
			ImpulseBitSet outBs) {
	}
	@Override
	public java.util.List<MemorySegment> getAttributeSegments() {
		return java.util.List.of();
	}
	@Override
	public void setCscSegments(MemorySegment rowOffsets, MemorySegment colTargets) {
		this.cscRowOffsets = rowOffsets;
		this.cscColTargets = colTargets;
	}
	@Override
	public org.impulsegraph.api.stats.RelationStatistics getStatistics() {
		return null;
	}
	@Override
	public long readEdgeIndex(MemorySegment segment, long nodeId) {
		return Integer.toUnsignedLong(segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (int) nodeId));
	}
	@Override
	public int readNodeId(MemorySegment segment, long index) {
		return segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (int) index);
	}
	@Override
	public int readSrcNodeId(MemorySegment segment, long index) {
		return segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, (int) index);
	}
	@Override
	public byte getSrcNodeIdWidth() {
		return 4;
	}
	@Override
	public byte getNodeIdWidth() {
		return 4;
	}
	@Override
	public byte getEdgeIndexWidth() {
		return 4;
	}
}
