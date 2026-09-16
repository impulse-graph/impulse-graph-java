# openCypher Dialect Reference Guide — Impulse Graph Engine (Java)

Impulse Graph provides a high-performance openCypher query parser and compiler frontend within the `impulse-compiler` module. Declarative openCypher analytical queries are parsed by [`CypherParser`](file:///Users/jesse/impulse/impulse-graph-java/impulse-compiler/src/main/java/org/impulsegraph/compiler/cypher/CypherParser.java) and lowered by [`CypherCompiler`](file:///Users/jesse/impulse/impulse-graph-java/impulse-compiler/src/main/java/org/impulsegraph/compiler/cypher/CypherCompiler.java) into canonical `ImpScheme` S-Expressions (`.impscm`), optimized across the 7-stage compiler pipeline, and emitted as zero-allocation `impOps` binary bytecode instructions for execution on Java 21 LTS Foreign Function & Memory (FFM) and Vector API.

---

## 1. Supported openCypher Clause Grammar

Impulse Graph supports the core analytical openCypher subset for graph pattern matching:

```cypher
MATCH <Pattern>
[WHERE <PredicateExpression>]
RETURN <ProjectionExpression>
```

### 1.1 Supported Projection Expressions (`RETURN`)
The `RETURN` clause determines the terminal materialization of the active frontier:
* `RETURN <variable>`: Materializes the active target domain frontier set as an off-heap BitSet ([`ScmCollect.bitset()`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/compiler/ast/ScmCollect.java)).
* `RETURN count(<variable>)`: Computes the scalar cardinality of the active target frontier set ([`ScmCollect.scalar()`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/compiler/ast/ScmCollect.java)).

> [!NOTE]
> Impulse Graph is an analytical vector traversal engine. Clauses modifying graph state (`CREATE`, `SET`, `DELETE`, `MERGE`) or pagination clauses (`LIMIT`, `SKIP`, `ORDER BY`) are not part of the core analytical kernel and are not accepted by [`CypherParser`](file:///Users/jesse/impulse/impulse-graph-java/impulse-compiler/src/main/java/org/impulsegraph/compiler/cypher/CypherParser.java). Analytical queries operate strictly on immutable `.imps` binary snapshots.

### 1.2 `MATCH` Pattern Grammar
Patterns define single-hop or multi-hop path traversals across node domains:

```cypher
// Single-Hop Forward Traversal (CSR Walk)
MATCH (u:User)-[:knows]->(f:User)

// Single-Hop Reverse Traversal (CSC Walk)
MATCH (p:Product)<-[:PURCHASED]-(u:User)

// Multi-Hop Path Traversal (Metapath)
MATCH (d:Disease)-[:DdG]->(g:Gene)<-[:CuG]-(c:Compound)

// Namespaced or Escaped Relationship Types (using backticks)
MATCH (d:Disease)-[:`DISGENET::da`]->(g:Gene)<-[:`DRUGBANK::target`]-(c:Compound)

// Bounded Variable-Length Traversal
MATCH (u1:User)-[:knows*1..3]->(u2:User)

// Relationship Variable Binding (for edge attribute filtering)
MATCH (u:User)-[e:knows]->(f:User)
```

#### Invariants Enforced by `CypherParser`
1. **Typed Edge Walk Mandate**: Every relationship in a path pattern must explicitly declare its relationship type (e.g. `[:RelName]`). Anonymous edge traversals (such as `-->` or `-[e]->`) are strictly prohibited and will trigger an `IllegalArgumentException` at parse time.
2. **Bounded Traversal Mandate**: Infinite unbounded variable-length traversals (`*`) are prohibited to guarantee bounded memory and execution bounds. Variable-length hops must specify a concrete upper bound (e.g. `*2`, `*1..4`, or `*..3`). Unbounded wildcards (`*`) without an upper bound will trigger an `IllegalArgumentException`.

---

## 2. Predicates & Expressions (`WHERE`)

The `WHERE` clause defines filtering predicates evaluated during traversal planning:

```cypher
WHERE <targetVar>.<field> <operator> <valueOrParam> [AND ...]
```

### 2.1 Supported Comparison Operators

| Category | Operators | Example |
| :--- | :--- | :--- |
| **Equality** | `=`, `==` | `WHERE d.id = $diseaseId` |
| **Inequality** | `!=`, `<>` | `WHERE e.status != 'inactive'` |
| **Relational Comparison** | `<`, `<=`, `>`, `>=` | `WHERE e.weight >= 0.8` |
| **Predicate Conjunction** | `AND` | `WHERE d.id = $diseaseId AND e.weight >= 0.8` |

> [!NOTE]
> `CypherParser` supports chaining predicates using `AND`. The token scanner recognizes `OR` and `NOT`, but the openCypher `WHERE` clause parser in `CypherParser` conjoins path-level filters exclusively with `AND`. Complex boolean expressions can be evaluated inside edge shader predicates via Google CEL.

### 2.2 Value and Parameter Bindings
Predicate expressions accept the following right-hand side operands:
* **Query Parameters**: `$paramName` (e.g. `$diseaseId`, `$minWeight`, `$userId`).
* **Numeric Literals**: Integers (`42`) and floating-point values (`0.8`, `49.99`).
* **String Literals**: Single-quoted (`'active'`) or double-quoted (`"active"`).
* **Identifiers / Boolean Literals**: Identifiers such as `true` or `false`.

### 2.3 Predicate Roles & Scope Lowering
`CypherCompiler` resolves predicates based on the variable target:
* **Seed Node Anchor Constraint**: When `<targetVar>` matches the start node variable of the pattern (e.g. `d` in `(d:Disease)`), `CypherCompiler` extracts the parameter or literal as the seed entity binding (`seedParameterOrValue`), anchoring the entrypoint frontier for the traversal.
* **Edge Attribute Shader Filter**: When `<targetVar>` matches an edge variable (e.g. `e` in `-[e:knows]->`), `CypherCompiler` synthesizes a Google CEL predicate (`edge.<field> <op> <valueOrParam>`) and parses it via [`CelParser`](file:///Users/jesse/impulse/impulse-graph-java/impulse-compiler/src/main/java/org/impulsegraph/compiler/cel/CelParser.java) into a [`ScmCelExpr`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/compiler/ast/ScmCelExpr.java) shader step attached to the traversal walk.

---

## 3. AST Architecture & Lowering (`CypherCompiler`)

openCypher queries are parsed by [`CypherParser`](file:///Users/jesse/impulse/impulse-graph-java/impulse-compiler/src/main/java/org/impulsegraph/compiler/cypher/CypherParser.java) into a structured [`CypherQuery`](file:///Users/jesse/impulse/impulse-graph-java/impulse-compiler/src/main/java/org/impulsegraph/compiler/cypher/CypherParser.java) record tree and lowered by [`CypherCompiler`](file:///Users/jesse/impulse/impulse-graph-java/impulse-compiler/src/main/java/org/impulsegraph/compiler/cypher/CypherCompiler.java) into a [`ScmProgram`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/compiler/ast/ScmProgram.java) AST.

### 3.1 Parser & Compiler Records

* **`CypherParser.CypherQuery`**: Represents the complete parsed query:
  ```java
  public record CypherQuery(PathPattern path, List<WherePredicate> wherePredicates, ReturnProjection projection)
  ```
* **`CypherParser.PathPattern` & `PathStep`**: Represents the multi-hop trajectory:
  ```java
  public record PathPattern(NodePattern startNode, List<PathStep> steps)
  public record PathStep(EdgePattern edge, NodePattern targetNode)
  public record NodePattern(String variable, String label)
  public record EdgePattern(String variable, String relationName, boolean isForward, int minHops, int maxHops, String filterExpr)
  ```
* **`CypherParser.WherePredicate`**: Represents parsed attribute conditions:
  ```java
  public record WherePredicate(String targetVar, String field, String op, String valueOrParam)
  ```
* **`CypherParser.ReturnProjection`**: Represents the terminal collection target:
  ```java
  public record ReturnProjection(String variable, boolean isCount)
  ```
* **`CypherCompiler.CompilationResult`**: Compiler output packaging the IR and parameter metadata:
  ```java
  public record CompilationResult(ScmProgram ast, String seedVariable, String seedParameterOrValue)
  ```

---

### 3.2 Canonical AST Lowering Examples

#### Example 1: Multi-Hop Forward/Reverse Metapath (BitSet Projection)
```cypher
MATCH (d:Disease)-[:DdG]->(g:Gene)<-[:CuG]-(c:Compound)
WHERE d.id = $diseaseId
RETURN c
```
Lowered `ImpScheme` S-Expression AST (`ast.toScmString()`):
```scheme
(program
  (csr-walk "DdG")
  (csc-walk "CuG")
  (collect-bitset))
```
* **Compilation Metadata**: `seedVariable = "d"`, `seedParameterOrValue = "$diseaseId"`
* Forward hop `-[:DdG]->` lowers to `(csr-walk "DdG")` using the CSR forward index.
* Reverse hop `<-[:CuG]-` lowers to `(csc-walk "CuG")` using the CSC transpose index.
* Terminal `RETURN c` lowers to `(collect-bitset)`.

#### Example 2: Cardinality Count Aggregation
```cypher
MATCH (d:Disease)-[:DdG]->(g:Gene)<-[:CuG]-(c:Compound)
WHERE d.id = $diseaseId
RETURN count(c)
```
Lowered `ImpScheme` S-Expression AST (`ast.toScmString()`):
```scheme
(program
  (csr-walk "DdG")
  (csc-walk "CuG")
  (collect-scalar))
```
* Terminal `RETURN count(c)` lowers to `(collect-scalar)` for scalar cardinality counting without materializing full bitset vectors.

#### Example 3: Variable-Length Bounded Traversal
```cypher
MATCH (u1:User)-[:knows*1..3]->(u2:User)
WHERE u1.id = $userId
RETURN u2
```
Lowered `ImpScheme` S-Expression AST (`ast.toScmString()`):
```scheme
(program
  (csr-walk "knows")
  (csr-walk "knows")
  (csr-walk "knows")
  (collect-bitset))
```
* Bounded variable-length hops (`maxHops = 3`) are expanded into sequential traversal steps.

#### Example 4: Edge Attribute Filter (Vectorized CEL Shader)
```cypher
MATCH (u:User)-[e:knows]->(f:User)
WHERE u.id = $userId AND e.weight >= 0.8
RETURN f
```
Lowered `ImpScheme` S-Expression AST (`ast.toScmString()`):
```scheme
(program
  (csr-walk "knows" (shader (cel-expr (vec-cmp-gte (get-attr edge "weight") 0.8))))
  (collect-bitset))
```
* Edge predicate `e.weight >= 0.8` is converted into a Google CEL expression `edge.weight >= 0.8`, parsed into a `CelAstNode`, and wrapped as a `(shader ...)` inside the forward walk.

---

## 4. Query Execution Pipelines

Impulse Graph provides two execution interfaces for openCypher queries: high-level prepared statements and direct engine-native compiler pipeline execution.

### 4.1 High-Level Statement API (`ImpulseStatement`)

Parameterized queries can be prepared once and executed repeatedly with different parameter bindings via [`ImpulseStatement`](file:///Users/jesse/impulse/impulse-graph-java/impulse-api/src/main/java/org/impulsegraph/api/statement/ImpulseStatement.java):

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

### 4.2 Direct Engine-Native Compiler Pipeline

For performance-critical batch workloads or integration with custom compiler passes, openCypher queries can be compiled directly through the 7-stage optimizer pipeline into native `impOps` binary bytecode:

```java
import org.impulsegraph.api.ImpulseGraphSnapshot;
import org.impulsegraph.api.bitset.ImpulseBitSet;
import org.impulsegraph.compiler.ast.ImpScmNode;
import org.impulsegraph.compiler.cypher.CypherCompiler;
import org.impulsegraph.compiler.emitter.ImpOpsBytecodeEmitter;
import org.impulsegraph.compiler.passes.CompilerContext;
import org.impulsegraph.compiler.passes.stage1.*;
import org.impulsegraph.compiler.passes.stage2.*;
import org.impulsegraph.compiler.trace.CompilerOptions;
import org.impulsegraph.compiler.trace.PassTracer;
import org.impulsegraph.vm.ImpulseVmInterpreter;

import java.lang.foreign.Arena;

public class DirectCypherPipelineExample {
    public static ImpulseBitSet executeCypher(ImpulseGraphSnapshot snapshot, String cypher, int seedNodeId, Arena arena) {
        // 1. Parse openCypher text and lower to ImpScheme S-Expression AST
        CypherCompiler.CompilationResult compilation = CypherCompiler.compile(cypher);

        // 2. Execute 7-stage optimizer pipeline
        CompilerOptions options = CompilerOptions.builder().withTracing(false).build();
        CompilerContext ctx = new CompilerContext(snapshot, options, new PassTracer(options));

        ImpScmNode compiled = ctx.executePass(PreBindValidator.INSTANCE, compilation.ast());
        compiled = ctx.executePass(ParameterBindingPass.INSTANCE, compiled);
        compiled = ctx.executePass(KernelFusionPass.INSTANCE, compiled);
        compiled = ctx.executePass(DirectionSelectionPass.INSTANCE, compiled);
        compiled = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, compiled);
        compiled = ctx.executePass(PhysicalBindingPass.INSTANCE, compiled);
        compiled = ctx.executePass(RegisterAllocationPass.INSTANCE, compiled);

        // 3. Emit 64-bit aligned impOps binary bytecode
        var prog = ImpOpsBytecodeEmitter.emit(compiled, snapshot, arena);

        // 4. Execute on ImpulseVM off-heap interpreter
        Object result = ImpulseVmInterpreter.execute(
            prog.programSegment(), prog.instructionCount(), snapshot, seedNodeId, arena);

        return (ImpulseBitSet) result;
    }
}
```

---

## 5. Impulse Set Deduplication vs. Cypher Multiset Semantics

> [!IMPORTANT]
> **Frontier Set Deduplication Invariant**:
> Standard openCypher implementations (such as Neo4j) evaluate patterns using multiset path algebra, preserving duplicate paths unless `RETURN DISTINCT` is specified.
> 
> Impulse Graph evaluates traversals using **Kleisli Frontier Set Propagation**:
> $$\text{Pipeline} = \text{Anchor}(D_0, F_0, S_0) \gg= T_1 \gg= T_2 \dots \gg= T_k \gg= \text{Collect}()$$
> 
> At each traversal step, the active node set is represented as an off-heap BitSet or Roaring Bitmap. Duplicate arrival paths at the same target node are automatically deduplicated in $O(1)$ time via bitwise OR operations ($\oplus = \lor$), delivering linear-time $O(|E|)$ traversal latency without garbage collection pauses.
