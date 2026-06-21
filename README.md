# Search Typeahead System

A search autocomplete system with a **distributed Redis cache (consistent
hashing)**, **trending searches (popularity + recency/frequency)**, and
**asynchronous batched writes (Redis Streams)**. Built for the "Build a Search
Typeahead System" assignment.

- **Backend:** Java 17 · Spring Boot 3 · Maven · PostgreSQL · Redis
- **Frontend:** React 18 · TypeScript · Vite
- **Infra:** Docker Compose (Postgres + 3 Redis nodes + backend + frontend)

> **Design docs:** [DESIGN.md](DESIGN.md) (HLD/LLD/DB/trade-offs) ·
> [PERFORMANCE.md](PERFORMANCE.md) (latency/hit-rate/write-reduction).

---

## 1. Project overview
Type a prefix → top-10 suggestions by popularity, served in ~1 ms from a sharded
Redis cache that falls back to an indexed Postgres lookup. Submit a search → a
dummy `"Searched"` response while the count update is enqueued on a Redis Stream
and applied later in aggregated batches. Trending shows what's hot via a decayed
recency+frequency score, toggleable against all-time popularity.

### Maps to the grading rubric (100)
| Component | Marks | Implemented by |
|-----------|-------|----------------|
| Basic implementation (ingestion, UI, suggest/search APIs, count updates, **distributed cache + consistent hashing**) | 60 | `cache/`, `controller/`, `service/`, `DatasetLoaderService`, frontend |
| Trending searches (popularity + recency, explained) | 20 | `TrendingService`, `TrendingController`, toggle in `TrendingPanel` |
| Batch writes (buffer/aggregate/flush, write-reduction evidence, failure trade-offs) | 20 | `batch/`, `/stats`, DESIGN §7 |

---

## 2. Architecture (short)
```
Browser ─▶ Backend ─▶ Redis cache ring (consistent hashing, 3 nodes) ─▶ Postgres
            │  ▲                                                          ▲
            │  └────────────── miss falls back to ────────────────────────┘
            ├─▶ Redis Stream (search-events) ─▶ batch consumer ─▶ Postgres (batched upserts)
            └─▶ Redis ZSET (trending:recency, decayed score)
```
Full diagrams (architecture, ER, class, sequence, component) are in [DESIGN.md](DESIGN.md).

### Repository layout
```
hld ass/
├── README.md  DESIGN.md  PERFORMANCE.md  Project_Report.pdf
├── docker-compose.yml
├── backend/            Spring Boot app (cache, trie, batch, service, controller, …)
│   └── src/main/resources/data/  queries.csv (120k) + sample_queries.csv
├── frontend/           React + TS + Vite (search bar, dropdown, trending panel)
├── dataset/            DatasetGenerator.java (+ generated queries.csv)
└── scripts/            loadtest.ps1 / loadtest.sh (performance probe)
```

---

## 3. Quick start with Docker (recommended)
Requires Docker Desktop. From the project root:
```bash
docker compose up --build
```
- Frontend → http://localhost:3000
- Backend  → http://localhost:8080  (health: http://localhost:8080/actuator/health)
- Postgres → localhost:5432 · Redis nodes → 6379 / 6380 / 6381

The backend loads the 120k dataset on first start (only when the table is empty).
Tear down with `docker compose down` (add `-v` to wipe volumes).

---

## 4. Running locally (no Docker)
Prerequisites: **JDK 17**, **Maven** (or an IDE that bundles it), **Node 18+**,
plus a local **PostgreSQL** and **Redis**.

**4.1 Postgres** — create the DB/user (matches `application.properties`):
```sql
CREATE DATABASE typeahead;
CREATE USER typeahead WITH PASSWORD 'typeahead';
GRANT ALL PRIVILEGES ON DATABASE typeahead TO typeahead;
```
**4.2 Redis** — one local instance on 6379 is enough; the 3 logical cache nodes
use DB indexes 0/1/2 on it (see `cache.nodes` in `application.properties`).

**4.3 Backend:**
```bash
cd backend
mvn spring-boot:run        # or: mvn clean package && java -jar target/typeahead-1.0.0.jar
```
**4.4 Frontend:**
```bash
cd frontend
npm install
npm run dev                # http://localhost:5173 (proxies API to :8080)
```

> The frontend build is verified (`tsc && vite build` passes). The backend is
> standard Spring Boot; build it with Maven or via the Docker image (which
> compiles inside a `maven:3.9-eclipse-temurin-17` stage, so no local Maven needed).

---

## 5. Dataset
- Ships with **`backend/src/main/resources/data/queries.csv`** — 120,000 unique
  queries with Zipf-like counts (meets the ≥100k requirement), and a small
  `sample_queries.csv` for quick smoke tests.
- Regenerate any size with the standalone generator (JDK only):
  ```bash
  cd dataset
  javac DatasetGenerator.java
  java DatasetGenerator 150000 queries.csv      # rows, output path
  cp queries.csv ../backend/src/main/resources/data/queries.csv
  ```
- Use your own dataset by pointing `dataset.csv-path` at a `classpath:` or
  `file:` CSV of `query,count`.

---

## 6. API <a id="api"></a>
Base URL `http://localhost:8080`.

### `GET /suggest?q=<prefix>&mode=popularity|recency` — typeahead
```bash
curl "http://localhost:8080/suggest?q=ip"                 # basic: popularity ranking
curl "http://localhost:8080/suggest?q=ip&mode=recency"    # enhanced: recency+frequency ranking
```
```json
{ "prefix": "ip", "mode": "POPULARITY",
  "suggestions": [ { "query": "iphone", "count": 100031 } ],
  "source": "DB", "cacheNode": "node-1 (localhost:6379/1)", "tookMs": 7 }
```
`mode` defaults to `popularity` (sort by all-time count). `mode=recency` re-ranks
the same prefix matches by a blended recency+frequency score (assignment §7
"enhanced version"). `source` is `CACHE` on a hit, `DB`/`TRIE`/`DB+RECENCY` on a
miss, `EMPTY` for blank input. The two rankings are cached under separate
consistent-hashing keys (`suggest:<p>` vs `suggest:recency:<p>`).

### `POST /search` — submit (dummy response + async count update)
```bash
curl -X POST http://localhost:8080/search \
  -H 'Content-Type: application/json' -d '{"query":"iphone 15"}'
```
```json
{ "message": "Searched", "query": "iphone 15", "accepted": true }
```
Invalid body (blank query) → HTTP 400 with a uniform `ApiError`.

### `GET /trending?mode=recency|popularity` — trending
```bash
curl "http://localhost:8080/trending?mode=recency"
```
```json
{ "mode": "RECENCY", "suggestions": [ { "query": "iphone 15", "count": 85012 } ],
  "source": "COMPUTED", "tookMs": 3 }
```

### `GET /cache/debug?prefix=<prefix>` — consistent-hashing proof (required)
```bash
curl "http://localhost:8080/cache/debug?prefix=ip"
```
```json
{ "prefix": "ip", "cacheKey": "suggest:ip", "ownerNode": "node-1 (localhost:6379/1)",
  "hashValue": 7421596180329, "status": "HIT", "ttlSeconds": 42,
  "ring": [ { "nodeId": "node-0 …", "virtualNodes": 150, "liveKeys": 31 }, … ] }
```

### `GET /stats` — performance counters
```bash
curl http://localhost:8080/stats     # cache hit rate + batch write-reduction
```

---

## 7. Testing the flow / performance
```bash
# PowerShell
./scripts/loadtest.ps1 -Base http://localhost:8080 -Searches 5000 -Suggests 2000
# Bash
./scripts/loadtest.sh http://localhost:8080 5000 2000
```
Then read `/stats` for hit rate and write-reduction. See [PERFORMANCE.md](PERFORMANCE.md).

**Demo the two ranking versions (viva):** submit a rare prefix-sharing query ~20×
via `POST /search`, then compare on the SAME suggestion API:
`GET /suggest?q=<prefix>&mode=popularity` vs `&mode=recency` (or use the UI's
"Rank suggestions by" toggle). The freshly-searched query climbs under *recency*
but not under *popularity*. The trending panel toggle shows the same effect on
the global trending list.

---

## 8. Configuration (key properties)
| Property | Default | Meaning |
|----------|---------|---------|
| `cache.nodes` | `localhost:6379:0,…:1,…:2` | ring members `host:port:db` |
| `cache.virtual-nodes` | 150 | replicas per node on the ring |
| `cache.suggest-ttl-seconds` | 60 | suggestion cache TTL |
| `suggest.source` | `DB` | `DB` (prefix index) or `TRIE` (in-memory) |
| `trending.alpha` | 0.5 | popularity↔recency blend |
| `trending.decay-half-life-seconds` | 3600 | recency decay half-life |
| `batch.flush-threshold` | 500 | events before a forced flush |
| `batch.flush-interval-ms` | 5000 | timed flush interval |
| `dataset.csv-path` | `classpath:data/queries.csv` | dataset to ingest |

---

## 9. Screenshots
> Add screenshots / a short demo video here before submitting.
- `docs/screenshot-suggest.png` — typeahead dropdown with live suggestions
- `docs/screenshot-trending.png` — trending panel, recency vs popularity toggle
- `docs/screenshot-cache-debug.png` — `/cache/debug` JSON showing node ownership
- `docs/demo.mp4` — 60–90s walkthrough (type → suggest → search → trending updates)

---

## 10. Assumptions
- Counts may lag by up to one cache TTL (eventual consistency) — acceptable for a suggestion counter.
- Batch writes are **at-least-once**; a crash between DB commit and `XACK` may over-count slightly.
- "Distributed cache" = multiple **logical** Redis nodes; locally they're DB indexes on one Redis, in Docker they're 3 separate containers (identical code path).
- Dataset counts are synthetic (Zipf-like); any real `query,count` CSV can be substituted.
- Traffic/capacity numbers in DESIGN are stated assumptions for the estimation exercise, not measured production figures.

---

## 11. Future improvements
- Exactly-once writes via a processed-offset table / idempotency keys.
- Top-K-per-node Trie for O(P) in-memory search; or `pg_trgm`/full-text for fuzzy + mid-word matching.
- Write-through cache invalidation for the hottest prefixes (tighter freshness).
- Postgres read replicas + table partitioning/sharding for write scale.
- Per-geo / per-user trending and personalization.
- Prometheus + Grafana dashboards for p95 latency, hit rate, stream lag.
- Multiple stream consumers (scale the consumer group) for higher write throughput.
```
