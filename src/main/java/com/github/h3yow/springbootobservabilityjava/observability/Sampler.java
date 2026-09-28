package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-endpoint, runtime-adjustable head sampling for metrics export.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Per-endpoint ratios with a default</b>: endpoints with an explicit
 *       override use it; everything else uses the default ratio.</li>
 *   <li><b>Exact boundaries</b>: ratio {@code 1.0} always exports the request
 *       (never rejected by sampling) and ratio {@code 0.0} never exports; the
 *       boundary cases are decided before any hashing, so configuration and
 *       behavior agree at both ends.</li>
 *   <li><b>Runtime adjustment</b>: {@link #updateDefaultRatio(double)} /
 *       {@link #updateEndpointRatio(String, double)} publish an immutable
 *       configuration copy atomically; new requests observe the new ratio
 *       immediately while requests already decided keep their memoized
 *       decision (a decision belongs to exactly one request for its whole
 *       lifetime).</li>
 *   <li><b>Reproducible verdict per request</b>: the decision is derived
 *       deterministically from the request id and memoized on the
 *       {@link RequestContext}; evaluating the same request again returns the
 *       same verdict and is counted exactly once.</li>
 *   <li><b>Bounded state</b>: decisions are cached on the request object
 *       itself — there is no external decision cache that can grow without
 *       bound.</li>
 *   <li><b>No fabricated attribution</b>: a missing context is rejected
 *       instead of being sampled under a guessed endpoint.</li>
 * </ul>
 */
public final class Sampler {

    /** Granularity of interior ratios: 1/SCALE steps (0.01%). */
    static final int SCALE = 10_000;

    private static final String BASIS_KEEP_ALL = "keep-all(1.0)";
    private static final String BASIS_DROP_ALL = "drop-all(0.0)";

    /** Immutable, atomically replaceable configuration snapshot. */
    private static final class Config {
        private final double defaultRatio;
        private final Map<String, Double> endpoints;

        private Config(double defaultRatio, Map<String, Double> endpoints) {
            this.defaultRatio = defaultRatio;
            this.endpoints = Map.copyOf(endpoints);
        }
    }

    /** Per-endpoint decision counters; only the first decision of a request counts. */
    private static final class EndpointSampleStats {
        private final LongAdder decisions = new LongAdder();
        private final LongAdder sampledIn = new LongAdder();
        private final LongAdder sampledOut = new LongAdder();
    }

    private volatile Config config;
    private final ConcurrentHashMap<String, EndpointSampleStats> stats = new ConcurrentHashMap<>();
    private final LongAdder totalSampledOut = new LongAdder();

    public Sampler(double defaultRatio) {
        this(defaultRatio, Map.of());
    }

    public Sampler(double defaultRatio, Map<String, Double> endpointRatios) {
        validateRatio(defaultRatio, "defaultRatio");
        Map<String, Double> validated = new LinkedHashMap<>();
        if (endpointRatios != null) {
            endpointRatios.forEach((endpoint, ratio) -> {
                if (endpoint == null || endpoint.isBlank()) {
                    throw new IllegalArgumentException("endpoint override key must not be blank");
                }
                validateRatio(ratio, "ratio for endpoint " + endpoint);
                validated.put(endpoint, ratio);
            });
        }
        this.config = new Config(defaultRatio, validated);
    }

    /**
     * Decides whether the given request's metrics event is exported.
     *
     * <p>The decision is computed once per request and memoized on its
     * context, so repeated calls from any branch of the same request return
     * the identical verdict and counters move exactly once.
     *
     * @throws IllegalArgumentException if {@code context} is {@code null} —
     *         sampling without attribution must never invent an endpoint.
     */
    public SamplingDecision decide(RequestContext context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "cannot sample without a RequestContext: attribution must not be invented");
        }
        SamplingDecision memoized = context.samplingDecision();
        if (memoized != null) {
            return memoized;
        }
        // Synchronize on the request itself: concurrent branches of the same
        // request compute the decision jointly; unrelated requests never block
        // each other (each context is a distinct monitor).
        synchronized (context) {
            memoized = context.samplingDecision();
            if (memoized != null) {
                return memoized;
            }
            SamplingDecision decision = compute(context);
            record(decision);
            context.setSamplingDecision(decision);
            return decision;
        }
    }

    private SamplingDecision compute(RequestContext context) {
        String endpoint = context.endpoint();
        Config snapshot = this.config;
        Double override = snapshot.endpoints.get(endpoint);
        double ratio = override != null ? override : snapshot.defaultRatio;
        String source = override != null ? "endpoint-override" : "default";

        if (ratio <= 0.0) {
            return new SamplingDecision(false, endpoint, ratio, source + ":" + BASIS_DROP_ALL);
        }
        if (ratio >= 1.0) {
            return new SamplingDecision(true, endpoint, ratio, source + ":" + BASIS_KEEP_ALL);
        }
        long threshold = Math.round(ratio * SCALE); // 1..SCALE-1 for interior ratios
        int bucket = bucket(context.requestId());
        boolean sampled = bucket < threshold;
        return new SamplingDecision(sampled, endpoint, ratio,
                source + ":hash(" + bucket + "/" + SCALE + ")<" + threshold);
    }

    /** Deterministic, well-distributed bucket in [0, SCALE) for a request id. */
    private static int bucket(String requestId) {
        // FNV-1a-ish 32-bit mix; no randomness/seed, so the same request id
        // maps to the same bucket on every machine and every run.
        int hash = 0x811c9dc5;
        for (int i = 0; i < requestId.length(); i++) {
            char c = requestId.charAt(i);
            hash ^= c & 0xff;
            hash *= 0x01000193;
            hash ^= (c >>> 8) & 0xff;
            hash *= 0x01000193;
        }
        return Math.floorMod(hash, SCALE);
    }

    private void record(SamplingDecision decision) {
        EndpointSampleStats s = stats.computeIfAbsent(decision.endpoint(), k -> new EndpointSampleStats());
        s.decisions.increment();
        if (decision.sampled()) {
            s.sampledIn.increment();
        } else {
            s.sampledOut.increment();
            totalSampledOut.increment();
        }
    }

    /** Ratio currently in force for the endpoint: override if present, else default. */
    public double effectiveRatio(String endpoint) {
        Config snapshot = this.config;
        Double override = snapshot.endpoints.get(endpoint);
        return override != null ? override : snapshot.defaultRatio;
    }

    /** Replaces the default ratio. New requests observe it immediately. */
    public void updateDefaultRatio(double ratio) {
        validateRatio(ratio, "defaultRatio");
        synchronized (this) {
            config = new Config(ratio, config.endpoints);
        }
    }

    /**
     * Sets the override for one endpoint and returns the ratio now in force.
     */
    public double updateEndpointRatio(String endpoint, double ratio) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        validateRatio(ratio, "ratio for endpoint " + endpoint);
        synchronized (this) {
            Map<String, Double> next = new LinkedHashMap<>(config.endpoints);
            next.put(endpoint, ratio);
            config = new Config(config.defaultRatio, next);
        }
        return ratio;
    }

    /**
     * Removes an endpoint override so the endpoint falls back to the default;
     * returns the ratio now in force for it.
     */
    public double removeEndpointOverride(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint must not be blank");
        }
        synchronized (this) {
            if (config.endpoints.containsKey(endpoint)) {
                Map<String, Double> next = new LinkedHashMap<>(config.endpoints);
                next.remove(endpoint);
                config = new Config(config.defaultRatio, next);
            }
        }
        return effectiveRatio(endpoint);
    }

    static void validateRatio(double ratio, String what) {
        if (Double.isNaN(ratio) || ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException(what + " must be within [0,1], got " + ratio);
        }
    }

    /** Total requests deliberately held back by sampling across all endpoints. */
    public long sampledOutCount() {
        return totalSampledOut.sum();
    }

    /**
     * Readable snapshot: configured default/overrides, the effective ratio in
     * force per seen endpoint, and per-endpoint decision counters.
     */
    public Map<String, Object> snapshot() {
        Config snapshot = this.config;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultRatio", snapshot.defaultRatio);
        out.put("endpointOverrides", new TreeMap<>(snapshot.endpoints));

        Map<String, Double> effective = new TreeMap<>();
        // Union of explicitly configured endpoints and endpoints with traffic.
        snapshot.endpoints.forEach((endpoint, ratio) -> effective.put(endpoint, ratio));
        stats.forEach((endpoint, ignored) -> effective.put(endpoint, effectiveRatio(endpoint)));
        out.put("effectiveRatios", effective);

        Map<String, Object> perEndpoint = new TreeMap<>();
        stats.forEach((endpoint, s) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("effectiveRatio", effectiveRatio(endpoint));
            row.put("decisions", s.decisions.sum());
            row.put("sampledIn", s.sampledIn.sum());
            row.put("sampledOut", s.sampledOut.sum());
            perEndpoint.put(endpoint, row);
        });
        out.put("stats", perEndpoint);
        out.put("totalSampledOut", totalSampledOut.sum());
        return out;
    }
}
