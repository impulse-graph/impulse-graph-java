package org.impulsegraph.storage.csr;

import java.lang.foreign.Arena;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance off-heap multi-relation graph container holding relation
 * snapshots across domain types.
 */
public class GraphSnapshot
		implements
			org.impulsegraph.api.ImpulseGraphSnapshot,
			AutoCloseable,
			org.impulsegraph.storage.stats.StatisticsView {

	private final Arena arena;
	private final Map<String, RelationSnapshot> relationMap;
	private final Map<String, RelationSnapshot> aliasMap;
	private final Map<String, org.impulsegraph.api.RelationSnapshot> unmodifiableRelationMap;
	private final RelationSnapshot[] relationsById;
	private final int maxNodeCount;
	private final org.impulsegraph.api.stats.GraphStatistics graphStats = new org.impulsegraph.api.stats.GraphStatistics();
	private final Map<String, String> metadata = new ConcurrentHashMap<>();
	private final Map<String, BinarySnapshotLoader.LoadedDomain> domainMap;
	private final Map<Integer, BinarySnapshotLoader.LoadedDomain> domainIdMap;
	private final List<BinarySnapshotLoader.LoadedIndex> secondaryIndices;
	private final org.impulsegraph.api.meta.SnapshotHeader header;
	private final java.lang.foreign.MemorySegment rootSegment;
	private final List<org.impulsegraph.api.meta.DomainMeta> domainMetas;
	private final Map<String, org.impulsegraph.api.meta.DomainMeta> domainMetaByName;
	private final Map<Integer, org.impulsegraph.api.meta.DomainMeta> domainMetaById;
	private final List<org.impulsegraph.api.meta.IndexMeta> indexMetas;
	private final Object sha256Lock = new Object();
	private volatile String cachedSha256;

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots) {
		this(arena, snapshots, Map.of(), Map.of());
	}

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots, Map<String, String> metadata) {
		this(arena, snapshots, metadata, Map.of());
	}

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots, Map<String, String> metadata,
			Map<String, RelationSnapshot> aliases) {
		this(arena, snapshots, metadata, aliases, Map.of());
	}

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots, Map<String, String> metadata,
			Map<String, RelationSnapshot> aliases, Map<String, BinarySnapshotLoader.LoadedDomain> domains) {
		this(arena, snapshots, metadata, aliases, domains, List.of());
	}

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots, Map<String, String> metadata,
			Map<String, RelationSnapshot> aliases, Map<String, BinarySnapshotLoader.LoadedDomain> domains,
			List<BinarySnapshotLoader.LoadedIndex> secondaryIndices) {
		this(arena, snapshots, metadata, aliases, domains, secondaryIndices, null, null);
	}

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots, Map<String, String> metadata,
			Map<String, RelationSnapshot> aliases, Map<String, BinarySnapshotLoader.LoadedDomain> domains,
			List<BinarySnapshotLoader.LoadedIndex> secondaryIndices, org.impulsegraph.api.meta.SnapshotHeader header,
			java.lang.foreign.MemorySegment rootSegment) {
		this(arena, snapshots, metadata, aliases, domains, domains != null ? domains.values() : List.of(),
				secondaryIndices, header, rootSegment);
	}

	public GraphSnapshot(Arena arena, Map<String, RelationSnapshot> snapshots, Map<String, String> metadata,
			Map<String, RelationSnapshot> aliases, Map<String, BinarySnapshotLoader.LoadedDomain> domains,
			java.util.Collection<BinarySnapshotLoader.LoadedDomain> orderedDomains,
			List<BinarySnapshotLoader.LoadedIndex> secondaryIndices, org.impulsegraph.api.meta.SnapshotHeader header,
			java.lang.foreign.MemorySegment rootSegment) {
		this.arena = Objects.requireNonNull(arena, "Arena must not be null");
		this.header = header;
		this.rootSegment = rootSegment;
		this.secondaryIndices = (secondaryIndices != null && !secondaryIndices.isEmpty())
				? List.copyOf(secondaryIndices)
				: List.of();
		if (this.secondaryIndices.isEmpty()) {
			this.indexMetas = List.of();
		} else {
			java.util.List<org.impulsegraph.api.meta.IndexMeta> im = new java.util.ArrayList<>(
					this.secondaryIndices.size());
			for (BinarySnapshotLoader.LoadedIndex idx : this.secondaryIndices) {
				im.add(idx.toIndexMeta());
			}
			this.indexMetas = Collections.unmodifiableList(im);
		}
		this.aliasMap = (aliases != null && !aliases.isEmpty())
				? Collections.unmodifiableMap(new java.util.LinkedHashMap<>(aliases))
				: Map.of();
		this.domainMap = (domains != null && !domains.isEmpty())
				? Collections.unmodifiableMap(new java.util.LinkedHashMap<>(domains))
				: Map.of();
		java.util.Collection<BinarySnapshotLoader.LoadedDomain> domainSource = (orderedDomains != null
				&& !orderedDomains.isEmpty()) ? orderedDomains : this.domainMap.values();
		Map<Integer, BinarySnapshotLoader.LoadedDomain> idMap = new java.util.LinkedHashMap<>();
		java.util.List<org.impulsegraph.api.meta.DomainMeta> dm = new java.util.ArrayList<>(domainSource.size());
		Map<String, org.impulsegraph.api.meta.DomainMeta> dmByName = new java.util.LinkedHashMap<>();
		Map<Integer, org.impulsegraph.api.meta.DomainMeta> dmById = new java.util.LinkedHashMap<>();
		int maxDomNodes = 0;
		for (BinarySnapshotLoader.LoadedDomain d : domainSource) {
			if (d != null) {
				idMap.put(d.domainId(), d);
				org.impulsegraph.api.meta.DomainMeta meta = d.toDomainMeta();
				dm.add(meta);
				dmByName.put(meta.name(), meta);
				dmById.put(meta.domainId(), meta);
				if (d.nodeCount() > 0 && d.nodeCount() <= Integer.MAX_VALUE) {
					maxDomNodes = Math.max(maxDomNodes, (int) d.nodeCount());
				}
			}
		}
		this.domainIdMap = Collections.unmodifiableMap(idMap);
		this.domainMetas = Collections.unmodifiableList(dm);
		this.domainMetaByName = Collections.unmodifiableMap(dmByName);
		this.domainMetaById = Collections.unmodifiableMap(dmById);
		if (snapshots == null || snapshots.isEmpty()) {
			this.relationMap = Map.of();
			this.unmodifiableRelationMap = Map.of();
			this.relationsById = new RelationSnapshot[0];
			this.maxNodeCount = maxDomNodes > 0 ? maxDomNodes : 1024 * 1024;
		} else {
			java.util.LinkedHashMap<String, RelationSnapshot> copy = new java.util.LinkedHashMap<>(snapshots);
			this.relationMap = Collections.unmodifiableMap(copy);
			this.unmodifiableRelationMap = Collections.unmodifiableMap(copy);
			int maxSlot = copy.size() - 1;
			for (Map.Entry<String, RelationSnapshot> entry : copy.entrySet()) {
				String key = entry.getKey();
				if (key != null && key.startsWith("rel_")) {
					int idx = parseNonNegativeSlot(key.substring(4));
					if (idx > maxSlot) {
						maxSlot = idx;
					}
				}
				RelationSnapshot rel = entry.getValue();
				if (rel != null && rel.getRelationId() > maxSlot && rel.getRelationId() < 65536) {
					maxSlot = rel.getRelationId();
				}
			}
			RelationSnapshot[] arr = new RelationSnapshot[maxSlot + 1];
			int i = 0;
			int maxNodes = maxDomNodes;
			for (RelationSnapshot rel : copy.values()) {
				if (rel != null) {
					if (rel.getRelationId() < 0) {
						rel.setCatalogMetadata(i, rel.getSrcDomainId(), rel.getTgtDomainId(), rel.getEncodingId(),
								rel.getSectionFeatures(), rel.getComponentIds());
					}
					maxNodes = Math.max(maxNodes, rel.getNodeCount());
					maxNodes = Math.max(maxNodes, rel.getTargetNodeCount());
				}
				arr[i++] = rel;
			}
			for (Map.Entry<String, RelationSnapshot> entry : copy.entrySet()) {
				String key = entry.getKey();
				RelationSnapshot rel = entry.getValue();
				if (rel != null && rel.getRelationId() >= 0 && rel.getRelationId() < arr.length) {
					arr[rel.getRelationId()] = rel;
				} else if (key != null && key.startsWith("rel_")) {
					int idx = parseNonNegativeSlot(key.substring(4));
					if (idx >= 0 && idx < arr.length) {
						arr[idx] = rel;
					}
				}
			}
			this.relationsById = arr;
			this.maxNodeCount = maxNodes > 0 ? maxNodes : 1024 * 1024;
		}
		if (metadata != null) {
			this.metadata.putAll(metadata);
		}
	}

	public List<BinarySnapshotLoader.LoadedIndex> getSecondaryIndices() {
		return secondaryIndices;
	}

	public boolean hasSecondaryIndex(int domainId, int attributeIndex) {
		for (BinarySnapshotLoader.LoadedIndex idx : secondaryIndices) {
			if (idx.relationId() == 0xFFFF && idx.domainId() == domainId && idx.attributeIndex() == attributeIndex) {
				return true;
			}
		}
		return false;
	}

	private static int parseNonNegativeSlot(String s) {
		if (s == null || s.isEmpty()) {
			return -1;
		}
		int val = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return -1;
			}
			val = val * 10 + (c - '0');
			if (val >= 65536) {
				return -1;
			}
		}
		return val;
	}

	@Override
	public int getEstimatedOutDegreePercentile(int relationId, double percentile) {
		String key = "stats.out_degree." + relationId;
		String json = metadata.get(key);
		if (json == null)
			return -1;
		try {
			// Very simple JSON parser for {"pct_99": 42}
			int pctInt = (int) Math.round(percentile * 100);
			String search = "\"pct_" + pctInt + "\":";
			int idx = json.indexOf(search);
			if (idx == -1)
				return -1;
			int start = idx + search.length();
			int end = json.indexOf(",", start);
			if (end == -1)
				end = json.indexOf("}", start);
			if (end == -1)
				return -1;
			return Integer.parseInt(json.substring(start, end).trim());
		} catch (Exception e) {
			return -1;
		}
	}

	@Override
	public long getEstimatedDomainCount(int domainId) {
		// If exact count is available via relation map, we could return it.
		// For CBO stats, we look for numeric stat if tracked.
		return -1;
	}

	@Override
	public String getRawStatisticJson(String metadataKey) {
		return metadata.get(metadataKey);
	}

	public RelationSnapshot getRelationSnapshot(String relationName) {
		if (relationName == null)
			return null;
		RelationSnapshot snapshot = relationMap.get(relationName);
		if (snapshot != null)
			return snapshot;
		snapshot = aliasMap.get(relationName);
		if (snapshot != null)
			return snapshot;

		String targetNorm = relationName.replaceFirst("^rel_\\d+_", "").toLowerCase();

		for (Map.Entry<String, RelationSnapshot> entry : relationMap.entrySet()) {
			String key = entry.getKey();
			if (key.equalsIgnoreCase(relationName)) {
				return entry.getValue();
			}
			String keyNorm = key.replaceFirst("^rel_\\d+_", "").toLowerCase();
			if (keyNorm.equals(targetNorm)) {
				return entry.getValue();
			}
		}
		return null;
	}

	@Override
	public RelationSnapshot getRelationSnapshot(int relationId) {
		return (relationId >= 0 && relationId < relationsById.length) ? relationsById[relationId] : null;
	}

	@Override
	public int getMaxNodeCount() {
		return maxNodeCount;
	}

	@Override
	public Map<String, org.impulsegraph.api.RelationSnapshot> getAllRelationSnapshots() {
		return unmodifiableRelationMap;
	}

	private volatile long cachedOffHeapBytes = -1L;

	public long getOffHeapMemorySizeBytes() {
		long cached = this.cachedOffHeapBytes;
		if (cached >= 0L) {
			return cached;
		}
		long total = 0;
		for (RelationSnapshot snapshot : relationMap.values()) {
			if (snapshot != null) {
				total += snapshot.getMemoryFootprintBytes();
			}
		}
		this.cachedOffHeapBytes = total;
		return total;
	}

	public org.impulsegraph.api.stats.GraphStatistics getGraphStatistics() {
		for (Map.Entry<String, RelationSnapshot> entry : relationMap.entrySet()) {
			if (entry.getValue() != null && graphStats.getRelationStatistics(entry.getKey()) == null) {
				graphStats.putRelationStatistics(entry.getKey(), entry.getValue().getStatistics());
			}
		}
		return graphStats;
	}

	@Override
	public int getRelationCount() {
		return relationMap.size();
	}

	@Override
	public java.util.Set<String> getRelationNames() {
		return relationMap.keySet();
	}

	public BinarySnapshotLoader.LoadedDomain getDomain(String domainName) {
		if (domainName == null) {
			return null;
		}
		BinarySnapshotLoader.LoadedDomain exact = domainMap.get(domainName);
		if (exact != null) {
			return exact;
		}
		for (Map.Entry<String, BinarySnapshotLoader.LoadedDomain> entry : domainMap.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(domainName)) {
				return entry.getValue();
			}
		}
		return null;
	}

	public BinarySnapshotLoader.LoadedDomain getDomain(int domainId) {
		return domainIdMap.get(domainId);
	}

	@Override
	public java.util.Set<String> getDomainNames() {
		if (!domainMap.isEmpty()) {
			return domainMap.keySet();
		}
		return metadata.keySet().stream().filter(k -> k.startsWith("domain.") && k.endsWith(".id"))
				.map(k -> k.substring(7, k.length() - 3)).collect(java.util.stream.Collectors.toSet());
	}

	@Override
	public String getDomainName(int domainId) {
		BinarySnapshotLoader.LoadedDomain dom = domainIdMap.get(domainId);
		if (dom != null) {
			return dom.name();
		}
		return metadata.get("domain." + domainId + ".name");
	}

	@Override
	public java.util.List<String> getDomainAttributeNames(String domainName) {
		BinarySnapshotLoader.LoadedDomain dom = getDomain(domainName);
		if (dom == null || dom.attributes().isEmpty()) {
			return java.util.List.of();
		}
		java.util.List<String> names = new java.util.ArrayList<>(dom.attributes().size());
		for (BinarySnapshotLoader.LoadedAttribute a : dom.attributes()) {
			names.add(a.name());
		}
		return Collections.unmodifiableList(names);
	}

	@Override
	public java.util.List<java.lang.foreign.MemorySegment> getDomainAttributeSegments(String domainName) {
		BinarySnapshotLoader.LoadedDomain dom = getDomain(domainName);
		return dom != null ? dom.attributeSegments() : java.util.List.of();
	}

	@Override
	public java.lang.foreign.MemorySegment getDomainAttributeSegment(String domainName, String attrName) {
		BinarySnapshotLoader.LoadedDomain dom = getDomain(domainName);
		return dom != null ? dom.getAttributeSegment(attrName) : java.lang.foreign.MemorySegment.NULL;
	}

	@Override
	public org.impulsegraph.api.Decimal64 getDomainAttributeDecimal64(String domainName, String attrName, long nodeId) {
		return readDomainDecimal64Internal(domainName, attrName, nodeId, -1, -1);
	}

	@Override
	public org.impulsegraph.api.Decimal64 getDomainAttributeDecimal64(String domainName, String attrName, long nodeId,
			int expectedPrecision, int expectedScale) {
		org.impulsegraph.api.Decimal64.validatePrecisionScale(expectedPrecision, expectedScale);
		return readDomainDecimal64Internal(domainName, attrName, nodeId, expectedPrecision, expectedScale);
	}

	private org.impulsegraph.api.Decimal64 readDomainDecimal64Internal(String domainName, String attrName, long nodeId,
			int expectedPrecision, int expectedScale) {
		Objects.requireNonNull(domainName, "domainName must not be null");
		Objects.requireNonNull(attrName, "attrName must not be null");
		BinarySnapshotLoader.LoadedDomain dom = getDomain(domainName);
		if (dom == null) {
			throw new IllegalArgumentException("Domain not found: " + domainName);
		}
		long nodeCount = dom.nodeCount();
		if (nodeId < 0L || nodeId >= nodeCount) {
			throw new IllegalArgumentException("nodeId " + nodeId + " out of bounds for domain '" + domainName
					+ "' (nodeCount=" + nodeCount + ")");
		}
		BinarySnapshotLoader.LoadedAttribute attr = dom.getAttribute(attrName);
		if (attr == null) {
			throw new IllegalArgumentException("Attribute '" + attrName + "' not found on domain '" + domainName + "'");
		}
		if (attr.typeCode() != (byte) 0x15) {
			throw new IllegalArgumentException("Attribute '" + domainName + "." + attrName
					+ "' is not DECIMAL64 (typeCode=0x" + Integer.toHexString(attr.typeCode() & 0xFF) + ")");
		}
		if (expectedPrecision >= 0 && (attr.precision() != expectedPrecision || attr.scale() != expectedScale)) {
			throw new IllegalArgumentException(
					"Attribute '" + domainName + "." + attrName + "' has DECIMAL(" + attr.precision() + ", "
							+ attr.scale() + "), expected DECIMAL(" + expectedPrecision + ", " + expectedScale + ")");
		}
		if (attr.isNullable()) {
			java.lang.foreign.MemorySegment valSeg = dom.getValiditySegment(attrName);
			if (valSeg == null || valSeg.address() == 0L || valSeg.byteSize() <= (nodeId >>> 3)) {
				throw new IllegalStateException("Validity bitmap unavailable for nullable DECIMAL64 attribute '"
						+ domainName + "." + attrName + "'");
			}
			byte b = valSeg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, nodeId >>> 3);
			if ((b & (1 << (int) (nodeId & 7L))) == 0) {
				return null;
			}
		}
		java.lang.foreign.MemorySegment dataSeg = dom.getAttributeSegment(attrName);
		if (dataSeg == null || dataSeg.address() == 0L || dataSeg.byteSize() < (nodeId + 1L) * 8L) {
			throw new IllegalStateException(
					"Attribute segment unavailable for '" + domainName + "." + attrName + "' at nodeId=" + nodeId);
		}
		long unscaled = dataSeg.get(
				java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN),
				nodeId * 8L);
		return new org.impulsegraph.api.Decimal64(unscaled, attr.precision(), attr.scale());
	}

	@Override
	public int getDomainKeyAttrIndex(String domainName) {
		BinarySnapshotLoader.LoadedDomain dom = getDomain(domainName);
		return dom != null ? dom.keyAttrIndex() : 0xFFFF;
	}

	@Override
	public org.impulsegraph.api.schema.GraphSchema getSchema(String domainName) {
		// Schema extraction not fully implemented without manifest context, returning
		// empty for now
		// A complete implementation would parse the catalog attributes.
		return new org.impulsegraph.api.schema.GraphSchema(java.util.List.of(), java.util.List.of());
	}

	public long getNodeCount(String domainName) {
		if (domainName != null) {
			BinarySnapshotLoader.LoadedDomain dom = getDomain(domainName);
			if (dom != null && dom.nodeCount() > 0) {
				return dom.nodeCount();
			}
			String meta = metadata.get("domain." + domainName + ".nodeCount");
			if (meta != null) {
				try {
					return Long.parseLong(meta);
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return 0;
	}

	@Override
	public long getEdgeCount(String relationName) {
		RelationSnapshot rel = getRelationSnapshot(relationName);
		return rel != null ? rel.getEdgeCount() : 0;
	}

	@Override
	public java.lang.foreign.MemorySegment getRelationTargetsSegment(String relationName) {
		RelationSnapshot rel = getRelationSnapshot(relationName);
		return rel != null ? rel.getColumnTargetsSegment() : null;
	}

	@Override
	public org.impulsegraph.api.meta.SnapshotHeader getHeader() {
		return header;
	}

	@Override
	public long getFileSizeBytes() {
		if (header != null) {
			return header.fileSizeBytes();
		}
		return (rootSegment != null && !rootSegment.equals(java.lang.foreign.MemorySegment.NULL))
				? rootSegment.byteSize()
				: 0L;
	}

	@Override
	public List<org.impulsegraph.api.meta.DomainMeta> getDomains() {
		return domainMetas;
	}

	@Override
	public org.impulsegraph.api.meta.DomainMeta getDomainMeta(String domainName) {
		if (domainName == null) {
			return null;
		}
		org.impulsegraph.api.meta.DomainMeta exact = domainMetaByName.get(domainName);
		if (exact != null) {
			return exact;
		}
		BinarySnapshotLoader.LoadedDomain dom = getDomain(domainName);
		return dom != null ? domainMetaById.get(dom.domainId()) : null;
	}

	@Override
	public org.impulsegraph.api.meta.DomainMeta getDomainMeta(int domainId) {
		return domainMetaById.get(domainId);
	}

	@Override
	public List<org.impulsegraph.api.meta.IndexMeta> getSecondaryIndexMetas() {
		return indexMetas;
	}

	@Override
	public String getSha256Checksum() {
		String cached = this.cachedSha256;
		if (cached != null) {
			return cached;
		}
		if (rootSegment == null || rootSegment.equals(java.lang.foreign.MemorySegment.NULL)
				|| rootSegment.byteSize() <= 0L) {
			return "";
		}
		synchronized (sha256Lock) {
			cached = this.cachedSha256;
			if (cached != null) {
				return cached;
			}
			enterQuery();
			try {
				if (!rootSegment.scope().isAlive()) {
					throw new IllegalStateException("Snapshot is closed");
				}
				java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
				long total = rootSegment.byteSize();
				byte[] chunk = new byte[65536];
				for (long off = 0L; off < total; off += chunk.length) {
					int len = (int) Math.min(chunk.length, total - off);
					java.lang.foreign.MemorySegment.copy(rootSegment, java.lang.foreign.ValueLayout.JAVA_BYTE, off,
							chunk, 0, len);
					md.update(chunk, 0, len);
				}
				String hex = java.util.HexFormat.of().formatHex(md.digest());
				this.cachedSha256 = hex;
				return hex;
			} catch (java.security.NoSuchAlgorithmException e) {
				throw new IllegalStateException("SHA-256 unavailable", e);
			} finally {
				exitQuery();
			}
		}
	}

	@Override
	public String getMetadata(String key) {
		return key != null ? metadata.get(key) : null;
	}

	@Override
	public Map<String, String> getMetadataMap() {
		return Collections.unmodifiableMap(metadata);
	}

	private final java.util.concurrent.atomic.AtomicLong activeQueryCount = new java.util.concurrent.atomic.AtomicLong(
			0L);
	private final java.util.concurrent.atomic.AtomicBoolean retiring = new java.util.concurrent.atomic.AtomicBoolean(
			false);

	public void enterQuery() {
		if (retiring.get()) {
			throw new IllegalStateException("Snapshot is draining or closed");
		}
		activeQueryCount.incrementAndGet();
		if (retiring.get()) {
			activeQueryCount.decrementAndGet();
			throw new IllegalStateException("Snapshot is draining or closed");
		}
	}

	public void exitQuery() {
		activeQueryCount.updateAndGet(v -> v > 0L ? v - 1L : 0L);
	}

	@Override
	public long getActiveQueryCount() {
		return activeQueryCount.get();
	}

	@Override
	public boolean isDrained() {
		return activeQueryCount.get() == 0L;
	}

	@Override
	public boolean awaitDrained(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
		long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
		while (activeQueryCount.get() > 0L) {
			if (System.nanoTime() >= deadlineNanos) {
				return activeQueryCount.get() == 0L;
			}
			Thread.onSpinWait();
			Thread.sleep(1);
		}
		return true;
	}

	@Override
	public void drainAndClose(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
		retiring.set(true);
		if (!awaitDrained(timeout, unit)) {
			throw new IllegalStateException("Timed out waiting for in-flight queries to drain before closing snapshot");
		}
		close();
	}

	@Override
	public void close() {
		if (arena != null && arena.scope().isAlive()) {
			try {
				arena.close();
			} catch (UnsupportedOperationException ignored) {
				// Auto or global arenas are managed by the GC/runtime
			}
		}
	}
}
