# Impulse Graph Engine — Java v1.0.0 Production Readiness TODO Roadmap

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

This document outlines the detailed engineering task checklist required to bring **`impulse-graph-java`** from initial public preview (v0.9.0) to **v1.0.0 major production readiness**, aligned with the master ecosystem roadmap ([`impulse-website/docs/roadmap.md`](file:///Users/jesse/impulse/impulse-website/docs/roadmap.md)).

---

## 🎯 v1.0.0 Key Goals for Java Ecosystem

1. **Java 21 LTS Baseline & FFM Engine**: 100% off-heap memory mapping (`java.lang.foreign.MemorySegment`) running on Java 21 LTS (with incubator `jdk.incubator.vector` SIMD acceleration), with forward compatibility for Java 22–25+ and Project Valhalla `value class` (naked structs).
2. **1x – 2x Performance Parity with C++**: JVM FFM & Vector API execution must operate within 1x–2x latency of native C++20 (`impulse-cpp`), with zero GC pauses during query execution.
3. **Zero Third-Party Runtime Dependencies**: `impulse-api`, `impulse-spec`, `impulse-storage`, `impulse-vm`, and `impulse-compiler` maintain **0 external dependencies** (< 350 KB total jar footprint).
4. **Complete Build-Time AOT & Codegen Pipeline**: `impulse-maven-plugin`, `impulse-gradle-plugin`, and `impulse-codegen` generating strongly-typed QueryBuilders/Records and pre-compiling static queries into ImpScheme (`.impscm`) ASTs at build time.

---

## 📋 TODO Checklist by Module

### 1. `impulse-api` (Public API Primitives & Universal Traversal)
- [x] Establish Java 21 LTS baseline target in `pom.xml` (FFM `java.lang.foreign`, `jdk.incubator.vector`, and preview features enabled).
- [x] Maintain 0 third-party runtime dependencies across core engine modules (`impulse-api`, `impulse-spec`, `impulse-storage`, `impulse-vm`, `impulse-compiler`).
- [x] Enforce Spotless code formatting (`spotless-maven-plugin`) for Java and Clojure source trees during Maven `verify`.
- [x] **Universal Kleisli Frontier Traversal API (`DomainView`, `Traversal<T>`)**:
  - Domain-bound traversal entrypoint: `snap.domain("User").from(seed).out("relation").filter("age > 25").collect()`.
  - Frontier monoidic reducers (`Reducer.OR`, `MIN_PLUS`, `SUM`, `MAX`).
  - Terminal extractors: `collect()`, `toBitSet()`, `toList()`, `toSet()`, `toKeyList()`, `toKeySet()`, `count()`, `toImpAsm()`.
  - Fixed-point loop primitives (`repeatUntilStable()`, `repeat(n)`).
- [x] **ImpScheme AST Fluent Query Builder (`ImpulseQueryBuilder`)**: Construct canonical `.impscm` S-Expression AST pipelines (`ScmProgram`).
- [ ] Create `@ImpKQuery`, `@ImpLogRule`, and `@ImpulseRepository` annotations in `org.impulsegraph.api.annotations.*`.
- [ ] Add zero-dependency exception hierarchy (`ImpulseException`, `ImpulseVMException`, `SnapshotCorruptedException`).
- [ ] Add Arrow Columnar and tabular row streaming extractors (`collectRows()`) to `Traversal<T>`.

### 2. `impulse-storage` & `impulse-vm` (Off-Heap Engine, Vector API & ImpScheme VM)
- [x] Complete off-heap zero-copy snapshot loader (`MemorySegment` mmap) matching Spec v0.9.0 Page 0 alignment and 128-byte hardware bounds.
- [x] Implement self-contained zero-dependency **Google CEL (Common Expression Language)** Pratt parser and optimizer with analytical vector math extensions.
- [x] Implement Level 2 **`MethodHandle` JIT Combinators** (`MethodHandles.foldArguments()`, `filterArguments()`, `VectorOperators` bindings) for unrolling `impOps` into HotSpot native execution.
- [x] **Configurable Primitive Node ID Widths (16, 32, 64-bit)**: Support for per-domain variable ID widths (`uint16_t`, `uint32_t`, `uint64_t`) and edge offset widths.
- [x] **Multi-Stage ImpScheme (`.impscm`) Compiler Pipeline**:
  - Stage 1 (Snapshot-Agnostic): `PreBindValidator`, `AstNormalizationPass`, `ConstantFoldingPass`, `CelPredicateFlatteningPass`, `AlgebraicTypeInferencePass`.
  - Stage 2 (Snapshot-Bound): `DirectionSelectionPass` (dynamic CSR vs CSC selection), `FilterPushdownPass`, `InjectiveDeduplicationBypassPass`, `KernelFusionPass`, `PhysicalBindingPass`, `RegisterAllocationPass`, `ImpOpsBytecodeEmitter`.
- [x] **Zero-Downtime Blue/Green Hot Snapshot Reloading (`SnapshotSwapManager`)**: Atomic off-heap pointer swapping under heavy query concurrency with active epoch reader tracking, query draining, and clean off-heap `Arena` closure without GC pauses (`BlueGreenDrainAndUnloadTest`).
- [x] Java 21 LTS Vector API (`jdk.incubator.vector`) SIMD acceleration for `OffHeapBitSet` bitwise ops (`LongVector.SPECIES_PREFERRED`) and fused edge attribute filtering (`IntVector`, `FloatVector`).
- [ ] Multi-Layout Execution Kernels: CSR (Push) and CSC (Pull via `OP_CSC_WALK`) complete; COO (Edge stream) and DENSE (512-bit AVX bitmatrix) remaining.
- [ ] **Ultra-Low-Latency Intra-Opcode Parallel Engine (`ImpulseCarrierThreadPool`)**:
  - Replace default executor with pre-allocated, pre-warmed static carrier platform threads.
  - Implement deterministic range slicing over `MemorySegment` off-heap buffers with 0 heap object allocations per query.
  - Implement dynamic single-cycle switching between sequential SIMD (`max_dop == 1`) and multi-carrier execution (`max_dop > 1`).
- [~] ~~**Live Ingestion & Streaming Mutation Architecture ([`docs/ingestion-strategies.md`](file:///Users/jesse/impulse/impulse-graph-java/docs/ingestion-strategies.md))**:~~ **SCRAPPED**. Impulse is strictly read-only per AGENTS.md §3.4 and §3.16. Ingestion is handled out-of-band by offline/streaming snapshot builders.
- [ ] Validate 100% pass rate against all spec test vectors (`tc01`..`tc36` and `vm-impas` via `JavaVmPolyglotAssemblyVerifierTest`; currently 301/307 passed, 95.5% opcode coverage).
- [ ] Benchmark execution latency to verify **1x–2x parity vs C++20 kernel** under JMH harnesses (`BfsVmJmhBenchmark`, `HetionetVmTraversalBenchmarkTest`).

### 3. `impulse-spec` (Format Specification Encoders/Decoders)
- [x] Validate fixed 4KB Page 0 header parsing, `IMPS` magic byte verification (`0x494D5053`), and SHA-256 integrity checksums.
- [x] Implement global string table pool (Section 2) decoder and primary key catalog mapping.
- [x] Implement Structure-of-Arrays (SoA) attribute descriptors for primitive types (`INT8`..`FLOAT64`), `FIXED_BYTES`, `VAR_STRING`, `TIMESTAMP_MS`/`TIMESTAMP_NS`, and multidimensional fixed vectors.
- [x] Binary snapshot layout constants conforming to Normative Format Specification v0.9.0 (`ImpulseLayoutsV0_9`).

### 4. `impulse-codegen` & `.imps.schema.yaml`
- [x] Implement `.imps.schema.yaml` schema parser and validation engine in `impulse-codegen` (`ManifestModel`, `GeneratorMain`).
- [x] Generate strongly-typed QueryBuilder classes (`UserQueryBuilder`) extending `TypedQueryBuilder` with compile-time type-checked traversal methods.
- [ ] Build Java Record and POJO DTO code generators based on snapshot schemas (`User_`, `ShipmentEdge_`, `Customer_`).
- [ ] Implement optional Java Project Valhalla `value class` (naked struct) generator.
- [ ] Implement `impulse-apt` JSR-269 Java Annotation Processor for in-IDE red squigglies and real-time CEL validation.

### 5. `impulse-maven-plugin` & `impulse-gradle-plugin`
- [x] Implement build-time code generation Mojos and Gradle tasks (`GenerateQueryBuildersMojo`, `GenerateQueryBuildersTask`) producing typed query builders during `mvn compile` and `./gradlew build`.
- [ ] Implement `validate-cel` Mojo / Gradle task for compile-time CEL query verification.
- [ ] Implement `aot-compile` Mojo pre-compiling static queries to **`ImpScheme` S-Expression ASTs (`.impscm`)** for 0ns runtime parse overhead.
- [ ] Implement `generate-schema` Mojo reverse-engineering schema definitions from binary `.imps` snapshot files.

### 6. JVM Language Extensions (`impulse-kotlin`, `impulse-scala`, `impulse-clojure`)
- [x] **`impulse-kotlin`**: Coroutine extensions (`suspend fun executeAsync`, `suspend fun awaitDrainedAsync`), `@JvmInline value class NodeId`, and idiomatic Kotlin DSL builder (`ImpulseExtensions.kt`, `ImpulseKotlinDslTest.kt`).
- [ ] **`impulse-kotlin`**: Add reactive `Flow<NodeId>` stream emitters.
- [x] **`impulse-scala`**: Scala 3 type-safe query DSL with infix notation, contextual abstractions (`?=>`), and domain types (`ImpulseDsl.scala`, `ImpulseScalaDslTest.scala`).
- [x] **`impulse-clojure`**: Clojure functional query wrappers and traversal primitives (`org.impulsegraph.core`, `core_test.clj`).

### 7. Documentation, Supply Chain Security (SLSA L3) & Package Publishing
- [x] Configure GitHub Actions workflow (`maven-publish.yml`) for automated GPG signing and deployment to **Maven Central** via `central-publishing-maven-plugin`.
- [x] Enable SLSA Level 3 build provenance attestations (`actions/attest-build-provenance` on published JARs) and Sigstore / Cosign keyless signatures (`release.yml`).
- [ ] Automated Javadoc generation for `impulse-api`, `impulse-storage`, `impulse-vm`, and `impulse-spec` integrated into hosted docs portal (`docs.impulsegraph.io`).

---

## 🚀 Post-v1.0.0 Forward Milestone Targets (v1.1.0+)

* **v1.1.0**: **Enterprise Cluster Hot Snapshot Sync** (distributed multi-node blue/green snapshot swap coordination in `impulse-platform`).
* **v1.2.0**: **Spring Boot Starter (`impulse-spring-boot-starter`)** in `impulse-platform` featuring Spring Data repositories with `@ImpK` / `@ImpLog` AST injection.
