# Quickstart Guide — Impulse Graph Engine (Java)

A practical guide for loading immutable binary snapshot files (`.imps`), querying connections across entity types, and executing parameterized graph queries in Java.

---

## 1. Installation & Setup

Impulse Graph is published to **Maven Central** under groupId `org.impulsegraph`.

### 1.1 Dependency Coordinates

#### Maven (`pom.xml`)
Add the engine modules to your `pom.xml`:

```xml
<dependencies>
    <!-- Public API & Domain Abstractions -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-api</artifactId>
        <version>0.9.0</version>
    </dependency>

    <!-- Storage Layer & Binary Snapshot Loader / Builder -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-storage</artifactId>
        <version>0.9.0</version>
    </dependency>

    <!-- Compute VM & Execution Engine Provider -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-vm</artifactId>
        <version>0.9.0</version>
    </dependency>

    <!-- Query Compiler & openCypher Frontend (Optional, for declarative Cypher queries) -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-compiler</artifactId>
        <version>0.9.0</version>
    </dependency>
</dependencies>
```

#### Gradle (Groovy DSL — `build.gradle`)
```groovy
dependencies {
    implementation 'org.impulsegraph:impulse-api:0.9.0'
    implementation 'org.impulsegraph:impulse-storage:0.9.0'
    implementation 'org.impulsegraph:impulse-vm:0.9.0'
    implementation 'org.impulsegraph:impulse-compiler:0.9.0' // Optional for Cypher
}
```

#### Gradle (Kotlin DSL — `build.gradle.kts`)
```kotlin
dependencies {
    implementation("org.impulsegraph:impulse-api:0.9.0")
    implementation("org.impulsegraph:impulse-storage:0.9.0")
    implementation("org.impulsegraph:impulse-vm:0.9.0")
    implementation("org.impulsegraph:impulse-compiler:0.9.0") // Optional for Cypher
}
```

### 1.2 Dual Target Runtime JVM Flags (JDK 21 LTS & JDK 22+)
Impulse Graph supports both **Java 21 LTS** (via JEP 442 FFM preview) and **Java 22+ / Java 25 LTS / Java 26+** (standard finalized FFM in `java.base` with zero preview flags required):

| Java Runtime | Required JVM Flags | Description |
| :--- | :--- | :--- |
| **Java 22+ / Java 25 LTS / Java 26+** | `--add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | **Standard FFM** in `java.base`. No preview flag required. |
| **Java 21 LTS** | `--enable-preview --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED` | Enables JEP 442 FFM Preview 3 on Java 21 LTS. |

#### Maven Configuration (`pom.xml`)
Ensure your compiler and surefire plugins pass the preview and incubator flags:

```xml
<properties>
    <java.version>21</java.version>
    <maven.compiler.release>21</maven.compiler.release>
</properties>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>
                <release>${maven.compiler.release}</release>
                <compilerArgs>
                    <arg>--enable-preview</arg>
                    <arg>--add-modules</arg>
                    <arg>jdk.incubator.vector</arg>
                </compilerArgs>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.2.5</version>
            <configuration>
                <argLine>--enable-preview --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED</argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

#### Gradle Configuration (`build.gradle`)
```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType(JavaCompile).configureEach {
    options.compilerArgs += ["--enable-preview", "--add-modules", "jdk.incubator.vector"]
}

tasks.withType(JavaExec).configureEach {
    jvmArgs += ["--enable-preview", "--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED"]
}

tasks.withType(Test).configureEach {
    jvmArgs += ["--enable-preview", "--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED"]
}
```

---

## 2. Loading a Binary Snapshot (`.imps`)

> [!NOTE]
> **Generating Binary Snapshots**:
> To convert large datasets (CSV, TSV, Parquet) into zero-copy `.imps` binary snapshots, always use the official [`impulse-graph-tooling`](file:///Users/jesse/impulse/impulse-graph-tooling) CLI (`impulse build` or `impulse generate`). You can also programmatically build snapshots directly in Java via `DefaultSnapshotBuilder` (see [Section 5](#5-building-snapshots-from-code)).

To load and query an immutable binary snapshot file:

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;

public class LoadSnapshotExample {
    public static void main(String[] args) throws Exception {
        // Manage off-heap lifecycle with an Arena
        try (Arena arena = Arena.ofShared()) {
            Path snapshotPath = Path.of("datasets/hetionet.imps");
            var loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);
            ImpulseGraphSnapshot snap = loaded.getGraph();

            System.out.println("Snapshot loaded: " + snap.getRelationCount() + " relations.");
            System.out.println("Memory size: " + (snap.getOffHeapMemorySizeBytes() / (1024 * 1024)) + " MB");
        }
    }
}
```

> [!TIP]
> **Convenience Loader for Tools & Scripts**:
> For command-line utilities and test scripts with GC-managed off-heap arenas, you can load directly via:
> ```java
> ImpulseGraphSnapshot snap = ImpulseGraphSnapshot.load(Path.of("datasets/hetionet.imps"));
> ```
> For long-running server processes, always manage the `Arena` explicitly (e.g. `Arena.ofShared()`) to ensure deterministic off-heap deallocation.

---

## 3. Querying Connections (Fluent Traversal API)

In Impulse Graph Engine, there is **no global flattened or synthetic unified node ID space** (Rule 3.14). Every Node Domain (e.g. `User`, `Product`, `Disease`) maintains its own independent 0-indexed dense integer space $0 \dots N_d-1$. Dense node ID `0` in domain `User` is fundamentally distinct from dense node ID `0` in `Product`.

All graph traversals follow the **Kleisli Frontier Propagation Model** (Rule 3.15), starting from an anchored domain context:
$$\text{Pipeline} = \text{Anchor}(D_0, F_0, S_0) \gg= T_1 \gg= T_2 \dots \gg= \text{Collect}()$$

Within a domain context, you can:
1. **Translate between external keys and internal dense IDs** (`toDenseId` / `toKey`).
2. **Seed frontiers** from external business keys (`.fromKey(...)`, `.fromKeys(...)`), dense IDs (`.from(...)`), or bitsets.
3. **Filter candidate nodes** via CEL predicates with `.filter(...)`.
4. **Walk edges across domains** with `.out("relationName")` or `.in("relationName")` (transitions the traversal context to the relation's target/source domain).
5. **Collect results** into lists, sets, or zero-copy off-heap bitsets (`.toKeyList()`, `.toKeySet()`, `.toList()`, `.toSet()`, `.toBitSet()`, `.count()`).

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;

import java.util.List;
import java.util.Set;

public class TraversalExamples {

    public static void runQueries(ImpulseGraphSnapshot snap) {
        // --- 1. Working with Domain Keys vs. Internal IDs ---
        var userDomain = snap.domain("User");

        // External Business Key -> Internal Engine ID
        long aliceId = userDomain.toDenseId("usr_alice"); // e.g. 0L

        // Internal Engine ID -> External Business Key
        String aliceKey = userDomain.toKey(0); // "usr_alice"

        // --- 2. Single-Node Starting Point (1-Hop & Multi-Hop) ---
        // Find all friends of "usr_alice"
        List<String> friendKeys = userDomain.fromKey("usr_alice")
            .out("knows")
            .toKeyList(); // ["usr_bob", "usr_charlie"]

        // 2-Hop Traversal: User -> Product -> Category
        Set<String> categories = userDomain.fromKey("usr_alice")
            .out("PURCHASED")
            .out("IN_CATEGORY")
            .toKeySet();

        // --- 3. Union of Connections from Multiple Starting Nodes ---
        // Combines outgoing connections across multiple users (Union):
        Set<String> allFriends = userDomain.fromKeys("usr_alice", "usr_bob")
            .out("knows")
            .toKeySet();

        // --- 4. Finding Mutual (Shared) Friends via BitSet Intersection ---
        // Intersect friend BitSets to find friends shared by BOTH Alice and Bob:
        ImpulseBitSet aliceFriends = userDomain.fromKey("usr_alice").out("knows").toBitSet();
        ImpulseBitSet bobFriends   = userDomain.fromKey("usr_bob").out("knows").toBitSet();
        aliceFriends.and(bobFriends); // in-place bitwise AND intersection

        List<String> mutualFriends = userDomain.from(aliceFriends).toKeyList();

        // --- 5. Filtering Candidate Nodes ---
        // Filter nodes in the active domain before following connections:
        long adultPurchases = userDomain.fromKeys("usr_alice", "usr_bob")
            .filter("node.age >= 21")
            .out("PURCHASED")
            .count();

        // --- 6. Filtering Edge Attributes (e.g. Timestamps / Date Ranges) ---
        // Filter edges during traversal (e.g. transactions within a specific timestamp window):
        Set<String> recentMerchants = userDomain.fromKey("usr_alice")
            .out("TRANSACTED", "edge.timestamp >= 1700000000 && edge.timestamp <= 1710000000")
            .toKeySet();
    }
}
```

> [!TIP]
> When multiple paths reach the same target node, deduplication is handled automatically without extra configuration.

---

## 4. Parameterized Cypher Queries (`ImpulseStatement`)

You can execute declarative openCypher graph queries using `snap.prepare(...)`. Statements are compiled into ImpScheme AST and lowered to native `impOps` bytecode, executing with sub-microsecond latency across parameter re-bindings:

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;

public class StatementExample {

    public static void executeQuery(ImpulseGraphSnapshot snap) {
        // 1. Prepare parameterized openCypher query with RETURN clause
        String cypher = "MATCH (u:User)-[:knows]->(f:User) WHERE u.id = $userId RETURN f";
        
        try (ImpulseStatement stmt = snap.prepare(cypher)) {
            // 2. Bind parameter and execute
            stmt.bindNode("$userId", 0);

            try (RowReader rows = stmt.execute()) {
                System.out.println("Result Column: " + rows.getColumnName(0));
                while (rows.next()) {
                    long friendId = rows.getNodeId(0);
                    System.out.println("Found Friend Node ID: " + friendId);
                }
            }

            // 3. Re-bind to a different user without re-preparing the statement
            stmt.bindNode("$userId", 1);
            System.out.println("User 1 Friends Count: " + stmt.count());
        }
    }
}
```

> [!NOTE]
> **Automatic Set Deduplication vs. Standard Cypher**:
> Unlike standard openCypher implementations that preserve duplicate paths/multisets unless `RETURN DISTINCT` is specified, Impulse Graph evaluates traversals over unique node sets, automatically deduplicating reachable nodes in the target frontier.

---

## 5. Building Snapshots from Code

To programmatically build and save a new `.imps` snapshot file in Java:

### 5.1 Quick Snapshot Serialization (`writeSnapshotBytes`)
For simple graphs without custom domain catalogs or string keys, use `DefaultSnapshotBuilder.writeSnapshotBytes`:

```java
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class BuildSnapshotSimpleExample {

    public static void createSnapshot() throws Exception {
        try (Arena arena = Arena.ofShared()) {
            // Build relation: User -> Group (2 edges: 0 -> 10, 1 -> 10)
            int[] rowOffsets = new int[]{0, 1, 2};
            int[] colIndices = new int[]{10, 10};
            RelationSnapshot rel = new RelationSnapshot(arena, 2, 2, rowOffsets, colIndices);

            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("userToGroup", rel));

            // Serialize graph directly to .imps C-ABI binary format
            byte[] impsBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);
            Files.write(Path.of("target/sample_graph.imps"), impsBytes);
            System.out.printf("Saved binary snapshot (%d bytes).%n", impsBytes.length);
        }
    }
}
```

### 5.2 Multi-Domain Snapshot with Domain Catalogs & Business Keys
To configure explicit domain catalogs, external business keys, relation domain endpoints, and reverse CSC indices:

```java
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class BuildSnapshotExample {

    public static void createSnapshot() throws Exception {
        try (Arena arena = Arena.ofShared()) {
            // Define the Compressed Sparse Row (CSR) adjacency matrix:
            // - offsets array: starting index in targets for each node's edges (length = nodeCount + 1)
            // - targets array: destination node IDs for each edge (length = edgeCount)
            //
            // Node 0 (Alice)   -> [Node 1 (Bob), Node 2 (Charlie)]
            // Node 1 (Bob)     -> [Node 2 (Charlie), Node 3 (Dave)]
            // Node 2 (Charlie) -> [Node 3 (Dave)]
            // Node 3 (Dave)    -> []
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 4, 5, 5);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 2, 3, 3);

            RelationSnapshot knowsRel = new RelationSnapshot(arena, 4, 5, offsets, targets);
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("knows", knowsRel));

            // Build snapshot with domain catalog, business keys, and reverse CSC:
            // - withDomain(domainId, name, keyType, nodeCount)
            //   keyType: (byte) 11 (VAR_STRING), (byte) 3 (INT32), etc.
            // - withRelationDomain(relName, srcDomainId, tgtDomainId)
            byte[] snapshotBytes = new DefaultSnapshotBuilder()
                    .withDomain(0, "User", (byte) 11, 4L) // domainId: 0, name: "User", keyType: VAR_STRING, nodeCount: 4
                    .withRelationDomain("knows", 0, 0)   // srcDomainId: 0 (User), tgtDomainId: 0 (User)
                    .withDomainKeys("User", List.of("usr_alice", "usr_bob", "usr_charlie", "usr_dave"))
                    .withCsc(true)                       // Generate reverse transpose (CSC) index
                    .build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
                            BinarySnapshotLoader.SNAPSHOT_MAGIC, (short) 9, graph, Map.of(), Map.of(), Map.of(), Map.of()
                    ));

            Files.write(Path.of("my_graph.imps"), snapshotBytes);
            System.out.println("Saved snapshot (" + snapshotBytes.length + " bytes)");
        }
    }
}
```

### 5.3 Configuring Primitive Node ID Widths (16, 32, 64-Bit)
Each domain and relation independently configures its physical primitive integer representation width in the binary snapshot layout based on domain cardinality:
* `2` bytes (`uint16_t`): Up to 65,536 nodes — optimal for compact entity catalogs (32 nodes per 64-byte cache line).
* `4` bytes (`uint32_t`): Up to 4,294,967,296 nodes — standard enterprise default (16 nodes per 64-byte cache line).
* `8` bytes (`uint64_t`): Hyperscale domains (8 nodes per 64-byte cache line).

In the C-ABI binary specification (`ImpulseLayoutsV0_9`), `node_id_width` and `edge_index_width` are encoded in the 128-byte Relation Directory Entry. When building relations in Java, constructors on `RelationSnapshot` accept primitive widths (`nodeIdWidth`, `edgeIndexWidth`), and when generating from external datasets, `impulse-graph-tooling` sets these in the manifest schema.

---

## 6. Next Steps & Advanced Topics

For deeper architectural topics, complex query patterns, and advanced features, see:
- [**Advanced Querying Guide**](file:///Users/jesse/impulse/impulse-graph-java/docs/ADVANCED_QUERYING.md) — Fixed-point loops (`repeatUntilStable`), monoidic path reductions (MIN/MAX/SUM), state vector projections (`.project`), CEL parameter sweeps, and BitSet algebra.
- [**openCypher Dialect Reference**](file:///Users/jesse/impulse/impulse-graph-java/docs/OPENCYPHER_REFERENCE.md) — Supported Cypher grammar (`MATCH`, `WHERE`, `RETURN`), edge attribute filtering, and frontier set semantics.
- [**Memory & Performance Tuning Guide**](file:///Users/jesse/impulse/impulse-graph-java/docs/MEMORY_TUNING_GUIDE.md) — FFM `Arena` lifecycle management, zero-GC mechanics, primitive ID width selection, and Vector API JVM tuning flags.
- [**Compiler Architecture**](file:///Users/jesse/impulse/impulse-graph-java/docs/COMPILER_ARCHITECTURE.md) — Multi-pass optimization pipeline, AST transformations, and bytecode emission.
- [**Ingestion Strategies**](file:///Users/jesse/impulse/impulse-graph-java/docs/ingestion-strategies.md) — Streaming large-scale datasets directly to `.imps` files.
- [**Executable Java Code Samples**](file:///Users/jesse/impulse/impulse-graph-java/samples/src/main/java/org/impulsegraph/samples) — Complete runnable examples in `samples/` covering basic traversals, SIMD attribute filtering, compiled statements, and snapshot generation.
