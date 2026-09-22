package com.github.h3yow.springbootobservabilityjava.observability.metrics;

import com.github.h3yow.springbootobservabilityjava.observability.export.BoundedEventExporter;
import com.github.h3yow.springbootobservabilityjava.observability.export.DropReason;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地读取接口：通过服务自身能力暴露指标与降级计数。
 * GET /observability/metrics
 */
@RestController
public class MetricsController {

    private static final String[] BUCKET_LABELS = {"lt10ms", "lt50ms", "lt100ms", "lt500ms", "lt1000ms", "gte1000ms"};

    private final MetricsRegistry metricsRegistry;
    private final BoundedEventExporter eventExporter;

    public MetricsController(MetricsRegistry metricsRegistry, BoundedEventExporter eventExporter) {
        this.metricsRegistry = metricsRegistry;
        this.eventExporter = eventExporter;
    }

    @GetMapping("/observability/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> endpoints = new LinkedHashMap<>();
        metricsRegistry.snapshot().forEach((endpoint, m) -> {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("total", m.getTotal());
            view.put("success", m.getSuccess());
            view.put("failure", m.getFailure());
            long[] buckets = m.getLatencyBuckets();
            Map<String, Long> bucketView = new LinkedHashMap<>();
            for (int i = 0; i < buckets.length; i++) {
                bucketView.put(BUCKET_LABELS[i], buckets[i]);
            }
            view.put("latencyBuckets", bucketView);
            endpoints.put(endpoint, view);
        });

        Map<String, Object> exporter = new LinkedHashMap<>();
        exporter.put("exported", eventExporter.exportedCount());
        exporter.put("backlog", eventExporter.backlog());
        Map<String, Long> drops = new LinkedHashMap<>();
        eventExporter.dropCounts().forEach((DropReason reason, Long count) -> drops.put(reason.name(), count));
        exporter.put("drops", drops);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("endpoints", endpoints);
        result.put("exporter", exporter);
        return result;
    }
}
