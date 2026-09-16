# Impulse Graph Java Compiler Suite (`impulse-compiler`)

The `impulse-compiler` module is a pure Java 21 LTS Foreign Function & Memory (FFM) and Vector API implementation of the **multi-pass optimizing ImpScheme (`.impscm`), openCypher, and CEL compiler pipeline**, generating zero-allocation `impOps` binary bytecode for direct execution on the `ImpulseVM`.

---

## 1. Features & Architecture Overview

The compilation pipeline is partitioned across three cooperating modules in the Java engine:
* **`impulse-api` (`org.impulsegraph.compiler.ast`)**: Sealed ImpScheme AST hierarchy, algebraic signatures, and S-expression parser/serializer.
* **`impulse-compiler` (`org.impulsegraph.compiler.*`)**: Polyglot query frontends (openCypher, CEL), Stage 1 and Stage 2 optimization passes, query registry, diagnostics, and ImpAsm disassembler.
* **`impulse-vm` (`org.impulsegraph.compiler.*`)**: Physical snapshot catalog binding, linear-scan register allocator, 8-byte instruction bytecode emitter, and compiler execution context.

### Polyglot Query Frontends
* **openCypher (`org.impulsegraph.compiler.cypher`)**: `CypherParser`, `CypherLexer`, and `CypherCompiler` lower `MATCH ... WHERE ... RETURN` queries into canonical `ScmProgram` pipelines with forward/reverse walks, filter shaders, and scalar/bitset collection.
* **Google CEL (`org.impulsegraph.compiler.cel`)**:
  - `CelParser`: Pratt recursive-descent parser.
  - `CelCompiler`: Lowers CEL expressions into ImpScheme S-expressions across SIMD vector tile mode and off-heap stream mode.
  - `CelAstOptimizer`: Performs constant folding, double negation elimination, boolean short-circuiting, and algebraic inversions of monotonic transcendental operations (e.g. $\text{sqrt}(x) < c \implies x < c^2$, $\log(x) < c \implies x < e^c$).
  - `CelMathFunctions`: Registry of 54 analytical vector math and floating-point classification functions (`abs`, `sqrt`, `rsqrt`, `exp`, `log`, `sigmoid`, `safeDiv`, `isFinite`, etc.) matching the core `impOps` specification.
  - `ProjectionParser` & `ProjectionValidator`: Parse and validate state projection expressions and monoidic reductions (`target = reducer(expr)`).
* **ImpScheme (`org.impulsegraph.compiler.ast`)**: Homoiconic S-expression intermediate representation bus (`.impscm`) with pure Java recursive-descent parser (`ImpScmParser`) and serializer (`ImpScmSerializer`).

### ImpScheme AST Node Hierarchy (`org.impulsegraph.compiler.ast.ImpScmNode`)
All AST nodes implement the sealed interface `ImpScmNode`:
* **`ScmProgram`**: Top-level sequence of query execution steps (`(program step...)`).
* **`ScmWalk`**: Graph traversal walk step (`(csr-walk rel)`, `(csc-walk rel)`, or `(walk rel)`) with optional attached shader filters and sub-steps.
* **`ScmWalk2Hop`**: Fused 2-hop CSR traversal (`(csr-walk-2hop rel1 rel2)`), executing two consecutive forward steps in a single hardware loop.
* **`ScmVectorFilter`**: SIMD vector attribute filter (`(vector-filter predicate)`).
* **`ScmStreamFilter`**: Streaming predicate filter for row/record shaders (`(stream-filter predicate)`).
* **`ScmStreamProject`**: Streaming state projection expression (`(stream-project expr)`).
* **`ScmCelExpr`**: Embedded raw or parsed CEL expression node (`(cel-expr ...)`).
* **`ScmReduce`**: Monoidic state reduction and aggregation (`(reduce-sum)`, `(reduce-first)`, `(reduce-count)`, `(reduce-min)`, `(reduce-max)`, `(reduce-argmin)`, `(reduce-argmax)`).
* **`ScmCollect`**: Terminal result materialization (`(collect-bitset)`, `(collect-vector)`, `(collect-list)`, `(collect-scalar)`, `(collect-distinct)`).
* **`ScmLiteral`**: Literal constants: `ScmInt` (64-bit integer), `ScmFloat` (64-bit float), `ScmBool` (`#t`/`#f`), and `ScmString`.
* **`ScmSymbol`**: Symbolic identifiers (`node`, `walk`, `:age`).
* **`ScmList`**: Generic homoiconic S-expression forms (`(+ 2 (* 3 4))`).

Attached to AST nodes during property inference is `AlgebraicSignature` (`org.impulsegraph.compiler.ast.algebra`), which tracks:
* **Interval bounds**: `IntervalBound` (`minInt`/`maxInt`, `minFloat`/`maxFloat`).
* **Morphism classifications**: `GENERAL`, `FUNCTIONAL` ($\text{OutDegree} \le 1$), `INJECTIVE` ($\text{InDegree} \le 1$), `BIJECTIVE` ($\text{InDegree} \le 1 \land \text{OutDegree} \le 1$).
* **Algebraic properties**: Semigroup, Monoid, Commutative, Idempotent, Distributive, Absorbing.
* **Homomorphism flags**: Commutes with max/min/sum, distributes over OR/AND.

### Two-Stage Optimization Pipeline

The compiler organizes optimization passes into two strictly separated stages:

#### Stage 1: Snapshot-Agnostic / Pre-Bind Optimization Pipeline (`org.impulsegraph.compiler.passes.stage1`)
Executes on raw AST without requiring an active `ImpulseGraphSnapshot`. Compiles raw queries into reusable, snapshot-independent `QueryObject` instances cached across snapshot swaps.
1. **`PreBindValidator`**: Syntactic and structural validation. Verifies non-empty programs, valid walk relations, and valid CEL syntax prior to physical binding.
2. **`AstNormalizationPass`**: Standardizes canonical AST structure, flattens associative nested operations, and normalizes walk steps.
3. **`ParameterBindingPass`**: Injects compile-time parameter values (`@paramName -> value`) into CEL expressions prior to property inference.
4. **`ConstantFoldingPass` & `CelAstOptimizer`**: Evaluates compile-time literals, double negations, and simplifies analytical math expressions across 54 math functions.
5. **`CelPredicateFlatteningPass`**: Lowers embedded CEL expressions into structured ImpScheme vector predicate ASTs (`vec-cmp-*`, `mask-*`).
6. **`AlgebraicTypeInferencePass`**: Infers bottom-up `AlgebraicSignature` annotations across AST nodes, propagating interval bounds, monotonicity, and morphism classes.
7. **`ZoneMapPruningPass`**: Evaluates predicates against BRIN attribute zone maps:
   - If predicate is provably FALSE ($u.age > 250$ when $\max(age) = 114$), prunes the entire traversal branch to an empty result.
   - If predicate is provably TRUE for 100% of rows, strips the filter from the inner traversal loop for zero runtime overhead.
8. **`MonotonicHomomorphismPass`**: Exploits join-semilattice homomorphisms to commute monotonic functions across aggregations (e.g. $\max(f(X)) \to f(\max(X))$ when $f$ is strictly monotonic increasing), computing $f$ once instead of $N$ times in the inner loop.

#### Stage 2: Snapshot-Bound Optimization Pipeline (`org.impulsegraph.compiler.passes.stage2`)
Executes when binding a `QueryObject` to a target `ImpulseGraphSnapshot`, specializing the AST to physical snapshot schema and statistics.
1. **`BindTimeValidator`**: Verifies that all logical relations, domain types, and property columns exist in the target snapshot catalog.
2. **`KernelFusionPass`**: Multi-hop traversal fusion. Rewrites consecutive forward CSR walks `(csr-walk rel1)` and `(csr-walk rel2)` into a fused 2-hop traversal `(csr-walk-2hop rel1 rel2)` when intermediate relation multiplicity is below `OptimizerConfig.FUSED_2HOP_MAX_MULTIPLICITY_THRESHOLD` ($1.5$), eliminating intermediate frontier bitset allocations.
3. **`DirectionSelectionPass`**: Cost-based walk direction planner. Selects between Forward CSR (`OP_CSR_WALK`) and Reverse CSC (`OP_CSC_WALK`) based on relation degree statistics and CSC matrix presence.
4. **`FilterPushdownPass`**: Interleaves high-selectivity vector property filters directly into graph walk steps as shader predicates (`OP_CSR_WALK_FILTERED` / stream shader).
5. **`InjectiveDeduplicationBypassPass`**: Bypasses expensive deduplication hashing when relation mapping is provably injective ($\text{InDegree} \le 1$), rewriting distinct collections into direct bitset/vector collects.
6. **`VirtualRelationDecompositionPass`**: Decomposes virtual super-relations (coproduct partitions) into physical CSR/CSC relation strides and eliminates non-matching partitions using zone maps.
7. **`PhysicalBindingPass`** *(in `impulse-vm`)*: Resolves logical relation string names into physical 16-bit catalog integer IDs.
8. **`RegisterAllocationPass`** *(in `impulse-vm`)*: Linear-scan register allocator allocating ImpulseVM registers (`R0`..`R63`) with ping-pong reuse across sequential traversal steps.

### Bytecode Emission & Disassembly
* **`ImpOpsBytecodeEmitter`** (`impulse-vm` / `org.impulsegraph.compiler.emitter`): Emits 8-byte 64-bit aligned instructions directly into off-heap memory segments via Java FFM (`Arena.allocate`), returning `EmittedProgram` or `CompiledQuery`.
* **`ImpAsmDisassembler`** (`impulse-compiler` / `org.impulsegraph.compiler.emitter`): Generates canonical human-readable `.impas` assembly disassembly from `EmittedProgram` instances.

---

## 2. Compiler Tracing & Pass Inspection

Enable compiler tracing programmatically or via CLI flags to inspect AST transformations across every pass:

```java
CompilerOptions options = CompilerOptions.builder()
        .withTracing(true)
        .build();

PassTracer tracer = new PassTracer(options);
CompilerContext ctx = new CompilerContext(snapshot, options, tracer);

ImpScmNode ast1 = ctx.executePass(AlgebraicTypeInferencePass.INSTANCE, inputAst);
ImpScmNode ast2 = ctx.executePass(ZoneMapPruningPass.INSTANCE, ast1);

// Generate diagnostic multi-pass report
System.out.println(tracer.generateTraceReport());
```

Sample Trace Output:
```
=========================================================================
                 IMPULSE COMPILER MULTI-PASS TRACE REPORT               
=========================================================================
Pass #01: AlgebraicTypeInferencePass       [0.014 ms]
  AST Output:
    (program
      (csr-walk "users" (shader (cel-expr (vec-cmp-gt (get-attr node "age") 250))))
      (collect-bitset))
-------------------------------------------------------------------------
Pass #02: ZoneMapPruningPass               [0.005 ms]
  AST Output:
    (program
      (collect-bitset))
-------------------------------------------------------------------------
Total Compilation Pipeline Duration: 0.019 ms across 2 passes
=========================================================================
```

---

## 3. Bytecode Disassembly (`ImpAsm`)

Disassemble compiled off-heap bytecode programs into canonical human-readable `.impas` assembly format:

```java
try (Arena arena = Arena.ofConfined()) {
    // Compile and emit 64-bit aligned off-heap impOps instructions
    ImpOpsBytecodeEmitter.EmittedProgram program = ImpOpsBytecodeEmitter.emit(optimizedAst, snapshot, arena);
    
    // Disassemble into canonical .impas assembly text
    String disassembly = ImpAsmDisassembler.disassemble(program);
    System.out.println(disassembly);
}
```

Sample output disassembly (`.impas`):
```impas
; =========================================================================
;                  IMPULSE VM BYTECODE DISASSEMBLY (.impas)               
; =========================================================================
.version 0.9.0
.instructions 4

  0x0000:  OP_CSC_WALK              flags=0x03, dst=R1 , payload=0x00240000 ; Walk src=R0 -> dst=R1 via rel[36] ("CtD") [seed-inlined] [early-exit]
  0x0001:  OP_CSR_WALK              flags=0x01, dst=R0 , payload=0x00080001 ; Walk src=R1 -> dst=R0 via rel[8] ("CrC") [early-exit]
  0x0002:  OP_COLLECT_BITSET        flags=0x00, dst=R0 , payload=0x00000000 ; Collect active result bitset from R0
  0x0003:  OP_HALT                  flags=0x00, dst=R0 , payload=0x00000000 ; Execution complete
; =========================================================================
```

---

## 4. Query Registry & SRE-Grade Production Lifecycle

The compiler suite provides production-grade query management, cached execution, and pre-flight validation gates for atomic Blue/Green snapshot swaps:

```java
// 1. Initialize query engine and registry
QueryRegistry registry = new QueryRegistry();

// 2. Register named queries at application startup (Stage 1 validation & optimization)
ImpScmNode rawAst = ImpScmParser.parse("(program (csr-walk \"userToGroup\") (collect-bitset))");
QueryObject queryObject = registry.register("userGroups", "MATCH (u:User)-[:MEMBER_OF]->(g:Group) RETURN g", rawAst);

// 3. Pre-flight Blue/Green snapshot deployment gate
// Validates all registered queries against a candidate snapshot before traffic cutover.
// Rejects deployment immediately if any schema invariant or relation is missing.
registry.validateSnapshot(candidateSnapshot);

// 4. Fast-path execution with plan caching
try (Arena arena = Arena.ofConfined()) {
    CompiledQuery plan = registry.engine().compileStage2(queryObject, activeSnapshot, arena, CompilerOptions.DEFAULT);
    // Execute plan on ImpulseVM...
}

// 5. Generate diagnostic explanation report
String report = QueryExplainer.explain(queryObject, activeSnapshot);
System.out.println(report);
```

### Key Lifecycle Components
* **`QueryCompilerEngine` (`org.impulsegraph.compiler.registry`)**: Coordinates Stage 1 (`compileStage1`) and Stage 2 (`compileStage2`) pipelines while collecting execution telemetry via `CompilerMetricsRecorder` and `PlanCacheMetricsRecorder`.
* **`QueryObject` (`org.impulsegraph.compiler.registry`)**: Thread-safe encapsulation of the Stage 1 normalized AST, maintaining a lock-free plan cache (`ConcurrentHashMap<ImpulseGraphSnapshot, CompiledQuery>`) keyed by active snapshot instance.
* **`QueryRegistry` (`org.impulsegraph.compiler.registry`)**: Central catalog of named queries. Enforces pre-flight schema compatibility during Blue/Green snapshot deployments via `validateSnapshot(candidateSnapshot)`.
* **`QueryExplainer` (`org.impulsegraph.compiler.explain`)**: Generates comprehensive multi-pass diagnostic reports combining AST transformation traces and `.impas` disassembly.

---

## 5. Microbenchmark Performance

Measured on Apple Silicon M-series hardware with Java 21 LTS FFM off-heap memory mapping:

```
----------------------------------------------------------------------------------------
 7-Stage AST Compilation Pipeline:
   • Throughput:                 1,107,677 compilations / second
   • Mean Latency:               0.892 µs (892 ns)
----------------------------------------------------------------------------------------
 MethodHandle JIT Combinator Tree Generation (8 instructions):
   • Mean Latency:               8.537 µs
----------------------------------------------------------------------------------------
 Total End-to-End JIT Pipeline (AST -> Optimization -> Bytecode -> MethodHandle):
   • Total Latency:              < 10 µs per query
----------------------------------------------------------------------------------------
```

