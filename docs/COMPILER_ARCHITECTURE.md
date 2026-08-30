# Impulse Graph Java Compiler Suite (`impulse-compiler`)

The `impulse-compiler` module is a pure Java 21+ implementation of the **multi-pass optimizing ImpScheme (ImpScm), openCypher, and CEL compiler pipeline**, generating zero-allocation `impOps` binary bytecode for direct execution via Java Foreign Function & Memory (FFM) and Vector API.

---

## 1. Features & Architecture Overview

* **Polyglot Query Frontends**:
  - **openCypher**: `MATCH ... WHERE ... RETURN` analytical graph queries with strict Set semantics, typed edge walks, and bounded traversals.
  - **Google CEL**: Pratt recursive-descent parser compiling attribute filters into 512-bit JDK Vector API SIMD tiles.
  - **ImpScheme (`.impscm`)**: Homoiconic S-Expression AST intermediate representation.

* **Multi-Stage Optimization Pipeline**:
  1. **`PreBindValidator`**: Verifies domain anchoring, relation existence, and schema type compatibility.
  2. **`AlgebraicTypeInferencePass`**: Infers algebraic shapes ($\text{Scalar} \to \text{BitSet} \to \text{Stream}$) and marks monotonic invariants.
  3. **`ZoneMapPruningPass`**: Evaluates BRIN attribute statistics:
     - If predicate is provably FALSE ($u.age > 250$ when $\max(age) = 114$), prunes the entire traversal branch.
     - If predicate is provably TRUE for 100% of rows, strips the filter from the inner traversal loop for zero runtime overhead.
  4. **`MonotonicHomomorphismPass`**: Hoists monotonic transcendental calculations (e.g. $\log(x)$, $\sqrt{x}$) outside the loop body.
  5. **`InjectiveDeduplicationBypassPass`**: Bypasses expensive deduplication hashing when relation mapping is provably injective ($1:1$ or $N:1$).
  6. **`VirtualRelationDecompositionPass`**: Decomposes virtual relations and partitions into physical CSR/CSC relation strides.
  7. **`ConstantFoldingPass` & `CelAstOptimizer`**: Folds constant literals and evaluates analytical math functions ($46$ unary/binary math functions like `sqrt`, `exp`, `log`, `safeDiv`, `isFinite`).
  8. **`ParameterBindingPass`**: Binds known parameter values and compiles static execution templates.
  9. **`DirectionSelectionPass`**: Cost-based planner selecting between Forward CSR (`OP_CSR_WALK`) and Reverse CSC (`OP_CSC_WALK`).
  10. **`PhysicalBindingPass`**: Resolves relation strings into physical catalog integer IDs.
  11. **`RegisterAllocationPass`**: Linear-scan register allocator allocating `R0`..`R63` with ping-pong reuse.

* **Bytecode Emission & Disassembly**:
  - **`ImpOpsBytecodeEmitter`**: Emits native 64-bit aligned opcode blocks directly into off-heap memory segments.
  - **`ImpAsmDisassembler`**: Generates human-readable disassembly text (`.impas`).

---

## 2. Compiler Tracing & Pass Inspection

Enable compiler tracing programmatically or via CLI flags to inspect AST transformations across every pass:

```java
CompilerOptions options = CompilerOptions.builder()
        .withTracing(true)
        .build();

CompilerContext ctx = new CompilerContext(snapshot, options, new PassTracer(options));
ImpScmNode optimizedAst = ctx.executePass(ZoneMapPruningPass.INSTANCE, inputAst);
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

Disassemble compiled bytecode segments into `.impas` assembly format:

```java
MemorySegment bytecode = ...; // Compiled 64-bit aligned impOps bytecode
String disassembly = ImpAsmDisassembler.disassemble(bytecode, instructionCount);
System.out.println(disassembly);
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

## 4. Microbenchmark Performance

Measured on Apple Silicon M-series hardware with Java 25 FFM off-heap memory mapping:

```
----------------------------------------------------------------------------------------
 Warmed Compilation Throughput:  549,040 compilations / second
   • p50 (Median Latency):        1,750 ns   (1.750 µs)
   • Mean (Average Latency):      1,787 ns   (1.787 µs)
   • p90 Latency:                 1,875 ns   (1.875 µs)
   • p99 Latency:                 2,083 ns   (2.083 µs)
----------------------------------------------------------------------------------------
 Query Execution Throughput:      74,454 queries / second
   • Mean Latency:                 9.958 µs
----------------------------------------------------------------------------------------
```

