# Impulse Graph: Snapshot Streaming Writer & Builder (`impulse-builder`)

The `impulse-builder` module provides a zero-dependency, Java 25-native streaming builder and serialization engine for the **Impulse Binary Snapshot Format (`.imps`) v0.9.0**.

It enables creating multi-gigabyte and multi-terabyte graph snapshots with **strict $O(\text{chunk})$ physical memory footprints** and direct-to-cloud **S3 single-pass streaming semantics** (zero backwards seeks on the output channel).

---

## 1. Key Architectural Concepts

### 1.1 Strict Single-Pass S3 Semantics
Cloud object stores (Amazon S3, Google Cloud Storage) and streaming sockets accept data strictly sequentially via `WritableByteChannel` or `OutputStream`.
- The engine computes layout offsets and stages dynamic sections so the 4KB Page 0 header and Section 2 directory tables are streamed in exact physical byte order.
- Trailing metadata (statistics, running histograms) and mandatory SHA-256 checksums are streamed into the **Footer Block at End-of-File (EOF)**, followed by a 16-byte `impulse_footer_trailer_t` that points backward to the footer start offset.

### 1.2 Strict Per-Domain Dense ID Independence ($0 \dots N_d - 1$)
In Impulse Graph, **there is no global or synthetic node ID space**. Every Node Domain (e.g., `User`, `Device`, `Transaction`) owns its own 0-indexed dense integer space $0 \dots N_d - 1$.
- When defining a domain, the `cardinality` specified is the **exact physical node count**, not a maximum capacity.
- Node IDs are implicit: physical storage is dedicated entirely to attribute arrays and relation topologies indexed by dense node ID.

### 1.3 Mandatory CSR & Optional Topologies
Every relation in an Impulse snapshot **must have a CSR (Compressed Sparse Row) topology** to enable instantaneous outgoing edge traversals.
- **CSR (Compressed Sparse Row)**: Mandatory. Edges are sorted primarily by **Source ID**.
- **CSC (Compressed Sparse Column)**: Optional. Edges are sorted primarily by **Target ID** for instant reverse/incoming traversals.
- **COO (Coordinate List)**: Optional. Raw `(src, tgt)` edge tuples.
- **Per-Topology Compression**: Compression is configured per topology (e.g. `RAW` for CSR and `SIMD_COMP` for CSC within the same relationship).

### 1.4 Zero-Allocation Java 25 FFM Streaming
Data is supplied through chunk iterators using Java 25 Foreign Function & Memory (FFM) `MemorySegment` buffers. No `List<Edge>` or per-node Java heap objects are allocated, preventing GC pauses during massive graph ingestion.

### 1.5 Adaptive NVMe Disk Staging
If the user requests a CSC index but only provides a Source-sorted edge stream, or when large string pools exceed the configured heap threshold, `SnapshotBuilder` spills chunks to a configured `stagingDirectory` to perform external parallel merge-sorts before streaming the sorted blocks.

### 1.6 Integrity & Cryptographic Trust Chains
- **Mandatory SHA-256 Digest**: Calculated on the fly over the snapshot payload and embedded directly in the Footer Block.
- **Optional Cryptographic Signing**: With `.withSignature(PrivateKey, List<Certificate>)`, the engine signs the payload digest and embeds the cryptographic signature and certificate chain into the footer for tamper resistance (SLSA Level 3 compliance).

---

## 2. Quickstart Example

```java
import org.impulsegraph.builder.api.*;
import org.impulsegraph.builder.spi.*;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class SnapshotExportExample {

    public static void main(String[] args) throws Exception {
        // 1. Define the 'User' domain (1,000,000 users)
        DomainDefinition userDomain = DomainDefinition.builder(1_000_000L)
            .idWidth(PrimitiveWidth.UINT32)
            .addAttribute("age", DataType.I32, Nullability.NULLABLE, new UserAgeSource())
            .addAttribute("embedding", DataType.vector(DataType.F32, 64), Nullability.NON_NULL, new UserEmbeddingSource())
            .build();

        // 2. Define the 'Account' domain (500,000 accounts)
        DomainDefinition accountDomain = DomainDefinition.builder(500_000L)
            .idWidth(PrimitiveWidth.UINT32)
            .build();

        // 3. Define the 'OWNS' relationship from User to Account
        RelationDefinition ownsRelation = RelationDefinition.builder("User", "Account")
            .addTopology(Topology.CSR, CompressionScheme.RAW)
            .addTopology(Topology.CSC, CompressionScheme.SIMD_COMP)
            .dataSource(new UserAccountEdgeSource()) // Provides source-sorted edges
            .build();

        // 4. Stream snapshot direct-to-disk or cloud channel
        Path outputPath = Path.of("banking_graph.imps");
        try (FileChannel channel = FileChannel.open(outputPath, 
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            
            SnapshotBuilder.create()
                .withStagingDirectory(Path.of("/tmp/impulse-staging"))
                .withStagingMemoryLimit(512 * 1024 * 1024L) // 512 MB
                .addDomain("User", userDomain)
                .addDomain("Account", accountDomain)
                .addRelation("OWNS", ownsRelation)
                .writeTo(channel);
        }
    }
}
```

---

## 3. Data Provider SPI Contracts

### 3.1 Attribute Data Source (`AttributeDataSource`)
An attribute source streams values for exactly $N$ nodes in ascending order of their implicit dense ID ($0, 1, 2, \dots, N-1$):

```java
public interface AttributeChunkIterator {
    boolean hasNext();
    
    /**
     * Fills the segment with up to `limit` values.
     * @param segment off-heap memory segment aligned to the attribute type width
     * @param limit maximum number of items to write
     * @return count of items written in this chunk
     */
    int nextChunk(MemorySegment segment, int limit);
}
```

For nullable attributes, a parallel bitset chunk iterator can be supplied, or nullability masks can be packed alongside data.

### 3.2 Relation Data Source (`RelationDataSource`)
```java
public interface RelationDataSource {
    long getEdgeCount();

    /**
     * Primary edge stream, strictly sorted by Source ID (required for CSR).
     */
    EdgeChunkIterator getEdges();

    /**
     * Optional secondary edge stream, strictly sorted by Target ID (for CSC).
     * If empty, the builder will use the staging directory to external-sort the primary stream.
     */
    default Optional<EdgeChunkIterator> getTargetSortedEdges() {
        return Optional.empty();
    }
}
```

---

## 4. Verification and Testing

Snapshots generated by `impulse-builder` can be verified using three distinct validation layers:

### 4.1 In-Memory Verification (Java)
```java
byte[] snapshotBytes = builder.toByteArray();
GraphSnapshot snapshot = BinarySnapshotLoader.load(MemorySegment.ofArray(snapshotBytes));
assertEquals(1_000_000L, snapshot.getNodeCount());
```

### 4.2 Query Execution Verification (ImpulseVM)
Mount the snapshot file into an off-heap `ImpulseGraphSnapshot` and execute compiled bytecode traversals (`OP_CSR_WALK`, `OP_MXV`).

### 4.3 Native CLI Tooling Verification
Verify compliance using the official `impulse-graph` CLI toolchain:

```bash
# 1. Inspect headers, domains, and topology tables
impulse-graph inspect banking_graph.imps

# 2. Strict C-ABI v0.9.0 validation with 128-byte hardware alignment check
impulse-graph snapshot validate --strict-alignment banking_graph.imps
```
