#!/usr/bin/env bash
# Compiles src/ and runs a simulation scenario: ./run-sim.sh <scenario>
# Scenarios live in com.swarmcron.sim.ClusterSim; defaults to "ping-pong".
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="$ROOT_DIR/build/classes"

mkdir -p "$BUILD_DIR"
find "$ROOT_DIR/src" -name '*.java' > "$ROOT_DIR/build/sources.txt"
javac -d "$BUILD_DIR" @"$ROOT_DIR/build/sources.txt"

SCENARIO="${1:-ping-pong}"
java -cp "$BUILD_DIR" com.swarmcron.sim.ClusterSim "$SCENARIO"
