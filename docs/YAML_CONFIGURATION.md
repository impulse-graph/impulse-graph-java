# YAML Manifest & Tablespace Configuration

> [!NOTE]
> **Normative Specification Alignment**: This documentation defines the multi-file chunking and declarative schema configuration for **Impulse Graph Engine** (`impulse-graph`), conforming to the **Impulse Binary Snapshot Format (`.imps`) v0.9.0** and the Java 21 LTS Foreign Function & Memory (FFM) runtime.

For enterprise deployments with datasets exceeding single-file I/O limits, multi-terabyte analytics, or partitioned domain catalogs, Impulse Graph Engine supports multi-file chunking and composite graph loading via a declarative `manifest.yaml` configuration.

---

## 1. Core Architectural Conventions

When configuring or loading composite graphs via YAML manifests, the following engine invariants apply:

### 1.1 Strict Per-Domain Dense ID Independence ($0 \dots N_d - 1$)
In Impulse Graph Engine, **there is NO global flattened or synthetic unified node ID space**. Every Node Domain (e.g. `User`, `Product`, `Group`) maintains its own completely independent, 0-indexed dense integer space $0 \dots N_d - 1$.
* Dense node ID `0` in domain `User` is fundamentally distinct and unrelated to dense node ID `0` in `Product` or `Group`.
* Traversal entrypoints MUST bind an explicit domain context in multi-domain graphs (`snap.domain("User").traverse([1, 2, 3])`).
* Every relation descriptor explicitly encodes `SrcDomainID` and `TgtDomainID`. Traversing an edge relation (`(User)-[:PURCHASED]->(Product)`) maps source dense IDs in `SrcDomainID` to target dense IDs in `TgtDomainID`.

### 1.2 Configurable Primitive Integer Widths per Domain
Each Node Domain independently configures its physical integer representation width in the binary snapshot layout based on its domain cardinality:
* `uint16_t` / `UINT16` / `int16` (up to 65,536 nodes): Optimal for compact catalogs, categorical taxonomies, and small entity domains.
* `uint32_t` / `UINT32` / `int32` (up to 4,294,967,296 nodes): Standard default for high-scale enterprise graphs.
* `uint64_t` / `UINT64` / `int64`: Hyperscale addressing for multi-billion or trillion-node domains.

### 1.3 Zero-Copy Off-Heap Execution & 128-Byte Alignment
All binary tablespaces (`.imps`) are memory-mapped (`mmap`) off-heap using Java 21 LTS FFM `Arena` segments:
* **Fixed 4KB Page 0 Header**: Baseline header with magic `0x494D5053` (`IMPS`), 16-bit packed version `0x0009` (v0.9.0), timestamp, and section directory offsets.
* **128-Byte Hardware Alignment**: All topological offset sections, target index arrays, and property buffers enforce strict 128-byte alignment for AVX-512 vector units, GPU warp coalescing (NVIDIA GPUDirect Storage `cuFile`), and TPU vector tiles.
* **Single-Pass Cloud S3 Streaming**: Snapshots are serialized direct-to-disk or cloud object storage (Amazon S3, Google Cloud Storage) with an $O(\text{chunk})$ physical RAM footprint without requiring random file seeks.

---

## 2. Manifest Structure (`manifest.yaml`)

A `manifest.yaml` acts as the declarative manifest linking logical domains, relations, and the physical `.imps` tablespaces that back them.

### Top-Level Properties

| Property | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `graphName` | `string` | `"ImpulseGraph"` | Logical identifier for the composite graph. Used in logging and facade registration. |
| `version` | `string` | `"1.0"` | Manifest specification version. |
| `tablespaces` | `map` | `{}` | Map of named physical `.imps` tablespace definitions. |
| `domains` | `map` | `{}` | Map of entity/node domain catalog definitions. |
| `relations` | `map` | `{}` | Map of physical directed edge relation definitions. |
| `virtual_relations` | `map` | `{}` | Map of logical coproducts and derived algorithmic relations. |

### Example `manifest.yaml`

```yaml
graphName: "EnterpriseKnowledgeGraph"
version: "1.0.0"

tablespaces:
  core_identity:
    file: "chunks/core_identity.imps"
    description: "User profiles, accounts, and internal relationships"
    mode: "read-only"
  product_catalog:
    file: "chunks/product_catalog.imps"
    description: "Product entity catalog and category hierarchies"
    mode: "read-only"
  transactions_2025:
    file: "chunks/transactions_2025.imps"
    description: "High-volume purchase and interaction event edges"
    mode: "read-only"

domains:
  User:
    tablespace: core_identity
    denseId: int32
    key: uuid
    attributes:
      account_status: "FixedString(8)"
      display_name: "VarString"
      embedding: "Float32[128]"
  Product:
    tablespace: product_catalog
    denseId: int32
    key: int64
    attributes:
      sku: "FixedString(16)"
      price: "Float32"
      category_id: "Int16"
  Category:
    tablespace: product_catalog
    denseId: int16
    key: int32
    attributes:
      title: "VarString"

relations:
  PURCHASED:
    source: User
    target: Product
    tablespace: transactions_2025
    direction: [out, in]
    cardinality: many_to_many
    attributes:
      timestamp: "TimestampMicro"
      quantity: "Int16"
      unitPrice: "Float32"
  BELONGS_TO:
    source: Product
    target: Category
    tablespace: product_catalog
    direction: [out]
    cardinality: many_to_one
```

---

## 3. Domain Catalog & Primitive Integer Widths

In Impulse Graph Engine, domains are first-class execution boundaries. Each domain defined in `manifest.yaml` maps to an independent dense ID space and defines its internal addressing width.

### 3.1 Domain Configuration Options

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `tablespace` | `string` | **Required** | Name of the tablespace hosting this domain's attributes and catalog data. |
| `denseId` / `idWidth` | `string` | `"int32"` | Internal primitive integer width for node IDs in this domain. |
| `key` | `string` | `"int64"` | Primary key format for external entity lookups (`int32`, `int64`, `uuid`, `string`, `bytes`). |
| `attributes` | `map` | `{}` | Map of strongly-typed property attributes attached to nodes in this domain. |

### 3.2 Primitive Integer Width Matrix

Integer widths are configured independently per domain to minimize memory bandwidth and maximize CPU L1/L2/L3 cache utilization:

| Width Configuration | Java Enum (`PrimitiveWidth`) | C-ABI Type | Max Node Count | Primary Use Case |
| :--- | :--- | :--- | :--- | :--- |
| `int16` / `uint16` | `PrimitiveWidth.UINT16` | `uint16_t` (2 bytes) | 65,536 | Compact catalogs, categorical entity types, status taxonomies. |
| `int32` / `uint32` | `PrimitiveWidth.UINT32` | `uint32_t` (4 bytes) | 4,294,967,296 | Standard default for enterprise-scale entity graphs. |
| `int64` / `uint64` | `PrimitiveWidth.UINT64` | `uint64_t` (8 bytes) | $1.84 \times 10^{19}$ | Hyperscale web graphs, genomics, and global identifier spaces. |

### 3.3 Domain-Bound Query Invariant
Because node IDs are independent 0-indexed dense integers per domain ($0 \dots N_d-1$), query pipelines MUST anchor to an explicit domain context:
```java
// Valid domain-anchored traversal
graph.domain("User").traverse(42);

// Direct relation traversal maps User (src) dense ID -> Product (tgt) dense ID
RelationSnapshot purchased = (RelationSnapshot) graph.getRelationSnapshot("PURCHASED");
int[] productIds = purchased.getTargets(userId);
```

---

## 4. Relations, Topologies & Compression

A `relation` defines a directed bipartite or homogeneous adjacency matrix connecting nodes from `source` domain to `target` domain.

### 4.1 Relation Configuration Options

| Option | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `source` | `string` | **Required** | Source node domain name. |
| `target` | `string` | **Required** | Target node domain name. |
| `tablespace` | `string` | **Required** | Name of the tablespace file backing this relation's physical topology. |
| `direction` | `list` | `[out]` | Storage topologies to compile: `[out]` (CSR), `[in]` (CSC), or `[out, in]` (CSR + CSC). |
| `cardinality` | `string` | `"many_to_many"` | Relational constraint: `one_to_one`, `one_to_many`, `many_to_one`, `many_to_many`. |
| `inverseAlias` | `string` | `null` | Logical alias for reverse edge traversals (e.g. `followedBy` for `FOLLOWS`). |
| `attributes` | `map` | `{}` | Map of strongly-typed edge attributes stored with each edge index. |

### 4.2 Storage Topologies & Traversal Direction

* **`[out]` — Compressed Sparse Row (CSR)**:
  * **Mandatory** for all relations.
  * Edges are sorted primarily by **Source ID**.
  * Enables zero-copy outgoing traversals via `.walkOut()` or `getTargets(srcId)` in $O(\text{degree})$.
* **`[in]` — Compressed Sparse Column (CSC)**:
  * **Optional** reverse index.
  * Edges are sorted primarily by **Target ID**.
  * Enables instant incoming edge lookups via `.walkIn()` without scanning outgoing edges.
* **`[out, in]`**: Generates both CSR and CSC index tables within the snapshot, enabling bidirectional traversals.

### 4.3 Topology Compression Schemes

In the underlying `.imps` binary format, each topology can independently configure its compression encoding:

| Scheme | Binary Code | Description |
| :--- | :--- | :--- |
| `RAW` | `0x00` | Uncompressed, 128-byte hardware-aligned contiguous C-ABI array layout. Optimal for maximum SIMD traversal throughput. |
| `SIMD_COMP` | `0x01` | Vectorized differential integer compression. Reduces physical storage footprint while executing SIMD decompression. |
| `TPU_BCOO` | `0x06` | Blocked Coordinate (BCOO) format for direct TPU/GPU matrix ingestion. |

---

## 5. Property Attributes & Supported Data Types

Attributes can be attached to both node domains and edge relations. All attributes are laid out in **Structure-of-Arrays (SoA)** format with strict 128-byte hardware alignment conforming to the C-ABI Binary Snapshot v0.9.0 specification.

### 5.1 Supported Attribute Types

| Data Type | Memory Size | Description |
| :--- | :--- | :--- |
| `Int8` | 1 byte | Signed 8-bit integer (`byte`). |
| `Int16` | 2 bytes | Signed 16-bit integer (`short`). |
| `Int32` | 4 bytes | Signed 32-bit integer (`int`). |
| `Int64` | 8 bytes | Signed 64-bit integer (`long`). |
| `Float16` | 2 bytes | Half-precision IEEE 754 floating point. |
| `Float32` | 4 bytes | Single-precision IEEE 754 float (`float`). |
| `Float64` | 8 bytes | Double-precision IEEE 754 float (`double`). |
| `Bool` | 1 byte | Boolean flag (`0x00` = false, `0x01` = true). |
| `TimestampMs` | 8 bytes | 64-bit millisecond Unix epoch timestamp. |
| `TimestampNs` | 8 bytes | 64-bit nanosecond Unix epoch timestamp. |
| `TimestampMicro` | 8 bytes | 64-bit microsecond Unix epoch timestamp. |
| `IntervalSec32` | 8 bytes | 32-bit time interval in seconds. |
| `IntervalMs64` | 16 bytes | 64-bit time interval in milliseconds. |
| `FixedString(N)` | `N` bytes | Fixed-length, zero-padded UTF-8 string. Stored inline without string-pool pointer indirection for fast stride scans. |
| `VarString` | Variable | Variable-length UTF-8 string referenced via offset into the Section 2 Global String Table. |
| `VarBytes` | Variable | Variable-length raw binary payload (BLOB). |

### 5.2 Vector Dimensions & Embeddings
Any primitive numeric type can declare an array/vector dimension using square-bracket notation (e.g. `Float32[128]`, `Float32[768]`, `Int32[64]`):
* Vectors are serialized as dense contiguous byte blocks aligned to 128-byte hardware boundaries.
* Zero-copy accessible as raw off-heap `MemorySegment` slices or PyTorch `torch.from_blob` tensor views.

### 5.3 Nullability & Validity Bitmaps
* **Non-Null** (`nullable: false`, default): Direct primitive array layout without overhead.
* **Nullable** (`nullable: true`): Generates a dedicated 128-byte aligned **Bitwise Validity Bitmap** preceding the attribute data buffer (type code high bit `0x80`).

---

## 6. Virtual Relations (Coproducts & Computed Graphs)

A **Virtual Relation** logically groups multiple physical relations into a unified query target or computes edges dynamically at query time without consuming physical disk space.

### 6.1 Physical Coproducts (`components`)

When multiple relations represent variations of a common interaction, group them into a single virtual relation:

```yaml
virtual_relations:
  ENGAGED_WITH:
    components:
      - ENGAGED_WITH_LIKES
      - ENGAGED_WITH_COMMENTS
      - ENGAGED_WITH_SHARES
      - ENGAGED_WITH_POSTS
```

When executing `.walkEdge("ENGAGED_WITH")`, the query compiler statically expands the traversal into an optimized coproduct over the underlying component CSR indices.

### 6.2 Declarative Algorithmic Relations

Virtual relations can also be computed via declarative **ImpLog** (Datalog reachability rules) or **ImpK** (matrix algebra) queries:

```yaml
virtual_relations:
  TRANSITIVE_MANAGER_OF:
    language: "ImpLog"
    query: |
      TRANSITIVE_MANAGER_OF(m, e) :- REPORTS_TO(e, m).
      TRANSITIVE_MANAGER_OF(m, e) :- REPORTS_TO(e, m1), TRANSITIVE_MANAGER_OF(m, m1).
    caching: "transient"
```

---

## 7. Snapshot Metadata & Footer Trailer

Impulse snapshots encode key-value string dictionaries, degree distributions, and cryptographic verification structures into a trailing footer block at EOF:

1. **Footer Dictionary**: Key-value pairs stored immediately before the EOF trailer.
2. **Trailer POD (16 Bytes)**: Conforming to `impulse_footer_trailer_v0_9_t`:
   * `footer_length` (`int64`): Offset distance to footer start.
   * `spec_version` (`int32`): Packed specification version (`0x0009` for v0.9.0).
   * `footer_magic` (`int32`): Magic identifier `0x494D5053` (`IMPS`).
3. **Cryptographic Attestation**: Snapshots can embed a SHA-256 payload digest signed with an enterprise private key and certificate chain for SLSA Level 3 provenance verification.

---

## 8. Loading from a Manifest (Java 21 LTS FFM Runtime)

Pass `manifest.yaml` to `BinarySnapshotLoader.loadFromManifest(Path, Arena)`. The loader parses the configuration, memory-maps all backing `.imps` tablespaces into off-heap memory, and returns a unified `LoadedSnapshot` facade.

```java
package org.impulsegraph.example;

import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.RelationSnapshot;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;

public class ManifestLoaderExample {

    public static void main(String[] args) throws Exception {
        Path manifestPath = Path.of("datasets/enterprise/manifest.yaml");

        // Use Java 21 LTS Arena to manage off-heap memory-mapped tablespaces
        try (Arena arena = Arena.ofShared()) {
            
            // Memory-map all underlying tablespaces into a composite snapshot
            BinarySnapshotLoader.LoadedSnapshot loadedSnapshot = 
                    BinarySnapshotLoader.loadFromManifest(manifestPath, arena);
            
            ImpulseGraphSnapshot graph = loadedSnapshot.graph();
            System.out.println("Loaded composite graph: " + manifestPath);
            System.out.println("Active relations: " + graph.getRelationNames());

            // 1. Query physical relation backed by transactions_2025 tablespace
            RelationSnapshot purchased = (RelationSnapshot) graph.getRelationSnapshot("PURCHASED");
            if (purchased != null) {
                System.out.printf("PURCHASED: %d nodes, %d edges%n", 
                        purchased.getNodeCount(), purchased.getEdgeCount());
                
                // Inspect outgoing edges for dense User ID 0
                int degree = purchased.getDegree(0);
                int[] productTargets = purchased.getTargets(0);
                System.out.printf("User 0 purchased %d products: %s%n", 
                        degree, java.util.Arrays.toString(productTargets));
            }

            // 2. Domain-bound traversal starting from User domain
            var reachable = graph.domain("User").traverse(0);
            System.out.println("Domain traversal initialized: " + reachable);

            // 3. Inspect snapshot metadata and integrity footer
            String generator = loadedSnapshot.getMetadata("generator");
            String checksum = loadedSnapshot.getSha256Checksum();
            System.out.println("Snapshot Generator: " + generator);
            System.out.println("SHA-256 Checksum: " + checksum);
        }
        // All off-heap mapped memory segments are unmapped deterministically on Arena close
    }
}
```

