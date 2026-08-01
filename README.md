# Impulse Graph Java Engine (`impulse-graph-java`)

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

High-performance **Java 25 Foreign Function & Memory (FFM) API** implementation of the Impulse Graph Engine.

## Modules

* `impulse-api`: Core Java graph domain model interfaces, relation directories, and query abstractions.
* `impulse-core`: Ultra-lean Java 25 FFM zero-copy off-heap snapshot graph engine (zero third-party runtime dependencies).
* `impulse-spec`: Java binary specification encoders, decoders, and data structures for C-ABI Binary Snapshot Spec v2.4.

## Prerequisites

* Java 25 JDK (with `--enable-preview` and FFM API support enabled).
* Maven 3.9+.

## Build & Test

```bash
mvn clean test
```

## License

Apache License 2.0. See [LICENSE](LICENSE) for details.
