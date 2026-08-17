# ImpulseGraph Schema Definition

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

This document defines the YAML schema used to generate strongly-typed Java classes, ID mappers, and binary snapshot layouts for the ImpulseGraph Engine.

## Purpose
The schema acts as the source of truth for the graph's structure. Tooling (see [Tooling Organization](tooling-org.md)) consumes this file to:
1. Generate **Type-Safe POJOs** for Nodes and Relations.
2. Configure **Memory-Mapped Layouts** (CSR/CSC) for the binary snapshot.
3. Generate **Fluent Query Builders** tailored to the specific domain (see [Query Context & Execution](query-context.md)).
4. Automate **ID Mapping** between external keys (UUID, String) and internal dense IDs.

---

## Top-Level Attributes

| Attribute | Type | Description |
| :--- | :--- | :--- |
| `graphName` | string | Unique identifier for the graph. Used to prefix generated classes and manage multiple snapshots in the same JVM. |
| `version` | string | Schema version (e.g., `1.0`). |
| `package` | string | The Java package where generated code will be placed. |

---

## Nodes
Nodes represent the entities in your graph.

```yaml
nodes:
  User:
    key: uuid
    denseId: int32
    attributes:
      name: { type: string, length: 35, nullable: false }
```

### Node Properties
*   **`key`**: The external identifier type.
    *   *Allowed values:* `uuid`, `string`, `int64`, `bytes`.
*   **`denseId`**: The internal bit-width for node addressing.
    *   *Allowed values:* `int16` (max ~65K nodes), `int32` (max ~2B nodes), `int64`.
*   **`attributes`**: A map of properties stored with the node.

---

## Relations
Relations (edges) define the connections between nodes.

```yaml
relations:
  userFollowsUser:
    source: User
    target: User
    direction: [out, in]
    symmetric: false
    inverseAlias: followedBy
    cardinality: many_to_many
    attributes:
      followDate: { type: timestamp }
```

### Relation Properties
*   **`source` / `target`**: Names of node types defined in the `nodes` section.
*   **`direction`**: Determines the traversal capability and physical storage layout.
    *   `out`: Generates **CSR** (Compressed Sparse Row) indices. Supports `walkOut`.
    *   `in`: Generates **CSC** (Compressed Sparse Column) indices. Supports `walkIn`.
    *   *Allowed values:* `[out]`, `[in]`, or `[out, in]`.
*   **`symmetric`**: Boolean. If true, the relation is undirected (e.g., "Friendship"). Adding a connection between A and B automatically makes them neighbors in both directions. Requires `source` and `target` to be the same node type.
*   **`inverseAlias`**: A string used to name the reverse traversal in generated code (e.g., if the relation is `follows`, the inverse alias could be `followedBy`).
*   **`cardinality`**: Used for code-gen optimization and validation.
    *   *Allowed values:* `one_to_one`, `one_to_many`, `many_to_one`, `many_to_many`.

---

## Attributes & Types
Attributes can be applied to both Nodes and Relations.

| Type | Description | Requirement |
| :--- | :--- | :--- |
| `string` | Fixed-length UTF-8 encoded string. | Requires `length` (in bytes). |
| `varstring` | Variable-length UTF-8 encoded string. | - |
| `varbytes` | Variable-length byte array (blob). | - |
| `bytes` | Raw byte array. | Requires `length` if fixed-width. |
| `uuid` | Convenience alias for `bytes` (length 16). | - |
| `int16` | 16-bit signed integer. | - |
| `int32` / `int64` | Signed integers. | - |
| `float32` / `float64` | Floating point numbers. | - |
| `timestamp` | 64-bit millisecond epoch. | - |
| `boolean` | 1-bit flag. | - |

### Attribute Constraints
*   **`length`**: Required for `string` and fixed-width `bytes` types to define memory offset. For `string`, this refers to the **maximum number of UTF-8 bytes**, not characters.
*   **Vector/Array Support**: Any primitive type can be made into a vector by adding `[size]` to the type name (e.g., `float32[768]`). This is stored as a contiguous block in the binary layout.
*   **`nullable`**: Boolean. If false, the generator may use primitive types instead of wrappers.
*   **`indexed`**: Boolean. If true, generates lookup indices for this attribute.

---

## String Encoding & Marshaling

ImpulseGraph mandates **UTF-8** encoding for all `string` and `varstring` types.

### Marshaling Implications:
1.  **Java Strings**: Java uses UTF-16 internally. When writing to the graph, strings are encoded to UTF-8 bytes. When reading, they are decoded back to Java's UTF-16 representation.
2.  **Length vs. Characters**: The `length` attribute for `string` defines the fixed allocation in the binary snapshot. Since UTF-8 is a variable-width encoding, a `length: 10` field can hold 10 ASCII characters, but as few as 2-3 complex Unicode characters (e.g., Emojis or certain Asian scripts).
3.  **Truncation**: If a Java string exceeds the allocated `length` in UTF-8 bytes, the tooling/generator will truncate the string or throw an error depending on the configuration.
4.  **Zero-Copy Reading**: While the engine uses zero-copy memory mapping, accessing a `String` property in Java typically involves a decoding step into a new Java `String` object. For extreme performance where even this allocation is a bottleneck, use `bytes` or `varbytes` and work directly with the `MemorySegment`.

---

## Full Example

```yaml
graphName: SocialGraph
version: 1.0
package: org.impulsegraph.example

nodes:
  User:
    key: uuid
    denseId: int32
    attributes:
      username: { type: string, length: 15, nullable: false, indexed: true }
      email: { type: varstring, nullable: false }
      embedding: { type: float32[768] }
      publicKey: { type: bytes, length: 32 }
  Post:
    key: int64
    denseId: int64
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
    attributes:
      since: { type: timestamp }
  userFriendsWithUser:
    source: User
    target: User
    direction: [out, in]
    symmetric: true
    cardinality: many_to_many
  userPostsPost:
    source: User
    target: Post
    direction: [out]
    cardinality: one_to_many
```
