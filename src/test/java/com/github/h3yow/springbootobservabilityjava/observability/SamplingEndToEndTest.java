package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end sampling behavior through the real HTTP stack: boundary rates,
 * metrics coverage independent of sampling, runtime rate changes, per-endpoint
 * overrides and distinct drop accounting. Policy is restored around every test;
 * all count assertions use deltas captured inside the test because the single
 * Spring context (and its samplers/counters) is shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(InFlightGate.GateConfig.class)
class SamplingEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(SamplingEndToEndTest.class);
    private static final String EP_WORK = "GET /demo/work";
    private static final String EP_FAIL = "GET /demo/fail";
    /** Stable endpoint name of the test-only barrier used to hold requests in flight. */
    private static final String EP_GATE = "GET /test/inflight";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Sampler sampler;

    @Autowired
    private MetricsRegistry metricsRegistry;

    @Autowired
    private MetricsExportQueue exportQueue;

    @Autowired
    private InFlightGate.GateController gate;

    private int port;

    @BeforeEach
    void resetPolicy() {
        port = rest.getRootUri() == null ? 0 : portFromRootUri(rest.getRootUri());
        sampler.setDefaultRate(1.0);
        sampler.removeEndpointRate(EP_WORK);
        sampler.removeEndpointRate(EP_FAIL);
        sampler.removeEndpointRate(EP_GATE);
        gate.release(); // never leave an armed gate across tests
    }

    @AfterEach
    void restorePolicy() {
        gate.release();
        sampler.setDefaultRate(1.0);
        sampler.removeEndpointRate(EP_WORK);
        sampler.removeEndpointRate(EP_FAIL);
        sampler.removeEndpointRate(EP_GATE);
    }

    private static int portFromRootUri(String rootUri) {
        int colon = rootUri.lastIndexOf(':');
        return Integer.parseInt(rootUri.substring(colon + 1));
    }

    /** Pre-encoded URI, so TestRestTemplate does not double-encode %2F/%20. */
    private URI uri(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void post(String pathAndQuery) {
        rest.exchange(uri(pathAndQuery), HttpMethod.POST, HttpEntity.EMPTY, Map.class);
    }

    /**
     * Waits until the export pipeline is quiescent: queue drained and both
     * accepted/exported totals stable across two samples. Needed because the
     * servlet container may hand the HTTP response back to the client before
     * the outermost filter's {@code finally} has submitted its event, so a
     * counter read right after a POST can otherwise race that last submit.
     */
    private void settle() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        long prevAccepted = -1;
        long prevExported = -1;
        while (System.nanoTime() < deadline) {
            long accepted = exportQueue.acceptedCount();
            long exported = exportQueue.exportedCount();
            if (accepted == prevAccepted && exported == prevExported
                    && accepted == exported && exportQueue.pendingCount() == 0) {
                return; // stable and drained
            }
            prevAccepted = accepted;
            prevExported = exported;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for pipeline quiescence", e);
            }
        }
        throw new AssertionError("export pipeline did not settle: "
                + "accepted=" + exportQueue.acceptedCount() + " exported=" + exportQueue.exportedCount()
                + " pending=" + exportQueue.pendingCount());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> drops() {
        return rest.getForObject("/observability/drops", Map.class);
    }

    @Test
    void dropAllStillRecordsMetricsAndCountsNotSampledSeparately() {
        post("/observability/sampling/default-rate?rate=0.0");
        settle();

        // Baselines captured AFTER the policy change: the admin request above
        // entered under the old (keep-all) policy and was therefore sampled —
        // decisions are pinned at request entry, so the request performing the
        // change is never affected by its own change.
        long notSampledBaseline = sampler.notSampledCount();
        long acceptedBaseline = exportQueue.acceptedCount();
        long queueFullBaseline = exportQueue.dropCounts().get(DropReason.QUEUE_FULL);
        EndpointMetrics metrics = metricsRegistry.metricsOf(EP_WORK);
        long totalBaseline = metrics == null ? 0 : metrics.totalCount();

        for (int i = 0; i < 10; i++) {
            String id = "req-e2e-drop-" + i;
            var response = rest.getForEntity("/demo/work", Map.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue(); // business never blocked
            assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
            log.info("verdict: requestId={} rate=0.0 -> business ok, suppressed from export", id);
        }

        long notSampledDelta = sampler.notSampledCount() - notSampledBaseline;
        settle();
        long acceptedDelta = exportQueue.acceptedCount() - acceptedBaseline;
        long totalDelta = metricsRegistry.metricsOf(EP_WORK).totalCount() - totalBaseline;
        log.info("verdict: notSampledDelta={} (exactly 10 new requests) acceptedDelta={} metricsTotalDelta={}",
                notSampledDelta, acceptedDelta, totalDelta);

        assertThat(notSampledDelta).isEqualTo(10);
        assertThat(acceptedDelta).isZero();                       // no demo event entered the queue
        assertThat(exportQueue.dropCounts().get(DropReason.QUEUE_FULL)).isEqualTo(queueFullBaseline);
        assertThat(totalDelta).isEqualTo(10);                    // metrics cover every request
        assertThat(metricsRegistry.metricsOf(EP_WORK).successCount())
                .isGreaterThanOrEqualTo(metricsRegistry.metricsOf(EP_WORK).totalCount());
    }

    @Test
    void keepAllExportsEveryRequest() {
        long acceptedBaseline = exportQueue.acceptedCount();
        long notSampledBaseline = sampler.notSampledCount();
        for (int i = 0; i < 10; i++) {
            ResponseEntity<Map> response = rest.getForEntity("/demo/work", Map.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }
        settle();
        log.info("verdict: rate=1.0 acceptedDelta=10 notSampledDelta=0");
        assertThat(exportQueue.acceptedCount() - acceptedBaseline).isEqualTo(10);
        assertThat(sampler.notSampledCount() - notSampledBaseline).isZero();
    }

    @Test
    void failuresAreStillCountedWhenSampledAway() {
        EndpointMetrics before = metricsRegistry.metricsOf(EP_FAIL);
        long failureBaseline = before == null ? 0 : before.failureCount();

        post("/observability/sampling/default-rate?rate=0.0");
        for (int i = 0; i < 5; i++) {
            rest.getForEntity("/demo/fail", String.class);
        }
        long failureDelta = metricsRegistry.metricsOf(EP_FAIL).failureCount() - failureBaseline;
        log.info("verdict: rate=0.0 endpoint={} failureDelta={}", EP_FAIL, failureDelta);
        assertThat(failureDelta).isEqualTo(5);
    }

    @Test
    void perEndpointOverrideAndRuntimeChangeTakeEffectImmediately() {
        // Default drops all; only /demo/work is kept.
        post("/observability/sampling/default-rate?rate=0.0");
        post("/observability/sampling/endpoint-rate?endpoint=" + enc(EP_WORK) + "&rate=1.0");
        settle(); // let the two admin requests' own events be fully accounted

        long acceptedBaseline = exportQueue.acceptedCount();
        long notSampledBaseline = sampler.notSampledCount();

        assertThat(sampler.effectiveRateFor(EP_WORK)).isEqualTo(1.0);
        rest.getForEntity("/demo/work", Map.class);  // override keeps
        rest.getForEntity("/demo/fail", Map.class);  // default drops

        settle();
        long acceptedDelta = exportQueue.acceptedCount() - acceptedBaseline;
        long notSampledDelta = sampler.notSampledCount() - notSampledBaseline;
        log.info("verdict: default=0.0 override[{}]=1.0 acceptedDelta={} notSampledDelta={} stats={}",
                EP_WORK, acceptedDelta, notSampledDelta,
                rest.getForObject("/observability/sampling/stats", Map.class));
        assertThat(acceptedDelta).isEqualTo(1);
        assertThat(notSampledDelta).isEqualTo(1);

        // Runtime switch: drop /demo/work too -> effective immediately.
        post("/observability/sampling/endpoint-rate?endpoint=" + enc(EP_WORK) + "&rate=0.0");
        long notSampledNow = sampler.notSampledCount();
        rest.getForEntity("/demo/work", Map.class);
        assertThat(sampler.notSampledCount() - notSampledNow).isEqualTo(1);
        log.info("verdict: config after runtime change={}",
                rest.getForObject("/observability/sampling/config", Map.class));

        // Remove override -> falls back to the default and disappears from overrides.
        @SuppressWarnings("unchecked")
        Map<String, Object> config = rest.exchange(
                uri("/observability/sampling/endpoint-rate?endpoint=" + enc(EP_WORK) + "&remove=true"),
                HttpMethod.POST, HttpEntity.EMPTY, Map.class).getBody();
        @SuppressWarnings("unchecked")
        Map<String, Double> rates = (Map<String, Double>) config.get("endpointRates");
        assertThat(rates).doesNotContainKey(EP_WORK);
        assertThat(sampler.effectiveRateFor(EP_WORK)).isEqualTo(0.0); // default still 0.0
    }

    /**
     * Requests that STARTED under keep-all must still be exported when the rate
     * drops to zero mid-flight; only requests starting afterwards follow the new
     * drop-all policy.
     */
    @Test
    void inFlightRequestsKeepOldDecisionWhenRateDropsToZeroMidway() throws Exception {
        sampler.setDefaultRate(0.0);
        sampler.setEndpointRate(EP_GATE, 1.0); // requests enter while the gate endpoint keeps all
        assertThat(sampler.effectiveRateFor(EP_GATE)).isEqualTo(1.0);

        int inFlight = 8;
        gate.arm(inFlight);
        // Baselines BEFORE dispatching: with decisions pinned (and counted) at
        // request entry, the in-flight requests' kept/dropped stats exist even
        // while they are still blocked at the gate.
        long notSampledBaseline = sampler.notSampledCount();
        long acceptedBaseline = exportQueue.acceptedCount();

        ExecutorService pool = Executors.newFixedThreadPool(inFlight);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < inFlight; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    log.info("verdict: in-flight request index={} entering under rate=1.0 endpoint={}",
                            idx, EP_GATE);
                    return rest.getForEntity("/test/inflight", Map.class);
                }));
            }
            gate.awaitArrivals(inFlight);

            // Change the policy WHILE every request is blocked in the gate.
            sampler.setEndpointRate(EP_GATE, 0.0);
            log.info("verdict: rate changed 1.0->0.0 while {} requests are in flight", inFlight);

            gate.release();
            for (Future<ResponseEntity<Map>> f : futures) {
                ResponseEntity<Map> response = f.get(10, TimeUnit.SECONDS);
                assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                log.info("verdict: in-flight requestId={} completed, kept under pinned decision",
                        response.getBody() == null ? null : response.getBody().get("requestId"));
            }

            // Requests that entered under 1.0 must not have flipped to dropped.
            assertThat(sampler.notSampledCount() - notSampledBaseline).isZero();
            settle();
            assertThat(exportQueue.acceptedCount() - acceptedBaseline).isEqualTo(inFlight);

            // New requests starting after the change follow the new 0.0 policy.
            long notSampledNow = sampler.notSampledCount();
            ResponseEntity<Map> late = rest.getForEntity("/test/inflight", Map.class);
            assertThat(late.getStatusCode().is2xxSuccessful()).isTrue();
            log.info("verdict: new requestId={} after change -> suppressed, stats={}",
                    late.getBody() == null ? null : late.getBody().get("requestId"),
                    sampler.statsSnapshot().get(EP_GATE));
            assertThat(sampler.notSampledCount() - notSampledNow).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Requests that STARTED under drop-all must stay suppressed when the rate
     * rises to keep-all mid-flight; only requests starting afterwards are kept.
     */
    @Test
    void inFlightRequestsStayDroppedWhenRateRaisesToOneMidway() throws Exception {
        sampler.setDefaultRate(0.0); // gate endpoint follows default: requests enter as dropped
        sampler.removeEndpointRate(EP_GATE);
        assertThat(sampler.effectiveRateFor(EP_GATE)).isEqualTo(0.0);

        int inFlight = 8;
        gate.arm(inFlight);
        // Baselines BEFORE dispatching (stats are recorded at request entry).
        long notSampledBaseline = sampler.notSampledCount();
        long acceptedBaseline = exportQueue.acceptedCount();

        ExecutorService pool = Executors.newFixedThreadPool(inFlight);
        try {
            List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < inFlight; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    log.info("verdict: in-flight request index={} entering under rate=0.0 endpoint={}",
                            idx, EP_GATE);
                    return rest.getForEntity("/test/inflight", Map.class);
                }));
            }
            gate.awaitArrivals(inFlight);

            // Raise the policy WHILE every request is blocked in the gate.
            sampler.setDefaultRate(1.0);
            log.info("verdict: rate changed 0.0->1.0 while {} requests are in flight", inFlight);

            gate.release();
            for (Future<ResponseEntity<Map>> f : futures) {
                ResponseEntity<Map> response = f.get(10, TimeUnit.SECONDS);
                assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
                log.info("verdict: in-flight requestId={} completed, stays suppressed under pinned decision",
                        response.getBody() == null ? null : response.getBody().get("requestId"));
            }

            // Requests that entered under 0.0 must not have flipped to kept.
            settle();
            assertThat(sampler.notSampledCount() - notSampledBaseline).isEqualTo(inFlight);
            assertThat(exportQueue.acceptedCount() - acceptedBaseline).isZero();

            // New requests starting after the change follow the new 1.0 policy.
            long acceptedNow = exportQueue.acceptedCount();
            ResponseEntity<Map> late = rest.getForEntity("/test/inflight", Map.class);
            assertThat(late.getStatusCode().is2xxSuccessful()).isTrue();
            settle();
            log.info("verdict: new requestId={} after change -> exported, stats={}",
                    late.getBody() == null ? null : late.getBody().get("requestId"),
                    sampler.statsSnapshot().get(EP_GATE));
            assertThat(exportQueue.acceptedCount() - acceptedNow).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }
}
