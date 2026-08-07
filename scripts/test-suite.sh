#!/bin/bash
set -e

# Usage: ./scripts/test-suite.sh

TEST_SCHEMAS_DIR="impulse-codegen/src/test/resources/schemas"
OUTPUT_DIR="impulse-codegen/target/generated-test-sources"

mkdir -p "$OUTPUT_DIR"

# Ensure API is built (skipping mvn install due to environment)
# mvn -pl impulse-api install -DskipTests

for schema in "$TEST_SCHEMAS_DIR"/*.yaml; do
    schema_name=$(basename "$schema" .yaml)
    target="$OUTPUT_DIR/$schema_name"
    ./scripts/generate-and-verify.sh "$schema" "$target"
done

echo "All test schemas processed successfully!"
