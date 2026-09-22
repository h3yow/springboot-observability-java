package com.github.h3yow.springbootobservabilityjava.observability.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * 单接口维度的指标：调用次数、成功/失败计数与耗时分布桶。
 * 全部使用 LongAdder，并发写入不丢失、不重复。
 * <p>耗时桶（毫秒）：&lt;10, &lt;50, &lt;100, &lt;500, &lt;1000, &ge;1000。
 */
public final class EndpointMetrics {

    /** 桶边界（毫秒），最后一桶为 +Inf */
    public static final long[] BUCKET_BOUNDS_MS = {10, 50, 100, 500, 1000};

    private final LongAdder total = new LongAdder();
    private final LongAdder success = new LongAdder();
    private final LongAdder failure = new LongAdder();
    private final LongAdder[] buckets;

    public EndpointMetrics() {
        this.buckets = new LongAdder[BUCKET_BOUNDS_MS.length + 1];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new LongAdder();
        }
    }

    public void record(boolean ok, long latencyNanos) {
        total.increment();
        if (ok) {
            success.increment();
        } else {
            failure.increment();
        }
        buckets[bucketIndex(latencyNanos)].increment();
    }

    private int bucketIndex(long latencyNanos) {
        long latencyMs = latencyNanos / 1_000_000;
        for (int i = 0; i < BUCKET_BOUNDS_MS.length; i++) {
            if (latencyMs < BUCKET_BOUNDS_MS[i]) {
                return i;
            }
        }
        return BUCKET_BOUNDS_MS.length;
    }

    public long getTotal() {
        return total.sum();
    }

    public long getSuccess() {
        return success.sum();
    }

    public long getFailure() {
        return failure.sum();
    }

    public long[] getLatencyBuckets() {
        long[] result = new long[buckets.length];
        for (int i = 0; i < buckets.length; i++) {
            result[i] = buckets[i].sum();
        }
        return result;
    }
}
