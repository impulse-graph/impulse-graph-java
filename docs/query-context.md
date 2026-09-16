# Query Context & Execution Architecture — Impulse Graph Engine (Java)

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

This document describes the query execution context architecture of **Impulse Graph Engine** (`impulse-graph`), detailing how queries interact with memory-mapped immutable snapshots (`ImpulseGraphSnapshot`), how the runtime execution context (`VmQueryContext`) manages off-heap state and scratch memory, and how zero-delay "blue/green" snapshot swaps rebind compiled query bytecode without invalidating query caches or blocking active readers.

## 1. Universal Traversal Model & Domain-Bound Context

All graph traversals in Impulse Graph Engine follow the **Kleisli Frontier Propagation Model** (§3.15 of `AGENTS.md`):

$$\text{Pipeline} = \text{Anchor}(D_0, F_0, S_0) \gg= T_1 \gg= T_2 \dots \gg= T_k \gg= \text{Collect}()$$

### 1.1 Strict Per-Domain Dense ID Independence ($0 \dots N_d-1$)
As defined in §3.14 of `AGENTS.md`, Impulse Graph Engine enforces strict per-domain dense ID independence:
* **No Global Unified ID Space**: There is no flattened, synthetic, or global node ID table. Every node domain (e.g., `User`, `Account`, `Device`) has its own independent 0-indexed dense integer address space $0 \dots N_d-1$. Node ID `1` in `User` is fundamentally unrelated to node ID `1` in `Account`.
* **Mandatory Domain Anchoring**: Multi-domain traversals must bind an explicit initial domain context (`snapshot.domain("User")`). Passing raw integers without an explicit domain context is invalid.
* **Domain Transition Descriptors**: Every relation in the binary snapshot explicitly declares `SrcDomainID` and `TgtDomainID`. Traversing an edge relation (`User -[:TRANSFERS]-> Account`) maps source dense IDs in `User` to target dense IDs in `Account`. Attribute filters resolve strictly against the attribute table of the currently active domain.

### 1.2 The Five Pipeline Stages
1. **Anchor Context & Initial Frontier $\langle D_0, \text{Frontier}_0, \text{State}_0 \rangle$**: Anchored to a domain via [`DomainView`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/traversal/DomainView.java) with a seed frontier:
   - `all()`: All $N_d$ nodes in the domain ($0 \dots N_d-1$).
   - `from(long nodeId)`: Single dense node ID.
   - `from(long... nodeIds)`: Sparse array of dense node IDs.
   - `from(ImpulseBitSet bitset)`: Off-heap bitset of dense IDs.
   - `fromKey(String key)` / `fromKeys(String... keys)`: Resolved from external business keys via snapshot metadata catalog.
2. **In-Domain Endomorphisms ($\langle D, S \rangle \to \langle D, S' \rangle$)**: Filter nodes or project attached state vectors in a vectorized SIMD sweep without transitioning domains:
   - `.filter(String celPredicate)`: Evaluates CEL expressions over domain attributes (e.g. `"node.active == true && node.score >= 50"`).
   - `.project(String projectionExpr)`: Mutates or assigns attached state vectors.
3. **Cross-Domain Traversal & Monoidic Reduction ($\langle D, S \rangle \xrightarrow{R} \langle D', S' \rangle$)**: Steps across relation $R: D \to D'$ via forward CSR (`.out(relation)`) or reverse CSC (`.in(relation)`). Multiple incoming paths converging on the same target node $v \in D'$ are reduced via a monoid $(\mathcal{S}, \oplus, \mathbf{0})$:
   - `Reducer.OR`: Boolean reachability / set union ($\oplus = \lor$).
   - `Reducer.MIN`: Shortest path / min-cost ($\oplus = \min$, $\otimes = +$).
   - `Reducer.MAX`: Bottleneck capacity / confidence ($\oplus = \max$).
   - `Reducer.SUM`: Path multiplicity / accumulation ($\oplus = +$).
   - `Reducer.AVG`: Arithmetic mean ($\oplus = \text{avg}$).
   - `Reducer.ANY`: First-witness masked vector scatter.
4. **Kleisli Chaining & Fixed-Point Loops**: Traversal steps chain indefinitely:
   - `.repeatUntilStable(step)`: Loops until frontier convergence ($\text{Frontier}_{t+1} == \text{Frontier}_t$).
   - `.repeat(int iterations, step)`: Loops for a fixed hop count.
5. **Terminal Materialization**: Collects the resulting target frontier:
   - `.collect()`: Materializes into the configured generic target type.
   - `.count()`: Returns active target cardinality as `long`.
   - `.toBitSet()`: Returns target frontier as off-heap [`ImpulseBitSet`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/bitset/ImpulseBitSet.java).
   - `.toList()` / `.toSet()`: Materializes target dense IDs as `List<Long>` or `Set<Long>`.
   - `.toKeyList()` / `.toKeySet()`: Resolves target dense IDs back to external business key strings.
   - `.toImpAsm()`: Disassembles the pipeline into human-readable `ImpAsm` assembly text.

---

## 2. The Three Query Execution Modalities

Impulse Graph Engine provides three complementary execution modalities depending on the query pattern and lifecycle requirements:

### Modality 1: Fluent Kleisli Traversal Pipeline (`DomainView` & `Traversal<T>`)
Best for domain-centric graph analytics, BFS expansions, and fixed-point algorithms:

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.traversal.DomainView;
import java.util.Set;

// 1. Obtain DomainView anchor context
DomainView userDomain = snapshot.domain("User");

// 2. Execute fluent Kleisli traversal pipeline
Set<Long> network = userDomain.fromKey("usr_alice")
    .repeat(2, step -> step.out("FOLLOWS"))
    .filter("node.active == true")
    .toSet();
```

### Modality 2: Parameterized Prepared Statements (`ImpulseStatement` & `RowReader`)
Best for server request handlers executing repeated parameterized traversals or openCypher queries:

```java
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;

// Prepare statement from snapshot
try (ImpulseStatement stmt = snapshot.prepare("FROM User -> out('FOLLOWS')")) {
    stmt.bindNode("id", aliceNodeId);

    // Execute with zero-copy flyweight RowReader cursor
    try (RowReader rows = stmt.execute()) {
        while (rows.next()) {
            long targetId = rows.getNodeId(0);
        }
    }
}
```

### Modality 3: Decoupled AST Query Pipelines (`ImpulseGraphQuery<R>` & `ImpulseQueryBuilder<R>`)
Best for pre-building immutable query ASTs that can execute across multiple snapshot instances or survive blue/green snapshot swaps:

```java
import org.impulsegraph.api.ImpulseGraphQuery;
import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.bitset.ImpulseBitSet;

// 1. Build immutable query AST (pure ImpScheme S-expression, decoupled from data)
ImpulseGraphQuery<ImpulseBitSet> followersQuery = ImpulseGraphQuery.<ImpulseBitSet>builder()
    .input("User", ArgType.SINGLE_NODE)
    .walkReverse("FOLLOWS")
    .collectRoaringBitset();

// 2. Execute against a specific snapshot
ImpulseBitSet followers = followersQuery.execute(snapshot, aliceNodeId);
```

## 3. Runtime Execution Context (`VmQueryContext`)

When queries execute against the virtual machine, execution state and temporary buffers are managed off-heap via [`VmQueryContext`](file:///Users/jesse/impulse/impulse-graph-java/impulse-vm/src/main/java/org/impulsegraph/vm/VmQueryContext.java):

```
+-------------------------------------------------------------------------+
|                              VmQueryContext                             |
|                                                                         |
|  +---------------------+   +---------------------+   +---------------+  |
|  |  Off-Heap Arena     |   | Scratch Memory Pool |   | BitSet Pool   |  |
|  |  (FFM Arena)        |   | (64KB - 512MB cap)  |   | (OffHeapBitSet|  |
|  +---------------------+   +---------------------+   +---------------+  |
|                                                                         |
|  +---------------------+   +---------------------+   +---------------+  |
|  |  Vector Pools       |   | Inline Data Segment |   | DoP & Threads |  |
|  |  (int/float/double) |   | (constant tables)   |   | (IMPULSE_DOP) |  |
|  +---------------------+   +---------------------+   +---------------+  |
+-------------------------------------------------------------------------+
                                    |
                                    v
       +-----------------------------------------------------------+
       |   640-Byte Off-Heap VM State (impulse_vm_state_t)         |
       |   - PC (uint32)                     - Flags (uint64)      |
       |   - Registers R0..R63 (64 x uint64) - Types (64 x uint8)  |
       |   - Context Pointer (offset 592)    - Call Stack (8 x u32)|
       +-----------------------------------------------------------+
```

### 3.1 Off-Heap Buffer & Scratch Memory Accounting
* **FFM Shared Arena**: Memory allocations use Java 21 LTS Foreign Function & Memory (`java.lang.foreign.Arena`), ensuring all intermediate traversal buffers stay off-heap without garbage collection overhead.
* **Scratch Memory Allocator**: Scratch memory allocations are strictly 64-byte aligned (`(bytes + 63) & ~63L`). Baseline default is 64 KB (`DEFAULT_SCRATCH_BYTES`), with an upper limit cap (default 512 MB, configurable via `setMaxScratchCapacityBytes`).
* **BitSet Pool**: [`acquireBitset()`](file:///Users/jesse/impulse/impulse-graph-java/impulse-vm/src/main/java/org/impulsegraph/vm/VmQueryContext.java#L178-L190) and `releaseBitset(handle)` reuse pre-allocated [`OffHeapBitSet`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/bitset/OffHeapBitSet.java) instances dimensioned to the maximum node count across all relation snapshots.
* **Vector & Value Map Pools**: Vector pools maintain primitive vectors (`int[]`, `float[]`, `double[]`, `long[]`, `String[]`) indexed by integer handles, enabling vector math instructions (`OP_FLOAT_VECTOR_SCALE`, `OP_L1_NORM_DIFF`) to operate without object allocation.
* **Degree of Parallelism (DoP)**: Multi-threading degree of parallelism (`maxThreads` / `maxDop`) is resolved dynamically from `IMPULSE_MAX_DOP` or `IMPULSE_MAX_THREADS` environment variables, defaulting to `Runtime.getRuntime().availableProcessors()`.
* **640-Byte VM State Layout**: `allocateStateSegment()` allocates a 640-byte off-heap [`VmStateLayout.VM_STATE_LAYOUT`](file:///Users/jesse/impulse/impulse-graph-java/impulse-vm/src/main/java/org/impulsegraph/vm/VmStateLayout.java) matching C-ABI `impulse_vm_state_t` (program counter `pc`, flags, registers `R0`..`R63`, register type tags, context address pointer at offset 592, and call stack).

---

## 4. Bytecode Compilation & Dynamic Instruction Patching (`CompiledQuery`)

When an [`ImpulseGraphQuery`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/ImpulseGraphQuery.java) is executed via [`DefaultImpulseQueryEvaluator`](file:///Users/jesse/impulse/impulse-graph-java/impulse-vm/src/main/java/org/impulsegraph/vm/DefaultImpulseQueryEvaluator.java), the AST is compiled into an immutable [`CompiledQuery`](file:///Users/jesse/impulse/impulse-graph-java/impulse-vm/src/main/java/org/impulsegraph/vm/CompiledQuery.java) and cached in `COMPILED_QUERY_CACHE`.

### 4.1 Binding State & Patch Table
A `CompiledQuery` encapsulates:
* **`QueryBindingState`**: An immutable record containing:
  - `snapshot`: The bound `ImpulseGraphSnapshot` instance.
  - `methodHandle`: The JIT-compiled `MethodHandle` combinator.
  - `programSeg`: Off-heap `MemorySegment` storing the 8-byte `impOps` instruction array.
  - `instructionCount`: Total instruction count.
  - `relationIdMap`: Map of logical relation names to physical relation IDs in the snapshot.
* **`List<RelationInstructionPatch>`**: A table of patch locations (`pc`, `logicalRelationName`, `srcReg`, `dstReg`) identifying instructions (such as `OP_CSR_WALK` or `OP_CSC_WALK`) whose payloads embed physical relation IDs.

### 4.2 Lock-Free Snapshot Re-binding (`rebind`)
When a compiled query is executed against a new snapshot instance (e.g. after a blue/green swap), `rebind(newSnapshot)` updates physical pointers without recompiling the query from scratch:

```
[Target Snapshot B Arrives]
            |
            v
1. Verify Logical Relations:
   Ensure all patch relations exist in Snapshot B
            |
            v
2. Clone Instruction Segment:
   Allocate new MemorySegment, copy existing bytecode
   (Active readers on Snapshot A continue uninterrupted)
            |
            v
3. Patch Instruction Payloads:
   Update (relId << 16 | srcReg) for each patch PC
            |
            v
4. Re-JIT MethodHandle & Atomic Swap:
   Compile new MethodHandle and swap AtomicReference<QueryBindingState>
```

```java
// Rebind updates the query's binding state atomically
compiled.rebind(newSnapshot);

// Concurrent queries execute against the new snapshot via MethodHandle or interpreter
Object result = compiled.execute(newSnapshot, seedInput, arena);
```

---

## 5. Blue/Green Snapshot Swaps & Active Reader Draining

High-availability services require zero-downtime graph updates while concurrent queries are actively executing. Impulse Graph achieves this via [`SnapshotSwapManager`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/SnapshotSwapManager.java) and active reader reference counting.

### 5.1 Atomic Pointer Swapping & Virtual Thread Cleanup
`SnapshotSwapManager<T>` manages an `AtomicReference<Holder<T>>`:
1. **Acquiring Snapshot**: Readers call `acquireCurrent()`, which increments `Holder.activeReaders` atomically (`retain()`). Callers invoke `holder.release()` when finished.
2. **Executing Swap**: When an updated snapshot is ready, `swap(newSnapshot)` atomically updates the active holder reference (`currentHolder.getAndSet(newHolder)`).
3. **Async Cleanup on Virtual Thread**: The previous holder (`oldHolder`) is handed to a background virtual thread (`Thread.startVirtualThread`). The thread spins until `oldHolder.getActiveReaders() == 0`, and then closes and unmaps the old off-heap `Arena`. Active in-flight queries complete safely on the old snapshot while new queries immediately access the new snapshot.

```java
import org.impulsegraph.storage.csr.SnapshotSwapManager;
import org.impulsegraph.storage.csr.RelationSnapshot;

// Thread-safe swap manager for off-heap snapshots
try (SnapshotSwapManager<RelationSnapshot> swapMgr = new SnapshotSwapManager<>(snapshotA)) {
    var holder = swapMgr.acquireCurrent();
    try {
        RelationSnapshot active = holder.getResource();
        // Perform queries against active snapshot...
    } finally {
        holder.release();
    }

    // Atomically swap to Snapshot B; Snapshot A closes once active readers drain
    swapMgr.swap(snapshotB);
}
```

### 5.2 Snapshot Reader Tracking & Graceful Draining
`ImpulseGraphSnapshot` exposes native query tracking hooks:
* `enterQuery()` / `exitQuery()`: Increments and decrements active query counts. `CompiledQuery.execute()` wraps execution in `targetSnapshot.enterQuery()` with a guaranteed `finally { targetSnapshot.exitQuery(); }`.
* `getActiveQueryCount()`: Returns the number of queries currently executing against the snapshot.
* `isDrained()`: Returns `true` when active queries reach zero.
* `awaitDrained(long timeout, TimeUnit unit)`: Blocks until all active queries have completed.
* `drainAndClose(long timeout, TimeUnit unit)`: Awaits draining before closing all memory-mapped file channels and unmapping off-heap arenas.

---

## 6. Code Generation & Strongly-Typed Query Builders

To ensure compile-time safety and IDE autocompletion, [`SchemaCodeGenerator`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/schema/SchemaCodeGenerator.java) generates type-safe query builders extending [`TypedQueryBuilder<E, R>`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/schema/TypedQueryBuilder.java):

```
      GraphSchema (.yaml)
               |
               v
      SchemaCodeGenerator
               |
               v
  +--------------------------+
  |    UserQueryBuilder<R>   |  extends TypedQueryBuilder<User, R>
  +--------------------------+
  | - walkFollows()          |  --> returns UserQueryBuilder<R>
  | - filterScore(op, val)   |  --> appends CEL filter
  | - collectRoaringBitset() |  --> terminal ImpulseGraphQuery<ImpulseBitSet>
  | - collectCount()         |  --> terminal ImpulseGraphQuery<Long>
  +--------------------------+
```

### 6.1 Codegen Class Contract
* **Input Detection**: `from(Object input)` automatically assigns [`ArgType.SINGLE_NODE`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/ArgType.java) for numeric scalar IDs or [`ArgType.ROARING_BITSET`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/ArgType.java) for bitset collections.
* **Type-Safe Relations**: Generates `walk<RelationName>()` methods that return the target entity's strongly-typed builder (e.g., `walkFollows()` $\to$ `UserQueryBuilder`).
* **Type-Safe Attribute Filters**: Generates `filter<AttributeName>(String op, <type> val)` that appends validated CEL expressions to the underlying builder.
* **Terminal Operations**: Provides `collectRoaringBitset()`, `collectCount()`, `reduceSum()`, and `reduceFirst()` producing typed [`ImpulseGraphQuery`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/ImpulseGraphQuery.java) instances ready for immediate execution or blue/green snapshot binding.
