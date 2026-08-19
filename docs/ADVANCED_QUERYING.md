# Advanced Querying Guide — Impulse Graph Engine (Java)

This guide covers advanced querying patterns, fixed-point loops, state projections, monoidic reductions, and openCypher query optimizations in Impulse Graph Engine.

---

## 1. Fixed-Point Loops & Transitive Reachability

For applications analyzing social networks, supply chains, or Relationship-Based Access Control (ReBAC / Zanzibar), Impulse Graph provides zero-allocation fixed-point traversal loops:

### 1.1 Unbounded Convergence (`repeatUntilStable`)
Repeatedly executes the step function on the evolving frontier until no new nodes are discovered ($\text{Frontier}_{t+1} == \text{Frontier}_t$):

```java
var userDomain = snap.domain("User");

// Transitive reachability / network closure
Set<Long> fullNetwork = userDomain.fromKey("usr_alice")
    .repeatUntilStable(step -> step.out("knows"))
    .toSet();
```

### 1.2 Bounded Step Expansion (`repeat(n, step)`)
Limits expansion to at most $N$ hops:

```java
// Expand up to 3 hops away from Alice
Set<Long> network3Hops = userDomain.fromKey("usr_alice")
    .repeat(3, step -> step.out("knows"))
    .toSet();
```

---

## 2. Monoidic Path Reductions

When multiple paths converge on the same target entity, Impulse Graph reduces values using algebraic monoids:

| Monoid Reducer | Operator | Use Cases |
| :--- | :--- | :--- |
| `Reducer.OR` (Default) | $\oplus = \lor$ | Set reachability, existence proofs, graph connectivity |
| `Reducer.MIN` | $\oplus = \min$ | Shortest path distances, min-hop routing |
| `Reducer.MAX` | $\oplus = \max$ | Capacity bounds, maximum confidence / trust scores |
| `Reducer.SUM` | $\oplus = +$ | PageRank Markov walks, accumulated flow, edge weight aggregation |

```java
import org.impulsegraph.api.traversal.Reducer;

// Shortest-path or minimum-hop propagation:
ImpulseBitSet minDistanceFrontier = userDomain.fromKey("usr_alice")
    .out("transacted", Reducer.MIN)
    .toBitSet();
```

---

## 3. Node State Projections (`.project(...)`)

You can attach and mutate continuous numerical state vectors on nodes during traversal without JVM heap allocations:

```java
// Project state calculation during graph evaluation
var result = userDomain.fromKey("usr_alice")
    .project("state.normalized_score = node.credit_score / 850.0")
    .out("TRANSACTED")
    .toList();
```

---

## 4. Complex CEL Filtering & Parameter Sweeps

Impulse Graph embeds Common Expression Language (CEL) predicates directly into traversal steps. Dynamic parameters can be bound at runtime to avoid recompilation:

```java
long qualifiedCount = userDomain.fromKeys("usr_alice", "usr_bob")
    .withParam("@minScore", 700.0)
    .withParam("@maxAge", 65)
    .filter("node.credit_score >= @minScore && node.age <= @maxAge")
    .out("PURCHASED", "edge.amount > 1000.0")
    .count();
```

---

## 5. BitSet Algebra & Graph Set Theory

For advanced analytical pipelines, target frontiers can be materialized into off-heap `ImpulseBitSet` instances and combined using native bitwise operations:

```java
// Alice's friends
ImpulseBitSet aliceFriends = userDomain.fromKey("usr_alice").out("knows").toBitSet();

// Bob's friends
ImpulseBitSet bobFriends = userDomain.fromKey("usr_bob").out("knows").toBitSet();

// 1. Intersection (Mutual Friends):
ImpulseBitSet mutual = new OffHeapBitSet(arena, (int) userDomain.nodeCount());
mutual.or(aliceFriends);
mutual.and(bobFriends);

// 2. Difference (Friends of Alice who are NOT friends of Bob):
ImpulseBitSet exclusiveAlice = new OffHeapBitSet(arena, (int) userDomain.nodeCount());
exclusiveAlice.or(aliceFriends);
exclusiveAlice.andNot(bobFriends);

// Re-anchor traversal from computed bitset
List<String> mutualKeys = userDomain.from(mutual).toKeyList();
```

---

## 6. Advanced Cypher Patterns & Execution Plans

Parameterized openCypher queries compile into optimized physical plans executing over binary snapshot sections:

```java
String cypher = """
    MATCH (u:User)-[:PURCHASED]->(p:Product)-[:IN_CATEGORY]->(c:Category)
    WHERE u.id =  AND p.price >= 
    RETURN c.name
    """;

try (ImpulseStatement stmt = snap.prepare(cypher)) {
    stmt.bindNode("", aliceId);
    stmt.bindDouble("", 49.99);

    try (RowReader rows = stmt.execute()) {
        while (rows.next()) {
            System.out.println("Category Node: " + rows.getNodeId(0));
        }
    }
}
```
