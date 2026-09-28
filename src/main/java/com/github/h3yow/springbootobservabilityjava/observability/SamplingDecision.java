package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Objects;

/**
 * Immutable result of one head-sampling decision for one request.
 *
 * <p>The decision captures everything needed to explain it: which endpoint it
 * was attributed to, the ratio that was effectively in force at decision time,
 * and a human-readable basis ({@code default} / {@code endpoint-override} /
 * {@code boundary-keep-all} / {@code boundary-drop-all}). Decisions are
 * memoized on the {@link RequestContext}, so re-evaluating a request yields
 * the same instance and therefore the same verdict.
 */
public final class SamplingDecision {

    private final boolean sampled;
    private final String endpoint;
    private final double ratio;
    private final String basis;

    public SamplingDecision(boolean sampled, String endpoint, double ratio, String basis) {
        this.sampled = sampled;
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.basis = Objects.requireNonNull(basis, "basis");
        this.ratio = ratio;
    }

    /** Whether the request's event should be exported. */
    public boolean sampled() {
        return sampled;
    }

    /** Endpoint the decision was attributed to. */
    public String endpoint() {
        return endpoint;
    }

    /** Ratio effectively in force when the decision was made, in [0,1]. */
    public double ratio() {
        return ratio;
    }

    /** Human-readable explanation of which rule produced the decision. */
    public String basis() {
        return basis;
    }

    @Override
    public String toString() {
        return "SamplingDecision{sampled=" + sampled + ", endpoint=" + endpoint
                + ", ratio=" + ratio + ", basis=" + basis + "}";
    }
}
