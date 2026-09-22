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

    public ObservabilityController(MetricsRegistry metricsRegistry,
                                   MetricsExportQueue exportQueue,
                                   LoggingMetricsExporter exporter) {
        this.metricsRegistry = metricsRegistry;
        this.exportQueue = exportQueue;
        this.exporter = exporter;
    }

    /** Per-endpoint counters and latency distribution. */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metricsRegistry.snapshot();
    }

    /** Drop accounting: every rejected/dropped event, by reason. */
    @GetMapping("/drops")
    public Map<String, Object> drops() {
        Map<String, Object> out = new LinkedHashMap<>();
        exportQueue.dropCounts().forEach((reason, count) -> out.put(reason.name(), count));
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
}
