package org.impulsegraph.api;

import java.lang.foreign.MemorySegment;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import java.util.Map;

/**
 * Read-only interface for a relation's physical memory layout.
 */
public interface RelationSnapshot {

	byte CMP_GT = 0x01;
	byte CMP_GTE = 0x02;
	byte CMP_LT = 0x03;
	byte CMP_LTE = 0x04;
	byte CMP_EQ = 0x05;
	byte CMP_NEQ = 0x06;

	long getEdgeCount();
	MemorySegment getRowOffsetsSegment();
	MemorySegment getColumnTargetsSegment();
	MemorySegment getCscColumnTargetsSegment();
	MemorySegment getCscRowOffsetsSegment();
	boolean hasCsc();
	boolean hasCsr();
	int getNodeCount();
	default int getCscNodeCount() {
		MemorySegment cscOff = getCscRowOffsetsSegment();
		if (cscOff != null && !cscOff.equals(MemorySegment.NULL) && cscOff.byteSize() >= 4) {
			int width = getEdgeIndexWidth() > 0 ? getEdgeIndexWidth() : 4;
			return (int) (cscOff.byteSize() / width - 1);
		}
		return 0;
	}
	int getDegree(int nodeId);
	int getInDegree(int nodeId);
	int[] getTargets(int nodeId);
	default int getTargetAt(int nodeId, int index) {
		int[] targets = getTargets(nodeId);
		return (targets != null && index >= 0 && index < targets.length) ? targets[index] : -1;
	}
	default int[] getInTargets(int nodeId) {
		return new int[0];
	}
	default int getRelationId() {
		return -1;
	}
	default int getSrcDomainId() {
		return 0;
	}
	default int getTgtDomainId() {
		return 0;
	}
	default boolean isVirtual() {
		return false;
	}
	void copyTargetsSimd(int nodeId, ImpulseBitSet frontier);
	void copyInTargetsSimd(int nodeId, ImpulseBitSet frontier);
	void copyTargetsSimdFilteredFloat(int nodeId, MemorySegment attrSegment, float threshold, byte cmpOp,
			ImpulseBitSet outBs);
	java.util.List<MemorySegment> getAttributeSegments();
	void setCscSegments(MemorySegment rowOffsets, MemorySegment colTargets);
	org.impulsegraph.api.stats.RelationStatistics getStatistics();
	long readEdgeIndex(MemorySegment segment, long nodeId);
	int readNodeId(MemorySegment segment, long index);
	int readSrcNodeId(MemorySegment segment, long index);
	byte getSrcNodeIdWidth();
	byte getNodeIdWidth();
	byte getEdgeIndexWidth();
}
