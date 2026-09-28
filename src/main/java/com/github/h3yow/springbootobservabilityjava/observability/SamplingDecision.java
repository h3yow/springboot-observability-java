package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Objects;

/**
 * Immutable result of evaluating the sampling policy for exactly one request.
 *
 * <p>A decision captures <em>why</em> it was made as well as the outcome:
 * which rate was applied (per-endpoint override or the default), where the
 * request landed in the hash space and what threshold it was compared with.
 * The same data is logged, so a keep/drop outcome is always reproducible from
 * the request id alone.
 *
 * @param sampled          whether the request's event may be exported
 * @param requestId        id of the request the decision belongs to
 * @param endpoint         endpoint the request was attributed to
 * @param rate             rate actually applied to this decision
 * @param endpointOverride {@code true} if {@code rate} came from a per-endpoint
 *                         override, {@code false} if it came from the default
 * @param bucket           deterministic hash bucket of the request id in
 *                         {@code [0, Sampler.BUCKET_COUNT)}
 * @param threshold        keep threshold: kept iff {@code bucket < threshold};
 *                         {@code 0} means drop-all, {@code BUCKET_COUNT} keep-all
 */
public record SamplingDecision(
        boolean sampled,
        String requestId,
        String endpoint,
        double rate,
        boolean endpointOverride,
        long bucket,
        long threshold) {

    public SamplingDecision {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(endpoint, "endpoint");
    }

    /** Short, log-friendly description of the decision and its basis. */
    public String basis() {
        return "endpoint=" + endpoint
                + " rate=" + rate
                + " source=" + (endpointOverride ? "endpoint" : "default")
                + " requestBucket=" + bucket
                + " threshold=" + threshold
                + " sampled=" + sampled;
    }
}
