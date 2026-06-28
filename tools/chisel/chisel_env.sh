#!/usr/bin/env bash

set -euo pipefail

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
  elif [[ -x /opt/homebrew/opt/openjdk/bin/java ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk"
  fi
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

if ! command -v java >/dev/null 2>&1 || ! java -version >/dev/null 2>&1; then
  echo "error: Java runtime is required for Chisel; install a JDK and rerun" >&2
  exit 2
fi

if ! command -v sbt >/dev/null 2>&1; then
  echo "error: sbt is required for Chisel; install sbt and rerun" >&2
  exit 2
fi
