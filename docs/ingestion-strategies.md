# Ingestion & Snapshot Generation Architecture

> [!NOTE]
> **Impulse Graph Engine — Java 21 LTS FFM Core Engine Specification**  
> *Document Version: 2.0.0 | Target Spec: Impulse Binary Snapshot Format (`.imps`) v0.9.0*

---

## 1. Executive Architectural Summary

Impulse Graph represents graph state as **immutable, zero-copy `.imps` binary snapshots** mapped directly off-heap using Java 21 LTS Foreign Function & Memory (FFM) `Arena` and `MemorySegment`. 

By design, the core execution engine (`impulse-core`, `impulse-vm`, `impulse-storage`) is strictly **read-only (RO)** with zero third-party runtime dependencies. Real-time updates, Change Data Capture (CDC) streaming, and batch ingestion are completely decoupled from the query engine. All data ingestion occurs out-of-band via streaming snapshot compilers (`SnapshotBuilder` in `impulse-builder` or official CLI tooling), streaming new `.imps` snapshots direct-to-disk or cloud object storage (Amazon S3 / Google Cloud Storage) with strict single-pass semantics and bounded $O(\text{chunk})$ memory.

Online query instances integrate updates through a **Blue-Green Atomic Pointer Swap** pattern:

```
                    Blue-Green Immutable Snapshot Generation Lifecycle
                    
 [ Upstream ETL / Kafka Stream / CDC ]
               │
               ▼ (Out-of-band Micro-Batch / Event Aggregation)
 ┌───────────────────────────┐
 │ Snapshot Builder Pipeline │ ──► Streams off-heap CSR/CSC structures via SnapshotBuilder
 └───────────────────────────┘     (Strict O(chunk) heap footprint, single-pass S3 write)
               │
               ▼ (Direct Streaming Serialization)
 ┌───────────────────────────┐
 │ Immutable .imps Snapshot  │ ──► Writes Page 0, Catalogs, Topologies, & Footer Metadata to NVMe / S3
 └───────────────────────────┘
               │
               ▼ (Zero-Downtime Blue-Green Swap)
 ┌───────────────────────────┐
 │ Active Engine Runtime     │ ──► Atomic pointer swap to new GraphSnapshot in Arena.ofShared()
 └───────────────────────────┘     ⚡ Zero-Lock Atomic Pointer Swap (0ns) to active readers
```

---

## 2. Ingestion & Snapshot Compilation Pipeline Architecture

Enterprise graph ingestion decouples into **3 orthogonal architectural layers**, eliminating in-engine mutation overhead while guaranteeing deterministic, bounded-memory snapshot generation:

```
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │ 1. UPSTREAM EVENT & ETL INGESTION (Out-of-band: impulse-platform / CDC)     │
 ├─────────────────────────────────────────────────────────────────────────────┤
 │ • Kafka WAL Consumers & Debezium CDC Connectors                             │
 │ • Batch Lakehouse / Parquet / CSV Extractors                                │
 │ • Out-of-band Micro-Batch Aggregators (Zero core engine runtime footprint) │
 └──────────────────────────────────────┬──────────────────────────────────────┘
                                        │
                                        ▼
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │ 2. STREAMING SNAPSHOT COMPILER (impulse-builder / SnapshotBuilder)          │
 ├─────────────────────────────────────────────────────────────────────────────┤
 │ • Domain Schema: Strict dense ID independence (0 .. N-1), uint16/32/64      │
 │ • Topology Schemas: Mandatory CSR, optional CSC / COO with RAW / SIMD_COMP  │
 │ • Data Providers (SPI): RelationDataSource, AttributeDataSource via FFM    │
 │ • Adaptive Staging: ExternalSortStaging spills to NVMe when transposing CSC │
 │ • Single-Pass S3 Writer: Streams Page 0, Topologies, & Footer (Zero seeks)  │
 └──────────────────────────────────────┬──────────────────────────────────────┘
                                        │
                                        ▼
 ┌─────────────────────────────────────────────────────────────────────────────┐
 │ 3. ZERO-LOCK RUNTIME SERVING (impulse-storage / GraphSnapshot)              │
 ├─────────────────────────────────────────────────────────────────────────────┤
 │ • Zero-copy off-heap mmap via Arena.ofShared() with MADV_WILLNEED prefetch   │
 │ • Blue-Green Atomic Pointer Swap (AtomicReference<GraphSnapshot>)           │
 │ • RCU-style query counter draining: enterQuery() / exitQuery()              │
 │ • Safe resource deallocation: drainAndClose() closes old off-heap Arena     │
 └─────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Domain Schema & Dense ID Independence
Per the Impulse specification, there is **no synthetic or global flattened node ID space**. Every Node Domain (e.g. `User`, `Account`, `Product`) configures:
1. **Exact Cardinality**: The total node count $N_d$, establishing an implicit dense integer range $[0, N_d - 1]$.
2. **Primitive ID Width**: Configured via `PrimitiveWidth` (`UINT16` for $\le 65{,}536$ nodes, `UINT32` for $\le 4.29\text{B}$ nodes, `UINT64` for hyperscale graphs), optimizing memory alignment and cache efficiency.
3. **Primary Key Indexing**: Optional reverse string/UUID-to-dense-ID lookup table.
4. **Columnar Attributes**: Strongly-typed contiguous attribute vectors streamed sequentially in dense ID order.

### 2.2 Relation Topologies & Transposition Modes
Every relation connects an explicit Source Domain to a Target Domain with configurable physical indices:
1. **Mandatory CSR (`Topology.CSR`)**: Compressed Sparse Row format, strictly sorted by Source Node ID. Mandatory for all outgoing edge traversals (`OP_CSR_WALK`).
2. **Optional CSC (`Topology.CSC`)**: Compressed Sparse Column format, strictly sorted by Target Node ID for instant incoming/reverse traversals (`OP_CSC_WALK`).
3. **Optional COO (`Topology.COO`)**: Uncompressed coordinate edge tuples `(src, tgt)` for bulk edge iteration or matrix transfers.
4. **Transposition Handling**: If an upstream data source cannot provide a pre-sorted target stream (`getTargetSortedEdges()`), `SnapshotBuilder` leverages `ExternalSortStaging` to parallel merge-sort the CSR edges on NVMe storage within configured memory limits (`withStagingMemoryLimit`), maintaining strict bounded-RAM behavior.

### 2.3 Zero-Allocation Data Provider SPIs
Data is streamed into the builder via high-throughput Service Provider Interfaces (SPIs) using Java 21 LTS Foreign Function & Memory (FFM) `MemorySegment` buffers:
* **`RelationDataSource`**: Supplies the total edge count and an `EdgeChunkIterator`. The iterator populates off-heap source and target ID memory segments in chunks, completely eliminating Java heap object allocations (`Edge` objects) and garbage collection pauses during large graph ingestion.
* **`AttributeDataSource`**: Streams typed attribute values (`DataType.I32`, `DataType.F64`, `DataType.vector(...)`) in ascending dense node ID order via `AttributeChunkIterator`.

### 2.4 Single-Pass S3 Streaming Serialization
Traditional graph formats require random disk seeks to update offset tables once edge payloads are written. Impulse Graph eliminates random writes:
* **Precomputed Offsets**: Directory tables in Section 2 compute layout offsets upfront based on domain cardinalities and edge counts.
* **Single-Pass Output**: The snapshot is streamed sequentially from Page 0, Section 2 directory table, 128-byte aligned topology sections, to the terminal Footer Block.
* **Direct-to-Cloud Ingestion**: Writers stream directly to network sockets or cloud object storage (`Amazon S3`, `Google Cloud Storage`) via `WritableByteChannel` or `OutputStream` without local disk intermediaries.

---

## 3. Concurrency, Locking & Synchronization Architecture

### 3.1 Zero-Lock Traversal & Read-Only Invariants
Impulse completely eliminates **Reader/Writer Locks (`ReentrantReadWriteLock`)** and atomic synchronization primitives from the traversal path.

```
 Out-of-Band Builder (Writer)                    Concurrent Query Threads (Readers)
 ────────────────────────────                    ──────────────────────────────────
 • Compiles new .imps snapshot                   • Core 0: Vector traversal executing (Lock-Free)
 • Streams direct to NVMe / S3                   • Core 1: Vector traversal executing (Lock-Free)
 • Zero runtime engine contention                • Core 2: Vector traversal executing (Lock-Free)
 • Triggers atomic reference pointer swap        • Readers NEVER acquire locks or execute CAS!
```

* **Elimination of Cache Contention**: In traditional graph databases, read locks cause **Cache Line Bouncing** across CPU cores (atomic CAS on shared lock cache lines), degrading traversal latencies from $5\text{ ns}$ to $> 500\text{ ns}$. In Impulse Graph, reader threads execute pure, uninterrupted hardware memory loads directly against off-heap `MemorySegment` buffers.

### 3.2 In-Flight Query Safety (RCU Draining Lifecycle)
When a new snapshot file is compiled and ready for serving, the query engine applies a zero-downtime pointer swap using an `AtomicReference<GraphSnapshot>`:

1. **Active Query Accounting (`enterQuery()` / `exitQuery()`)**: Reader threads increment a high-performance off-heap or `LongAdder` counter upon entering a traversal and decrement upon completion.
2. **Atomic Reference Swap**: The runtime performs an atomic pointer swap (`activeSnapshot.getAndSet(newSnapshot)`). New queries instantly route to `newSnapshot` with 0ns lock overhead.
3. **Non-Blocking Draining (`awaitDrained()` & `drainAndClose()`)**: Existing in-flight queries executing against `oldSnapshot` continue reading their immutable memory-mapped pages safely. A background task invokes `oldSnapshot.drainAndClose(timeout, unit)`. Once active queries hit zero, the old `Arena` is closed, immediately unmapping memory from the OS kernel.

```java
// Query execution lifecycle in Java 21 LTS:
GraphSnapshot snapshot = activeSnapshot.get();
snapshot.enterQuery();
try {
    return compiledQuery.execute(snapshot, inputNode, scratchArena);
} finally {
    snapshot.exitQuery();
}
```

---

## 4. Memory Footprint & Physical Layout Equations

### 4.1 Zero-Allocation Query Runtime
Because the query runtime operates directly over memory-mapped `.imps` binary files, **physical DRAM allocation inside the JVM query engine is $0\text{ MB}$**:

| Layer | Physical Footprint Model | Behavior & OS Interaction |
| :--- | :--- | :--- |
| **Java Heap Memory** | **$0\text{ MB}$** | Zero edge or node objects allocated on JVM heap. |
| **Direct Off-Heap DRAM** | **$0\text{ MB}$ (managed)** | Mapped via `FileChannel.MapMode.READ_ONLY` into `Arena.ofShared()`. |
| **OS Page Cache** | Demand-Paged | Kernel populates physical RAM on demand; prefetched via `segment.load()` (`MADV_WILLNEED`). |
| **Clean Page Eviction** | Transparent | Under memory pressure, pages are reclaimed instantly without swapping or disk writes. |

### 4.2 Physical Storage Sizing Equations (v0.9.0 Format)
For a graph relation connecting Source Domain $D_s$ (cardinality $|V_s|$) to Target Domain $D_t$ (cardinality $|V_t|$) with $|E|$ edges:

$$\text{CSR Footprint} = (|V_s| + 1) \times W_{\text{edgeIdx}} + |E| \times W_{\text{tgtId}}$$

$$\text{CSC Footprint} = (|V_t| + 1) \times W_{\text{edgeIdx}} + |E| \times W_{\text{srcId}}$$

Where:
* $W_{\text{edgeIdx}} = 4\text{ bytes}$ (or $8\text{ bytes}$ if $|V_s| > 4\text{B}$ or $|E| > 4\text{B}$).
* $W_{\text{tgtId}}, W_{\text{srcId}} \in \{2, 4, 8\}\text{ bytes}$ as declared by the respective `DomainDefinition.idWidth`.

### 4.3 Streaming Builder Bounded Memory Footprint
During snapshot compilation, memory consumption is strictly bounded regardless of overall graph scale:

$$\text{RAM}_{\text{builder}} = O(\text{chunk}) \le \text{stagingMemoryLimit} \quad (\text{default: } 64\text{ MB})$$

If secondary reverse indexes (`Topology.CSC`) are enabled and the input stream is not pre-sorted by target node ID, `SnapshotBuilder` stages chunks to the configured `stagingDirectory` on NVMe storage, executing an external multi-way merge sort within configured memory bounds.

---

## 5. Ingestion Builder API & Query Swap Patterns

### 5.1 Programmatic Snapshot Compilation (`SnapshotBuilder`)
Developers configure domains, relations, topologies, and streaming sources using the fluent `SnapshotBuilder` API (`org.impulsegraph.builder.api.*`):

```java
import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.*;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class GraphIngestionPipeline {

    public static void compileSnapshot(Path outputPath) throws Exception {
        // 1. Define 'User' Domain with explicit cardinality and primitive width
        DomainDefinition userDomain = DomainDefinition.builder(1_000_000L)
            .idWidth(PrimitiveWidth.UINT32)
            .withPrimaryKeyIndex(true)
            .addAttribute("age", new UserAgeDataSource())
            .build();

        // 2. Define 'Account' Domain
        DomainDefinition accountDomain = DomainDefinition.builder(500_000L)
            .idWidth(PrimitiveWidth.UINT32)
            .build();

        // 3. Define 'OWNS' Relation with CSR and optional CSC reverse index
        RelationDefinition ownsRelation = RelationDefinition.builder("User", "Account")
            .addTopology(Topology.CSR, CompressionScheme.RAW)
            .addTopology(Topology.CSC, CompressionScheme.RAW)
            .dataSource(new UserAccountRelationDataSource()) // EdgeChunkIterator provider
            .build();

        // 4. Stream compiled snapshot direct to channel with bounded heap
        try (FileChannel channel = FileChannel.open(outputPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            
            SnapshotBuilder.create()
                .withStagingDirectory(Path.of("/tmp/impulse-staging"))
                .withStagingMemoryLimit(256 * 1024 * 1024L) // 256 MB RAM staging limit
                .addDomain("User", userDomain)
                .addDomain("Account", accountDomain)
                .addRelation("OWNS", ownsRelation)
                .addFooterMetadata("sys.kafka.topic", "banking-cdc")
                .addFooterMetadata("sys.kafka.committed_offset", "1492048592")
                .writeTo(channel);
        }
    }
}
```

### 5.2 Query Engine Atomic Pointer Swap (`GraphSnapshotManager`)
Query servers mount the newly compiled `.imps` snapshot file into a fresh shared arena and perform a zero-downtime Blue-Green swap:

```java
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.GraphSnapshot;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class GraphSnapshotManager implements AutoCloseable {
    private final AtomicReference<GraphSnapshot> currentSnapshot = new AtomicReference<>();

    public void swapToNewSnapshot(Path snapshotPath) throws Exception {
        // 1. Map new immutable snapshot in a dedicated shared arena
        Arena newArena = Arena.ofShared();
        GraphSnapshot newSnapshot = BinarySnapshotLoader.loadSnapshot(snapshotPath, newArena).graph();

        // 2. Atomic pointer swap (0ns lock overhead to active readers)
        GraphSnapshot oldSnapshot = currentSnapshot.getAndSet(newSnapshot);

        // 3. Asynchronously drain and close the retired snapshot's arena
        if (oldSnapshot != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    oldSnapshot.drainAndClose(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    oldSnapshot.close();
                }
            });
        }
    }

    public GraphSnapshot getActiveSnapshot() {
        return currentSnapshot.get();
    }

    @Override
    public void close() {
        GraphSnapshot snap = currentSnapshot.getAndSet(null);
        if (snap != null) {
            snap.close();
        }
    }
}
```

---

## 6. Binary `.imps` Metadata & Pod Cold-Start Protocol (Spec v0.9.0)

To ensure snapshots are self-describing across polyglot runtimes (C++, Java, Rust, Python), streaming ingestion metadata and stream offsets are embedded directly inside the **Footer Custom Metadata Stream**:

### 6.1 Embedded Footer Metadata Tags

```
 Footer Custom Metadata Stream
 ─────────────────────────────────────────────────────────────────────────────
 • sys.kafka.topic:              "fleet-telematics-cdc"
 • sys.kafka.partition:          "0"
 • sys.kafka.committed_offset:   "1492048592"
 • sys.build.git_commit:         "7fa2b9d"
 • sys.build.timestamp_ms:       "1773620000000"
 ─────────────────────────────────────────────────────────────────────────────
```

### 6.2 Container Pod Cold-Start & Ingestion Protocol
When an Impulse Graph container pod initializes:
1. **Load Base Snapshot**: Pod memory-maps the latest `.imps` file in $< 1\,\mu\text{s}$ via `BinarySnapshotLoader.loadSnapshot(path, arena)`.
2. **OS Memory Prefetch**: Triggers `segment.load()` (`MADV_WILLNEED`), instructing the OS kernel to populate physical RAM pages in the background.
3. **Immediate Query Serving**: Pod begins executing compiled vector traversals instantly with zero warmup and zero GC pauses.
4. **Decoupled CDC Synchronization**: The external ingestion daemon (in `impulse-platform`) inspects `snapshot.getMetadata("sys.kafka.committed_offset")` and resumes consuming change events from offset $N+1$, compiling future snapshot generations out-of-band.

---

## 7. Production Enterprise Ingestion Patterns

| Ingestion Pattern | Target Workload | Architecture & Mechanism | Performance Characteristics |
| :--- | :--- | :--- | :--- |
| **1. Batch Lakehouse / Parquet ETL** | Billions of nodes/edges from Iceberg / Parquet | Single-pass compilation via `SnapshotBuilder` with external NVMe sort staging for CSC | $O(\text{chunk})$ bounded heap, single-pass zero-seek cloud upload, 128-byte hardware aligned |
| **2. Micro-Batch CDC WAL Synchronization** | Operational OLTP tables via Debezium / Kafka | Out-of-band aggregator buffers deltas in `impulse-platform`, compiles new snapshot, triggers atomic swap | 0ns reader lock contention, RCU query draining, sub-microsecond traversal latency maintained |
| **3. Zanzibar / ReBAC Authorizations** | Dynamic fine-grained access control (`impulse-authz`) | Micro-batch snapshot compilation (1–5s intervals), atomic pointer swap | Lock-free transitive reachability traversals, zero JVM garbage collection pauses |
| **4. Hyperscale Multi-Terabyte Static Analytics** | 100M to 10B+ node reference graphs (Hetionet, DRKG) | Multi-domain `.imps` snapshot with `uint64_t` widths, mounted directly via read-only `mmap` | $0\text{ MB}$ JVM heap allocation, instant $< 1\,\mu\text{s}$ cold start, clean OS page cache eviction |

---

### Summary Architectural Conclusion
By decoupling ingestion from query execution and pairing an **immutable binary snapshot format (`.imps`)** with **out-of-band streaming compilation (`SnapshotBuilder`)** and **Blue-Green atomic pointer swaps**, Impulse Graph eliminates the traditional tradeoff between static query performance and continuous data updates. The query engine executes read-only traversals at full hardware memory-bus speed without locks or GC pauses, while streaming compilers generate new snapshots in bounded $O(\text{chunk})$ physical RAM.
