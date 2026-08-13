# Impulse Graph Engine — Java v1.0.0 Production Readiness TODO Roadmap

This document outlines the detailed engineering task checklist required to bring **`impulse-graph-java`** from initial public preview (v0.9.0) to **v1.0.0 major production readiness**, aligned with the master ecosystem roadmap ([`impulse-website/docs/roadmap.md`](file:///Users/jesse/impulse/impulse-website/docs/roadmap.md)).

---

## 🎯 v1.0.0 Key Goals for Java Ecosystem

1. **Java 21 LTS Baseline & FFM Engine**: 100% off-heap memory mapping (`java.lang.foreign.MemorySegment`) running on Java 21 LTS+, with forward compatibility for Java 25+ Project Valhalla `value class` (naked structs).
2. **1x – 2x Performance Parity with C++**: JVM FFM & Vector API execution must operate within 1x–2x latency of native C++20 (`impulse-cpp`), with zero GC pauses during query execution.
3. **Zero Third-Party Runtime Dependencies**: `impulse-api` and `impulse-core` maintain **0 external dependencies** (< 350 KB total jar footprint).
4. **Complete Build-Time AOT & Codegen Pipeline**: `impulse-maven-plugin`, `impulse-gradle-plugin`, and `impulse-codegen` generating strongly-typed Records/POJOs/Value Classes and compiling `@ImpKQuery` annotations at build time.

---

## 📋 TODO Checklist by Module

### 1. `impulse-api` (Public API & Annotations)
- [x] Establish Java 21 LTS baseline target in `pom.xml`.
- [x] Create `@ImpKQuery`, `@ImpLogRule`, and `@ImpulseRepository` annotations in `io.impulse.graph.annotations.*`.
- [ ] Finalize fluent query builder API (`GraphView`, `ImpulseQueryBuilder`) with 100% type safety.
- [ ] Add zero-dependency exception hierarchy (`ImpulseException`, `ImpulseVMException`, `SnapshotCorruptedException`).
- [ ] Ensure 0 third-party transitive dependencies on build classpath.

### 2. `impulse-core` (Off-Heap Engine & Vector API VM)
- [ ] Complete off-heap zero-copy snapshot loader (`MemorySegment` mmap) matching Spec v0.9.0 Page 0 alignment and 128-byte hardware bounds.
- [ ] Implement pure Java 25 / Java 21 JDK Vector API (`jdk.incubator.vector`) SIMD acceleration loops for GraphBLAS matrix math (`OP_MXV`, `OP_CSR_WALK`).
- [ ] Implement `MethodHandle` JIT combinators for `ImpulseVM` bytecode execution (`0x00`..`0x72` opcodes).
- [ ] Validate 100% pass rate against all spec test vectors (`tc01`..`tc36` and `vm-impas`).
- [ ] Benchmark execution latency to verify **1x–2x parity vs C++20 kernel** under JMH harnesses.

### 3. `impulse-spec` (Format Specification Encoders/Decoders)
- [ ] Validate fixed 4KB Page 0 header parsing, `IMPS` magic byte verification (`0x494D5053`), and SHA-256 integrity checksums.
- [ ] Implement global string table pool (Section 2) decoder.
- [ ] Implement Structure-of-Arrays (SoA) attribute descriptors for `FixedString(N)`, `VarString`, `TimestampMicro`, and primitive types.

### 4. `impulse-codegen` & `.imps.schema.yaml`
- [ ] Implement `.imps.schema.yaml` schema parser and validation engine.
- [ ] Build Java 21 Record and POJO DTO code generators based on snapshot schemas.
- [ ] Implement optional Java 25+ Project Valhalla `value class` (naked struct) generator.
- [ ] Add compile-time query validator checking `@ImpKQuery` syntax against `.imps.schema.yaml`.

### 5. `impulse-maven-plugin` & `impulse-gradle-plugin`
- [ ] Implement Maven plugin mojo (`ImpulseCompileMojo`) for compiling `@ImpKQuery` annotations during `mvn compile`.
- [ ] Implement Gradle task (`ImpulseCompileTask`) for compiling `@ImpKQuery` annotations during `./gradlew build`.
- [ ] Integrate native Rust `libimpulse_compiler` binaries (`.so`/`.dylib`/`.dll`) for build-time AOT compilation.

### 6. `impulse-kotlin` & `impulse-scala`
- [ ] **`impulse-kotlin`**: Add Kotlin coroutine extensions (`suspend` query functions, `Flow<NodeId>` streams).
- [ ] **`impulse-scala`**: Implement Scala 3 type-safe GraphBLAS matrix algebra DSL wrappers.

### 7. Documentation & Supply Chain Security (SLSA L3)
- [ ] Automated Javadoc generation for `impulse-api`, `impulse-core`, and `impulse-spec` integrated into hosted docs portal (`docs.impulsegraph.io`).
- [ ] Configure GitHub Actions workflow for automated GPG signing and deployment to **Maven Central**.
- [ ] Enable SLSA Level 3 build provenance attestations (`actions/attest-build-provenance`).

---

## 🚀 Post-v1.0.0 Forward Milestone Targets (v1.1.0+)

* **v1.1.0**: **Java Blue/Green Hot Snapshot Reloading** (atomic off-heap pointer swapping under heavy query load without JVM GC pauses).
* **v1.2.0**: **Spring Boot Starter (`impulse-spring-boot-starter`)** featuring Spring Data repositories with `@ImpK` / `@ImpLog` AST injection.
