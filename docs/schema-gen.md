# Impulse Graph Engine: Schema & Manifest Definition

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

This document defines the declarative YAML schema and manifest format used across the **Impulse Graph Engine** (`impulse-graph`). The schema acts as the single source of truth for generating strongly-typed Java query builders, configuring zero-copy memory-mapped binary snapshot layouts ([`.imps` v0.9.0](file:///Users/jesse/impulse/impulse-graph-spec/docs/FORMAT_SPECIFICATION.md)), defining multi-tablespace composite graph deployments, and binding bi-directional external key mappers.

## Purpose & Tooling Ecosystem
The schema/manifest file (typically `schema.yaml` or `manifest.yaml`) is consumed across the Impulse Graph tooling suite:
1. **Strongly-Typed Query Builders (`impulse-codegen`, `impulse-maven-plugin`)**: Generates compile-time type-checked Java query builders ([`TypedQueryBuilder`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/schema/TypedQueryBuilder.java)) with IDE auto-complete for traversal steps and attribute filtering (see [Query Context & Execution](query-context.md)).
2. **Binary Snapshot Serialization (`impulse-builder`)**: Configures memory-mapped layouts for [`SnapshotBuilder`](file:///Users/jesse/impulse/impulse-graph-java/impulse-builder/src/main/java/org/impulsegraph/builder/api/SnapshotBuilder.java), enforcing 128-byte hardware alignment, mandatory CSR forward indices, optional CSC reverse indices, and Structure of Arrays (SoA) attribute arrays.
3. **Multi-Tablespace Composite Loading (`impulse-storage`)**: Instructs [`BinarySnapshotLoader`](file:///Users/jesse/impulse/impulse-graph-java/impulse-storage/src/main/java/org/impulsegraph/storage/csr/BinarySnapshotLoader.java) to load and memory-map multi-file snapshot chunks into a unified [`ImpulseGraphSnapshot`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/ImpulseGraphSnapshot.java) facade (see [YAML Manifest Tablespace Configuration](YAML_CONFIGURATION.md)).
4. **Bi-Directional Key Resolution (`impulse-api`)**: Directs [`IdMapper`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/IdMapper.java) implementations (`UuidIdMapper`, `StringIdMapper`, `LongIdMapper`, `BytesIdMapper`) to map external business identifiers to internal 0-indexed dense IDs.

---

## Top-Level Configuration Attributes

| Attribute | Type | Requirement | Description |
| :--- | :--- | :--- | :--- |
| `graphName` | string | Required | Unique graph identifier. Used to prefix generated snapshot wrapper classes (`<GraphName>Snapshot`) and namespace catalog metadata. |
| `version` | string | Required | Schema and snapshot version string (e.g., `1.0` or `1.0.0`). |
| `package` | string | Optional | Target Java package for generated query builders and snapshot classes (default: `org.impulsegraph.generated`). |
| `tablespaces` | map | Optional | Declarative mapping of physical `.imps` tablespace chunk files for multi-file deployments. |
| `domains` | map | Required | Node domain catalog definitions (also accepts `nodes` for backwards compatibility). |
| `relations` | map | Required | Directed edge relation directory definitions connecting source and target domains. |
| `virtual_relations` | map | Optional | Super-relations composing multiple constituent physical relations into a single queryable edge name. |

---

## Node Domains (`domains` / `nodes`)

Node Domains represent independent entity types in your graph.

```yaml
domains:
  User:
    key: uuid
    denseId: int32
    tablespace: core_space
    attributes:
      username: { type: string, length: 32, nullable: false, indexed: true }
      email: { type: varstring, nullable: false }
      reputation: { type: float32, nullable: false }
      embedding: { type: float32[128] }
```

### Universal Node Architecture (Per-Domain Dense ID Independence)
In accordance with the Impulse Graph Engine architecture:
* **Strict Per-Domain Dense ID Independence ($0 \dots N_d-1$)**: In Impulse Graph, **there is NO global flattened or synthetic unified node ID space**. Every Node Domain (e.g., `User`, `Post`, `Group`) owns its own completely independent 0-indexed dense integer space $0 \dots N_d-1$. Dense node ID `1` in domain `User` is fundamentally distinct and unrelated to dense node ID `1` in `Post`.
* **Domain-Bound Traversal Entrypoints**: Traversal queries bind an explicit domain context in multi-domain graphs (`snapshot.domain("User").traverse([0, 1, 2])`). Passing a raw integer array without a domain context on a multi-domain snapshot is an invalid/ambiguous query error.

### Domain Configuration Properties
* **`key`**: The external business identifier type.
  * *Allowed values:* `uuid` (`java.util.UUID`), `string` (`java.lang.String`), `int64` (`long`), `bytes` (`byte[]`).
  * *Resolution:* Forward lookups (dense ID $\to$ external key) are performed in $O(1)$ time via off-heap attribute arrays. Reverse lookups (external key $\to$ dense ID) are resolved via [`IdMapper<K>`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/IdMapper.java) implementations or Section 4 Key Catalogs using Minimal Perfect Hash Functions (MPHF) or binary search.
* **`denseId`** (or **`idWidth`**): The internal physical primitive integer width for node addressing.
  * *`int16`* / *`uint16`*: 16-bit integer (up to 65,536 nodes). Optimal for compact entity catalogs and reference domains.
  * *`int32`* / *`uint32`*: 32-bit integer (up to 4,294,967,296 nodes). Standard default for high-scale enterprise graphs.
  * *`int64`* / *`uint64`*: 64-bit integer. Designed for multi-billion/trillion-node hyperscale domains.
* **`tablespace`**: Optional tablespace name referencing a defined tablespace in `tablespaces:`. Specifies which physical `.imps` file backs this domain in modular composite deployments.
* **`attributes`**: A map of properties stored with the node in contiguous 128-byte aligned Structure of Arrays (SoA) layout.

---

## Relations & Graph Topology

Relations (directed edges) connect source node domains to target node domains.

```yaml
relations:
  userFollowsUser:
    source: User
    target: User
    direction: [out, in]
    symmetric: false
    inverseAlias: followedBy
    cardinality: many_to_many
    tablespace: core_space
    attributes:
      affinityScore: { type: float32, nullable: false }
      followTimestamp: { type: timestamp_ms }
```

### Relation Configuration Properties
* **`source` / `target`**: The source and target node domain names defined in `domains:`. Every relation descriptor in the `.imps` binary snapshot explicitly encodes `SrcDomainID` and `TgtDomainID`, mapping source dense IDs directly to target dense IDs.
* **`direction`**: Controls physical graph topology indices:
  * `out`: Generates **CSR** (Compressed Sparse Row) indices. Edges are sorted primarily by Source ID. Mandatory for forward edge traversals (`builder.walkEdge(...)` and generated `walk<Relation>()` methods).
  * `in`: Generates **CSC** (Compressed Sparse Column) transpose indices. Edges are sorted primarily by Target ID. Enables instant incoming edge traversals without scanning the full graph.
  * *Allowed values:* `[out]`, `[in]`, or `[out, in]`.
* **`cardinality`**: Multiplicity layout optimization flag conforming to C-ABI Binary Snapshot v0.9.0 Section 3.3.3:
  * `many_to_many` (`IMP_REL_CARDINALITY_M_N`): Standard CSR/CSC layout (`offsets` + `columnIndices`). Target $k$ of node $i$ is read at `csrColumnIndices[csrRowOffsets[i] + k]`.
  * `many_to_one` (`IMP_REL_CARDINALITY_M_1`): Flat target array with `CsrRowOffBytes = 0`. Each source node has at most 1 target; target for node $i$ is accessed directly at `csrColumnIndices[i]` in $O(1)$ time.
  * `one_to_many` (`IMP_REL_CARDINALITY_1_M`): Forward standard CSR layout; reverse CSC transpose omits `cscRowOffsets` and stores a flat source ID array.
  * `one_to_one` (`IMP_REL_CARDINALITY_1_1`): Flat bi-directional direct arrays. Both forward and reverse traversals execute as $O(1)$ single-hop dereferences.
* **`symmetric`**: Boolean. If true, the relation is undirected (e.g., mutual friendship). Requires `source` and `target` to be identical node domains.
* **`inverseAlias`**: Method name used to generate reverse traversal methods in strongly-typed query builders (e.g., if relation is `follows`, reverse traversal becomes `walkFollowedBy()`).
* **`tablespace`**: Optional tablespace name referencing a defined tablespace in `tablespaces:`. Specifies which physical `.imps` file backs this relation in modular multi-file deployments.
* **`attributes`**: Map of edge attributes (e.g., weights, timestamps, status flags) stored in Structure of Arrays (SoA) layout.

### Virtual Super-Relations (`virtual_relations`)
A **Virtual Relation** logically aggregates multiple physical relations into a single queryable relation name without duplicating physical edge data or allocating heap objects.

```yaml
virtual_relations:
  userInteractions:
    components:
      - userLikesPost
      - userCommentsOnPost
      - userSharesPost
```

In the `.imps` C-ABI binary layout, virtual relations store constituent physical relation IDs inline in the relation directory table (for $1 \le C \le 10$) or via an off-heap overflow array (up to 65,536 components). Traversing a virtual relation expands across its constituent physical relations and performs monoidic set union over intermediate bitsets with zero allocation overhead.

---

## Attributes & Data Types

Attributes can be attached to both Node Domains and Relations. They are stored off-heap in contiguous **Structure of Arrays (SoA)** format, with strict 128-byte hardware alignment for AVX-512 SIMD and Java Vector API acceleration.

### Supported Primitive Data Types

| Type Identifier | C-ABI Type Code | Byte Size | Description | Requirement |
| :--- | :--- | :--- | :--- | :--- |
| `int8` / `i8` | `0x01` (`INT8`) | 1 Byte | Signed/unsigned 8-bit integer. | - |
| `int16` / `i16` | `0x02` (`INT16`) | 2 Bytes | Signed/unsigned 16-bit integer. | - |
| `int32` / `i32` | `0x03` (`INT32`) | 4 Bytes | Signed 32-bit integer. | - |
| `int64` / `i64` | `0x04` (`INT64`) | 8 Bytes | Signed 64-bit integer. | - |
| `float16` / `f16` | `0x05` (`FLOAT16`) | 2 Bytes | 16-bit half-precision float (bfloat16 / fp16). | - |
| `float32` / `f32` | `0x06` (`FLOAT32`) | 4 Bytes | IEEE 754 32-bit float (standard for edge weights and vector embeddings). | - |
| `float64` / `f64` | `0x07` (`FLOAT64`) | 8 Bytes | IEEE 754 64-bit double-precision float. | - |
| `timestamp_ms` / `timestamp` | `0x08` (`TIMESTAMP_MS`) | 8 Bytes | 64-bit millisecond Unix epoch. | - |
| `timestamp_ns` | `0x09` (`TIMESTAMP_NS`) | 8 Bytes | 64-bit nanosecond Unix epoch. | - |
| `bytes` / `fixed_bytes` | `0x0A` (`FIXED_BYTES`) | $\text{dim}$ Bytes | Fixed-width raw byte array (e.g., 16 bytes for UUID, 32 bytes for SHA-256). | Requires `length` / dimension. |
| `varstring` / `string` | `0x0B` (`VAR_STRING`) | Variable | Variable-length UTF-8 encoded text string. | Stored via `uint32_t offsets[]` + data pool. |
| `varbytes` | `0x0C` (`VAR_BYTES`) | Variable | Variable-length binary byte blob. | Stored via `uint32_t offsets[]` + data pool. |
| `interval_sec_32` | `0x0D` (`INTERVAL_SEC_32`) | 8 Bytes | Packed temporal interval (`uint32_t start_sec`, `uint32_t duration_sec`). | - |
| `interval_ms_64` | `0x0E` (`INTERVAL_MS_64`) | 16 Bytes | Packed temporal interval (`uint64_t start_ms`, `uint64_t duration_ms`). | - |
| `boolean` | `0x01` (`INT8`) | 1 Byte | Boolean flag (`0` = false, `1` = true). | - |

### Attribute Constraints & Layout Modifiers
* **`length`**: Required for fixed-width `bytes` or fixed-length `string` to set static byte allocation per entity.
* **Vector / Array Support**: Any primitive numeric type can be declared as a vector by appending `[dimension]` (e.g., `float32[128]`, `float32[768]`). Vectors are stored as contiguous fixed-width memory slices in SoA layout, aligned to 128-byte boundaries for zero-copy SIMD processing and GPU tensor consumption.
* **`nullable`**: Boolean flag controlling nullability encoding:
  * `nullable: false` (Default): Contiguous raw values with **zero memory overhead** for null checking.
  * `nullable: true`: Bit 7 (`0x80`) is set in the C-ABI `type_code`. A 128-byte aligned **Bitwise Validity Bitmap** (`uint64_t validity_bitmap[(K + 63) / 64]`), strictly padded with `0x00` to the nearest 128-byte boundary, is placed before the payload array ($1\text{ bit per entity}$, Bit $i = 1 \rightarrow$ valid, Bit $i = 0 \rightarrow$ null).
* **`indexed`**: Boolean. If true, generates a Section 2.6 Secondary Index entry (e.g., Minimal Perfect Hash Function, Permutation, or BitSet index) for accelerated lookups.

---

## String Encoding & Memory-Mapped Semantics

Impulse Graph mandates **UTF-8** encoding across all `string`, `varstring`, and string pool structures.

### Marshaling & Zero-Copy Execution:
1. **Variable-Length Strings (`varstring`)**: Variable-length strings are stored in Section 2 (shared string pool) or Section 5 (attribute payload blobs) using a 128-byte aligned `uint32_t offsets[K + 1]` array and contiguous UTF-8 byte stream. String slice $i$ is extracted from `data_offset + offsets[i]` with byte length `offsets[i + 1] - offsets[i]`, eliminating fixed-size truncation.
2. **Fixed-Length Strings (`string`)**: For high-frequency fixed-width codes (e.g., country codes, status codes), fixed `length` strings allocate exact byte slices. Strings are padded with `0x00` if shorter than `length`.
3. **Zero-Copy JVM Access**: Traversal and filter opcodes inside `ImpulseVM` evaluate string equality and prefix matches directly against off-heap `MemorySegment` bytes without allocating Java `String` objects on the JVM heap.

---

## Full Specification Example (`manifest.yaml` / `schema.yaml`)

```yaml
graphName: SocialGraph
version: "1.0.0"
package: org.impulsegraph.example

tablespaces:
  core_space:
    file: "social_core.imps"
    description: "Core identity and user relation graph"
    mode: "read-only"
  posts_space:
    file: "social_posts.imps"
    description: "Posts and content engagement graph"
    mode: "read-only"

domains:
  User:
    key: uuid
    denseId: int32
    tablespace: core_space
    attributes:
      username: { type: string, length: 32, nullable: false, indexed: true }
      email: { type: varstring, nullable: false }
      reputation: { type: float32, nullable: false }
      embedding: { type: float32[128] }
      publicKey: { type: bytes, length: 32 }
  Post:
    key: int64
    denseId: int32
    tablespace: posts_space
    attributes:
      title: { type: varstring, nullable: false }
      attachment: { type: varbytes }

relations:
  userFollowsUser:
    source: User
    target: User
    direction: [out, in]
    inverseAlias: followedBy
    cardinality: many_to_many
    tablespace: core_space
    attributes:
      affinityScore: { type: float32, nullable: false }
      since: { type: timestamp_ms }
  userFriendsWithUser:
    source: User
    target: User
    direction: [out, in]
    symmetric: true
    cardinality: many_to_many
    tablespace: core_space
  userPostsPost:
    source: User
    target: Post
    direction: [out]
    cardinality: one_to_many
    tablespace: posts_space
  userLikesPost:
    source: User
    target: Post
    direction: [out, in]
    cardinality: many_to_many
    tablespace: posts_space

virtual_relations:
  userEngagements:
    components:
      - userPostsPost
      - userLikesPost
```

---

## Code Generation & Tooling Workflow

### 1. Maven Build Plugin Integration (`impulse-maven-plugin`)
Add the plugin to your `pom.xml` to automatically generate strongly-typed query builders during the `generate-sources` lifecycle phase:

```xml
<plugin>
    <groupId>org.impulsegraph</groupId>
    <artifactId>impulse-maven-plugin</artifactId>
    <version>0.9.0</version>
    <executions>
        <execution>
            <goals>
                <goal>generate</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <schemaFile>${project.basedir}/src/main/resources/schema.yaml</schemaFile>
        <outputDirectory>${project.build.directory}/generated-sources/impulse</outputDirectory>
        <packageName>org.impulsegraph.example</packageName>
    </configuration>
</plugin>
```

### 2. Standalone CodeGen CLI Execution
Generate source classes programmatically or via CLI using [`GeneratorMain`](file:///Users/jesse/impulse/impulse-graph-java/impulse-codegen/src/main/java/org/impulsegraph/codegen/GeneratorMain.java):

```bash
java -cp "impulse-codegen.jar:..." org.impulsegraph.codegen.GeneratorMain \
    src/main/resources/schema.yaml \
    target/generated-sources/impulse \
    org.impulsegraph.example
```

The generator produces:
* `<Domain>QueryBuilder.java` (e.g., `UserQueryBuilder`, `PostQueryBuilder`) extending [`TypedQueryBuilder`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/schema/TypedQueryBuilder.java).
* `<GraphName>Snapshot.java` (e.g., `SocialGraphSnapshot.java`).

---

## Application Query Execution & ID Mapping

### Generated Query Builder Anatomy
The code generator produces strongly-typed domain transition methods matching relation names:

```java
package org.impulsegraph.example;

import org.impulsegraph.api.ArgType;
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.schema.TypedQueryBuilder;

public class UserQueryBuilder<R> extends TypedQueryBuilder<Object, R> {

    public UserQueryBuilder(ImpulseQueryBuilder<R> builder) {
        super(builder);
    }

    public static UserQueryBuilder<Object> from(Object input) {
        ArgType argType = (input instanceof Number) ? ArgType.SINGLE_NODE : ArgType.ROARING_BITSET;
        return new UserQueryBuilder<Object>("User", argType);
    }

    // Strongly-typed relation traversal methods transitioning between domains
    public UserQueryBuilder<R> walkUserFollowsUser() {
        this.builder.walkEdge("userFollowsUser");
        return new UserQueryBuilder<R>(this.builder);
    }

    public PostQueryBuilder<R> walkUserPostsPost() {
        this.builder.walkEdge("userPostsPost");
        return new PostQueryBuilder<R>(this.builder);
    }

    // Vectorized attribute filter methods
    public UserQueryBuilder<R> filterReputation(String op, float val) {
        this.builder.filter("node.reputation " + op + " " + val);
        return this;
    }
}
```

### End-to-End Query Execution
The following example demonstrates loading a multi-tablespace composite snapshot, resolving external UUID business keys to internal dense node IDs, and executing a compiled traversal via `ImpulseVM`:

```java
import org.impulsegraph.api.*;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.example.UserQueryBuilder;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.UUID;

public class SocialGraphApplication {

    public static void main(String[] args) throws Exception {
        Path manifestPath = Path.of("manifest.yaml");

        try (Arena arena = Arena.ofShared()) {
            // 1. Zero-copy load composite graph from manifest
            BinarySnapshotLoader.LoadedSnapshot loaded = 
                BinarySnapshotLoader.loadFromManifest(manifestPath, arena);
            ImpulseGraphSnapshot snapshot = loaded.graph();

            // 2. Map external UUID to 0-indexed dense node ID
            IdMapper<UUID> userMapper = new UuidIdMapper("User");
            UUID externalUserId = UUID.fromString("f47ac10b-58cc-4372-a567-0e02b2c3d479");
            long userDenseId = userMapper.getOrAssignId(externalUserId);

            // 3. Construct compile-time type-checked traversal query
            ImpulseGraphQuery<ImpulseBitSet> followersQuery = UserQueryBuilder.from(userDenseId)
                .filterReputation(">", 100.0f)
                .walkUserFollowsUser()
                .collectRoaringBitset();

            // 4. Zero-allocation execution via ImpulseVM
            ImpulseBitSet activeFollowers = followersQuery.execute(snapshot, userDenseId);
            System.out.println("Active followers count: " + activeFollowers.cardinality());

            // 5. Reverse resolve dense IDs back to external UUID keys
            activeFollowers.forEach(followerDenseId -> {
                UUID followerUuid = userMapper.getExternalKey(followerDenseId);
                System.out.println("Follower UUID: " + followerUuid);
            });
        }
    }
}
```
