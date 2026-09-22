package com.github.h3yow.springbootobservabilityjava.observability;

/**
 * Reason why a metrics event was rejected or dropped.
 *
 * <p>Every rejected/dropped event is counted under exactly one of these
 * reasons, so degraded operation is always explainable — nothing is
 * silently discarded.
 */
public enum DropReason {
    /** The bounded export queue was full when the event was submitted (backpressure). */
    QUEUE_FULL,
    /** The exporter did not finish one event within the configured export timeout. */
    EXPORT_TIMEOUT,
    /** The exporter reported itself unavailable (or is wedged from a prior hang). */
    EXPORTER_UNAVAILABLE,
    /** The exporter threw while handling the event. */
    EXPORT_FAILED,
    /** The pipeline is shutting down and no longer accepts events. */
    SHUTTING_DOWN
}
