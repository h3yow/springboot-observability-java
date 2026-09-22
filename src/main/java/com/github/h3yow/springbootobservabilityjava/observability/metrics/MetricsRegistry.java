package com.github.h3yow.springbootobservabilityjava.observability.metrics;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标注册表：按接口维度聚合。
 * ConcurrentHashMap.computeIfAbsent 保证同一接口只创建一个 EndpointMetrics，
 * 并发写入由 LongAdder 保证不丢失不重复；指标名（接口 key）一经创建保持稳定。
 */
public class MetricsRegistry {

    private final ConcurrentHashMap<String, EndpointMetrics> metrics = new ConcurrentHashMap<>();

    public void record(String endpoint, boolean success, long latencyNanos) {
        metrics.computeIfAbsent(endpoint, k -> new EndpointMetrics())
                .record(success, latencyNanos);
    }

    /** 返回按接口名排序的只读快照，保证读取结果稳定可解释。 */
    public Map<String, EndpointMetrics> snapshot() {
        return new TreeMap<>(metrics);
    }
}
