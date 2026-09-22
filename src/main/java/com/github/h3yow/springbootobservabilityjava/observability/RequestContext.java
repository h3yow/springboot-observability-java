package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Objects;
import java.util.UUID;

/**
 * Immutable request-scoped execution context.
 *
 * <p>A context is created exactly once per inbound request and is the single
 * source of truth for request attribution. All async/concurrent branches of
 * the same request share the same instance; it is never mutated after
 * creation, so it can safely cross thread boundaries.
 */
public final class RequestContext {

    private final String requestId;
    private final String endpoint;
    private final long startNanos;

    private RequestContext(String requestId, String endpoint, long startNanos) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.startNanos = startNanos;
    }

    /** Creates a context with a freshly generated request id. */
    public static RequestContext create(String endpoint) {
        return new RequestContext(UUID.randomUUID().toString(), endpoint, System.nanoTime());
    }

    /**
     * Creates a context carrying an externally supplied request id
     * (e.g. from an inbound {@code X-Request-Id} header).
     */
    public static RequestContext create(String requestId, String endpoint) {
        if (requestId == null || requestId.isBlank()) {
            return create(endpoint);
        }
        return new RequestContext(requestId, endpoint, System.nanoTime());
    }

    public String requestId() {
        return requestId;
    }

    public String endpoint() {
        return endpoint;
    }

    public long startNanos() {
        return startNanos;
    }

    /** Elapsed time since the request started, in nanoseconds. */
    public long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    @Override
    public String toString() {
        return "RequestContext{requestId=" + requestId + ", endpoint=" + endpoint + "}";
    }
}
