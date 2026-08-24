# openCypher Dialect Reference Guide — Impulse Graph Engine (Java)

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

Impulse Graph provides a high-performance openCypher query parser and compiler frontend. Declarative openCypher queries are parsed by [`CelCompiler`](file:///Users/jesse/impulse/impulse-graph-java/impulse-compiler/src/main/java/org/impulsegraph/compiler/cel/CelCompiler.java) into `ImpScheme` S-Expressions (`.impscm`), optimized across 7 IR passes, and compiled directly into zero-allocation `impOps` bytecode instructions for execution on Java 21+ FFM.

---

## 1. Supported openCypher Clause Grammar

Impulse Graph supports the core analytical openCypher subset for graph pattern matching:

```cypher
MATCH <Pattern>
[WHERE <PredicateExpression>]
RETURN <ProjectionExpression>
[LIMIT <IntegerConstant>]
```

### 1.1 `MATCH` Pattern Grammar
Patterns define single-hop or multi-hop path traversals across domains:

```cypher
// Single-Hop Forward Traversal
MATCH (u:User)-[:knows]->(f:User)

// Multi-Hop Path Traversal (Metapath)
MATCH (d:Disease)-[:DdG]->(g:Gene)<-[:CuG]-(c:Compound)

// Reverse Relationship Walk (Uses CSC Index)
MATCH (p:Product)<-[:PURCHASED]-(u:User)
```

---

## 2. Predicates & Expressions (`WHERE`)

`WHERE` clauses support Google CEL (Common Expression Language) predicates evaluated over node and edge attributes:

### 2.1 Supported Operators

| Category | Operators | Example |
| :--- | :--- | :--- |
| **Comparison** | `=`, `!=`, `<`, `<=`, `>`, `>=` | `WHERE u.age >= 21` |
| **Logical** | `AND`, `OR`, `NOT` | `WHERE u.age >= 21 AND u.active = true` |
| **Parameter Binding** | `$paramName` | `WHERE u.id = $userId AND edge.amount >= $minAmount` |

### 2.2 Attribute Scope Prefixes
* `node.<attribute>` or `<variable>.<attribute>`: Resolves against the attribute table of the currently active domain in the pattern step.
* `edge.<attribute>`: Resolves against the edge attribute array for the active relation.

---

## 3. Parameter Binding & Execution (`ImpulseStatement`)

Parameterized openCypher queries are compiled once and executed repeatedly with different parameter bindings via [`ImpulseStatement`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/statement/ImpulseStatement.java):

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.statement.ImpulseStatement;
import org.impulsegraph.api.statement.RowReader;

public class CypherExecutionExample {
    public static void runQuery(ImpulseGraphSnapshot snap) {
        String cypher = """
            MATCH (d:Disease)-[:DdG]->(g:Gene)<-[:CuG]-(c:Compound)
            WHERE d.id = $diseaseId
            RETURN c
            """;

        try (ImpulseStatement stmt = snap.prepare(cypher)) {
            // Bind parameter
            stmt.bindNode("$diseaseId", 42);

            try (RowReader rows = stmt.execute()) {
                while (rows.next()) {
                    long compoundNodeId = rows.getNodeId(0);
                    System.out.println("Discovered Target Compound Node ID: " + compoundNodeId);
                }
            }
        }
    }
}
```

---

## 4. Impulse Set Deduplication vs. Cypher Multiset Semantics

> [!IMPORTANT]
> **Frontier Set Deduplication Invariant**:
> Standard openCypher implementations (e.g. Neo4j) evaluate patterns using multiset path algebra, preserving duplicate paths unless `RETURN DISTINCT` is specified.
> 
> Impulse Graph evaluates traversals using **Kleisli Frontier Set Propagation**. At each hop, the active node set is represented as an off-heap BitSet or Roaring Bitmap. Duplicate arrival paths at the same target node are automatically deduplicated in $O(1)$ time, delivering linear-time $O(|E|)$ traversal latency.
