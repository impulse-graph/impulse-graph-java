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

## Attributes and Data Types

You can attach strongly-typed attributes to both domains and relations. Based on the Impulse Graph Schema Specification, the supported data types are:
* **Integers**: `Int8`, `Int16`, `Int32`, `Int64`
* **Floating Point**: `Float32`, `Float64`
* **Strings**: `VarString` (variable length, uses string pool) and `FixedString(N)` (fixed byte length for fast scanning)
* **Boolean**: `Bool`
* **Dates**: `TimestampMicro`

For **Embeddings** and vector representations, define a primitive array dimension using the schema (e.g. `dimension: 128`). In the basic `manifest.yaml`, it is commonly noted with array syntax:

### Example with Attributes

```yaml
domains:
  User:
    tablespace: core_users
    attributes:
      account_status: "FixedString(8)"
      display_name: "VarString"
      embedding: "Float32[128]"

relations:
  ENGAGED_WITH_LIKES:
    source: User
    target: Post
    tablespace: likes_ts
    attributes:
      affinityScore: "Float32"
      timestamp: "TimestampMicro"
```

## Virtual Relations (Coproducts)

A **Virtual Relation** allows you to logically group multiple physical relations into a single queryable edge name. This avoids the need for prefix matching or dynamic rules, acting simply as an explicit composite array.

### Example: Social Media Interactions

```yaml
virtual_relations:
  ENGAGED_WITH:
    components:
      - ENGAGED_WITH_LIKES
      - ENGAGED_WITH_COMMENTS
      - ENGAGED_WITH_SHARES
      - ENGAGED_WITH_POSTS
```

When you query `.walkEdge("ENGAGED_WITH")`, the query planner statically expands it into a coproduct of the defined component relations.

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
