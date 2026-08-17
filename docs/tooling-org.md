# ImpulseGraph Tooling & Code Generation Organization

> [!WARNING]
> **Pre-release Documentation**: This documentation describes pre-release software under active development and may be inaccurate, incomplete, or missing.

This document outlines the organization of the tools used to generate code from the ImpulseGraph YAML schema.

## Module Structure

To support both Maven, Gradle, and standalone CLI usage, the tooling is organized into two primary components:

### 1. `impulse-codegen` (Core Logic)
*   **Purpose**: Contains the engine for parsing the YAML schema and the templates for generating Java code.
*   **Responsibilities**:
    *   YAML validation against the spec.
    *   AST representation of the graph schema.
    *   Java code generation (POJOs, Query Builders, Layouts).
    *   No dependency on Maven or Gradle APIs.
*   **Output**: A library that can be embedded in other tools.

### 2. `impulse-maven-plugin` (Maven Wrapper)
*   **Purpose**: Provides a standard Maven interface for triggering code generation during the build lifecycle.
*   **Responsibilities**:
    *   Mojo implementation to bind to the `generate-sources` phase.
    *   Configuration handling (input YAML path, output directory).
    *   Adding generated sources to the Maven project compile path.
*   **Usage**:
    ```xml
    <plugin>
        <groupId>io.impulse.graph</groupId>
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
            <schemaFile>src/main/resources/my-graph.yaml</schemaFile>
        </configuration>
    </plugin>
    ```

---

## Why this structure?

1.  **Separation of Concerns**: The core generation logic is decoupled from build tool APIs, making it easier to test and maintain.
2.  **Extensibility**: Adding a `impulse-gradle-plugin` or a standalone `impulse-cli` becomes trivial as they will just be thin wrappers around `impulse-codegen`.
3.  **Consistency**: Ensures that regardless of the entry point (Maven vs. CLI), the generated code is identical.

## Code Generation Lifecycle

1.  **Parse**: Load the YAML file using a library like SnakeYAML or Jackson-YAML.
2.  **Validate**: Ensure the schema follows the rules defined in `docs/schema-gen.md` (e.g., fixed-width strings have a length).
3.  **Plan**: Determine the FFM memory layouts for each Node and Relation.
4.  **Generate**: Write `.java` files using a template engine (like FreeMarker) or a code generation library (like JavaPoet).
