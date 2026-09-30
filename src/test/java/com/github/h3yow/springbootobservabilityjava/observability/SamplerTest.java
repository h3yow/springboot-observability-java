package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the sampling policy: boundary rates, per-endpoint overrides,
 * reproducible decisions, runtime rate changes and concurrent decision/count
 * consistency. Every verdict is logged with the request id and decision basis.
 */
class SamplerTest {

    private static final Logger log = LoggerFactory.getLogger(SamplerTest.class);
    private static final String EP_A = "GET /demo/work";
    private static final String EP_B = "GET /demo/fail";

    private static RequestContext ctx(String requestId, String endpoint) {
        return RequestContext.create(requestId, endpoint);
    }

    @Test
    void boundaryRateOneKeepsEveryRequest() {
        Sampler sampler = new Sampler(1.0, Map.of());
        for (int i = 0; i < 1_000; i++) {
            RequestContext c = ctx("req-keep-" + i, EP_A);
            SamplingDecision d = sampler.decide(c);
            assertThat(d.sampled())
                    .as("rate=1.0 must keep requestId=%s bucket=%d", c.requestId(), d.bucket())
                    .isTrue();
        }
        log.info("verdict: rate=1.0 endpoint={} kept=1000/1000 stats={}", EP_A, sampler.statsSnapshot().get(EP_A));
    }

    @Test
    void boundaryRateZeroDropsEveryRequest() {
        Sampler sampler = new Sampler(0.0, Map.of());
        for (int i = 0; i < 1_000; i++) {
            RequestContext c = ctx("req-drop-" + i, EP_A);
            SamplingDecision d = sampler.decide(c);
            assertThat(d.sampled())
                    .as("rate=0.0 must drop requestId=%s bucket=%d", c.requestId(), d.bucket())
                    .isFalse();
            assertThat(d.threshold()).isZero();
        }
        log.info("verdict: rate=0.0 endpoint={} dropped=1000/1000 notSampled={} stats={}",
                EP_A, sampler.notSampledCount(), sampler.statsSnapshot().get(EP_A));
        assertThat(sampler.notSampledCount()).isEqualTo(1_000);
    }

    @Test
    void perEndpointOverridesBeatTheDefault() {
        // Default drops everything; EP_A explicitly keeps everything;
        // EP_B has no override and therefore follows the default.
        Sampler sampler = new Sampler(0.0, Map.of(EP_A, 1.0));

        for (int i = 0; i < 200; i++) {
            SamplingDecision a = sampler.decide(ctx("req-a-" + i, EP_A));
            SamplingDecision b = sampler.decide(ctx("req-b-" + i, EP_B));
            assertThat(a.sampled()).isTrue();
            assertThat(a.endpointOverride()).isTrue();
            assertThat(b.sampled()).isFalse();
            assertThat(b.endpointOverride()).isFalse();
        }
        log.info("verdict: default=0.0 override[{}]=1.0 -> EP_A kept=200, EP_B dropped=200, stats={}",
                EP_A, sampler.statsSnapshot());
        assertThat(sampler.effectiveRateFor(EP_A)).isEqualTo(1.0);
        assertThat(sampler.effectiveRateFor(EP_B)).isEqualTo(0.0);
    }

    @Test
    void interiorRateApproximatesProportion() {
        Sampler sampler = new Sampler(0.5, Map.of());
        int n = 10_000;
        long kept = 0;
        for (int i = 0; i < n; i++) {
            SamplingDecision d = sampler.decide(ctx("req-half-" + i, EP_A));
            if (i == 0) {
                assertThat(d.threshold()).isEqualTo(Sampler.BUCKET_COUNT / 2);
                log.info("verdict: sample requestId={} {}", d.requestId(), d.basis());
            }
            if (d.sampled()) {
                kept++;
            }
        }
        double observed = (double) kept / n;
        log.info("verdict: rate=0.5 n={} kept={} observedRate={}", n, kept, observed);
        assertThat(observed).isBetween(0.40, 0.60);
    }

    @Test
    void sameRequestEvaluatedManyTimesGetsOneStableDecision() {
        Sampler sampler = new Sampler(0.5, Map.of());
        RequestContext c = ctx("req-stable-1", EP_A);

        Boolean firstOutcome = null;
        long firstBucket = -1;
        for (int i = 0; i < 100; i++) {
            SamplingDecision d = sampler.decide(c);
            if (firstOutcome == null) {
                firstOutcome = d.sampled();
                firstBucket = d.bucket();
            }
            assertThat(d.sampled()).isEqualTo(firstOutcome);
            assertThat(d.bucket()).isEqualTo(firstBucket);
            assertThat(d.requestId()).isEqualTo("req-stable-1");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) sampler.statsSnapshot().get(EP_A);
        log.info("verdict: requestId=req-stable-1 evaluated=100 stableOutcome={} considered={} sampled={}",
                firstOutcome, row.get("considered"), row.get("sampled"));
        // Decisions counted exactly once despite 100 evaluations.
        assertThat(row.get("considered")).isEqualTo(1L);
        assertThat(row.get("sampled")).isEqualTo(firstOutcome ? 1L : 0L);
        assertThat(row.get("notSampled")).isEqualTo(firstOutcome ? 0L : 1L);
    }

    @Test
    void nullContextIsRejectedNotFabricated() {
        Sampler sampler = new Sampler(1.0, Map.of());
        assertThatThrownBy(() -> sampler.decide(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must not be null");
        log.info("verdict: missing context rejected, no attribution fabricated");
    }

    @Test
    void invalidRatesAreRejected() {
        assertThatThrownBy(() -> new Sampler(1.5, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Sampler(-0.1, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Sampler(Double.NaN, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Sampler sampler = new Sampler(1.0, Map.of());
        assertThatThrownBy(() -> sampler.setDefaultRate(2.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sampler.setEndpointRate(EP_A, 0.0 / 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sampler.setEndpointRate("  ", 0.5))
                .isInstanceOf(IllegalArgumentException.class);
        log.info("verdict: out-of-range/NaN/blank inputs rejected, config unchanged={}",
                sampler.configSnapshot());
    }

    @Test
    void decisionIsReproducibleAcrossSamplerInstances() {
        Sampler s1 = new Sampler(0.37, Map.of());
        Sampler s2 = new Sampler(0.37, Map.of());
        for (int i = 0; i < 50; i++) {
            String id = "req-repro-" + i;
            SamplingDecision d1 = s1.decide(ctx(id, EP_A));
            SamplingDecision d2 = s2.decide(ctx(id, EP_A));
            assertThat(d2.bucket()).isEqualTo(d1.bucket());
            assertThat(d2.sampled()).isEqualTo(d1.sampled());
            assertThat(d2.threshold()).isEqualTo(d1.threshold());
        }
        log.info("verdict: 50 requestIds reproduce identical bucket/outcome under two samplers");
    }

    @Test
    void runtimeRateChangeAppliesToNewRequestsImmediately() {
        Sampler sampler = new Sampler(1.0, Map.of());
        assertThat(sampler.decide(ctx("req-before", EP_A)).sampled()).isTrue();

        sampler.setDefaultRate(0.0);
        SamplingDecision after = sampler.decide(ctx("req-after", EP_A));
        log.info("verdict: default changed 1.0->0.0, requestId=req-after {} effectiveRateFor[{}]={}",
                after.basis(), EP_A, sampler.effectiveRateFor(EP_A));
        assertThat(after.sampled()).isFalse();

        // Per-endpoint override and removal are also live.
        sampler.setEndpointRate(EP_A, 1.0);
        assertThat(sampler.decide(ctx("req-override", EP_A)).sampled()).isTrue();
        sampler.removeEndpointRate(EP_A);
        assertThat(sampler.decide(ctx("req-removed", EP_A)).sampled()).isFalse();
        @SuppressWarnings("unchecked")
        Map<String, Double> endpoints = (Map<String, Double>) sampler.configSnapshot().get("endpointRates");
        log.info("verdict: override removed, endpointRates={}", endpoints);
        assertThat(endpoints).doesNotContainKey(EP_A);
    }

    @Test
    void inFlightRequestKeepsItsDecisionWhenRateChangesMidway() throws Exception {
        Sampler sampler = new Sampler(0.5, Map.of());
        // Build a deterministic set of in-flight contexts that have already
        // decided once under the 0.5 policy.
        int inFlight = 200;
        RequestContext[] contexts = new RequestContext[inFlight];
        boolean[] original = new boolean[inFlight];
        for (int i = 0; i < inFlight; i++) {
            contexts[i] = ctx("req-inflight-" + i, EP_A);
            original[i] = sampler.decide(contexts[i]).sampled();
        }

        // Flip rates wildly on another thread while the in-flight requests are
        // re-evaluated (as fan-out branches would).
        Thread flipper = new Thread(() -> {
            for (int i = 0; i < 1_000; i++) {
                sampler.setDefaultRate(i % 2 == 0 ? 0.0 : 1.0);
            }
        });
        flipper.start();
        for (int round = 0; round < 50; round++) {
            for (int i = 0; i < inFlight; i++) {
                SamplingDecision d = sampler.decide(contexts[i]);
                assertThat(d.sampled())
                        .as("in-flight requestId=%s must keep original outcome %s",
                                contexts[i].requestId(), original[i])
                        .isEqualTo(original[i]);
                // The memoized decision also pins the rate it was made under.
                assertThat(d.rate()).isEqualTo(0.5);
            }
        }
        flipper.join();
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) sampler.statsSnapshot().get(EP_A);
        log.info("verdict: {} in-flight requests stable across 1000 rate flips; considered={} (expected {})",
                inFlight, row.get("considered"), inFlight);
        // Each request counted once even though it was evaluated 51 times.
        assertThat(row.get("considered")).isEqualTo((long) inFlight);
    }

    @Test
    void concurrentDecisionsAndRateChangesKeepCountsExact() throws Exception {
        Sampler sampler = new Sampler(0.5, Map.of(EP_B, 0.25));
        int threads = 16;
        int requestsPerThread = 2_000;
        int totalRequests = threads * requestsPerThread;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < requestsPerThread; i++) {
                    String endpoint = (i + thread) % 2 == 0 ? EP_A : EP_B;
                    RequestContext c = ctx("req-conc-" + thread + "-" + i, endpoint);
                    SamplingDecision d = sampler.decide(c);
                    // Re-evaluate a few times to stress memoization.
                    sampler.decide(c);
                    sampler.decide(c);
                    if (thread == 0 && i < 5) {
                        log.info("verdict: requestId={} {}", c.requestId(), d.basis());
                    }
                }
            }));
        }
        // Concurrent policy churn: default rate and EP_B override.
        java.util.List<java.util.concurrent.Future<?>> churn = new java.util.ArrayList<>();
        for (int k = 0; k < 2; k++) {
            churn.add(pool.submit(() -> {
                for (int i = 0; i < 2_000; i++) {
                    sampler.setDefaultRate((i % 10) / 10.0);
                    sampler.setEndpointRate(EP_B, ((i % 8) + 1) / 8.0);
                }
                return null;
            }));
        }

        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        for (java.util.concurrent.Future<?> f : churn) {
            f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        @SuppressWarnings("unchecked")
        Map<String, Object> aRow = (Map<String, Object>) sampler.statsSnapshot().get(EP_A);
        @SuppressWarnings("unchecked")
        Map<String, Object> bRow = (Map<String, Object>) sampler.statsSnapshot().get(EP_B);
        long consideredA = ((Number) aRow.get("considered")).longValue();
        long consideredB = ((Number) bRow.get("considered")).longValue();
        log.info("verdict: consideredA={} consideredB={} sum={} (expected {}) stats={}",
                consideredA, consideredB, consideredA + consideredB, totalRequests, sampler.statsSnapshot());
        assertThat(consideredA + consideredB).isEqualTo(totalRequests);
        assertThat(((Number) aRow.get("sampled")).longValue()
                + ((Number) aRow.get("notSampled")).longValue()).isEqualTo(consideredA);
        assertThat(((Number) bRow.get("sampled")).longValue()
                + ((Number) bRow.get("notSampled")).longValue()).isEqualTo(consideredB);
    }

    @Test
    void oneRequestEvaluatedConcurrentlyByManyThreadsGetsOneStableDecision() throws Exception {
        Sampler sampler = new Sampler(0.5, Map.of());
        RequestContext c = ctx("req-concurrent-one", EP_A);
        int threads = 32;
        int evaluationsPerThread = 500;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.List<java.util.concurrent.Future<SamplingDecision>> futures =
                new java.util.ArrayList<>();

        // Churn the policy concurrently too: the pinned decision must survive it.
        Thread flipper = new Thread(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (int i = 0; i < 2_000; i++) {
                sampler.setDefaultRate(i % 2 == 0 ? 0.0 : 1.0);
            }
        });
        flipper.start();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                SamplingDecision first = null;
                for (int i = 0; i < evaluationsPerThread; i++) {
                    SamplingDecision d = sampler.decide(c);
                    if (first == null) {
                        first = d;
                    } else {
                        assertThat(d).isSameAs(first); // identical instance, not just equal outcome
                    }
                }
                return first;
            }));
        }
        start.countDown();

        SamplingDecision expected = null;
        for (java.util.concurrent.Future<SamplingDecision> f : futures) {
            SamplingDecision d = f.get(30, java.util.concurrent.TimeUnit.SECONDS);
            if (expected == null) {
                expected = d;
            } else {
                assertThat(d).isSameAs(expected);
            }
        }
        flipper.join();

        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) sampler.statsSnapshot().get(EP_A);
        log.info("verdict: requestId=req-concurrent-one {} evaluations x {} threads, considered={} sampled={} (must be 1 / 0|1)",
                evaluationsPerThread, threads, row.get("considered"), row.get("sampled"));
        assertThat(row.get("considered")).isEqualTo(1L);
        assertThat(row.get("sampled")).isEqualTo(expected.sampled() ? 1L : 0L);
        assertThat(row.get("notSampled")).isEqualTo(expected.sampled() ? 0L : 1L);
        pool.shutdownNow();
    }

    @Test
    void concurrentStatsReadersAlwaysSeeConsistentCountsWhileRatesChurn() throws Exception {
        Sampler sampler = new Sampler(0.5, Map.of(EP_B, 0.25));
        int writerThreads = 8;
        int requestsPerThread = 2_000;
        int totalRequests = writerThreads * requestsPerThread;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(writerThreads + 3);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < writerThreads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < requestsPerThread; i++) {
                    String endpoint = (i + thread) % 2 == 0 ? EP_A : EP_B;
                    RequestContext c = ctx("req-stats-" + thread + "-" + i, endpoint);
                    sampler.decide(c);
                }
                return null;
            }));
        }
        // Readers must never observe a torn invariant (kept + dropped != considered),
        // and notSampledCount must never lose or invent updates.
        java.util.List<java.util.concurrent.Future<?>> readers = new java.util.ArrayList<>();
        for (int r = 0; r < 2; r++) {
            readers.add(pool.submit(() -> {
                start.await();
                long lastNotSampled = 0L;
                for (int i = 0; i < 20_000; i++) {
                    Map<String, Object> snapshot = sampler.statsSnapshot();
                    for (Object value : snapshot.values()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> row = (Map<String, Object>) value;
                        long considered = ((Number) row.get("considered")).longValue();
                        long sampled = ((Number) row.get("sampled")).longValue();
                        long notSampled = ((Number) row.get("notSampled")).longValue();
                        assertThat(sampled + notSampled)
                                .as("considered must always equal sampled + notSampled")
                                .isEqualTo(considered);
                    }
                    long total = sampler.notSampledCount();
                    assertThat(total).as("notSampledCount is monotonic").isGreaterThanOrEqualTo(lastNotSampled);
                    lastNotSampled = total;
                }
                return null;
            }));
        }
        // Concurrent policy churn on both the default and one endpoint.
        futures.add(pool.submit(() -> {
            start.await();
            for (int i = 0; i < 2_000; i++) {
                sampler.setDefaultRate(i % 3 / 3.0d);
                sampler.setEndpointRate(EP_B, ((i % 4) + 1) / 4.0d);
            }
            return null;
        }));

        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        for (java.util.concurrent.Future<?> f : readers) {
            f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        @SuppressWarnings("unchecked")
        Map<String, Object> aRow = (Map<String, Object>) sampler.statsSnapshot().get(EP_A);
        @SuppressWarnings("unchecked")
        Map<String, Object> bRow = (Map<String, Object>) sampler.statsSnapshot().get(EP_B);
        long consideredA = ((Number) aRow.get("considered")).longValue();
        long consideredB = ((Number) bRow.get("considered")).longValue();
        log.info("verdict: readers saw no torn counts; consideredA={} consideredB={} sum={} (expected {}) notSampledTotal={}",
                consideredA, consideredB, consideredA + consideredB, totalRequests,
                sampler.notSampledCount());
        assertThat(consideredA + consideredB).isEqualTo(totalRequests);
    }
}
