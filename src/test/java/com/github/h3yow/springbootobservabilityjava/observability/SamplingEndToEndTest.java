package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end sampling behavior through the real HTTP stack: boundary rates,
 * metrics coverage independent of sampling, runtime rate changes, per-endpoint
 * overrides and distinct drop accounting. Policy is restored around every test;
 * all count assertions use deltas captured inside the test because the single
 * Spring context (and its samplers/counters) is shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SamplingEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(SamplingEndToEndTest.class);
    private static final String EP_WORK = "GET /demo/work";
    private static final String EP_FAIL = "GET /demo/fail";

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
    }

    @AfterEach
    void restorePolicy() {
        sampler.setDefaultRate(1.0);
        sampler.removeEndpointRate(EP_WORK);
        sampler.removeEndpointRate(EP_FAIL);
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
    void dropAllStillRecordsMetricsAndCountsNotSampledSeparately() {
        long notSampledBaseline = sampler.notSampledCount();
        long acceptedBaseline = exportQueue.acceptedCount();
        long queueFullBaseline = exportQueue.dropCounts().get(DropReason.QUEUE_FULL);
        EndpointMetrics metrics = metricsRegistry.metricsOf(EP_WORK);
        long totalBaseline = metrics == null ? 0 : metrics.totalCount();

        post("/observability/sampling/default-rate?rate=0.0");

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
        log.info("verdict: notSampledDelta={} (10 demo + 1 admin reset under new rate) acceptedDelta={} metricsTotalDelta={}",
                notSampledDelta, acceptedDelta, totalDelta);

        assertThat(notSampledDelta).isGreaterThanOrEqualTo(10);
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
}
