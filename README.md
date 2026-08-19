# Impulse Graph Engine — Java 25 FFM Core (`impulse-graph-java`)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A high-performance, zero-copy, off-heap **Java 25 FFM** graph engine and Virtual Machine interpreter for the **Impulse Graph Engine**.

It acts as the **"Apache Arrow for Graph Analytics"** on the JVM, pairing an immutable `.imps` C-ABI binary snapshot format with a pure Java 25 register-based Virtual Machine (`ImpulseVM`).

---

## 🚀 Key Architectural Properties

- **Zero Garbage Collection (GC) Overhead**: Memory-maps `.imps` v0.9.0 binary snapshot files off-heap via `java.lang.foreign.MemorySegment`.
- **Zero External Runtime Dependencies**: All core modules maintain **strictly 0 third-party runtime dependencies** (`java.base`, `jdk.incubator.vector`, FFM).
- **SIMD Vector API Acceleration**: Vectorizes graph traversal steps across unrolled AVX-512 and ARM Neon registers using `jdk.incubator.vector`.
- **Per-Domain Dense ID Independence ($0 \dots N_d-1$)**: Strict per-domain ID spaces with explicit domain anchoring.
- **Kleisli Frontier Traversal Pipeline**: Monadic frontier propagation $\langle D, S \rangle \xrightarrow{R} \langle D', S' \rangle$ with monoidic path reduction (`OR`, `MIN`, `MAX`, `SUM`).

---

## 📦 Modular Architecture

| Module | Scope & Role | Dependencies |
| :--- | :--- | :--- |
| **`impulse-spec`** | Binary snapshot layout constants (Page 0, 128-byte hardware alignment, Section 2 string offsets). | **0** |
| **`impulse-api`** | High-level contracts: `ImpulseGraphSnapshot`, `DomainView`, `Traversal`, `ImpulseStatement`, `RowReader`. | **0** |
| **`impulse-storage`** | Off-heap snapshot loader (`BinarySnapshotLoader`), `GraphSnapshot`, `RelationSnapshot`, CSR/CSC/COO accessors, snapshot builder. | **0** |
| **`impulse-compiler`** | ImpScheme S-Expression AST, CEL optimizer, 7-stage optimization passes, `impOps` bytecode emitter. | **0** |
| **`impulse-vm`** | Register VM (`R0`..`R63`), Java 25 Vector API AVX-512 SIMD handlers (`VmHandlers`), `MethodHandle` JIT combinators, and Statement runner. | **0** |

---

## ⚡ Quickstart

### 1. Prerequisites & Maven Coordinates
Java 25 with preview features and Vector API enabled:
```xml
<dependencies>
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-api</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-storage</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-vm</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

JVM Runtime Flag:
```bash
--enable-preview --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
```

### 2. Loading a Snapshot
```java
try (Arena arena = Arena.ofShared()) {
    var loaded = BinarySnapshotLoader.loadSnapshot(Path.of("hetionet.imps"), arena);
    ImpulseGraphSnapshot snap = loaded.getGraph();
    
    // Ready for sub-microsecond queries
}
```

### 3. Querying the Graph (Fluent Traversal API)
```java
var userDomain = snap.domain("User");

// 1. Domain Key <-> Internal ID Resolution
long denseId = userDomain.toDenseId("usr_alice"); // 0L
String key = userDomain.toKey(0); // "usr_alice"

// 2. Single-Node Query: find friends of "usr_alice"
List<String> friends = userDomain.fromKey("usr_alice").out("knows").toKeyList(); // ["usr_bob", "usr_charlie"]

// 3. Union of Connections from Multiple Starting Nodes
Set<String> allFriends = userDomain.fromKeys("usr_alice", "usr_bob").out("knows").toKeySet();

// 4. Mutual (Shared) Friends via BitSet Intersection
ImpulseBitSet aliceFriends = userDomain.fromKey("usr_alice").out("knows").toBitSet();
ImpulseBitSet bobFriends   = userDomain.fromKey("usr_bob").out("knows").toBitSet();
aliceFriends.and(bobFriends); // in-place AND
List<String> mutual = userDomain.from(aliceFriends).toKeyList();

// 5. Edge Attribute Filtering (e.g. Time Windows)
Set<String> recent = userDomain.fromKey("usr_alice")
    .out("TRANSACTED", "edge.timestamp >= 1700000000 && edge.timestamp <= 1710000000")
    .toKeySet();
```

### 4. Parameterized Cypher Queries (`ImpulseStatement`)
```java
// OpenCypher query with automatic set deduplication
try (ImpulseStatement stmt = snap.prepare("MATCH (u:User)-[:knows]->(f:User) WHERE u.id = $id RETURN f")) {
    stmt.bindNode("$id", 0);
    try (RowReader rows = stmt.execute()) {
        while (rows.next()) {
            System.out.println("Friend Node ID: " + rows.getNodeId(0));
        }
    }
}
```

---

## 📚 Documentation

- [**Quickstart Guide**](docs/GETTING_STARTED.md) — Loading snapshots, basic traversals, filtering, and prepared statements.
- [**Advanced Querying Guide**](docs/ADVANCED_QUERYING.md) — Fixed-point loops (`repeatUntilStable`), monoidic reductions, state projections, and BitSet algebra.
- [**Compiler Architecture**](docs/COMPILER_ARCHITECTURE.md) — IR passes, optimization pipeline, and bytecode generation.

---

## 🛠️ Build & Test Instructions

```bash
mvn clean test
```

---

## 📄 License

Apache License 2.0. See [LICENSE](LICENSE) for details.
