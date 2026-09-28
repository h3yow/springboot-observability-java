package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end verification through the real HTTP stack: request-id
 * propagation, per-endpoint metrics, fan-out attribution and degradation
 * accounting, all readable locally.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ObservabilityEndToEndTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityEndToEndTest.class);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MetricsRegistry metricsRegistry;

    @Autowired
    private MetricsExportQueue exportQueue;

    @Autowired
    private Sampler sampler;

    @Test
    void requestIdIsEchoedAndMetricsAreRecorded() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "req-e2e-1");
        ResponseEntity<Map> response = rest.exchange("/demo/work", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        String echoed = response.getHeaders().getFirst("X-Request-Id");
        Object bodyRequestId = response.getBody() == null ? null : response.getBody().get("requestId");
        log.info("verdict: requestId sent=req-e2e-1 echoed={} body={} status={}",
                echoed, bodyRequestId, response.getStatusCode());

        assertThat(echoed).isEqualTo("req-e2e-1");
        assertThat(bodyRequestId).isEqualTo("req-e2e-1");

        EndpointMetrics metrics = metricsRegistry.metricsOf("GET /demo/work");
        assertThat(metrics).isNotNull();
        log.info("verdict: endpoint=GET /demo/work total={} success={} failure={}",
                metrics.totalCount(), metrics.successCount(), metrics.failureCount());
        assertThat(metrics.totalCount()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.successCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void missingRequestIdGetsGenerated() {
        ResponseEntity<Map> response = rest.getForEntity("/demo/work", Map.class);
        String echoed = response.getHeaders().getFirst("X-Request-Id");
        log.info("verdict: no inbound id, generated requestId={}", echoed);
        assertThat(echoed).isNotBlank();
    }

    @Test
    void fanoutBranchesShareTheParentRequestId() {
        ResponseEntity<Map> response = rest.getForEntity("/demo/fanout", Map.class);
        Map body = response.getBody();
        String parent = (String) body.get("parentRequestId");
        @SuppressWarnings("unchecked")
        List<String> branches = (List<String>) body.get("branchRequestIds");
        log.info("verdict: parentRequestId={} branchRequestIds={} allMatch={}",
                parent, branches, branches.stream().allMatch(parent::equals));

        assertThat(parent).isNotEqualTo("none");
        assertThat(branches).hasSize(4);
        assertThat(branches).allMatch(parent::equals);
    }

    @Test
    void failureIsCountedAsFailure() {
        long before = failureCount();
        rest.getForEntity("/demo/fail", String.class);
        long after = failureCount();
        log.info("verdict: endpoint=GET /demo/fail failure before={} after={}", before, after);
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void exporterOutageIsVisibleInDropAccountingAndDoesNotBreakRequests() {
        // Take the exporter down via the local API.
        rest.postForEntity("/observability/exporter/availability?available=false", null, Map.class);
        try {
            long dropsBefore = dropCount("EXPORTER_UNAVAILABLE");

            ResponseEntity<Map> response = rest.getForEntity("/demo/work", Map.class);
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue(); // business unaffected

            long dropsAfter = dropCount("EXPORTER_UNAVAILABLE");
            log.info("verdict: exporter down, request ok, EXPORTER_UNAVAILABLE drops before={} after={}",
                    dropsBefore, dropsAfter);
            assertThat(dropsAfter).isGreaterThan(dropsBefore);
        } finally {
            rest.postForEntity("/observability/exporter/availability?available=true", null, Map.class);
        }

        Map pipeline = rest.getForObject("/observability/pipeline", Map.class);
        log.info("verdict: pipeline state after recovery={}", pipeline);
        assertThat(pipeline.get("exporterAvailable")).isEqualTo(true);
    }

    private long failureCount() {
        EndpointMetrics m = metricsRegistry.metricsOf("GET /demo/fail");
        return m == null ? 0 : m.failureCount();
    }

    private long dropCount(String reason) {
        Map drops = rest.getForObject("/observability/drops", Map.class);
        Object value = drops == null ? null : drops.get(reason);
        return value == null ? 0 : ((Number) value).longValue();
    }

    @Test
    void ratioZeroSuppressesExportButStillRecordsMetricsAndCountsSampledOut() {
        // Sample nothing for the work endpoint at runtime; restore afterwards.
        setEndpointRatio("GET /demo/work", 0.0);
        try {
            long metricsBefore = workTotal();
            long inBefore = workStat("sampledIn");
            long outBefore = workStat("sampledOut");

            ResponseEntity<Map> response = rest.getForEntity("/demo/work", Map.class);
            String requestId = response.getHeaders().getFirst("X-Request-Id");
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue(); // business unaffected

            long metricsAfter = awaitCounterChange("metrics total", metricsBefore, this::workTotal);
            long inAfter = workStat("sampledIn");
            long outAfter = awaitCounterChange("sampledOut", outBefore,
                    () -> workStat("sampledOut"));

            log.info("verdict: requestId={} ratio=0.0; metrics total {}->{}, sampledIn {}->{}, sampledOut {}->{}",
                    requestId, metricsBefore, metricsAfter, inBefore, inAfter, outBefore, outAfter);

            // Metrics still count the request...
            assertThat(metricsAfter).isEqualTo(metricsBefore + 1);
            // ...it is not selected for export...
            assertThat(inAfter).isEqualTo(inBefore);
            // ...and the suppression is attributed to sampling, distinctly.
            assertThat(outAfter).isEqualTo(outBefore + 1);
        } finally {
            setEndpointRatio("GET /demo/work", null); // remove override
        }
    }

    @Test
    void ratioOneExportsAndSampledOutDoesNotIncrease() {
        setEndpointRatio("GET /demo/work", 1.0);
        try {
            long inBefore = workStat("sampledIn");
            long outBefore = workStat("sampledOut");
            long acceptedBefore = exportQueue.acceptedCount();

            ResponseEntity<Map> response = rest.getForEntity("/demo/work", Map.class);
            String requestId = response.getHeaders().getFirst("X-Request-Id");
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            long acceptedAfter = awaitCounterChange("accepted", acceptedBefore,
                    exportQueue::acceptedCount);
            long inAfter = awaitCounterChange("sampledIn", inBefore,
                    () -> workStat("sampledIn"));

            log.info("verdict: requestId={} ratio=1.0; sampledIn {}->{}, accepted {}->{}, sampledOut stays={}",
                    requestId, inBefore, inAfter, acceptedBefore, acceptedAfter,
                    workStat("sampledOut"));
            assertThat(inAfter).isEqualTo(inBefore + 1);
            assertThat(acceptedAfter).isEqualTo(acceptedBefore + 1);
            assertThat(workStat("sampledOut")).isEqualTo(outBefore);
        } finally {
            setEndpointRatio("GET /demo/work", null);
        }
    }

    @Test
    void runtimeRatioChangeTakesEffectImmediatelyForNewRequests() {
        // Start at 0.0: next request must be sampled out.
        rest.postForEntity("/observability/sampling/default?ratio=0.0", null, Map.class);
        try {
            long outBefore = workStat("sampledOut");
            ResponseEntity<Map> dropped = rest.getForEntity("/demo/work", Map.class);
            long outAfter = awaitCounterChange("sampledOut", outBefore,
                    () -> workStat("sampledOut"));
            log.info("verdict: default=0.0, requestId={} sampled out ({}->{})",
                    dropped.getHeaders().getFirst("X-Request-Id"), outBefore, outAfter);
            assertThat(outAfter).isEqualTo(outBefore + 1);

            // Flip to 1.0 at runtime: the immediately following request is exported.
            rest.postForEntity("/observability/sampling/default?ratio=1.0", null, Map.class);
            long inBefore = workStat("sampledIn");
            long acceptedBefore = exportQueue.acceptedCount();
            ResponseEntity<Map> response = rest.getForEntity("/demo/work", Map.class);
            String requestId = response.getHeaders().getFirst("X-Request-Id");
            long acceptedAfter = awaitCounterChange("accepted", acceptedBefore,
                    exportQueue::acceptedCount);
            long inAfter = awaitCounterChange("sampledIn", inBefore,
                    () -> workStat("sampledIn"));
            log.info("verdict: runtime flip 0.0->1.0, new requestId={} exported immediately (sampledIn {}->{})",
                    requestId, inBefore, inAfter);
            assertThat(inAfter).isEqualTo(inBefore + 1);
            assertThat(acceptedAfter).isEqualTo(acceptedBefore + 1);
        } finally {
            rest.postForEntity("/observability/sampling/default?ratio=1.0", null, Map.class);
        }
    }

    @Test
    void samplingSnapshotShowsEffectiveRatiosAndCounters() {
        setEndpointRatio("GET /demo/work", 0.37);
        try {
            rest.getForEntity("/demo/work", Map.class);
            Map snapshot = rest.getForObject("/observability/sampling", Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) snapshot.get("effectiveRatios");
            log.info("verdict: /observability/sampling snapshot={}", snapshot);
            assertThat(effective.get("GET /demo/work")).isEqualTo(0.37);
            assertThat(snapshot.get("defaultRatio")).isEqualTo(1.0);
        } finally {
            setEndpointRatio("GET /demo/work", null);
        }
    }


    private ResponseEntity<Map> setEndpointRatio(String endpoint, Double ratio) {
        String url = ratio == null
                ? "/observability/sampling/endpoint?endpoint={endpoint}"
                : "/observability/sampling/endpoint?endpoint={endpoint}&ratio={ratio}";
        Object[] args = ratio == null
                ? new Object[]{endpoint}
                : new Object[]{endpoint, ratio};
        return rest.postForEntity(url, null, Map.class, args);
    }

    private long workTotal() {
        EndpointMetrics m = metricsRegistry.metricsOf("GET /demo/work");
        return m == null ? 0 : m.totalCount();
    }

    /** Reads one per-endpoint sampling counter straight from the bean (no HTTP,
     *  so admin polling traffic cannot perturb the assertion). */
    @SuppressWarnings("unchecked")
    private long workStat(String field) {
        Map<String, Object> stats = (Map<String, Object>) sampler.snapshot().get("stats");
        Map<String, Object> row = (Map<String, Object>) stats.get("GET /demo/work");
        return row == null ? 0L : ((Number) row.get(field)).longValue();
    }

    private interface LongSupplier {
        long get();
    }

    private long awaitCounterChange(String name, long before, LongSupplier reader) {
        long deadline = System.currentTimeMillis() + 5_000;
        long value = before;
        while (System.currentTimeMillis() < deadline) {
            value = reader.get();
            if (value != before) {
                return value;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return value;
    }
}
