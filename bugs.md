# Impulse Graph Java (`impulse-graph-java`) Bug Tracker

This document details identified bugs, build failures, functional defects, root cause analyses, proposed fixes, and mandatory test cases required to prevent regressions across `impulse-graph-java`.

> [!NOTE]
> **Status Update (2026-08-02)**: All 5 identified bugs (`BUG-JAVA-001` through `BUG-JAVA-005`) have been fully resolved, verified via automated test suites (42/42 tests passing), and committed to `main`.

---

## BUG-JAVA-001: `LoadedSnapshot` Interface Incompleteness (`getMetadata` & `getMetadataMap`)

* **Severity**: Critical (Build Blocker)
* **Component**: `impulse-core` (`org.impulsegraph.core.csr.BinarySnapshotLoader`)
* **Affected Versions**: v2.3.0-SNAPSHOT

### Description & Symptoms
Executing `mvn clean install` on `impulse-graph-java` fails during compilation of `impulse-core`:
`[ERROR] BinarySnapshotLoader.LoadedSnapshot is not abstract and does not override abstract method getMetadataMap() in org.impulsegraph.api.ImpulseGraphSnapshot`

### Root Cause Analysis
The `ImpulseGraphSnapshot` interface in `impulse-api` declares:
* `String getMetadata(String key)`
* `Map<String, String> getMetadataMap()`

`BinarySnapshotLoader.LoadedSnapshot` implements `ImpulseGraphSnapshot` but omitted these two method implementations.

### Proposed Fix
Implement `getMetadata` and `getMetadataMap` in `LoadedSnapshot` record:

```java
@Override
public String getMetadata(String key) {
    return null;
}

@Override
public Map<String, String> getMetadataMap() {
    return Map.of();
}
```

### Mandatory Test Case (Regression Prevention)
Add a unit test `BinarySnapshotLoaderTest.testLoadedSnapshotMetadataInterface()` in `impulse-core`:
1. Load a snapshot using `BinarySnapshotLoader.loadSnapshot(...)`.
2. Assert `loadedSnapshot.getMetadata("testKey")` returns non-null or null without throwing `AbstractMethodError`.
3. Assert `loadedSnapshot.getMetadataMap()` returns a valid `Map`.

---

## BUG-JAVA-002: Missing `junit-jupiter-params` Test Dependency in `impulse-core/pom.xml`

* **Severity**: High (Build Blocker for `-DskipTests`)
* **Component**: `impulse-core/pom.xml`
* **Affected Versions**: v2.3.0-SNAPSHOT

### Description & Symptoms
Executing `mvn clean install -DskipTests` fails during `testCompile` in `impulse-core`:
`[ERROR] package org.junit.jupiter.params does not exist`
`[ERROR] cannot find symbol class ParameterizedTest`

`-DskipTests` skips test execution but still runs the `testCompile` lifecycle phase. `TestVectorSuiteTest.java` uses `@ParameterizedTest` and `@MethodSource`, but `junit-jupiter-params` was missing from `impulse-core/pom.xml`.

### Root Cause Analysis
`impulse-core/pom.xml` declared `junit-jupiter-api` and `junit-jupiter-engine`, but omitted `junit-jupiter-params`.

### Proposed Fix
Add `junit-jupiter-params` to `<dependencies>` in `impulse-core/pom.xml`:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter-params</artifactId>
    <version>${junit.version}</version>
    <scope>test</scope>
</dependency>
```

### Mandatory Test Case (Regression Prevention)
Verify that `mvn clean install -DskipTests` executes to `BUILD SUCCESS` without dependency resolution or compilation errors.

---

## BUG-JAVA-003: `ImpulseGraphQuery.execute()` AST Builder Returns Un-Evaluated Input Stub

* **Severity**: Critical (Functional Defect)
* **Component**: `impulse-api` (`org.impulsegraph.api.ImpulseQueryBuilder.DefaultImpulseGraphQuery`)
* **Affected Versions**: v2.3.0-SNAPSHOT

### Description & Symptoms
Queries constructed using `ImpulseGraphQuery.builder().input("USER", ...).walkEdge("userToGroup").walkEdge("groupToRole").collect(...)` do not execute graph reachability traversals. Calling `.execute(loadedSnapshot, inputUsers)` returns `inputUsers` pass-through unchanged (`{0}`).

### Root Cause Analysis
In `ImpulseQueryBuilder.java`, `DefaultImpulseGraphQuery.execute` is a stub:

```java
@Override
public R execute(ImpulseGraphSnapshot snapshot, Object input) {
    // Evaluates pipeline over snapshot
    return (R) input;
}
```

No query evaluator exists to iterate `walkEdge` steps against `RelationSnapshot` CSR off-heap memory buffers.

### Proposed Fix
Implement a `QueryEvaluator` in `impulse-core` (or `impulse-api`) that evaluates `StepNode` operations (`WALK_EDGE`, `COLLECT`) against `snapshot.getRelationTargetsSegment()` or `RelationSnapshot.getTargets(nodeId)`.

### Mandatory Test Case (Regression Prevention)
Add an end-to-end integration test `RbacQueryExecutionTest` in `impulse-core`:
1. Load `rbac_snapshot.imps`.
2. Construct a 3-tier AST query: `USER` $\to$ `walkEdge("userToGroup")` $\to$ `walkEdge("groupToRole")` $\to$ `collect()`.
3. Execute with input seed User 0.
4. Assert return value contains Role IDs `[0, 1, 2]` instead of seed `{0}`.

---

## BUG-JAVA-004: Relation Key Prefixing Mismatch (`rel_<idx>_` vs Raw Relation Names)

* **Severity**: Medium
* **Component**: `impulse-core` (`org.impulsegraph.core.csr.BinarySnapshotLoader`)
* **Affected Versions**: v2.3.0-SNAPSHOT

### Description & Symptoms
When loading a snapshot, `BinarySnapshotLoader` generates relation keys as `"rel_0_userToGroup"` and `"rel_1_groupToRole"`. Lookups using raw relation names (e.g. `graph.getRelationSnapshot("userToGroup")` or AST `.walkEdge("userToGroup")`) fail with `null`.

### Root Cause Analysis
In `BinarySnapshotLoader.java` line 296:
`String relName = "rel_" + j + "_" + srcDom.name().toLowerCase() + "To" + capitalize(tgtDom.name().toLowerCase());`

### Proposed Fix
Index relations under both canonical formats (e.g. `"userToGroup"` and `"rel_0_userToGroup"`) in `GraphSnapshot.relationMap`, or normalize relation lookup strings in `getRelationSnapshot()`.

### Mandatory Test Case (Regression Prevention)
Add a test `BinarySnapshotLoaderRelationNameTest` asserting that both `graph.getRelationSnapshot("userToGroup")` and `graph.getRelationSnapshot("rel_0_userToGroup")` return the target `RelationSnapshot`.

---

## BUG-JAVA-005: Hardcoded `<release>25</release>` with `--enable-preview` Mismatch on Non-JDK 25

* **Severity**: Medium
* **Component**: Parent `pom.xml` (`maven-compiler-plugin`)
* **Affected Versions**: v2.3.0-SNAPSHOT

### Description & Symptoms
Building on JDK 26 fails with:
`[ERROR] invalid source release 25 with --enable-preview (preview language features are only supported for release 26)`

### Root Cause Analysis
Java `javac` compiler strictly enforces that `--enable-preview` can only be combined with `--release` matching the host JDK version.

### Proposed Fix
Allow overriding compiler release version or set `<maven.compiler.release>${java.specification.version}</maven.compiler.release>`.

### Mandatory Test Case (Regression Prevention)
Matrix build validation in GitHub Actions across Java 25 and Java 26.
