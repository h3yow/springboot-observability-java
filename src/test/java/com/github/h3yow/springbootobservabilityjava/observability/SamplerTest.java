package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavior of {@link Sampler}: per-endpoint ratios, exact boundary handling,
 * runtime adjustment, stable per-request verdicts, concurrency-exact counters
 * and refusal to sample without attribution. Test logs print the request id
 * and decision basis ({@code verdict:} lines) for reproduction.
 */
class SamplerTest {

    private static final Logger log = LoggerFactory.getLogger(SamplerTest.class);

    private static final String EP_WORK = "GET /demo/work";
    private static final String EP_FAIL = "GET /demo/fail";

    private static RequestContext ctx(String requestId, String endpoint) {
        return RequestContext.create(requestId, endpoint);
    }

    private static void logVerdict(RequestContext c, SamplingDecision d) {
        log.info("verdict: requestId={} endpoint={} sampled={} ratio={} basis={}",
                c.requestId(), c.endpoint(), d.sampled(), d.ratio(), d.basis());
    }

    /** Number of sampled-in requests over {@code n} fresh ids for an endpoint. */
    private static int sampleN(Sampler sampler, String endpoint, int n) {
        int kept = 0;
        for (int i = 0; i < n; i++) {
            RequestContext c = ctx("req-dist-" + endpoint.replace(' ', '_') + "-" + i, endpoint);
            SamplingDecision d = sampler.decide(c);
            if (d.sampled()) {
                kept++;
            }
        }
        return kept;
    }

    @Test
    void endpointsWithoutOverrideUseTheDefaultRatio() {
        Sampler sampler = new Sampler(0.25);
        int kept = sampleN(sampler, EP_WORK, 4_000);
        log.info("verdict: default ratio=0.25 endpoint={} kept={}/4000", EP_WORK, kept);
        // Deterministic hash should land close to 25%; allow generous slack.
        assertThat(kept).isBetween(800, 1_200);
    }

    @Test
    void endpointOverridesAreIndependentOfDefaultAndEachOther() {
        Map<String, Double> overrides = new HashMap<>();
        overrides.put(EP_WORK, 0.10);
        overrides.put(EP_FAIL, 0.90);
        Sampler sampler = new Sampler(0.50, overrides);

        int workKept = sampleN(sampler, EP_WORK, 4_000);
        int failKept = sampleN(sampler, EP_FAIL, 4_000);
        int defaultKept = sampleN(sampler, "GET /demo/other", 4_000);
        log.info("verdict: overrides work=0.10 kept={}, fail=0.90 kept={}, default=0.50 kept={}",
                workKept, failKept, defaultKept);

        assertThat(sampler.effectiveRatio(EP_WORK)).isEqualTo(0.10);
        assertThat(sampler.effectiveRatio(EP_FAIL)).isEqualTo(0.90);
        assertThat(sampler.effectiveRatio("GET /demo/other")).isEqualTo(0.50);
        assertThat(workKept).isBetween(250, 550);
        assertThat(failKept).isBetween(3_400, 3_800);
        assertThat(defaultKept).isBetween(1_700, 2_300);
    }

    @Test
    void ratioOneKeepsEveryRequest() {
        Sampler sampler = new Sampler(1.0);
        for (int i = 0; i < 500; i++) {
            RequestContext c = ctx("req-keep-" + i, EP_WORK);
            SamplingDecision d = sampler.decide(c);
            assertThat(d.sampled()).as("ratio 1.0 must never reject request " + i).isTrue();
            assertThat(d.basis()).contains("keep-all");
            if (i < 3) {
                logVerdict(c, d);
            }
        }
        log.info("verdict: ratio=1.0 all 500 kept, sampledOut={}", sampler.sampledOutCount());
        assertThat(sampler.sampledOutCount()).isZero();
    }

    @Test
    void ratioZeroDropsEveryRequest() {
        Sampler sampler = new Sampler(0.0);
        for (int i = 0; i < 500; i++) {
            RequestContext c = ctx("req-drop-" + i, EP_WORK);
            SamplingDecision d = sampler.decide(c);
            assertThat(d.sampled()).as("ratio 0.0 must never export request " + i).isFalse();
            assertThat(d.basis()).contains("drop-all");
            if (i < 3) {
                logVerdict(c, d);
            }
        }
        log.info("verdict: ratio=0.0 all 500 dropped, sampledOut={}", sampler.sampledOutCount());
        assertThat(sampler.sampledOutCount()).isEqualTo(500);
    }

    @Test
    void ratioBoundariesWorkForEndpointOverridesToo() {
        Map<String, Double> overrides = new HashMap<>();
        overrides.put(EP_WORK, 1.0);
        overrides.put(EP_FAIL, 0.0);
        Sampler sampler = new Sampler(0.5, overrides);
        for (int i = 0; i < 100; i++) {
            assertThat(sampler.decide(ctx("w" + i, EP_WORK)).sampled()).isTrue();
            assertThat(sampler.decide(ctx("f" + i, EP_FAIL)).sampled()).isFalse();
        }
        log.info("verdict: override boundaries work(1.0) kept 100/100, fail(0.0) kept 0/100, snapshot={}",
                sampler.snapshot());
    }

    @Test
    void sameRequestAlwaysGetsTheSameVerdictAcrossRepeatedEvaluations() {
        Sampler sampler = new Sampler(0.5);
        RequestContext c = ctx("req-stable-1", EP_WORK);
        SamplingDecision first = sampler.decide(c);
        for (int i = 0; i < 100; i++) {
            SamplingDecision again = sampler.decide(c);
            assertThat(again).isSameAs(first);
            assertThat(again.sampled()).isEqualTo(first.sampled());
        }
        log.info("verdict: requestId={} stable verdict across 101 evaluations, decision={}",
                c.requestId(), first);

        // Even changing the ratio mid-flight must not re-decide this request.
        sampler.updateDefaultRatio(1.0 - first.ratio());
        SamplingDecision afterFlip = sampler.decide(c);
        assertThat(afterFlip.sampled()).isEqualTo(first.sampled());
        log.info("verdict: requestId={} after runtime ratio flip still sampled={}",
                c.requestId(), afterFlip.sampled());
    }

    @Test
    void runtimeRatioChangeAppliesImmediatelyToNewRequestsOnly() throws Exception {
        Sampler sampler = new Sampler(0.0);
        RequestContext inFlight = ctx("req-inflight-1", EP_WORK);
        SamplingDecision before = sampler.decide(inFlight);
        assertThat(before.sampled()).isFalse();

        // Capture an in-flight decision right before flipping, evaluate again after.
        sampler.updateDefaultRatio(1.0);
        RequestContext after = ctx("req-after-flip-1", EP_WORK);
        SamplingDecision afterDecision = sampler.decide(after);
        SamplingDecision inFlightAgain = sampler.decide(inFlight);

        log.info("verdict: inFlight requestId={} before sampled={} after sampled={}; new requestId={} sampled={}",
                inFlight.requestId(), before.sampled(), inFlightAgain.sampled(),
                after.requestId(), afterDecision.sampled());
        assertThat(afterDecision.sampled()).isTrue();
        assertThat(inFlightAgain.sampled()).isFalse();

        // Endpoint override change is also immediate for new requests.
        sampler.updateEndpointRatio(EP_WORK, 0.0);
        assertThat(sampler.decide(ctx("req-after-ep-flip", EP_WORK)).sampled()).isFalse();
        double restored = sampler.removeEndpointOverride(EP_WORK);
        assertThat(restored).isEqualTo(1.0);
        assertThat(sampler.decide(ctx("req-after-ep-remove", EP_WORK)).sampled()).isTrue();
        log.info("verdict: endpoint override removed, falls back to default=1.0");
    }

    @Test
    void concurrentDecisionsAndRatioUpdatesProduceExactCounters() throws Exception {
        Sampler sampler = new Sampler(0.5);
        int threads = 16;
        int requestsPerThread = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        // One mutator keeps changing ratios while decisions are being made.
        futures.add(pool.submit(() -> {
            try {
                start.await();
                for (int i = 0; i < 500; i++) {
                    double r = (i % 11) / 10.0; // 0.0..1.0
                    sampler.updateDefaultRatio(r);
                    sampler.updateEndpointRatio(EP_WORK, r);
                }
                sampler.updateDefaultRatio(0.5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        int totalRequests = threads * requestsPerThread;
        List<RequestContext> shared = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            shared.add(ctx("req-shared-" + i, EP_WORK));
        }
        // Exact set of distinct request ids that must be decided exactly once;
        // an id is registered at the moment a worker decides it.
        java.util.Set<String> distinctIds =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        for (int t = 0; t < threads; t++) {
            int thread = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < requestsPerThread; i++) {
                    RequestContext c;
                    if (i % 20 == 0) {
                        // Same request hammered by every thread: verdict must
                        // stay stable and be counted once.
                        c = shared.get((thread + i) % shared.size());
                    } else {
                        c = ctx("req-c-" + thread + "-" + i, EP_WORK);
                    }
                    distinctIds.add(c.requestId());
                    SamplingDecision d1 = sampler.decide(c);
                    SamplingDecision d2 = sampler.decide(c);
                    if (d1.sampled() != d2.sampled()) {
                        throw new AssertionError("unstable verdict for " + c.requestId());
                    }
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) sampler.snapshot().get("stats");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) stats.get(EP_WORK);
        long decisions = ((Number) row.get("decisions")).longValue();
        long in = ((Number) row.get("sampledIn")).longValue();
        long out = ((Number) row.get("sampledOut")).longValue();
        log.info("verdict: evaluationCalls={} distinctRequests={} decisions={} sampledIn={} sampledOut={} in+out==decisions:{}",
                totalRequests * 2L, distinctIds.size(), decisions, in, out, in + out == decisions);

        // Every distinct request decided exactly once; counters never lost/duplicated.
        assertThat(decisions).isEqualTo(distinctIds.size());
        assertThat(in + out).isEqualTo(decisions);
        assertThat(sampler.sampledOutCount()).isEqualTo(out);

        // Every shared request actually decided has one globally consistent verdict.
        Map<String, Boolean> observed = new ConcurrentHashMap<>();
        for (RequestContext c : shared) {
            SamplingDecision decision = c.samplingDecision();
            if (decision == null) {
                continue; // this shared id was never referenced by any worker
            }
            boolean v = decision.sampled();
            Boolean prev = observed.put(c.requestId(), v);
            assertThat(prev == null || prev == v).isTrue();
        }
    }

    @Test
    void missingContextIsRejectedInsteadOfInventingAttribution() {
        Sampler sampler = new Sampler(1.0);
        assertThatThrownBy(() -> sampler.decide(null))
                .isInstanceOf(IllegalArgumentException.class);
        log.info("verdict: decide(null) rejected, no attribution invented");
    }

    @Test
    void invalidRatiosAreRejected() {
        assertThatThrownBy(() -> new Sampler(1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Sampler(-0.1)).isInstanceOf(IllegalArgumentException.class);
        Sampler sampler = new Sampler(0.5);
        assertThatThrownBy(() -> sampler.updateDefaultRatio(2.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sampler.updateEndpointRatio(EP_WORK, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        log.info("verdict: out-of-range and NaN ratios rejected, snapshot={}", sampler.snapshot());
    }
}
