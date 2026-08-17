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

## Virtual Relations (Coproduct Decomposition)

Impulse Graph supports **Virtual Relations**—a powerful feature where the query planner automatically decomposes a single logical relation walk into a union of multiple physical edge tables based on an underscore prefix naming convention (`relName_*`).

This is extremely useful in social media and interaction networks where a generic action (like "interacting with a post") is physically partitioned into discrete tablespaces by interaction type.

### Example: Social Media Interactions

```yaml
version: "1.0"
graphName: "SocialMediaGraph"

tablespaces:
  core_users:
    file: "chunks/users.imps"
  core_posts:
    file: "chunks/posts.imps"
  
  # Partitioned interaction tablespaces
  likes_ts:
    file: "chunks/interactions_likes.imps"
  comments_ts:
    file: "chunks/interactions_comments.imps"
  shares_ts:
    file: "chunks/interactions_shares.imps"

domains:
  User:
    tablespace: core_users
  Post:
    tablespace: core_posts

relations:
  # These are physical constituent relations matching the 'ENGAGED_WITH_*' prefix
  ENGAGED_WITH_LIKES:
    source: User
    target: Post
    tablespace: likes_ts
  ENGAGED_WITH_COMMENTS:
    source: User
    target: Post
    tablespace: comments_ts
  ENGAGED_WITH_SHARES:
    source: User
    target: Post
    tablespace: shares_ts
```

With this manifest, if you query the **Virtual Relation** `ENGAGED_WITH`:

```java
var query = new ImpulseQueryBuilder<BitSet>()
    .input("User", ArgType.SINGLE_LONG)
    .walkEdge("ENGAGED_WITH") // Virtual relation
    .build();
```

The Stage 2 Compiler (`VirtualRelationDecompositionPass`) will automatically detect the `ENGAGED_WITH_` prefix, decompose the virtual super-relation, and simultaneously walk the `LIKES`, `COMMENTS`, and `SHARES` physical tablespaces in a highly optimized vector coproduct!

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
