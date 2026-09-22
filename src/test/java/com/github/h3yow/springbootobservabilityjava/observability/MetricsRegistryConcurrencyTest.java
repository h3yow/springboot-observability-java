package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Counter consistency under concurrent writes: totals must be exact
 * (no lost or duplicated updates) and the success+failure invariant must hold.
 */
class MetricsRegistryConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsRegistryConcurrencyTest.class);

    @Test
    void concurrentIncrementsAreExact() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        int threads = 16;
        int recordsPerThread = 5_000;
        String endpoint = "GET /demo/work";

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                try {
                    startGun.await();
                    for (int i = 0; i < recordsPerThread; i++) {
                        // deterministic mix: every 4th record is a failure
                        boolean success = (i + thread) % 4 != 0;
                        registry.record(endpoint, success, (i % 700) * 1_000_000L);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        startGun.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        EndpointMetrics metrics = registry.metricsOf(endpoint);
        long expectedTotal = (long) threads * recordsPerThread;
        long expectedFailure = threads * (recordsPerThread / 4);
        long expectedSuccess = expectedTotal - expectedFailure;
        log.info("verdict: endpoint={} total={} (expected={}) success={} (expected={}) failure={} (expected={}) invariant={}",
                endpoint, metrics.totalCount(), expectedTotal,
                metrics.successCount(), expectedSuccess,
                metrics.failureCount(), expectedFailure,
                metrics.totalCount() == metrics.successCount() + metrics.failureCount());

        assertThat(metrics.totalCount()).isEqualTo(expectedTotal);
        assertThat(metrics.successCount()).isEqualTo(expectedSuccess);
        assertThat(metrics.failureCount()).isEqualTo(expectedFailure);
        assertThat(metrics.totalCount())
                .isEqualTo(metrics.successCount() + metrics.failureCount());
    }

    @Test
    void concurrentEndpointsStayIsolated() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        int endpoints = 8;
        int recordsPerEndpoint = 2_000;

        ExecutorService pool = Executors.newFixedThreadPool(endpoints);
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int e = 0; e < endpoints; e++) {
            String endpoint = "GET /demo/ep" + e;
            futures.add(pool.submit(() -> {
                try {
                    startGun.await();
                    for (int i = 0; i < recordsPerEndpoint; i++) {
                        registry.record(endpoint, true, 1_000_000L);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        startGun.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        Map<String, Object> snapshot = registry.snapshot();
        log.info("verdict: endpoints={} perEndpointCount={} (expected={})",
                snapshot.size(), recordsPerEndpoint, recordsPerEndpoint);
        assertThat(snapshot).hasSize(endpoints);
        for (int e = 0; e < endpoints; e++) {
            EndpointMetrics m = registry.metricsOf("GET /demo/ep" + e);
            log.info("verdict: endpoint=GET /demo/ep{} total={}", e, m.totalCount());
            assertThat(m.totalCount()).isEqualTo(recordsPerEndpoint);
            assertThat(m.successCount()).isEqualTo(recordsPerEndpoint);
            assertThat(m.failureCount()).isZero();
        }
    }

    @Test
    void latencyBucketsCoverEveryRecord() {
        EndpointMetrics metrics = new EndpointMetrics();
        long[] latenciesMs = {1, 5, 7, 25, 60, 300, 999, 2500, 4000, 60_000};
        for (long ms : latenciesMs) {
            metrics.record(true, ms * 1_000_000L);
        }
        Map<String, Object> snapshot = metrics.snapshot();
        @SuppressWarnings("unchecked")
        Map<String, Long> buckets = (Map<String, Long>) snapshot.get("latencyBucketsMs");
        long bucketSum = buckets.values().stream().mapToLong(Long::longValue).sum();
        log.info("verdict: records={} bucketSum={} buckets={}", latenciesMs.length, bucketSum, buckets);
        assertThat(bucketSum).isEqualTo(latenciesMs.length);
        assertThat(snapshot.get("total")).isEqualTo((long) latenciesMs.length);
    }
}
