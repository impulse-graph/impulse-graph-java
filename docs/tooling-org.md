# Impulse Graph Tooling & Code Generation Architecture

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

This document outlines the organization of code generation utilities, build plugins, and tooling within the **Impulse Graph Engine** (`impulse-graph`) ecosystem, specifically detailing the Java tooling modules (`impulse-codegen`, `impulse-maven-plugin`, `impulse-gradle-plugin`) and their relationship to the unified Impulse Graph CLI suite (`impulse-graph-tooling`).

---

## 1. Ecosystem Tooling Architecture & Roles

The Impulse Graph ecosystem decouples schema-driven code generation, dataset snapshot compilation, and query DSL pipelines across dedicated modules and repositories:

| Layer / Tool | Primary Repository | Responsibilities | Output / Artifacts |
| :--- | :--- | :--- | :--- |
| **`impulse-codegen`** | `impulse-graph-java` | Core Java schema parser, AST representation, and JavaPoet code generator. Standalone Java CLI entry point via `GeneratorMain`. | Strongly-typed Java POJOs, Domain Views, Query Builders. |
| **`impulse-maven-plugin`** | `impulse-graph-java` | Maven plugin binding code generation to `generate-sources` lifecycle phase. | Generated sources compiled into Maven project classpath. |
| **`impulse-gradle-plugin`** | `impulse-graph-java` | Gradle plugin providing tasks for code generation, CSV snapshot compilation, and snapshot inspection. | Generated sources, test `.imps` snapshots, inspection output. |
| **`impulse-graph-tooling`** | `impulse-graph-tooling` | Unified ecosystem CLI utility (`impulse` / `impulse-graph`) for building, optimizing, validating, inspecting `.imps` snapshots, and assembling/running bytecode. | Native binary snapshot compiler, layout optimizer, and query runner. |
| **`impulse-compiler`** | `impulse-graph-java` | Multi-pass query compiler for openCypher, CEL, and ImpScheme into `impOps` bytecode. | Native off-heap `impOps` bytecode blocks. |
| **`impulse-builder`** | `impulse-graph-java` | Pure Java 21 LTS zero-allocation streaming builder for `.imps` binary snapshots with S3 single-pass semantics. | Immutable binary snapshot files (`.imps`). |

---

## 2. Java Code Generation Modules (`impulse-graph-java`)

To support Maven builds, Gradle builds, and direct CLI execution, Java code generation is partitioned into three distinct modules:

### 2.1 `impulse-codegen` (Core Generator Engine & CLI)
* **Coordinates**: `org.impulsegraph:impulse-codegen`
* **Purpose**: Parses the YAML graph schema (see [Schema Definition](schema-gen.md)) and generates type-safe Java sources using Jackson-YAML and JavaPoet.
* **Key Responsibilities**:
  * YAML schema validation against the Impulse Graph specification.
  * In-memory AST representation of domain catalogs, attributes, and relations.
  * Code generation of type-safe Node POJOs, Relation definitions, Domain Views, and Fluent Query Builders (see [Query Context & Execution](query-context.md)).
  * Calculation of Java 21 LTS Foreign Function & Memory (FFM) off-heap layouts.
  * Strict decoupling: Zero dependency on Maven or Gradle plugin APIs.
* **Standalone CLI Usage**:
  `impulse-codegen` can be executed directly as a standalone CLI application via `GeneratorMain`:
  ```bash
  java -cp "impulse-codegen-0.9.0.jar:lib/*" \
      org.impulsegraph.codegen.GeneratorMain <schema.yaml> <target-folder> [package-name]
  ```

### 2.2 `impulse-maven-plugin` (Maven Integration)
* **Coordinates**: `org.impulsegraph:impulse-maven-plugin`
* **Purpose**: Integrates schema-driven code generation into standard Maven build lifecycles.
* **Key Responsibilities**:
  * Mojo implementation (`GenerateQueryBuildersMojo`) bound to the `generate-sources` lifecycle phase (`goal: generate`).
  * Configuration parameter handling (`schemaFile`, `outputDirectory`, `packageName`).
  * Automatically attaches the generated output directory to the Maven project compile path.
* **Usage**:
  ```xml
  <plugin>
      <groupId>org.impulsegraph</groupId>
      <artifactId>impulse-maven-plugin</artifactId>
      <version>${project.version}</version>
      <executions>
          <execution>
              <goals>
                  <goal>generate</goal>
              </goals>
          </execution>
      </executions>
      <configuration>
          <schemaFile>src/main/resources/schema.yaml</schemaFile>
          <outputDirectory>${project.build.directory}/generated-sources/impulse</outputDirectory>
          <packageName>org.impulsegraph.generated</packageName>
      </configuration>
  </plugin>
  ```

### 2.3 `impulse-gradle-plugin` (Gradle Integration)
* **Coordinates**: `org.impulsegraph:impulse-gradle-plugin`
* **Purpose**: Integrates schema generation, CSV compilation, and snapshot inspection into Gradle projects.
* **Key Tasks**:
  * `impulseGenerate` (`GenerateQueryBuildersTask`): Triggers schema parsing and Java source generation into the build directory.
  * `impulseCompileCsv` (`CompileCsvToSnapshotTask`): Ingests CSV/TSV edge lists and compiles a test `.imps` snapshot file.
  * `impulseInspect` (`InspectSnapshotTask`): Reads an `.imps` file off-heap and prints header details, memory footprint, relations, and checksums.
* **Usage**:
  ```groovy
  plugins {
      id 'org.impulsegraph.impulse' version '0.9.0'
  }

  impulse {
      schemaFile = file("src/main/resources/schema.yaml")
      outputDirectory = file("build/generated/sources/impulse")
      packageName = "org.impulsegraph.generated"
      csvFile = file("src/test/resources/edges.csv")
      snapshotOutputFile = file("build/snapshots/test.imps")
  }
  ```

---

## 3. Code Generation Lifecycle

The code generation pipeline follows a four-stage lifecycle:

```
+--------------------+      +--------------------+      +--------------------+      +--------------------+
|     1. Parse       | ---> |    2. Validate     | ---> |      3. Plan       | ---> |    4. Generate     |
| Jackson YAML Parser|      | Schema Spec Rules  |      | FFM Off-Heap Layout|      | JavaPoet Generator |
+--------------------+      +--------------------+      +--------------------+      +--------------------+
```

1. **Parse**: Loads the YAML schema file using Jackson-YAML (`com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`) into the strongly-typed `ManifestModel` AST.
2. **Validate**: Ensures the schema complies with the normative rules in [Schema Definition](schema-gen.md):
   - Fixed-length strings (`string`) specify byte `length`.
   - Primitive widths (`int16`, `int32`, `int64`) are validated per domain.
   - Relation endpoints (`source`, `target`) exist in the domain catalog.
3. **Plan**: Computes the Java 21 LTS Foreign Function & Memory (FFM) memory layouts:
   - Evaluates CSR and CSC index strides based on relation traversal direction (`[out]`, `[in]`, `[out, in]`).
   - Determines Structure-of-Arrays (SoA) attribute block alignment (128-byte hardware boundary).
4. **Generate**: Emits `.java` source files using JavaPoet (`com.squareup:javapoet`):
   - Type-safe Node entity classes and getters/setters.
   - Relation descriptors with forward and inverse aliases.
   - Specialized Domain Views and fluent query builders tailored to the schema.
   - Snapshot wrapper classes (e.g. `<GraphName>Snapshot`).

---

## 4. Relationship to Ecosystem CLI Tooling (`impulse-graph-tooling`)

While `impulse-graph-java` generates strongly-typed Java application code, the **`impulse-graph-tooling`** repository provides the canonical command-line interface (`impulse` / `impulse-graph`) for binary snapshot creation, layout optimization, and bytecode compilation across the entire ecosystem.

> [!IMPORTANT]
> **Official Tooling Mandate**: When building or regenerating `.imps` zero-copy binary snapshot files from datasets, always use the official `impulse-graph-tooling` CLI utilities (`impulse build`, `impulse generate`) or the native Java streaming builder (`impulse-builder`). Never use ad-hoc scripts to directly assemble raw snapshot bytes, as this bypasses Section 2 string pools, auxiliary CSC indices, and 128-byte alignment verification.

### Key CLI Operations in `impulse-graph-tooling`

* **Snapshot Compilation & Ingestion**:
  ```bash
  # Compile .imps binary snapshot from a JSON/YAML manifest and TSV/CSV/Parquet sources
  impulse build manifest.json graph.imps

  # Synthesize benchmark graphs across 7 topology profiles (Graph500 R-MAT, Barabási–Albert, SBM, etc.)
  impulse generate --profile graph500 --scale 12 --edge-factor 16 -o graph500.imps
  ```
* **Inspection & Diagnostics**:
  ```bash
  # Inspect binary Page 0 header, domain catalogs, and section offsets
  impulse inspect graph.imps

  # Compute degree distributions, multiplicity, zone maps, and CBO sketches
  impulse stats graph.imps

  # Validate Spec v0.9.0 compliance (128-byte alignment, monotonic CSR offsets)
  impulse snapshot validate graph.imps
  ```
* **Offline Layout Optimization**:
  ```bash
  # Apply Reverse Cuthill-McKee (RCM) bandwidth reduction and Delta-VByte column compression
  impulse snapshot optimize --input graph.imps --output graph_opt.imps --rcm --encoding delta_vbyte
  ```
* **Cryptographic Verification**:
  ```bash
  # Sign snapshot file with Ed25519 private key (SLSA Level 3 compliance)
  impulse crypto sign graph.imps --key private.priv

  # Verify cryptographic authenticity
  impulse crypto verify graph.imps --key public.pub
  ```

---

## 5. DSL Compiler Suite & Execution Runtime

Tooling across the ecosystem connects front-end domain languages to the **Impulse VM (`ImpulseVM`)** through standardized compiler pipeline stages:

```
+--------------------+   +--------------------+
|  ImpK (.impk)      |   |  ImpLog (.implog)  |
|  Matrix & Vector   |   |  Datalog & ReBAC   |
+---------+----------+   +---------+----------+
          |                        |
          +----------+  +----------+
                     |  |
                     v  v
        +----------------------------+
        |  ImpScheme (.impscm)       |
        |  Universal S-Expr AST IR   |
        +-------------+--------------+
                      |
                      v
        +----------------------------+
        |  ImpAsm (.impas)           |
        |  Text Assembly Format      |
        +-------------+--------------+
                      |
                      v
        +----------------------------+
        |  impOps (.impb)            |
        |  VM Bytecode Binary ISA    |
        +-------------+--------------+
                      |
                      v
        +----------------------------+
        |  ImpulseVM                 |
        |  Register Execution Engine |
        +----------------------------+
```

### Standardized Lexicon & Components

* **ImpK (`.impk`)**: Primary user frontend DSL for GraphBLAS matrix mathematics, PageRank, connected components, and SIMD vector operations.
* **ImpLog (`.implog`)**: Primary user frontend DSL for declarative Datalog logic rules, Relationship-Based Access Control (ReBAC / Zanzibar), and transitive reachability.
* **ImpScheme (`.impscm`)**: Universal Compiler Intermediate Representation (IR) and low-level macro extension target. Homoiconic S-Expression AST compiler bus between frontends (`ImpK`, `ImpLog`, openCypher) and the bytecode emitter.
* **ImpAsm (`.impas`)**: Canonical human-readable assembly text format representing `ImpulseVM` registers (`R0`..`R63`).
* **impOps (`.impb`)**: Virtual Machine Bytecode Instruction Set Architecture (ISA opcodes `0x01`..`0x6A`). Documented in the normative specification.
* **Impulse VM (`ImpulseVM`)**: The register-based bytecode Virtual Machine engine executing `impOps` instructions over zero-copy memory-mapped `.imps` snapshots. Implemented in Java (`impulse-vm`), C++ (`impulse-graph-core`), and Rust (`impulse-rust`).

### CLI Compiler & Execution Workflow

```bash
# Compile DSL source into ImpAsm assembly or impOps bytecode
impulse compile query.impk -o query.impb

# Assemble textual assembly into binary bytecode
impulse assemble -i query.impas -o query.impb

# Disassemble binary bytecode into annotated assembly text
impulse disassemble -i query.impb

# Execute compiled bytecode directly against a memory-mapped binary snapshot
impulse run --snapshot graph.imps --bytecode query.impb --input-val 0
```

---

## 6. Repository Navigation & Cross-References

| Repository | Role in Tooling & Runtime Pipeline |
| :--- | :--- |
| **[`impulse-graph-java`](https://github.com/impulse-graph/impulse-graph-java)** | Java 21 LTS FFM Core Engine: `impulse-codegen`, `impulse-maven-plugin`, `impulse-gradle-plugin`, `impulse-compiler`, `impulse-vm`, `impulse-storage`, `impulse-builder`. |
| **[`impulse-graph-tooling`](https://github.com/impulse-graph/impulse-graph-tooling)** | Unified Developer Utilities & CLI (`impulse build`, `generate`, `inspect`, `stats`, `validate`, `optimize`, `compile`, `assemble`, `disassemble`, `run`, `crypto`). |
| **[`impulse-graph-spec`](https://github.com/impulse-graph/impulse-graph-spec)** | Normative C-ABI Binary Snapshot v0.9.0 specification (`FORMAT_SPECIFICATION.md`), test vectors (`tc01`..`tc36`), and polyglot validation suite (`four_way_validation.py`). |
| **[`impulse-graph-core`](https://github.com/impulse-graph/impulse-graph-core)** | C++20 zero-copy kernel, Rust core crate (`impulse-rust`), and multi-language FFI bindings (Python, C#, Go, Node.js). |
| **[`impulse-platform`](https://github.com/impulse-graph/impulse-platform)** | Cloud ingestion infrastructure, gRPC services, Kafka WAL streaming, and RocksDB persistence. |

