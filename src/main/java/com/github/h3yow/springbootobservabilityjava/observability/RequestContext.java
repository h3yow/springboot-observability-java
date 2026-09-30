package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    /**
     * Memoized sampling decision for this request — at most one entry for the
     * whole request lifetime, so decision caches can never grow unboundedly.
     */
    private final AtomicReference<SamplingDecision> samplingDecision = new AtomicReference<>();

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

    /**
     * Atomically memoizes the request's sampling decision. The first candidate
     * wins, so the same request evaluated many times (including from concurrent
     * branches) always observes the identical decision.
     *
     * @return the winning decision — {@code candidate} on the first call,
     *         the previously installed one afterwards.
     */
    SamplingDecision memoizeSamplingDecision(SamplingDecision candidate) {
        return samplingDecision.compareAndSet(null, candidate)
                ? candidate
                : samplingDecision.get();
    }

    /**
     * Returns the decision already pinned for this request, or {@code null}
     * if it has never been evaluated. Lets callers reuse the pinned decision
     * without paying for (or racing on) a fresh evaluation.
     */
    SamplingDecision samplingDecision() {
        return samplingDecision.get();
    }

    @Override
    public String toString() {
        return "RequestContext{requestId=" + requestId + ", endpoint=" + endpoint + "}";
    }
}
