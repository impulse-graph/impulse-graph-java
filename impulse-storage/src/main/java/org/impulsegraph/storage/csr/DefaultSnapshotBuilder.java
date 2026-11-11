package org.impulsegraph.storage.csr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.impulsegraph.spec.v0_9.ImpulseLayoutsV0_9.SPEC_MAGIC;
import static org.impulsegraph.spec.v0_10.ImpulseLayoutsV0_10.SPEC_VERSION_PACKED;

public final class DefaultSnapshotBuilder {

	private final Map<String, String> metadata = new HashMap<>();
	private Boolean includeCscOverride = null;
	private Boolean includeCooOverride = null;
	private short targetVersion = SPEC_VERSION_PACKED;

	public DefaultSnapshotBuilder() {
	}

	public static byte[] writeSnapshotBytes(GraphSnapshot graph) {
		return new DefaultSnapshotBuilder().build(new BinarySnapshotLoader.DefaultLoadedSnapshot(SPEC_MAGIC,
				(short) SPEC_VERSION_PACKED, graph, Map.of(), Map.of(), Map.of(), Map.of()));
	}

	public static class DomainAttributeEntry {
		private final String name;
		private final byte typeCode;
		private final int dimension;
		private final byte[] data;
		private final byte[] offsets;
		private final byte[] validity;

		public DomainAttributeEntry(String name, byte typeCode, int dimension, byte[] data, byte[] offsets,
				byte[] validity) {
			this.name = Objects.requireNonNull(name, "attribute name cannot be null");
			this.typeCode = typeCode;
			this.dimension = dimension;
			this.data = data != null ? data : new byte[0];
			this.offsets = offsets != null ? offsets : new byte[0];
			this.validity = validity != null ? validity : new byte[0];
		}

		public String name() {
			return name;
		}
		public byte typeCode() {
			return typeCode;
		}
		public int dimension() {
			return dimension;
		}
		public byte[] data() {
			return data;
		}
		public byte[] offsets() {
			return offsets;
		}
		public byte[] validity() {
			return validity;
		}
	}

	public static class DomainEntry {
		private final int domainId;
		private final String name;
		private final byte keyType;
		private final long nodeCount;
		private final int keyAttrIndex;
		private final List<DomainAttributeEntry> attributes;

		public DomainEntry(int domainId, String name, byte keyType, long nodeCount) {
			this(domainId, name, keyType, nodeCount, 0xFFFF, new ArrayList<>());
		}

		public DomainEntry(int domainId, String name, byte keyType, long nodeCount, int keyAttrIndex,
				List<DomainAttributeEntry> attributes) {
			this.domainId = domainId;
			this.name = name;
			this.keyType = keyType;
			this.nodeCount = nodeCount;
			this.keyAttrIndex = keyAttrIndex;
			this.attributes = attributes != null ? new ArrayList<>(attributes) : new ArrayList<>();
		}

		public int domainId() {
			return domainId;
		}
		public String name() {
			return name;
		}
		public byte keyType() {
			return keyType;
		}
		public long nodeCount() {
			return nodeCount;
		}
		public int keyAttrIndex() {
			return keyAttrIndex;
		}
		public List<DomainAttributeEntry> attributes() {
			return attributes;
		}
	}

	private final List<DomainEntry> customDomains = new ArrayList<>();
	private final Map<String, int[]> customRelationDomains = new HashMap<>();

	public DefaultSnapshotBuilder withVersion(short version) {
		this.targetVersion = version;
		return this;
	}

	public DefaultSnapshotBuilder withDomain(int domainId, String name, byte keyType, long nodeCount) {
		return withDomain(domainId, name, keyType, nodeCount, 0xFFFF, new ArrayList<>());
	}

	public DefaultSnapshotBuilder withDomain(int domainId, String name, byte keyType, long nodeCount,
			int keyAttrIndex) {
		return withDomain(domainId, name, keyType, nodeCount, keyAttrIndex, new ArrayList<>());
	}

	public DefaultSnapshotBuilder withDomain(int domainId, String name, byte keyType, long nodeCount, int keyAttrIndex,
			List<DomainAttributeEntry> attributes) {
		customDomains.add(new DomainEntry(domainId, name, keyType, nodeCount, keyAttrIndex, attributes));
		withMetadata("domain." + name + ".nodeCount", String.valueOf(nodeCount));
		return this;
	}

	public DefaultSnapshotBuilder withDomainAttribute(int domainId, String name, byte typeCode, byte[] data) {
		return withDomainAttribute(domainId, name, typeCode, 1, data, null, null);
	}

	public DefaultSnapshotBuilder withDomainAttribute(String domainName, String name, byte typeCode, byte[] data) {
		DomainEntry dom = findDomain(domainName);
		if (dom == null) {
			throw new IllegalArgumentException("Domain name not found: " + domainName);
		}
		return withDomainAttribute(dom.domainId(), name, typeCode, 1, data, null, null);
	}

	public DefaultSnapshotBuilder withDomainAttribute(int domainId, String name, byte typeCode, int dimension,
			byte[] data, byte[] offsets, byte[] validity) {
		DomainEntry dom = findDomain(domainId);
		if (dom == null) {
			throw new IllegalArgumentException("Domain ID not found: " + domainId);
		}
		dom.attributes().add(new DomainAttributeEntry(name, typeCode, dimension, data, offsets, validity));
		return this;
	}

	public DefaultSnapshotBuilder withDomainStringAttribute(int domainId, String name, List<String> values) {
		int n = (values != null) ? values.size() : 0;
		ByteBuffer offsetsBuf = ByteBuffer.allocate((n + 1) * 4).order(ByteOrder.LITTLE_ENDIAN);
		ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
		if (values != null) {
			for (String v : values) {
				offsetsBuf.putInt(dataOut.size());
				if (v != null) {
					byte[] b = v.getBytes(StandardCharsets.UTF_8);
					dataOut.write(b, 0, b.length);
				}
			}
		}
		offsetsBuf.putInt(dataOut.size());
		return withDomainAttribute(domainId, name, (byte) 0x0B, 0, dataOut.toByteArray(), offsetsBuf.array(), null);
	}

	public DefaultSnapshotBuilder withDomainKeyAttribute(int domainId, String name, byte keyType, List<String> keys) {
		DomainEntry dom = findDomain(domainId);
		if (dom == null) {
			throw new IllegalArgumentException("Domain ID not found: " + domainId);
		}
		int keyAttrIdx = dom.attributes().size();
		if (keyType == 0x0B) { // VAR_STRING
			withDomainStringAttribute(domainId, name, keys);
		} else if (keyType == 0x04) { // INT64
			ByteBuffer buf = ByteBuffer.allocate(keys.size() * 8).order(ByteOrder.LITTLE_ENDIAN);
			for (String k : keys) {
				buf.putLong(Long.parseLong(k));
			}
			withDomainAttribute(domainId, name, (byte) 0x04, 1, buf.array(), null, null);
		} else if (keyType == 0x03) { // INT32
			ByteBuffer buf = ByteBuffer.allocate(keys.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (String k : keys) {
				buf.putInt(Integer.parseInt(k));
			}
			withDomainAttribute(domainId, name, (byte) 0x03, 1, buf.array(), null, null);
		} else if (keyType == 0x0C) { // UUID128
			ByteBuffer buf = ByteBuffer.allocate(keys.size() * 16).order(ByteOrder.LITTLE_ENDIAN);
			for (String k : keys) {
				UUID u = UUID.fromString(k);
				buf.putLong(u.getMostSignificantBits());
				buf.putLong(u.getLeastSignificantBits());
			}
			withDomainAttribute(domainId, name, (byte) 0x0C, 16, buf.array(), null, null);
		}
		customDomains.set(customDomains.indexOf(dom),
				new DomainEntry(dom.domainId(), dom.name(), keyType, dom.nodeCount(), keyAttrIdx, dom.attributes()));
		withDomainKeys(dom.name(), keys);
		return this;
	}

	private DomainEntry findDomain(int domainId) {
		for (DomainEntry dom : customDomains) {
			if (dom.domainId() == domainId)
				return dom;
		}
		return null;
	}

	private DomainEntry findDomain(String name) {
		for (DomainEntry dom : customDomains) {
			if (dom.name().equalsIgnoreCase(name))
				return dom;
		}
		return null;
	}

	public DefaultSnapshotBuilder withRelationDomain(String relName, int srcDomainId, int tgtDomainId) {
		customRelationDomains.put(relName, new int[]{srcDomainId, tgtDomainId});
		return this;
	}

	public DefaultSnapshotBuilder withDomainKey(String domainName, String key, long denseId) {
		withMetadata("domain." + domainName + ".key." + key, String.valueOf(denseId));
		withMetadata("domain." + domainName + ".id." + denseId, key);
		return this;
	}

	public DefaultSnapshotBuilder withDomainKeys(String domainName, List<String> keys) {
		if (keys != null) {
			for (int i = 0; i < keys.size(); i++) {
				String key = keys.get(i);
				withDomainKey(domainName, key, i);
			}
		}
		return this;
	}

	public DefaultSnapshotBuilder withMetadata(String key, String value) {
		metadata.put(key, value);
		return this;
	}

	public DefaultSnapshotBuilder withCsc(boolean include) {
		this.includeCscOverride = include;
		return this;
	}

	public DefaultSnapshotBuilder withCoo(boolean include) {
		this.includeCooOverride = include;
		return this;
	}

	public static MemorySegment[] computeCscSegments(Arena arena, int nodeCount, int edgeCount,
			MemorySegment csrRowOffsets, MemorySegment csrColumnTargets) {
		if (nodeCount <= 0 || csrRowOffsets == null || csrRowOffsets.equals(MemorySegment.NULL)
				|| csrColumnTargets == null || csrColumnTargets.equals(MemorySegment.NULL)) {
			return new MemorySegment[]{MemorySegment.NULL, MemorySegment.NULL};
		}

		int numEdges = (int) (csrColumnTargets.byteSize() / 4);
		MemorySegment cscRowOffSeg = arena.allocate((long) (nodeCount + 1) * 4);
		MemorySegment cscColIdxSeg = arena.allocate((long) numEdges * 4);

		int[] inDegrees = new int[nodeCount];
		for (int i = 0; i < numEdges; i++) {
			int target = csrColumnTargets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, i);
			if (target >= 0 && target < nodeCount) {
				inDegrees[target]++;
			}
		}

		int accum = 0;
		for (int n = 0; n < nodeCount; n++) {
			cscRowOffSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, n, accum);
			accum += inDegrees[n];
		}
		cscRowOffSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, nodeCount, accum);

		int[] currentOffsets = new int[nodeCount];
		for (int n = 0; n < nodeCount; n++) {
			currentOffsets[n] = cscRowOffSeg.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, n);
		}

		for (int u = 0; u < nodeCount; u++) {
			int startIdx = csrRowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u);
			int endIdx = csrRowOffsets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, u + 1);
			for (int edgeIdx = startIdx; edgeIdx < endIdx; edgeIdx++) {
				int v = csrColumnTargets.getAtIndex(ValueLayout.JAVA_INT_UNALIGNED, edgeIdx);
				if (v >= 0 && v < nodeCount) {
					int insertPos = currentOffsets[v]++;
					cscColIdxSeg.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, insertPos, u);
				}
			}
		}

		return new MemorySegment[]{cscRowOffSeg, cscColIdxSeg};
	}

	public static byte[][] computeCscBytes(int nodeCount, int edgeCount, MemorySegment csrRowOffsets,
			MemorySegment csrColumnTargets) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment[] segs = computeCscSegments(arena, nodeCount, edgeCount, csrRowOffsets, csrColumnTargets);
			if (segs[0] == MemorySegment.NULL) {
				return new byte[][]{new byte[0], new byte[0]};
			}
			return new byte[][]{segs[0].toArray(ValueLayout.JAVA_BYTE), segs[1].toArray(ValueLayout.JAVA_BYTE)};
		}
	}

	public byte[] build(BinarySnapshotLoader.LoadedSnapshot loaded) {
		List<String> relNames = (loaded != null && loaded.graph() != null)
				? new ArrayList<>(new java.util.TreeSet<>(loaded.graph().getAllRelationSnapshots().keySet()))
				: List.of();
		int relationCount = relNames.size();

		int dataOffset = 4096;

		// Resolve domain list
		List<DomainEntry> domainList = new ArrayList<>(this.customDomains);
		if (domainList.isEmpty() && loaded != null && loaded.domainsById() != null && !loaded.domainsById().isEmpty()) {
			for (BinarySnapshotLoader.LoadedDomain ld : loaded.domainsById().values()) {
				List<DomainAttributeEntry> domAttrs = new ArrayList<>();
				for (int a = 0; a < ld.attributes().size(); a++) {
					BinarySnapshotLoader.LoadedAttribute la = ld.attributes().get(a);
					MemorySegment dSeg = ld.getAttributeSegment(a);
					MemorySegment oSeg = ld.getOffsetsSegment(a);
					MemorySegment vSeg = ld.getValiditySegment(a);
					byte[] dBytes = (dSeg != null && dSeg != MemorySegment.NULL)
							? dSeg.toArray(ValueLayout.JAVA_BYTE)
							: new byte[0];
					byte[] oBytes = (oSeg != null && oSeg != MemorySegment.NULL)
							? oSeg.toArray(ValueLayout.JAVA_BYTE)
							: new byte[0];
					byte[] vBytes = (vSeg != null && vSeg != MemorySegment.NULL)
							? vSeg.toArray(ValueLayout.JAVA_BYTE)
							: new byte[0];
					domAttrs.add(
							new DomainAttributeEntry(la.name(), la.typeCode(), la.dimension(), dBytes, oBytes, vBytes));
				}
				domainList.add(new DomainEntry(ld.domainId(), ld.name(), ld.keyType(), ld.nodeCount(),
						ld.keyAttrIndex(), domAttrs));
			}
		}
		if (domainList.isEmpty()) {
			domainList.add(new DomainEntry(0, "User", (byte) 0x03, 0L));
		}
		int domainCount = domainList.size();

		// Build Shared String Table
		ByteArrayOutputStream stringPoolOut = new ByteArrayOutputStream();
		stringPoolOut.write(0); // Offset 0 = empty string ""
		Map<String, Integer> stringMap = new HashMap<>();
		stringMap.put("", 0);

		java.util.function.Function<String, Integer> getOrAddString = (str) -> {
			if (str == null || str.isEmpty())
				return 0;
			if (stringMap.containsKey(str))
				return stringMap.get(str);
			int off = stringPoolOut.size();
			byte[] sb = str.getBytes(StandardCharsets.UTF_8);
			stringPoolOut.write(sb, 0, sb.length);
			stringPoolOut.write(0); // null terminator
			stringMap.put(str, off);
			return off;
		};

		for (DomainEntry dom : domainList) {
			getOrAddString.apply(dom.name());
			for (DomainAttributeEntry a : dom.attributes()) {
				getOrAddString.apply(a.name());
			}
		}

		List<Integer> relNameOffsets = new ArrayList<>();
		for (String rName : relNames) {
			relNameOffsets.add(getOrAddString.apply(rName));
			RelationSnapshot relSnap = loaded != null && loaded.graph() != null
					? loaded.graph().getRelationSnapshot(rName)
					: null;
			if (relSnap != null) {
				int attrCount = relSnap.getAttributeSegments().size();
				for (int a = 0; a < attrCount; a++) {
					String attrName = relSnap.getAttributeName(a);
					getOrAddString.apply(attrName != null ? attrName : ("attr_" + a));
				}
			}
		}

		// Calculate Section 2 Directory Table layout length
		int poolBytes = stringPoolOut.size();
		int alignedStrTableBytes = (4 + poolBytes + 127) & ~127;

		int rawDomTableBytes = 0;
		for (DomainEntry dom : domainList) {
			rawDomTableBytes += (targetVersion >= 10) ? (32 + dom.attributes().size() * 44) : 16;
		}
		int alignedDomTableBytes = (rawDomTableBytes + 127) & ~127;

		int totalRelAttrDescBytes = 0;
		for (String rName : relNames) {
			RelationSnapshot relSnap = loaded != null && loaded.graph() != null
					? loaded.graph().getRelationSnapshot(rName)
					: null;
			if (relSnap != null) {
				totalRelAttrDescBytes += relSnap.getAttributeSegments().size() * 44;
			}
		}
		int rawRelTableBytes = relationCount * 128 + totalRelAttrDescBytes;
		int alignedRelTableBytes = (rawRelTableBytes + 127) & ~127;

		int totalDirLen = alignedStrTableBytes + alignedDomTableBytes + alignedRelTableBytes;
		int alignedDirLen = (totalDirLen + 4095) & ~4095;
		long payloadBaseOffset = dataOffset + alignedDirLen;

		ByteArrayOutputStream payloadOut = new ByteArrayOutputStream();

		// Layout Domain Attribute Payloads & Build Descriptors
		Map<Integer, List<byte[]>> domainAttrDescriptors = new HashMap<>();
		if (targetVersion >= 10) {
			for (DomainEntry dom : domainList) {
				List<byte[]> descs = new ArrayList<>();
				for (DomainAttributeEntry a : dom.attributes()) {
					try {
						align128Out(payloadOut);
						long currentPayloadOffset = payloadBaseOffset + payloadOut.size();

						int aNameOff = getOrAddString.apply(a.name());
						byte typeCode = a.typeCode();
						boolean isNullable = (typeCode & 0x80) != 0;
						long validityOff = 0;
						long validityBytes = 0;

						if (isNullable) {
							long numBits = dom.nodeCount();
							long rawBitmapBytes = ((numBits + 63) / 64) * 8;
							int paddedBitmapBytes = (int) ((rawBitmapBytes + 127) & ~127L);

							byte[] valData = new byte[paddedBitmapBytes];
							if (a.validity() != null && a.validity().length > 0) {
								System.arraycopy(a.validity(), 0, valData, 0,
										Math.min(a.validity().length, valData.length));
							}
							validityOff = currentPayloadOffset;
							validityBytes = paddedBitmapBytes;
							payloadOut.write(valData);
							currentPayloadOffset = payloadBaseOffset + payloadOut.size();
						}

						align128Out(payloadOut);
						currentPayloadOffset = payloadBaseOffset + payloadOut.size();

						long dataOff = 0;
						long dataBytes = 0;
						if (a.data() != null && a.data().length > 0) {
							dataOff = currentPayloadOffset;
							dataBytes = a.data().length;
							payloadOut.write(a.data());
							align128Out(payloadOut);
							currentPayloadOffset = payloadBaseOffset + payloadOut.size();
						}

						long offsOff = 0;
						long offsBytes = 0;
						if (a.offsets() != null && a.offsets().length > 0) {
							offsOff = currentPayloadOffset;
							offsBytes = a.offsets().length;
							payloadOut.write(a.offsets());
							align128Out(payloadOut);
						}

						ByteBuffer descBuf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
						descBuf.putInt(aNameOff);
						descBuf.put(typeCode);
						descBuf.put((byte) 0); // reserved1
						descBuf.putShort((short) 0); // reserved2
						descBuf.putInt(a.dimension());
						if (isNullable) {
							descBuf.putLong(validityOff);
							descBuf.putLong(validityBytes + dataBytes);
						} else {
							descBuf.putLong(dataOff);
							descBuf.putLong(dataBytes);
						}
						descBuf.putLong(offsOff);
						descBuf.putLong(offsBytes);
						descs.add(descBuf.array());

					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
				domainAttrDescriptors.put(dom.domainId(), descs);
			}
		}

		// Layout Relation Payloads & Build Descriptors
		List<byte[]> relEntries = new ArrayList<>();
		int relIdx = 0;
		for (String rName : relNames) {
			try {
				align4kOut(payloadOut);

				RelationSnapshot relSnap = loaded != null && loaded.graph() != null
						? loaded.graph().getRelationSnapshot(rName)
						: null;

				// Row Offsets
				MemorySegment rowSeg = relSnap != null ? relSnap.getRowOffsetsSegment() : MemorySegment.NULL;
				byte[] rowBytes = rowSeg != null && rowSeg != MemorySegment.NULL
						? rowSeg.toArray(ValueLayout.JAVA_BYTE)
						: new byte[0];
				long csrRowOffOffset = 0L;
				long csrRowOffBytes = rowBytes.length;
				if (csrRowOffBytes > 0) {
					align128Out(payloadOut);
					csrRowOffOffset = payloadBaseOffset + payloadOut.size();
					payloadOut.write(rowBytes);
				}

				// Column Indices
				MemorySegment colSeg = relSnap != null ? relSnap.getColumnTargetsSegment() : MemorySegment.NULL;
				byte[] colBytes = colSeg != null && colSeg != MemorySegment.NULL
						? colSeg.toArray(ValueLayout.JAVA_BYTE)
						: new byte[0];
				long csrColIdxOffset = 0L;
				long csrColIdxBytes = colBytes.length;
				if (csrColIdxBytes > 0) {
					align128Out(payloadOut);
					csrColIdxOffset = payloadBaseOffset + payloadOut.size();
					payloadOut.write(colBytes);
				}

				// CSC Segments evaluation
				boolean sourceHasCsc = relSnap != null && relSnap.hasCsc();
				boolean includeCsc = (includeCscOverride != null) ? includeCscOverride : sourceHasCsc;

				long cscRowOffOffset = 0L;
				long cscRowOffBytes = 0L;
				long cscColIdxOffset = 0L;
				long cscColIdxBytes = 0L;

				if (includeCsc) {
					byte[] cscRowBytes = new byte[0];
					byte[] cscColBytes = new byte[0];

					if (sourceHasCsc) {
						MemorySegment cscRowSeg = relSnap.getCscRowOffsetsSegment();
						MemorySegment cscColSeg = relSnap.getCscColumnTargetsSegment();
						if (cscRowSeg != null && cscRowSeg != MemorySegment.NULL)
							cscRowBytes = cscRowSeg.toArray(ValueLayout.JAVA_BYTE);
						if (cscColSeg != null && cscColSeg != MemorySegment.NULL)
							cscColBytes = cscColSeg.toArray(ValueLayout.JAVA_BYTE);
					} else if (relSnap != null) {
						byte[][] comp = computeCscBytes(relSnap.getNodeCount(), (int) relSnap.getEdgeCount(), rowSeg,
								colSeg);
						cscRowBytes = comp[0];
						cscColBytes = comp[1];
					}

					if (cscRowBytes.length > 0) {
						align128Out(payloadOut);
						cscRowOffOffset = payloadBaseOffset + payloadOut.size();
						payloadOut.write(cscRowBytes);
						cscRowOffBytes = cscRowBytes.length;

						align128Out(payloadOut);
						cscColIdxOffset = payloadBaseOffset + payloadOut.size();
						payloadOut.write(cscColBytes);
						cscColIdxBytes = cscColBytes.length;
					}
				}

				byte encodingId = 0x00; // RAW
				long nodeCount = relSnap != null ? relSnap.getNodeCount() : 0;
				long edgeCount = relSnap != null ? (int) relSnap.getEdgeCount() : 0;

				List<MemorySegment> attrSegs = relSnap != null ? relSnap.getAttributeSegments() : List.of();
				List<MemorySegment> valSegs = relSnap != null ? relSnap.getValiditySegments() : List.of();
				int attrCount = attrSegs.size();
				ByteBuffer attrDescsBuf = ByteBuffer.allocate(attrCount * 44).order(ByteOrder.LITTLE_ENDIAN);

				for (int a = 0; a < attrCount; a++) {
					String attrName = relSnap.getAttributeName(a);
					int attrNameOff = getOrAddString.apply(attrName != null ? attrName : ("attr_" + a));
					byte typeCode = relSnap.getAttributeTypeCode(a);
					if (typeCode == 0) {
						typeCode = 0x06; // default float32
					}
					MemorySegment valSeg = (a < valSegs.size()) ? valSegs.get(a) : MemorySegment.NULL;
					boolean hasValidity = valSeg != null && valSeg != MemorySegment.NULL && valSeg.byteSize() > 0;
					if (hasValidity) {
						typeCode = (byte) (typeCode | 0x80);
					}

					align128Out(payloadOut);
					long attrDataOff = payloadBaseOffset + payloadOut.size();
					long attrDataBytes = 0L;

					if (hasValidity) {
						long numBits = (edgeCount > 0) ? edgeCount : nodeCount;
						long rawBitmapBytes = ((numBits + 63) / 64) * 8;
						int paddedBitmapBytes = (int) ((rawBitmapBytes + 127) & ~127L);
						byte[] valBytes = valSeg.toArray(ValueLayout.JAVA_BYTE);
						byte[] paddedVal = new byte[Math.max(paddedBitmapBytes, valBytes.length)];
						System.arraycopy(valBytes, 0, paddedVal, 0, valBytes.length);
						payloadOut.write(paddedVal);
						attrDataBytes += paddedVal.length;
					}

					MemorySegment dataSeg = attrSegs.get(a);
					if (dataSeg != null && dataSeg != MemorySegment.NULL && dataSeg.byteSize() > 0) {
						byte[] dataBytes = dataSeg.toArray(ValueLayout.JAVA_BYTE);
						payloadOut.write(dataBytes);
						attrDataBytes += dataBytes.length;
					}

					attrDescsBuf.putInt(attrNameOff);
					attrDescsBuf.put(typeCode);
					attrDescsBuf.put((byte) 0); // reserved1
					attrDescsBuf.putShort((short) 0); // reserved2
					attrDescsBuf.putInt(1); // dimension
					attrDescsBuf.putLong(attrDataOff);
					attrDescsBuf.putLong(attrDataBytes);
					attrDescsBuf.putLong(0L); // offsets_off
					attrDescsBuf.putLong(0L); // offsets_bytes
				}

				// Relation Entry (Fixed 128 Bytes) + Attribute Descriptors (44 Bytes each)
				int rNameOff = relNameOffsets.get(relIdx);
				int[] doms = customRelationDomains.getOrDefault(rName, new int[]{0, 0});
				ByteBuffer relBuf = ByteBuffer.allocate(128 + attrCount * 44).order(ByteOrder.LITTLE_ENDIAN);
				relBuf.putShort((short) relIdx); // relation_id
				relBuf.putShort((short) doms[0]); // src_domain_id
				relBuf.putShort((short) doms[1]); // tgt_domain_id
				relBuf.put(encodingId); // encoding_id = RAW (0) or TPU_BCOO (6)
				relBuf.put((byte) 4); // node_id_width = 4
				relBuf.put((byte) 4); // edge_index_width = 4
				relBuf.put(new byte[3]); // reserved1
				relBuf.putInt(rNameOff); // name_offset

				long sectionFeatures = (cscRowOffBytes > 0) ? 1L : 0L;

				relBuf.putLong(nodeCount);
				relBuf.putLong(edgeCount);
				relBuf.putLong(sectionFeatures); // section_features (bit 0 = CSC)
				relBuf.putLong(csrRowOffOffset);
				relBuf.putLong(csrRowOffBytes);
				relBuf.putLong(csrColIdxOffset);
				relBuf.putLong(csrColIdxBytes);
				relBuf.putLong(cscRowOffOffset);
				relBuf.putLong(cscRowOffBytes);
				relBuf.putLong(cscColIdxOffset);
				relBuf.putLong(cscColIdxBytes);
				relBuf.putShort((short) attrCount); // attr_count
				relBuf.put(new byte[22]); // reserved2
				if (attrCount > 0) {
					relBuf.put(attrDescsBuf.array());
				}

				relEntries.add(relBuf.array());
				relIdx++;
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		// 2. Build Section 2 Directory Table
		ByteArrayOutputStream dirTableOut = new ByteArrayOutputStream();
		try {
			ByteBuffer strHdrBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			strHdrBuf.putInt(poolBytes);
			dirTableOut.write(strHdrBuf.array());
			dirTableOut.write(stringPoolOut.toByteArray());

			align128Out(dirTableOut);

			// Write Domain Catalog Entries
			for (DomainEntry dom : domainList) {
				int domNameOff = getOrAddString.apply(dom.name());
				if (targetVersion >= 10) {
					List<byte[]> descs = domainAttrDescriptors.getOrDefault(dom.domainId(), List.of());
					ByteBuffer domBuf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
					domBuf.putShort((short) dom.domainId());
					domBuf.put(dom.keyType());
					domBuf.put((byte) 0); // reserved1
					domBuf.putInt(domNameOff);
					domBuf.putLong(dom.nodeCount());
					domBuf.putShort((short) descs.size()); // attr_count
					domBuf.putShort((short) dom.keyAttrIndex()); // key_attr_index
					domBuf.put(new byte[12]); // reserved2
					dirTableOut.write(domBuf.array());

					for (byte[] d : descs) {
						dirTableOut.write(d);
					}
				} else {
					ByteBuffer domBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
					domBuf.putShort((short) dom.domainId());
					domBuf.put(dom.keyType());
					domBuf.put((byte) 0); // reserved
					domBuf.putInt(domNameOff); // name_offset
					domBuf.putLong(dom.nodeCount());// node_count
					dirTableOut.write(domBuf.array());
				}
			}

			align128Out(dirTableOut);

			for (byte[] entryBytes : relEntries) {
				dirTableOut.write(entryBytes, 0, entryBytes.length);
			}

			align128Out(dirTableOut);
			align4kOut(dirTableOut);

		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		ByteArrayOutputStream finalOut = new ByteArrayOutputStream();

		// 1. Write Header Page 0 (4096 Bytes)
		ByteBuffer hdrBuf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
		hdrBuf.putInt(SPEC_MAGIC); // magic
		hdrBuf.putShort(targetVersion); // version = 10 (or 9)
		hdrBuf.putInt(dataOffset); // data_offset = 4096
		hdrBuf.putShort((short) domainCount); // domain_count
		hdrBuf.putShort((short) relationCount); // relation_count
		hdrBuf.putLong(1700000000000L); // timestamp_ms
		hdrBuf.putLong(1L); // required_features (4KB_PAGE_ALIGNED)
		hdrBuf.putLong(0L); // footer_directory_offset
		hdrBuf.putLong(0L); // footer_directory_bytes
		hdrBuf.put(new byte[16]); // snapshot_uuid

		// CRC-16 over bytes 0..61
		byte[] hdrBytes = hdrBuf.array();
		short crc = (short) computeCrc16(hdrBytes, 0, 0x3E);
		hdrBuf.putShort(0x3E, crc);

		if (targetVersion >= 10) {
			hdrBuf.putShort(0x40, (short) 0); // index_count = 0
		}

		try {
			finalOut.write(hdrBuf.array());
			finalOut.write(dirTableOut.toByteArray());
			finalOut.write(payloadOut.toByteArray());

			// Write Footer Block
			align4kOut(finalOut);
			int footerStart = finalOut.size();

			Map<String, String> mergedMetadata = new HashMap<>(this.metadata);
			if (loaded != null && loaded.getMetadataMap() != null) {
				for (var e : loaded.getMetadataMap().entrySet()) {
					mergedMetadata.putIfAbsent(e.getKey(), e.getValue());
				}
			}

			ByteBuffer metaBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
			metaBuf.putInt(mergedMetadata.size());
			finalOut.write(metaBuf.array());

			for (Map.Entry<String, String> kv : mergedMetadata.entrySet()) {
				byte[] kb = kv.getKey().getBytes(StandardCharsets.UTF_8);
				ByteBuffer kBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
				kBuf.putShort((short) kb.length);
				finalOut.write(kBuf.array());
				finalOut.write(kb);

				byte[] vb = kv.getValue().getBytes(StandardCharsets.UTF_8);
				ByteBuffer vBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
				vBuf.putInt(vb.length);
				finalOut.write(vBuf.array());
				finalOut.write(vb);
			}

			long footerLen = finalOut.size() + 16 - footerStart;
			ByteBuffer trailerBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			trailerBuf.putLong(footerLen);
			trailerBuf.putInt(targetVersion);
			trailerBuf.putInt(SPEC_MAGIC);
			finalOut.write(trailerBuf.array());

		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return finalOut.toByteArray();
	}

	private static void align128Out(ByteArrayOutputStream out) throws IOException {
		int rem = out.size() % 128;
		if (rem != 0) {
			out.write(new byte[128 - rem]);
		}
	}

	private static void align4kOut(ByteArrayOutputStream out) throws IOException {
		int rem = out.size() % 4096;
		if (rem != 0) {
			out.write(new byte[4096 - rem]);
		}
	}

	private static int computeCrc16(byte[] data, int off, int len) {
		int crc = 0xFFFF;
		for (int i = off; i < off + len; i++) {
			crc ^= (data[i] & 0xFF) << 8;
			for (int j = 0; j < 8; j++) {
				if ((crc & 0x8000) != 0) {
					crc = (crc << 1) ^ 0x1021;
				} else {
					crc <<= 1;
				}
			}
		}
		return crc & 0xFFFF;
	}
}
