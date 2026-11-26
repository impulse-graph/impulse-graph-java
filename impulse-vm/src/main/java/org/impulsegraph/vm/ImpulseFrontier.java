package org.impulsegraph.vm;

import java.util.Arrays;

/**
 * ImpulseVM v0.11.0 3-Tier Domain-Bound Frontier (Tier0_Inline / Tier1_Paged /
 * Tier2_Dense / Tier_All_Span).
 *
 * <p>
 * All backing arrays are pre-allocated by {@link ImpulseWorkspace} and reused
 * across queries via O(1) epoch invalidation so steady-state execution performs
 * zero heap allocations.
 */
public final class ImpulseFrontier {

	public static final int TIER_EMPTY = 0;
	public static final int TIER0_INLINE = 1;
	public static final int TIER1_PAGED = 2;
	public static final int TIER2_DENSE = 3;
	public static final int TIER_ALL_SPAN = 4;

	public static final int TIER0_MAX = 64;
	public static final int PAGE_SHIFT = 12; // 4096 nodes per page (64 longs = 512 bytes)
	public static final int PAGE_WORDS = 64;
	public static final int PAGE_MASK = (1 << PAGE_SHIFT) - 1;
	public static final int TIER2_DENOM = 64;
	private static final boolean FORCE_TIER1 = Boolean.getBoolean("v11.forceTier1");

	// Open-addressed page directory (power-of-two capacity, epoch-stamped for O(1)
	// clear)
	private static final int DIR_CAP = 512;
	private static final int DIR_MASK = DIR_CAP - 1;
	private static final int MAX_DIRTY_PGS = 256;

	public int domainId;
	public int domainCard;
	public int tier;
	public int cachedCard; // -1 when unknown/lazy

	// Tier 0: inline sorted/deduped dense node IDs (<= 64)
	public final int[] inlineIds = new int[TIER0_MAX];
	public int inlineCount;

	// Tier 1: epoch-stamped open-addressed page directory + 512B bitset pages
	private final int[] dirPageId = new int[DIR_CAP];
	private final int[] dirSlot = new int[DIR_CAP];
	private final int[] dirEpoch = new int[DIR_CAP];
	private int pageEpoch = 1;

	public final int[] dirtyPageIds = new int[MAX_DIRTY_PGS];
	public long[] pageBits;
	public int dirtyPageCount;

	// Tier 2: dense bitset words (sized once to maxDomainCard)
	public final long[] denseWords;
	public int denseWordCount;

	// Multi-lane payload (used by LOAD_PARAM.frontier multilane & Pillar 5)
	public boolean multiLane;
	public int allocWidth = 1;
	public int activeLaneCount = 1;
	public long[] mlNodes;
	public long[] mlMasks;
	public int mlCount;

	public ImpulseFrontier(int maxDomainCard) {
		int maxWords = Math.max(1, (maxDomainCard + 63) >>> 6);
		this.denseWords = new long[maxWords];
		this.tier = TIER_EMPTY;
		this.cachedCard = 0;
	}

	/** O(1) reset to empty frontier on a target domain (zero array zeroing). */
	public void resetEmpty(int domainId, int domainCard) {
		this.domainId = domainId;
		this.domainCard = domainCard;
		this.tier = TIER_EMPTY;
		this.cachedCard = 0;
		this.inlineCount = 0;
		this.dirtyPageCount = 0;
		this.denseWordCount = (domainCard + 63) >>> 6;
		this.multiLane = false;
		this.allocWidth = 1;
		this.activeLaneCount = 1;
		this.mlCount = 0;
	}

	public void initAllSpan(int domainId, int domainCard) {
		this.domainId = domainId;
		this.domainCard = domainCard;
		this.tier = domainCard > 0 ? TIER_ALL_SPAN : TIER_EMPTY;
		this.cachedCard = domainCard;
		this.inlineCount = 0;
		this.dirtyPageCount = 0;
		this.denseWordCount = (domainCard + 63) >>> 6;
		this.multiLane = false;
		this.allocWidth = 1;
		this.activeLaneCount = 1;
		this.mlCount = 0;
	}

	public boolean isEmpty() {
		return multiLane ? (mlCount == 0) : (tier == TIER_EMPTY);
	}

	/**
	 * Initialize a 1-bit frontier from a list of validated node IDs using normative
	 * geometry tiers.
	 */
	public void initFromValidatedIds(int domainId, int domainCard, int[] sortedUniqueIds, ImpulseGeometry geom,
			boolean knowCard) {
		initFromValidatedIds(domainId, domainCard, sortedUniqueIds, sortedUniqueIds.length, geom, knowCard);
	}

	/**
	 * Zero-allocation overload taking an explicit count into a caller/arena-owned
	 * buffer.
	 */
	public void initFromValidatedIds(int domainId, int domainCard, int[] sortedUniqueIds, int c, ImpulseGeometry geom,
			boolean knowCard) {
		resetEmpty(domainId, domainCard);
		if (c == 0) {
			return;
		}
		int targetTier = geom.computeTier(domainCard, c, false);
		if (targetTier == TIER0_INLINE && c <= TIER0_MAX) {
			System.arraycopy(sortedUniqueIds, 0, inlineIds, 0, c);
			inlineCount = c;
			tier = TIER0_INLINE;
			cachedCard = c;
		} else {
			if (denseWords.length >= denseWordCount) {
				Arrays.fill(denseWords, 0, denseWordCount, 0L);
				for (int i = 0; i < c; i++) {
					int u = sortedUniqueIds[i];
					denseWords[u >>> 6] |= (1L << u);
				}
			}
			if (targetTier == TIER1_PAGED) {
				tier = TIER1_PAGED;
				dirtyPageCount = -1;
			} else {
				tier = TIER2_DENSE;
			}
			cachedCard = (knowCard || targetTier == TIER0_INLINE || targetTier == TIER_ALL_SPAN) ? c : -1;
		}
	}

	/**
	 * Zero-allocation initialization directly from a dense bitset
	 * (words[0..wordCount-1]).
	 */
	public void initFromDenseBits(int domainId, int domainCard, long[] bits, int wordCount, int card,
			ImpulseGeometry geom, boolean knowCard) {
		resetEmpty(domainId, domainCard);
		if (card == 0) {
			return;
		}
		int targetTier = geom.computeTier(domainCard, card, false);
		if (targetTier == TIER0_INLINE && card <= TIER0_MAX) {
			int p = 0;
			for (int w = 0; w < wordCount; w++) {
				long m = bits[w];
				while (m != 0L) {
					inlineIds[p++] = (w << 6) + Long.numberOfTrailingZeros(m);
					m &= (m - 1L);
				}
			}
			inlineCount = p;
			tier = TIER0_INLINE;
			cachedCard = p;
		} else {
			if (denseWords.length >= wordCount) {
				System.arraycopy(bits, 0, denseWords, 0, wordCount);
			}
			if (targetTier == TIER1_PAGED) {
				tier = TIER1_PAGED;
				dirtyPageCount = -1;
			} else {
				tier = TIER2_DENSE;
			}
			cachedCard = (knowCard || targetTier == TIER0_INLINE || targetTier == TIER_ALL_SPAN) ? card : -1;
		}
	}

	/** Initialize a multi-lane frontier from validated (node, mask) entries. */
	public void initMultiLane(int domainId, int domainCard, int allocWidth, int activeLaneCount, long[] sortedNodes,
			long[] sortedMasks, ImpulseGeometry geom) {
		initMultiLane(domainId, domainCard, allocWidth, activeLaneCount, sortedNodes, sortedMasks, sortedNodes.length,
				geom);
	}

	/** Zero-allocation multi-lane initialization taking explicit count. */
	public void initMultiLane(int domainId, int domainCard, int allocWidth, int activeLaneCount, long[] sortedNodes,
			long[] sortedMasks, int count, ImpulseGeometry geom) {
		resetEmpty(domainId, domainCard);
		this.multiLane = true;
		this.allocWidth = allocWidth;
		this.activeLaneCount = activeLaneCount;
		this.mlNodes = sortedNodes;
		this.mlMasks = sortedMasks;
		this.mlCount = count;
		this.tier = geom.computeTier(domainCard, this.mlCount, false);
		this.cachedCard = this.mlCount;
	}

	public long[] mlSortedNodes() {
		return mlNodes == null ? new long[0] : Arrays.copyOf(mlNodes, mlCount);
	}

	public long[] mlSortedMasks() {
		return mlMasks == null ? new long[0] : Arrays.copyOf(mlMasks, mlCount);
	}

	/** Deep snapshot copy for spill slots and sliding call windows. */
	public ImpulseFrontier snapshotCopy() {
		ImpulseFrontier copy = new ImpulseFrontier(Math.max(64, this.domainCard));
		copy.copyFrom(this);
		return copy;
	}

	private void bumpPageEpoch() {
		pageEpoch++;
		if (pageEpoch == 0) {
			Arrays.fill(dirEpoch, 0);
			pageEpoch = 1;
		}
		dirtyPageCount = 0;
	}

	/** Insert a dense node ID with automatic Tier0 -> Tier1 -> Tier2 promotion. */
	public void add(int nodeId) {
		switch (tier) {
			case TIER_EMPTY -> {
				if (FORCE_TIER1) {
					bumpPageEpoch();
					tier = TIER1_PAGED;
					int slot = findOrAllocPage(nodeId >>> PAGE_SHIFT);
					int local = nodeId & PAGE_MASK;
					pageBits[(slot << 6) + (local >>> 6)] |= (1L << local);
					cachedCard = -1;
				} else {
					inlineIds[0] = nodeId;
					inlineCount = 1;
					tier = TIER0_INLINE;
					cachedCard = 1;
				}
			}
			case TIER0_INLINE -> {
				for (int i = 0; i < inlineCount; i++) {
					if (inlineIds[i] == nodeId) {
						return;
					}
				}
				if (inlineCount < TIER0_MAX) {
					inlineIds[inlineCount++] = nodeId;
					cachedCard = inlineCount;
				} else {
					promoteFromTier0();
					add(nodeId);
				}
			}
			case TIER1_PAGED -> {
				if (dirtyPageCount < 0) {
					addDense(nodeId);
				} else {
					int pageId = nodeId >>> PAGE_SHIFT;
					int slot = findOrAllocPage(pageId);
					if (slot < 0) {
						promoteTier1ToTier2();
						addDense(nodeId);
					} else {
						int local = nodeId & PAGE_MASK;
						pageBits[(slot << 6) + (local >>> 6)] |= (1L << local);
						cachedCard = -1;
					}
				}
			}
			case TIER2_DENSE -> addDense(nodeId);
			case TIER_ALL_SPAN -> {
				/* already contains all nodes */ }
			default -> throw new IllegalStateException("Unknown tier: " + tier);
		}
	}

	private void addDense(int nodeId) {
		denseWords[nodeId >>> 6] |= (1L << nodeId);
		cachedCard = -1;
	}

	private void promoteFromTier0() {
		if (domainCard <= (1 << PAGE_SHIFT) || (long) (TIER0_MAX + 1) * TIER2_DENOM >= domainCard) {
			Arrays.fill(denseWords, 0, denseWordCount, 0L);
			for (int i = 0; i < inlineCount; i++) {
				int u = inlineIds[i];
				denseWords[u >>> 6] |= (1L << u);
			}
			tier = TIER2_DENSE;
			cachedCard = inlineCount;
		} else {
			bumpPageEpoch();
			tier = TIER1_PAGED;
			for (int i = 0; i < inlineCount; i++) {
				int u = inlineIds[i];
				int slot = findOrAllocPage(u >>> PAGE_SHIFT);
				if (slot < 0) {
					promoteTier1ToTier2();
					for (int j = i; j < inlineCount; j++) {
						int v = inlineIds[j];
						denseWords[v >>> 6] |= (1L << v);
					}
					break;
				}
				int local = u & PAGE_MASK;
				pageBits[(slot << 6) + (local >>> 6)] |= (1L << local);
			}
			cachedCard = inlineCount;
		}
	}

	private int findOrAllocPage(int pageId) {
		int idx = (pageId * 0x9E3779B9) & DIR_MASK;
		while (dirEpoch[idx] == pageEpoch) {
			if (dirPageId[idx] == pageId) {
				return dirSlot[idx];
			}
			idx = (idx + 1) & DIR_MASK;
		}
		if (dirtyPageCount >= MAX_DIRTY_PGS) {
			return -1;
		}
		int totalPages = (domainCard + PAGE_MASK) >>> PAGE_SHIFT;
		if (!FORCE_TIER1 && (long) (dirtyPageCount + 1) * TIER2_DENOM >= totalPages && totalPages > 0) {
			return -1;
		}
		int slot = dirtyPageCount++;
		dirEpoch[idx] = pageEpoch;
		dirPageId[idx] = pageId;
		dirSlot[idx] = slot;
		dirtyPageIds[slot] = pageId;
		if (pageBits == null) {
			pageBits = new long[MAX_DIRTY_PGS * PAGE_WORDS];
		}
		int base = slot << 6;
		Arrays.fill(pageBits, base, base + PAGE_WORDS, 0L);
		return slot;
	}

	public void promoteTier1ToTier2() {
		if (dirtyPageCount < 0) {
			tier = TIER2_DENSE;
			return;
		}
		Arrays.fill(denseWords, 0, denseWordCount, 0L);
		for (int s = 0; s < dirtyPageCount; s++) {
			int dstBase = dirtyPageIds[s] << 6;
			int srcBase = s << 6;
			int copyLen = Math.min(PAGE_WORDS, denseWordCount - dstBase);
			if (copyLen > 0) {
				System.arraycopy(pageBits, srcBase, denseWords, dstBase, copyLen);
			}
		}
		tier = TIER2_DENSE;
	}

	public boolean contains(int nodeId) {
		if (nodeId < 0 || nodeId >= domainCard) {
			return false;
		}
		return switch (tier) {
			case TIER_EMPTY -> false;
			case TIER_ALL_SPAN -> true;
			case TIER0_INLINE -> {
				for (int i = 0; i < inlineCount; i++) {
					if (inlineIds[i] == nodeId)
						yield true;
				}
				yield false;
			}
			case TIER1_PAGED -> {
				if (dirtyPageCount < 0) {
					yield (denseWords[nodeId >>> 6] & (1L << nodeId)) != 0L;
				}
				int pageId = nodeId >>> PAGE_SHIFT;
				int idx = (pageId * 0x9E3779B9) & DIR_MASK;
				while (dirEpoch[idx] == pageEpoch) {
					if (dirPageId[idx] == pageId) {
						int slot = dirSlot[idx];
						int local = nodeId & PAGE_MASK;
						yield (pageBits[(slot << 6) + (local >>> 6)] & (1L << local)) != 0L;
					}
					idx = (idx + 1) & DIR_MASK;
				}
				yield false;
			}
			case TIER2_DENSE -> (denseWords[nodeId >>> 6] & (1L << nodeId)) != 0L;
			default -> false;
		};
	}

	/** Lazy cardinality computation with memoization into cachedCard. */
	public int cardinality() {
		if (cachedCard >= 0) {
			return cachedCard;
		}
		if (multiLane) {
			cachedCard = mlCount;
			return mlCount;
		}
		int c = 0;
		if (tier == TIER1_PAGED && dirtyPageCount >= 0) {
			int limit = dirtyPageCount << 6;
			for (int i = 0; i < limit; i++) {
				c += Long.bitCount(pageBits[i]);
			}
		} else if (tier == TIER2_DENSE || (tier == TIER1_PAGED && dirtyPageCount < 0)) {
			for (int i = 0; i < denseWordCount; i++) {
				c += Long.bitCount(denseWords[i]);
			}
		}
		if (c == 0) {
			tier = TIER_EMPTY;
		}
		cachedCard = c;
		return c;
	}

	/**
	 * Copy contents of [src] into this frontier without heap allocation (for 1-bit
	 * hot path).
	 */
	public void copyFrom(ImpulseFrontier src) {
		if (this == src) {
			return;
		}
		this.domainId = src.domainId;
		this.domainCard = src.domainCard;
		this.tier = src.tier;
		this.cachedCard = src.cachedCard;
		this.denseWordCount = src.denseWordCount;
		this.multiLane = src.multiLane;
		this.allocWidth = src.allocWidth;
		this.activeLaneCount = src.activeLaneCount;
		if (src.multiLane) {
			this.mlCount = src.mlCount;
			this.mlNodes = src.mlNodes != null ? Arrays.copyOf(src.mlNodes, src.mlCount) : null;
			this.mlMasks = src.mlMasks != null ? Arrays.copyOf(src.mlMasks, src.mlCount) : null;
			return;
		}
		if (src.tier == TIER0_INLINE) {
			this.inlineCount = src.inlineCount;
			System.arraycopy(src.inlineIds, 0, this.inlineIds, 0, src.inlineCount);
		} else if (src.tier == TIER1_PAGED) {
			if (src.dirtyPageCount < 0) {
				this.dirtyPageCount = -1;
				System.arraycopy(src.denseWords, 0, this.denseWords, 0, src.denseWordCount);
			} else {
				bumpPageEpoch();
				for (int s = 0; s < src.dirtyPageCount; s++) {
					int pid = src.dirtyPageIds[s];
					int dstSlot = findOrAllocPage(pid);
					System.arraycopy(src.pageBits, s << 6, this.pageBits, dstSlot << 6, PAGE_WORDS);
				}
			}
		} else if (src.tier == TIER2_DENSE) {
			System.arraycopy(src.denseWords, 0, this.denseWords, 0, src.denseWordCount);
		}
	}

	/**
	 * Extract all node IDs into a sorted int[] (allocates; prefer toSortedArrayInto
	 * on hot paths).
	 */
	public int[] toSortedArray() {
		int card = cardinality();
		int[] out = new int[card];
		toSortedArrayInto(out);
		return out;
	}

	/**
	 * Zero-allocation extraction of sorted node IDs into caller/arena-owned [out];
	 * returns count written.
	 */
	public int toSortedArrayInto(int[] out) {
		int p = 0;
		switch (tier) {
			case TIER_EMPTY -> {
			}
			case TIER_ALL_SPAN -> {
				for (int i = 0; i < domainCard; i++)
					out[p++] = i;
			}
			case TIER0_INLINE -> {
				System.arraycopy(inlineIds, 0, out, 0, inlineCount);
				p = inlineCount;
				Arrays.sort(out, 0, p);
			}
			case TIER1_PAGED -> {
				if (dirtyPageCount < 0) {
					for (int w = 0; w < denseWordCount; w++) {
						long bits = denseWords[w];
						while (bits != 0L) {
							int tz = Long.numberOfTrailingZeros(bits);
							out[p++] = (w << 6) + tz;
							bits &= (bits - 1L);
						}
					}
				} else {
					for (int s = 0; s < dirtyPageCount; s++) {
						int baseNode = dirtyPageIds[s] << PAGE_SHIFT;
						int baseWord = s << 6;
						for (int w = 0; w < PAGE_WORDS; w++) {
							long bits = pageBits[baseWord + w];
							while (bits != 0L) {
								int tz = Long.numberOfTrailingZeros(bits);
								out[p++] = baseNode + (w << 6) + tz;
								bits &= (bits - 1L);
							}
						}
					}
					Arrays.sort(out, 0, p);
				}
			}
			case TIER2_DENSE -> {
				for (int w = 0; w < denseWordCount; w++) {
					long bits = denseWords[w];
					while (bits != 0L) {
						int tz = Long.numberOfTrailingZeros(bits);
						out[p++] = (w << 6) + tz;
						bits &= (bits - 1L);
					}
				}
			}
		}
		return p;
	}
}
