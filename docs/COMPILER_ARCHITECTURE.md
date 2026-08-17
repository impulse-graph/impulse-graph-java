# Impulse Graph Java Compiler Suite (`impulse-compiler`)

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

The `impulse-compiler` module is a pure Java 25 implementation of the **7-stage optimizing ImpScheme (ImpScm), openCypher, and CEL compiler pipeline**, generating zero-allocation `impOps` binary bytecode for direct execution via Java 25 Foreign Function & Memory (FFM) and Vector API.

---

## 1. Features & Invariants

* **Polyglot Query Frontends**:
  - **openCypher**: `MATCH ... WHERE ... RETURN` analytical graph queries with strict Set semantics, typed edge walks, and bounded traversals.
  - **Google CEL**: Pratt recursive-descent parser compiling attribute filters into 512-bit JDK Vector API SIMD tiles.
  - **ImpScheme (`.impscm`)**: Homoiconic S-Expression AST intermediate representation.
* **7-Stage Optimization Passes**:
  1. `PreBindValidator`
  2. `ParameterBindingPass`
  3. `KernelFusionPass` (2-hop walk fusion into direct single-instruction kernels)
  4. `DirectionSelectionPass` (automated Forward CSR vs Reverse CSC access planning)
  5. `AlgebraicTypeInferencePass` (frontier type analysis)
  6. `PhysicalBindingPass` (zero-copy memory segment binding)
  7. `RegisterAllocationPass` (linear-scan register allocator with ping-pong caching)
* **Bytecode Emission**:
  - `ImpOpsBytecodeEmitter`: Emits native 64-bit aligned opcode blocks directly into off-heap memory segments.
  - `ImpAsmDisassembler`: Generates human-readable disassembly text (`.impas`).

---

## 2. Microbenchmark Performance (JMH 1.37 on Java 25)

```
Benchmark                                                              Mode  Cnt    Score      Error  Units
HetionetScreen1AllDiseasesJmhBenchmark.screen1_single_disease_point    avgt    5   16.297 ±   10.901  us/op  (73,000 QPS)
HetionetScreen1AllDiseasesJmhBenchmark.screen1_all_134_sequential      avgt    5 3032.020 ±  401.013  us/op  (44,200 screens/s)
HetionetScreen1AllDiseasesJmhBenchmark.screen1_all_134_parallel        avgt    5  597.953 ±   40.164  us/op  (224,000 screens/s)
```
