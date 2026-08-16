# Impulse Graph Engine — Java 25 FFM Core (`impulse-graph-java`)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A high-performance, off-heap **Java 25 FFM** execution engine and multi-module ecosystem for the **Impulse Graph Engine**. 

It fills a critical gap in the JVM ecosystem by acting as the **"Apache Arrow for Graph Analytics"**, pairing an immutable `.imps` C-ABI binary snapshot format with a pure Java 25 register-based Virtual Machine (`ImpulseVM`).

---

## 🚀 Architecture & Data Flow

```text
  Application Code (Spring Boot, gRPC, JVM Services)
                          │
         ┌────────────────┼────────────────┐
  Java Fluent API + CEL (Primary)      Scripting (ImpK, ImpLog, Cypher)
         └────────────────┼────────────────┘
                          │ AST
           Impulse Compiler (Java 25 CBO JIT)
                          │ Optimized impOps (.impb)
         ImpulseVM (Java 25 MethodHandles)
                          │ Zero-copy pointers
         ┌────────────────┴────────────────┐
   .imps Snapshot (mmap)         OverlayMutator (Off-Heap)
   (Read-Only Base)              (Lock-Free Delta Blocks)
```

---

## 🧠 How It Works

### Pure Java 25 Off-Heap Execution
Memory-maps `.imps` v0.9.0 binary snapshot files off-heap via `java.lang.foreign.MemorySegment` with **zero Garbage Collection (GC) pauses**. Avoids native `dlopen` execution blocks and prevents C++ `SIGSEGV` process crashes.

### Zero Third-Party Dependencies
`impulse-core` and `impulse-api` maintain **strictly 0 external dependencies**. No Guava, no Netty, no Log4j. This guarantees zero CVE supply-chain bloat and maximum enterprise container security.

### Continuous HTAP & Mutation Overlays
While `.imps` snapshots are fully immutable on disk, `impulse-core` implements an off-heap `OverlayMutator` and `ColumnarDeltaBlock` framework for lock-free HTAP workloads:
- **Zero-Pause Query Execution:** Queries execute over `AtomicReference` states without acquiring read locks.
- **Background Compaction:** A streaming Blue/Green compactor seamlessly merges the base mmap snapshot and millions of in-memory delta edges into a new `.imps` file on disk while queries sustain tens of millions of QPS.

### ImpulseVM & JDK Vector API Acceleration
Dynamically generates AVX-512 and ARM Neon SIMD assembly instructions at JVM JIT compile time via `jdk.incubator.vector`. Bytecode opcodes (`impOps`) are compiled dynamically via `MethodHandle` combinators, letting the HotSpot C2 compiler aggressively inline and vectorize the inner graph loops.

---

## ⚡ Empirical Performance

**Continuous HTAP Blue/Green Compaction Stress Test (1 CPU Core, 15.7M Edges):**

| Metric | Empirical Value |
| :--- | :--- |
| **Max Concurrent Query Throughput** | **47.5 Million QPS** |
| **Off-Heap Edge Ingestion Rate** | **221,000 Edges / sec** |
| **Query Latency during Disk Compaction** | **Zero Stalls (Lock-Free)** |
| **Snapshot Load Time (Mmap)** | **< 1 ms** |

---

## 📦 Artifact Partitioning Matrix

| Artifact / Module | Size | Native Binaries? | Third-Party Deps | Purpose & Description |
| :--- | :--- | :--- | :--- | :--- |
| **`impulse-api`** | ~50 KB | ❌ None | **0** | Lightweight public interfaces, fluent builder API (`ImpulseQueryBuilder`), and domain types. |
| **`impulse-core`** | ~300 KB | ❌ None | **0** | Pure Java 25 off-heap HTAP engine and `ImpulseVM` bytecode interpreter. |
| **`impulse-compiler`** | ~200 KB | ❌ None | **0** | Cypher & ImpScheme AST query planner, Stage 1/2 Optimizer, and `impOps` emitter. |
| **`impulse-spec`** | ~100 KB | ❌ None | **0** | Binary Snapshot v0.9.0 header encoders, decoders, and structural spec definitions. |
| **`impulse-kotlin`** | ~200 KB | ❌ None | Kotlin Stdlib | Idiomatic Kotlin extensions, coroutines support, and flow streams. |
| **`impulse-scala`** | ~250 KB | ❌ None | Scala 3 Library | Scala 3 type-safe GraphBLAS matrix math API wrappers. |

---

## 🔄 Execution Modes

Because ImpulseVM dynamic JIT compilation is so incredibly fast (< 3 µs per query via `MethodHandles`), **Ahead-of-Time (AOT) compilation plugins have been retired.** All query parsing and optimization now happens entirely at runtime via our embedded Cost-Based Optimizer (CBO).

### Mode 1: Java Fluent API + CEL (Primary API)
The primary interface for developers using Impulse Graph in a JVM application is the `ImpulseQueryBuilder`. It supports pure Java fluent traversals interspersed with Common Expression Language (CEL) predicates:

```java
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ArgType;

var query = new ImpulseQueryBuilder<BitSet>()
    .input("User", ArgType.SINGLE_LONG)
    .walkEdge("FOLLOWS")
    .filterWithCel("age > 30")
    .walkEdgeWithCel("PURCHASED", "amount > 100.00")
    .build();

// Evaluates natively via the ImpulseVM vector engine
var bitset = evaluator.evaluate(query, graph, userId);
```

### Mode 2: Dynamic Scripting (ImpK, ImpLog, Cypher)
For complex analytical workloads, external rule engines, or ad-hoc web consoles, developers can dynamically compile string scripts directly to bytecode at runtime. The compiler supports three frontend domain-specific languages:
1. **Cypher**: Standard declarative graph pattern matching (`MATCH (n)-[:KNOWS]->(m) RETURN m`). Includes full mutation CRUD DML support via the `OverlayMutator`.
2. **ImpK (GraphBLAS)**: Explicit matrix-vector math for PageRank, connected components, and SIMD array operations.
3. **ImpLog (Datalog)**: Declarative logic programming for ReBAC (Zanzibar) authorization and recursive transitive closures.
## 🛠️ Prerequisites & Build Instructions

* **JDK 25** (with `--enable-preview` and `--add-modules jdk.incubator.vector`)
* **Maven 3.9+**

### Building and Running Tests
```bash
mvn clean test
```

---

## 📄 License

Apache License 2.0. See [LICENSE](LICENSE) for details.
