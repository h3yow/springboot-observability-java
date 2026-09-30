package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-endpoint sampling policy, adjustable at runtime.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Per-endpoint rates with one default</b>: each endpoint uses its own
 *       override if configured, otherwise the default rate. Boundary rates are
 *       exact: {@code 0.0} drops every event, {@code 1.0} keeps every event —
 *       no near-boundary float can flip an edge case.</li>
 *   <li><b>One reproducible decision per request</b>: the decision is a pure
 *       function of {@code (requestId, effective rate)} using a stable FNV-1a
 *       hash, and is memoized <em>on the request context itself</em>. The same
 *       request evaluated any number of times — including concurrently from
 *       fan-out branches — always gets the identical outcome. The per-request
 *       memo has at most one entry, so it can never grow unboundedly.</li>
 *   <li><b>Runtime changes take effect immediately for new requests</b>: the
 *       active configuration is one immutable snapshot behind a
 *       {@code volatile} reference. New requests read the new snapshot;
 *       requests already in flight keep their memoized decision, so a mid-flight
 *       change never re-attributes or flips an in-flight request.</li>
 *   <li><b>Counts are exact under concurrency</b>: only the single winning
 *       memoization per request updates the per-endpoint counters, and both the
 *       commit and the count snapshots are linearized on one lock, so decisions
 *       are neither lost nor double-counted and a snapshot can never show a torn
 *       or momentarily shrinking total.</li>
 *   <li><b>No invented attribution</b>: {@code null} contexts are rejected,
 *       never attributed to a synthetic endpoint.</li>
 * </ul>
 */
public final class Sampler {

    /** Resolution of the hash space; rates are quantized to this many buckets. */
    static final long BUCKET_COUNT = 10_000L;

    private record Config(double defaultRate, Map<String, Double> overrides) {
    }

    /**
     * Per-endpoint counters. Both fields are guarded by {@link #countLock}:
     * increments happen exactly once per decided request (the single memo
     * winner) while holding it, and strongly-consistent reads take the same
     * lock. Because every read/write is linearized on one lock the totals are
     * exact — never lost, duplicated, torn or momentarily shrinking — even
     * with concurrent decisions and concurrent rate changes. The lock is
     * uncontended on the hot path apart from that one commit per request.
     */
    private static final class EndpointStats {
        private long considered;
        private long sampled;
    }

    /**
     * Active policy: one immutable snapshot swapped atomically. Reads on the
     * hot path are a single volatile read.
     */
    private final AtomicReference<Config> config;
    private final ConcurrentHashMap<String, EndpointStats> statsByEndpoint = new ConcurrentHashMap<>();

    /**
     * Serializes the single per-request winning count against strongly-consistent
     * count snapshots. It is taken once per request (only by the thread that wins
     * the request's memo), so the hot path still performs one volatile read plus a
     * cheap uncontended monitor. A snapshot taken under this lock has a
     * happens-before edge with every count committed before it, so it never shows
     * torn or momentarily-shrinking totals even while other threads are deciding.
     */
    private final Object countLock = new Object();

    public Sampler(double defaultRate, Map<String, Double> perEndpointRates) {
        validateRate(defaultRate, "defaultRate");
        Map<String, Double> overrides = new LinkedHashMap<>();
        if (perEndpointRates != null) {
            perEndpointRates.forEach((endpoint, rate) -> {
                validateEndpoint(endpoint);
                validateRate(rate, "rate for endpoint " + endpoint);
                overrides.put(endpoint, rate);
            });
        }
        this.config = new AtomicReference<>(
                new Config(defaultRate, Collections.unmodifiableMap(overrides)));
    }

    /**
     * Evaluates (or recalls) the sampling decision for the given request.
     *
     * @throws NullPointerException if {@code context} is null — attribution
     *         must never be fabricated.
     */
    public SamplingDecision decide(RequestContext context) {
        if (context == null) {
            throw new NullPointerException("context must not be null; sampling never fabricates attribution");
        }
        SamplingDecision candidate = evaluate(context, config.get());
        SamplingDecision winner = context.memoizeSamplingDecision(candidate);
        if (winner == candidate) {
            // This thread installed the first (and only) decision for the request.
            // Commit both counters under countLock so a strongly-consistent
            // snapshot can never observe considered without the matching sampled
            // increment (or vice-versa) and totals never appear to shrink.
            EndpointStats stats;
            synchronized (countLock) {
                stats = statsByEndpoint.computeIfAbsent(winner.endpoint(), k -> new EndpointStats());
                stats.considered++;
                if (winner.sampled()) {
                    stats.sampled++;
                }
            }
        }
        return winner;
    }

    private static SamplingDecision evaluate(RequestContext context, Config snapshot) {
        Double override = snapshot.overrides().get(context.endpoint());
        boolean endpointOverride = override != null;
        double rate = endpointOverride ? override : snapshot.defaultRate();
        long bucket = Math.floorMod((long) fnv1a32(context.requestId()), BUCKET_COUNT);
        long threshold = thresholdOf(rate);
        return new SamplingDecision(bucket < threshold, context.requestId(), context.endpoint(),
                rate, endpointOverride, bucket, threshold);
    }

    private static long thresholdOf(double rate) {
        if (rate <= 0.0d) {
            return 0L;                          // exact drop-all
        }
        if (rate >= 1.0d) {
            return BUCKET_COUNT;               // exact keep-all
        }
        return Math.round(rate * BUCKET_COUNT); // interior: at least 1, at most N-1
    }

    /**
     * Stable, JVM-independent 32-bit FNV-1a hash of the request id. Unlike
     * {@link String#hashCode()} it is documented and distribution-friendly.
     */
    static int fnv1a32(String value) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    // ---- Runtime configuration --------------------------------------------

    /** Atomically replaces the default rate used by unconfigured endpoints. */
    public void setDefaultRate(double rate) {
        validateRate(rate, "defaultRate");
        Config current;
        Config next;
        do {
            current = config.get();
            next = new Config(rate, current.overrides());
        } while (!config.compareAndSet(current, next));
    }

    /**
     * Sets (or, when {@code rate} is null, removes) the override for one
     * endpoint. Takes effect for requests that have not decided yet.
     */
    public void setEndpointRate(String endpoint, Double rate) {
        validateEndpoint(endpoint);
        Config current;
        Config next;
        do {
            current = config.get();
            Map<String, Double> overrides = new LinkedHashMap<>(current.overrides());
            if (rate == null) {
                overrides.remove(endpoint);
            } else {
                validateRate(rate, "rate for endpoint " + endpoint);
                overrides.put(endpoint, rate);
            }
            next = new Config(current.defaultRate(), Collections.unmodifiableMap(overrides));
        } while (!config.compareAndSet(current, next));
    }

    /** Removes an endpoint override; the endpoint then follows the default. */
    public void removeEndpointRate(String endpoint) {
        setEndpointRate(endpoint, null);
    }

    /** Immutable snapshot of the active policy. */
    public Map<String, Object> configSnapshot() {
        Config snapshot = config.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultRate", snapshot.defaultRate());
        out.put("endpointRates", new TreeMap<>(snapshot.overrides()));
        return out;
    }

    /** Rate currently applied to the endpoint: override if present, else default. */
    public double effectiveRateFor(String endpoint) {
        Config snapshot = config.get();
        Double override = snapshot.overrides().get(endpoint);
        return override != null ? override : snapshot.defaultRate();
    }

    /**
     * Per-endpoint decision stats: how many requests were considered, how many
     * were kept, and the rate currently in effect (with its source). This is
     * the "what is actually in use right now" view.
     *
     * <p>Counters are read under {@link #countLock}, so the returned rows are a
     * strongly-consistent point-in-time snapshot: {@code considered},
     * {@code sampled} and {@code notSampled} are exact (the invariant
     * {@code considered == sampled + notSampled} holds for every row) even while
     * other threads are deciding or changing the rate.
     */
    public Map<String, Object> statsSnapshot() {
        Config snapshot = config.get();
        Map<String, Object> out = new TreeMap<>();
        synchronized (countLock) {
            statsByEndpoint.forEach((endpoint, stats) -> {
                long considered = stats.considered;
                long sampled = stats.sampled;
                Double override = snapshot.overrides().get(endpoint);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("considered", considered);
                row.put("sampled", sampled);
                row.put("notSampled", considered - sampled);
                row.put("sampleRate", override != null ? override : snapshot.defaultRate());
                row.put("sampleRateSource", override != null ? "endpoint" : "default");
                out.put(endpoint, row);
            });
        }
        return out;
    }

    /**
     * Total events deliberately suppressed by the sampling policy. Read under
     * {@link #countLock}, so the value is a strongly-consistent exact total that
     * neither misses nor double-counts a decision, even under concurrent
     * decisions and concurrent rate changes.
     */
    public long notSampledCount() {
        synchronized (countLock) {
            long total = 0L;
            for (EndpointStats stats : statsByEndpoint.values()) {
                total += stats.considered - stats.sampled;
            }
            return total;
        }
    }

    /**
     * Total requests considered (decided) so far, summed under
     * {@link #countLock} for an exact, strongly-consistent value.
     */
    public long consideredCount() {
        synchronized (countLock) {
            long total = 0L;
            for (EndpointStats stats : statsByEndpoint.values()) {
                total += stats.considered;
            }
            return total;
        }
    }

    // ---- Validation --------------------------------------------------------

    private static void validateRate(double rate, String name) {
        if (Double.isNaN(rate) || rate < 0.0d || rate > 1.0d) {
            throw new IllegalArgumentException(name + " must be within [0.0, 1.0], got " + rate);
        }
    }

    private static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be null or blank");
        }
    }
}
