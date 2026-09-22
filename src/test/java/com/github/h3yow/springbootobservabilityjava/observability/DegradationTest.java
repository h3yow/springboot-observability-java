package com.github.h3yow.springbootobservabilityjava.observability;

import com.github.h3yow.springbootobservabilityjava.observability.config.ObservabilityProperties;
import com.github.h3yow.springbootobservabilityjava.observability.export.BoundedEventExporter;
import com.github.h3yow.springbootobservabilityjava.observability.export.DropReason;
import com.github.h3yow.springbootobservabilityjava.observability.export.RequestEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级行为验证：每种拒绝/丢弃都有可区分原因，submit 不阻塞业务线程，队列不无界增长。
 * 每条断言打印请求标识与判定依据。
 */
class DegradationTest {

    private static final Logger log = LoggerFactory.getLogger(DegradationTest.class);

    private ObservabilityProperties props(int capacity, double sampleRate, long timeoutMillis) {
        ObservabilityProperties p = new ObservabilityProperties();
        p.setQueueCapacity(capacity);
        p.setSampleRate(sampleRate);
        p.setExportTimeoutMillis(timeoutMillis);
        return p;
    }

    private RequestEvent event(String requestId) {
        return new RequestEvent(requestId, "GET /demo/ok", true, 1_000_000);
    }

    @Test
    void queueFullIsRejectedWithDistinctReasonAndNeverBlocks() throws Exception {
        CountDownLatch sinkGate = new CountDownLatch(1);
        try (BoundedEventExporter exporter = new BoundedEventExporter(props(4, 1.0, 5_000))) {
            exporter.setSink(e -> {
                try {
                    sinkGate.await(); // 堵住导出，制造积压
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            int attempts = 20;
            List<DropReason> reasons = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < attempts; i++) {
                reasons.add(exporter.submit(event("req-overflow-" + i)));
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            long rejected = reasons.stream().filter(r -> r == DropReason.QUEUE_FULL).count();
            // 判定依据：容量 4 + 在途 1，超出部分必须全部以 QUEUE_FULL 拒绝，且 20 次 submit 总耗时远小于 1s（不阻塞）
            log.info("queue full check: requestIds=req-overflow-[0..{}], capacity=4, rejected={}, elapsedMs={}, pass={}",
                    attempts - 1, rejected, elapsedMs, rejected >= attempts - 5 && elapsedMs < 1000);
            assertThat(rejected).isGreaterThanOrEqualTo(attempts - 5);
            assertThat(elapsedMs).isLessThan(1000);
            assertThat(exporter.dropCounts().get(DropReason.QUEUE_FULL)).isEqualTo(rejected);
            sinkGate.countDown();
        }
    }

    @Test
    void exporterUnavailableIsRejectedImmediately() {
        ObservabilityProperties p = props(16, 1.0, 500);
        p.setExporterUnavailable(true);
        try (BoundedEventExporter exporter = new BoundedEventExporter(p)) {
            DropReason reason = exporter.submit(event("req-degraded-1"));
            // 判定依据：降级开关打开时必须返回 EXPORTER_UNAVAILABLE 且计数为 1
            log.info("unavailable check: requestId=req-degraded-1, reason={}, pass={}",
                    reason, reason == DropReason.EXPORTER_UNAVAILABLE);
            assertThat(reason).isEqualTo(DropReason.EXPORTER_UNAVAILABLE);
            assertThat(exporter.dropCounts().get(DropReason.EXPORTER_UNAVAILABLE)).isEqualTo(1);
        }
    }

    @Test
    void sampledOutIsCountedWithDistinctReason() {
        try (BoundedEventExporter exporter = new BoundedEventExporter(props(16, 0.0, 500))) {
            DropReason reason = exporter.submit(event("req-sampled-1"));
            // 判定依据：采样率 0 时必须返回 SAMPLED_OUT，且不得进入导出计数
            log.info("sampling check: requestId=req-sampled-1, reason={}, exported={}, pass={}",
                    reason, exporter.exportedCount(),
                    reason == DropReason.SAMPLED_OUT && exporter.exportedCount() == 0);
            assertThat(reason).isEqualTo(DropReason.SAMPLED_OUT);
            assertThat(exporter.dropCounts().get(DropReason.SAMPLED_OUT)).isEqualTo(1);
            assertThat(exporter.exportedCount()).isZero();
        }
    }

    @Test
    void exportTimeoutIsCountedWithDistinctReason() throws Exception {
        try (BoundedEventExporter exporter = new BoundedEventExporter(props(16, 1.0, 100))) {
            exporter.setSink(e -> {
                try {
                    Thread.sleep(2_000); // 超过 100ms 导出超时
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            DropReason submitResult = exporter.submit(event("req-timeout-1"));
            assertThat(submitResult).isNull(); // 入队成功，超时发生在导出阶段
            exporter.awaitIdle(5_000);
            // 判定依据：sink 耗时 2000ms > 超时 100ms，必须记 EXPORT_TIMEOUT
            long timeouts = exporter.dropCounts().get(DropReason.EXPORT_TIMEOUT);
            log.info("timeout check: requestId=req-timeout-1, timeoutCount={}, pass={}", timeouts, timeouts == 1);
            assertThat(timeouts).isEqualTo(1);
        }
    }

    @Test
    void exportFailureIsCountedWithDistinctReason() throws Exception {
        try (BoundedEventExporter exporter = new BoundedEventExporter(props(16, 1.0, 500))) {
            exporter.setSink(e -> {
                throw new RuntimeException("sink broken");
            });
            exporter.submit(event("req-failure-1"));
            exporter.awaitIdle(5_000);
            // 判定依据：sink 抛异常必须记 EXPORT_FAILURE
            long failures = exporter.dropCounts().get(DropReason.EXPORT_FAILURE);
            log.info("failure check: requestId=req-failure-1, failureCount={}, pass={}", failures, failures == 1);
            assertThat(failures).isEqualTo(1);
        }
    }

    @Test
    void acceptedEventsAreExportedExactly() throws Exception {
        try (BoundedEventExporter exporter = new BoundedEventExporter(props(64, 1.0, 500))) {
            int count = 10;
            for (int i = 0; i < count; i++) {
                assertThat(exporter.submit(event("req-normal-" + i))).isNull();
            }
            exporter.awaitIdle(5_000);
            // 判定依据：全部接受且无降级时，导出数必须精确等于提交数，无任何丢弃
            log.info("normal path check: requestIds=req-normal-[0..{}], exported={}, drops={}, pass={}",
                    count - 1, exporter.exportedCount(), exporter.dropCounts(),
                    exporter.exportedCount() == count);
            assertThat(exporter.exportedCount()).isEqualTo(count);
            assertThat(exporter.dropCounts().values()).allMatch(c -> c == 0);
        }
    }
}
