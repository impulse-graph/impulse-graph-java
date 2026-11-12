package org.impulsegraph.storage.csr;

import org.impulsegraph.api.bitset.ImpulseBitSet;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * High-performance off-heap CSR relation snapshot container holding edge
 * targets for a single relation. Backed by Java 25 Foreign Function and Memory
 * (FFM) {@link Arena} and off-heap {@link MemorySegment}s.
 */
public class RelationSnapshot implements org.impulsegraph.api.RelationSnapshot, AutoCloseable {

	private final Arena arena;
	private final int nodeCount;
	private final int edgeCount;
	private final MemorySegment rowOffsetsSegment;
	private final MemorySegment columnTargetsSegment;
	private final int[] rowOffsetsData;
	private final int[] columnIndicesData;
	private final byte srcNodeIdWidth;
	private final byte nodeIdWidth;
	private final byte edgeIndexWidth;

	public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, int[] rowOffsetsData, int[] columnIndicesData) {
		this.arena = Objects.requireNonNull(arena, "arena must not be null");
		this.nodeCount = nodeCount;
		this.edgeCount = edgeCount;
		this.rowOffsetsData = Objects.requireNonNull(rowOffsetsData, "rowOffsetsData must not be null");
		this.columnIndicesData = Objects.requireNonNull(columnIndicesData, "columnIndicesData must not be null");
		this.srcNodeIdWidth = 4;
		this.nodeIdWidth = 4;
		this.edgeIndexWidth = 4;

		// Allocate off-heap memory segments for row offsets and column targets
		this.rowOffsetsSegment = arena.allocate((long) rowOffsetsData.length * ValueLayout.JAVA_INT.byteSize());
		for (int i = 0; i < rowOffsetsData.length; i++) {
			rowOffsetsSegment.setAtIndex(ValueLayout.JAVA_INT, i, rowOffsetsData[i]);
		}

		this.columnTargetsSegment = arena.allocate((long) columnIndicesData.length * ValueLayout.JAVA_INT.byteSize());
		for (int j = 0; j < columnIndicesData.length; j++) {
			columnTargetsSegment.setAtIndex(ValueLayout.JAVA_INT, j, columnIndicesData[j]);
		}
	}

	private final java.util.List<MemorySegment> attributeSegments = new java.util.ArrayList<>();
	private final java.util.List<MemorySegment> validitySegments = new java.util.ArrayList<>();
	private final java.util.List<MemorySegment> offsetsSegments = new java.util.ArrayList<>();
	private final java.util.List<BinarySnapshotLoader.LoadedAttribute> loadedAttributes = new java.util.ArrayList<>();
	private final java.util.List<String> attributeNames = new java.util.ArrayList<>();
	private final java.util.List<Byte> attributeTypeCodes = new java.util.ArrayList<>();
	private int targetNodeCount = 0;
	private int relationId = -1;
	private int srcDomainId = 0;
	private int tgtDomainId = 0;
	private byte encodingId = 0;
	private long sectionFeatures = 0L;
	private int[] componentIds = new int[0];
	private RelationSnapshot[] componentRelations = new RelationSnapshot[0];
	private volatile int[] cscToCsrEdgeMap;
	private volatile int[] flatForwardEdgeMap;

	public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment,
			MemorySegment columnTargetsSegment, java.util.List<MemorySegment> attributeSegments) {
		this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, MemorySegment.NULL,
				MemorySegment.NULL, attributeSegments, java.util.Collections.emptyList(), (byte) 4, (byte) 4);
	}

	public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment,
			MemorySegment columnTargetsSegment) {
		this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, java.util.Collections.emptyList());
	}

	public void setAttributeNames(java.util.List<String> names) {
		this.attributeNames.clear();
		if (names != null)
			this.attributeNames.addAll(names);
	}

	public void setAttributeTypeCodes(java.util.List<Byte> typeCodes) {
		this.attributeTypeCodes.clear();
		if (typeCodes != null)
			this.attributeTypeCodes.addAll(typeCodes);
	}

	public String getAttributeName(int index) {
		return (index >= 0 && index < attributeNames.size()) ? attributeNames.get(index) : null;
	}

	public byte getAttributeTypeCode(int index) {
		return (index >= 0 && index < attributeTypeCodes.size()) ? attributeTypeCodes.get(index) : (byte) 0;
	}

	public void setTargetNodeCount(int targetNodeCount) {
		this.targetNodeCount = targetNodeCount;
	}

	public int getTargetNodeCount() {
		if (targetNodeCount > 0) {
			return targetNodeCount;
		}
		int cscCnt = getCscNodeCount();
		return cscCnt > 0 ? cscCnt : nodeCount;
	}

	public void setCatalogMetadata(int relationId, int srcDomainId, int tgtDomainId, byte encodingId,
			long sectionFeatures, int[] componentIds) {
		this.relationId = relationId;
		this.srcDomainId = srcDomainId;
		this.tgtDomainId = tgtDomainId;
		this.encodingId = encodingId;
		this.sectionFeatures = sectionFeatures;
		this.componentIds = componentIds != null ? componentIds.clone() : new int[0];
	}

	public void setLoadedAttributes(java.util.List<BinarySnapshotLoader.LoadedAttribute> attrs,
			java.util.List<MemorySegment> attrOffsets) {
		this.loadedAttributes.clear();
		if (attrs != null) {
			this.loadedAttributes.addAll(attrs);
		}
		this.offsetsSegments.clear();
		if (attrOffsets != null) {
			this.offsetsSegments.addAll(attrOffsets);
		}
	}

	public java.util.List<BinarySnapshotLoader.LoadedAttribute> getLoadedAttributes() {
		return java.util.Collections.unmodifiableList(loadedAttributes);
	}

	public java.util.List<MemorySegment> getOffsetsSegments() {
		return java.util.Collections.unmodifiableList(offsetsSegments);
	}

	public int[] getComponentIds() {
		return componentIds.clone();
	}

	public void setComponentRelations(RelationSnapshot[] componentRelations) {
		this.componentRelations = componentRelations != null ? componentRelations.clone() : new RelationSnapshot[0];
	}

	public RelationSnapshot[] getComponentRelations() {
		return componentRelations;
	}

	@Override
	public int getRelationId() {
		return relationId;
	}

	@Override
	public int getSrcDomainId() {
		return srcDomainId;
	}

	@Override
	public int getTgtDomainId() {
		return tgtDomainId;
	}

	public byte getEncodingId() {
		return encodingId;
	}

	public long getSectionFeatures() {
		return sectionFeatures;
	}

	@Override
	public boolean isVirtual() {
		return (sectionFeatures & 0x04L) != 0L && componentRelations.length > 0;
	}

	public boolean isFlatForward() {
		return !isVirtual() && rowOffsetsData == null
				&& (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)
						|| rowOffsetsSegment.byteSize() == 0)
				&& columnTargetsSegment != null && !columnTargetsSegment.equals(MemorySegment.NULL)
				&& columnTargetsSegment.byteSize() > 0;
	}

	public boolean isFlatReverse() {
		return !isVirtual()
				&& (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)
						|| cscRowOffsetsSegment.byteSize() == 0)
				&& cscColumnTargetsSegment != null && !cscColumnTargetsSegment.equals(MemorySegment.NULL)
				&& cscColumnTargetsSegment.byteSize() > 0;
	}

	public static boolean isSentinelNodeId(int rawId, byte widthBytes) {
		return (widthBytes == 2) ? (rawId == 0xFFFF) : (rawId < 0);
	}

	@Override
	public int getCscNodeCount() {
		if (isVirtual() && componentRelations.length > 0) {
			return targetNodeCount > 0 ? targetNodeCount : componentRelations[0].getCscNodeCount();
		}
		int cscFromOffsets = org.impulsegraph.api.RelationSnapshot.super.getCscNodeCount();
		if (cscFromOffsets > 0) {
			return cscFromOffsets;
		}
		if (isFlatReverse()) {
			int w = (srcNodeIdWidth == 2) ? 2 : (srcNodeIdWidth == 8 ? 8 : 4);
			return (int) (cscColumnTargetsSegment.byteSize() / w);
		}
		return targetNodeCount;
	}

	public int findAttributeIndex(String name) {
		if (name == null)
			return -1;
		for (int i = 0; i < attributeNames.size(); i++) {
			if (attributeNames.get(i).equalsIgnoreCase(name))
				return i;
		}
		String suffix = "." + name.toLowerCase();
		for (int i = 0; i < attributeNames.size(); i++) {
			if (attributeNames.get(i).toLowerCase().endsWith(suffix))
				return i;
		}
		return -1;
	}

	public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment,
			MemorySegment columnTargetsSegment, MemorySegment cscRowOffsetsSegment,
			MemorySegment cscColumnTargetsSegment, java.util.List<MemorySegment> attributeSegments,
			java.util.List<MemorySegment> validitySegments, byte nodeIdWidth, byte edgeIndexWidth) {
		this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, cscRowOffsetsSegment,
				cscColumnTargetsSegment, attributeSegments, validitySegments, nodeIdWidth, nodeIdWidth, edgeIndexWidth);
	}

	public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment,
			MemorySegment columnTargetsSegment, MemorySegment cscRowOffsetsSegment,
			MemorySegment cscColumnTargetsSegment, java.util.List<MemorySegment> attributeSegments,
			java.util.List<MemorySegment> validitySegments, byte srcNodeIdWidth, byte dstNodeIdWidth,
			byte edgeIndexWidth) {
		this.arena = Objects.requireNonNull(arena, "arena must not be null");
		this.nodeCount = nodeCount;
		this.edgeCount = edgeCount;
		this.rowOffsetsSegment = Objects.requireNonNull(rowOffsetsSegment, "rowOffsetsSegment must not be null");
		this.columnTargetsSegment = Objects.requireNonNull(columnTargetsSegment,
				"columnTargetsSegment must not be null");
		this.cscRowOffsetsSegment = cscRowOffsetsSegment != null ? cscRowOffsetsSegment : MemorySegment.NULL;
		this.cscColumnTargetsSegment = cscColumnTargetsSegment != null ? cscColumnTargetsSegment : MemorySegment.NULL;
		this.cscPresent = (!this.cscRowOffsetsSegment.equals(MemorySegment.NULL)
				&& (!this.cscColumnTargetsSegment.equals(MemorySegment.NULL) || edgeCount == 0))
				|| (this.cscRowOffsetsSegment.equals(MemorySegment.NULL)
						&& !this.cscColumnTargetsSegment.equals(MemorySegment.NULL)
						&& this.cscColumnTargetsSegment.byteSize() > 0);
		this.srcNodeIdWidth = srcNodeIdWidth;
		this.nodeIdWidth = dstNodeIdWidth;
		this.edgeIndexWidth = edgeIndexWidth;
		this.rowOffsetsData = null;
		this.columnIndicesData = null;
		if (attributeSegments != null) {
			this.attributeSegments.addAll(attributeSegments);
		}
		if (validitySegments != null) {
			this.validitySegments.addAll(validitySegments);
		}
	}

	public RelationSnapshot(Arena arena, int nodeCount, int edgeCount, MemorySegment rowOffsetsSegment,
			MemorySegment columnTargetsSegment, MemorySegment cscRowOffsetsSegment,
			MemorySegment cscColumnTargetsSegment) {
		this(arena, nodeCount, edgeCount, rowOffsetsSegment, columnTargetsSegment, cscRowOffsetsSegment,
				cscColumnTargetsSegment, java.util.Collections.emptyList(), java.util.Collections.emptyList(), (byte) 4,
				(byte) 4, (byte) 4);
	}

	public byte getSrcNodeIdWidth() {
		return srcNodeIdWidth;
	}

	public byte getNodeIdWidth() {
		return nodeIdWidth;
	}

	public byte getEdgeIndexWidth() {
		return edgeIndexWidth;
	}

	private boolean cscPresent = false;
	private MemorySegment cscRowOffsetsSegment = MemorySegment.NULL;
	private MemorySegment cscColumnTargetsSegment = MemorySegment.NULL;

	public void setCscSegments(MemorySegment rowOffsets, MemorySegment columnTargets) {
		this.cscRowOffsetsSegment = rowOffsets != null ? rowOffsets : MemorySegment.NULL;
		this.cscColumnTargetsSegment = columnTargets != null ? columnTargets : MemorySegment.NULL;
		this.cscPresent = (!this.cscRowOffsetsSegment.equals(MemorySegment.NULL)
				&& (!this.cscColumnTargetsSegment.equals(MemorySegment.NULL) || edgeCount == 0)) || isFlatReverse();
	}

	public boolean hasCsc() {
		if (isVirtual()) {
			for (RelationSnapshot child : componentRelations) {
				if (child != null && child.hasCsc()) {
					return true;
				}
			}
			return false;
		}
		if (isFlatReverse()) {
			return true;
		}
		return cscPresent && cscRowOffsetsSegment != null && !cscRowOffsetsSegment.equals(MemorySegment.NULL)
				&& cscRowOffsetsSegment.byteSize() > 0
				&& ((cscColumnTargetsSegment != null && !cscColumnTargetsSegment.equals(MemorySegment.NULL))
						|| edgeCount == 0);
	}

	public MemorySegment getCscRowOffsetsSegment() {
		return cscRowOffsetsSegment;
	}

	public MemorySegment getCscColumnTargetsSegment() {
		return cscColumnTargetsSegment;
	}

	public boolean hasCsr() {
		if (isVirtual()) {
			for (RelationSnapshot child : componentRelations) {
				if (child != null && child.hasCsr()) {
					return true;
				}
			}
			return false;
		}
		return (rowOffsetsData != null && rowOffsetsData.length > 0) || (rowOffsetsSegment != null
				&& !rowOffsetsSegment.equals(MemorySegment.NULL) && rowOffsetsSegment.byteSize() > 0)
				|| isFlatForward();
	}

	private volatile org.impulsegraph.api.stats.RelationStatistics cachedStats;

	private volatile String metadataJson;

	public void injectMetadata(String metadataJson) {
		this.metadataJson = metadataJson;
	}

	private int extractInt(String json, String key) {
		int idx = json.indexOf(key);
		if (idx == -1)
			return 0;
		idx += key.length();
		int end = idx;
		while (end < json.length() && Character.isDigit(json.charAt(end))) {
			end++;
		}
		if (end == idx)
			return 0;
		return Integer.parseInt(json.substring(idx, end));
	}

	private org.impulsegraph.api.stats.RelationStatistics parseFromJson(String json) {
		int maxDegree = extractInt(json, "\"max\":");
		int p50 = extractInt(json, "\"p50\":");
		int p90 = extractInt(json, "\"p90\":");
		int p99 = extractInt(json, "\"p99\":");
		int zeroCount = extractInt(json, "\"zero_count\":");

		int uniqueSources = Math.max(0, nodeCount - zeroCount);
		double avgOut = nodeCount > 0 ? (double) edgeCount / nodeCount : 0.0;
		double sparsity = nodeCount > 0 ? (double) uniqueSources / nodeCount : 0.0;

		org.impulsegraph.api.stats.RelationStatistics.Multiplicity multiplicity = org.impulsegraph.api.stats.RelationStatistics.Multiplicity.MANY_TO_MANY;

		if (maxDegree <= 1) {
			multiplicity = org.impulsegraph.api.stats.RelationStatistics.Multiplicity.MANY_TO_ONE;
		}

		return new org.impulsegraph.api.stats.RelationStatistics(nodeCount, edgeCount, uniqueSources, maxDegree, avgOut,
				0.0, p50, p90, p99, sparsity, new org.impulsegraph.api.bitset.OffHeapBitSet(arena, 0), multiplicity, 0,
				avgOut, false, false, false);
	}

	public org.impulsegraph.api.stats.RelationStatistics getStatistics() {
		if (cachedStats == null) {
			synchronized (this) {
				if (cachedStats == null) {
					if (metadataJson != null && !metadataJson.isEmpty()) {
						cachedStats = parseFromJson(metadataJson);
					} else {
						cachedStats = org.impulsegraph.storage.stats.RelationStatisticsCalculator.calculate(this);
					}
				}
			}
		}
		return cachedStats;
	}

	public java.util.List<MemorySegment> getAttributeSegments() {
		return attributeSegments;
	}

	public java.util.List<MemorySegment> getValiditySegments() {
		return validitySegments;
	}

	public int getNodeCount() {
		return nodeCount;
	}

	public long getEdgeCount() {
		if (isVirtual() && edgeCount == 0) {
			long total = 0L;
			for (RelationSnapshot child : componentRelations) {
				if (child != null) {
					total += child.getEdgeCount();
				}
			}
			return total;
		}
		return edgeCount;
	}

	public long readEdgeIndex(MemorySegment segment, long nodeId) {
		if (edgeIndexWidth == 8) {
			return segment.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, nodeId);
		} else {
			return Integer.toUnsignedLong(segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeId));
		}
	}

	public int readNodeId(MemorySegment segment, long index) {
		return readNodeId(segment, index, nodeIdWidth);
	}

	public int readSrcNodeId(MemorySegment segment, long index) {
		return readNodeId(segment, index, srcNodeIdWidth);
	}

	public static int readNodeId(MemorySegment segment, long index, byte width) {
		if (width == 2) {
			return Short.toUnsignedInt(segment.getAtIndex(ValueLayout.JAVA_SHORT_UNALIGNED, index));
		} else if (width == 8) {
			return (int) segment.getAtIndex(ValueLayout.JAVA_LONG_UNALIGNED, index);
		} else {
			return segment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, index);
		}
	}

	public int[] getRowOffsets() {
		if (rowOffsetsData != null)
			return rowOffsetsData;
		if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL))
			return new int[0];
		int numRowOffsets = (int) (rowOffsetsSegment.byteSize() / (edgeIndexWidth == 8 ? 8 : 4));
		int[] arr = new int[numRowOffsets];
		if (edgeIndexWidth == 4) {
			MemorySegment.copy(rowOffsetsSegment, ValueLayout.JAVA_INT_UNALIGNED, 0, arr, 0, numRowOffsets);
		} else {
			for (int i = 0; i < numRowOffsets; i++)
				arr[i] = (int) readEdgeIndex(rowOffsetsSegment, i);
		}
		return arr;
	}

	public int[] getColumnIndices() {
		if (columnIndicesData != null)
			return columnIndicesData;
		if (columnTargetsSegment == null || columnTargetsSegment.equals(MemorySegment.NULL))
			return new int[0];
		int numCols = (int) (columnTargetsSegment.byteSize() / (nodeIdWidth == 2 ? 2 : (nodeIdWidth == 8 ? 8 : 4)));
		int[] arr = new int[numCols];
		if (nodeIdWidth == 4) {
			MemorySegment.copy(columnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, 0, arr, 0, numCols);
		} else {
			for (int i = 0; i < numCols; i++)
				arr[i] = readNodeId(columnTargetsSegment, i);
		}
		return arr;
	}

	public MemorySegment getRowOffsetsSegment() {
		return rowOffsetsSegment;
	}

	public MemorySegment getColumnTargetsSegment() {
		return columnTargetsSegment;
	}

	/**
	 * Gets the out-degree for a specific node ID zero-copy directly from off-heap
	 * memory.
	 */
	public int getDegree(int nodeId) {
		if (nodeId < 0 || nodeId >= nodeCount)
			return 0;
		if (isVirtual()) {
			int total = 0;
			for (RelationSnapshot child : componentRelations) {
				if (child != null) {
					total += child.getDegree(nodeId);
				}
			}
			return total;
		}
		if (isFlatForward()) {
			int w = (nodeIdWidth == 2) ? 2 : (nodeIdWidth == 8 ? 8 : 4);
			if ((long) (nodeId + 1) * w > columnTargetsSegment.byteSize()) {
				return 0;
			}
			int t = readNodeId(columnTargetsSegment, nodeId);
			return isSentinelNodeId(t, nodeIdWidth) ? 0 : 1;
		}
		if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)
				|| rowOffsetsSegment.byteSize() == 0)
			return 0;
		long start = readEdgeIndex(rowOffsetsSegment, nodeId);
		long end = readEdgeIndex(rowOffsetsSegment, nodeId + 1);
		return (int) (end - start);
	}

	/**
	 * Returns the array slice of target node IDs for a specific source node ID
	 * zero-copy from off-heap memory.
	 */
	public int[] getTargets(int nodeId) {
		if (nodeId < 0 || nodeId >= nodeCount)
			return new int[0];
		if (isVirtual()) {
			int total = getDegree(nodeId);
			if (total <= 0) {
				return new int[0];
			}
			int[] out = new int[total];
			int pos = 0;
			for (RelationSnapshot child : componentRelations) {
				if (child != null) {
					int[] sub = child.getTargets(nodeId);
					if (sub.length > 0) {
						System.arraycopy(sub, 0, out, pos, sub.length);
						pos += sub.length;
					}
				}
			}
			return pos == out.length ? out : java.util.Arrays.copyOf(out, pos);
		}
		if (isFlatForward()) {
			int w = (nodeIdWidth == 2) ? 2 : (nodeIdWidth == 8 ? 8 : 4);
			if ((long) (nodeId + 1) * w > columnTargetsSegment.byteSize()) {
				return new int[0];
			}
			int t = readNodeId(columnTargetsSegment, nodeId);
			return isSentinelNodeId(t, nodeIdWidth) ? new int[0] : new int[]{t};
		}
		if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)
				|| rowOffsetsSegment.byteSize() == 0)
			return new int[0];
		long start = readEdgeIndex(rowOffsetsSegment, nodeId);
		long end = readEdgeIndex(rowOffsetsSegment, nodeId + 1);
		int len = (int) (end - start);
		if (len <= 0)
			return new int[0];
		int[] targets = new int[len];
		if (nodeIdWidth == 4) {
			MemorySegment.copy(columnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, start * 4, targets, 0, len);
		} else {
			for (int i = 0; i < len; i++) {
				targets[i] = readNodeId(columnTargetsSegment, start + i);
			}
		}
		return targets;
	}

	/**
	 * Gets the in-degree for a specific destination node ID zero-copy directly from
	 * off-heap memory.
	 */
	public int getInDegree(int nodeId) {
		int cscBound = getCscNodeCount() > 0 ? getCscNodeCount() : nodeCount;
		if (nodeId < 0 || nodeId >= cscBound)
			return 0;
		if (isVirtual()) {
			int total = 0;
			for (RelationSnapshot child : componentRelations) {
				if (child != null) {
					total += child.getInDegree(nodeId);
				}
			}
			return total;
		}
		if (isFlatReverse()) {
			int w = (srcNodeIdWidth == 2) ? 2 : (srcNodeIdWidth == 8 ? 8 : 4);
			if ((long) (nodeId + 1) * w > cscColumnTargetsSegment.byteSize()) {
				return 0;
			}
			int s = readSrcNodeId(cscColumnTargetsSegment, nodeId);
			return isSentinelNodeId(s, srcNodeIdWidth) ? 0 : 1;
		}
		if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)
				|| cscRowOffsetsSegment.byteSize() == 0)
			return 0;
		long start = readEdgeIndex(cscRowOffsetsSegment, nodeId);
		long end = readEdgeIndex(cscRowOffsetsSegment, nodeId + 1);
		return (int) (end - start);
	}

	/**
	 * Returns the array slice of incoming source node IDs for a specific
	 * destination node ID zero-copy from off-heap memory.
	 */
	@Override
	public int[] getInTargets(int nodeId) {
		int cscBound = getCscNodeCount() > 0 ? getCscNodeCount() : nodeCount;
		if (nodeId < 0 || nodeId >= cscBound)
			return new int[0];
		if (isVirtual()) {
			int total = getInDegree(nodeId);
			if (total <= 0) {
				return new int[0];
			}
			int[] out = new int[total];
			int pos = 0;
			for (RelationSnapshot child : componentRelations) {
				if (child != null) {
					int[] sub = child.getInTargets(nodeId);
					if (sub.length > 0) {
						System.arraycopy(sub, 0, out, pos, sub.length);
						pos += sub.length;
					}
				}
			}
			return pos == out.length ? out : java.util.Arrays.copyOf(out, pos);
		}
		if (isFlatReverse()) {
			int w = (srcNodeIdWidth == 2) ? 2 : (srcNodeIdWidth == 8 ? 8 : 4);
			if ((long) (nodeId + 1) * w > cscColumnTargetsSegment.byteSize()) {
				return new int[0];
			}
			int s = readSrcNodeId(cscColumnTargetsSegment, nodeId);
			return isSentinelNodeId(s, srcNodeIdWidth) ? new int[0] : new int[]{s};
		}
		if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)
				|| cscRowOffsetsSegment.byteSize() == 0)
			return new int[0];
		long start = readEdgeIndex(cscRowOffsetsSegment, nodeId);
		long end = readEdgeIndex(cscRowOffsetsSegment, nodeId + 1);
		int len = (int) (end - start);
		if (len <= 0)
			return new int[0];
		int[] targets = new int[len];
		if (srcNodeIdWidth == 4) {
			MemorySegment.copy(cscColumnTargetsSegment, ValueLayout.JAVA_INT_UNALIGNED, start * 4, targets, 0, len);
		} else {
			for (int i = 0; i < len; i++) {
				targets[i] = readSrcNodeId(cscColumnTargetsSegment, start + i);
			}
		}
		return targets;
	}

	private static final jdk.incubator.vector.VectorSpecies<Integer> INT_SPECIES = jdk.incubator.vector.IntVector.SPECIES_PREFERRED;
	private static final jdk.incubator.vector.VectorSpecies<Float> FLOAT_SPECIES = jdk.incubator.vector.FloatVector.SPECIES_PREFERRED;

	/**
	 * SIMD vectorized target node traversal into a destination ImpulseBitSet. Uses
	 * Java 25 Vector API (AVX-512 / AVX2 / ARM Neon) to load vector tiles of target
	 * IDs directly off-heap.
	 */
	public void copyTargetsSimd(int nodeId, ImpulseBitSet outBs) {
		if (nodeId < 0 || nodeId >= nodeCount || outBs == null)
			return;
		if (isVirtual()) {
			for (RelationSnapshot child : componentRelations) {
				if (child != null) {
					child.copyTargetsSimd(nodeId, outBs);
				}
			}
			return;
		}
		if (isFlatForward()) {
			int w = (nodeIdWidth == 2) ? 2 : (nodeIdWidth == 8 ? 8 : 4);
			if ((long) (nodeId + 1) * w <= columnTargetsSegment.byteSize()) {
				int t = readNodeId(columnTargetsSegment, nodeId);
				if (!isSentinelNodeId(t, nodeIdWidth)) {
					outBs.set(t);
				}
			}
			return;
		}
		if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL)
				|| rowOffsetsSegment.byteSize() == 0)
			return;

		long start = readEdgeIndex(rowOffsetsSegment, nodeId);
		long end = readEdgeIndex(rowOffsetsSegment, nodeId + 1);
		int count = (int) (end - start);
		if (count <= 0)
			return;

		for (int i = 0; i < count; i++) {
			outBs.set(readNodeId(columnTargetsSegment, start + i));
		}
	}

	/**
	 * SIMD Vector API fused edge attribute predicate filter during CSR traversal.
	 * When count >= OptimizerConfig.SIMD_PREDICATE_EVAL_MIN_DEGREE_THRESHOLD (64),
	 * loads 512-bit vector tiles of edge attribute values, compares using SIMD
	 * masks, and streams matching column targets directly into outBs.
	 */
	public void copyTargetsSimdFilteredFloat(int nodeId, MemorySegment attrSegment, float threshold, byte cmpOp,
			ImpulseBitSet outBs) {
		if (nodeId < 0 || nodeId >= nodeCount || outBs == null)
			return;
		if (rowOffsetsSegment == null || rowOffsetsSegment.equals(MemorySegment.NULL))
			return;
		if (attrSegment == null || attrSegment.equals(MemorySegment.NULL)) {
			copyTargetsSimd(nodeId, outBs);
			return;
		}

		long start = readEdgeIndex(rowOffsetsSegment, nodeId);
		long end = readEdgeIndex(rowOffsetsSegment, nodeId + 1);
		int count = (int) (end - start);
		if (count <= 0)
			return;

		int i = 0;
		if (nodeIdWidth == 4
				&& count >= org.impulsegraph.api.config.OptimizerConfig.SIMD_PREDICATE_EVAL_MIN_DEGREE_THRESHOLD) {
			int upperBound = FLOAT_SPECIES.loopBound(count);
			jdk.incubator.vector.VectorOperators.Comparison op = switch (cmpOp) {
				case CMP_GT -> jdk.incubator.vector.VectorOperators.GT;
				case CMP_GTE -> jdk.incubator.vector.VectorOperators.GE;
				case CMP_LT -> jdk.incubator.vector.VectorOperators.LT;
				case CMP_LTE -> jdk.incubator.vector.VectorOperators.LE;
				case CMP_EQ -> jdk.incubator.vector.VectorOperators.EQ;
				case CMP_NEQ -> jdk.incubator.vector.VectorOperators.NE;
				default -> jdk.incubator.vector.VectorOperators.GE;
			};

			for (; i < upperBound; i += FLOAT_SPECIES.length()) {
				var vecAttr = jdk.incubator.vector.FloatVector.fromMemorySegment(FLOAT_SPECIES, attrSegment,
						(start + i) * ValueLayout.JAVA_FLOAT.byteSize(), java.nio.ByteOrder.LITTLE_ENDIAN);
				var mask = vecAttr.compare(op, threshold);
				for (int lane = 0; lane < FLOAT_SPECIES.length(); lane++) {
					if (mask.laneIsSet(lane)) {
						outBs.set(columnTargetsSegment.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, start + i + lane));
					}
				}
			}
		}

		for (; i < count; i++) {
			float val = attrSegment.getAtIndex(ValueLayout.JAVA_FLOAT_UNALIGNED, start + i);
			boolean match = switch (cmpOp) {
				case CMP_GT -> val > threshold;
				case CMP_GTE -> val >= threshold;
				case CMP_LT -> val < threshold;
				case CMP_LTE -> val <= threshold;
				case CMP_EQ -> val == threshold;
				case CMP_NEQ -> val != threshold;
				default -> val >= threshold;
			};
			if (match) {
				outBs.set(readNodeId(columnTargetsSegment, start + i));
			}
		}
	}

	/**
	 * SIMD vectorized incoming target node traversal into a destination
	 * ImpulseBitSet zero-allocation.
	 */
	public void copyInTargetsSimd(int nodeId, ImpulseBitSet outBs) {
		int cscBound = getCscNodeCount() > 0 ? getCscNodeCount() : nodeCount;
		if (nodeId < 0 || nodeId >= cscBound || outBs == null)
			return;
		if (isVirtual()) {
			for (RelationSnapshot child : componentRelations) {
				if (child != null) {
					child.copyInTargetsSimd(nodeId, outBs);
				}
			}
			return;
		}
		if (isFlatReverse()) {
			int w = (srcNodeIdWidth == 2) ? 2 : (srcNodeIdWidth == 8 ? 8 : 4);
			if ((long) (nodeId + 1) * w <= cscColumnTargetsSegment.byteSize()) {
				int s = readSrcNodeId(cscColumnTargetsSegment, nodeId);
				if (!isSentinelNodeId(s, srcNodeIdWidth)) {
					outBs.set(s);
				}
			}
			return;
		}
		if (cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)
				|| cscRowOffsetsSegment.byteSize() == 0)
			return;

		long start = readEdgeIndex(cscRowOffsetsSegment, nodeId);
		long end = readEdgeIndex(cscRowOffsetsSegment, nodeId + 1);
		int count = (int) (end - start);
		if (count <= 0)
			return;

		for (int i = 0; i < count; i++) {
			outBs.set(readSrcNodeId(cscColumnTargetsSegment, start + i));
		}
	}

	/**
	 * Lazily builds an exact bijection from CSC slot {@code k}
	 * ({@code 0..edgeCount-1}) to CSR edge index {@code e}
	 * ({@code 0..edgeCount-1}), preserving parallel multi-edge attribute alignment.
	 */
	public int[] getOrBuildCscToCsrEdgeMap() {
		int[] cached = cscToCsrEdgeMap;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			cached = cscToCsrEdgeMap;
			if (cached != null) {
				return cached;
			}
			if (edgeCount <= 0 || cscRowOffsetsSegment == null || cscRowOffsetsSegment.equals(MemorySegment.NULL)
					|| cscRowOffsetsSegment.byteSize() == 0) {
				cached = new int[0];
				cscToCsrEdgeMap = cached;
				return cached;
			}
			int cscN = getCscNodeCount();
			int[] map = new int[edgeCount];
			java.util.Arrays.fill(map, -1);
			int[] cursors = new int[cscN];
			for (int v = 0; v < cscN; v++) {
				cursors[v] = (int) readEdgeIndex(cscRowOffsetsSegment, v);
			}
			if (isFlatForward()) {
				int[] fwdMap = getOrBuildFlatForwardEdgeMap();
				for (int u = 0; u < nodeCount; u++) {
					int e = (u < fwdMap.length) ? fwdMap[u] : -1;
					if (e < 0) {
						continue;
					}
					int v = readNodeId(columnTargetsSegment, u);
					if (!isSentinelNodeId(v, nodeIdWidth) && v >= 0 && v < cscN) {
						int cscEnd = (int) readEdgeIndex(cscRowOffsetsSegment, v + 1);
						for (int k = cursors[v]; k < cscEnd; k++) {
							if (map[k] == -1 && readSrcNodeId(cscColumnTargetsSegment, k) == u) {
								map[k] = e;
								while (cursors[v] < cscEnd && map[cursors[v]] != -1) {
									cursors[v]++;
								}
								break;
							}
						}
					}
				}
			} else if (rowOffsetsSegment != null && !rowOffsetsSegment.equals(MemorySegment.NULL)
					&& rowOffsetsSegment.byteSize() > 0) {
				for (int u = 0; u < nodeCount; u++) {
					int start = (int) readEdgeIndex(rowOffsetsSegment, u);
					int end = (int) readEdgeIndex(rowOffsetsSegment, u + 1);
					for (int e = start; e < end; e++) {
						int v = readNodeId(columnTargetsSegment, e);
						if (v >= 0 && v < cscN) {
							int cscEnd = (int) readEdgeIndex(cscRowOffsetsSegment, v + 1);
							for (int k = cursors[v]; k < cscEnd; k++) {
								if (map[k] == -1 && readSrcNodeId(cscColumnTargetsSegment, k) == u) {
									map[k] = e;
									while (cursors[v] < cscEnd && map[cursors[v]] != -1) {
										cursors[v]++;
									}
									break;
								}
							}
						}
					}
				}
			}
			cscToCsrEdgeMap = map;
			return map;
		}
	}

	public int[] getOrBuildFlatForwardEdgeMap() {
		int[] cached = flatForwardEdgeMap;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			cached = flatForwardEdgeMap;
			if (cached != null) {
				return cached;
			}
			if (!isFlatForward() || nodeCount <= 0) {
				cached = new int[0];
				flatForwardEdgeMap = cached;
				return cached;
			}
			int[] map = new int[nodeCount];
			int rank = 0;
			int w = (nodeIdWidth == 2) ? 2 : (nodeIdWidth == 8 ? 8 : 4);
			for (int u = 0; u < nodeCount; u++) {
				if ((long) (u + 1) * w <= columnTargetsSegment.byteSize()) {
					int t = readNodeId(columnTargetsSegment, u);
					if (!isSentinelNodeId(t, nodeIdWidth)) {
						map[u] = (edgeCount == nodeCount) ? u : rank++;
						continue;
					}
				}
				map[u] = -1;
			}
			flatForwardEdgeMap = map;
			return map;
		}
	}

	public int findCsrEdgeIndex(int srcU, int dstV) {
		if (srcU < 0 || srcU >= nodeCount) {
			return -1;
		}
		if (isFlatForward()) {
			int[] fwdMap = getOrBuildFlatForwardEdgeMap();
			return (srcU < fwdMap.length) ? fwdMap[srcU] : -1;
		}
		if (rowOffsetsSegment != null && !rowOffsetsSegment.equals(MemorySegment.NULL)
				&& rowOffsetsSegment.byteSize() > 0) {
			int start = (int) readEdgeIndex(rowOffsetsSegment, srcU);
			int end = (int) readEdgeIndex(rowOffsetsSegment, srcU + 1);
			for (int e = start; e < end; e++) {
				if (readNodeId(columnTargetsSegment, e) == dstV) {
					return e;
				}
			}
		}
		return -1;
	}

	public long getMemoryFootprintBytes() {
		return rowOffsetsSegment.byteSize() + columnTargetsSegment.byteSize();
	}

	@Override
	public void close() {
		if (arena.scope().isAlive()) {
			arena.close();
		}
	}
}
