package com.github.h3yow.springbootobservabilityjava.observability;

import com.github.h3yow.springbootobservabilityjava.observability.context.RequestContextFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地读取验证：服务自身接口 /observability/metrics 可读取指标与降级计数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LocalMetricsEndpointTest {

    private static final Logger log = LoggerFactory.getLogger(LocalMetricsEndpointTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void metricsEndpointExposesEndpointsAndExporter() {
        String requestId = "req-local-read-1";
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestContextFilter.REQUEST_ID_HEADER, requestId);
        restTemplate.exchange("/demo/ok", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        var response = restTemplate.getForEntity("/observability/metrics", Map.class);
        Map body = response.getBody();
        Map endpoints = (Map) body.get("endpoints");
        Map exporter = (Map) body.get("exporter");
        Map okMetrics = (Map) endpoints.get("GET /demo/ok");
        // 判定依据：本地接口必须返回该接口的计数与耗时桶，以及导出器的 exported/backlog/drops
        boolean pass = okMetrics != null
                && ((Number) okMetrics.get("total")).longValue() >= 1
                && okMetrics.get("latencyBuckets") != null
                && exporter.get("exported") != null
                && exporter.get("drops") != null;
        log.info("local read check: requestId={}, endpointMetrics={}, exporter={}, pass={}",
                requestId, okMetrics, exporter, pass);
        assertThat(pass).isTrue();
    }
}
