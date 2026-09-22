package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Objects;

/**
 * A single immutable metrics event to be exported.
 *
 * <p>Carries the originating request id so exported data can always be
 * traced back to exactly one request.
 */
public final class MetricsEvent {

    private final String requestId;
    private final String endpoint;
    private final boolean success;
    private final long latencyNanos;

    public MetricsEvent(String requestId, String endpoint, boolean success, long latencyNanos) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.success = success;
        this.latencyNanos = latencyNanos;
    }

    public String requestId() {
        return requestId;
    }

    public String endpoint() {
        return endpoint;
    }

    public boolean success() {
        return success;
    }

    public long latencyNanos() {
        return latencyNanos;
    }

    @Override
    public String toString() {
        return "MetricsEvent{requestId=" + requestId + ", endpoint=" + endpoint
                + ", success=" + success + ", latencyMs=" + (latencyNanos / 1_000_000.0) + "}";
    }
}
