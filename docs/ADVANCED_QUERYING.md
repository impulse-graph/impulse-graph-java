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

## 2. State-Bearing Traversals & Vectorized Reductions

Impulse Graph executes all traversals as **block-oriented SIMD pipeline operations**. There are no scalar edge-by-edge `for` loops. The graph is never treated as a simple ID lookup table; instead, traversal is the application of linear algebra (Kleisli composition) over memory-mapped columns.

### 2.1 The Active Frontier ($\langle D, S \rangle$)
A traversal pipeline maintains an **Active Frontier**, defined mathematically as $\langle D, S \rangle$:
*   **$D$ (Domain)**: The currently active node domain. All active nodes are dense integers $0 \dots N_D-1$.
*   **$S$ (State)**: A set of explicitly allocated columnar vectors (matching the dimension $N_D$) holding runtime variables (e.g., distances, accumulated costs).

### 2.2 Projections (Cross-Domain Transitions)
When walking an edge relation ($D_1 \xrightarrow{R} D_2$), state does *not* silently carry over. Because multiple paths from $D_1$ will inevitably collide at the same target node in $D_2$, the transition must explicitly declare how to compute the new state and how to **reduce** collisions.

Projections cleanly separate the reduction strategy from the standard CEL math:
`state.<TargetField>:<REDUCER> = <Pure CEL Expression>`

```java
snap.domain("City").fromKey("ATL")
    // Target Field : Reducer = Standard CEL Expression
    .out("SHUTTLE", "state.total_cost:MIN = src.flight_cost + edge.fee")
```

### 2.3 The 8 Core SIMD Reducers
Because execution is block-oriented, scalar "short-circuiting" (e.g., `if (visited) break;`) does not exist. Reducers are mapped directly to hardware SIMD conflict-resolution, scatter instructions, and bitwise algebra.

| Reducer Tag | Category | Monoid Operation | Hardware / SIMD Execution Strategy |
| :--- | :--- | :--- | :--- |
| **`:MIN`** | Math | Minimum | Conflict-aware vector min reduction prior to memory scatter. |
| **`:MAX`** | Math | Maximum | Conflict-aware vector max reduction prior to memory scatter. |
| **`:SUM`** | Math | Accumulation | Vectorized histogram accumulation / hardware scatter-add. |
| **`:AVG`** | Math | Arithmetic Mean | Compiles to dual-accumulators (Sum + Count) $\rightarrow$ Vector Division. |
| **`:COUNT`** | Topology | Path Multiplicity | Vectorized histogram generation over target node IDs. |
| **`:OR`** | Logic | Boolean Reach | `_mm512_or_si512` lock-free bitwise block algebra. |
| **`:AND`** | Logic | Universal Match | `_mm512_and_si512` lock-free bitwise block algebra. |
| **`:ANY`** | Structure | Arbitrary Witness | **Masked Vector Scatter**. Uses inverted `visited` bitmask to suppress memory writes. |
| **`:ARGMIN(f)`**| Structure | Min-Witness | **Vector Co-Scatter**. Re-uses the SIMD lane-winner mask from field `f`'s `:MIN` reduction. |
| **`:ARGMAX(f)`**| Structure | Max-Witness | **Vector Co-Scatter**. Re-uses the SIMD lane-winner mask from field `f`'s `:MAX` reduction. |

*Note: `:FIRST` and `:LAST` are intentionally excluded. `:ANY` explicitly relaxes deterministic ordering to unleash maximum lock-free parallel bandwidth for structural witnesses.*

### 2.4 Co-Reducers (Witness Extraction)
When calculating a `:MIN` or `:MAX` metric, you often want to extract the *witness*—the node ID that provided the optimal path. `ARGMIN` and `ARGMAX` allow you to bind a payload to the outcome of another field's reduction.

```java
snap.domain("Warehouse").from(activeWarehouses)
    .out("TRUCK_ROUTE", """
        // 1. The Primary Metric Reduction
        state.lowest_cost:MIN = src.cost + edge.fee,
        
        // 2. The Co-Reduced Witness (Who won?)
        state.best_supplier:ARGMIN(state.lowest_cost) = src.id
        """)
```
This guarantees that the metric and the witness are updated atomically via hardware Vector Co-Scatter.

---

## 3. Late Materialization vs. Lightweight Payloads

A classic performance optimization in graph traversal is deciding when to carry data through the pipeline versus looking it up at the very end.

### 3.1 Lightweight Payloads (Early Materialization)
For lightweight numerical primitives (`float32`, `int32`, `uint8`), it is extremely efficient to calculate and carry values directly in the state vectors during the SIMD traversal. They fit perfectly in wide registers and do not thrash the CPU cache.

```java
snap.domain("Warehouse").from(activeWarehouses)
    .out("TRUCK_LEG", """
        // Safe and highly optimized to carry through the hot loop
        state.accumulated_weight:SUM = src.pallet_weight + edge.weight,
        state.transit_hours:MIN = src.transit_hours + edge.hours
        """)
```

### 3.2 Late Materialization (Heavy Strings & Vectors)
Materializing heavy attributes (like strings from the Shared String Table, or 512-dimension float vectors) during the hot SIMD traversal destroys CPU cache and memory bandwidth. 

Instead, you should **Late Materialize**: project the **Dense IDs** as lightweight proxies, and only resolve the heavy values at the end of the pipeline (`collect()`). The engine exposes `src.id` and `edge.id` to facilitate this.

```java
// Hot SIMD loop operates strictly on integers using :ANY
var results = snap.domain("User").fromKey("alice")
    .out("PURCHASED", "state.product_id:ANY = edge.target_id")
    .out("MANUFACTURED_BY", "state.factory_id:ANY = src.id")
    .collect();

// Cold Path: Look up the actual string names ONLY for the final resulting nodes
String factory = snap.domain("Factory").getStringAttribute(results.get("factory_id"), "name");
```

### 3.3 Semantic Type Enforcement on Dense IDs
Even though Dense IDs are stored physically as integers, the compiler treats them as semantic identifiers (`TYPE_NODE_ID`, `TYPE_EDGE_ID`) and restricts which reducers are mathematically valid:

*   **ALLOWED (`:ANY`, `:MIN`, `:MAX`)**: Perfect for witness extraction (`:ANY`) or deterministic tie-breaking.
*   **BANNED (`:SUM`, `:AVG`, `:COUNT`, `:OR`, `:AND`)**: Adding or averaging memory offsets (e.g., `Node 5 + Node 10 = Node 15`) is structurally nonsensical and will throw a static compiler error.

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

## 6. Declarative Cypher Queries vs. Prepared Statements

Impulse Graph supports openCypher graph queries:

```java
String cypher = """
    MATCH (u:User)-[:PURCHASED]->(p:Product)-[:IN_CATEGORY]->(c:Category)
    WHERE u.id = $userId AND p.price >= $minPrice
    RETURN c.name
    """;

try (ImpulseStatement stmt = snap.prepare(cypher)) {
    stmt.bindNode("$userId", aliceId);
    stmt.bindDouble("$minPrice", 49.99);

    try (RowReader rows = stmt.execute()) {
        while (rows.next()) {
            System.out.println("Category Node: " + rows.getNodeId(0));
        }
    }
}
```

> [!TIP]
> **Ad-Hoc Query Compilation vs. Pre-Prepared Statements**:
> Because the Impulse Graph query compiler evaluates in sub-microseconds (tens of nanoseconds for small ASTs), **it is generally recommended NOT to pre-prepare generic parameterized statements for analytical workloads**.
> 
> When queries are compiled directly with concrete values, the compiler performs aggressive **parameter-specific optimizations** that are impossible with generic placeholders:
> - **Partition Elimination**: Entire snapshot partitions are skipped when filtering on known constant partition keys.
> - **Zone Map Dead-Code Pruning**: Scans are pruned entirely to $O(0)$ when min/max zone map boundaries prove no matching records exist.
> - **Constant Folding & Monotonic Homomorphisms**: Mathematical predicates are pre-computed at compile time rather than in the inner traversal loop.
>
> On large graphs, empirical benchmarks consistently show that direct, freshly compiled queries perform as fast or faster than generic parameterized statements.
