package org.impulsegraph.vm;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.bitset.OffHeapBitSet;

/**
 * Runtime Execution Context for Impulse VM. Holds off-heap buffer pools,
 * bitsets, vectors, value maps, scratch buffers, and graph snapshot references.
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
	private byte finalScalarKind = 0; // 0 = Object/none, 1 = double, 2 = long, 3 = int
	private double finalScalarDouble = 0.0;
	private long finalScalarLong = 0L;
	private boolean inUse = false;

	public Object getFinalResult() {
		if (finalResult != null) {
			return finalResult;
		}
		return switch (finalScalarKind) {
			case 1 -> Double.valueOf(finalScalarDouble);
			case 2 -> Long.valueOf(finalScalarLong);
			case 3 -> Integer.valueOf((int) finalScalarLong);
			default -> null;
		};
	}

	public void setFinalResult(Object o) {
		finalResult = o;
		finalScalarKind = 0;
	}

	public void setFinalResultDouble(double d) {
		finalResult = null;
		finalScalarKind = 1;
		finalScalarDouble = d;
	}

	public void setFinalResultLong(long l) {
		finalResult = null;
		finalScalarKind = 2;
		finalScalarLong = l;
	}

	public void setFinalResultInt(int v) {
		finalResult = null;
		finalScalarKind = 3;
		finalScalarLong = v;
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

	// Auxiliary Vector & Value Map Pools
	private List<int[]> intVectors;
	private List<float[]> floatVectors;
	private List<double[]> doubleVectors;
	private List<long[]> longVectors;
	private List<String[]> stringVectors;
	private List<Map<Integer, Object>> valueMaps;
	private List<VmLaneFrontier> laneFrontiers;
	private VmBranchCoverageRecorder coverageRecorder;
	private VmFaultInjector faultInjector;

	public VmBranchCoverageRecorder getCoverageRecorder() {
		if (coverageRecorder == null) {
			coverageRecorder = new VmBranchCoverageRecorder();
		}
		return coverageRecorder;
	}

	public void setCoverageRecorder(VmBranchCoverageRecorder coverageRecorder) {
		this.coverageRecorder = coverageRecorder;
	}

	public VmFaultInjector getFaultInjector() {
		return faultInjector != null ? faultInjector : VmFaultInjector.global();
	}

	public void setFaultInjector(VmFaultInjector faultInjector) {
		this.faultInjector = faultInjector;
	}

	// Scratch Memory Accounting (64 KB default baseline allocation)
	public static final long DEFAULT_SCRATCH_BYTES = 64 * 1024L;
	private long maxScratchCapacityBytes = 512 * 1024 * 1024L;
	private long allocatedScratchBytes = DEFAULT_SCRATCH_BYTES;

	// Multi-Threading (MT) & Degree of Parallelism (DoP) Control
	private static final int DEFAULT_MAX_THREADS = resolveDefaultMaxThreads();
	private int maxThreads = DEFAULT_MAX_THREADS;

	// Zero-boxing primitive mock attribute table
	private int[] mockAttrKeys = new int[16];
	private Object[] mockAttrVals = new Object[16];
	private int mockAttrCount = 0;

	public void setMockAttribute(int attrId, Object array) {
		for (int i = 0; i < mockAttrCount; i++) {
			if (mockAttrKeys[i] == attrId) {
				mockAttrVals[i] = array;
				return;
			}
		}
		if (mockAttrCount >= mockAttrKeys.length) {
			int newCap = mockAttrKeys.length << 1;
			mockAttrKeys = Arrays.copyOf(mockAttrKeys, newCap);
			mockAttrVals = Arrays.copyOf(mockAttrVals, newCap);
		}
		mockAttrKeys[mockAttrCount] = attrId;
		mockAttrVals[mockAttrCount] = array;
		mockAttrCount++;
	}

	public Object getMockAttribute(int attrId) {
		for (int i = 0; i < mockAttrCount; i++) {
			if (mockAttrKeys[i] == attrId) {
				return mockAttrVals[i];
			}
		}
		return null;
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
		getFaultInjector().stepOrThrow("allocateScratch(" + bytes + ")");
		long aligned = (bytes + 63) & ~63L;
		allocatedScratchBytes += aligned;
		if (allocatedScratchBytes > maxScratchCapacityBytes) {
			throw new IllegalStateException("IMPULSE_VM_ERR_OUT_OF_BOUNDS: scratch capacity exceeded");
		}
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
		this.finalScalarKind = 0;
		this.maxScratchCapacityBytes = 512 * 1024 * 1024L;
		this.allocatedScratchBytes = DEFAULT_SCRATCH_BYTES;
		this.maxThreads = DEFAULT_MAX_THREADS;
		this.mockAttrCount = 0;
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

	/**
	 * Thread-local buffer pool for zero-allocation reuse of vector arrays,
	 * multi-lane frontiers, and scratch workspaces across query invocations.
	 */
	public static final class BfsScratch {
		public long[] visitedWords = new long[1024];
		public int[] queue = new int[4096];

		void ensureCapacity(int n) {
			int words = (n + 63) >>> 6;
			if (visitedWords.length < words) {
				visitedWords = new long[Math.max(words, visitedWords.length << 1)];
			}
			Arrays.fill(visitedWords, 0, words, 0L);
			if (queue.length < n) {
				queue = new int[Math.max(n, queue.length << 1)];
			}
		}
	}

	private static final ThreadLocal<BfsScratch> TL_BFS_SCRATCH = ThreadLocal.withInitial(BfsScratch::new);

	public static BfsScratch acquireThreadLocalBfsScratch(int n) {
		BfsScratch s = TL_BFS_SCRATCH.get();
		s.ensureCapacity(n);
		return s;
	}

	private static final class ThreadLocalBufferPool {
		final ArrayList<int[]> freeInts = new ArrayList<>(16);
		final ArrayList<float[]> freeFloats = new ArrayList<>(16);
		final ArrayList<double[]> freeDoubles = new ArrayList<>(8);
		final ArrayList<long[]> freeLongs = new ArrayList<>(16);
		final ArrayList<String[]> freeStrings = new ArrayList<>(4);
		final ArrayList<VmLaneFrontier> freeFrontiers = new ArrayList<>(16);
		final ArrayList<long[]> freeFrontierBuffers = new ArrayList<>(16);

		final int[][] scratchInts = new int[4][1024];
		final long[][] scratchLongs = new long[4][1024];
		final float[][] scratchFloats = new float[4][1024];
		final AtomicInteger atomicCounter = new AtomicInteger(0);
		ImpulseBitSet[] bitsetArray = new ImpulseBitSet[64];
		AtomicIntegerArray atomicIntArray = new AtomicIntegerArray(1024);

		int[] acquireInt(int exactLen) {
			for (int i = freeInts.size() - 1; i >= 0; i--) {
				int[] arr = freeInts.get(i);
				if (arr.length == exactLen) {
					freeInts.remove(i);
					Arrays.fill(arr, 0);
					return arr;
				}
			}
			return new int[exactLen];
		}

		void recycleInt(int[] arr) {
			if (arr != null && freeInts.size() < 32) {
				freeInts.add(arr);
			}
		}

		float[] acquireFloat(int exactLen) {
			for (int i = freeFloats.size() - 1; i >= 0; i--) {
				float[] arr = freeFloats.get(i);
				if (arr.length == exactLen) {
					freeFloats.remove(i);
					Arrays.fill(arr, 0.0f);
					return arr;
				}
			}
			return new float[exactLen];
		}

		void recycleFloat(float[] arr) {
			if (arr != null && freeFloats.size() < 32) {
				freeFloats.add(arr);
			}
		}

		double[] acquireDouble(int exactLen) {
			for (int i = freeDoubles.size() - 1; i >= 0; i--) {
				double[] arr = freeDoubles.get(i);
				if (arr.length == exactLen) {
					freeDoubles.remove(i);
					Arrays.fill(arr, 0.0);
					return arr;
				}
			}
			return new double[exactLen];
		}

		void recycleDouble(double[] arr) {
			if (arr != null && freeDoubles.size() < 16) {
				freeDoubles.add(arr);
			}
		}

		long[] acquireLong(int exactLen) {
			for (int i = freeLongs.size() - 1; i >= 0; i--) {
				long[] arr = freeLongs.get(i);
				if (arr.length == exactLen) {
					freeLongs.remove(i);
					Arrays.fill(arr, 0L);
					return arr;
				}
			}
			return new long[exactLen];
		}

		void recycleLong(long[] arr) {
			if (arr != null && freeLongs.size() < 32) {
				freeLongs.add(arr);
			}
		}

		String[] acquireString(int exactLen) {
			for (int i = freeStrings.size() - 1; i >= 0; i--) {
				String[] arr = freeStrings.get(i);
				if (arr.length == exactLen) {
					freeStrings.remove(i);
					Arrays.fill(arr, null);
					return arr;
				}
			}
			return new String[exactLen];
		}

		void recycleString(String[] arr) {
			if (arr != null && freeStrings.size() < 8) {
				freeStrings.add(arr);
			}
		}

		VmLaneFrontier acquireFrontier(VmLaneFrontier.LaneWidth width, int numLanes, long activeMask, int nodeCount) {
			VmLaneFrontier lf = freeFrontiers.isEmpty()
					? new VmLaneFrontier(new int[8], new long[8][], new int[8])
					: freeFrontiers.remove(freeFrontiers.size() - 1);
			long[] buf = null;
			for (int i = freeFrontierBuffers.size() - 1; i >= 0; i--) {
				long[] cand = freeFrontierBuffers.get(i);
				if (cand.length == nodeCount) {
					buf = freeFrontierBuffers.remove(i);
					break;
				}
			}
			if (buf == null) {
				buf = new long[nodeCount];
			}
			lf.reset(width, numLanes, activeMask, nodeCount, buf);
			return lf;
		}

		void recycleFrontier(VmLaneFrontier lf) {
			if (lf != null) {
				if (lf.lanes() != null && freeFrontierBuffers.size() < 32) {
					freeFrontierBuffers.add(lf.lanes());
				}
				if (freeFrontiers.size() < 32) {
					freeFrontiers.add(lf);
				}
			}
		}
	}

	private static final ThreadLocal<ThreadLocalBufferPool> TL_BUFFER_POOL = ThreadLocal
			.withInitial(ThreadLocalBufferPool::new);

	private long[] streamTouchedMask;

	public long[] initStreamTouchedMask(int minCap) {
		this.streamTouchedMask = scratchLong(3, minCap);
		return this.streamTouchedMask;
	}

	public long[] currentStreamTouchedMask() {
		if (this.streamTouchedMask == null) {
			this.streamTouchedMask = scratchLong(3, 65536);
		}
		return this.streamTouchedMask;
	}

	public int[] scratchInt(int slot, int minCap) {
		ThreadLocalBufferPool pool = TL_BUFFER_POOL.get();
		int[] arr = pool.scratchInts[slot];
		if (arr.length < minCap) {
			arr = new int[Math.max(minCap, arr.length << 1)];
			pool.scratchInts[slot] = arr;
		}
		Arrays.fill(arr, 0, minCap, 0);
		return arr;
	}

	public int[] peekScratchInt(int slot) {
		return TL_BUFFER_POOL.get().scratchInts[slot];
	}

	public long[] scratchLong(int slot, int minCap) {
		ThreadLocalBufferPool pool = TL_BUFFER_POOL.get();
		long[] arr = pool.scratchLongs[slot];
		if (arr.length < minCap) {
			arr = new long[Math.max(minCap, arr.length << 1)];
			pool.scratchLongs[slot] = arr;
		}
		Arrays.fill(arr, 0, minCap, 0L);
		return arr;
	}

	public float[] scratchFloat(int slot, int minCap) {
		ThreadLocalBufferPool pool = TL_BUFFER_POOL.get();
		float[] arr = pool.scratchFloats[slot];
		if (arr.length < minCap) {
			arr = new float[Math.max(minCap, arr.length << 1)];
			pool.scratchFloats[slot] = arr;
		}
		Arrays.fill(arr, 0, minCap, 0.0f);
		return arr;
	}

	public AtomicInteger scratchAtomicCounter() {
		AtomicInteger ai = TL_BUFFER_POOL.get().atomicCounter;
		ai.set(0);
		return ai;
	}

	public ImpulseBitSet[] scratchBitsetArray(int numThreads) {
		ThreadLocalBufferPool pool = TL_BUFFER_POOL.get();
		if (pool.bitsetArray.length < numThreads) {
			pool.bitsetArray = new ImpulseBitSet[Math.max(numThreads, pool.bitsetArray.length << 1)];
		}
		return pool.bitsetArray;
	}

	public AtomicIntegerArray scratchAtomicIntArray(int minCap) {
		ThreadLocalBufferPool pool = TL_BUFFER_POOL.get();
		if (pool.atomicIntArray.length() < minCap) {
			pool.atomicIntArray = new AtomicIntegerArray(Math.max(minCap, pool.atomicIntArray.length() << 1));
		}
		return pool.atomicIntArray;
	}

	long[] allocateRawLongBuffer(int cap) {
		return new long[cap];
	}

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
		getFaultInjector().stepOrThrow("acquireBitset");
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

	public int bitsetCount() {
		return bitsets.size();
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
		getFaultInjector().stepOrThrow("registerIntVector");
		if (intVectors == null) {
			intVectors = new ArrayList<>(4);
		}
		int handle = intVectors.size();
		intVectors.add(vec);
		return handle;
	}

	public int acquireIntVector(int capacity) {
		return registerIntVector(TL_BUFFER_POOL.get().acquireInt(capacity));
	}

	public int intVectorCount() {
		return intVectors != null ? intVectors.size() : 0;
	}

	public int acquireNodeVector(int[] vec) {
		return registerIntVector(vec);
	}

	public int acquireNodeVector(int capacity) {
		return acquireIntVector(capacity);
	}

	public int[] getIntVector(int handle) {
		return (intVectors != null && handle >= 0 && handle < intVectors.size()) ? intVectors.get(handle) : null;
	}

	public int[] getNodeVector(int handle) {
		return getIntVector(handle);
	}

	public int registerFloatVector(float[] vec) {
		getFaultInjector().stepOrThrow("registerFloatVector");
		if (floatVectors == null) {
			floatVectors = new ArrayList<>(4);
		}
		int handle = floatVectors.size();
		floatVectors.add(vec);
		return handle;
	}

	public int floatVectorCount() {
		return floatVectors != null ? floatVectors.size() : 0;
	}

	public void setFloatVector(int handle, float[] vec) {
		if (floatVectors != null && handle >= 0 && handle < floatVectors.size()) {
			floatVectors.set(handle, vec);
		}
	}

	public int acquireFloatVector(int capacity) {
		return registerFloatVector(TL_BUFFER_POOL.get().acquireFloat(capacity));
	}

	public float[] getFloatVector(int handle) {
		return (floatVectors != null && handle >= 0 && handle < floatVectors.size()) ? floatVectors.get(handle) : null;
	}

	public int registerDoubleVector(double[] vec) {
		getFaultInjector().stepOrThrow("registerDoubleVector");
		if (doubleVectors == null) {
			doubleVectors = new ArrayList<>(4);
		}
		int handle = doubleVectors.size();
		doubleVectors.add(vec);
		return handle;
	}

	public int acquireDoubleVector(int capacity) {
		return registerDoubleVector(TL_BUFFER_POOL.get().acquireDouble(capacity));
	}

	public int doubleVectorCount() {
		return doubleVectors != null ? doubleVectors.size() : 0;
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
		getFaultInjector().stepOrThrow("registerLongVector");
		if (longVectors == null) {
			longVectors = new ArrayList<>(4);
		}
		int handle = longVectors.size();
		longVectors.add(vec);
		return handle;
	}

	public int acquireLongVector(int capacity) {
		return registerLongVector(TL_BUFFER_POOL.get().acquireLong(capacity));
	}

	public int longVectorCount() {
		return longVectors != null ? longVectors.size() : 0;
	}

	public long[] getLongVector(int handle) {
		return (longVectors != null && handle >= 0 && handle < longVectors.size()) ? longVectors.get(handle) : null;
	}

	public int registerStringVector(String[] vec) {
		getFaultInjector().stepOrThrow("registerStringVector");
		if (stringVectors == null) {
			stringVectors = new ArrayList<>(4);
		}
		int handle = stringVectors.size();
		stringVectors.add(vec);
		return handle;
	}

	public int acquireStringVector(int capacity) {
		return registerStringVector(TL_BUFFER_POOL.get().acquireString(capacity));
	}

	public int stringVectorCount() {
		return stringVectors != null ? stringVectors.size() : 0;
	}

	public String[] getStringVector(int handle) {
		return (stringVectors != null && handle >= 0 && handle < stringVectors.size())
				? stringVectors.get(handle)
				: null;
	}

	// --- Value Map & Multi-Lane Frontier Management ---

	public int registerValueMap(Map<Integer, Object> map) {
		getFaultInjector().stepOrThrow("registerValueMap");
		if (valueMaps == null) {
			valueMaps = new ArrayList<>(4);
		}
		int handle = valueMaps.size();
		valueMaps.add(map);
		return handle;
	}

	public int materializeValueMap(int[] scratch, int count, byte valsType, int valsHandle) {
		Map<Integer, Object> outMap = new LinkedHashMap<>(Math.max(4, count));
		if (valsType == VmRegisterType.TYPE_FLOAT_VECTOR) {
			float[] fv = getFloatVector(valsHandle);
			if (fv != null) {
				boolean positional = (fv.length == count);
				for (int i = 0; i < count; i++) {
					int u = scratch[i];
					if (u >= 0 && u < fv.length && !positional) {
						outMap.put(u, fv[u]);
					} else if (i < fv.length) {
						outMap.put(u, fv[i]);
					}
				}
			}
		} else if (valsType == VmRegisterType.TYPE_DOUBLE_VECTOR) {
			double[] dv = getDoubleVector(valsHandle);
			if (dv != null) {
				boolean positional = (dv.length == count);
				for (int i = 0; i < count; i++) {
					int u = scratch[i];
					if (u >= 0 && u < dv.length && !positional) {
						outMap.put(u, dv[u]);
					} else if (i < dv.length) {
						outMap.put(u, dv[i]);
					}
				}
			}
		} else if (valsType == VmRegisterType.TYPE_NODE_VECTOR) {
			int[] iv = getIntVector(valsHandle);
			if (iv != null) {
				boolean positional = (iv.length == count);
				for (int i = 0; i < count; i++) {
					int u = scratch[i];
					if (u >= 0 && u < iv.length && !positional) {
						outMap.put(u, (long) iv[u]);
					} else if (i < iv.length) {
						outMap.put(u, (long) iv[i]);
					}
				}
			}
		} else if (valsType == VmRegisterType.TYPE_UINT64_VECTOR) {
			long[] lv = getLongVector(valsHandle);
			if (lv != null) {
				boolean positional = (lv.length == count);
				for (int i = 0; i < count; i++) {
					int u = scratch[i];
					if (u >= 0 && u < lv.length && !positional) {
						outMap.put(u, lv[u]);
					} else if (i < lv.length) {
						outMap.put(u, lv[i]);
					}
				}
			}
		} else if (valsType == VmRegisterType.TYPE_STRING_VECTOR) {
			String[] sv = getStringVector(valsHandle);
			if (sv != null) {
				boolean positional = (sv.length == count);
				for (int i = 0; i < count; i++) {
					int u = scratch[i];
					if (u >= 0 && u < sv.length && !positional) {
						outMap.put(u, sv[u]);
					} else if (i < sv.length) {
						outMap.put(u, sv[i]);
					}
				}
			}
		}
		return registerValueMap(outMap);
	}

	public int valueMapCount() {
		return valueMaps != null ? valueMaps.size() : 0;
	}

	public Map<Integer, Object> getValueMap(int handle) {
		return (valueMaps != null && handle >= 0 && handle < valueMaps.size()) ? valueMaps.get(handle) : null;
	}

	public int registerLaneFrontier(VmLaneFrontier frontier) {
		getFaultInjector().stepOrThrow("registerLaneFrontier");
		if (laneFrontiers == null) {
			laneFrontiers = new ArrayList<>(4);
		}
		int handle = laneFrontiers.size();
		laneFrontiers.add(frontier);
		return handle;
	}

	public int acquireLaneFrontier(VmLaneFrontier.LaneWidth width, int numLanes, long activeMask, int nodeCount) {
		VmLaneFrontier lf = TL_BUFFER_POOL.get().acquireFrontier(width, numLanes, activeMask, nodeCount);
		return registerLaneFrontier(lf);
	}

	public void setLaneFrontier(int handle, VmLaneFrontier frontier) {
		if (laneFrontiers != null && handle >= 0 && handle < laneFrontiers.size()) {
			laneFrontiers.set(handle, frontier);
		}
	}

	public VmLaneFrontier getLaneFrontier(int handle) {
		return (laneFrontiers != null && handle >= 0 && handle < laneFrontiers.size())
				? laneFrontiers.get(handle)
				: null;
	}

	public int laneFrontierCount() {
		return laneFrontiers != null ? laneFrontiers.size() : 0;
	}

	/**
	 * Initialize a mock CSR/CSC relation slot from inline bytecode data (opcode
	 * 0x09 OP_INIT_MOCK_GRAPH).
	 */
	public void initMockGraph(int slotId, int offset, int nodeCount) {
		MemorySegment inlineSeg = inlineDataSegment();
		if (inlineSeg == null) {
			throw new IllegalStateException("IMPULSE_VM_ERR_NULL_SNAPSHOT");
		}
		int[] offsets = new int[nodeCount + 1];
		ValueLayout.OfInt layoutInt = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i <= nodeCount; i++) {
			offsets[i] = inlineSeg.get(layoutInt, offset + i * 4L);
		}
		int numTargets = offsets[nodeCount];
		int[] targets = new int[numTargets];
		long targetsOffset = offset + (nodeCount + 1) * 4L;
		for (int i = 0; i < numTargets; i++) {
			targets[i] = inlineSeg.get(layoutInt, targetsOffset + i * 4L);
		}
		RelationSnapshot rel = new MockRelationSnapshot(Arena.ofAuto(), nodeCount, numTargets, offsets, targets);
		int targetDomainCount = nodeCount;
		for (int t : targets) {
			if (t >= targetDomainCount) {
				targetDomainCount = t + 1;
			}
		}
		int[] inDegrees = new int[targetDomainCount];
		for (int t : targets) {
			if (t >= 0 && t < targetDomainCount) {
				inDegrees[t]++;
			}
		}
		int[] cscOffsets = new int[targetDomainCount + 1];
		cscOffsets[0] = 0;
		for (int i = 0; i < targetDomainCount; i++) {
			cscOffsets[i + 1] = cscOffsets[i] + inDegrees[i];
		}
		int[] cscCur = cscOffsets.clone();
		int[] cscTargets = new int[numTargets];
		for (int u = 0; u < nodeCount; u++) {
			int start = offsets[u];
			int end = offsets[u + 1];
			for (int idx = start; idx < end; idx++) {
				int v = targets[idx];
				if (v >= 0 && v < targetDomainCount) {
					cscTargets[cscCur[v]++] = u;
				}
			}
		}
		MemorySegment cscOffsetsSeg = Arena.ofAuto().allocate(cscOffsets.length * 4L, 4);
		for (int i = 0; i < cscOffsets.length; i++) {
			cscOffsetsSeg.setAtIndex(ValueLayout.JAVA_INT, i, cscOffsets[i]);
		}
		MemorySegment cscTargetsSeg = Arena.ofAuto().allocate(cscTargets.length * 4L, 4);
		for (int i = 0; i < cscTargets.length; i++) {
			cscTargetsSeg.setAtIndex(ValueLayout.JAVA_INT, i, cscTargets[i]);
		}
		rel.setCscSegments(cscOffsetsSeg, cscTargetsSeg);
		if (getSnapshot() instanceof MockImpulseGraphSnapshot mockSnap) {
			setSnapshot(mockSnap.withSlot(slotId, rel));
		} else {
			setSnapshot(new MockImpulseGraphSnapshot(Map.of()).withSlot(slotId, rel));
		}
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
		ThreadLocalBufferPool bufPool = TL_BUFFER_POOL.get();
		if (intVectors != null) {
			for (int i = 0, n = intVectors.size(); i < n; i++) {
				int[] arr = intVectors.get(i);
				if (arr != finalResult) {
					bufPool.recycleInt(arr);
				}
			}
			intVectors.clear();
		}
		if (floatVectors != null) {
			for (int i = 0, n = floatVectors.size(); i < n; i++) {
				float[] arr = floatVectors.get(i);
				if (arr != finalResult) {
					bufPool.recycleFloat(arr);
				}
			}
			floatVectors.clear();
		}
		if (doubleVectors != null) {
			for (int i = 0, n = doubleVectors.size(); i < n; i++) {
				double[] arr = doubleVectors.get(i);
				if (arr != finalResult) {
					bufPool.recycleDouble(arr);
				}
			}
			doubleVectors.clear();
		}
		if (longVectors != null) {
			for (int i = 0, n = longVectors.size(); i < n; i++) {
				long[] arr = longVectors.get(i);
				if (arr != finalResult) {
					bufPool.recycleLong(arr);
				}
			}
			longVectors.clear();
		}
		if (stringVectors != null) {
			for (int i = 0, n = stringVectors.size(); i < n; i++) {
				String[] arr = stringVectors.get(i);
				if (arr != finalResult) {
					bufPool.recycleString(arr);
				}
			}
			stringVectors.clear();
		}
		if (valueMaps != null) {
			valueMaps.clear();
		}
		if (laneFrontiers != null) {
			for (int i = 0, n = laneFrontiers.size(); i < n; i++) {
				VmLaneFrontier lf = laneFrontiers.get(i);
				if (lf != finalResult) {
					bufPool.recycleFrontier(lf);
				}
			}
			laneFrontiers.clear();
		}
		this.mockAttrCount = 0;
		this.finalResult = null;
		this.finalScalarKind = 0;
		this.snapshot = null;
		this.inlineDataSeg = null;
		this.inlineDataBytes = 0L;
		this.stringPool = List.of();
		this.inUse = false;
	}
}
