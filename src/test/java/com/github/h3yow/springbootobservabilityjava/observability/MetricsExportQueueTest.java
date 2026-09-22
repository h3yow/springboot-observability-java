package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Degradation scenarios of {@link MetricsExportQueue}: queue full, exporter
 * unavailable, export timeout, shutdown. Every drop must be attributed to a
 * distinguishable reason and the submit path must never block the caller.
 */
class MetricsExportQueueTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsExportQueueTest.class);

    private MetricsExportQueue queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.close();
        }
    }

    private static MetricsEvent event(String requestId) {
        return new MetricsEvent(requestId, "GET /demo/work", true, 1_000_000);
    }

    @Test
    void queueFullIsCountedAndDoesNotBlockCaller() throws Exception {
        // Exporter that blocks until released, so the queue backs up.
        CountDownLatch release = new CountDownLatch(1);
        MetricsExporter blocking = new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void export(MetricsEvent event) throws Exception {
                release.await(5, TimeUnit.SECONDS);
            }
        };
        // capacity 2, long export timeout so the worker stays busy
        queue = new MetricsExportQueue(2, 0, 5_000, blocking);
        queue.start();

        List<DropReason> results = new ArrayList<>();
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            String requestId = "req-full-" + i;
            DropReason reason = queue.submit(event(requestId));
            results.add(reason);
            log.info("submit requestId={} result={}", requestId, reason == null ? "ACCEPTED" : reason);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long accepted = results.stream().filter(r -> r == null).count();
        long dropped = results.stream().filter(r -> r == DropReason.QUEUE_FULL).count();
        log.info("verdict: accepted={} droppedQueueFull={} submitElapsedMs={} capacity=2",
                accepted, dropped, elapsedMs);

        assertThat(accepted).isLessThanOrEqualTo(2 + 1); // capacity + one in-flight
        assertThat(dropped).isGreaterThan(0);
        assertThat(queue.dropCounts().get(DropReason.QUEUE_FULL)).isEqualTo(dropped);
        assertThat(elapsedMs).isLessThan(1_000); // non-blocking submit
        release.countDown();
    }

    @Test
    void unavailableExporterRejectsWithDistinctReason() {
        MetricsExporter down = new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return false;
            }

            @Override
            public void export(MetricsEvent event) {
            }
        };
        queue = new MetricsExportQueue(8, 0, 500, down);
        queue.start();

        DropReason reason = queue.submit(event("req-down-1"));
        log.info("submit requestId=req-down-1 result={} (exporter unavailable)", reason);

        assertThat(reason).isEqualTo(DropReason.EXPORTER_UNAVAILABLE);
        assertThat(queue.dropCounts().get(DropReason.EXPORTER_UNAVAILABLE)).isEqualTo(1);
        assertThat(queue.pendingCount()).isZero(); // not even enqueued
    }

    @Test
    void slowExportTimesOutAndIsCounted() throws Exception {
        MetricsExporter slow = new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void export(MetricsEvent event) throws Exception {
                Thread.sleep(10_000); // far beyond export timeout
            }
        };
        queue = new MetricsExportQueue(8, 0, 100, slow); // 100ms export timeout
        queue.start();

        DropReason reason = queue.submit(event("req-slow-1"));
        assertThat(reason).isNull(); // accepted into queue

        awaitDrop(DropReason.EXPORT_TIMEOUT, 1);
        log.info("verdict: requestId=req-slow-1 dropped with reason=EXPORT_TIMEOUT, counts={}",
                queue.dropCounts());
        assertThat(queue.dropCounts().get(DropReason.EXPORT_TIMEOUT)).isEqualTo(1);
    }

    @Test
    void failingExportIsCountedAsExportFailed() throws Exception {
        MetricsExporter failing = new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void export(MetricsEvent event) {
                throw new RuntimeException("boom");
            }
        };
        queue = new MetricsExportQueue(8, 0, 500, failing);
        queue.start();

        queue.submit(event("req-fail-1"));
        awaitDrop(DropReason.EXPORT_FAILED, 1);
        log.info("verdict: requestId=req-fail-1 dropped with reason=EXPORT_FAILED, counts={}",
                queue.dropCounts());
        assertThat(queue.dropCounts().get(DropReason.EXPORT_FAILED)).isEqualTo(1);
    }

    @Test
    void submitAfterShutdownIsCounted() {
        MetricsExporter noop = new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void export(MetricsEvent event) {
            }
        };
        queue = new MetricsExportQueue(8, 0, 500, noop);
        queue.start();
        queue.close();

        DropReason reason = queue.submit(event("req-shutdown-1"));
        log.info("submit requestId=req-shutdown-1 result={} (after shutdown)", reason);
        assertThat(reason).isEqualTo(DropReason.SHUTTING_DOWN);
        assertThat(queue.dropCounts().get(DropReason.SHUTTING_DOWN)).isEqualTo(1);
    }

    @Test
    void happyPathExportsEverything() throws Exception {
        ConcurrentLinkedQueue<String> exportedIds = new ConcurrentLinkedQueue<>();
        MetricsExporter recording = new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void export(MetricsEvent event) {
                exportedIds.add(event.requestId());
            }
        };
        queue = new MetricsExportQueue(64, 0, 500, recording);
        queue.start();

        for (int i = 0; i < 20; i++) {
            assertThat(queue.submit(event("req-ok-" + i))).isNull();
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (exportedIds.size() < 20 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        log.info("verdict: exported={} drops={}", exportedIds.size(), queue.dropCounts());
        assertThat(exportedIds).hasSize(20);
        assertThat(queue.dropCounts().values()).allSatisfy(c -> assertThat(c).isZero());
    }

    @Test
    void wedgedExporterCausesSubsequentEventsToBeRejectedAsUnavailable() throws Exception {
        // Exporter hangs forever on the first call (ignores interrupts),
        // works normally afterwards.
        AtomicBoolean firstCall = new AtomicBoolean(true);
        MetricsExporter hangOnce = new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void export(MetricsEvent event) throws Exception {
                if (firstCall.compareAndSet(true, false)) {
                    Thread.sleep(60_000); // wedged; interrupt is swallowed by sleep->rethrow? no: sleep throws InterruptedException
                }
            }
        };
        queue = new MetricsExportQueue(8, 0, 100, hangOnce);
        queue.start();

        // First event: export hangs -> EXPORT_TIMEOUT, worker stays wedged
        // (sleep is interrupted by cancel(true), so simulate a truly wedged
        // exporter with an uninterruptible loop instead).
        queue.close();

        AtomicBoolean wedged = new AtomicBoolean(true);
        MetricsExporter uninterruptible = new MetricsExporter() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public void export(MetricsEvent event) {
                while (wedged.get()) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                        // wedged exporters ignore interrupts
                    }
                }
            }
        };
        queue = new MetricsExportQueue(8, 0, 100, uninterruptible);
        queue.start();

        DropReason first = queue.submit(event("req-wedge-1"));
        log.info("submit requestId=req-wedge-1 result={}", first == null ? "ACCEPTED" : first);
        awaitDrop(DropReason.EXPORT_TIMEOUT, 1);

        // While the worker is wedged, further events must be rejected as
        // EXPORTER_UNAVAILABLE, not queued behind the stuck export.
        for (int i = 0; i < 3; i++) {
            String requestId = "req-wedge-after-" + i;
            DropReason reason = queue.submit(event(requestId));
            log.info("submit requestId={} result={} (worker wedged)", requestId,
                    reason == null ? "ACCEPTED" : reason);
        }
        awaitDrop(DropReason.EXPORTER_UNAVAILABLE, 3);
        Map<DropReason, Long> counts = queue.dropCounts();
        log.info("verdict: dropCounts={} pending={}", counts, queue.pendingCount());

        assertThat(counts.get(DropReason.EXPORT_TIMEOUT)).isEqualTo(1);
        assertThat(counts.get(DropReason.EXPORTER_UNAVAILABLE)).isEqualTo(3);
        assertThat(queue.pendingCount()).isZero();

        wedged.set(false); // release the wedged worker so close() can finish
    }

    private void awaitDrop(DropReason reason, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Map<DropReason, Long> counts = queue.dropCounts();
            if (counts.get(reason) >= expected) {
                return;
            }
            Thread.sleep(10);
        }
    }
}
