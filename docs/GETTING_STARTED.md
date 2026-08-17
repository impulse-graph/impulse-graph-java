# Getting Started with Impulse Graph (Java 25 FFM)

Welcome to the **Impulse Graph Engine**. This guide will walk you through the basics of loading a graph snapshot and executing queries using the `impulse-api` and `impulse-storage` modules.

## 1. Installation

Include the core engine dependencies in your `pom.xml`:

```xml
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
```

> **Note**: Impulse Graph requires **Java 25** with `--enable-preview` and `--add-modules jdk.incubator.vector` to run effectively.

## 2. Loading a Snapshot

Impulse Graph stores graph data in immutable `.imps` files. You map these off-heap memory snapshot files into your application via the FFM `Arena`.

```java
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import java.lang.foreign.Arena;
import java.nio.file.Path;

public class GraphExample {
    public static void main(String[] args) throws Exception {
        // Use a shared memory arena for the application lifecycle
        try (Arena arena = Arena.ofShared()) {
            
            // Map the binary snapshot directly to memory
            Path snapshotPath = Path.of("my_graph.imps");
            var loadedSnapshot = BinarySnapshotLoader.loadSnapshot(snapshotPath, arena);
            
            ImpulseGraphSnapshot graph = loadedSnapshot.getGraph();
            System.out.println("Loaded Graph with " + graph.getRelationCount() + " relations.");
            
            // Execute your queries...
        }
    }
}
```

## 3. Querying the Graph

The primary interface for executing queries in Java is the `ImpulseQueryBuilder`.

```java
import org.impulsegraph.api.ImpulseQueryBuilder;
import org.impulsegraph.api.ArgType;

var query = new ImpulseQueryBuilder<BitSet>()
    .input("User", ArgType.SINGLE_LONG)
    .walkEdge("FOLLOWS")
    .filterWithCel("age > 30")
    .walkEdge("PURCHASED")
    .build();

// Evaluate the query
var results = evaluator.evaluate(query, graph, 12345L);
```

For advanced use cases like multi-file partitioning, see the [YAML Configuration Guide](YAML_CONFIGURATION.md).
