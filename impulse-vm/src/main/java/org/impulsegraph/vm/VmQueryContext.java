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
	private List<String> stringPool = List.of();
	private Arena arena;
	private MemorySegment inlineDataSeg = null;
	private Object finalResult = null;
	private boolean inUse = false;

	public Object getFinalResult() {
		return finalResult;
	}
	public void setFinalResult(Object o) {
		finalResult = o;
	}

	private long inlineDataBytes = 0;

	// Bitset Pool
	private final List<ImpulseBitSet> bitsets = new ArrayList<>(4);
	private final java.util.BitSet freeBitsetHandles = new java.util.BitSet();

	public void setStringPool(List<String> pool) {
		this.stringPool = (pool != null) ? pool : List.of();
	}

	public String getString(int index) {
		if (index >= 0 && index < stringPool.size()) {
			return stringPool.get(index);
		}
		return null;
	}

	// Lazy Auxiliary Vector & Value Map Pools (allocated only when vector/map
	// opcodes execute)
	private List<int[]> intVectors;
	private List<float[]> floatVectors;
	private List<double[]> doubleVectors;
	private List<long[]> longVectors;
	private List<String[]> stringVectors;
	private List<Map<Integer, Object>> valueMaps;

	// Scratch Memory Accounting (64 KB default baseline allocation)
	public static final long DEFAULT_SCRATCH_BYTES = 64 * 1024L; // 64 KB default baseline
	private long maxScratchCapacityBytes = 512 * 1024 * 1024L; // 512 MB default cap
	private long allocatedScratchBytes = DEFAULT_SCRATCH_BYTES;

	// Multi-Threading (MT) & Degree of Parallelism (DoP) Control
	private static final int DEFAULT_MAX_THREADS = resolveDefaultMaxThreads();
	private int maxThreads = DEFAULT_MAX_THREADS;
	private Map<Integer, Object> mockAttributes;

	public void setMockAttribute(int attrId, Object array) {
		if (mockAttributes == null) {
			mockAttributes = new HashMap<>();
		}
		mockAttributes.put(attrId, array);
	}

	public Object getMockAttribute(int attrId) {
		return mockAttributes != null ? mockAttributes.get(attrId) : null;
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

	private static final Arena GLOBAL_POOL_ARENA = Arena.ofAuto();

	public VmQueryContext(ImpulseGraphSnapshot snapshot, Arena arena) {
		this.snapshot = snapshot;
		this.arena = (arena != null) ? arena : GLOBAL_POOL_ARENA;
	}

	private static final ThreadLocal<VmQueryContext> TL_CONTEXT = ThreadLocal
			.withInitial(() -> new VmQueryContext(null, GLOBAL_POOL_ARENA));

	/**
	 * Acquire a thread-local {@link VmQueryContext} for zero-allocation hot-path
	 * execution, falling back to a fresh instance if the current thread is already
	 * executing a query re-entrantly.
	 */
	public static VmQueryContext acquire(ImpulseGraphSnapshot snapshot, Arena arena) {
		VmQueryContext ctx = TL_CONTEXT.get();
		if (!ctx.inUse) {
			ctx.inUse = true;
			ctx.resetForAcquire(snapshot, arena);
			return ctx;
		}
		VmQueryContext fallback = new VmQueryContext(snapshot, arena);
		fallback.inUse = true;
		return fallback;
	}

	private void resetForAcquire(ImpulseGraphSnapshot snapshot, Arena arena) {
		this.snapshot = snapshot;
		this.arena = (arena != null) ? arena : GLOBAL_POOL_ARENA;
		this.stringPool = List.of();
		this.inlineDataSeg = null;
		this.inlineDataBytes = 0L;
		this.finalResult = null;
		this.maxScratchCapacityBytes = 512 * 1024 * 1024L;
		this.allocatedScratchBytes = DEFAULT_SCRATCH_BYTES;
		this.maxThreads = DEFAULT_MAX_THREADS;
	}

	public boolean isInUse() {
		return inUse;
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

	private static final ThreadLocal<ArrayDeque<MemorySegment>> TL_STATE_POOL = ThreadLocal
			.withInitial(() -> new ArrayDeque<>(4));

	private static final class ThreadLocalBitsetPool {
		private static final long SLAB_BYTES = 1024L * 1024L;
		final int bitCount;
		final long byteSize;
		final ArrayDeque<OffHeapBitSet> freeList = new ArrayDeque<>(16);
		MemorySegment currentSlab;
		long slabOffset;

		ThreadLocalBitsetPool(int bitCount) {
			this.bitCount = bitCount;
			int words = (bitCount + 63) / 64;
			this.byteSize = ((long) words * 8L + 63L) & ~63L;
		}

		OffHeapBitSet acquire() {
			OffHeapBitSet bs = freeList.pollFirst();
			if (bs != null) {
				bs.clear();
				return bs;
			}
			if (byteSize >= SLAB_BYTES) {
				return new OffHeapBitSet(GLOBAL_POOL_ARENA, bitCount);
			}
			if (currentSlab == null || slabOffset + byteSize > SLAB_BYTES) {
				currentSlab = Arena.ofAuto().allocate(SLAB_BYTES, 64);
				slabOffset = 0L;
			}
			MemorySegment slice = currentSlab.asSlice(slabOffset, byteSize);
			slabOffset += byteSize;
			return new OffHeapBitSet(slice, bitCount, true);
		}

		void recycleScratch(OffHeapBitSet bs) {
			if (freeList.size() < 32) {
				bs.clear();
				freeList.addFirst(bs);
			}
		}
	}

	private static final ThreadLocal<Map<Integer, ThreadLocalBitsetPool>> TL_BITSET_POOL = ThreadLocal
			.withInitial(HashMap::new);

	private MemorySegment acquiredStateSegment;
	private int acquiredBitsetMaxNodes;

	/**
	 * Allocate a 640-byte off-heap MemorySegment representing impulse_vm_state_t.
	 */
	public MemorySegment allocateStateSegment() {
		ArrayDeque<MemorySegment> pool = TL_STATE_POOL.get();
		MemorySegment state = pool.pollFirst();
		if (state == null) {
			state = GLOBAL_POOL_ARENA.allocate(VmStateLayout.VM_STATE_LAYOUT);
		}
		state.fill((byte) 0);
		this.acquiredStateSegment = state;
		return state;
	}

	// --- Bitset & Relation Resolution ---

	public org.impulsegraph.api.RelationSnapshot resolveRelation(int relId) {
		return snapshot != null ? snapshot.getRelationSnapshot(relId) : null;
	}

	private static int getMaxNodeCount(ImpulseGraphSnapshot snap) {
		return snap != null ? snap.getMaxNodeCount() : 1024 * 1024;
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
		int maxNodes = getMaxNodeCount(snapshot);
		this.acquiredBitsetMaxNodes = maxNodes;
		ThreadLocalBitsetPool pool = TL_BITSET_POOL.get().computeIfAbsent(maxNodes, ThreadLocalBitsetPool::new);
		int newHandle = bitsets.size();
		bitsets.add(pool.acquire());
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
		if (intVectors == null) {
			intVectors = new ArrayList<>(4);
		}
		int handle = intVectors.size();
		intVectors.add(vec);
		return handle;
	}

	public int acquireNodeVector(int[] vec) {
		return registerIntVector(vec);
	}

	public int[] getIntVector(int handle) {
		return (intVectors != null && handle >= 0 && handle < intVectors.size()) ? intVectors.get(handle) : null;
	}

	public int[] getNodeVector(int handle) {
		return getIntVector(handle);
	}

	public int registerFloatVector(float[] vec) {
		if (floatVectors == null) {
			floatVectors = new ArrayList<>(4);
		}
		int handle = floatVectors.size();
		floatVectors.add(vec);
		return handle;
	}

	public void setFloatVector(int handle, float[] vec) {
		if (floatVectors != null && handle >= 0 && handle < floatVectors.size()) {
			floatVectors.set(handle, vec);
		}
	}

	public int acquireFloatVector(int capacity) {
		return registerFloatVector(new float[capacity]);
	}

	public float[] getFloatVector(int handle) {
		return (floatVectors != null && handle >= 0 && handle < floatVectors.size()) ? floatVectors.get(handle) : null;
	}

	public int registerDoubleVector(double[] vec) {
		if (doubleVectors == null) {
			doubleVectors = new ArrayList<>(4);
		}
		int handle = doubleVectors.size();
		doubleVectors.add(vec);
		return handle;
	}

	public void setDoubleVector(int handle, double[] vec) {
		if (doubleVectors != null && handle >= 0 && handle < doubleVectors.size()) {
			doubleVectors.set(handle, vec);
		}
	}

	public double[] getDoubleVector(int handle) {
		return (doubleVectors != null && handle >= 0 && handle < doubleVectors.size())
				? doubleVectors.get(handle)
				: null;
	}

	public int registerLongVector(long[] vec) {
		if (longVectors == null) {
			longVectors = new ArrayList<>(4);
		}
		int handle = longVectors.size();
		longVectors.add(vec);
		return handle;
	}

	public long[] getLongVector(int handle) {
		return (longVectors != null && handle >= 0 && handle < longVectors.size()) ? longVectors.get(handle) : null;
	}

	public int registerStringVector(String[] vec) {
		if (stringVectors == null) {
			stringVectors = new ArrayList<>(4);
		}
		int handle = stringVectors.size();
		stringVectors.add(vec);
		return handle;
	}

	public String[] getStringVector(int handle) {
		return (stringVectors != null && handle >= 0 && handle < stringVectors.size())
				? stringVectors.get(handle)
				: null;
	}

	// --- Value Map Management ---

	public int registerValueMap(Map<Integer, Object> map) {
		if (valueMaps == null) {
			valueMaps = new ArrayList<>(4);
		}
		int handle = valueMaps.size();
		valueMaps.add(map);
		return handle;
	}

	public Map<Integer, Object> getValueMap(int handle) {
		return (valueMaps != null && handle >= 0 && handle < valueMaps.size()) ? valueMaps.get(handle) : null;
	}

	@Override
	public void close() {
		if (acquiredStateSegment != null) {
			ArrayDeque<MemorySegment> statePool = TL_STATE_POOL.get();
			if (statePool.size() < 8) {
				statePool.addFirst(acquiredStateSegment);
			}
			acquiredStateSegment = null;
		}
		if (!bitsets.isEmpty() && acquiredBitsetMaxNodes > 0) {
			ThreadLocalBitsetPool pool = TL_BITSET_POOL.get().get(acquiredBitsetMaxNodes);
			if (pool != null) {
				for (int i = 0, n = bitsets.size(); i < n; i++) {
					ImpulseBitSet bs = bitsets.get(i);
					if (bs instanceof OffHeapBitSet obs && obs != finalResult) {
						pool.recycleScratch(obs);
					}
				}
			}
		}
		bitsets.clear();
		freeBitsetHandles.clear();
		if (intVectors != null) {
			intVectors.clear();
		}
		if (floatVectors != null) {
			floatVectors.clear();
		}
		if (doubleVectors != null) {
			doubleVectors.clear();
		}
		if (longVectors != null) {
			longVectors.clear();
		}
		if (stringVectors != null) {
			stringVectors.clear();
		}
		if (valueMaps != null) {
			valueMaps.clear();
		}
		if (mockAttributes != null) {
			mockAttributes.clear();
		}
		this.finalResult = null;
		this.snapshot = null;
		this.inlineDataSeg = null;
		this.inlineDataBytes = 0L;
		this.stringPool = List.of();
		this.inUse = false;
	}
}
