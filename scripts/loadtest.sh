#!/usr/bin/env bash
# Bash twin of loadtest.ps1. Usage: ./scripts/loadtest.sh [BASE] [SEARCHES] [SUGGESTS]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
SEARCHES="${2:-5000}"
SUGGESTS="${3:-2000}"

prefixes=(ip iph ipho ja jav java sam sams lap mac head air red sys doc python react spring nike best)
queries=("iphone" "iphone 15" "java tutorial" "samsung galaxy" "macbook air" "airpods pro" \
         "redis caching" "system design interview" "python pandas" "react hooks" "docker tutorial")

echo "== Submitting $SEARCHES searches (drives batch writes) =="
for ((i=0;i<SEARCHES;i++)); do
  if (( i % 5 == 0 )); then q="${queries[$RANDOM % ${#queries[@]}]}"; else q="${queries[0]}"; fi
  curl -s -X POST "$BASE/search" -H 'Content-Type: application/json' \
       -d "{\"query\":\"$q\"}" > /dev/null
done

measure() {
  local label="$1"; local -a lat=()
  for ((i=0;i<SUGGESTS;i++)); do
    p="${prefixes[$RANDOM % ${#prefixes[@]}]}"
    t=$(curl -s -o /dev/null -w '%{time_total}' "$BASE/suggest?q=$p")
    lat+=("$t")
  done
  printf '%s\n' "${lat[@]}" | sort -n | awk -v L="$label" '
    { a[NR]=$1 }
    END {
      p50=a[int(0.50*NR)]; p95=a[int(0.95*NR)]; p99=a[int(0.99*NR)];
      for(i=1;i<=NR;i++) s+=a[i];
      printf "== Suggest latency [%s] (s): p50=%.4f p95=%.4f p99=%.4f avg=%.4f\n", L, p50, p95, p99, s/NR
    }'
}

echo "== cold cache =="; measure COLD
echo "== warm cache =="; measure WARM
echo "== /stats =="; curl -s "$BASE/stats"; echo
echo "== consistent-hashing routing =="
for p in ip java samsung macbook redis; do
  curl -s "$BASE/cache/debug?prefix=$p" | sed -E 's/.*"ownerNode":"([^"]+)".*"status":"([^"]+)".*/  '"$p"' -> \1 [\2]/'
done
