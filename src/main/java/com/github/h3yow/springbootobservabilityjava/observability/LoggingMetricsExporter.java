package com.github.h3yow.springbootobservabilityjava.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link MetricsExporter} that logs each event locally.
 *
 * <p>Availability is switchable at runtime so degradation behavior
 * (exporter down / recovering) can be exercised without redeploying.
 */
public class LoggingMetricsExporter implements MetricsExporter {

    private static final Logger log = LoggerFactory.getLogger(LoggingMetricsExporter.class);

    private final AtomicBoolean available = new AtomicBoolean(true);

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    public void setAvailable(boolean value) {
        available.set(value);
    }

    @Override
    public void export(MetricsEvent event) {
        log.info("export metrics: requestId={} endpoint={} success={} latencyMs={}",
                event.requestId(), event.endpoint(), event.success(),
                event.latencyNanos() / 1_000_000.0);
    }
}
