package com.github.h3yow.springbootobservabilityjava.observability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
/**
 * Local read API for the observability pipeline: per-endpoint metrics,
 * export-drop accounting and pipeline state. Also allows flipping exporter
 * availability so degradation behavior can be verified locally.
 */
@RestController
@RequestMapping("/observability")
public class ObservabilityController {

    private final MetricsRegistry metricsRegistry;
    private final MetricsExportQueue exportQueue;
    private final LoggingMetricsExporter exporter;
    private final Sampler sampler;

    public ObservabilityController(MetricsRegistry metricsRegistry,
                                   MetricsExportQueue exportQueue,
                                   LoggingMetricsExporter exporter,
                                   Sampler sampler) {
        this.metricsRegistry = metricsRegistry;
        this.exportQueue = exportQueue;
        this.exporter = exporter;
        this.sampler = sampler;
    }

    /** Per-endpoint counters and latency distribution. */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metricsRegistry.snapshot();
    }

    /**
     * Drop accounting: every rejected/dropped event, by reason. Events held
     * back by the sampling policy are reported separately as
     * {@code NOT_SAMPLED}, so policy-driven suppression never blends into
     * queue/exporter degradation.
     */
    @GetMapping("/drops")
    public Map<String, Object> drops() {
        Map<String, Object> out = new LinkedHashMap<>();
        exportQueue.dropCounts().forEach((reason, count) -> out.put(reason.name(), count));
        out.put(DropReason.NOT_SAMPLED.name(), sampler.notSampledCount());
        return out;
    }

    /** Pipeline state: capacity, backlog, accepted/exported totals. */
    @GetMapping("/pipeline")
    public Map<String, Object> pipeline() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capacity", exportQueue.capacity());
        out.put("pending", exportQueue.pendingCount());
        out.put("accepted", exportQueue.acceptedCount());
        out.put("exported", exportQueue.exportedCount());
        out.put("exporterAvailable", exporter.isAvailable());
        return out;
    }

    /** Toggle exporter availability to exercise degradation locally. */
    @PostMapping("/exporter/availability")
    public Map<String, Object> setExporterAvailability(@RequestParam boolean available) {
        exporter.setAvailable(available);
        return Map.of("exporterAvailable", available);
    }

    /** Active sampling policy: default rate plus per-endpoint overrides. */
    @GetMapping("/sampling/config")
    public Map<String, Object> samplingConfig() {
        return sampler.configSnapshot();
    }

    /**
     * Per-endpoint decision statistics: considered/kept/suppressed counts and
     * the rate (with source) currently in effect per endpoint.
     */
    @GetMapping("/sampling/stats")
    public Map<String, Object> samplingStats() {
        return sampler.statsSnapshot();
    }

    /**
     * Changes the default sampling rate at runtime. Applies to new requests
     * immediately; requests already in flight keep their original decision.
     */
    @PostMapping("/sampling/default-rate")
    public Map<String, Object> setDefaultSampleRate(@RequestParam double rate) {
        sampler.setDefaultRate(rate);
        return sampler.configSnapshot();
    }

    /**
     * Changes (or removes) one endpoint's sampling rate at runtime.
     * Pass {@code rate} in [0,1]; omit it (or send an empty value with
     * {@code remove=true}) to fall back to the default rate.
     */
    @PostMapping("/sampling/endpoint-rate")
    public Map<String, Object> setEndpointSampleRate(@RequestParam String endpoint,
                                                     @RequestParam(required = false) Double rate,
                                                     @RequestParam(defaultValue = "false") boolean remove) {
        if (remove || rate == null) {
            sampler.removeEndpointRate(endpoint);
        } else {
            sampler.setEndpointRate(endpoint, rate);
        }
        return sampler.configSnapshot();
    }
}
