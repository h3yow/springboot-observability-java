package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Bounded, non-blocking export queue with explicit degradation semantics.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Never blocks the business thread</b>: {@link #submit} waits at most
 *       {@code offerTimeoutMillis} (default 0 = pure non-blocking) and then
 *       rejects with {@link DropReason#QUEUE_FULL}.</li>
 *   <li><b>Never grows unboundedly</b>: capacity is fixed at construction.</li>
 *   <li><b>Never silently drops</b>: every rejected or failed event is counted
 *       under exactly one {@link DropReason}, readable via {@link #dropCounts()}.</li>
 *   <li><b>Exporter faults are contained</b>: each export runs with
 *       {@code exportTimeoutMillis}; a timed-out or failed export drops only
 *       that event, and a wedged exporter causes further events to be
 *       rejected as {@link DropReason#EXPORTER_UNAVAILABLE} instead of
 *       stalling the pipeline.</li>
 * </ul>
 */
public class MetricsExportQueue implements AutoCloseable {

    private final ArrayBlockingQueue<MetricsEvent> queue;
    private final MetricsExporter exporter;
    private final long offerTimeoutMillis;
    private final long exportTimeoutMillis;
    private final int capacity;

    private final Map<DropReason, LongAdder> dropCounts = new EnumMap<>(DropReason.class);
    private final LongAdder accepted = new LongAdder();
    private final LongAdder exported = new LongAdder();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread dispatcher;
    private ExecutorService exportExecutor;
    /** Non-null after an export timed out, until the wedged task actually ends. */
    private volatile Future<?> wedgedExport;
    /** True while the worker is inside {@code exporter.export} (cancel() does
     *  not guarantee the task stops, so track real liveness ourselves). */
    private final AtomicBoolean exportTaskActive = new AtomicBoolean(false);

    public MetricsExportQueue(int capacity, long offerTimeoutMillis, long exportTimeoutMillis,
                              MetricsExporter exporter) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.offerTimeoutMillis = Math.max(0, offerTimeoutMillis);
        this.exportTimeoutMillis = Math.max(1, exportTimeoutMillis);
        this.exporter = exporter;
        for (DropReason reason : DropReason.values()) {
            dropCounts.put(reason, new LongAdder());
        }
    }

    /** Starts the dispatcher thread. Idempotent. */
    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        // Single worker; the dispatcher submits at most one task at a time and
        // guards with wedgedExport, so the internal queue can never grow past 1.
        this.exportExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "metrics-export-worker");
            t.setDaemon(true);
            return t;
        });
        this.dispatcher = new Thread(this::dispatchLoop, "metrics-export-dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    /**
     * Submits an event for export. Never blocks longer than
     * {@code offerTimeoutMillis} and never throws for degradation reasons.
     *
     * @return {@code null} if accepted, otherwise the {@link DropReason}.
     */
    public DropReason submit(MetricsEvent event) {
        if (!running.get()) {
            return drop(event, DropReason.SHUTTING_DOWN);
        }
        if (!exporter.isAvailable()) {
            return drop(event, DropReason.EXPORTER_UNAVAILABLE);
        }
        boolean offered;
        try {
            offered = offerTimeoutMillis <= 0
                    ? queue.offer(event)
                    : queue.offer(event, offerTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return drop(event, DropReason.QUEUE_FULL);
        }
        if (!offered) {
            return drop(event, DropReason.QUEUE_FULL);
        }
        accepted.increment();
        return null;
    }

    private DropReason drop(MetricsEvent event, DropReason reason) {
        dropCounts.get(reason).increment();
        return reason;
    }

    private void dispatchLoop() {
        while (running.get() || !queue.isEmpty()) {
            MetricsEvent event;
            try {
                event = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == null) {
                continue;
            }
            exportOne(event);
        }
    }

    private void exportOne(MetricsEvent event) {
        if (wedgedExport != null) {
            if (exportTaskActive.get()) {
                // A previous export never finished: the exporter is effectively
                // down, so reject instead of piling work behind it.
                drop(event, DropReason.EXPORTER_UNAVAILABLE);
                return;
            }
            wedgedExport = null; // exporter recovered
        }
        exportTaskActive.set(true);
        Future<?> future = exportExecutor.submit(() -> {
            try {
                exporter.export(event);
            } finally {
                exportTaskActive.set(false);
            }
            return null;
        });
        try {
            future.get(exportTimeoutMillis, TimeUnit.MILLISECONDS);
            exported.increment();
        } catch (TimeoutException e) {
            future.cancel(true);
            wedgedExport = future; // ignore further events until this one ends
            drop(event, DropReason.EXPORT_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            drop(event, DropReason.SHUTTING_DOWN);
        } catch (Exception e) {
            drop(event, DropReason.EXPORT_FAILED);
        }
    }

    /** Count of events rejected/dropped per reason. */
    public Map<DropReason, Long> dropCounts() {
        Map<DropReason, Long> out = new EnumMap<>(DropReason.class);
        dropCounts.forEach((reason, adder) -> out.put(reason, adder.sum()));
        return out;
    }

    public long acceptedCount() {
        return accepted.sum();
    }

    public long exportedCount() {
        return exported.sum();
    }

    public int pendingCount() {
        return queue.size();
    }

    public int capacity() {
        return capacity;
    }

    /** Stops accepting new events and drains what remains, bounded by a wait. */
    @Override
    public synchronized void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        try {
            dispatcher.join(Math.max(1000, exportTimeoutMillis * 2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Anything still pending at shutdown is accounted, not silently lost.
        MetricsEvent leftover;
        while ((leftover = queue.poll()) != null) {
            drop(leftover, DropReason.SHUTTING_DOWN);
        }
        exportExecutor.shutdownNow();
    }
}
