# Memory & Performance Tuning Guide — Impulse Graph Engine (Java)

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

This guide details Foreign Function & Memory (FFM) lifecycle management, zero-GC memory mapping, primitive integer width selection, and JVM Vector API tuning options for **`impulse-graph-java`** running on **Java 21 LTS+**.

---

## 1. FFM Arena Lifecycle Management (`java.lang.foreign.Arena`)

Impulse Graph allocates all snapshot topology buffers (CSR row offsets, column target arrays, attribute SoA blocks) completely off-heap via FFM [`MemorySegment`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/ImpulseGraphSnapshot.java) instances bound to an [`Arena`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/BinarySnapshotLoader.java).

### 1.1 `Arena.ofShared()` vs. `Arena.ofConfined()`

| Arena Type | Thread Access Model | Primary Use Case | Lifecycle Management |
| :--- | :--- | :--- | :--- |
| **`Arena.ofShared()`** | Multi-Threaded | Active Graph Snapshots & SWMR Query Engines | Shared across carrier thread pools; closed explicitly when draining snapshots. |
| **`Arena.ofConfined()`** | Single-Threaded | Snapshot Builders & Scratch Calculations | Confined to building thread; automatically unmaps off-heap memory upon `try-with-resources` exit. |

```java
// Production Pattern for Long-Lived Graph Snapshots
try (Arena snapshotArena = Arena.ofShared()) {
    var loaded = BinarySnapshotLoader.loadSnapshot(Path.of("graph.imps"), snapshotArena);
    ImpulseGraphSnapshot snap = loaded.getGraph();
    
    // Serve concurrent query threads safely across snapshotArena
} // Off-heap memory unmapped directly from OS kernel on exit
```

---

## 2. Recommended JVM Tuning Flags

To run `impulse-graph-java` on Java 21 LTS through Java 25+, pass the following runtime JVM arguments:

```bash
java --enable-preview \
     --add-modules jdk.incubator.vector \
     --enable-native-access=ALL-UNNAMED \
     -XX:+UseG1GC \
     -Xms4g -Xmx16g \
     -jar your-application.jar
```

### 2.1 Flag Rationale
* `--enable-preview`: Enables FFM and preview language features under Java 21 LTS.
* `--add-modules jdk.incubator.vector`: Binds the SIMD Vector API module for AVX-512 and ARM Neon hardware register unrolling.
* `--enable-native-access=ALL-UNNAMED`: Grants zero-copy off-heap memory access to unmapped OS memory segments.

---

## 3. Primitive Node ID Width Selection (`uint16_t`, `uint32_t`, `uint64_t`)

Each domain in Impulse Graph independently configures its physical integer width based on cardinality. Selecting the optimal width maximizes CPU L1/L2 cache line packing density and memory bus throughput:

```
 64-Byte CPU Cache Line Packing Density Comparison
 ─────────────────────────────────────────────────────────────────────────
 • 16-bit (uint16_t): [ID0][ID1][ID2] ... [ID31] (32 Node IDs / 64B Line)
 • 32-bit (uint32_t): [  ID0  ][  ID1  ] ... [  ID15  ] (16 Node IDs / 64B Line)
 • 64-bit (uint64_t): [    ID0    ] ... [    ID7    ] (8 Node IDs / 64B Line)
 ─────────────────────────────────────────────────────────────────────────
```

### 3.1 Selection Matrix

| Width | Primitive | Max Domain Capacity | L1 Cache Line Density | Memory Footprint (1B Edges) |
| :--- | :--- | :--- | :--- | :--- |
| **`2` Bytes** | `uint16_t` | 65,536 nodes | **32 IDs** / 64B line | **2.0 GB** |
| **`4` Bytes** | `uint32_t` | 4,294,967,296 nodes | **16 IDs** / 64B line | **4.0 GB** |
| **`8` Bytes** | `uint64_t` | >4.29 Billion nodes | **8 IDs** / 64B line | **8.0 GB** |

---

## 4. Zero Garbage Collection (GC) Guarantee

Because traversal frontiers propagate through pre-allocated `MemorySegment` buffers and `ImpulseBitSet` instances, the engine query loop generates **0 heap allocations per traversal step**. 

To verify zero-GC execution in production or microbenchmarks:
```bash
mvn test -pl impulse-vm -Dtest=Twitter2010PrVmBenchmarkTest -DargLine="-XX:+PrintGCDetails"
```
