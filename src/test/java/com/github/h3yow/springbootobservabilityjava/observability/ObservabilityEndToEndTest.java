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
}
