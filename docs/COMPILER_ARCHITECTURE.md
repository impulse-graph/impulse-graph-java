# Impulse Graph Java Compiler Suite (`impulse-compiler`)

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

The `impulse-compiler` module is a pure Java 21+ implementation of the **7-stage optimizing ImpScheme (ImpScm), openCypher, and CEL compiler pipeline**, generating zero-allocation `impOps` binary bytecode for direct execution via Java 21+ Foreign Function & Memory (FFM) and Vector API.

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
* **Bytecode Emission & Disassembly**:
  - `ImpOpsBytecodeEmitter`: Emits native 64-bit aligned opcode blocks directly into off-heap memory segments.
  - `ImpAsmDisassembler`: Generates human-readable disassembly text (`.impas`).

---

## 2. Bytecode Disassembly & Debugging Flags

To inspect the human-readable `ImpAsm` disassembly for a compiled query:

```java
MemorySegment bytecodeSegment = ...; // Compiled 64-bit aligned impOps bytecode
String disassembly = ImpAsmDisassembler.disassemble(bytecodeSegment, instructionCount);
System.out.println(disassembly);
```

Sample output disassembly (`.impas`):
```impas
; =========================================================================
;                  IMPULSE VM BYTECODE DISASSEMBLY (.impas)               
; =========================================================================
.version 0.9.0
.instructions 4

  0x0000:  OP_CSC_WALK              flags=0x03, dst=R1 , payload=0x00240000 ; Walk src=R0 -> dst=R1 via rel[36] ("CtD")
  0x0001:  OP_CSR_WALK              flags=0x01, dst=R0 , payload=0x00080001 ; Walk src=R1 -> dst=R0 via rel[8] ("CrC")
  0x0002:  OP_COLLECT_BITSET        flags=0x00, dst=R0 , payload=0x00000000 ; Collect active result bitset from R0
  0x0003:  OP_HALT                  flags=0x00, dst=R0 , payload=0x00000000 ; Execution complete
; =========================================================================
```

### Compiler Optimization Toggles
- **`-Dimpulse.compiler.disable_jit=true`**: Bypasses Level 2 `MethodHandle` JIT combinator unrolling and forces emission of standard scalar bytecodes. Guarantees 100% bytecode identity for cross-engine C++ vs Java verification.

---

## 3. Microbenchmark Performance (JMH on Java 21 / Java 25)

```
Benchmark                                                              Mode  Cnt    Score      Error  Units
HetionetScreen1AllDiseasesJmhBenchmark.screen1_single_disease_point    avgt    5   16.297 ±   10.901  us/op  (73,000 QPS)
HetionetScreen1AllDiseasesJmhBenchmark.screen1_all_134_sequential      avgt    5 3032.020 ±  401.013  us/op  (44,200 screens/s)
HetionetScreen1AllDiseasesJmhBenchmark.screen1_all_134_parallel        avgt    5  597.953 ±   40.164  us/op  (224,000 screens/s)
```
