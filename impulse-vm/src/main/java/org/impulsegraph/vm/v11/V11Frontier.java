package org.impulsegraph.vm.v11;

import java.util.Arrays;

/**
 * ImpulseVM v0.11.0 3-Tier Domain-Bound Frontier (Tier0_Inline / Tier1_Paged / Tier2_Dense / Tier_All_Span).
 *
 * <p>All backing arrays are pre-allocated by {@link V11Workspace} and reused across queries via
 * O(1) epoch invalidation so steady-state execution performs zero heap allocations.
 */
public final class V11Frontier {

    public static final int TIER_EMPTY    = 0;
    public static final int TIER0_INLINE  = 1;
    public static final int TIER1_PAGED   = 2;
    public static final int TIER2_DENSE   = 3;
    public static final int TIER_ALL_SPAN = 4;

    public static final int TIER0_MAX     = 64;
    public static final int PAGE_SHIFT    = 12; // 4096 nodes per page (64 longs = 512 bytes)
    public static final int PAGE_WORDS    = 64;
    public static final int PAGE_MASK     = (1 << PAGE_SHIFT) - 1;
    public static final int TIER2_DENOM   = 64;
    private static final boolean FORCE_TIER1 = Boolean.getBoolean("v11.forceTier1");

    // Open-addressed page directory (power-of-two capacity, epoch-stamped for O(1) clear)
    private static final int DIR_CAP       = 512;
    private static final int DIR_MASK      = DIR_CAP - 1;
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
    private final int[] dirSlot   = new int[DIR_CAP];
    private final int[] dirEpoch  = new int[DIR_CAP];
    private int pageEpoch = 1;

    public final int[] dirtyPageIds = new int[MAX_DIRTY_PGS];
    public final long[] pageBits    = new long[MAX_DIRTY_PGS * PAGE_WORDS];
    public int dirtyPageCount;

    // Tier 2: dense bitset words (sized once to maxDomainCard)
    public final long[] denseWords;
    public int denseWordCount;

    public V11Frontier(int maxDomainCard) {
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
    }

    public void initAllSpan(int domainId, int domainCard) {
        this.domainId = domainId;
        this.domainCard = domainCard;
        this.tier = domainCard > 0 ? TIER_ALL_SPAN : TIER_EMPTY;
        this.cachedCard = domainCard;
        this.inlineCount = 0;
        this.dirtyPageCount = 0;
        this.denseWordCount = (domainCard + 63) >>> 6;
    }

    public boolean isEmpty() {
        return tier == TIER_EMPTY;
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
            case TIER2_DENSE -> addDense(nodeId);
            case TIER_ALL_SPAN -> { /* already contains all nodes */ }
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
        int base = slot << 6;
        Arrays.fill(pageBits, base, base + PAGE_WORDS, 0L);
        return slot;
    }

    public void promoteTier1ToTier2() {
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
        return switch (tier) {
            case TIER_EMPTY -> false;
            case TIER_ALL_SPAN -> nodeId >= 0 && nodeId < domainCard;
            case TIER0_INLINE -> {
                for (int i = 0; i < inlineCount; i++) {
                    if (inlineIds[i] == nodeId) yield true;
                }
                yield false;
            }
            case TIER1_PAGED -> {
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
        int c = 0;
        if (tier == TIER1_PAGED) {
            int limit = dirtyPageCount << 6;
            for (int i = 0; i < limit; i++) {
                c += Long.bitCount(pageBits[i]);
            }
        } else if (tier == TIER2_DENSE) {
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

    /** Copy contents of [src] into this frontier without heap allocation. */
    public void copyFrom(V11Frontier src) {
        this.domainId = src.domainId;
        this.domainCard = src.domainCard;
        this.tier = src.tier;
        this.cachedCard = src.cachedCard;
        this.denseWordCount = src.denseWordCount;
        if (src.tier == TIER0_INLINE) {
            this.inlineCount = src.inlineCount;
            System.arraycopy(src.inlineIds, 0, this.inlineIds, 0, src.inlineCount);
        } else if (src.tier == TIER1_PAGED) {
            bumpPageEpoch();
            for (int s = 0; s < src.dirtyPageCount; s++) {
                int pid = src.dirtyPageIds[s];
                int dstSlot = findOrAllocPage(pid);
                System.arraycopy(src.pageBits, s << 6, this.pageBits, dstSlot << 6, PAGE_WORDS);
            }
        } else if (src.tier == TIER2_DENSE) {
            System.arraycopy(src.denseWords, 0, this.denseWords, 0, src.denseWordCount);
        }
    }

    /** Extract all node IDs into a sorted int[] (for parity verification outside timed loops). */
    public int[] toSortedArray() {
        int card = cardinality();
        int[] out = new int[card];
        int p = 0;
        switch (tier) {
            case TIER_EMPTY -> {}
            case TIER_ALL_SPAN -> {
                for (int i = 0; i < domainCard; i++) out[p++] = i;
            }
            case TIER0_INLINE -> {
                System.arraycopy(inlineIds, 0, out, 0, inlineCount);
                Arrays.sort(out);
            }
            case TIER1_PAGED -> {
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
                Arrays.sort(out);
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
        return out;
    }
}
