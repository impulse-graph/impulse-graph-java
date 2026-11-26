package org.impulsegraph.vm.v11;

import java.util.Arrays;

/**
 * Caller-Owned Zero-Allocation Buffer Arena for ImpulseVM v0.11.0 (Java).
 *
 * <p>
 * Holds grow-once, reuse-forever pools of 0-based primitive arrays and reusable
 * {@link V11Vector} / {@link V11Frontier} descriptors. Calling {@link #reset()}
 * between queries rewinds all cursors to 0 in O(1) with zero heap allocations,
 * enabling strict 0-byte steady-state execution across all Pillars 1–5.
 */
public final class V11Arena {

	private static final int INITIAL_SLOTS = 64;

	private long[][] longPool = new long[INITIAL_SLOTS][];
	private int longPos;

	private double[][] doublePool = new double[INITIAL_SLOTS][];
	private int doublePos;

	private int[][] intPool = new int[INITIAL_SLOTS][];
	private int intPos;

	private byte[][] bytePool = new byte[INITIAL_SLOTS][];
	private int bytePos;

	private boolean[][] boolPool = new boolean[INITIAL_SLOTS][];
	private int boolPos;

	private V11Vector[] vecPool = new V11Vector[INITIAL_SLOTS];
	private int vecPos;

	private V11Frontier[] frontierPool = new V11Frontier[32];
	private int frontierPos;

	// Reusable validator scratch (avoids per-call allocations in V11Validator)
	public boolean[] valInMorsel = new boolean[256];
	public boolean[] valHasJoin = new boolean[256];
	public int[] valJoinMask = new int[256];
	public boolean[] valVisited = new boolean[256];
	public int[] valStack = new int[256];

	// Reusable morsel per-register scratch tables (zero-alloc across morsel
	// invocations)
	public final V11Vector[] morselGatherVecs = new V11Vector[16];
	public final V11Vector[] morselCmpVecs = new V11Vector[16];

	// Pre-allocated flat reverse-adjacency scratch for CSC builds
	public int[] revOff = new int[64];
	public int[] revSrc = new int[64];
	public int[] revEid = new int[64];

	public V11Arena(int preallocLen) {
		for (int i = 0; i < 16; i++) {
			vecPool[i] = new V11Vector();
		}
		// Buffers grow-once on first use and are reused forever via reset(),
		// preserving 0 B/query steady-state without eager multi-MB startup bloat per
		// thread.
	}

	/** O(1) zero-allocation rewind between queries. */
	public void reset() {
		longPos = 0;
		doublePos = 0;
		intPos = 0;
		bytePos = 0;
		boolPos = 0;
		vecPos = 0;
		frontierPos = 0;
	}

	public long[] allocLong(int len) {
		int idx = longPos++;
		if (idx >= longPool.length) {
			longPool = Arrays.copyOf(longPool, longPool.length * 2);
		}
		long[] arr = longPool[idx];
		if (arr == null || arr.length < len) {
			arr = new long[Math.max(len, 64)];
			longPool[idx] = arr;
		}
		return arr;
	}

	public long[] allocLongZeroed(int len) {
		long[] arr = allocLong(len);
		Arrays.fill(arr, 0, len, 0L);
		return arr;
	}

	public long[] allocValidAllTrue(int len) {
		int words = Math.max(1, (len + 63) >>> 6);
		long[] bits = allocLong(words);
		Arrays.fill(bits, 0, words, -1L);
		int rem = len & 63;
		if (rem != 0 && words > 0) {
			bits[words - 1] = (1L << rem) - 1L;
		}
		return bits;
	}

	public double[] allocDouble(int len) {
		int idx = doublePos++;
		if (idx >= doublePool.length) {
			doublePool = Arrays.copyOf(doublePool, doublePool.length * 2);
		}
		double[] arr = doublePool[idx];
		if (arr == null || arr.length < len) {
			arr = new double[Math.max(len, 64)];
			doublePool[idx] = arr;
		}
		return arr;
	}

	public double[] allocDoubleZeroed(int len) {
		double[] arr = allocDouble(len);
		Arrays.fill(arr, 0, len, 0.0);
		return arr;
	}

	public int[] allocInt(int len) {
		int idx = intPos++;
		if (idx >= intPool.length) {
			intPool = Arrays.copyOf(intPool, intPool.length * 2);
		}
		int[] arr = intPool[idx];
		if (arr == null || arr.length < len) {
			arr = new int[Math.max(len, 64)];
			intPool[idx] = arr;
		}
		return arr;
	}

	public int[] allocIntZeroed(int len) {
		int[] arr = allocInt(len);
		Arrays.fill(arr, 0, len, 0);
		return arr;
	}

	public byte[] allocByte(int len) {
		int idx = bytePos++;
		if (idx >= bytePool.length) {
			bytePool = Arrays.copyOf(bytePool, bytePool.length * 2);
		}
		byte[] arr = bytePool[idx];
		if (arr == null || arr.length < len) {
			arr = new byte[Math.max(len, 64)];
			bytePool[idx] = arr;
		}
		return arr;
	}

	public byte[] allocByteZeroed(int len) {
		byte[] arr = allocByte(len);
		Arrays.fill(arr, 0, len, (byte) 0);
		return arr;
	}

	public boolean[] allocBool(int len) {
		int idx = boolPos++;
		if (idx >= boolPool.length) {
			boolPool = Arrays.copyOf(boolPool, boolPool.length * 2);
		}
		boolean[] arr = boolPool[idx];
		if (arr == null || arr.length < len) {
			arr = new boolean[Math.max(len, 64)];
			boolPool[idx] = arr;
		}
		return arr;
	}

	public boolean[] allocBoolZeroed(int len) {
		boolean[] arr = allocBool(len);
		Arrays.fill(arr, 0, len, false);
		return arr;
	}

	public V11Vector allocVec() {
		int idx = vecPos++;
		if (idx >= vecPool.length) {
			vecPool = Arrays.copyOf(vecPool, vecPool.length * 2);
		}
		V11Vector v = vecPool[idx];
		if (v == null) {
			v = new V11Vector();
			vecPool[idx] = v;
		}
		v.reset();
		return v;
	}

	public V11Vector allocI64(long[] data, int len, long[] validBits) {
		V11Vector v = allocVec();
		v.elemTag = V11Vector.TAG_I64;
		v.length = len;
		v.i64Data = data;
		v.validBits = validBits;
		return v;
	}

	public V11Vector allocU64(long[] data, int len, long[] validBits) {
		V11Vector v = allocVec();
		v.elemTag = V11Vector.TAG_U64;
		v.length = len;
		v.i64Data = data;
		v.validBits = validBits;
		return v;
	}

	public V11Vector allocF64(double[] data, int len, long[] validBits) {
		V11Vector v = allocVec();
		v.elemTag = V11Vector.TAG_F64;
		v.length = len;
		v.f64Data = data;
		v.validBits = validBits;
		return v;
	}

	public V11Vector allocBool3vl(int len, long[] validBits, long[] dataBits) {
		V11Vector v = allocVec();
		v.elemTag = V11Vector.TAG_BOOL_3VL;
		v.length = len;
		v.validBits = validBits;
		v.boolDataBits = dataBits;
		return v;
	}

	public V11Vector shallowCopyVec(V11Vector src) {
		V11Vector c = allocVec();
		c.copyFieldsFrom(src);
		return c;
	}

	public V11Frontier allocFrontier(int domCard) {
		int idx = frontierPos++;
		if (idx >= frontierPool.length) {
			frontierPool = Arrays.copyOf(frontierPool, frontierPool.length * 2);
		}
		V11Frontier f = frontierPool[idx];
		int needWords = Math.max(1, (domCard + 63) >>> 6);
		if (f == null || f.denseWords.length < needWords) {
			f = new V11Frontier(Math.max(domCard, 64));
			frontierPool[idx] = f;
		}
		return f;
	}

	public void ensureValidatorCapacity(int wordCount) {
		if (valInMorsel.length < wordCount) {
			int cap = Math.max(wordCount, valInMorsel.length * 2);
			valInMorsel = new boolean[cap];
			valHasJoin = new boolean[cap];
			valJoinMask = new int[cap];
			valVisited = new boolean[cap];
			valStack = new int[cap];
		}
	}
}
