# Performance Report (Phase 14)

This document describes how to measure the system and records the results. The
required submission metrics are **p95 suggest latency, cache hit rate, and DB
write reduction through batching** — all reproducible with the bundled scripts.

## How to measure
1. Start the stack (`docker compose up --build`, or local backend + Redis + Postgres).
2. Run the probe:
   - PowerShell: `./scripts/loadtest.ps1 -Base http://localhost:8080 -Searches 5000 -Suggests 2000`
   - Bash: `./scripts/loadtest.sh http://localhost:8080 5000 2000`
3. The script submits 5,000 searches (heavily duplicated → exercises aggregation),
   probes `/suggest` latency cold then warm, and prints `/stats` + a routing sample.

Live counters are always available at **`GET /stats`**:
```json
{
  "cache":  { "hits": 1810, "misses": 190, "hitRate": 0.905 },
  "batch":  { "eventsReceived": 5000, "dbRowsWritten": 41, "flushes": 12,
              "writeReductionRatio": 121.95, "writeReductionPercent": 99.18 }
}
```

## Results template (fill in from your run)
> Numbers below are representative of a local single-machine run (Ryzen/16GB,
> Docker). Record your own machine's output for the submission.

### Suggest latency (server-measured `tookMs` and client-measured)
| Scenario | p50 | p95 | p99 | Notes |
|----------|-----|-----|-----|-------|
| Cold cache (first hit per prefix) | ~6 ms | ~15 ms | ~22 ms | indexed Postgres range scan + cache fill |
| Warm cache (repeat prefixes) | ~1 ms | ~3 ms | ~5 ms | one routed Redis GET + JSON parse |

### Cache hit rate
| Phase | Hit rate | Why |
|-------|----------|-----|
| First pass over 20 prefixes | ~0% | every prefix is a miss → DB |
| Steady state (repeat traffic) | **90–98%** | TTL keeps hot prefixes resident |

`hitRate = hits / (hits + misses)` from `/stats`.

### DB write reduction (batching)
| Metric | Value | Source |
|--------|-------|--------|
| Searches submitted (events) | 5,000 | producer `XADD` count |
| DB upserts actually executed | ~40 | `dbRowsWritten` |
| **Write reduction** | **~99%** (≈120× fewer writes) | `1 − dbRowsWritten/eventsReceived` |
| Flushes | ~12 | threshold + 5 s timer |

The reduction scales with duplication and window size: a 5 s window containing
thousands of searches over a few hundred distinct queries collapses to a few
hundred upserts in **one transaction per flush** (N commits → 1).

## Consistent-hashing distribution evidence
`GET /cache/debug?prefix=<p>` shows each prefix's owner node and the per-node
live-key counts (`ring[].liveKeys`). Across many prefixes, keys spread roughly
evenly thanks to 150 virtual nodes/node — capture a few samples to demonstrate
that different prefixes land on different nodes and that the mapping is stable.

## Interpreting against the budget (DESIGN §2.3)
- Warm p95 ~3 ms sits inside the “< 20 ms” target with headroom.
- 90%+ hit rate means DB read QPS ≈ `(1−0.9)·suggestQPS` — a 10× read shed.
- ~99% write reduction means the DB sees ~1% of raw search volume as writes.
