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

    /** Head-sampling configuration. */
    private Sampling sampling = new Sampling();

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

    public Sampling getSampling() {
        return sampling;
    }

    public void setSampling(Sampling sampling) {
        this.sampling = sampling;
    }

    /** Per-endpoint head-sampling configuration. */
    public static class Sampling {

        /** Ratio applied to endpoints without an explicit override; 1.0 = export everything. */
        private double defaultRatio = 1.0;

        /** Endpoint name ({@code METHOD /pattern}) to ratio override in [0,1]. */
        private Map<String, Double> endpoints = new LinkedHashMap<>();

        public double getDefaultRatio() {
            return defaultRatio;
        }

        public void setDefaultRatio(double defaultRatio) {
            this.defaultRatio = defaultRatio;
        }

        public Map<String, Double> getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(Map<String, Double> endpoints) {
            this.endpoints = endpoints;
        }
    }
}
