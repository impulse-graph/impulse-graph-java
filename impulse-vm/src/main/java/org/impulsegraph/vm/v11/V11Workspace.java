package org.impulsegraph.vm.v11;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Caller-Owned Zero-Allocation Workspace for ImpulseVM v0.11.0.
 *
 * <p>Pre-allocates all 16 scalar, 16 frontier, and 16 vector register backing slots
 * (including thread-private morsel scratch lanes M0..M7 / MF0..MF7), 16-deep sliding
 * register window stacks, and 16 frame-local spill slots (256 total across 16 call frames).
 * Calling {@link #reset()} between queries performs zero heap allocations.
 */
public final class V11Workspace {

    public static final int MORSEL_MAX     = 2048;
    public static final int MAX_CALL_DEPTH = 16;
    public static final int SPILL_PER_FRAME = 16;

    // Scalar register bank R0..R15 + validity/type masks
    public final long[] rRegs = new long[16];
    public int rValidMask;
    public long rTypeWord;

    // VM execution status, PC, and condition flags
    public int pc;
    public int status = V11IsaTables.IMPULSE_VM_OK;
    public boolean zf;
    public boolean st;
    public boolean nf;
    public int maxDop = 1;
    public int hostMaxThreads = 16;
    public int userThrowCode;
    public boolean hasUserThrowPayload;
    public long userThrowPayload;
    public long gasRemaining = Long.MAX_VALUE;
    public final long[] stepsScratch = new long[1];
    public V11Geometry geometry = V11Geometry.P0_DEFAULT;

    // Frontier register bank F0..F15 (F8..F15 = MF0..MF7) + initialized mask
    public final V11Frontier[] fRegs = new V11Frontier[16];
    public int fValidMask;

    // Typed Vector register bank V0..V15 (plus legacy primitive slices for gut-check JIT)
    public final V11Vector[] vVectors = new V11Vector[16];
    public final double[][] vF64 = new double[16][];
    public final int[][] vU32    = new int[16][];
    public final long[][] vValid = new long[16][];
    public final long[][] vBool  = new long[16][];
    public final int[] vLen      = new int[16];

    // Pre-allocated morsel edge staging arrays (M0=src, M1=tgt, M2=edgeIdx)
    public final int[] morselSrc  = new int[MORSEL_MAX];
    public final int[] morselTgt  = new int[MORSEL_MAX];
    public final int[] morselEdge = new int[MORSEL_MAX];

    // Sliding register window call stack (max depth 16)
    public int callDepth;
    public final int[] callReturnPc = new int[MAX_CALL_DEPTH];
    public final int[] callRStride  = new int[MAX_CALL_DEPTH];
    public final int[] callFStride  = new int[MAX_CALL_DEPTH];
    public final int[] callVStride  = new int[MAX_CALL_DEPTH];
    public final long[][] savedRVal    = new long[MAX_CALL_DEPTH][16];
    public final int[] savedRValidMask = new int[MAX_CALL_DEPTH];
    public final long[] savedRTypeWord = new long[MAX_CALL_DEPTH];
    public final V11Frontier[][] savedF = new V11Frontier[MAX_CALL_DEPTH][16];
    public final int[] savedFValidMask  = new int[MAX_CALL_DEPTH];
    public final V11Vector[][] savedV   = new V11Vector[MAX_CALL_DEPTH][16];

    // Frame-local spill slots (16 per call depth = 256 total)
    public static final int SPILL_EMPTY = 0;
    public static final int SPILL_R     = 1;
    public static final int SPILL_F     = 2;
    public static final int SPILL_V     = 3;

    public final int[] spillKind        = new int[(MAX_CALL_DEPTH + 1) * SPILL_PER_FRAME];
    public final long[] spillRVal       = new long[(MAX_CALL_DEPTH + 1) * SPILL_PER_FRAME];
    public final boolean[] spillRValid  = new boolean[(MAX_CALL_DEPTH + 1) * SPILL_PER_FRAME];
    public final int[] spillRType       = new int[(MAX_CALL_DEPTH + 1) * SPILL_PER_FRAME];
    public final V11Frontier[] spillF   = new V11Frontier[(MAX_CALL_DEPTH + 1) * SPILL_PER_FRAME];
    public final V11Vector[] spillV     = new V11Vector[(MAX_CALL_DEPTH + 1) * SPILL_PER_FRAME];

    // Constant pool, host-bound query params, scalar string table, terminal COLLECT outputs
    public final Map<Integer, ConstPoolEntry> constPool = new HashMap<>();
    public final Map<Integer, ParamEntry> params = new HashMap<>();
    public final Map<Long, String> strTable = new HashMap<>();
    public long nextStrHandle = 1L;
    public final Map<Integer, V11Vector> outputs = new HashMap<>();

    public final int maxDomainCard;
    public final V11Arena arena;

    public sealed interface ConstPoolEntry permits PoolVec, PoolFrontier, PoolStr {}
    public record PoolVec(V11Vector vec) implements ConstPoolEntry {}
    public record PoolFrontier(long[] ids) implements ConstPoolEntry {}
    public record PoolStr(String value) implements ConstPoolEntry {}

    public sealed interface ParamEntry permits ParamScalar, ParamStr, ParamFrontier, ParamMultiLane, ParamVec {}
    public record ParamScalar(int scalarType, boolean valid, long bits) implements ParamEntry {}
    public record ParamStr(String value) implements ParamEntry {}
    public record ParamFrontier(int domainId, long[] ids) implements ParamEntry {}
    public record ParamMultiLane(int domainId, int allocWidth, int activeLaneCount, long[] nodes, long[] masks) implements ParamEntry {}
    public record ParamVec(V11Vector vec) implements ParamEntry {}

    public V11Workspace(int maxDomainCard) {
        this.maxDomainCard = Math.max(64, maxDomainCard);
        this.arena = new V11Arena(this.maxDomainCard);
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
        pc = 0;
        status = V11IsaTables.IMPULSE_VM_OK;
        rValidMask = 0;
        rTypeWord = 0L;
        Arrays.fill(rRegs, 0L);
        zf = false;
        st = false;
        nf = false;
        maxDop = 1;
        userThrowCode = 0;
        hasUserThrowPayload = false;
        userThrowPayload = 0L;
        gasRemaining = Long.MAX_VALUE;
        fValidMask = 0;
        callDepth = 0;
        Arrays.fill(spillKind, 0, SPILL_PER_FRAME, SPILL_EMPTY);
        for (int i = 0; i < 16; i++) {
            fRegs[i].tier = V11Frontier.TIER_EMPTY;
            fRegs[i].cachedCard = 0;
            fRegs[i].inlineCount = 0;
            fRegs[i].dirtyPageCount = 0;
            fRegs[i].multiLane = false;
            vVectors[i] = null;
            vLen[i] = 0;
        }
        outputs.clear();
        arena.reset();
    }

    public void resetAllWithBindings() {
        reset();
        constPool.clear();
        params.clear();
        strTable.clear();
        nextStrHandle = 1L;
    }

    // --- Scalar Register Helpers ---

    public boolean isRValid(int reg) {
        return ((rValidMask >>> reg) & 1) != 0;
    }

    public int readRType(int reg) {
        if (!isRValid(reg)) {
            return V11IsaTables.SCALAR_NULL_UNSET;
        }
        return (int) ((rTypeWord >>> (reg * 4)) & 0xFL);
    }

    public void writeRNull(int reg) {
        rRegs[reg] = 0L;
        rValidMask &= ~(1 << reg);
        rTypeWord &= ~(0xFL << (reg * 4));
    }

    public void writeR(int reg, int scalarType, long value) {
        rRegs[reg] = value;
        rValidMask |= (1 << reg);
        rTypeWord = (rTypeWord & ~(0xFL << (reg * 4))) | ((scalarType & 0xFL) << (reg * 4));
    }

    public long computeCanonicalRTypeWord() {
        long w = 0L;
        for (int i = 0; i < 16; i++) {
            int nib = isRValid(i) ? readRType(i) : 0;
            w |= ((nib & 0xFL) << (i * 4));
        }
        return w;
    }

    public int vmFlagsRaw() {
        return (zf ? V11IsaTables.VM_FLAG_ZF : 0)
                | (st ? V11IsaTables.VM_FLAG_ST : 0)
                | (nf ? V11IsaTables.VM_FLAG_NF : 0);
    }

    // --- Frontier Register Helpers ---

    public boolean isFInitialized(int reg) {
        return ((fValidMask >>> reg) & 1) != 0;
    }

    public void setFInitialized(int reg, boolean initialized) {
        if (initialized) {
            fValidMask |= (1 << reg);
        } else {
            fValidMask &= ~(1 << reg);
        }
    }

    // --- Scalar String Table ---

    public long internScalarStr(String s) {
        long h = nextStrHandle++;
        strTable.put(h, s);
        return h;
    }

    public String resolveScalarStr(long handle) {
        return strTable.get(handle);
    }

    public int[] sortedOutputSlots() {
        int[] slots = new int[outputs.size()];
        int idx = 0;
        for (Integer k : outputs.keySet()) {
            slots[idx++] = k;
        }
        Arrays.sort(slots);
        return slots;
    }
}
