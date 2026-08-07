#!/bin/bash
set -e

# Usage: ./scripts/generate-and-verify.sh <yaml-file> <target-folder>

YAML_FILE=$1
TARGET_FOLDER=$2

if [ -z "$YAML_FILE" ] || [ -z "$TARGET_FOLDER" ]; then
    echo "Usage: $0 <yaml-file> <target-folder>"
    exit 1
fi

echo "--- Processing $YAML_FILE ---"

# 1. Build the codegen tool if needed
mkdir -p impulse-codegen/target/classes
javac -d impulse-codegen/target/classes impulse-codegen/src/main/java/org/impulsegraph/codegen/GeneratorMain.java

# 2. Run the generator
java -cp impulse-codegen/target/classes org.impulsegraph.codegen.GeneratorMain "$YAML_FILE" "$TARGET_FOLDER"

# 3. Verify compilation of generated files
echo "Verifying compilation in $TARGET_FOLDER..."

# Find all generated .java files
SOURCES=$(find "$TARGET_FOLDER" -name "*.java")

if [ -z "$SOURCES" ]; then
    echo "Error: No java files generated!"
    exit 1
fi

javac --enable-preview --release 26 $SOURCES

echo "SUCCESS: $YAML_FILE generated and verified."
