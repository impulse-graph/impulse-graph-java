# Quickstart Guide — Impulse Graph Engine (Java)

A practical guide for loading immutable binary snapshot files (`.imps`), querying connections across entity types, and executing parameterized graph queries in Java.

---

## 1. Installation & Setup

> [!TIP]
> **Build from Source Recommendation**:
> While Impulse Graph is in snapshot development, build the Java modules from source and install them directly into your local Maven cache (`~/.m2`):
> ```bash
> cd ~/impulse/impulse-graph-java
> mvn clean install -DskipTests
> ```

### 1.1 Maven Coordinates (`pom.xml`)
Add the core engine modules to your `pom.xml`:

```xml
<dependencies>
    <!-- Public API -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-api</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>

    <!-- Storage Layer & Snapshot Builder -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-storage</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>

    <!-- Compute Engine & Query Interpreter -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-vm</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

### 1.2 Required JVM Arguments
Configure your runtime and build plugins with standard Java 25 preview and vector access flags:

```bash
--enable-preview --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
```

In your `pom.xml`:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.2.5</version>
    <configuration>
        <argLine>--enable-preview --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED</argLine>
    </configuration>
</plugin>
```

---

## 2. Loading a Binary Snapshot (`.imps`)

> [!NOTE]
> **Creating Snapshots**:
> You can create binary snapshots directly from code using the Java API (see [Section 5](#5-building-snapshots-from-code)), or generate them from CSV, TSV, and Parquet files using the CLI utilities in [`impulse-graph-tooling`](file:///Users/jesse/impulse/impulse-graph-tooling).

To load and query a snapshot file:

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;

public class LoadSnapshotExample {
    public static void main(String[] args) {
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

---

## 3. Querying Connections (Fluent Traversal API)

All queries start from a specific **node type / domain** (e.g. `User`, `Product`, `Disease`).

Within a domain context, you can:
1. **Translate between external keys and internal dense IDs** (`toDenseId` / `toKey`).
2. **Filter** candidate nodes with `.filter(...)`.
3. **Walk edges** to connected node types with `.out("relationName")`.
4. **Collect results** into lists, sets, or key collections (`.toKeyList()`, `.toList()`, `.toSet()`, `.count()`).

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

You can execute declarative openCypher graph queries using `snap.prepare(...)`. Execution uses a familiar cursor model similar to JDBC or SQLite:

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

To programmatically build and save a new `.imps` snapshot file with custom domains, entity keys, and relations:

```java
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

            // Build snapshot with domain metadata and business keys
            byte[] snapshotBytes = new DefaultSnapshotBuilder()
                    .withDomain(0, "User", (byte) 1, 4)
                    .withDomainKeys("User", List.of("usr_alice", "usr_bob", "usr_charlie", "usr_dave"))
                    .build(new BinarySnapshotLoader.DefaultLoadedSnapshot(
                            BinarySnapshotLoader.SNAPSHOT_MAGIC, (short) 9, graph, Map.of(), Map.of(), Map.of(), Map.of()
                    ));

            Files.write(Path.of("my_graph.imps"), snapshotBytes);
            System.out.println("Saved snapshot (" + snapshotBytes.length + " bytes)");
        }
    }
}
```
