#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "${REPO_DIR}"

echo "========================================================================"
echo " Impulse Graph Engine — Dual JDK Test Harness (JDK 21 LTS & JDK 22+)"
echo "========================================================================"

# 1. Resolve JDK 21
JDK21_HOME="${JAVA_HOME_21:-}"
if [ -z "${JDK21_HOME}" ]; then
  if [ -d "/Users/jesse/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home" ]; then
    JDK21_HOME="/Users/jesse/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home"
  elif /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
    JDK21_HOME="$(/usr/libexec/java_home -v 21)"
  fi
fi

if [ -z "${JDK21_HOME}" ] || [ ! -x "${JDK21_HOME}/bin/javac" ]; then
  echo "[ERROR] JDK 21 not found. Please set JAVA_HOME_21."
  exit 1
fi

# 2. Resolve JDK 25 / JDK 22+
JDK22PLUS_HOME="${JAVA_HOME_25:-}"
if [ -z "${JDK22PLUS_HOME}" ]; then
  if [ -d "/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home" ]; then
    JDK22PLUS_HOME="/opt/homebrew/Cellar/openjdk/25.0.2/libexec/openjdk.jdk/Contents/Home"
  elif [ -d "/Users/jesse/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home" ]; then
    JDK22PLUS_HOME="/Users/jesse/Library/Java/JavaVirtualMachines/temurin-25.0.2/Contents/Home"
  elif /usr/libexec/java_home -v 25 >/dev/null 2>&1; then
    JDK22PLUS_HOME="$(/usr/libexec/java_home -v 25)"
  fi
fi

if [ -z "${JDK22PLUS_HOME}" ] || [ ! -x "${JDK22PLUS_HOME}/bin/javac" ]; then
  echo "[ERROR] JDK 22+ (JDK 25) not found. Please set JAVA_HOME_25."
  exit 1
fi

echo "[INFO] JDK 21 Home:     ${JDK21_HOME}"
echo "[INFO] JDK 21 Version:  $("${JDK21_HOME}/bin/java" -version 2>&1 | head -n 1)"
echo "[INFO] JDK 22+ Home:    ${JDK22PLUS_HOME}"
echo "[INFO] JDK 22+ Version: $("${JDK22PLUS_HOME}/bin/java" -version 2>&1 | head -n 1)"
echo "------------------------------------------------------------------------"

# --- RUN 1: JDK 21 LTS Test Pass ---
echo ""
echo ">>> [STAGE 1/2] Executing Full Reactor Test Suite on JDK 21 LTS..."
export JAVA_HOME="${JDK21_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"

mvn clean test -Pjdk21 --batch-mode --errors

echo ">>> [STAGE 1/2] SUCCESS: All tests passed on JDK 21 LTS!"
echo "------------------------------------------------------------------------"

# --- RUN 2: JDK 22+ (JDK 25) Test Pass ---
echo ""
echo ">>> [STAGE 2/2] Executing Full Reactor Test Suite on JDK 22+ (OpenJDK 25)..."
export JAVA_HOME="${JDK22PLUS_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"

mvn clean test -Pjdk22-plus --batch-mode --errors

echo ">>> [STAGE 2/2] SUCCESS: All tests passed on JDK 22+ (OpenJDK 25)!"
echo "------------------------------------------------------------------------"

echo ""
echo "========================================================================"
echo " ALL TESTS PASSED ACROSS BOTH JDK 21 LTS AND JDK 22+ (JDK 25)!"
echo "========================================================================"
