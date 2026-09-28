package com.github.h3yow.springbootobservabilityjava.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ObservabilityController.class);

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
     * Drop accounting: every rejected/dropped event, by reason.
     *
     * <p>{@code SAMPLED_OUT} is a deliberate sampling decision (the event
     * never entered the queue) and is reported by the {@link Sampler}; the
     * remaining reasons are export-pipeline faults reported by the queue.
     */
    @GetMapping("/drops")
    public Map<String, Object> drops() {
        Map<String, Object> out = new LinkedHashMap<>();
        exportQueue.dropCounts().forEach((reason, count) -> out.put(reason.name(), count));
        out.put(DropReason.SAMPLED_OUT.name(), sampler.sampledOutCount());
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

    /**
     * Current sampling configuration, effective ratio per endpoint and
     * sampled-in/out decision counters.
     */
    @GetMapping("/sampling")
    public Map<String, Object> sampling() {
        return sampler.snapshot();
    }

    /**
     * Changes the default sampling ratio at runtime (no restart). New requests
     * use it immediately; in-flight requests keep their earlier verdict.
     */
    @PostMapping("/sampling/default")
    public Map<String, Object> setDefaultRatio(@RequestParam double ratio) {
        sampler.updateDefaultRatio(ratio);
        log.info("sampling config updated at runtime: defaultRatio={}", ratio);
        return Map.of("defaultRatio", ratio);
    }

    /**
     * Sets or removes the sampling override for one endpoint at runtime.
     * Omitting {@code ratio} removes the override so the default applies.
     */
    @PostMapping("/sampling/endpoint")
    public Map<String, Object> setEndpointRatio(@RequestParam String endpoint,
                                                @RequestParam(required = false) Double ratio) {
        double effective = ratio == null
                ? sampler.removeEndpointOverride(endpoint)
                : sampler.updateEndpointRatio(endpoint, ratio);
        log.info("sampling config updated at runtime: endpoint={} requested={} effectiveRatio={}",
                endpoint, ratio == null ? "remove-override" : ratio, effective);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("endpoint", endpoint);
        out.put("requested", ratio == null ? "remove-override" : ratio);
        out.put("effectiveRatio", effective);
        return out;
    }
}
