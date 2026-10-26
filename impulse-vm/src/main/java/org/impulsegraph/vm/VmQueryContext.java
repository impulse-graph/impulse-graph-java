package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.*;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;

/**
 * Runtime Execution Context for Impulse VM. Holds off-heap buffer pools,
 * bitsets, vectors, value maps, and graph snapshot references.
 */
public final class VmQueryContext implements AutoCloseable {

	public ImpulseGraphSnapshot getSnapshot() {
		return snapshot;
	}

	private ImpulseGraphSnapshot snapshot;
	private final List<String> stringPool = new ArrayList<>();
	private final Arena arena;
	private MemorySegment inlineDataSeg = null;
	private Object finalResult = null;
	public Object getFinalResult() {
		return finalResult;
	}
	public void setFinalResult(Object o) {
		finalResult = o;
	}

	private long inlineDataBytes = 0;

	// Bitset Pool
	private final List<ImpulseBitSet> bitsets = new ArrayList<>();
	private final java.util.BitSet freeBitsetHandles = new java.util.BitSet();

	public void setStringPool(List<String> pool) {
		stringPool.clear();
		if (pool != null) {
			stringPool.addAll(pool);
		}
	}

	public String getString(int index) {
		if (index >= 0 && index < stringPool.size()) {
			return stringPool.get(index);
		}
		return null;
	}

	// Vector Pools
	private final List<int[]> intVectors = new ArrayList<>();
	private final List<float[]> floatVectors = new ArrayList<>();
	private final List<double[]> doubleVectors = new ArrayList<>();
	private final List<long[]> longVectors = new ArrayList<>();
	private final List<String[]> stringVectors = new ArrayList<>();

	// Value Map Pool
	private final List<Map<Integer, Object>> valueMaps = new ArrayList<>();

	// Scratch Memory Accounting (64 KB default baseline allocation)
	public static final long DEFAULT_SCRATCH_BYTES = 64 * 1024L; // 64 KB default baseline
	private long maxScratchCapacityBytes = 512 * 1024 * 1024L; // 512 MB default cap
	private long allocatedScratchBytes = DEFAULT_SCRATCH_BYTES;

	// Multi-Threading (MT) & Degree of Parallelism (DoP) Control
	private int maxThreads = resolveDefaultMaxThreads();
	private final Map<Integer, Object> mockAttributes = new HashMap<>();

	public void setMockAttribute(int attrId, Object array) {
		mockAttributes.put(attrId, array);
	}

	public Object getMockAttribute(int attrId) {
		return mockAttributes.get(attrId);
	}

	private static int resolveDefaultMaxThreads() {
		String envDop = System.getenv("IMPULSE_MAX_DOP");
		if (envDop == null)
			envDop = System.getenv("IMPULSE_MAX_THREADS");
		if (envDop != null) {
			try {
				return Math.max(1, Integer.parseInt(envDop.trim()));
			} catch (NumberFormatException ignored) {
			}
		}
		return Runtime.getRuntime().availableProcessors();
	}

	public int getMaxThreads() {
		return maxThreads;
	}

	public int getMaxDop() {
		return maxThreads;
	}

	public void setMaxThreads(int maxThreads) {
		this.maxThreads = Math.max(1, maxThreads);
	}

	public void setMaxDop(int maxDop) {
		setMaxThreads(maxDop);
	}

	public long allocateScratch(long bytes) {
		long aligned = (bytes + 63) & ~63L;
		allocatedScratchBytes += aligned;
		return allocatedScratchBytes;
	}

	public long getAllocatedScratchBytes() {
		return allocatedScratchBytes;
	}

	public long getMaxScratchCapacityBytes() {
		return maxScratchCapacityBytes;
	}

	public void setMaxScratchCapacityBytes(long bytes) {
		this.maxScratchCapacityBytes = bytes;
	}

	public VmQueryContext(ImpulseGraphSnapshot snapshot, Arena arena) {
		this.snapshot = snapshot;
		this.arena = (arena != null) ? arena : Arena.ofShared();
	}

	public MemorySegment inlineDataSegment() {
		return inlineDataSeg;
	}

	public long inlineDataBytes() {
		return inlineDataBytes;
	}

	public void setInlineData(MemorySegment segment, long bytes) {
		this.inlineDataSeg = segment;
		this.inlineDataBytes = bytes;
	}

	public ImpulseGraphSnapshot snapshot() {
		return snapshot;
	}

	public void setSnapshot(ImpulseGraphSnapshot snapshot) {
		this.snapshot = snapshot;
	}

	public Arena arena() {
		return arena;
	}

	/**
	 * Allocate a 640-byte off-heap MemorySegment representing impulse_vm_state_t.
	 */
	public MemorySegment allocateStateSegment() {
		MemorySegment state = arena.allocate(VmStateLayout.VM_STATE_LAYOUT);
		state.fill((byte) 0);
		return state;
	}

	// --- Bitset & Relation Cache Management ---

	private static final Map<ImpulseGraphSnapshot, SnapshotMetadataCache> SNAPSHOT_CACHE = Collections
			.synchronizedMap(new WeakHashMap<>());

	private static final class SnapshotMetadataCache {
		final int maxNodeCount;
		final org.impulsegraph.api.RelationSnapshot[] byIndex;
		final Map<String, org.impulsegraph.api.RelationSnapshot> mapRef;

		SnapshotMetadataCache(ImpulseGraphSnapshot snap) {
			Map<String, org.impulsegraph.api.RelationSnapshot> all = snap.getAllRelationSnapshots();
			this.mapRef = all;
			if (all == null || all.isEmpty()) {
				this.maxNodeCount = 1024 * 1024;
				this.byIndex = new org.impulsegraph.api.RelationSnapshot[0];
			} else {
				int max = 0;
				org.impulsegraph.api.RelationSnapshot[] arr = new org.impulsegraph.api.RelationSnapshot[all.size()];
				int i = 0;
				for (org.impulsegraph.api.RelationSnapshot rel : all.values()) {
					if (rel != null) {
						max = Math.max(max, rel.getNodeCount());
					}
					arr[i++] = rel;
				}
				this.maxNodeCount = max > 0 ? max : 1024 * 1024;
				this.byIndex = arr;
			}
		}

		org.impulsegraph.api.RelationSnapshot resolve(ImpulseGraphSnapshot snap, int relId) {
			if (mapRef != null && !mapRef.isEmpty()) {
				String key = "rel_" + relId;
				org.impulsegraph.api.RelationSnapshot direct = mapRef.get(key);
				if (direct != null) {
					return direct;
				}
				if (relId >= 0 && relId < byIndex.length) {
					return byIndex[relId];
				}
			}
			return snap.getRelationSnapshot("rel_" + relId);
		}
	}

	private SnapshotMetadataCache metadataCache;

	private SnapshotMetadataCache getMetadataCache() {
		if (snapshot == null)
			return null;
		SnapshotMetadataCache c = this.metadataCache;
		if (c == null) {
			c = SNAPSHOT_CACHE.computeIfAbsent(snapshot, SnapshotMetadataCache::new);
			this.metadataCache = c;
		}
		return c;
	}

	public org.impulsegraph.api.RelationSnapshot resolveRelation(int relId) {
		if (snapshot == null)
			return null;
		SnapshotMetadataCache c = getMetadataCache();
		return c != null ? c.resolve(snapshot, relId) : null;
	}

	private int getMaxNodeCount(ImpulseGraphSnapshot snap) {
		if (snap == null)
			return 1024 * 1024;
		SnapshotMetadataCache c = (snap == this.snapshot) ? getMetadataCache()
				: SNAPSHOT_CACHE.computeIfAbsent(snap, SnapshotMetadataCache::new);
		return c != null ? c.maxNodeCount : 1024 * 1024;
	}

	public int acquireBitset() {
		int handle = freeBitsetHandles.nextSetBit(0);
		if (handle >= 0) {
			freeBitsetHandles.clear(handle);
			ImpulseBitSet bs = bitsets.get(handle);
			if (bs != null)
				bs.clear();
			return handle;
		}
		int newHandle = bitsets.size();
		bitsets.add(new OffHeapBitSet(arena, getMaxNodeCount(snapshot)));
		return newHandle;
	}

	public void releaseBitset(int handle) {
		if (handle >= 0 && handle < bitsets.size()) {
			ImpulseBitSet bs = bitsets.get(handle);
			if (bs != null)
				bs.clear();
			freeBitsetHandles.set(handle);
		}
	}

	public ImpulseBitSet getBitset(int handle) {
		if (handle >= 0 && handle < bitsets.size()) {
			return bitsets.get(handle);
		}
		return null;
	}

	// --- Vector Management ---

	public int registerIntVector(int[] vec) {
		int handle = intVectors.size();
		intVectors.add(vec);
		return handle;
	}

	public int acquireNodeVector(int[] vec) {
		return registerIntVector(vec);
	}

	public int[] getIntVector(int handle) {
		return (handle >= 0 && handle < intVectors.size()) ? intVectors.get(handle) : null;
	}

	public int[] getNodeVector(int handle) {
		return getIntVector(handle);
	}

	public int registerFloatVector(float[] vec) {
		int handle = floatVectors.size();
		floatVectors.add(vec);
		return handle;
	}

	public void setFloatVector(int handle, float[] vec) {
		if (handle >= 0 && handle < floatVectors.size()) {
			floatVectors.set(handle, vec);
		}
	}

	public int acquireFloatVector(int capacity) {
		return registerFloatVector(new float[capacity]);
	}

	public float[] getFloatVector(int handle) {
		return (handle >= 0 && handle < floatVectors.size()) ? floatVectors.get(handle) : null;
	}

	public int registerDoubleVector(double[] vec) {
		int handle = doubleVectors.size();
		doubleVectors.add(vec);
		return handle;
	}

	public void setDoubleVector(int handle, double[] vec) {
		if (handle >= 0 && handle < doubleVectors.size()) {
			doubleVectors.set(handle, vec);
		}
	}

	public double[] getDoubleVector(int handle) {
		return (handle >= 0 && handle < doubleVectors.size()) ? doubleVectors.get(handle) : null;
	}

	public int registerLongVector(long[] vec) {
		int handle = longVectors.size();
		longVectors.add(vec);
		return handle;
	}

	public long[] getLongVector(int handle) {
		return (handle >= 0 && handle < longVectors.size()) ? longVectors.get(handle) : null;
	}

	public int registerStringVector(String[] vec) {
		int handle = stringVectors.size();
		stringVectors.add(vec);
		return handle;
	}

	public String[] getStringVector(int handle) {
		return (handle >= 0 && handle < stringVectors.size()) ? stringVectors.get(handle) : null;
	}

	// --- Value Map Management ---

	public int registerValueMap(Map<Integer, Object> map) {
		int handle = valueMaps.size();
		valueMaps.add(map);
		return handle;
	}

	public Map<Integer, Object> getValueMap(int handle) {
		return (handle >= 0 && handle < valueMaps.size()) ? valueMaps.get(handle) : null;
	}

	@Override
	public void close() {
		bitsets.clear();
		floatVectors.clear();
		doubleVectors.clear();
		longVectors.clear();
		stringVectors.clear();
		valueMaps.clear();
	}
}
