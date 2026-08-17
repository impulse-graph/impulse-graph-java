# YAML Manifest Tablespace Configuration

For enterprise deployments with datasets exceeding single-file I/O limits, Impulse Graph supports multi-file chunking and composite graph loading via a declarative `manifest.yaml` configuration.

## Manifest Structure

A `manifest.yaml` defines domains, relations, and the physical `.imps` tablespaces that back them. 

### Example `manifest.yaml`

```yaml
version: "1.0"
graphName: "EnterpriseKnowledgeGraph"

tablespaces:
  users_space:
    file: "chunks/users.imps"
    mode: "read-only"
  purchased_part1:
    file: "chunks/purchased_01.imps"
  purchased_part2:
    file: "chunks/purchased_02.imps"

domains:
  Users:
    tablespace: users_space
  Products:
    tablespace: users_space

relations:
  PURCHASED:
    source: Users
    target: Products
    tablespace: purchased_part1
  PURCHASED_ARCHIVE:
    source: Users
    target: Products
    tablespace: purchased_part2
```

> **Note**: Domains must reside entirely within a single tablespace. Relations can be logically split by mapping them to multiple tablespaces using different relation identifiers (e.g. `PURCHASED` vs `PURCHASED_ARCHIVE`).

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
