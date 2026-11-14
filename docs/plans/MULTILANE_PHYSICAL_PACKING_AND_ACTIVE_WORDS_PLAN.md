# Multi-Lane Physical Width Packing (`WIDTH_8`/`16`/`32`/`64`), Sparse `activeWords` Summary Bitset & $O(\text{active})$ Pool Reuse

* **Repository**: `impulse-graph-java` (`impulse-vm`, `impulse-core`)
* **Tracking Issue**: [`impulse-graph-java#41`](https://github.com/impulse-graph/impulse-graph-java/issues/41)
* **Companion Issues**:
  * C++20 Core Engine: [`impulse-graph-core#83`](https://github.com/impulse-graph/impulse-graph-core/issues/83)
  * `v0.11+` Specification (`TYPE_LANE_STATE`): [`impulse-graph-spec#23`](https://github.com/impulse-graph/impulse-graph-spec/issues/23)
  * Historical Context: [`impulse-graph-core#29`](https://github.com/impulse-graph/impulse-graph-core/issues/29) (commit `59ccd37`)
* **Target Branch**: `feat/multilane-physical-packing`
* **Specification Impact**: **Zero ISA or binary format changes** (`VM_SPECIFICATION.md` v0.10.0 and `opcodes.json` remain unchanged).

---

## 1. Motivation & Root-Cause Analysis

### 1.1 Two Missing Optimizations in `impulse-graph-java` (`AGENTS.md` §3.13 Parity)
Inspection of `VmLaneFrontier.java`, `VmMultiLaneHandlers.java`, and `VmQueryContext.java` against C++ (`impulse-graph-core#29` / `#83`) and *Li et al., "MITra: A Framework for Multi-Instance Graph Traversal", PVLDB 16(10): 2551–2564, 2023* surfaced four concrete bottlenecks in Java 25 multi-lane execution (`0xC0`–`0xCB`):

1. **Unpacked `long[]` Backing Storage Across All 4 Width Tiers**:
   `VmLaneFrontier.LaneWidth` (`VmLaneFrontier.java:15-27`) defines `WIDTH_8`, `WIDTH_16`, `WIDTH_32`, and `WIDTH_64`, and `selectLaneWidth(k)` (`lines 36-60`) selects the width tier, **but** `VmLaneFrontier` (`line 66`) always backs the frontier with `long[] lanes` (`8 B/node`), wasting **8x / 4x / 2x** L2/L3 cache capacity for $K \le 8 / 16 / 32$ queries.
2. **Dense $O(N)$ Scans & `Arrays.fill` Instead of Sparse `activeWords` Summary Bitset**:
   `impulse-graph-core#29` (`59ccd37`) added `active_words` (`1 bit/node`) to C++ `VmLaneFrontier` and `lane_walk_common.hpp`, but **never ported it to Java**:
   * `VmLaneFrontier.reset` (`VmLaneFrontier.java:104`) executes `Arrays.fill(lanesBuf, 0, nodeCount, 0L)` ($O(N)$) on every handle acquisition.
   * `VmMultiLaneHandlers.stepLanesOnce` (`VmMultiLaneHandlers.java:74, 100, 178`) executes `Arrays.fill(nextLanes, 0, outLen, 0L)` and scans every node `0 .. lim - 1` on every hop.
   * `FIXPOINT` (`0x04` flag, `VmMultiLaneHandlers.java:510-522`) scans `0 .. outLen - 1` on every fixpoint iteration even when `delta` has $< 10$ active nodes on a 10M-node domain.
   * `0xC2`, `0xC5`, `0xC6`, `0xC7`, `0xC8`, `0xC9`, `0xCA`, and `0xCB` all execute dense $O(N)$ loops over `0 .. nodeCount - 1`.
   * On `0xC4` (`OP_LANE_CSC_WALK`), `stepLanesOnce` (`VmMultiLaneHandlers.java:176-213`) currently scans all `relN` forward CSR rows ($O(N + E)$) instead of using `rel.hasCsc()` / `rel.getCscRowOffsetsSegment()` / `rel.getCscColumnTargetsSegment()` over active target nodes ($O(\sum_{v \in \text{active}} \text{deg}^-(v))$).
   * Together, these cause $> 5\times$ Java slowdowns vs. C++ on sparse multi-seed queries over large domains, violating the $\le 2\times$ C++/Java parity rule in `AGENTS.md` §3.13.
3. **Exact-Length Buffer Pool Mismatch on Multi-Domain Walks**:
   `VmQueryContext.ThreadLocalBufferPool.acquireFrontier` (`VmQueryContext.java:586-595`) matches pooled arrays using `if (cand.length == nodeCount)` (exact equality). Stepping across multi-domain relations (`DomainA` $N = 50{,}000 \to$ `DomainB` $N = 12{,}000$) misses the pool and allocates `new` arrays on the heap mid-query.

---

## 2. Architectural Design & Zero-Allocation Guardrails

### 2.1 Strict `javap -c` Zero-Allocation & Zero-Autoboxing Compliance (`AGENTS.md` §3.19)
`VmInvariantsAndCoverageTest#testZeroHotPathOpcodeBytecodeAllocationsAndAutoboxing` disassembles **every non-constructor method** in `VmLaneFrontier.class` and `VmMultiLaneHandlers.class` via `javap -c` and fails the build if any method emits:
* `new`, `newarray`, `anewarray`, `multianewarray`
* `Arrays.copyOf` / `Arrays.copyOfRange`
* Primitive `valueOf` autoboxing (`Integer.valueOf`, `Long.valueOf`, `Byte.valueOf`, `Short.valueOf`)
* Variable-capturing lambdas (`invokedynamic`) or `Iterator`s

Therefore:
* **Zero `new byte[]`, `new short[]`, `new int[]`, or `new long[]`** may appear anywhere in `VmLaneFrontier` or `VmMultiLaneHandlers`. All primitive array allocations and capacity growths MUST reside exclusively in `VmQueryContext` / `VmQueryContext.ThreadLocalBufferPool`.
* Width dispatch in `VmLaneFrontier` and `VmMultiLaneHandlers` MUST use direct `switch (width)` statements over `byte[]` / `short[]` / `int[]` / `long[]` fields — never `Object` boxing or capturing lambdas.

### 2.2 Unsigned Sub-Word Widening (Preventing Java Sign-Extension Bugs)
Java `byte`, `short`, and `int` are **signed** two's-complement primitives:
* Lane `7` sets bit 7 (`(byte) 0x80 == -128`). Implicitly widening `(long) lanes8[u]` sign-extends bits `8..63` to `0xFFFF_FFFF_FFFF_FF80L`, corrupting `activeMask`, `OP_LANE_POPCOUNT` (`0xC7`), `OP_LANE_EXTRACT_BITSET` (`0xC6`), and ICTX v1 hashes!
* Same trap applies to lane `15` (`(short) 0x8000 == -32768`) and lane `31` (`0x8000_0000 == Integer.MIN_VALUE`).

**Mandatory Rule**: Every read of a sub-word lane slot to `long` MUST use Java's intrinsic zero-extending conversions:
* `WIDTH_8`: `Byte.toUnsignedLong(lanes8[u])` (compiles to a single `movzbl` / `uxtb` instruction in C2 JIT).
* `WIDTH_16`: `Short.toUnsignedLong(lanes16[u])` (compiles to `movzwl` / `uxth`).
* `WIDTH_32`: `Integer.toUnsignedLong(lanes32[u])` (compiles to 32-bit `movl` / `mov w`).
* `WIDTH_64`: `lanes64[u]`.

### 2.3 `VmLaneFrontier` Tiered Representation & Sparse `activeWords`
`VmLaneFrontier` holds four typed primitive array references (exactly **one** non-null per active frontier, matching `width`) plus a pooled `long[] activeWords` summary bitset (`1 bit/node`, `activeWordCount = (nodeCount + 63) >>> 6`):

```java
private LaneWidth width;
private int numLanes;
private long activeMask;
private int nodeCount;
private byte[] lanes8;
private short[] lanes16;
private int[] lanes32;
private long[] lanes64;
private long[] activeWords;
private int activeWordCount;
```

* **Bi-Directional `activeWords` Invariant**:
  For every node $0 \le u < \text{nodeCount}$:
  $$\text{readLane(}u\text{)} \neq 0 \iff (\text{activeWords}[u \ggg 6] \ \& \ (1\text{L} \ll (u \ \& \ 63))) \neq 0$$
  * Every non-zero write (`orLane`, `writeLane`, walk scatter) sets `activeWords[u >>> 6] |= (1L << u)`.
  * Any prune (`0xC5`) or mask clear that reduces node $u$'s mask to `0` clears `activeWords[u >>> 6] &= ~(1L << u)`.
  * Unused upper bits above `numLanes` (`~activeMask`) MUST always be `0` in storage so `lanesX[u] != 0` is equivalent to `(readLane(u) & activeMask) != 0L`.
* **Backwards-Compatible `toLongArray` / Statement Materialization**:
  * Package-private direct array getters `lanes8()`, `lanes16()`, `lanes32()`, `lanes64()`, `activeWords()`, `activeWordCount()` feed hot-path opcode loops in `VmMultiLaneHandlers`.
  * `readLane(int u)` provides width-neutral unsigned `long` reads for `VmContextHash` (ICTX v1) and scalar helpers without allocating.
  * For `ImpulseStatementImpl` / legacy `lanes()` callers outside the VM hot path: provide `materializeWidenedLanes(VmQueryContext ctx)` (using `ctx.scratchLong(...)` or caller-supplied `long[]`) and keep the public deprecated/test constructor `VmLaneFrontier(LaneWidth, int, long, int, long[], Map)` populating the appropriate tier + `activeWords` for existing unit tests.

### 2.4 Tiered `ThreadLocalBufferPool` (`>=` Capacity Reuse & $O(\text{active})$ Sparse Cleanup)
Refactor `VmQueryContext.ThreadLocalBufferPool` (`VmQueryContext.java:460-610`):
1. Replace `ArrayList<long[]> freeFrontierBuffers` with tiered pools:
   * `ArrayList<byte[]> freeFrontierBytes` (`WIDTH_8`, max 32)
   * `ArrayList<short[]> freeFrontierShorts` (`WIDTH_16`, max 32)
   * `ArrayList<int[]> freeFrontierInts` (`WIDTH_32`, max 32)
   * `ArrayList<long[]> freeFrontierLongs` (`WIDTH_64`, max 32)
   * `ArrayList<long[]> freeActiveWords` (summary bitsets, max 32)
2. **Capacity-Bounded Best-Fit Reuse (`cand.length >= requiredLen`)**:
   * Instead of `if (cand.length == nodeCount)`, pick the smallest pooled array with `cand.length >= requiredLen` (bounded by `cand.length <= Math.max(4096, requiredLen * 4)` so a tiny 64-node test graph does not permanently hog a 10M-element array if a smaller slot is needed, while multi-domain hops like $50{,}000 \to 12{,}000 \to 50{,}000$ reuse pooled buffers with **zero heap allocations**).
   * Newly allocated arrays (`new byte[cap]`, `new short[cap]`, `new int[cap]`, `new long[cap]`) are already zero-initialized by the JVM specification (JLS §4.12.5).
3. **$O(\text{active})$ Sparse Reset on `recycleFrontier(VmLaneFrontier lf)`**:
   * Pooled arrays satisfy the **Clean-In-Pool Invariant**: every array sitting in `freeFrontierBytes`/`Shorts`/`Ints`/`Longs` and `freeActiveWords` is **all-zeros**.
   * When `VmQueryContext.reset()` recycles handles at end of query (preserving mid-query `OP_MOV` handle immutability!), `recycleFrontier(lf)` cleans **only** the touched indices via `Long.numberOfTrailingZeros`:
     ```java
     static void clearSparse(VmLaneFrontier lf) {
         long[] aw = lf.activeWords();
         int awCount = lf.activeWordCount();
         int n = lf.nodeCount();
         switch (lf.width()) {
             case WIDTH_8 -> {
                 byte[] a = lf.lanes8();
                 for (int wIdx = 0; wIdx < awCount; wIdx++) {
                     long w = aw[wIdx];
                     if (w == 0L) continue;
                     while (w != 0L) {
                         int bit = Long.numberOfTrailingZeros(w);
                         w &= (w - 1L);
                         int u = (wIdx << 6) + bit;
                         if (u < n) a[u] = 0;
                     }
                     aw[wIdx] = 0L;
                 }
             }
             // WIDTH_16 (short[]), WIDTH_32 (int[]), WIDTH_64 (long[]) mirror identically
         }
     }
     ```
   * Consequently, `acquireFrontier` performs **$O(1)$ reference assignment with zero `Arrays.fill`!**

### 2.5 Tiered Scratch Buffers for `FIXPOINT` (`0x04`) & Sub-Word Scatter Guard
* Add `scratchByte(int slot, int minCap)` and `scratchShort(int slot, int minCap)` (plus sparse-cleaned variants for multi-lane `delta` and `stepOut`) on `VmQueryContext` alongside existing `scratchInt` and `scratchLong`, plus two `scratchActiveWords` slots so `FIXPOINT` (`0xC3`/`0xC4` with `flags & 0x04`) runs in native `byte[]`/`short[]`/`int[]`/`long[]` with $O(\text{active})$ per-iteration delta propagation (`deltaActiveWords` $\to$ `stepActiveWords`) instead of dense `0 .. outLen - 1` loops!
* **Cross-Domain Bounds Separation (`AGENTS.md` §3.14)**:
  * On `0xC3` (`CSR`), source node bound is `Math.min(lfSrc.nodeCount(), rel.getNodeCount())` and target node bound `outLen` is target domain cardinality (for `rel.hasCsc()`, `Math.max(rel.getCscNodeCount(), accumulate || isFixpoint ? lfSrc.nodeCount() : 0)`, else `resolveTargetNodeCount`).
  * On `0xC4` (`CSC`), when `rel.hasCsc() && tripArr == null`, iterate **only active target nodes** `v` in `lfSrc.activeWords()` (`Long.numberOfTrailingZeros`), read `cscOffSeg` (`v .. v+1`) and `cscColSeg` (`rel.readSrcNodeId`), and scatter `vMask` into `lfOut` (`outLen = rel.getNodeCount()`), matching C++ `lane_walk_common.hpp:241-280`! Fall back to CSR edge scan only when `!rel.hasCsc() || tripArr != null`.
* **Sub-Word Concurrency Guard (`max_dop > 1`)**:
  * Document explicitly in `VmMultiLaneHandlers` that `0xC3`/`0xC4` scatter single-threaded across all 8–64 lanes simultaneously; if future intra-instruction `max_dop > 1` parallelization is ever added, `byte[]` and `short[]` cannot use non-atomic RMW (`lanes8[v] |= prop`) across threads and must use `MethodHandles.arrayElementVarHandle` bitwise atomic ops or CSC target partitioning.

---

## 3. File-by-File Implementation Plan

### 3.1 `impulse-vm/src/main/java/org/impulsegraph/vm/VmLaneFrontier.java`
* Replace single `private long[] lanes` (`line 66`) with `lanes8`, `lanes16`, `lanes32`, `lanes64`, `activeWords`, and `activeWordCount`.
* Replace `reset(...)` (`lines 97-105`) with zero-`Arrays.fill` tiered binding methods (`reset8`, `reset16`, `reset32`, `reset64`) receiving pre-zeroed arrays from `ThreadLocalBufferPool`.
* Add hot-path inline-friendly accessors (zero allocations, zero boxing — verified by `javap -c`):
  * `lanes8()`, `lanes16()`, `lanes32()`, `lanes64()`, `activeWords()`, `activeWordCount()`.
  * `long readLane(int u)` using `Byte.toUnsignedLong` / `Short.toUnsignedLong` / `Integer.toUnsignedLong` / `lanes64[u]`.
  * `void orLane(int u, long mask)` and `void writeLane(int u, long mask)` maintaining both unused-upper-bit masking (`mask & activeMask`) and the bi-directional `activeWords` invariant.
  * `boolean hasAnyActive()` scanning `0 .. activeWordCount - 1` (`activeWords[w] != 0L` — **64x faster** than scanning `0 .. nodeCount - 1` for `FLAG_ZF`!).
* Keep `copyIntoLongArray(long[] dst, int maxLen)` for `ImpulseStatementImpl` and non-hot test callers.

### 3.2 `impulse-vm/src/main/java/org/impulsegraph/vm/VmQueryContext.java`
* Extend `ThreadLocalBufferPool` (`lines 460-610`) with:
  * `freeFrontierBytes`, `freeFrontierShorts`, `freeFrontierInts`, `freeFrontierLongs`, `freeActiveWords`.
  * `acquireFrontier(LaneWidth width, int numLanes, long activeMask, int nodeCount)` using `cand.length >= requiredLen` best-fit capacity matching and zero `Arrays.fill`.
  * `recycleFrontier(VmLaneFrontier lf)` calling `clearSparse(lf)` (`Long.numberOfTrailingZeros`) before returning both the tiered lane buffer and `activeWords` buffer to their pools.
  * `scratchByte(int slot, int minCap)` and `scratchShort(int slot, int minCap)` (2 slots each) for native-width `FIXPOINT` `delta` and `stepOut` buffers, plus sparse cleanup helper so `FIXPOINT` never executes `Arrays.fill(0, outLen)` on multi-million-node domains.

### 3.3 `impulse-vm/src/main/java/org/impulsegraph/vm/VmMultiLaneHandlers.java`
* **`0xC0` (`OP_LANE_INIT_SEEDS`) & `0xC1` (`OP_LANE_INIT_GROUPS`) (`lines 232-406`)**:
  * Populate seeds/groups via `lf.orLane(u, 1L << b)` (sets native `byte`/`short`/`int`/`long` slot + `activeWords[u >>> 6]`).
* **`0xC2` (`OP_LANE_SET_EDGE_MASK`) (`lines 408-475`)**:
  * Copy active lane masks sparsely from `lfSrc` to `lfOut` via `lfSrc.activeWords()` (`Long.numberOfTrailingZeros`) in $O(\text{active})$ instead of scanning `0 .. lfSrc.nodeCount() - 1`.
* **`0xC3` & `0xC4` (`OP_LANE_CSR_WALK` / `OP_LANE_CSC_WALK`) (`lines 72-214, 477-535`)**:
  * Replace dense `stepLanesOnce` with width-specialized `stepSingleRelation8`, `stepSingleRelation16`, `stepSingleRelation32`, `stepSingleRelation64`:
    * **Forward CSR (`!isCsc`)**: Iterate `lfSrc` (or `delta`) active nodes strictly via `curActiveWords` + `Long.numberOfTrailingZeros(word)`. Read native `curLanes[u]`, mask, traverse `rowSeg`/`colSeg` (`fast32` unaligned `JAVA_INT_UNALIGNED` fast path + generic `readEdgeIndex`/`readNodeId` + mock fallback), scatter to `nextLanes[v]`, and set `nextActiveWords[v >>> 6] |= (1L << v)` whenever `nw != prev`.
    * **Reverse CSC (`isCsc`)**: When `rel.hasCsc() && tripArr == null`, iterate active nodes `v` via `curActiveWords` + `Long.numberOfTrailingZeros(word)`, traverse `cscRowOffsetsSegment` / `cscColumnTargetsSegment` (`fast32` `JAVA_INT_UNALIGNED` + generic `readEdgeIndex`/`readSrcNodeId`), and scatter to `nextLanes[u]` + `nextActiveWords[u >>> 6]`. Fall back to CSR scan (gated by active source `v` check) only when `!rel.hasCsc() || tripArr != null`.
    * **`ACCUMULATE` & `FIXPOINT` (`0x04`)**: Copy initial active seeds sparsely via `lfSrc.activeWords()`, run `FIXPOINT` loop using tiered `delta`/`stepOut` + `deltaActiveWords`/`stepActiveWords` ($O(\text{newlyReached})$ per hop), and sparse-clear `delta`/`stepOut` scratch slots on exit.
    * Set `FLAG_ZF` via `!lfOut.hasAnyActive()`.
* **`0xC5` (`OP_LANE_PRUNE_MASK`) (`lines 537-680`)**:
  * Iterate strictly over `lfSrc.activeWords()` (`Long.numberOfTrailingZeros`), write surviving masks (`m != 0L`) into `lfOut` via `lfOut.writeLane(u, m)` (setting `lfOut.activeWords` only for surviving nodes), and set `FLAG_ZF` via `!lfOut.hasAnyActive()`.
* **`0xC6` (`OP_LANE_EXTRACT_BITSET`), `0xC7` (`OP_LANE_POPCOUNT`), `0xC8` (`OP_LANE_REDUCE_COUNTS`), `0xC9` (`OP_VECTOR_SCORE_BATCH`), `0xCA` (`OP_VECTOR_TOPK_BITSET`), `0xCB` (`OP_VECTOR_RERANK_DOT`)**:
  * Replace all `for (int u = 0; u < nodeCount; u++)` scans over `lf.lanes()` with sparse `lf.activeWords()` (`Long.numberOfTrailingZeros`) loops reading unsigned masks via `lf.readLane(u)` (or tiered switch in `0xC7`/`0xC8`).

### 3.4 Supporting VM, Hash, Invariant & Statement Classes
* **`VmContextHash.java`**:
  * Update `TYPE_LANE_FRONTIER` ICTX v1 serialization to emit `lf.numLanes()`, `lf.width().maxLanes()`, `lf.activeMask()`, `lf.nodeCount()`, and `lf.readLane(n) & lf.activeMask()` as `U64LE` for `0 .. lf.nodeCount() - 1` — preserving **100% bitwise ICTX v1 parity** with C++ and OCaml.
* **`VmHandlers.java` (`0x90`–`0x93`)**:
  * Update any `TYPE_LANE_FRONTIER` cast/inspection (`0x93` `OP_TYPE_CAST`) to use `lf.activeWords()` + `lf.readLane(u)` instead of `lf.lanes()`.
* **`VmInvariants.java`**:
  * Strengthen post-instruction invariant checks on allocated `VmLaneFrontier` handles (when assertions/invariant verification are enabled):
    1. Exactly one of `lanes8`, `lanes16`, `lanes32`, `lanes64` is non-null and matches `lf.width()`, with `array.length >= lf.nodeCount()`.
    2. Bi-directional `activeWords` invariant holds across `0 .. lf.nodeCount() - 1` (`lf.readLane(u) != 0L` iff `(lf.activeWords()[u >>> 6] & (1L << u)) != 0L`) and no bits $\ge \text{nodeCount}$ are set in `activeWords`.
    3. Unused upper bits are clean: `(lf.readLane(u) & ~lf.activeMask()) == 0L`.
* **`statement/ImpulseStatementImpl.java` & `DefaultMultiLaneTraversal.java`**:
  * Preserve `long[]` caller output compatibility for raw `TYPE_LANE_FRONTIER` statement results via `lf.copyIntoLongArray(...)`, while zero-allocating inside the VM execution loop.

---

## 4. Verification, Bytecode Linting & Benchmark Plan

### 4.1 Mandatory Hot-Path Bytecode Linter & Existing Suites
* `mvn test -Dtest=VmInvariantsAndCoverageTest` — **MUST pass `testZeroHotPathOpcodeBytecodeAllocationsAndAutoboxing`** (`javap -c` static verification of zero `new`/`newarray`/`Arrays.copyOf`/`valueOf`/capturing lambdas across every method in `VmLaneFrontier` and `VmMultiLaneHandlers`) and maintain branch/testcase coverage thresholds.
* Update white-box tests directly calling `lf.lanes()` (`MultiLaneAndVectorMaterializationTest`, `VmCombinatorialTypeMatrixTest`, `MathAndReducerEdgeCasesTest`) to use `lf.readLane(u)` or tiered array assertions.

### 4.2 Five Mandatory New JUnit Tests (`MultiLanePhysicalPackingAndPoolTest.java`)
1. **`testPhysicalArrayTierBindingAcross17BoundarySeedCounts`**:
   * Run `OP_LANE_INIT_SEEDS` across all 17 `VmLaneFrontier.NORMATIVE_BOUNDARY_SEED_COUNTS` (`0, 1, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, ...`).
   * Assert `lanes8 != null` (and `16/32/64 == null`) for $K \le 8$, `lanes16 != null` for $9 \le K \le 16$, `lanes32 != null` for $17 \le K \le 32$, and `lanes64 != null` for $33 \le K \le 64$.
2. **`testSignedJavaPrimitiveHighBitSignExtensionCanary`**:
   * Seed MSB lanes `7` (`0x80` in `byte[]`), `15` (`0x8000` in `short[]`), `31` (`0x8000_0000` in `int[]`), and `63` (`0x8000_0000_0000_0000L` in `long[]`).
   * Execute `0xC0` $\to$ `0xC2` $\to$ `0xC3` (single + `ACCUMULATE` + `FIXPOINT`) $\to$ `0xC4` (both off-heap CSC and CSR fallback) $\to$ `0xC5` $\to$ `0xC6` / `0xC7` / `0xC8`.
   * Assert `lf.readLane(u)` equals exact `1L << 7`, `1L << 15`, `1L << 31`, `1L << 63` (zero sign-extended upper bits!), `OP_LANE_POPCOUNT` returns `1` (never `57`, `49`, or `33`), and neighbor slots `u - 1` / `u + 1` remain `0`.
3. **`testActiveWordsBiDirectionalInvariantAndSparsePoolRecycleCanary`**:
   * On a $N = 100{,}000$-node graph, execute 50 randomized sparse multi-lane walks + `0xC5` prunes across mixed widths (`WIDTH_8` $\leftrightarrow$ `WIDTH_16` $\leftrightarrow$ `WIDTH_32` $\leftrightarrow$ `WIDTH_64`), calling `ctx.reset()` between runs.
   * Assert after every instruction that `VmInvariants` bi-directional `activeWords` check holds and recycled buffers contain zero stale bits on re-acquisition.
4. **`testCrossDomainCapacityReuseZeroAllocations`**:
   * Execute a multi-domain walk `DomainA (N = 4096) -> DomainB (N = 1024) -> DomainA (N = 4096)` across two consecutive `ctx.reset()` cycles.
   * Assert via `assertSame` on the backing `byte[]`/`short[]`/`int[]`/`long[]` and `activeWords` references that the second query reuses the pooled arrays from `ThreadLocalBufferPool` (`cand.length >= requiredLen`) with **zero new array allocations**.
5. **`testInPlaceRegisterAliasingAndMovSafetyAcrossAllWidths`**:
   * Execute `OP_LANE_INIT_SEEDS R0` $\to$ `OP_MOV R1, R0` $\to$ `OP_LANE_CSR_WALK R0, R0, rel0` $\to$ `OP_LANE_PRUNE_MASK R0, R0, ...` for $K \in \{8, 16, 32, 64\}$.
   * Assert `R1` retains the untouched initial seed frontier while `R0` holds the walked + pruned frontier, matching non-aliased register execution bit-for-bit.

### 4.3 Four-Way Polyglot Parity & Benchmark Verification (`AGENTS.md` §3.13 & §3.18)
* Run `python3 ~/impulse/impulse-verification/tools/four_way_validation.py` — **100% identical register state, `ZF`, `PC`, status codes, and ICTX v1 hashes** across C++ (`-O3`), C++ (`-O0`), Java 25, and OCaml.
* Run `MultiLaneVmBenchmarkTest` and cross-language C++ vs. Java 25 multi-lane traversal benchmarks (`K = 8, 16, 32, 64`, 1-hop / 3-hop / fixpoint BFS) to verify warmed-up Java 25 executes within **$\le 2\times$ of C++** (`AGENTS.md` §3.13).
