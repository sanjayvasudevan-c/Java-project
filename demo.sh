#!/usr/bin/env bash
# Builds the jar (if needed) and boots a real local 3-node cluster (alpha,
# beta, gamma -- conf/node1.conf..node3.conf) seeded from conf/jobs.json,
# each a real OS process talking over real UDP/TCP sockets on localhost.
# Prints each node's dashboard URL, then waits: Ctrl+C stops every node and
# cleans up its data directory.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_PATH="$ROOT_DIR/dist/swarmcron.jar"
RUN_DIR="$ROOT_DIR/demo-run"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "swarmcron.jar not found, building it first..."
  "$ROOT_DIR/build.sh"
fi

rm -rf "$RUN_DIR"
mkdir -p "$RUN_DIR"

PIDS=()
CLEANED_UP=0

cleanup() {
  if [[ "$CLEANED_UP" -eq 1 ]]; then
    return
  fi
  CLEANED_UP=1
  echo
  echo "Stopping demo nodes..."
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" 2>/dev/null || true
  done
  for pid in "${PIDS[@]:-}"; do
    wait "$pid" 2>/dev/null || true
  done
  echo "Stopped. Logs and data kept at $RUN_DIR (delete it whenever)."
}
trap cleanup EXIT INT TERM

echo "Starting a 3-node SwarmCron cluster (alpha, beta, gamma)..."
for n in node1 node2 node3; do
  # exec replaces the subshell's own process image with java, so $! is
  # guaranteed to be the java process's real PID -- without it, bash may or
  # may not collapse the subshell into java depending on version/optimizer,
  # and killing the wrong (wrapper) PID would leave java running orphaned.
  ( cd "$RUN_DIR" && exec java -jar "$JAR_PATH" --config "$ROOT_DIR/conf/$n.conf" --jobs "$ROOT_DIR/conf/jobs.json" \
      > "$RUN_DIR/$n.log" 2>&1 ) &
  PIDS+=("$!")
done

sleep 3
echo
echo "Cluster is up. Dashboards (each shows the whole cluster, from that node's point of view):"
echo "  alpha  http://127.0.0.1:8081"
echo "  beta   http://127.0.0.1:8082"
echo "  gamma  http://127.0.0.1:8083"
echo
echo "Logs: $RUN_DIR/node{1,2,3}.log"
echo "Press Ctrl+C to stop."
wait
