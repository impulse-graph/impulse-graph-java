# Quickstart Guide — Impulse Graph Engine (Java 25 FFM)

A quick-start guide for loading zero-copy `.imps` binary snapshots, running SIMD-accelerated Kleisli frontier traversals, and executing SQLite-style parameterized prepared statements using **Java 25 Foreign Function & Memory (FFM)**.

---

## 1. Maven Dependencies & JVM Flags

### 1.1 Maven Coordinates (`pom.xml`)
Add the 3 core modules to your project:

```xml
<dependencies>
    <!-- Public API Contracts -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-api</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>

    <!-- Zero-Copy Storage Layer (FFM Mmap Loader & Snapshot Builder) -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-storage</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>

    <!-- ImpulseVM Compute Engine (SIMD Handlers & Statement Runner) -->
    <dependency>
        <groupId>org.impulsegraph</groupId>
        <artifactId>impulse-vm</artifactId>
        <version>0.9.0-SNAPSHOT</version>
    </dependency>
</dependencies>
```

### 1.2 Required JVM Arguments
Because Impulse Graph leverages Java 25 Foreign Function & Memory (FFM) and Vector API SIMD acceleration, configure your JVM runtime flags:

```bash
--enable-preview --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED
```

In your `pom.xml` build configuration:
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

Snapshots are mapped into off-heap memory via `java.lang.foreign.Arena` in **sub-millisecond cold start** with **zero heap allocation and zero GC pauses**:

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.storage.csr.BinarySnapshotLoader;

import java.lang.foreign.Arena;
import java.nio.file.Path;

public class LoadSnapshotExample {
    public static void main(String[] args) {
        // 1. Manage off-heap lifecycle with a shared Arena
        try (Arena arena = Arena.ofShared()) {
            
            // 2. Zero-copy memory-map snapshot file (< 1 ms cold start)
            Path snapshotPath = Path.of("datasets/hetionet.imps");
            var loaded = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);
            ImpulseGraphSnapshot snap = loaded.getGraph();

            System.out.println("Snapshot loaded: " + snap.getRelationCount() + " relations.");
            System.out.println("Memory footprint: " + (snap.getOffHeapMemorySizeBytes() / (1024 * 1024)) + " MB");
        }
    }
}
```

---

## 3. Query Pattern 1: High-Level Kleisli Fluent Traversal

All traversals in Impulse Graph start by anchoring to a specific **Domain Context** (`snap.domain("User")`). Dense node IDs ($0 \dots N_d-1$) are strictly per-domain.

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.api.traversal.Reducer;

import java.util.List;
import java.util.Set;

public class TraversalExamples {

    public static void runTraversals(ImpulseGraphSnapshot snap) {
        // --- 1. Single Seed Traversal (1-Hop & Multi-Hop) ---
        // Find all friends of User 42
        List<Long> friends = snap.domain("User")
            .from(42)
            .out("knows")
            .toList();

        // 2-Hop Traversal: User -> Compound -> Disease
        Set<Long> diseases = snap.domain("User")
            .from(42)
            .out("PURCHASED")
            .out("TREATS")
            .toSet();

        // --- 2. Batch Multi-Seed Frontier Traversal ---
        // Traverse simultaneously from seeds [10, 20, 30] in a single SIMD pass
        List<Long> batchTargets = snap.domain("User")
            .from(10, 20, 30)
            .out("knows")
            .toList();

        // --- 3. In-Domain Filtering with CEL Predicates ---
        // Filter candidate nodes in-domain before traversing
        long qualifyingCount = snap.domain("User")
            .from(10, 20, 30, 40)
            .filter("node.age >= 21")
            .out("PURCHASED")
            .count();

        // --- 4. Monoidic Path Reduction ---
        // When multiple paths converge, combine target node states (OR, MIN, MAX, SUM)
        ImpulseBitSet reachability = snap.domain("User")
            .from(10, 20)
            .out("knows", Reducer.OR)
            .toBitSet();

        // --- 5. Fixed-Point Loop (Transitive Reachability / ReBAC) ---
        // Repeat step until frontier stabilizes (Frontier_{t+1} == Frontier_t)
        ImpulseBitSet transitiveFriends = snap.domain("User")
            .from(42)
            .repeatUntilStable(step -> step.out("knows"))
            .toBitSet();

        // --- 6. Domain-Wide Aggregation ---
        // Traverse from ALL nodes in the User domain
        long totalActivePurchases = snap.domain("User")
            .all()
            .out("PURCHASED")
            .count();

        // --- 7. Domain Key <-> Dense ID Mapping (Read-Only from Snapshot) ---
        var userDomain = snap.domain("User");

        // External Business Key -> Internal Dense ID (0 ... N_d - 1)
        long aliceDenseId = userDomain.toDenseId("usr_alice"); // 0L

        // Internal Dense ID -> External Business Key String
        String aliceKey = userDomain.toKey(0); // "usr_alice"

        // Traversal initiated directly from external Business Key
        List<String> friendKeys = userDomain.fromKey("usr_alice")
            .out("knows")
            .toKeyList(); // ["usr_bob", "usr_charlie"]

        // Multi-key batch frontier
        Set<String> batchKeys = userDomain.fromKeys("usr_alice", "usr_bob")
            .out("knows")
            .toKeySet();
    }
}
```

---

## 4. Query Pattern 2: SQLite-Style Statement API (`ImpulseStatement`)

For applications that prefer parameterized statements, bind parameters dynamically and iterate over zero-copy `RowReader` cursors:

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;

public class StatementExample {

    public static void executeStatement(ImpulseGraphSnapshot snap) {
        // 1. Prepare parameterized statement
        String query = "FROM User WHERE id = $userId -> out('knows') -> out('likes')";
        
        try (ImpulseStatement stmt = snap.prepare(query)) {
            // 2. Bind parameter and execute
            stmt.bindNode("$userId", 42);

            try (RowReader rows = stmt.execute()) {
                System.out.println("Result column: " + rows.getColumnName(0));
                while (rows.next()) {
                    long targetNodeId = rows.getNodeId(0);
                    System.out.println("Reached Target: " + targetNodeId);
                }
            }

            // 3. Re-bind to a different seed without re-compiling
            stmt.bindNode("$userId", 100);
            System.out.println("Re-bound count: " + stmt.count());
        }
    }
}
```

---

## 5. Building & Writing Snapshots from Code

You can create brand-new `.imps` snapshots directly in Java using `DefaultSnapshotBuilder`:

```java
import org.impulsegraph.storage.csr.DefaultSnapshotBuilder;
import org.impulsegraph.storage.csr.GraphSnapshot;
import org.impulsegraph.storage.csr.RelationSnapshot;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class BuildSnapshotExample {

    public static void createSnapshot() throws Exception {
        try (Arena arena = Arena.ofShared()) {
            // Define CSR offsets and target arrays for relation "knows"
            // Node 0 -> [1, 2], Node 1 -> [2], Node 2 -> []
            MemorySegment offsets = arena.allocateFrom(ValueLayout.JAVA_INT, 0, 2, 3, 3);
            MemorySegment targets = arena.allocateFrom(ValueLayout.JAVA_INT, 1, 2, 2);

            RelationSnapshot knowsRel = new RelationSnapshot(arena, 3, 3, offsets, targets);
            GraphSnapshot graph = new GraphSnapshot(arena, Map.of("knows", knowsRel));

            // Serialize direct to .imps binary format
            byte[] impsBytes = DefaultSnapshotBuilder.writeSnapshotBytes(graph);
            Files.write(Path.of("output_graph.imps"), impsBytes);

            System.out.println("Wrote snapshot file (" + impsBytes.length + " bytes)");
        }
    }
}
```

---

## 6. Performance Summary & Execution Directives

- **Zero Allocations in Query Hot Path**: `snap.domain(...).from(...).out(...).toBitSet()` executes in off-heap bitsets with no JVM object allocations inside the loop.
- **SIMD Vectorization**: Outgoing target scans execute in unrolled 512-bit vector registers via AVX-512 / ARM Neon.
- **Lock-Free Read Operations**: Any number of threads can query `ImpulseGraphSnapshot` concurrently without synchronization locks or contention.
