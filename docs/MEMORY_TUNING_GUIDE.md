# Memory & Performance Tuning Guide — Impulse Graph Engine (Java)

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and reflects the C-ABI Binary Snapshot v0.9.0 normative format.

This guide details Foreign Function & Memory (FFM) lifecycle management, off-heap zero-copy memory mapping, 128-byte hardware alignment, hugepage configuration, primitive integer width selection, and JVM Vector API tuning for **`impulse-graph-java`** running on **Java 21 LTS+** (with forward compatibility for Java 22, 23, 24, and 25+).

---

## 1. FFM Off-Heap Architecture & Storage Structures (`impulse-storage`)

Impulse Graph stores graph topology and attributes completely off-heap via FFM [`MemorySegment`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/RelationSnapshot.java) buffers bound to a [`java.lang.foreign.Arena`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/BinarySnapshotLoader.java). The engine operates directly over memory-mapped `.imps` files without deserializing data into Java heap objects.

### 1.1 Off-Heap Segment Hierarchy

When a snapshot is loaded via [`BinarySnapshotLoader.loadSnapshot(...)`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/BinarySnapshotLoader.java):
1. **Memory Mapping**: The `.imps` file is mapped via `FileChannel.map(FileChannel.MapMode.READ_ONLY, 0, size, arena)`, producing an off-heap `MemorySegment`.
2. **Kernel Prefetching (`MADV_WILLNEED`)**: `BinarySnapshotLoader` immediately invokes `segment.load()`. At the OS level, this issues an `madvise(..., MADV_WILLNEED)` call, prefetching pages into the OS page cache asynchronously to eliminate soft page fault stalls during cold start.
3. **Relation Slices**: The root segment is sliced into zero-copy sub-segments encapsulated inside [`RelationSnapshot`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/RelationSnapshot.java):
   * `rowOffsetsSegment`: CSR row offsets array (`(N + 1) * edgeIndexWidth` bytes).
   * `columnTargetsSegment`: CSR target node IDs (`E * nodeIdWidth` bytes).
   * `cscRowOffsetsSegment` & `cscColumnTargetsSegment`: Optional CSC reverse transpose index buffers.
   * `attributeSegments`: Structure-of-Arrays (SoA) contiguous primitive and vector arrays.
   * `validitySegments`: 128-byte padded bitmasks for nullable attributes.
4. **Container**: [`GraphSnapshot`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/GraphSnapshot.java) aggregates all domain relation snapshots and string catalog metadata.

---

## 2. FFM Arena Lifecycle Management (`java.lang.foreign.Arena`)

FFM arenas govern off-heap memory lifetimes. Choosing the proper arena determines thread-safety and memory unmapping behavior:

### 2.1 `Arena.ofShared()` vs. `Arena.ofConfined()`

| Arena Type | Thread Concurrency Model | Engine Usage | Lifecycle & Deallocation |
| :--- | :--- | :--- | :--- |
| **`Arena.ofShared()`** | Multi-Threaded | Active Graph Snapshots & SWMR Query Engines | Safe for concurrent worker and virtual carrier threads. Memory is unmapped when `close()` or `drainAndClose()` is explicitly called. |
| **`Arena.ofConfined()`** | Single-Threaded | Snapshot Builders & Scratch Computations | Strictly confined to allocating thread. Automatically unmaps memory upon `try-with-resources` block exit. Used internally by [`DefaultSnapshotBuilder`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/DefaultSnapshotBuilder.java). |

### 2.2 Production SWMR Snapshot Swapping & Safe Draining

In high-throughput 24/7 environments, incoming CDC streams or scheduled updates generate new `.imps` snapshots. To avoid JVM segmentation faults from unmapping off-heap memory while query threads are reading:

1. **Active Query Accounting**: [`GraphSnapshot`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/GraphSnapshot.java) maintains an off-heap reader count via `LongAdder`:
   * `enterQuery()`: Increments active query count.
   * `exitQuery()`: Decrements active query count.
   * `awaitDrained(timeout, unit)`: Non-blocking spin-wait until active reader count drops to 0.
   * `drainAndClose(timeout, unit)`: Waits for readers to complete, then unmaps the arena.
2. **Atomic Pointer Swapping**: Use [`SnapshotSwapManager`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/SnapshotSwapManager.java) for atomic A/B pointer swaps:

```java
// Initialize swap manager with active snapshot
SnapshotSwapManager<GraphSnapshot> swapManager = new SnapshotSwapManager<>(initialSnapshot);

// Query Path (Multi-Threaded Readers)
var holder = swapManager.acquireCurrent();
if (holder != null) {
    try {
        GraphSnapshot snap = holder.getResource();
        // Execute zero-copy vector queries...
    } finally {
        holder.release(); // Decrements epoch reader count
    }
}

// Ingestion / Refresh Path (Atomic Pointer Swap)
try (Arena newArena = Arena.ofShared()) {
    var newLoaded = BinarySnapshotLoader.loadSnapshot(Path.of("graph_v2.imps"), newArena);
    GraphSnapshot newSnapshot = newLoaded.getGraph();
    
    // Atomically swap current pointer. Spawns a virtual thread that spins
    // until oldHolder activeReaders reaches 0, then cleanly calls close().
    swapManager.swap(newSnapshot);
}
```

---

## 3. 128-Byte Hardware Alignment & 4KB Page Layout

To maximize memory subsystem throughput and enable direct vector hardware instructions, Impulse Graph enforces strict memory alignment throughout the snapshot format:

### 3.1 128-Byte Hardware SIMD & Direct I/O Boundary
Every topological array, SoA attribute column, and validity bitmap in `.imps` is aligned to a **128-byte boundary** (`(offset + 127) & ~127`):
* **AVX-512 Vector Registers**: 512-bit registers (`zmm0`..`zmm31`) require 64-byte alignment; 128-byte alignment guarantees two back-to-back 512-bit vector registers or unrolled dual-issue SIMD pipelines never cross a hardware cache-line boundary.
* **GPU Warp Coalescing**: Aligns with 128-byte memory transaction segments for NVIDIA GPUDirect Storage (`cuFile`) and GPU warp memory access.
* **TPU Vector Tiles**: Matches Google TPU matrix multiply vector tile buffers.

```
 128-Byte Aligned Memory Block Layout
 ┌───────────────────────────────────────┬───────────────────────────────────────┐
 │       64-Byte CPU Cache Line 0        │       64-Byte CPU Cache Line 1        │
 ├───────────────────────────────────────┼───────────────────────────────────────┤
 │ 512-bit AVX-512 Lane 0 (16 x float32) │ 512-bit AVX-512 Lane 1 (16 x float32) │
 └───────────────────────────────────────┴───────────────────────────────────────┘
 ▲
 128-Byte Hardware Aligned Offset
```

### 3.2 4KB OS Page Alignment
* **Page 0 Header**: Bytes `0x0000` to `0x0FFF` (4096 bytes) house the 64-byte baseline header with 4032 bytes of reserved alignment padding (`DataOffset = 4096`).
* **Catalog & Relation Directory**: Page 1 begins at byte 4096, containing the Shared String Table and 128-byte relation directory entries.
* **Payload Blocks**: All relation blocks and footer metadata blocks are padded to 4096-byte boundaries (`(offset + 4095) & ~4095`).

### 3.3 Nullability Validity Bitmap Padding
Nullable attributes encode a validity bitmap where 1 bit represents entity nullability. The bitmap size in bytes is padded to the nearest 128-byte boundary:
$$\text{BitmapBytes} = \left(\left(\frac{K + 63}{64} \times 8\right) + 127\right) \ \& \ \sim 127\text{L}$$
The attribute payload array starts immediately after the padded bitmap, ensuring that attribute data values remain strictly 128-byte aligned for SIMD vector loads.

---

## 4. Recommended JVM Tuning Flags

Because graph topology and attributes reside completely off-heap in memory-mapped files, JVM heap sizing and configuration differ fundamentally from traditional graph database engines.

### 4.1 Production JVM Command Line

```bash
java --enable-preview \
     --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockDiagnosticVMOptions \
     -XX:UseAVX=3 \
     -XX:+UseLargePages \
     -XX:LargePageSizeInBytes=2m \
     -XX:+UseTransparentHugePages \
     -XX:+UseZGC \
     -XX:+ZGenerational \
     -Xms2g -Xmx4g \
     -jar your-application.jar
```

### 4.2 Flag Rationale & Analysis

| JVM Argument | Purpose & Architectural Impact |
| :--- | :--- |
| **`--enable-preview`** | Enables preview features (FFM on Java 21 LTS baseline). Mandatory for preview compilation. |
| **`--add-modules jdk.incubator.vector`** | Binds the SIMD Vector API module for AVX-512, AVX2, and ARM Neon vector instruction synthesis. |
| **`--enable-native-access=ALL-UNNAMED`** | Grants zero-overhead off-heap native memory segment access and unmapping permissions. |
| **`-XX:UseAVX=3`** | Enables AVX-512 instruction generation on Intel/AMD x86-64 processors. Sets `PREFERRED_VECTOR_BIT_WIDTH = 512`. |
| **`-XX:+UseLargePages`**<br>**`-XX:LargePageSizeInBytes=2m`** | Configures 2MB huge pages for JVM metadata and code cache, reducing TLB miss latency. |
| **`-XX:+UseTransparentHugePages`** | Advises the Linux kernel to back memory-mapped pages with Transparent Huge Pages (THP). |
| **`-XX:+UseZGC -XX:+ZGenerational`** | Low-latency generational garbage collection with sub-millisecond pauses for carrier and query threads. |
| **`-Xms2g -Xmx4g`** | **Lean Heap Sizing**: Sets a small heap (2–4 GB). Allocating large heaps (e.g. `-Xmx32g`) locks physical RAM away from the OS Page Cache, degrading `.imps` zero-copy I/O throughput. |

### 4.3 Engine Optimization Thresholds ([`OptimizerConfig`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/config/OptimizerConfig.java))

The following Java system properties tune compiler heuristic thresholds:

```bash
# Minimum node degree required to activate 512-bit SIMD Vector API fused edge attribute filters
-Dimpulse.optimizer.simd.min_degree=64

# Multiplicity threshold for fusing 2-hop traversals directly in CPU registers (OP_CSR_WALK_2HOP)
-Dimpulse.optimizer.2hop.max_multiplicity=1.5

# Master toggle for experimental compiler optimization passes
-Dimpulse.optimizer.experimental=false

# Intra-query OpenMP-style degree of parallelism (default: Available CPU cores)
export IMPULSE_MAX_DOP=16
```

---

## 5. OS & Linux Kernel Memory Tuning

For high-concurrency production deployments hosting multi-gigabyte or terabyte snapshots:

### 5.1 Virtual Memory Mapping Limits (`vm.max_map_count`)
By default, Linux limits a process to 65,530 memory mappings. Large multi-domain graphs with many relations and tablespace partitions will fail with `java.io.IOException: Map failed` if this limit is reached.
```bash
# Persist in /etc/sysctl.conf
sudo sysctl -w vm.max_map_count=1048576
```

### 5.2 Transparent Huge Pages (THP) for Zero-Copy Mappings
Traversing sparse graphs (e.g. billion-edge BFS or PageRank) involves random edge offset lookups. Under standard 4KB pages, a 100GB graph requires over 26 million TLB entries, resulting in severe TLB cache thrashing. With 2MB huge pages, the TLB footprint drops 512x to ~51,200 entries:
```bash
# Enable madvise-based transparent hugepages
echo madvise | sudo tee /sys/kernel/mm/transparent_hugepage/enabled
echo advise  | sudo tee /sys/kernel/mm/transparent_hugepage/shmem_enabled
```

### 5.3 Page Cache & Swappiness
Because `.imps` files are opened in read-only mode, pages in the OS page cache are clean. Under memory pressure, the Linux kernel can drop clean pages instantly without writing them to disk swap:
```bash
# Minimize kernel swapping aggressiveness
sudo sysctl -w vm.swappiness=10
```

---

## 6. Primitive Node ID Width Selection (`uint16_t`, `uint32_t`, `uint64_t`)

In Impulse Graph, each Node Domain possesses an independent 0-indexed dense integer space ($0 \dots N_d-1$). Setting the domain ID width based on cardinality maximizes CPU cache density and memory bus utilization:

```
 64-Byte CPU Cache Line Packing Density Comparison
 ─────────────────────────────────────────────────────────────────────────
 • 16-bit (uint16_t): [ID0][ID1][ID2] ... [ID31] (32 Node IDs / 64B Line)
 • 32-bit (uint32_t): [  ID0  ][  ID1  ] ... [  ID15  ] (16 Node IDs / 64B Line)
 • 64-bit (uint64_t): [    ID0    ] ... [    ID7    ] (8 Node IDs / 64B Line)
 ─────────────────────────────────────────────────────────────────────────
```

### 6.1 Width Selection Matrix

| Width | Primitive | Max Domain Capacity | L1 Cache Density (64B) | Vector Tile (512-bit) | Memory (1B Edges) |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **`2` Bytes** | `uint16_t` | 65,536 nodes | **32 IDs** / line | **32 IDs** / tile | **2.0 GB** |
| **`4` Bytes** | `uint32_t` | 4,294,967,296 nodes | **16 IDs** / line | **16 IDs** / tile | **4.0 GB** |
| **`8` Bytes** | `uint64_t` | >4.29 Billion nodes | **8 IDs** / line | **8 IDs** / tile | **8.0 GB** |

To configure domain width during snapshot compilation with [`DefaultSnapshotBuilder`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/DefaultSnapshotBuilder.java):
```java
builder.withDomain(0, "User", (byte) 0x01, 65000); // Configured with uint16 target width
```

---

## 7. Zero Garbage Collection (GC) Guarantee & Verification

Because traversal frontiers propagate through pre-allocated `MemorySegment` buffers and [`OffHeapBitSet`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/bitset/OffHeapBitSet.java) instances using `LongVector.SPECIES_PREFERRED`, the core engine query loop produces **zero heap allocations per traversal step**.

### 7.1 Microbenchmark & GC Verification

> [!NOTE]
> Modern JVMs (Java 9+) replace the legacy `-XX:+PrintGCDetails` option with unified JVM logging (`-Xlog:gc*`).

To verify zero-GC execution in continuous integration or microbenchmarks:
```bash
mvn test -pl impulse-vm -Dtest=BlueGreenDrainAndUnloadTest \
    -DargLine="--enable-preview --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED -Xlog:gc*"
```

### 7.2 Zero-Allocation Enforcement with Epsilon GC
For deterministic latency and allocation profiling, run microbenchmarks with the no-op Epsilon GC. Any accidental heap allocation during traversal will cause immediate JVM termination:
```bash
java --enable-preview \
     --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UnlockExperimentalVMOptions \
     -XX:+UseEpsilonGC \
     -Xms1g -Xmx1g \
     -jar your-benchmark.jar
```

