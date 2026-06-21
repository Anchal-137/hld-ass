package com.scaler.typeahead.service;

import com.scaler.typeahead.dto.SuggestionDto;
import com.scaler.typeahead.dto.TrendingResponse;
import com.scaler.typeahead.entity.SearchQuery;
import com.scaler.typeahead.repository.SearchQueryRepository;
import com.scaler.typeahead.util.PrefixUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Trending searches, in two versions (Phase 5 of the assignment).
 *
 * <h3>Version 1 - POPULARITY</h3>
 * Pure all-time count: {@code SELECT ... ORDER BY count DESC LIMIT N}. Stable but
 * dominated forever by historically huge queries.
 *
 * <h3>Version 2 - RECENCY + FREQUENCY (the +20% enhancement)</h3>
 * We keep a Redis sorted set {@code trending:recency}. On every search we do
 * {@code ZINCRBY} by a <i>time-growing weight</i> {@code w(t) = e^(t/tau)} where
 * {@code tau = halfLife / ln2}. The stored score of a query is therefore
 * {@code S = sum_i e^(t_i/tau)} over its searches.
 *
 * <p>Ranking by {@code S} is mathematically identical to ranking by the
 * <i>exponentially-decayed</i> recency score {@code e^(-now/tau) * S =
 * sum_i e^(-(now - t_i)/tau)} - multiplying every score by the same positive
 * constant {@code e^(-now/tau)} preserves order. This gives us decay "for free"
 * without ever rewriting old scores:
 * <ul>
 *   <li><b>Recent searches are tracked</b> by the ZINCRBY with a growing weight.</li>
 *   <li><b>Recent activity raises rank</b> because newer searches contribute
 *       exponentially more weight than old ones.</li>
 *   <li><b>No permanent over-ranking:</b> a query that was hot only briefly stops
 *       receiving new (large) weights, so as {@code now} advances its decayed
 *       contribution shrinks relative to currently-active queries.</li>
 * </ul>
 *
 * <p>The final published score blends recency with all-time popularity:
 * {@code finalScore = alpha * normPopularity + (1 - alpha) * normRecency}, both
 * normalized to [0,1] over the candidate set, so the two signals are comparable.
 *
 * <p><b>Cache invalidation on ranking change:</b> the trending list is recomputed
 * on a schedule and the {@code TrendingController} caches it with a short TTL
 * ({@code trending.recompute-interval}), so a new ranking naturally propagates
 * within one interval without manual invalidation.
 */
@Service
public class TrendingService {

    private static final Logger log = LoggerFactory.getLogger(TrendingService.class);
    private static final String RECENCY_KEY = "trending:recency";

    // exp() overflows to +Infinity around an exponent of 709. We re-base the
    // time origin well before that so weights stay finite forever.
    private static final double MAX_EXPONENT = 600.0;

    private final StringRedisTemplate redis; // primary (streams) Redis
    private final SearchQueryRepository repository;
    private final double alpha;
    private final double tau;       // decay time-constant in seconds
    private final int trendingSize;

    /**
     * Reference time origin for the recency weight. CRITICAL: the weight must be
     * {@code exp(secondsSinceThisOrigin / tau)}, NOT {@code exp(absoluteEpoch / tau)}
     * - the latter is ~1.78e9/5193 ≈ 342000, and {@code exp(342000)} is Infinity,
     * which poisons the ZSET and makes every subsequent search fail. Anchoring at
     * startup keeps the exponent near 0 and growing slowly.
     */
    private volatile long originMillis = System.currentTimeMillis();

    public TrendingService(StringRedisTemplate streamsRedisTemplate,
                           SearchQueryRepository repository,
                           @Value("${trending.alpha:0.5}") double alpha,
                           @Value("${trending.decay-half-life-seconds:3600}") double halfLife,
                           @Value("${trending.size:10}") int trendingSize) {
        this.redis = streamsRedisTemplate;
        this.repository = repository;
        this.alpha = alpha;
        this.tau = halfLife / Math.log(2.0);
        this.trendingSize = trendingSize;
    }

    /** Record a search for the recency signal. Called on every POST /search. */
    public void recordSearch(String queryNorm) {
        double exponent = elapsedSeconds() / tau;
        if (exponent > MAX_EXPONENT) {
            // Approaching overflow (only after weeks of uptime): shift the origin
            // forward and shrink existing scores by the same factor, preserving order.
            rebase();
            exponent = elapsedSeconds() / tau;
        }
        double weight = Math.exp(exponent);
        redis.opsForZSet().incrementScore(RECENCY_KEY, queryNorm, weight);
    }

    /**
     * Re-rank a set of prefix candidates by the blended recency + frequency
     * score, returning the top {@code limit}. This is what powers the ENHANCED
     * ranking on {@code GET /suggest?mode=recency}: the same suggestion API,
     * same candidates, but ordered by {@code alpha*normPopularity +
     * (1-alpha)*normRecency} instead of raw count. A query that was searched a
     * lot recently rises above an all-time-popular-but-stale query.
     *
     * @param candidates prefix matches (display query + all-time count), any order
     * @param limit      number of results to return
     */
    public List<SuggestionDto> rankByRecency(List<SuggestionDto> candidates, int limit) {
        if (candidates.isEmpty()) {
            return candidates;
        }
        double maxRecency = 0.0;
        long maxPop = 1;
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (SuggestionDto c : candidates) {
            String norm = PrefixUtil.normalize(c.query());
            Double rs = redis.opsForZSet().score(RECENCY_KEY, norm);
            double rec = rs == null ? 0.0 : rs;
            scored.add(new Scored(c.query(), c.count(), rec));
            maxRecency = Math.max(maxRecency, rec);
            maxPop = Math.max(maxPop, c.count());
        }
        final double fMaxRecency = maxRecency == 0 ? 1 : maxRecency;
        final long fMaxPop = maxPop;
        scored.sort(Comparator.comparingDouble(
                (Scored s) -> alpha * ((double) s.count() / fMaxPop)
                        + (1 - alpha) * (s.recency() / fMaxRecency)).reversed());
        return scored.stream()
                .limit(limit)
                .map(s -> new SuggestionDto(s.query(), s.count()))
                .toList();
    }

    /** Candidate with its blended-ranking inputs. */
    private record Scored(String query, long count, double recency) {
    }

    /** Version 1: trending by all-time popularity. */
    public TrendingResponse trendingByPopularity() {
        long start = System.nanoTime();
        List<SearchQuery> rows = repository.findTopByCount(PageRequest.of(0, trendingSize));
        List<SuggestionDto> list = rows.stream()
                .map(r -> new SuggestionDto(r.getQuery(), r.getCount()))
                .toList();
        return new TrendingResponse("POPULARITY", list, "COMPUTED", elapsedMs(start));
    }

    /** Version 2: trending by blended recency + frequency. */
    public TrendingResponse trendingByRecency() {
        long start = System.nanoTime();

        // Pull more candidates than we need so the blend can re-order them.
        int candidates = Math.max(trendingSize * 3, trendingSize);
        Set<ZSetOperations.TypedTuple<String>> top =
                redis.opsForZSet().reverseRangeWithScores(RECENCY_KEY, 0, candidates - 1);

        if (top == null || top.isEmpty()) {
            // Cold start (no searches yet) -> fall back to popularity.
            TrendingResponse pop = trendingByPopularity();
            return new TrendingResponse("RECENCY", pop.suggestions(), "FALLBACK_POPULARITY", elapsedMs(start));
        }

        // Build candidate list with raw recency scores.
        List<Candidate> cands = new ArrayList<>();
        double maxRecency = 0.0;
        for (ZSetOperations.TypedTuple<String> t : top) {
            double rec = t.getScore() == null ? 0.0 : t.getScore();
            cands.add(new Candidate(t.getValue(), rec, 0));
            maxRecency = Math.max(maxRecency, rec);
        }

        // Look up all-time popularity for the candidate set. NOTE: the ZSET member
        // is the NORMALIZED query, but the table PK is the DISPLAY text, so we must
        // look up by query_norm (not findById) or mixed-case queries would read 0.
        long maxPop = 1;
        for (Candidate c : cands) {
            long pop = repository.findFirstByQueryNormOrderByCountDesc(c.query)
                    .map(SearchQuery::getCount).orElse(0L);
            c.popularity = pop;
            maxPop = Math.max(maxPop, pop);
        }

        // Blend normalized signals.
        double finalMaxRecency = maxRecency == 0 ? 1 : maxRecency;
        long finalMaxPop = maxPop;
        cands.sort(Comparator.comparingDouble(
                (Candidate c) -> alpha * ((double) c.popularity / finalMaxPop)
                        + (1 - alpha) * (c.recency / finalMaxRecency)).reversed());

        List<SuggestionDto> list = cands.stream()
                .limit(trendingSize)
                // Expose the all-time count to the UI (recency drives ORDER, count is shown).
                .map(c -> new SuggestionDto(c.query, c.popularity))
                .toList();

        return new TrendingResponse("RECENCY", list, "COMPUTED", elapsedMs(start));
    }

    /**
     * Move the time origin to "now" and divide every stored score by the same
     * decay factor {@code exp(shift/tau)}. Because all scores shrink by an
     * identical positive factor, their RELATIVE order is unchanged, but the
     * absolute magnitudes drop back near 1 - so {@code exp()} can never overflow.
     * Only needed after weeks of continuous uptime.
     */
    private synchronized void rebase() {
        long now = System.currentTimeMillis();
        double shiftSeconds = (now - originMillis) / 1000.0;
        double divisor = Math.exp(shiftSeconds / tau);
        if (!(divisor > 0) || Double.isInfinite(divisor)) {
            return; // can't safely rescale; leave as-is
        }
        Set<ZSetOperations.TypedTuple<String>> everything =
                redis.opsForZSet().rangeWithScores(RECENCY_KEY, 0, -1);
        if (everything != null) {
            for (ZSetOperations.TypedTuple<String> t : everything) {
                if (t.getValue() != null && t.getScore() != null) {
                    redis.opsForZSet().add(RECENCY_KEY, t.getValue(), t.getScore() / divisor);
                }
            }
        }
        originMillis = now;
        log.info("Re-based trending recency origin; divided scores by {}", divisor);
    }

    /** Seconds since the (re-baseable) time origin - keeps the exp() exponent small. */
    private double elapsedSeconds() {
        return (System.currentTimeMillis() - originMillis) / 1000.0;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Mutable holder used during blending. */
    private static final class Candidate {
        final String query;
        final double recency;
        long popularity;

        Candidate(String query, double recency, long popularity) {
            this.query = query;
            this.recency = recency;
            this.popularity = popularity;
        }
    }
}
