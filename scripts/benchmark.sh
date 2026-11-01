#!/usr/bin/env bash
# Throughput benchmark: queues N echo jobs and measures how long the cluster takes to drain them.
#   ./scripts/benchmark.sh 5000 http://localhost:8080
set -euo pipefail
COUNT="${1:-5000}"
BASE="${2:-http://localhost:8080}"

succeeded() { curl -s "$BASE/api/v1/stats" | sed -E 's/.*"SUCCEEDED":([0-9]+).*/\1/'; }

before=$(succeeded)
start=$(date +%s.%N)
curl -s -X POST "$BASE/api/v1/jobs/bulk" -H 'Content-Type: application/json' \
     -d "{\"type\":\"echo\",\"count\":$COUNT,\"payload\":{\"bench\":true}}" >/dev/null
echo "Queued $COUNT jobs"

while [ $(( $(succeeded) - before )) -lt "$COUNT" ]; do sleep 0.2; done
end=$(date +%s.%N)

secs=$(echo "$end - $start" | bc)
printf 'Drained %d jobs in %.2fs  ->  %.0f jobs/s\n' "$COUNT" "$secs" "$(echo "$COUNT / $secs" | bc -l)"
