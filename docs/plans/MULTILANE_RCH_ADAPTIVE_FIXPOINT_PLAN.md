# Multi-Lane `MITra-RCH` Gauss-Seidel `FIXPOINT` Coalescing & Adaptive Push-CSR / Pull-CSC Walk (`Java 25`)

* **Repository**: `impulse-graph-java` (`impulse-vm`)
* **Tracking Issue**: [`impulse-graph-java#42`](https://github.com/impulse-graph/impulse-graph-java/issues/42)
* **Release Horizon**: Post-`v0.10.0` feature lockdown (`v0.10.x` / `v0.11.0`), stacked on [`impulse-graph-java#41`](https://github.com/impulse-graph/impulse-graph-java/issues/41) (`feat/multilane-physical-packing`)
* **Companion Issues**:
  * C++20 Counterpart: [`impulse-graph-core#85`](https://github.com/impulse-graph/impulse-graph-core/issues/85) (stacked on [`impulse-graph-core#83`](https://github.com/impulse-graph/impulse-graph-core/issues/83))
  * `v0.11+` Numeric State Projection Spec (Lesson 4.2): [`impulse-graph-spec#23`](https://github.com/impulse-graph/impulse-graph-spec/issues/23)
* **Target Branch**: `feat/multilane-rch-adaptive-fixpoint`
* **Binary / ISA Impact**: **Zero `.imps` or `impOps` bytecode encoding changes**; preserves 100% 4-way polyglot parity (`AGENTS.md` §3.18) and `javap -c` zero-allocation hot-path compliance (`AGENTS.md` §3.19).

---

## 1. Motivation & Cross-Language Parity Context (`AGENTS.md` §3.13)

1. **Algorithmic Gain (`1.78x–5.45x` Over Synchronous `FIXPOINT`)**:
   In `impulse-benchmarks/BENCHMARK_FINDINGS.md` (`mitra_aside_bench`, `K = 2..512`), replacing 3-buffer Jacobi level-synchronous `FIXPOINT` (`MITra-BFS`) with 2-buffer `vid`-ordered Gauss-Seidel coalescing (`MITra-RCH`) delivered **`3.84x` speedup at `K=64` on `100K` nodes / `1M` edges** and **`3.52x` speedup at `K=64` on `1M` nodes / `10M` edges** (`4.34x` at `K=16`).
2. **Mandatory Java/C++ Parity (`AGENTS.md` §3.13)**:
   Once `impulse-graph-core#85` upgrades C++ `OP_LANE_CSR_WALK` (`0xC3`) and `OP_LANE_CSC_WALK` (`0xC4`) `FIXPOINT` (`0x04`) to `MITra-RCH` + direction-optimizing Pull-CSC, Java `VmMultiLaneHandlers` MUST execute the same algorithm over `#41`'s tiered primitive arrays (`byte[]`, `short[]`, `int[]`, `long[]`) so warmed-up Java 25 stays within **$\le 2\times$ of C++**.
3. **Eliminating `stepOut` Scratch Traffic**:
   Current Java `FIXPOINT` (`VmMultiLaneHandlers.java:498-522` on `main`) allocates/clears **two** scratch arrays (`delta` and `stepOut`) and scans `0 .. outLen - 1` every iteration. Stacked on `#41`, `MITra-RCH` drops `stepOut` completely in unbounded `FIXPOINT`, operating with **only `lfOut` + 1 tiered `delta` buffer + 1 `deltaActiveWords` bitset**.

---

## 2. Architectural Design & Hot-Path Guardrails

### 2.1 Strict `javap -c` Zero-Allocation & Unsigned Sub-Word Rules (`AGENTS.md` §3.19)
* Every method added to `VmMultiLaneHandlers` and `VmLaneFrontier` MUST pass `VmInvariantsAndCoverageTest#testZeroHotPathOpcodeBytecodeAllocationsAndAutoboxing`:
  * Zero `new`, `newarray`, `Arrays.copyOf`, autoboxing (`Byte.valueOf` / `Short.valueOf` / `Integer.valueOf` / `Long.valueOf`), or variable-capturing lambdas (`invokedynamic`).
  * All `delta` (`byte[]`, `short[]`, `int[]`, `long[]`) and `deltaActiveWords` (`long[]`) buffers come strictly from `VmQueryContext` thread-local scratch slots introduced in `#41`.
* **Unsigned Sub-Word Bitwise Operations**:
  * In Java, `byte` and `short` promote to signed `int` on bitwise `|`, `&`, `~`.
  * Always mask with unsigned tier mask (`int mask8 = (int) (activeMask & 0xFFL)`, `int mask16 = (int) (activeMask & 0xFFFFL)`) and widen with `Byte.toUnsignedInt` / `Short.toUnsignedInt` before comparing against `mask8` / `mask16` in early-saturation checks (`curV == mask8`), otherwise lane `7` (`(byte) 0x80 == -128`) will compare `-128 != 255` and **never trigger early saturation**!

### 2.2 Tiered In-Place Gauss-Seidel (`MITra-RCH`) Kernel
For unbounded `FIXPOINT` (`!ctx.hasFixpointMaxHops()`):

1. **Forward `0xC3` (`OP_LANE_CSR_WALK`, `!isCsc`) — Ascending `wIdx` Sweep**:
   * Iterate `wIdx = 0 .. activeWordCount - 1`:
     ```java
     while (deltaActiveWords[wIdx] != 0L) {
         long word = deltaActiveWords[wIdx];
         deltaActiveWords[wIdx] = 0L;
         while (word != 0L) {
             int bit = Long.numberOfTrailingZeros(word);
             word &= (word - 1L);
             int u = (wIdx << 6) + bit;
             if (u >= csrLim) break;
             // Read & clear delta[u] in native tier width
             // For each outgoing edge e = start .. end - 1 -> target v:
             //   nb = (prop & ~trip) & ~outLanes[v]
             //   if (nb != 0):
             //     outLanes[v] |= nb; delta[v] |= nb; outActiveWords[v >>> 6] |= (1L << v);
             //     int vw = v >>> 6; long vbit = 1L << v;
             //     if (v <= u) { deltaActiveWords[vw] |= vbit; anyBackEdge = true; }
             //     else if (vw == wIdx) { word |= vbit; }
             //     else { deltaActiveWords[vw] |= vbit; }
         }
     }
     ```
2. **Reverse `0xC4` (`OP_LANE_CSC_WALK`, `isCsc`) — Descending `wIdx` Sweep**:
   * Iterate `wIdx = activeWordCount - 1` down to `0`, extracting highest set bit first via `int bit = 63 - Long.numberOfLeadingZeros(word); word ^= (1L << bit);`:
     * If `u >= v`: `deltaActiveWords[uw] |= ubit; anyBackEdge = true;`
     * Else if `uw == wIdx`: `word |= ubit;` (cascades within current descending word!)
     * Else (`uw < wIdx`): `deltaActiveWords[uw] |= ubit;` (cascades within current descending sweep!)

### 2.3 Adaptive Push-CSR / Pull-CSC Direction Switching
Before each outer sweep in unbounded `FIXPOINT` on a single physical off-heap `RelationSnapshot`:
* **Eligibility**:
  * Forward `0xC3` (`!isCsc`): `rel.hasCsc() && lfOut.getEdgeTripMask(relId) == null && rel.getNodeCount() == outLen`.
  * Reverse `0xC4` (`isCsc`): `rel.hasCsr() && lfOut.getEdgeTripMask(relId) == null && rel.getCscNodeCount() == outLen`.
* **Threshold**: Compute `activeNodes` via `Long.bitCount(deltaActiveWords[w])` over `0 .. activeWordCount - 1`. Switch to Pull for the current sweep iff `activeNodes >= Math.max(64, outLen / 20)`.
* **Off-Heap FFM Pull Sweep (`ValueLayout.JAVA_INT_UNALIGNED` Fast Path + Generic Width Fallback)**:
  1. Sparse-clear `delta` via `deltaActiveWords` ($O(\text{active})$) so `delta` will hold *only* newly reached bits from this Pull sweep.
  2. Scan `v = 0 .. outLen - 1` sequentially:
     * Read `curV` (unsigned-masked, e.g., `Byte.toUnsignedInt(outLanes8[v])` for `WIDTH_8`).
     * **Immediate Saturation Skip**: `if (curV == mask8) continue;` (skips `cscOffSeg` read completely).
     * Accumulate `acc |= Byte.toUnsignedInt(outLanes8[u])` across incoming `u` from `cscOffSeg` / `cscColSeg`, breaking early as soon as `acc == mask8`.
     * Compute `int nb = (acc & mask8) & ~curV;`. If `nb != 0`, write `outLanes8[v] = (byte) (curV | nb)`, `delta8[v] = (byte) nb`, set `(1L << v)` in both `outActiveWords[v >>> 6]` and `deltaActiveWords[v >>> 6]`, and set `anyNew = true`.
  3. If the next sweep has fewer than `outLen / 20` newly reached nodes, execution seamlessly resumes sparse `MITra-RCH` Push using `delta` + `deltaActiveWords`!

### 2.4 Bounded-Hop Guard & 4-Way Fuel/Flag Parity (`AGENTS.md` §3.18)
* **`fixpointMaxHops` Guard**: Expose `hasFixpointMaxHops()` / `fixpointMaxHops()` on `VmQueryContext` (matching C++ `impulse_vm_context`). Whenever `hasFixpointMaxHops()` is true, execute level-synchronous Jacobi (`delta -> stepOut`) for at most `fixpointMaxHops` hops so multi-hop forward cascades cannot overshoot $k$ hops.
* **4-Way Fuel & `FLAG_ZF` Parity**:
  * Coordinate with `impulse-graph-core#85` and `impulse-ocaml` (`vm_multilane.ml`) so fuel consumption on `0xC3`/`0xC4` `FIXPOINT` is instruction-level (invariant to Push vs. Pull-CSC sweep counts) with deterministic `MAX_FIXPOINT_SWEEPS = 1024`, and `FLAG_ZF` is `!lfOut.hasAnyActive()` per `VM_SPECIFICATION.md` §6.11.2.

---

## 3. File-by-File Implementation Plan

1. **`impulse-vm/src/main/java/org/impulsegraph/vm/VmQueryContext.java`**:
   * Ensure `hasFixpointMaxHops()` / `fixpointMaxHops()` getters/setters mirror C++ `impulse_vm_context` (resetting in `reset()`).
2. **`impulse-vm/src/main/java/org/impulsegraph/vm/VmMultiLaneHandlers.java`**:
   * Add `runFixpointRchAdaptive8`, `runFixpointRchAdaptive16`, `runFixpointRchAdaptive32`, `runFixpointRchAdaptive64` called from `case 0xC3, 0xC4` when `isFixpoint && !ctx.hasFixpointMaxHops()`.
   * Implement off-heap `pullCscSweep8/16/32/64` (`!isCsc`) and `pullCsrSweep8/16/32/64` (`isCsc`) with `JAVA_INT_UNALIGNED` fast32 path, generic `readEdgeIndex`/`readSrcNodeId` fallback, unsigned saturation checks (`curV == activeMaskW`), and sparse `delta` cleanup on exit.
   * Keep synchronous `runFixpointJacobi8/16/32/64` for `ctx.hasFixpointMaxHops() == true`.
3. **`impulse-benchmarks/java-benchmarks/.../ImpulseJavaBenchRunner.java`** *(follow-up benchmark hook)*:
   * Add `multilane-rch` workload (`K = 8, 16, 32, 64`) to compare Java 25 vs. C++20 `MITra-RCH` directly in `run_benchmarks.py`.

---

## 4. Required Tests & Verification Gates

1. **Bytecode Zero-Allocation Audit**:
   * `mvn test -pl impulse-vm -Dtest=VmInvariantsAndCoverageTest` — verify `testZeroHotPathOpcodeBytecodeAllocationsAndAutoboxing` passes with zero `new`/`newarray`/`valueOf`/`invokedynamic` in `VmMultiLaneHandlers` and `VmLaneFrontier`.
2. **New JUnit Suite (`MultiLaneRchAdaptiveFixpointTest.java`)**:
   * **`testRchBitExactParityAcrossTopologiesAndUnsignedSignBits`**: Compare `MITra-RCH` against reference Jacobi BFS across `K = 8` (lane 7 = `0x80`), `K = 16` (lane 15 = `0x8000`), `K = 32` (lane 31 = `0x8000_0000`), and `K = 64` (lane 63) on forward DAGs, reverse chains (`255 -> 254 -> ... -> 0` crossing word boundaries `64 -> 63`), strongly connected cycles, self-loops, and `0xC2` counterfactual edge trip masks (`0xC3` and `0xC4`).
   * **`testUnsignedSignBitSaturationSkipInPullCsc`**: Specifically test `K = 8` (all 8 lanes active $\implies$ `(byte) 0xFF == -1`) and `K = 16` (`(short) 0xFFFF == -1`) in dense Pull-CSC mode (`activeNodes >= N / 20`), asserting `Byte.toUnsignedInt` / `Short.toUnsignedInt` triggers the early saturation skip cleanly without infinite loops or missed lanes.
   * **`testBoundedFixpointMaxHopsUsesJacobi`**: Verify `fixpointMaxHops = 3` on ascending chain `0 -> 1 -> ... -> 20` stops at node `3`.
   * **`testSparsePushToDensePullToSparsePushTransition`**: Verify bit-exact parity and `VmInvariants` bi-directional `activeWords` cleanliness across a graph that transitions `Push -> Pull -> Push` within one `FIXPOINT` execution.
3. **4-Way Polyglot Parity & $\le 2\times$ C++ Benchmark Gate (`AGENTS.md` §3.13, §3.18)**:
   * Pass `python3 ~/impulse/impulse-verification/tools/four_way_validation.py` and `fuzz_vm_contracts.py`.
   * Verify warmed-up Java 25 executes within $\le 2\times$ of C++20 on `synth_medium` and `synth_large` multi-lane reachability.
