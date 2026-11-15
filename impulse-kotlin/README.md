# `impulse-kotlin` — Kotlin Extensions & DSL

Idiomatic Kotlin 2.1 bindings for **Impulse Graph Engine** (`0.10.0-SNAPSHOT`), providing `@JvmInline value class NodeId`, `DomainView` / `Traversal` / `ImpulseBitSet` operators, and `kotlinx.coroutines` helpers (`executeAsync`, `awaitDrainedAsync`).

## Maven Dependency

```xml
<dependency>
    <groupId>org.impulsegraph</groupId>
    <artifactId>impulse-kotlin</artifactId>
    <version>0.10.0-SNAPSHOT</version>
</dependency>
```

## Example

```kotlin
import org.impulsegraph.kotlin.*

snap.domain("User") {
    val friends = from(NodeId(0L))
        .out("knows")
        .toBitSet()
}

// Query builder DSL with CEL predicate & count materialization
val query = impulseQuery {
    domain("User")
    seedNode(0L)
    walkEdge("knows", "edge.weight > 0.5")
    collectCount()
}
val count = snap(query)
```
