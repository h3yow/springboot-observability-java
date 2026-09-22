package com.github.h3yow.springbootobservabilityjava.observability;

import com.github.h3yow.springbootobservabilityjava.observability.context.RequestContextFilter;
import com.github.h3yow.springbootobservabilityjava.observability.metrics.EndpointMetrics;
import com.github.h3yow.springbootobservabilityjava.observability.metrics.MetricsRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 计数一致性验证：并发写入下总数不丢失不重复，成功+失败=总数，耗时桶合计=总数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsConsistencyTest.class);
    private static final String ENDPOINT_OK = "GET /demo/ok";
    private static final String ENDPOINT_FAIL = "GET /demo/fail";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MetricsRegistry metricsRegistry;

    @Test
    void concurrentWritesAreCountedExactly() throws Exception {
        EndpointMetrics beforeOk = metricsRegistry.snapshot().get(ENDPOINT_OK);
        long baseTotal = beforeOk == null ? 0 : beforeOk.getTotal();

        int requestCount = 200;
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            String requestId = "req-metrics-" + i;
            futures.add(pool.submit(() -> {
                HttpHeaders headers = new HttpHeaders();
                headers.set(RequestContextFilter.REQUEST_ID_HEADER, requestId);
                var response = restTemplate.exchange("/demo/ok", HttpMethod.GET, new HttpEntity<>(headers), String.class);
                boolean ok = response.getStatusCode().is2xxSuccessful();
                log.info("metrics request done: requestId={}, status={}", requestId, response.getStatusCode());
                return ok;
            }));
        }
        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                succeeded++;
            }
        }
        pool.shutdown();

        EndpointMetrics after = metricsRegistry.snapshot().get(ENDPOINT_OK);
        long delta = after.getTotal() - baseTotal;
        // 判定依据：并发 N 次调用后，该接口计数增量必须精确等于 N，且 success+failure=total，桶合计=total
        long bucketSum = 0;
        for (long b : after.getLatencyBuckets()) {
            bucketSum += b;
        }
        log.info("consistency check: endpoint={}, expectedDelta={}, actualDelta={}, success={}, failure={}, bucketSum={}, pass={}",
                ENDPOINT_OK, requestCount, delta, after.getSuccess(), after.getFailure(), bucketSum,
                delta == requestCount && after.getSuccess() + after.getFailure() == after.getTotal()
                        && bucketSum == after.getTotal());
        assertThat(succeeded).isEqualTo(requestCount);
        assertThat(delta).isEqualTo(requestCount);
        assertThat(after.getSuccess() + after.getFailure()).isEqualTo(after.getTotal());
        assertThat(bucketSum).isEqualTo(after.getTotal());
    }

    @Test
    void failureResultsAreCountedSeparately() {
        EndpointMetrics before = metricsRegistry.snapshot().get(ENDPOINT_FAIL);
        long baseTotal = before == null ? 0 : before.getTotal();
        long baseFailure = before == null ? 0 : before.getFailure();

        String requestId = "req-metrics-fail-1";
        HttpHeaders headers = new HttpHeaders();
        headers.set(RequestContextFilter.REQUEST_ID_HEADER, requestId);
        var response = restTemplate.exchange("/demo/fail", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        EndpointMetrics after = metricsRegistry.snapshot().get(ENDPOINT_FAIL);
        // 判定依据：500 响应必须计入 failure 且 total 恰好 +1
        log.info("failure check: requestId={}, status={}, totalDelta={}, failureDelta={}, pass={}",
                requestId, response.getStatusCode(),
                after.getTotal() - baseTotal, after.getFailure() - baseFailure,
                response.getStatusCode().is5xxServerError()
                        && after.getTotal() - baseTotal == 1
                        && after.getFailure() - baseFailure == 1);
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        assertThat(after.getTotal() - baseTotal).isEqualTo(1);
        assertThat(after.getFailure() - baseFailure).isEqualTo(1);
    }
}
