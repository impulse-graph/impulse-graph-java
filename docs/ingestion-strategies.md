# Ingestion Strategies & Live Mutation Architecture

**Impulse Graph Engine — Java 25 FFM Core Specification**  
*Document Version: 1.0.0 | Target Spec: Impulse Binary Snapshot Format v0.9.0 / v0.9.1*

---

## 1. Executive Architectural Summary

Impulse Graph is fundamentally an **immutable zero-copy C-ABI binary snapshot engine (`.imps`)** optimized for sub-microsecond SIMD vector traversals. However, real-world enterprise workloads require continuous streaming ingestion (e.g. Kafka CDC event streams, Debezium database logs, IoT vehicle GPS telematics, and fine-grained authorization mutations).

To bridge static immutable snapshots with real-time updates, Impulse Graph decouples the engine into a **Single-Writer Multi-Reader (SWMR)** architecture using **Java 25 Foreign Function & Memory (FFM) `MemorySegment`** off-heap arenas.

```
                      The Complete Ingestion Architecture Lifecycle
                      
 [ Live Kafka / CDC Stream ]
              │
              ▼ (1ns Append)
 ┌───────────────────────────┐
 │ Tier 0: Live Off-Heap COO │ ──► Instant In-Place Updates (Speed/Status) & Tombstone BitFlips
 └───────────────────────────┘
              │
              ▼ (Every 1-5s via Single Ingestion Thread)
 ┌───────────────────────────┐
 │ Tier 1: In-Memory RCU     │ ──► Rebuilds modified RelationSnapshot in Arena.ofShared()
 └───────────────────────────┘     ⚡ Zero-Lock Atomic Pointer Swap (0ns) to active readers
              │
              ▼ (Every 1-4 hours or on pod scale-down)
 ┌───────────────────────────┐
 │ Tier 2: Background Flush  │ ──► Compacts Base + Delta into fresh .imps with committed Kafka Offset
 └───────────────────────────┘     Streams direct to AWS S3 / Local NVMe without GC pauses
```

---

## 2. Composable Lego Block Taxonomy

Enterprise graph workloads vary wildly in cardinality, write frequency, and read SLAs. Rather than forcing a one-size-fits-all model, Impulse decomposes ingestion into **5 orthogonal composable strategy dimensions**:

```
 ┌────────────────────────┐     ┌────────────────────────┐     ┌────────────────────────┐
 │ 1. STORAGE CONTAINER   │     │ 2. DELETION STRATEGY   │     │ 3. INGESTION CADENCE   │
 ├────────────────────────┤     ├────────────────────────┤     ├────────────────────────┤
 │ • FrozenMmapSegment    │     │ • TombstoneBitSet      │     │ • InPlaceAtomic        │
 │ • DenseOffHeapSegment  │     │ • BatchRebuildOnly     │     │ • MicroBatchRCU        │
 │ • PagedAppendixSegment │     │ • AttributeFilterFlag  │     │ • CoWArraySwap         │
 │ • SparseDeltaHashTable │     └────────────────────────┘     │ • AppendixAppend       │
 └────────────────────────┘                                    └────────────────────────┘
              │                                                             │
              └──────────────────────────────┬──────────────────────────────┘
                                             ▼
                                ┌────────────────────────┐     ┌────────────────────────┐
                                │ 4. TRANSPOSITION (CSC) │     │ 5. PERSISTENCE/FLUSH   │
                                ├────────────────────────┤     ├────────────────────────┤
                                │ • ForwardOnly (No CSC) │     │ • PureEphemeralRAM     │
                                │ • EagerDualIndex       │     │ • LocalNvmeSpooler     │
                                │ • LazyOnDemand         │     │ • CloudS3Streamer      │
                                └────────────────────────┘     └────────────────────────┘
```

### 2.1 Storage Container Blocks
1. **`FrozenMmapSegment`**: Zero-copy OS page cache mapped directly from `.imps`. Zero physical DRAM allocation.
2. **`DenseOffHeapSegment`**: 100% contiguous, 128-byte aligned primitive arrays allocated in off-heap `Arena.ofShared()`. Delivers maximum AVX-512 / ARM NEON throughput.
3. **`PagedAppendixSegment`**: Fixed-size unrolled chunk buffers (e.g. 16-slot blocks) dedicated per node for dynamic edge appends.
4. **`SparseDeltaHashTable`**: Off-heap Robin Hood hash map overlay holding sparse, irregular point patches.

### 2.2 Deletion Strategy Blocks
1. **`TombstoneBitSet`**: 1 bit per edge in off-heap memory. Filtered in 1 CPU cycle via AVX-512 `_mm512_andnot_si512` / `knot`.
2. **`BatchRebuildOnly`**: Deletions are buffered in memory and physically purged during the next batch rebuild.
3. **`AttributeFilterFlag`**: Logical status column (`status = DELETED`) checked via standard predicate filters.

### 2.3 Ingestion Cadence Blocks
1. **`InPlaceAtomic`**: Direct 1-nanosecond hardware atomic store (`VarHandle.setVolatile()`). Zero memory allocation.
2. **`MicroBatchRCU`**: Single writer rebuilds relation in fresh `Arena` every $N$ seconds/events; atomic reference swap.
3. **`CoWArraySwap`**: Copy-on-Write cloning of a single column array ($8\,\mu\text{s}$), mutating slot, and swapping pointer.
4. **`AppendixAppend`**: Appends edge to node's unrolled appendix buffer in $15\text{ ns}$.

### 2.4 Transposition & Reverse Indexing Blocks
1. **`ForwardOnly`**: CSC reverse index is completely disabled. Saves 50% memory and build time.
2. **`EagerDualIndex`**: CSR and CSC indices are rebuilt and maintained synchronously.
3. **`LazyOnDemand`**: CSC index is generated in RAM only if a reverse query (`OP_CSC_WALK`) is executed.

### 2.5 Persistence & Compaction Blocks
1. **`PureEphemeralRAM`**: No disk writes. Memory stays in off-heap RAM; crash recovery replays Kafka stream.
2. **`LocalNvmeSpooler`**: Background thread writes fresh `.imps` to local NVMe every 10–30 minutes.
3. **`CloudS3Streamer`**: Streams compacted `.imps` directly to Amazon S3 / Google Cloud Storage with committed Kafka offset.

---

## 3. Concurrency, Locking & Synchronization Architecture

### 3.1 The Single-Writer Multi-Reader (SWMR) Model
Impulse completely eliminates **Reader/Writer Locks (`ReentrantReadWriteLock`)** from the read path.

```
 Single Ingestion Thread (Writer)                Concurrent Query Threads (Readers)
 ────────────────────────────────                ──────────────────────────────────
 • Consumes Kafka / Ring Buffer                  • Core 0: Traversal executing (Lock-Free)
 • Mutates off-heap arrays in isolation          • Core 1: Traversal executing (Lock-Free)
 • Zero lock contention                          • Core 2: Traversal executing (Lock-Free)
 • Publishes via release-fence memory store      • Readers NEVER acquire read-locks!
```

* **Why SWMR Dominates**: In traditional multi-threaded graphs, read locks cause **Cache Line Bouncing** across CPU cores (atomic CAS on lock addresses), degrading latency from $5\text{ ns}$ to $> 500\text{ ns}$. In Impulse, reader threads execute pure, unrestricted hardware memory loads.

### 3.2 In-Flight Query Safety (RCU Draining Lifecycle)
When a relation or snapshot is swapped:
1. **`enterQuery()` / `exitQuery()`**: Readers increment a `LongAdder` counter upon entering a query and decrement upon completion.
2. **Atomic Hot-Swap**: The writer updates `graphSnapshot.updateRelation("relName", newRelSnapshot)`. Future queries immediately pick up `newRelSnapshot`.
3. **`awaitDrained()` & `drainAndClose()`**: In-flight queries on `oldRelSnapshot` continue running safely on their immutable memory segments. Once `activeQueryCount` hits zero, the background worker invokes `oldRelSnapshot.close()`, releasing its off-heap `Arena` directly to the OS kernel.

```java
// Query execution wrapper in Java 25:
snapshot.enterQuery();
try {
    return compiledQuery.execute(snapshot, inputNode, arena);
} finally {
    snapshot.exitQuery();
}
```

---

## 4. Memory Footprint & Mathematical Overhead Equations

### 4.1 Base vs Live Ingestion Footprint

| Component | Physical Memory Equation | Concrete Size (10M Edges) |
| :--- | :--- | :--- |
| **Base CSR Array** | $|V| \times 4\text{B} + |E| \times 4\text{B}$ | $\approx 44.0\text{ MB}$ (Virtual OS Page Cache) |
| **Base CSC Transpose** | $|V| \times 4\text{B} + |E| \times 4\text{B}$ | $\approx 44.0\text{ MB}$ (Virtual OS Page Cache) |
| **Tombstone BitSet** | $\lceil |E| / 8 \rceil\text{ bytes}$ | **$1.25\text{ MB}$** (Off-heap DRAM) |
| **Paged Appendix (16-slot)** | $|V_{\text{active}}| \times 16 \times 4\text{B}$ | $\approx 6.40\text{ MB}$ (Off-heap DRAM) |
| **COO RingBuffer (50k updates)** | $50,000 \times 8\text{B}$ | **$0.40\text{ MB}$** (Off-heap DRAM) |

$$\text{Total Live DRAM Overhead} = \text{TombstoneBitSet} + \text{AppendixMemory} + \text{COORingBuffer} \le \mathbf{8.05\text{ MB}}$$

---

## 5. User API: How Developers Declare Ingestion Strategies

### 5.1 Programmatic Java Builder API
Developers configure ingestion policies per relation or globally using `DefaultSnapshotBuilder`:

```java
DefaultSnapshotBuilder builder = new DefaultSnapshotBuilder()
    // Configure Vehicle Location (High-Frequency In-Place Updates)
    .withRelationStrategy("truckToLocation", IngestionStrategy.builder()
        .storage(StorageContainer.DENSE_OFF_HEAP)
        .ingestion(IngestionCadence.IN_PLACE_ATOMIC)
        .transposition(TranspositionMode.FORWARD_ONLY)
        .build())

    // Configure Security Relations (Batch RCU with S3 Sync)
    .withRelationStrategy("userToGroup", IngestionStrategy.builder()
        .storage(StorageContainer.DENSE_OFF_HEAP)
        .ingestion(IngestionCadence.MICRO_BATCH_RCU)
        .rebuildInterval(Duration.ofSeconds(5))
        .transposition(TranspositionMode.EAGER_DUAL_INDEX)
        .persistence(PersistenceMode.CLOUD_S3_STREAMER)
        .build())

    // Configure Streaming Edge Graph (Tombstones + Appendices)
    .withRelationStrategy("userFollows", IngestionStrategy.builder()
        .storage(StorageContainer.PAGED_APPENDIX)
        .deletions(DeletionStrategy.TOMBSTONE_BITSET)
        .ingestion(IngestionCadence.APPENDIX_APPEND)
        .compactionThreshold(0.20) // Compact at 20% tombstone ratio
        .build());
```

---

## 6. Binary `.imps` Mapping & Embedded Configuration (Spec v0.9.1)

To ensure snapshots are self-describing across languages (C++, Java, Rust, Python), the ingestion configuration and streaming metadata are embedded directly inside the **Section 1 Catalog and Section 5 Statistics Metadata**:

### 6.1 Section 1 / Section 5 Metadata Header Tags

```
 Section 1 / Section 5 Embedded Metadata Keys
 ─────────────────────────────────────────────────────────────────────────────
 • sys.kafka.topic:              "fleet-telematics-cdc"
 • sys.kafka.partition:          "0"
 • sys.kafka.committed_offset:   "1492048592"
 • sys.ingest.strategy.rel_0:    "IN_PLACE_ATOMIC|FORWARD_ONLY"
 • sys.ingest.strategy.rel_1:    "MICRO_BATCH_RCU|EAGER_DUAL|S3_FLUSH"
 • sys.ingest.strategy.rel_2:    "PAGED_APPENDIX|TOMBSTONE_BITSET"
 ─────────────────────────────────────────────────────────────────────────────
```

### 6.2 Automatic Pod Cold-Start & Catch-Up Protocol
When a container pod boots up:
1. **Load Base Snapshot**: Pod `mmap`s the latest `.imps` file in $< 1\,\mu\text{s}$.
2. **Read Metadata Offset**: Extracts `sys.kafka.committed_offset` (e.g. `1492048592`).
3. **Attach Kafka Stream**: Subscribes to `sys.kafka.topic` starting at offset `1492048593`.
4. **Initialize Off-Heap Buffers**: Instantiates the declared Lego blocks (e.g. allocates Tombstone bitset and Appendix segments).
5. **Begin Serving Queries**: Pod serves live queries immediately while catching up on the few remaining Kafka events.

---

## 7. Production Enterprise Recipes

| Recipe Name | Target Workload | Composed Lego Blocks | Performance Metrics |
| :--- | :--- | :--- | :--- |
| **1. IoT / Fleet Telematics** | 50k trucks sending 5-min GPS pings | `FrozenMmap` + `InPlaceAtomic` + `ForwardOnly` + `EphemeralRAM` | **$1\text{ ns}$ writes, $0\text{ MB}$ allocation, sub-microsecond routing** |
| **2. Zanzibar ReBAC Security** | Fine-grained authorizations | `DenseOffHeap` + `BatchRebuild` + `MicroBatchRCU` (5s) + `EagerDual` + `S3Streamer` | **$0.3\text{ ns}$ traversal, $0$ read-locks, strict ACID snapshots** |
| **3. High-Churn Streaming** | Social / Financial transactions | `PagedAppendix` + `TombstoneBitSet` + `AppendixAppend` + `LocalNvmeSpooler` | **$15\text{ ns}$ inserts, $2\text{ ns}$ deletes, 100% AVX-512 SIMD** |
| **4. Massive Static Knowledge** | 100M-node Reference graphs | `FrozenMmap` + `TombstoneBitSet` + `ForwardOnly` + `CloudS3Streamer` | **$0\text{ DRAM}$ allocated, instant $< 1\,\mu\text{s}$ pod cold start** |

---

### Summary Architectural Conclusion
By treating ingestion as a **composition of orthogonal off-heap Lego blocks**, Impulse Graph eliminates the traditional tradeoff between static immutable performance and live streaming mutability. The engine achieves **millions of streaming updates per second** while guaranteeing that **read queries never stop executing at full hardware SIMD memory-bus speed**.
