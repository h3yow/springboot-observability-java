package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metrics for a single endpoint.
 *
 * <p>Semantics (stable naming, see README):
 * <ul>
 *   <li>{@code total} — every completed request to the endpoint.</li>
 *   <li>{@code success} — completed with a non-error outcome.</li>
 *   <li>{@code failure} — completed with an error outcome.</li>
 *   <li>{@code total == success + failure} is an invariant.</li>
 *   <li>{@code latencyBucketsMs} — fixed histogram of request latency in
 *       milliseconds; {@code "+Inf"} catches the tail.</li>
 * </ul>
 *
 * <p>All counters are {@link LongAdder}s, so concurrent writers never lose
 * or double-count updates. A single request records exactly one
 * {@link #record(boolean, long)} call.
 */
public final class EndpointMetrics {

    /** Upper bounds (inclusive) of latency buckets, in milliseconds. */
    static final long[] BUCKET_BOUNDS_MS = {5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

    private final LongAdder total = new LongAdder();
    private final LongAdder success = new LongAdder();
    private final LongAdder failure = new LongAdder();
    private final LongAdder[] buckets;
    private final LongAdder latencyNanosSum = new LongAdder();
    private final LongAdder latencyNanosMax = new LongAdder();

    public EndpointMetrics() {
        this.buckets = new LongAdder[BUCKET_BOUNDS_MS.length + 1]; // +1 for +Inf
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    /**
     * Records one completed request. Must be called exactly once per request.
     *
     * @param succeeded   whether the request completed successfully
     * @param latencyNanos wall-clock latency of the request
     */
    public void record(boolean succeeded, long latencyNanos) {
        total.increment();
        if (succeeded) {
            success.increment();
        } else {
            failure.increment();
        }
        long latencyMs = latencyNanos / 1_000_000;
        buckets[bucketIndex(latencyMs)].increment();
        latencyNanosSum.add(Math.max(latencyNanos, 0));
        updateMax(Math.max(latencyNanos, 0));
    }

    private void updateMax(long nanos) {
        // LongAdder has no max; approximate atomically via a CAS loop on sum
        // is not applicable, so track max with a synchronized compare.
        synchronized (latencyNanosMax) {
            if (nanos > latencyNanosMax.sum()) {
                latencyNanosMax.reset();
                latencyNanosMax.add(nanos);
            }
        }
    }

    private static int bucketIndex(long latencyMs) {
        for (int i = 0; i < BUCKET_BOUNDS_MS.length; i++) {
            if (latencyMs <= BUCKET_BOUNDS_MS[i]) {
                return i;
            }
        }
        return BUCKET_BOUNDS_MS.length; // +Inf
    }

    /** Returns a consistent-enough point-in-time snapshot for local reads. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        long t = total.sum();
        long s = success.sum();
        long f = failure.sum();
        out.put("total", t);
        out.put("success", s);
        out.put("failure", f);
        Map<String, Long> hist = new LinkedHashMap<>();
        for (int i = 0; i < BUCKET_BOUNDS_MS.length; i++) {
            hist.put("le=" + BUCKET_BOUNDS_MS[i], buckets[i].sum());
        }
        hist.put("+Inf", buckets[BUCKET_BOUNDS_MS.length].sum());
        out.put("latencyBucketsMs", hist);
        out.put("latencyAvgMs", t == 0 ? 0.0 : (latencyNanosSum.sum() / 1_000_000.0) / t);
        out.put("latencyMaxMs", latencyNanosMax.sum() / 1_000_000.0);
        return out;
    }

    // Visible for tests.
    public long totalCount() {
        return total.sum();
    }

    public long successCount() {
        return success.sum();
    }

    public long failureCount() {
        return failure.sum();
    }
}
