package com.github.h3yow.springbootobservabilityjava.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the observability pipeline.
 *
 * <pre>
 * observability.export-queue-capacity=1024      # bounded queue size
 * observability.offer-timeout-millis=0          # max time a request thread may wait to enqueue (0 = non-blocking)
 * observability.export-timeout-millis=500       # max time one export may take before the event is dropped as EXPORT_TIMEOUT
 * observability.request-id-header=X-Request-Id  # inbound/outbound request id header
 *
 * # Sampling policy (1.0 = export every event, 0.0 = export none)
 * observability.default-sample-rate=1.0                        # rate for endpoints without an override
 * observability.sample-rates[GET /demo/work]=0.1               # per-endpoint overrides
 * </pre>
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    /** Bounded capacity of the export queue. */
    private int exportQueueCapacity = 1024;

    /** Max milliseconds a request thread may wait to enqueue an event; 0 = never wait. */
    private long offerTimeoutMillis = 0;

    /** Max milliseconds one export may take before the event is dropped as EXPORT_TIMEOUT. */
    private long exportTimeoutMillis = 500;

    /** Header used to carry the request id in and out of the service. */
    private String requestIdHeader = "X-Request-Id";

    /** Default sampling rate applied to endpoints without an override; 1.0 = all, 0.0 = none. */
    private double defaultSampleRate = 1.0;

    /** Per-endpoint sampling rate overrides, keyed by the stable endpoint name. */
    private Map<String, Double> sampleRates = new LinkedHashMap<>();

    public double getDefaultSampleRate() {
        return defaultSampleRate;
    }

    public void setDefaultSampleRate(double defaultSampleRate) {
        this.defaultSampleRate = defaultSampleRate;
    }

    public Map<String, Double> getSampleRates() {
        return sampleRates;
    }

    public void setSampleRates(Map<String, Double> sampleRates) {
        this.sampleRates = sampleRates;
    }

    public int getExportQueueCapacity() {
        return exportQueueCapacity;
    }

    public void setExportQueueCapacity(int exportQueueCapacity) {
        this.exportQueueCapacity = exportQueueCapacity;
    }

    public long getOfferTimeoutMillis() {
        return offerTimeoutMillis;
    }

    public void setOfferTimeoutMillis(long offerTimeoutMillis) {
        this.offerTimeoutMillis = offerTimeoutMillis;
    }

    public long getExportTimeoutMillis() {
        return exportTimeoutMillis;
    }

    public void setExportTimeoutMillis(long exportTimeoutMillis) {
        this.exportTimeoutMillis = exportTimeoutMillis;
    }

    public String getRequestIdHeader() {
        return requestIdHeader;
    }

    public void setRequestIdHeader(String requestIdHeader) {
        this.requestIdHeader = requestIdHeader;
    }
}
