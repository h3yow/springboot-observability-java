package com.github.h3yow.springbootobservabilityjava.observability;

/**
 * Sink for exported metrics events.
 *
 * <p>Implementations must be thread-safe. {@link #export(MetricsEvent)} is
 * invoked by the export queue's dispatcher thread; it should honor its own
 * internal timeout so a slow sink cannot stall the pipeline indefinitely.
 */
public interface MetricsExporter {

    /** Whether the exporter is currently able to accept events. */
    boolean isAvailable();

    /**
     * Exports one event.
     *
     * @throws Exception if the export fails (the queue counts this as a
     *         {@code EXPORT_FAILED} drop and moves on; it never retries
     *         indefinitely).
     */
    void export(MetricsEvent event) throws Exception;
}
