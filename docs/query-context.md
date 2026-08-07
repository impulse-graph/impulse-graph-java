# ImpulseGraph Query Context & Execution

This document describes how `ImpulseGraphQuery` objects interact with the underlying graph data via a context mechanism, especially in high-availability scenarios involving "blue/green" snapshot swaps.

## The Query Lifecycle

In ImpulseGraph, queries are constructed as immutable ASTs (Abstract Syntax Trees) that are decoupled from any specific graph instance.

```java
// 1. Construct a query (decoupled from data)
var userFollowersQuery = User.query()
    .walk(User.FOLLOWS, Direction.IN)
    .collectCount();

// 2. Execute against a specific graph
int followers = userFollowersQuery.execute(myGraph, userId);
```

## Internal Context & Execution

When `execute()` is called, the engine binds the query AST to a specific `ImpulseGraphSnapshot`. This binding creates an internal **Execution Context**.

### Context Composition
The context contains:
*   **Memory Pointers**: Off-heap memory addresses for the CSR/CSC arrays of all relations involved in the query.
*   **Cardinalities**: Pre-calculated node and edge counts used for buffer allocation during execution.
*   **Layout Metadata**: FFM `MemoryLayout` descriptors for attribute access.

### Performance & Snapshot Swaps
ImpulseGraph is designed for low-latency queries even while the graph is being updated. This is achieved through a "blue/green" snapshot swap mechanism.

1.  **Immutable Snapshots**: Queries execute against an immutable `ImpulseGraphSnapshot`.
2.  **Snapshot Swapping**: When a new version of the graph is ready (e.g., after a compaction), the `SnapshotSwapManager` atomically updates the pointer to the "current" snapshot.
3.  **Automatic Re-compilation**: If a query is held by a long-running service, the engine can detect a snapshot swap. On the next execution, the query's internal pointers are automatically updated to point to the new off-heap segments. This ensures that the query always sees the most recent stable data without manual developer intervention.

## Thread Safety & Memory Management
*   **Reference Counting**: The `SnapshotSwapManager` uses atomic reference counting to track active readers. A snapshot will not be closed/unmapped until all queries executing against it have completed.
*   **Zero-Copy Execution**: Because the context points directly to memory-mapped files via the FFM API, no data is copied into the JVM heap during context updates or query execution.

## Summary for Codegen
The generated code (POJOs and Query Builders) should facilitate this pattern by:
1.  Providing fluent builders that produce `ImpulseGraphQuery` instances.
2.  Ensuring that generated accessors utilize the `MemorySegment` pointers provided by the active context.




