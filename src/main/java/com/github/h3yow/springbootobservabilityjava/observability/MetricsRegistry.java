package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of per-endpoint metrics.
 *
 * <p>Endpoints are keyed by their normalized name (e.g. {@code GET /demo/work}).
 * Registration is idempotent and thread-safe; recording is wait-free on the
 * hot path ({@link ConcurrentHashMap} + {@link EndpointMetrics} adders).
 */
public class MetricsRegistry {

    private final ConcurrentHashMap<String, EndpointMetrics> byEndpoint = new ConcurrentHashMap<>();

    /**
     * Records one completed request for the given endpoint. Safe for
     * concurrent use; counts are never lost or duplicated as long as each
     * request calls this exactly once.
     */
    public void record(String endpoint, boolean success, long latencyNanos) {
        byEndpoint.computeIfAbsent(endpoint, k -> new EndpointMetrics())
                .record(success, latencyNanos);
    }

    /** Returns the metrics for one endpoint, or {@code null} if unseen. */
    public EndpointMetrics metricsOf(String endpoint) {
        return byEndpoint.get(endpoint);
    }

    /** Returns a sorted snapshot of all endpoints for local reads. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new TreeMap<>();
        byEndpoint.forEach((endpoint, metrics) -> out.put(endpoint, metrics.snapshot()));
        return out;
    }
}
