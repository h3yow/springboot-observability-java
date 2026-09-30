package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.github.h3yow.springbootobservabilityjava.observability.support.InFlightProbeController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end sampling behavior through the real HTTP stack: boundary rates,
 * metrics coverage independent of sampling, runtime rate changes, per-endpoint
 * overrides and distinct drop accounting. Policy is restored around every test;
 * all count assertions use deltas captured inside the test because the single
 * Spring context (and its samplers/counters) is shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SamplingEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(SamplingEndToEndTest.class);
    private static final String EP_WORK = "GET /demo/work";
    private static final String EP_FAIL = "GET /demo/fail";
    private static final String EP_PROBE = "GET /testsupport/inflight";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private Sampler sampler;

    @Autowired
    private MetricsRegistry metricsRegistry;

    @Autowired
    private MetricsExportQueue exportQueue;

    private int port;

    @BeforeEach
    void resetPolicy() {
        port = rest.getRootUri() == null ? 0 : portFromRootUri(rest.getRootUri());
        sampler.setDefaultRate(1.0);
        sampler.removeEndpointRate(EP_WORK);
        sampler.removeEndpointRate(EP_FAIL);
        sampler.removeEndpointRate(EP_PROBE);
    }

    @AfterEach
    void restorePolicy() {
        sampler.setDefaultRate(1.0);
        sampler.removeEndpointRate(EP_WORK);
        sampler.removeEndpointRate(EP_FAIL);
        sampler.removeEndpointRate(EP_PROBE);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> drops() {
        return rest.getForObject("/observability/drops", Map.class);
    }

    @Test
    @Order(10)
    void dropAllStillRecordsMetricsAndCountsNotSampledSeparately() {
        long notSampledBaseline = sampler.notSampledCount();
        long acceptedBaseline = exportQueue.acceptedCount();
        long queueFullBaseline = exportQueue.dropCounts().get(DropReason.QUEUE_FULL);
        EndpointMetrics metrics = metricsRegistry.metricsOf(EP_WORK);
        long totalBaseline = metrics == null ? 0 : metrics.totalCount();

        // Switch in-process so the switch call itself does not pass through the
        // filter as an extra request. The HTTP switch path is exercised in
        // perEndpointOverrideAndRuntimeChangeTakeEffectImmediately.
        sampler.setDefaultRate(0.0);

        for (int i = 0; i < 10; i++) {
            String id = "req-e2e-drop-" + i;
            var response = rest.getForEntity("/demo/work", Map.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue(); // business never blocked
            assertThat(response.getHeaders().getFirst("X-Request-Id")).isNotNull();
            log.info("verdict: requestId={} rate=0.0 -> business ok, suppressed from export", id);
        }

        long notSampledDelta = sampler.notSampledCount() - notSampledBaseline;
        long acceptedDelta = exportQueue.acceptedCount() - acceptedBaseline;
        long totalDelta = metricsRegistry.metricsOf(EP_WORK).totalCount() - totalBaseline;
        log.info("verdict: notSampledDelta={} (10 demo requests under rate 0.0) acceptedDelta={} metricsTotalDelta={}",
                notSampledDelta, acceptedDelta, totalDelta);

        assertThat(notSampledDelta).isEqualTo(10);
        assertThat(acceptedDelta).isZero();                       // no demo event entered the queue
        assertThat(exportQueue.dropCounts().get(DropReason.QUEUE_FULL)).isEqualTo(queueFullBaseline);
        assertThat(totalDelta).isEqualTo(10);                    // metrics cover every request
        assertThat(metricsRegistry.metricsOf(EP_WORK).successCount())
                .isGreaterThanOrEqualTo(metricsRegistry.metricsOf(EP_WORK).totalCount());
    }

    @Test
    @Order(20)
    void keepAllExportsEveryRequest() {
        long acceptedBaseline = exportQueue.acceptedCount();
        long notSampledBaseline = sampler.notSampledCount();
        for (int i = 0; i < 10; i++) {
            ResponseEntity<Map> response = rest.getForEntity("/demo/work", Map.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        }
        log.info("verdict: rate=1.0 acceptedDelta=10 notSampledDelta=0");
        assertThat(exportQueue.acceptedCount() - acceptedBaseline).isEqualTo(10);
        assertThat(sampler.notSampledCount() - notSampledBaseline).isZero();
    }

    @Test
    @Order(30)
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
    @Order(40)
    void perEndpointOverrideAndRuntimeChangeTakeEffectImmediately() {
        // Default drops all; only /demo/work is kept.
        post("/observability/sampling/default-rate?rate=0.0");
        post("/observability/sampling/endpoint-rate?endpoint=" + enc(EP_WORK) + "&rate=1.0");

        long acceptedBaseline = exportQueue.acceptedCount();
        long notSampledBaseline = sampler.notSampledCount();

        assertThat(sampler.effectiveRateFor(EP_WORK)).isEqualTo(1.0);
        rest.getForEntity("/demo/work", Map.class);  // override keeps
        rest.getForEntity("/demo/fail", Map.class);  // default drops

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
     * A request that STARTS while rate=1.0 must be exported even if the rate is
     * flipped to 0.0 while it is still being processed; requests that start
     * after the flip must be suppressed.
     */
    @Test
    @Order(50)
    void inFlightRequestStaysKeptWhenRateDropsToZeroMidway() throws Exception {
        InFlightProbeController.reset();
        sampler.setDefaultRate(1.0);
        long acceptedBaseline = exportQueue.acceptedCount();
        long notSampledBaseline = sampler.notSampledCount();

        java.util.concurrent.ExecutorService background =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ResponseEntity<Map>> inFlight =
                    background.submit(() -> rest.getForEntity("/testsupport/inflight", Map.class));

            // Deterministic barrier: the probe only signals once the filter has
            // pinned the verdict at entry and the request body is blocked.
            InFlightProbeController.awaitPinned();

            // Flip while the request is provably still in flight.
            sampler.setDefaultRate(0.0);
            log.info("verdict: default rate flipped 1.0->0.0 while probe request is in flight");

            // A request that STARTS after the flip follows the new rate immediately.
            long notSampledBeforeNew = sampler.notSampledCount();
            ResponseEntity<Map> newResponse = rest.getForEntity("/demo/work", Map.class);
            long newNotSampledDelta = sampler.notSampledCount() - notSampledBeforeNew;
            log.info("verdict: new requestId={} after flip, status={}, effectiveRateFor[{}]={}, notSampledDelta={}",
                    newResponse.getHeaders().getFirst("X-Request-Id"),
                    newResponse.getStatusCode(), EP_WORK, sampler.effectiveRateFor(EP_WORK),
                    newNotSampledDelta);
            assertThat(newNotSampledDelta)
                    .as("a new request after the flip must be suppressed")
                    .isEqualTo(1);

            // Release the in-flight request; it must finish successfully and keep
            // the keep-verdict it pinned under 1.0.
            InFlightProbeController.allowCompletion();
            ResponseEntity<Map> response = inFlight.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            String probeRequestId = response.getHeaders().getFirst("X-Request-Id");

            awaitQueueDrain();
            long acceptedDelta = exportQueue.acceptedCount() - acceptedBaseline;
            long notSampledDelta = sampler.notSampledCount() - notSampledBaseline;
            log.info("verdict: requestId={} in-flight under 1.0 flipped to 0.0; acceptedDelta={} (in-flight kept) "
                    + "notSampledDelta={} (new request only)", probeRequestId, acceptedDelta, notSampledDelta);
            assertThat(acceptedDelta)
                    .as("in-flight requestId=%s must keep its pinned keep verdict", probeRequestId)
                    .isEqualTo(1);
            assertThat(notSampledDelta).isEqualTo(1); // only the new /demo/work request
        } finally {
            InFlightProbeController.allowCompletion(); // never block a background thread
            background.shutdownNow();
        }
    }

    /**
     * The reverse direction: a request that STARTS while rate=0.0 must stay
     * suppressed even if the rate is raised to 1.0 while it is still being
     * processed; requests that start after the raise are exported immediately.
     */
    @Test
    @Order(60)
    void inFlightRequestStaysDroppedWhenRateRaisesToOneMidway() throws Exception {
        InFlightProbeController.reset();
        sampler.setDefaultRate(0.0);
        long acceptedBaseline = exportQueue.acceptedCount();
        long notSampledBaseline = sampler.notSampledCount();

        java.util.concurrent.ExecutorService background =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ResponseEntity<Map>> inFlight =
                    background.submit(() -> rest.getForEntity("/testsupport/inflight", Map.class));

            InFlightProbeController.awaitPinned();

            sampler.setDefaultRate(1.0);
            log.info("verdict: default rate flipped 0.0->1.0 while probe request is in flight");

            // A request starting AFTER the raise follows the new rate immediately.
            long acceptedBeforeNew = exportQueue.acceptedCount();
            ResponseEntity<Map> newResponse = rest.getForEntity("/demo/work", Map.class);
            awaitQueueDrain();
            long newAcceptedDelta = exportQueue.acceptedCount() - acceptedBeforeNew;
            log.info("verdict: new requestId={} after raise, status={}, acceptedDelta={}",
                    newResponse.getHeaders().getFirst("X-Request-Id"), newResponse.getStatusCode(),
                    newAcceptedDelta);
            assertThat(newAcceptedDelta)
                    .as("a new request after the raise must be exported")
                    .isEqualTo(1);

            // Release the in-flight request; it must stay suppressed under its
            // pinned 0.0 verdict even though the current rate is 1.0.
            InFlightProbeController.allowCompletion();
            ResponseEntity<Map> response = inFlight.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            String probeRequestId = response.getHeaders().getFirst("X-Request-Id");

            long acceptedDelta = exportQueue.acceptedCount() - acceptedBaseline;
            long notSampledDelta = sampler.notSampledCount() - notSampledBaseline;
            log.info("verdict: requestId={} in-flight under 0.0 raised to 1.0; acceptedDelta={} (new request only) "
                    + "notSampledDelta={} (in-flight stays suppressed)", probeRequestId, acceptedDelta,
                    notSampledDelta);
            assertThat(acceptedDelta).isEqualTo(1);  // only the new /demo/work request
            assertThat(notSampledDelta).isEqualTo(1); // only the pinned-suppressed probe request
        } finally {
            InFlightProbeController.allowCompletion();
            background.shutdownNow();
        }
    }

    /** Gives the dispatcher thread a short window to drain accepted events. */
    private void awaitQueueDrain() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && exportQueue.pendingCount() > 0) {
            Thread.sleep(5);
        }
    }
}
