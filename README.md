# Impulse Graph Engine — Java Workspace (`impulse-graph-java`)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

High-performance, off-heap **Java 21 LTS+ Foreign Function & Memory (FFM) API** engine and multi-module ecosystem for the **Impulse Graph Engine**.

---

## 🚀 Key Architectural Advantages

* **Pure Java 21 LTS+ Off-Heap Engine**: Memory-maps `.imps` v0.9.0 binary snapshot files off-heap via `java.lang.foreign.MemorySegment` with **zero Garbage Collection (GC) pauses**.
* **Zero Third-Party Runtime Dependencies**: `impulse-api` and `impulse-core` maintain **0 external dependencies** for maximum enterprise container security, zero CVE bloat, and fast startup.
* **JDK Vector API Acceleration**: Dynamically generates AVX-512 and ARM Neon SIMD assembly instructions at JVM JIT compile time via `jdk.incubator.vector`.
* **Project Valhalla Value Class Support**: Optional forward-compatible codegen targeting Java 25+ identityless `value class` ("naked structs") for flat memory allocation, zero object header overhead, and zero GC pressure.
* **Enterprise Security & Crash Safety**: Runs 100% inside the standard JVM sandbox, avoiding native `dlopen` execution blocks (`noexec`) and preventing C++ `SIGSEGV` process crashes.

---

## 📦 Artifact Partitioning Matrix

| Artifact / Module | Size | Native Binaries? | Third-Party Deps | Purpose & Description |
| :--- | :--- | :--- | :--- | :--- |
| **`impulse-api`** | ~50 KB | ❌ None | **0** | Lightweight public interfaces, annotations (`@ImpKQuery`, `@ImpLogRule`, `@ImpulseRepository`), and domain types. |
| **`impulse-core`** | ~300 KB | ❌ None | **0** | Pure Java 25 off-heap snapshot engine and `ImpulseVM` bytecode interpreter. |
| **`impulse-spec`** | ~100 KB | ❌ None | **0** | Binary Snapshot v0.9.0 header encoders, decoders, and structural spec definitions. |
| **`impulse-kotlin`** | ~200 KB | ❌ None | Kotlin Stdlib | Idiomatic Kotlin extensions, coroutines support, and flow streams. |
| **`impulse-scala`** | ~250 KB | ❌ None | Scala 3 Library | Scala 3 type-safe GraphBLAS matrix math API wrappers. |
| **`impulse-compiler-native`**| ~15 MB | ✅ Embedded | **0** | Optional FFM bridge to Rust `libimpulse_compiler` shared objects (`.so`/`.dylib`/`.dll`) for dynamic runtime `ImpK` text compilation. |
| **`impulse-maven-plugin`** | ~1 MB | ✅ Embedded | Maven Plugin API | Build-time Maven plugin for compiling `@ImpKQuery` annotations and `.impk` scripts to binary `.impb` bytecode (`mvn compile`). |
| **`impulse-gradle-plugin`** | ~1 MB | ✅ Embedded | Gradle API | Build-time Gradle plugin for compiling `@ImpKQuery` annotations and `.impk` scripts to binary `.impb` bytecode (`./gradlew build`). |

---

## 🔄 Execution Modes

### Mode 1: Pure Java 25 Off-Heap Mode (Enterprise Default)
Runs 100% inside the JVM. Queries are compiled ahead-of-time (AOT) at build time via `impulse-maven-plugin` or `impulse-gradle-plugin` into binary `.impb` bytecode files. At runtime, `impulse-core` executes bytecode off-heap with zero native shared libraries in production.

### Mode 2: Build-Time AOT Script Compilation (`@ImpKQuery`)
Developers write inline `ImpK` DSL queries directly inside Java annotations:

```java
package com.mycompany.repository;

import io.impulse.graph.annotations.ImpKQuery;
import io.impulse.graph.annotations.ImpulseRepository;

@ImpulseRepository
public interface FollowerRepository {

    @ImpKQuery("""
        MATCH (u:User)-[:FOLLOWS]->(f:User)
        WHERE u.id == $startNode
        RETURN f.id
        """)
    long[] findFollowers(long startNode);
}
```

During `mvn compile` or `./gradlew build`, `impulse-maven-plugin` / `impulse-gradle-plugin` compiles the text script to binary `impOps` bytecode (`.impb`) and generates an optimized implementation running off-heap on `impulse-core`.

### Mode 3: Dynamic Runtime Compilation (`impulse-compiler-native`)
If an interactive application (e.g., ad-hoc web query console) requires compiling dynamic `ImpK` text scripts at runtime, include `impulse-compiler-native`. Java 25 FFM (`Linker.nativeLinker()`) dynamically downcalls to the embedded Rust compiler shared library in memory without JNI.

---

## 🛠️ Prerequisites & Build Instructions

* **JDK 21 LTS+** (with `--enable-preview` and `--add-modules jdk.incubator.vector`)
* **Maven 3.9+**

### Building and Running Tests
```bash
mvn clean test
```

---

## 📄 License

Apache License 2.0. See [LICENSE](LICENSE) for details.
