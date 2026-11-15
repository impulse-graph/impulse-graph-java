# `impulse-scala` — Scala 3 Extensions & DSL

Type-safe Scala 3 DSL for **Impulse Graph Engine** (`impulse-scala_3`, `0.10.0-SNAPSHOT`), featuring `DomainName` / `RelationName` / `NodeId` opaque types, receiver-less `using` query-builder forwarders (`ImpulseDsl`), `ImpulseBitSet` set algebra, and `Future`-based async execution.

## Maven Dependency

```xml
<dependency>
    <groupId>org.impulsegraph</groupId>
    <artifactId>impulse-scala_3</artifactId>
    <version>0.10.0-SNAPSHOT</version>
</dependency>
```

## Example

```scala
import org.impulsegraph.scala.ImpulseDsl.{*, given}

val q = impulseQuery {
  domain(DomainName("User"))
  seedNode(NodeId(0L))
  walkEdgeWithCel(RelationName("knows"), "edge.weight > 0.5")
  collectCount()
}
val total: java.lang.Long = snap(q)
```
