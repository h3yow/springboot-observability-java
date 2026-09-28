package com.github.h3yow.springbootobservabilityjava.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-level guarantee of the sampling gate in {@link RequestContextFilter}:
 * <ul>
 *   <li>success/failure and latency metrics are recorded for <em>every</em>
 *       request regardless of the sampling verdict;</li>
 *   <li>an event reaches the export queue iff the request was sampled in;</li>
 *   <li>the verdict and request id are logged for reproduction.</li>
 * </ul>
 */
class RequestContextFilterSamplingTest {

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilterSamplingTest.class);

    private MetricsExportQueue queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
        RequestContextHolder.detach(); // safety net; the filter detaches itself
    }

    private MetricsExportQueue queue() {
        queue = new MetricsExportQueue(64, 0, 500, new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void export(MetricsEvent event) {
                // no-op sink
            }
        });
        queue.start();
        return queue;
    }

    private void invoke(RequestContextFilter filter, MockHttpServletRequest request,
                        MockHttpServletResponse response, boolean handlerFails) throws Exception {
        FilterChain chain = (req, res) -> {
            if (handlerFails) {
                ((MockHttpServletResponse) res).setStatus(500);
                throw new IllegalStateException("boom");
            }
            ((MockHttpServletResponse) res).setStatus(200);
        };
        try {
            filter.doFilter(request, response, chain);
        } catch (IllegalStateException expected) {
            // the failing handler propagates after metrics/export handling
        }
    }

    @Test
    void sampledOutRequestStillRecordsMetricsButNeverReachesQueue() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        MetricsExportQueue exportQueue = queue();
        Sampler sampler = new Sampler(0.0); // drop all
        RequestContextFilter filter = new RequestContextFilter(registry, exportQueue, sampler,
                new ObservabilityProperties());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo/work");
        MockHttpServletResponse response = new MockHttpServletResponse();
        invoke(filter, request, response, false);

        EndpointMetrics metrics = registry.metricsOf("GET /demo/work");
        log.info("verdict: sampled-out request id={}; metrics total={} success={} accepted={} sampledOutTotal={}",
                response.getHeader("X-Request-Id"), metrics.totalCount(), metrics.successCount(),
                exportQueue.acceptedCount(), sampler.sampledOutCount());

        assertThat(metrics.totalCount()).isEqualTo(1);
        assertThat(metrics.successCount()).isEqualTo(1);
        assertThat(exportQueue.acceptedCount()).isZero();      // never enqueued
        assertThat(exportQueue.pendingCount()).isZero();
        assertThat(sampler.sampledOutCount()).isEqualTo(1);
        assertThat(RequestContextHolder.current()).isNull();    // detached in finally
        assertThat(response.getHeader("X-Request-Id")).isNotBlank();
    }

    @Test
    void sampledInFailureRequestRecordsFailureAndIsExported() throws Exception {
        MetricsRegistry registry = new MetricsRegistry();
        MetricsExportQueue exportQueue = queue();
        Sampler sampler = new Sampler(1.0); // keep all
        RequestContextFilter filter = new RequestContextFilter(registry, exportQueue, sampler,
                new ObservabilityProperties());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/demo/fail");
        MockHttpServletResponse response = new MockHttpServletResponse();
        invoke(filter, request, response, true);

        EndpointMetrics metrics = registry.metricsOf("GET /demo/fail");
        long exported = exportQueue.exportedCount();
        log.info("verdict: sampled-in failing request id={}; total={} success={} failure={} exported={}",
                response.getHeader("X-Request-Id"), metrics.totalCount(), metrics.successCount(),
                metrics.failureCount(), exported);

        assertThat(metrics.totalCount()).isEqualTo(1);
        assertThat(metrics.failureCount()).isEqualTo(1);
        assertThat(metrics.successCount()).isZero();
        // Sampling does not perturb failure accounting; the event was exported.
        long deadline = System.currentTimeMillis() + 5_000;
        while (exportQueue.exportedCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(exportQueue.exportedCount()).isEqualTo(1);
        assertThat(sampler.sampledOutCount()).isZero();
        assertThat(RequestContextHolder.current()).isNull();
    }
}
