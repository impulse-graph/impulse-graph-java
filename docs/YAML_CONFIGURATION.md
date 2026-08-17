# YAML Manifest Tablespace Configuration

For enterprise deployments with datasets exceeding single-file I/O limits, Impulse Graph supports multi-file chunking and composite graph loading via a declarative `manifest.yaml` configuration.

## Manifest Structure

A `manifest.yaml` defines domains, relations, and the physical `.imps` chunk files that back them. The engine seamlessly maps these chunks into a unified `ImpulseGraphSnapshot`.

### Example `manifest.yaml`

```yaml
version: "1.0"
graphName: "EnterpriseKnowledgeGraph"

tablespaces:
  Users:
    type: domain
    files:
      - "chunks/users_01.imps"
      - "chunks/users_02.imps"
  Products:
    type: domain
    files:
      - "chunks/products_01.imps"
  PURCHASED:
    type: relation
    sourceDomain: Users
    targetDomain: Products
    files:
      - "chunks/purchased_01.imps"
      - "chunks/purchased_02.imps"
      - "chunks/purchased_03.imps"
```

## Loading from a Manifest

Instead of loading a single `.imps` file, pass the `manifest.yaml` to the `BinarySnapshotLoader`.

```java
import org.impulsegraph.storage.csr.BinarySnapshotLoader;
import org.impulsegraph.api.ImpulseGraphSnapshot;
import java.lang.foreign.Arena;
import java.nio.file.Path;

public class ManifestLoaderExample {
    public static void main(String[] args) throws Exception {
        try (Arena arena = Arena.ofShared()) {
            
            Path manifestPath = Path.of("datasets/enterprise/manifest.yaml");
            
            // The loader parses the YAML and memory-maps all underlying chunks
            var loadedSnapshot = BinarySnapshotLoader.loadFromManifest(manifestPath, arena);
            ImpulseGraphSnapshot compositeGraph = loadedSnapshot.getGraph();
            
            System.out.println("Composite Graph Loaded.");
        }
    }
}
```

## Configuration Properties

* **`version`**: The manifest version (currently "1.0").
* **`graphName`**: An identifier for your unified graph.
* **`tablespaces`**: The definition of logical entities.
  * **`type`**: `domain` (nodes) or `relation` (edges).
  * **`sourceDomain` / `targetDomain`**: Required for `relation` types to define schema topology.
  * **`files`**: An ordered array of relative or absolute paths to physical `.imps` binaries.
