# Search Typeahead System — Design Document

> Covers Phases 1–7 and 13–15 of the brief: requirements, HLD, DB design,
> typeahead design (Trie + prefix index), trending, caching, batch writes,
> LLD diagrams and trade-offs. Performance numbers live in
> [PERFORMANCE.md](PERFORMANCE.md).

---

## Phase 1 — Requirements Analysis

### 1.1 Functional requirements
| # | Requirement | Where it lives |
|---|-------------|----------------|
| FR1 | Typing a prefix returns ≤10 suggestions starting with that prefix, sorted by count desc | `SuggestController` → `SuggestionService` |
| FR2 | Handle empty / missing / mixed-case / no-match input gracefully | `PrefixUtil.normalize`, empty-guard in `SuggestionService` |
| FR3 | Debounce so the UI doesn't call the backend per keystroke | `useDebounce` (200 ms) + `AbortController` |
| FR4 | `POST /search` returns dummy `"Searched"` and updates the query count | `SearchController` → `SearchService` |
| FR5 | New query inserted with initial count; existing query incremented | `SearchQueryRepository.upsertIncrement` (UPSERT) |
| FR6 | Updates eventually reflected in suggestions & trending | batch flush → DB; TTL cache expiry; recency ZSET |
| FR7 | Trending searches (popularity + recency versions) | `TrendingService`, `TrendingController` |
| FR8 | Distributed cache with **consistent hashing** | `ConsistentHashRouter`, `DistributedCacheService`, 3 Redis nodes |
| FR9 | Cache debug endpoint showing node ownership + hit/miss | `CacheDebugController` (`GET /cache/debug`) |
| FR10 | Batch writes (buffer → aggregate → flush periodically/threshold) | Redis Streams: producer/consumer/aggregator/scheduler |

### 1.2 Non-functional requirements
- **Low latency** reads (target p95 < 20 ms warm; see PERFORMANCE.md).
- **Easy to run locally** (`docker compose up --build`, or two terminals).
- **Observability**: `/stats` (hit rate, write reduction), actuator `/actuator/health`, logs for consistent-hashing routing.
- **Modularity**: clean package-per-concern layout; cache/trie/batch isolated.
- **Graceful degradation**: a cache failure becomes a miss, never a 5xx.

### 1.3 API requirements
`GET /suggest?q=` · `POST /search` · `GET /trending?mode=` · `GET /cache/debug?prefix=` · `GET /stats`. Detailed contracts in [README.md](README.md#api).

### 1.4 Database requirements
One durable table of `query → count` supporting (a) prefix range scan ordered by count and (b) atomic increment-or-insert. Indexes on `query_norm` and `count`.

### 1.5 Caching requirements
Prefix→suggestions cached in Redis, sharded across ≥3 logical nodes via consistent hashing, with TTL expiry and the option of explicit invalidation.

### 1.6 Trending requirements
Two rankings sharing one API surface: (v1) all-time count, (v2) recency+frequency with decay so short-lived spikes don't dominate forever.

### 1.7 Batch-write requirements
Searches never write to Postgres synchronously. They are appended to a durable log (Redis Streams), aggregated in memory, and flushed in one transaction on a size threshold or a timer, with crash recovery.

### 1.8 UI requirements
Search box, live dropdown, Enter/click submit, dummy-response display, trending panel (toggle v1/v2), loading + error states, keyboard navigation, clean layout.

### 1.9 What the professor is really testing
The marks are weighted 60/20/20 toward **systems data-plumbing**, not CRUD. The graded skills are:
1. **Read-path engineering** — turning "prefix lookup top-10" into an index range scan and putting a *distributed* cache in front of it.
2. **Consistent hashing literacy** — can you explain *why* modulo sharding is bad and demonstrate ring ownership (hence the mandatory `/cache/debug`).
3. **Write-path back-pressure** — decoupling user writes from DB writes via a queue + aggregation, and reasoning about the failure window.
4. **Ranking design judgement** — combining popularity and recency with a defensible decay, and articulating the freshness/latency/complexity trade-off.
5. **Honest trade-off articulation** — the academic-integrity clause means every choice must be explainable. This doc exists for exactly that.

---

## Phase 2 — High Level Design

### 2.1 Capacity & traffic estimation (assumptions stated)
Assume a mid-size product: **1M daily active users**, each doing ~10 searches/day and typing ~15 keystrokes per search (but debounced → ~4 suggest calls/search).

| Quantity | Estimate | Working |
|----------|----------|---------|
| Searches/day | 10M | 1M × 10 |
| Suggest calls/day | 40M | 10M × 4 debounced calls |
| Avg search QPS | ~115 | 10M / 86400 |
| Avg suggest QPS | ~460 | 40M / 86400 |
| Peak factor | ×5 | typical diurnal peak |
| **Peak suggest QPS** | **~2.3k** | |
| **Peak search QPS** | **~575** | |
| Distinct queries | ~10M | long-tail; dataset here = 120k sample |
| Avg row size | ~80 B | query + count + timestamps |
| Primary table size | ~0.8 GB | 10M × 80 B (fits one node easily) |

### 2.2 Read/write ratio
**~4:1 read-heavy** (40M suggest reads vs 10M search "writes"), and the writes are *deferrable*. This is the textbook justification for: (a) a big read cache, (b) async batched writes. Reads must be fast and consistent-enough; writes must be cheap and durable, not instant.

### 2.3 Expected latency budget (warm)
- Cache hit suggest: **~1–5 ms** (one routed Redis GET + JSON parse).
- Cache miss suggest: **~5–20 ms** (indexed Postgres range scan + cache fill).
- Search submit: **~1–3 ms** (one Redis `XADD` + one `ZINCRBY`; no DB on the hot path).

### 2.4 Scalability assumptions
- Stateless backend → scale horizontally behind a load balancer; the hash ring is identical on every instance (same node list + hash fn) so any instance routes a key to the same node.
- Cache scales by adding ring nodes; consistent hashing limits re-mapping to ~1/N of keys.
- Postgres is the eventual bottleneck for writes; batching cuts write volume ~100–1000×, and read load is mostly absorbed by cache. Read replicas are the next lever.

### 2.5 Architecture decisions (and why)
| Decision | Choice | Why |
|----------|--------|-----|
| Cache | Redis, **app-level** consistent-hash ring over N nodes | Assignment grades consistent hashing & node-ownership visibility; app-level ring makes `/cache/debug` trivial and is client-library-agnostic |
| Primary store | PostgreSQL + `text_pattern_ops`-style prefix index | Durable, supports `LIKE 'p%'` range scan + `ON CONFLICT` upsert |
| Suggestion index | DB prefix index (default), Trie available | Counts change continuously via batch writes; DB is simplest *correct* source. Trie kept for comparison + ultra-low-latency option |
| Write pipeline | Redis Streams + consumer group | Durable log, at-least-once, crash recovery via pending list |
| Trending | Redis ZSET with time-growing weights (decay-equivalent) | O(log n) updates, no scan, decay "for free" |

### 2.6 Architecture diagram
```
                ┌──────────────────────────────────────────────────────────────┐
                │                          BROWSER                               │
                │   React + TS (Vite)  · debounce · keyboard nav · trending UI   │
                └───────────────┬───────────────────────────┬──────────────────┘
                    GET /suggest │                           │ POST /search
                    GET /trending│                           │
                                 ▼                           ▼
                ┌──────────────────────────────────────────────────────────────┐
                │                   BACKEND (Spring Boot, stateless)            │
                │                                                                │
                │  SuggestController     SearchController     TrendingController │
                │        │                     │                     │           │
                │        ▼                     ▼                     ▼           │
                │  SuggestionService     SearchService         TrendingService   │
                │        │   ▲                  │                  │   ▲          │
                │  read  │   │ fill         XADD│            ZINCRBY│   │range     │
                └────────┼───┼──────────────────┼───────────────────┼───┼────────┘
                         │   │                  │                   │   │
              consistent │   │                  │                   │   │
                 hashing ▼   │                  ▼                   ▼   │
        ┌────────────────────┴───┐    ┌──────────────────┐   ┌─────────┴──────┐
        │  DISTRIBUTED CACHE     │    │  REDIS STREAM     │   │ REDIS ZSET     │
        │  redis-0  redis-1  ... │    │  "search-events"  │   │ trending:recency│
        │  (suggest:<prefix>)    │    └─────────┬─────────┘   └────────────────┘
        └───────────▲────────────┘              │ XREADGROUP
                    │ miss → fill                ▼
                    │                  ┌──────────────────────────────────────┐
                    │                  │ BATCH WRITER (consumer thread)        │
                    │                  │  SearchEventConsumer → BatchAggregator│
                    │                  │  (aggregate) → BatchPersister (1 txn) │
                    │                  │  + BatchFlushScheduler (timer)        │
                    │                  └─────────────────┬────────────────────┘
                    │ fallback read                      │ upsert (batched)
                    ▼                                    ▼
        ┌──────────────────────────────────────────────────────────────────────┐
        │                       POSTGRESQL (primary store)                       │
        │                 search_query(query PK, query_norm, count, …)           │
        └──────────────────────────────────────────────────────────────────────┘
```

### 2.7 Request flows
**Suggest (read):** normalize prefix → route `suggest:<prefix>` to owner node via ring → GET. Hit → return. Miss → indexed Postgres range scan top-10 → SET on owner node (TTL) → return.

**Search (write):** normalize → `XADD search-events {q,d}` (durable) → `ZINCRBY trending:recency` → return `"Searched"`. Later: consumer reads via group, aggregates duplicates, and on threshold/timer flushes a single transaction of upserts, then `XACK`s.

**Trending:** v1 = `ORDER BY count DESC LIMIT 10`. v2 = top candidates from recency ZSET, blended with normalized popularity, sorted, top-10. Controller caches the list for the recompute interval.

---

## Phase 3 — Database Design

### 3.1 ER diagram
```
        ┌─────────────────────────────────────────────┐
        │                 search_query                 │
        ├─────────────────────────────────────────────┤
        │ PK  query           VARCHAR(512)  NOT NULL   │
        │     query_norm      VARCHAR(512)  NOT NULL   │  ── idx_query_norm
        │     count           BIGINT        NOT NULL   │  ── idx_count
        │     last_searched_at TIMESTAMPTZ            │
        │     created_at      TIMESTAMPTZ   NOT NULL   │
        └─────────────────────────────────────────────┘
```
Single-table by design: the system is a key (query) → counter store with a prefix-range read. No joins on the hot path = no join cost, and the natural key (`query`) gives us a free uniqueness constraint for upserts. (A normalized `search_event` history table is discussed as a future extension in §3.5.)

### 3.2 Schema (PostgreSQL DDL)
```sql
CREATE TABLE IF NOT EXISTS search_query (
    query             VARCHAR(512) PRIMARY KEY,
    query_norm        VARCHAR(512) NOT NULL,
    count             BIGINT       NOT NULL DEFAULT 0,
    last_searched_at  TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Prefix range scan ordered by popularity:
--   WHERE query_norm LIKE 'iph%' ORDER BY count DESC LIMIT 10
-- text_pattern_ops makes LIKE 'prefix%' an index range scan (locale-independent).
CREATE INDEX IF NOT EXISTS idx_query_norm
    ON search_query (query_norm text_pattern_ops);

-- Trending v1 (global top-N by all-time count):
CREATE INDEX IF NOT EXISTS idx_count
    ON search_query (count DESC);
```
> JPA (`ddl-auto=update`) creates the table and B-tree indexes automatically.
> The `text_pattern_ops` operator class above is the production-grade refinement
> you should mention in viva — apply it via the SQL when you want `LIKE 'p%'` to
> use the index regardless of DB locale/collation.

### 3.3 Keys
- **PK `query`** — natural key; queries are unique by text. Enables `ON CONFLICT (query)` upsert.
- No foreign keys (single table). `query_norm` is the lookup column; `query` preserves display casing.

### 3.4 Optimization decisions
- Store **`query_norm`** so case-insensitive matching hits a plain B-tree instead of a non-sargable `LOWER(query)`.
- **`count DESC` index** turns trending v1 into an index-only top-N.
- **Batched upserts** in one transaction (Hibernate `batch_size=500`, `order_inserts/updates=true`) minimize round-trips and commits.
- `BIGINT` count avoids overflow at scale; `TIMESTAMPTZ` for correct time math in recency.

### 3.5 Future extension
Append-only `search_event(id, query_norm, ts)` for windowed analytics / exact sliding-window trending, with a rollup job into `search_query`. Omitted here because the Redis ZSET already gives windowless decayed recency far more cheaply.

---

## Phase 4 — Typeahead Design (two approaches)

### 4.1 Approach A — Trie (prefix tree)
- **Structure:** nodes keyed by character; terminal nodes carry `count` + full `word`. `HashMap` children (open alphabet, unicode-safe).
- **Insert:** walk/create one node per char → **O(L)**.
- **Search:** walk to prefix node **O(P)**, then collect completions in the subtree into a size-K min-heap → **O(P + M·log K)** where M = completions under the prefix.
- **Space:** O(Σ characters) nodes.
- Implementation: [`Trie.java`](backend/src/main/java/com/scaler/typeahead/trie/Trie.java), [`TrieService.java`](backend/src/main/java/com/scaler/typeahead/trie/TrieService.java).
- **Production optimization:** cache top-K at each node during build → search becomes **O(P)**. We left the subtree-walk in for clarity; the Redis cache already makes repeated prefixes O(1) network.

### 4.2 Approach B — Prefix index (PostgreSQL B-tree)
- **Structure:** B-tree on `query_norm` (+ `count`).
- **Insert/upsert:** **O(log N)** index maintenance.
- **Search:** `LIKE 'p%' ORDER BY count DESC LIMIT 10` → index **range scan**, roughly **O(log N + K)** when a count-aware index is used, otherwise O(log N + matches) + a small sort.
- **Space:** O(N) rows + index.
- Implementation: [`SearchQueryRepository.findTopByPrefix`](backend/src/main/java/com/scaler/typeahead/repository/SearchQueryRepository.java) + [`SuggestionService`](backend/src/main/java/com/scaler/typeahead/service/SuggestionService.java).

### 4.3 Comparison
| Dimension | Trie (in-memory) | Prefix index (DB) |
|-----------|------------------|-------------------|
| Cold lookup latency | fastest (no I/O) | fast (indexed) |
| Memory | high (whole corpus in heap) | low (on disk, cached pages) |
| Durability | none (rebuild on restart) | durable |
| Continuous count updates | awkward (must re-rank top-K) | trivial (`ON CONFLICT`) |
| Horizontal scale | per-instance heap copy | shared store + replicas |
| Multi-word / fuzzy | needs extra structures | `pg_trgm`/FTS available |

### 4.4 Recommendation (defensible)
**Use the DB prefix index as the source of truth, fronted by the Redis cache; keep the Trie as an optional in-memory accelerator.** Rationale: the workload has *continuously changing counts* (batch writes), and a Trie's per-node top-K must be re-ranked on every update — extra complexity for a read path the cache already makes O(1) network on repeats. The classic "Trie is the answer" holds for **static or rarely-updated** dictionaries; here durability + easy re-ranking win. The system is switchable via `suggest.source=DB|TRIE` so both can be demoed and benchmarked.

---

## Phase 5 — Trending Search Design

Implementation: [`TrendingService.java`](backend/src/main/java/com/scaler/typeahead/service/TrendingService.java).

### 5.1 Version 1 — popularity
`SELECT … ORDER BY count DESC LIMIT N` (rides `idx_count`). Simple, stable, but historically huge queries dominate forever.

> **The enhanced ranking is exposed on the SAME suggestion API** as §7 requires:
> `GET /suggest?q=<prefix>&mode=recency` re-ranks the prefix matches by the
> blended score below (`SuggestionService.suggest` → `TrendingService.rankByRecency`),
> while `mode=popularity` (default) keeps the all-time-count order. Each mode is
> cached under its own consistent-hashing key (`suggest:<p>` vs `suggest:recency:<p>`)
> with its own TTL. `GET /trending` exposes the same signal as a global list.

### 5.2 Version 2 — recency + frequency (the +20%)
Keep a Redis ZSET `trending:recency`. On each search: `ZINCRBY` by a **time-growing weight** `w(t) = e^{t/τ}`, `τ = halfLife / ln2`. A query's stored score becomes `S = Σ_i e^{t_i/τ}`.

- **Freshness / recency:** ranking by `S` equals ranking by the **exponentially-decayed** score `e^{-now/τ}·S = Σ_i e^{-(now−t_i)/τ}` (multiplying all scores by the same positive constant preserves order). So recent searches weigh exponentially more than old ones — decay with **no rewriting of old entries**.
- **Frequency:** each search adds another term, so more searches ⇒ higher score.
- **No permanent over-ranking:** a brief spike stops accruing new (large) weights; as `now` advances, its decayed contribution shrinks relative to currently-active queries → it falls off trending naturally.
- **Ranking update / cache invalidation:** the controller caches the computed list for `trending.recompute-interval-ms`; expiry *is* the invalidation, so new rankings appear within one interval without manual purges.

### 5.3 Ranking formula (published score)
```
finalScore(q) = α · normPopularity(q) + (1 − α) · normRecency(q)
  normPopularity = count(q)      / maxCount(candidates)
  normRecency    = recencyScore  / maxRecency(candidates)
  α = trending.alpha (default 0.5)
```
Both signals normalized to [0,1] over the candidate set so they're comparable; `α` tunes the popularity↔recency balance.

### 5.4 Overflow guard
`e^{t/τ}` grows over uptime; if it ever hits `+∞` we rescale the whole ZSET by `1/max` (order-preserving). Fine for assignment/demo timescales; production would re-epoch periodically.

### 5.5 Demoing it in viva
1. `GET /suggest?q=<prefix>&mode=popularity` → note the all-time order of the prefix matches.
2. `POST /search` a *rare* query sharing that prefix ~20 times (a quick loop).
3. `GET /suggest?q=<prefix>&mode=recency` → the freshly-spammed query jumps up the SAME suggestion list even though its all-time count is tiny — this is the basic-vs-enhanced difference on the core API.
4. Wait / keep searching other terms → it decays back down. The UI's **"Rank suggestions by"** toggle shows this live; `GET /trending?mode=recency|popularity` shows the same effect on the global list. Logs print per-search `ZINCRBY`.

---

## Phase 6 — Caching Design

Implementation: [`DistributedCacheService.java`](backend/src/main/java/com/scaler/typeahead/cache/DistributedCacheService.java), [`ConsistentHashRouter.java`](backend/src/main/java/com/scaler/typeahead/cache/ConsistentHashRouter.java).

- **Keys:** `suggest:<normalizedPrefix>` (prefix cache); `trending:recency` ZSET; trending list cached in-process per mode.
- **Value structure:** JSON array of `{query,count}` (whole top-10 per prefix — one GET returns all, one SET replaces all, TTL expires the unit atomically).
- **Distribution:** N logical nodes on a consistent-hash ring (150 virtual nodes each). `route(key)` = first node clockwise from `hash(key)`.
- **TTL strategy:** suggest entries `cache.suggest-ttl-seconds` (default 60 s) → bounds staleness from async writes; trending cached for the recompute interval.
- **Invalidation:** primarily TTL (cheap, eventual). Explicit `invalidate(prefix)` exists for write-through if stronger consistency is needed.
- **Refresh:** lazy (populate on miss). 
- **Warming:** optional — pre-`GET` the hottest prefixes at startup to avoid a cold-cache spike (documented; not on by default).

**Distributed caching & consistency trade-offs:** the cache is *sharded, not replicated* — each key lives on exactly one node, so there's no cross-node coherence problem, but a node loss drops ~1/N of keys (they refill from DB on miss). We accept **eventual consistency**: a freshly incremented count may take up to one TTL to surface. Given the 4:1 read-heavy, deferred-write workload, bounded staleness is the right call vs. the cost of write-through invalidation on every search.

---

## Phase 7 — Batch Writes

Implementation: [`SearchEventProducer`](backend/src/main/java/com/scaler/typeahead/batch/SearchEventProducer.java), [`SearchEventConsumer`](backend/src/main/java/com/scaler/typeahead/batch/SearchEventConsumer.java), [`BatchAggregator`](backend/src/main/java/com/scaler/typeahead/batch/BatchAggregator.java), [`BatchPersister`](backend/src/main/java/com/scaler/typeahead/batch/BatchPersister.java), [`BatchFlushScheduler`](backend/src/main/java/com/scaler/typeahead/batch/BatchFlushScheduler.java).

- **No synchronous DB write.** `POST /search` does one `XADD` to the `search-events` stream and returns.
- **Buffer + aggregate:** a consumer-group thread `XREADGROUP`s events and folds duplicates in memory (`Map<queryNorm, delta>`), so 1,000 "iphone" searches become one `+1000` upsert.
- **Flush triggers:** size threshold (`batch.flush-threshold`, default 500 events) **or** timer (`batch.flush-interval-ms`, default 5 s) — whichever first.
- **One transaction per flush:** `BatchPersister.persist` runs all upserts in a single `@Transactional` commit → N commits collapse to 1.
- **Order of operations = durability:** persist to Postgres **first**, `XACK` **after** the commit. If the process dies before ack, events stay in the consumer group's **Pending Entries List (PEL)** and are replayed on restart (`drainPending()` reads offset `0`). Graceful shutdown does a best-effort final flush.

**Durability & recovery / failure trade-offs:**
- **Crash before XADD returns:** search is lost — but it was never acknowledged to the user as persisted (we only promise enqueue). Acceptable for a counter.
- **Crash after XADD, before flush:** safe — event is durable in Redis (AOF on), replayed via PEL.
- **Crash after persist, before XACK:** event re-delivered → **at-least-once**, so a batch could be applied twice → slight over-count. We minimize the window by ack-ing immediately post-commit. True exactly-once would need a processed-offset table or idempotency keys (documented trade-off; over-counting a popularity counter by a hair is tolerable).
- **Redis loss:** with AOF `appendonly yes`, only sub-second of un-fsynced events are at risk.

---

## Phase 13 — Low Level Design

### 13.1 Class diagram (key types)
```
            ┌────────────────────┐        ┌─────────────────────────┐
            │ SuggestController  │        │ ConsistentHashRouter    │
            └─────────┬──────────┘        │  - ring: TreeMap<Long,  │
                      │ uses              │           Integer>      │
                      ▼                   │  - nodes: List<CacheNode>│
            ┌────────────────────┐        │  + route(key):CacheNode │
            │ SuggestionService  │───────▶│  + routeIndex/positionOf│
            └───┬───────────┬────┘        └───────────┬─────────────┘
       uses     │           │ uses                    │ holds
                ▼           ▼                          ▼
   ┌────────────────┐  ┌─────────────────────┐   ┌──────────────┐
   │ TrieService    │  │ DistributedCache    │──▶│ CacheNode    │
   │  - Trie (atom) │  │  Service            │   │  + redis     │
   └────────────────┘  │  + get/put/invalid  │   │  + size()    │
                       │  + hitRate()        │   └──────────────┘
                       └─────────────────────┘

   ┌────────────────────┐   ┌─────────────────────┐   ┌────────────────────┐
   │ SearchController   │──▶│ SearchService       │──▶│ SearchEventProducer│ XADD
   └────────────────────┘   │  + submit()         │   └────────────────────┘
                            └──────────┬──────────┘
                                       │ recordSearch (ZINCRBY)
                                       ▼
                            ┌─────────────────────┐
                            │ TrendingService     │  v1: repo.findTopByCount
                            │  + trendingBy*()     │  v2: ZSET + blend
                            └─────────────────────┘

   Redis Stream ──▶ SearchEventConsumer ──▶ BatchAggregator ──▶ BatchPersister ──▶ Postgres
                          ▲ (XREADGROUP)         ▲ flush()              (1 txn upserts)
                          └─────────── BatchFlushScheduler (timer) ─────┘
```

### 13.2 Sequence — `GET /suggest?q=ip`
```
Browser    SuggestCtl   SuggestionSvc   Router   CacheNode(redis)   Postgres
  │ debounced │             │             │            │               │
  │──q=ip────▶│             │             │            │               │
  │           │──suggest───▶│             │            │               │
  │           │             │─route(key)─▶│            │               │
  │           │             │◀──node-1────│            │               │
  │           │             │──GET suggest:ip─────────▶│               │
  │           │             │◀──── miss (null) ────────│               │
  │           │             │──findTopByPrefix('ip%')─────────────────▶│
  │           │             │◀──── top-10 rows ────────────────────────│
  │           │             │──SET suggest:ip (TTL)───▶│               │
  │           │◀─SuggestResp─(source=DB,node-1)        │               │
  │◀─JSON─────│             │             │            │               │
  (next identical keystroke → GET hits, source=CACHE)
```

### 13.3 Sequence — `POST /search` + async flush
```
Browser  SearchCtl  SearchSvc  Producer  Stream   Consumer  Aggregator  Persister  PG
  │──q───▶│           │          │         │         │          │           │      │
  │       │──submit──▶│          │         │         │          │           │      │
  │       │           │──XADD───▶│────────▶│         │          │           │      │
  │       │           │──ZINCRBY trending:recency────────────────────────────▶(zset)
  │◀"Searched"────────│          │         │         │          │           │      │
  │       (later, async)                   │─XREADGROUP─▶│       │           │      │
  │                                        │            │──add──▶│           │      │
  │                                 (threshold/timer)   │        │──flush()─▶│      │
  │                                                     │        │           │upsert(batch, 1 txn)▶
  │                                                     │◀────── XACK ────────┘      │
```

### 13.4 Component diagram
```
┌─────────────┐   HTTP    ┌──────────────────────────────────────────┐
│  Frontend   │──────────▶│  Backend (Spring Boot)                    │
│ (nginx/Vite)│           │  ┌─────────┐ ┌─────────┐ ┌──────────────┐ │
└─────────────┘           │  │ web     │ │ cache   │ │ batch        │ │
                          │  │ layer   │ │ layer   │ │ pipeline     │ │
                          │  └─────────┘ └────┬────┘ └──────┬───────┘ │
                          └───────────────────┼────────────┼─────────┘
                                  ┌────────────┴───┐   ┌────┴─────┐
                                  ▼                ▼   ▼          ▼
                             ┌─────────┐  ┌─────────┐ ┌─────────┐ ┌─────────┐
                             │redis-0  │  │redis-1  │ │redis-2  │ │Postgres │
                             │(+stream)│  │(cache)  │ │(cache)  │ │(primary)│
                             └─────────┘  └─────────┘ └─────────┘ └─────────┘
```

---

## Phase 14 — Performance Analysis
See [PERFORMANCE.md](PERFORMANCE.md) for the measured report; the complexity summary:

| Operation | Cache hit | Cache miss / cold |
|-----------|-----------|-------------------|
| Suggest (DB mode) | O(1) routed Redis GET + parse | O(log N + matches) index scan + cache fill |
| Suggest (Trie mode) | O(1) Redis GET | O(P + M·log K) subtree walk |
| Search submit | O(1) `XADD` + O(log n) `ZINCRBY` | same (no DB on hot path) |
| Batch flush | — | O(B) upserts in 1 txn for B distinct queries |
| Trending v1 | cached | O(log N + K) index top-N |
| Trending v2 | cached | O(C·log n) range + O(C) blend, C = candidates |

**DB load reduction:** reads — hit rate `h` ⇒ DB read QPS = `(1−h)·suggestQPS` (e.g. 90% hit on 2.3k QPS → 230 QPS to DB). Writes — `writeReduction% = (1 − distinctQueries/totalSearches)·100`; with heavy duplication a 5 s window of thousands of searches becomes a few hundred upserts. Live numbers at `GET /stats`.

---

## Phase 15 — Trade-offs

| Axis | Option A | Option B | Our choice & why |
|------|----------|----------|------------------|
| **Index** | Trie (in-memory, fastest cold, volatile, hard to re-rank) | DB prefix index (durable, easy upsert/re-rank) | **B** (+ optional A) — counts change continuously; cache hides cold latency |
| **Cache** | Redis distributed (shared, survives restart, network hop) | Local in-process (fastest, per-instance, incoherent, no consistent-hashing story) | **Redis** — assignment grades distribution + consistent hashing; shared cache scales horizontally |
| **Writes** | Immediate per-search (simple, instant consistency, DB write per search) | Batched async (cheap, durable-eventual, tiny duplicate window) | **Batched** — 4:1 read-heavy, deferrable writes; ~100–1000× fewer DB writes |
| **Ranking** | Popularity only (stable, stale) | Recency+frequency decay (fresh, more compute, tunable) | **Both**, recency default — decay prevents permanent over-ranking; popularity kept as v1 baseline |
| **Consistency** | Write-through invalidation (fresh, write-amplified) | TTL expiry (cheap, bounded staleness) | **TTL** — bounded staleness acceptable for a suggestion counter |
| **Delivery** | At-least-once (simple, rare over-count) | Exactly-once (offset table, complex) | **At-least-once** — over-counting a popularity counter slightly is tolerable |
```
