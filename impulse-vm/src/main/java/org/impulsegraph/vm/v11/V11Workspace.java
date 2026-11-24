package org.impulsegraph.vm.v11;

/**
 * Caller-Owned Zero-Allocation Workspace for ImpulseVM v0.11.0.
 *
 * <p>Pre-allocates all 16 scalar, 16 frontier, and 16 vector register backing slots
 * (including thread-private morsel scratch lanes M0..M7 / MF0..MF7). Calling {@link #reset()}
 * between queries performs zero heap allocations.
 */
public final class V11Workspace {

    public static final int MORSEL_MAX = 2048;

    // Scalar register bank R0..R15 + validity/type masks
    public final long[] rRegs = new long[16];
    public int rValidMask;
    public long rTypeWord;

    // VM condition flags
    public boolean zf;
    public boolean st;
    public boolean nf;

    // Frontier register bank F0..F15 (F8..F15 = MF0..MF7)
    public final V11Frontier[] fRegs = new V11Frontier[16];

    // Vector register bank V0..V15 (V0..V7 outer domain-sized; V8..V15 morsel-sized M0..M7)
    public final double[][] vF64 = new double[16][];
    public final int[][] vU32    = new int[16][];
    public final long[][] vValid = new long[16][];
    public final long[][] vBool  = new long[16][];
    public final int[] vLen      = new int[16];

    // Pre-allocated morsel edge staging arrays (M0=src, M1=tgt, M2=edgeIdx)
    public final int[] morselSrc  = new int[MORSEL_MAX];
    public final int[] morselTgt  = new int[MORSEL_MAX];
    public final int[] morselEdge = new int[MORSEL_MAX];

    public final int maxDomainCard;

    public V11Workspace(int maxDomainCard) {
        this.maxDomainCard = Math.max(64, maxDomainCard);
        for (int i = 0; i < 8; i++) {
            fRegs[i] = new V11Frontier(this.maxDomainCard);
            vF64[i] = new double[this.maxDomainCard];
            vU32[i] = new int[this.maxDomainCard];
            int words = (this.maxDomainCard + 63) >>> 6;
            vValid[i] = new long[words];
            vBool[i] = new long[words];
        }
        int morselWords = (MORSEL_MAX + 63) >>> 6;
        for (int i = 8; i < 16; i++) {
            fRegs[i] = new V11Frontier(MORSEL_MAX);
            vF64[i] = new double[MORSEL_MAX];
            vU32[i] = new int[MORSEL_MAX];
            vValid[i] = new long[morselWords];
            vBool[i] = new long[morselWords];
        }
    }

    /** O(1) zero-allocation reset before each query execution. */
    public void reset() {
        rValidMask = 0;
        rTypeWord = 0L;
        zf = false;
        st = false;
        nf = false;
        for (int i = 0; i < 16; i++) {
            fRegs[i].tier = V11Frontier.TIER_EMPTY;
            fRegs[i].cachedCard = 0;
            fRegs[i].inlineCount = 0;
            fRegs[i].dirtyPageCount = 0;
            vLen[i] = 0;
        }
    }
}
