package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

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
 *   <li><b>Runtime changes take effect only for requests that start afterwards</b>:
 *       the active configuration is one immutable snapshot behind a
 *       {@code volatile} reference. The pipeline evaluates a request once, at
 *       request entry <em>before business processing begins</em>, and pins the
 *       result on its context; completion (and fan-out branches) only recalls
 *       it. A rate changed while a request is in flight therefore never flips
 *       that request or leaks into another one — only requests whose first
 *       evaluation happens after the swap see the new rate. The request that
 *       performs the change is itself decided on entry, under the old rate.</li>
 *   <li><b>Counts are exact under concurrency</b>: only the single winning
 *       memoization per request updates {@link LongAdder} stats, so decisions
 *       are neither lost nor double-counted.</li>
 *   <li><b>No invented attribution</b>: {@code null} contexts are rejected,
 *       never attributed to a synthetic endpoint.</li>
 * </ul>
 */
public final class Sampler {

    /** Resolution of the hash space; rates are quantized to this many buckets. */
    static final long BUCKET_COUNT = 10_000L;

    private record Config(double defaultRate, Map<String, Double> overrides) {
    }

    private static final class EndpointStats {
        /** Requests the policy kept (may be exported). */
        private final LongAdder kept = new LongAdder();
        /** Requests the policy deliberately suppressed. */
        private final LongAdder dropped = new LongAdder();
    }

    /**
     * Active policy: one immutable snapshot swapped atomically. Reads on the
     * hot path are a single volatile read.
     */
    private final AtomicReference<Config> config;
    private final ConcurrentHashMap<String, EndpointStats> statsByEndpoint = new ConcurrentHashMap<>();

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
        // Fast path: a decision already pinned for this request (e.g. it was
        // made at request entry and is recalled on completion, or by a fan-out
        // branch). Never re-evaluate: the pinned rate/outcome must stay stable
        // even while the active config churns on other threads.
        SamplingDecision pinned = context.samplingDecision();
        if (pinned != null) {
            return pinned;
        }
        SamplingDecision candidate = evaluate(context, config.get());
        SamplingDecision winner = context.memoizeSamplingDecision(candidate);
        if (winner == candidate) {
            // This thread installed the first (and only) decision for the request.
            // Exactly one of kept/dropped is incremented per request, so any
            // concurrent reader always observes considered == kept + dropped.
            EndpointStats stats = statsByEndpoint.computeIfAbsent(winner.endpoint(), k -> new EndpointStats());
            if (winner.sampled()) {
                stats.kept.increment();
            } else {
                stats.dropped.increment();
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
     */
    public Map<String, Object> statsSnapshot() {
        Config snapshot = config.get();
        Map<String, Object> out = new TreeMap<>();
        statsByEndpoint.forEach((endpoint, stats) -> {
            long sampled = stats.kept.sum();
            long notSampled = stats.dropped.sum();
            long considered = sampled + notSampled;
            Double override = snapshot.overrides().get(endpoint);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("considered", considered);
            row.put("sampled", sampled);
            row.put("notSampled", notSampled);
            row.put("sampleRate", override != null ? override : snapshot.defaultRate());
            row.put("sampleRateSource", override != null ? "endpoint" : "default");
            out.put(endpoint, row);
        });
        return out;
    }

    /** Total events deliberately suppressed by the sampling policy. */
    public long notSampledCount() {
        // Sequential traversal with a local accumulator: forEachValue with a
        // parallelism threshold >1 may run updates on multiple threads, and
        // "+=" on a shared cell is not atomic — that used to lose increments.
        long[] total = {0L};
        statsByEndpoint.forEachValue(Long.MAX_VALUE, stats -> total[0] += stats.dropped.sum());
        return total[0];
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
