# Impulse Graph: Snapshot Streaming Writer & Builder (`impulse-builder`)

The `impulse-builder` module provides a zero-dependency, Java 21 LTS-native streaming builder and serialization engine for the **Impulse Binary Snapshot Format (`.imps`) v0.9.0**.

It enables creating multi-gigabyte and multi-terabyte graph snapshots with **strict $O(\text{chunk})$ physical memory footprints** and direct-to-cloud **S3 single-pass streaming semantics** (zero backwards seeks on the output channel).

---

## 1. Key Architectural Concepts

### 1.1 Strict Single-Pass S3 Semantics
Cloud object stores (Amazon S3, Google Cloud Storage) and streaming sockets accept data strictly sequentially via `WritableByteChannel` or `OutputStream`.
- The engine precomputes layout offsets and stages dynamic sections so the 4KB Page 0 header and Section 2 directory tables are streamed in exact physical byte order.
- Trailing metadata (custom key-value pairs, binary payloads, statistics) and mandatory SHA-256 checksums are streamed into the **Footer Block at End-of-File (EOF)**, followed by a 16-byte `impulse_footer_trailer_t` that points backward to the footer start offset.

### 1.2 Strict Per-Domain Dense ID Independence ($0 \dots N_d - 1$)
In Impulse Graph, **there is no global or synthetic node ID space**. Every Node Domain (e.g., `User`, `Account`, `Transaction`) owns its own 0-indexed dense integer space $0 \dots N_d - 1$.
- When defining a domain, the `cardinality` specified is the **exact physical node count**, not a maximum capacity bound.
- Node IDs are implicit: physical storage is dedicated entirely to attribute arrays and relation topologies indexed directly by dense node ID.

### 1.3 Mandatory CSR & Optional Topologies
Every relation in an Impulse snapshot requires a **CSR (Compressed Sparse Row)** topology to enable instantaneous outgoing edge traversals.
- **CSR (Compressed Sparse Row)**: Mandatory. Edges are sorted primarily by **Source ID**. The builder automatically defaults to CSR if not explicitly specified.
- **CSC (Compressed Sparse Column)**: Optional. Edges are sorted primarily by **Target ID** for instant incoming/reverse edge lookups (`OP_CSC_WALK`).
- **COO (Coordinate List)**: Optional. Raw `(src, tgt)` edge coordinate pairs.
- **Per-Topology Compression**: Configured per topology via `CompressionScheme` (`RAW` for standard C-ABI contiguous arrays, `SIMD_COMP` for vectorized compression, `TPU_BCOO` for blocked coordinate formats).

### 1.4 Zero-Allocation Java 21 LTS FFM Streaming
Data is supplied through chunk iterators using Java 21 LTS Foreign Function & Memory (FFM) `MemorySegment` buffers. No `List<Edge>` or per-node Java heap objects are allocated, preventing GC pauses during massive graph ingestion.

### 1.5 Adaptive NVMe Disk Staging
If the user requests a CSC index but only provides a Source-sorted edge stream, or when large data structures exceed the configured heap threshold, `SnapshotBuilder` spills chunks to a configured `stagingDirectory` using `ExternalSortStaging` to perform an external two-pass inversion before streaming the sorted blocks.

### 1.6 Integrity & Cryptographic Trust Chains
- **Mandatory SHA-256 Digest**: Calculated on the fly over the snapshot payload and embedded directly in the Footer Block.
- **Optional Cryptographic Signing**: With `.withSignature(PrivateKey, List<Certificate>)`, the engine signs the payload digest and embeds the cryptographic signature and certificate chain into the footer for tamper resistance (SLSA Level 3 compliance).

---

## 2. Quickstart Example

```java
import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class SnapshotExportExample {

    public static void main(String[] args) throws Exception {
        // 1. Define the 'User' domain (1,000,000 users)
        DomainDefinition userDomain = DomainDefinition.builder(1_000_000L)
            .idWidth(PrimitiveWidth.UINT32)
            .addAttribute("age", new UserAgeSource())
            .build();

        // 2. Define the 'Account' domain (500,000 accounts)
        DomainDefinition accountDomain = DomainDefinition.builder(500_000L)
            .idWidth(PrimitiveWidth.UINT32)
            .build();

        // 3. Define the 'OWNS' relationship from User to Account
        RelationDefinition ownsRelation = RelationDefinition.builder("User", "Account")
            .addTopology(Topology.CSR, CompressionScheme.RAW)
            .addTopology(Topology.CSC, CompressionScheme.RAW)
            .dataSource(new UserAccountEdgeSource()) // Provides source-sorted edges
            .build();

        // 4. Stream snapshot direct-to-disk or cloud channel
        Path outputPath = Path.of("banking_graph.imps");
        SnapshotBuilder.create()
            .withStagingDirectory(Path.of("/tmp/impulse-staging"))
            .withStagingMemoryLimit(512 * 1024 * 1024L) // 512 MB staging limit
            .addDomain("User", userDomain)
            .addDomain("Account", accountDomain)
            .addRelation("OWNS", ownsRelation)
            .addFooterMetadata("environment", "production")
            .addFooterMetadata("generator", "org.impulsegraph.builder")
            .writeTo(outputPath);
    }
}
```

### 2.1 Implementing `UserAgeSource`
```java
public class UserAgeSource implements AttributeDataSource {
    private static final int TOTAL_USERS = 1_000_000;

    @Override
    public DataType dataType() {
        return DataType.I32;
    }

    @Override
    public Nullability nullability() {
        return Nullability.NULLABLE;
    }

    @Override
    public AttributeChunkIterator iterator() {
        return new AttributeChunkIterator() {
            private int current = 0;

            @Override
            public boolean hasNext() {
                return current < TOTAL_USERS;
            }

            @Override
            public int nextChunk(MemorySegment destination, int limit) {
                int count = 0;
                while (current < TOTAL_USERS && count < limit) {
                    destination.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, 20 + (current % 60));
                    current++;
                    count++;
                }
                return count;
            }
        };
    }
}
```

### 2.2 Implementing `UserAccountEdgeSource`
```java
public class UserAccountEdgeSource implements RelationDataSource {
    private final long edgeCount = 2_000_000L;

    @Override
    public long getEdgeCount() {
        return edgeCount;
    }

    @Override
    public EdgeChunkIterator getEdges() {
        return new EdgeChunkIterator() {
            private long index = 0;

            @Override
            public boolean hasNext() {
                return index < edgeCount;
            }

            @Override
            public int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit) {
                int count = 0;
                while (index < edgeCount && count < limit) {
                    // User 0..999,999 owns Account 0..499,999
                    srcIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) (index / 2));
                    tgtIds.setAtIndex(ValueLayout.JAVA_INT_UNALIGNED, count, (int) (index % 500_000));
                    index++;
                    count++;
                }
                return count;
            }
        };
    }
}
```

---

## 3. Data Provider SPI Contracts

### 3.1 Attribute Data Source (`AttributeDataSource`)
An attribute source streams values for exactly $N$ entities in ascending order of their implicit dense ID ($0, 1, 2, \dots, N-1$):

```java
public interface AttributeDataSource {
    /**
     * Primitive or vector data type of the attribute.
     */
    DataType dataType();

    /**
     * Declares whether the attribute is nullable or non-null.
     */
    Nullability nullability();

    /**
     * Iterator yielding chunks of values directly into off-heap memory.
     */
    AttributeChunkIterator iterator();

    /**
     * Optional statistics collector hook for computing distribution metrics into the footer.
     */
    default Optional<StatCollector> statCollector() {
        return Optional.empty();
    }
}
```

The chunk iterator writes directly to an off-heap `MemorySegment`:

```java
public interface AttributeChunkIterator {
    boolean hasNext();
    
    /**
     * Fills the segment with up to `limit` values.
     * @param destination off-heap memory segment aligned to the attribute type width
     * @param limit maximum number of items to write
     * @return count of items written in this chunk
     */
    int nextChunk(MemorySegment destination, int limit);
}
```

#### Running Statistics Hook (`StatCollector`)
```java
public interface StatCollector {
    /**
     * Called for each chunk of attribute values written to the stream.
     */
    void observeChunk(MemorySegment segment, int count);

    /**
     * Finalizes and serializes the statistics metadata payload to be stored in the footer.
     */
    byte[] finalizeMetadataPayload();
}
```

### 3.2 Relation Data Source (`RelationDataSource`)
```java
public interface RelationDataSource {
    /**
     * Total exact number of directed edges in this relation.
     */
    long getEdgeCount();

    /**
     * Primary edge stream, strictly sorted by Source ID (mandatory for building CSR).
     */
    EdgeChunkIterator getEdges();

    /**
     * Optional secondary edge stream, strictly sorted by Target ID (for building CSC).
     * If empty and CSC is requested, the builder will use the staging directory to
     * external-sort the primary stream into CSC layout.
     */
    default Optional<EdgeChunkIterator> getTargetSortedEdges() {
        return Optional.empty();
    }
}
```

The edge chunk iterator writes parallel `(src, tgt)` pairs directly into off-heap buffers:

```java
public interface EdgeChunkIterator extends AutoCloseable {
    boolean hasNext();

    /**
     * Populates the provided parallel off-heap memory segments with edge pairs.
     *
     * @param srcIds segment receiving source node IDs formatted to the source domain's primitive width
     * @param tgtIds segment receiving target node IDs formatted to the target domain's primitive width
     * @param limit  maximum number of edges to populate
     * @return count of edges actually populated
     */
    int nextChunk(MemorySegment srcIds, MemorySegment tgtIds, int limit);

    @Override
    default void close() throws Exception {}
}
```

---

## 4. API Reference Summary

### 4.1 `SnapshotBuilder`
| Method | Description |
| :--- | :--- |
| `SnapshotBuilder.create()` | Factory method creating a `StreamingSnapshotWriter` instance. |
| `withStagingDirectory(Path tempDir)` | Configures local directory for disk-backed staging (e.g. CSR $\to$ CSC external sorts). |
| `withStagingMemoryLimit(long bytes)` | Configures maximum RAM limit before spilling chunks to staging directory (default: 64MB). |
| `withSignature(PrivateKey, List<Certificate>)` | Cryptographically signs the SHA-256 payload checksum and embeds signature & certificates into the footer. |
| `addDomain(String name, DomainDefinition domain)` | Registers a node domain schema and its data sources. |
| `addRelation(String name, RelationDefinition rel)` | Registers a relation schema and its topology sources. |
| `addFooterMetadata(String key, String value)` | Adds custom key-value string metadata to the trailing footer. |
| `addFooterMetadata(String key, byte[] payload)` | Adds raw binary metadata payload to the trailing footer. |
| `writeTo(WritableByteChannel channel)` | Sequentially streams the snapshot binary in a strict single pass (e.g. S3 uploads). |
| `writeTo(OutputStream out)` | Convenience method streaming the snapshot binary to an `OutputStream`. |
| `writeTo(Path path)` | Convenience method writing the snapshot directly to a file on disk. |
| `toByteArray()` | Collects snapshot binary into an in-memory `byte[]` (convenient for testing). |

### 4.2 Schema Definitions & Enums
- **`DomainDefinition.builder(long cardinality)`**:
  - `.idWidth(PrimitiveWidth)`: `UINT16`, `UINT32` (default), or `UINT64`.
  - `.withPrimaryKeyIndex(boolean)`: Enables reverse lookup index generation.
  - `.addAttribute(String name, AttributeDataSource source)`: Registers an attribute data source.
- **`RelationDefinition.builder(String srcDomain, String tgtDomain)`**:
  - `.addTopology(Topology, CompressionScheme)`: Topologies `CSR` (mandatory, default `RAW`), `CSC` (optional), `COO` (optional). Compression: `RAW`, `SIMD_COMP`, `TPU_BCOO`.
  - `.addAttribute(String name, AttributeDataSource source)`: Registers an edge attribute source.
  - `.dataSource(RelationDataSource)`: Edge stream source.
- **`DataType`**: Primitive types (`I8`, `I16`, `I32`, `I64`, `F16`, `F32`, `F64`, `TIMESTAMP_MS`, `TIMESTAMP_NS`, `STRING`, `BYTES`), fixed vectors (`DataType.vector(base, dim)`), and fixed bytes (`DataType.fixedBytes(dim)`).
- **`Nullability`**: `NON_NULL` (`0x00`) or `NULLABLE` (`0x80`, generating a 128-byte aligned validity bitmap).

---

## 5. Verification and Testing

Snapshots generated by `impulse-builder` can be verified using three distinct validation layers:

### 5.1 In-Memory Verification (Java)
Load snapshot bytes or files via `BinarySnapshotLoader` using a Java 21 LTS `Arena`:

```java
byte[] snapshotBytes = builder.toByteArray();

try (Arena arena = Arena.ofConfined()) {
    BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotBytes, arena);
    assertNotNull(loaded);

    // Verify catalog domains and counts
    assertEquals(2, loaded.domainCount());
    assertEquals("User", loaded.domainsById().get(0).name());
    assertEquals("Account", loaded.domainsById().get(1).name());

    // Verify graph and relation snapshots
    GraphSnapshot graph = loaded.graph();
    RelationSnapshot rel = graph.getRelationSnapshot("OWNS");
    assertNotNull(rel);
    assertEquals(1_000_000L, rel.getNodeCount());
    assertEquals(2_000_000L, rel.getEdgeCount());

    // Verify forward CSR adjacency
    int[] user0Accounts = rel.getTargets(0);
    assertNotNull(user0Accounts);

    // Verify reverse CSC adjacency (if CSC requested)
    if (rel.hasCsc()) {
        int[] account0Owners = rel.getInTargets(0);
        assertNotNull(account0Owners);
    }

    // Verify footer metadata
    assertEquals("production", loaded.getMetadata("environment"));
}
```

Loading from a file on disk with mandatory SHA-256 checksum verification:

```java
Path snapshotPath = Path.of("banking_graph.imps");
try (Arena arena = Arena.ofShared()) {
    BinarySnapshotLoader.LoadedSnapshot loaded = 
        BinarySnapshotLoader.loadSnapshot(snapshotPath, arena, true /* verifyChecksum */);
    GraphSnapshot graph = loaded.graph();
    assertNotNull(graph);
}
```

### 5.2 Query Execution Verification (ImpulseVM)
Mount the snapshot file into an off-heap `GraphSnapshot` (implementing `ImpulseGraphSnapshot`) and execute compiled bytecode traversals (`OP_CSR_WALK`, `OP_MXV`):

```java
try (Arena arena = Arena.ofShared()) {
    BinarySnapshotLoader.LoadedSnapshot loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);
    GraphSnapshot graph = loaded.graph();

    try (VmQueryContext ctx = new VmQueryContext(graph, arena)) {
        MemorySegment state = ctx.allocateStateSegment();
        // Execute compiled bytecode traversal over off-heap memory
        Object result = ImpulseVmInterpreter.execute(bytecodeSegment, instructionCount, graph, inputNode, arena);
    }
}
```

### 5.3 Native CLI Tooling Verification
Verify compliance using the official `impulse-graph` CLI toolchain:

```bash
# 1. Inspect headers, domains, and topology tables
impulse-graph inspect banking_graph.imps

# 2. Strict C-ABI v0.9.0 validation with 128-byte hardware alignment check
impulse-graph snapshot validate --strict-alignment banking_graph.imps

# 3. View structural degree statistics & distribution metrics
impulse-graph stats banking_graph.imps
```

