package com.github.h3yow.springbootobservabilityjava.observability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性配置：采样率、队列容量、导出超时与降级开关。
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    /** 采样率，0.0 ~ 1.0 */
    private double sampleRate = 1.0;

    /** 导出缓冲队列容量 */
    private int queueCapacity = 1024;

    /** 单次导出超时（毫秒） */
    private long exportTimeoutMillis = 500;

    /** 降级开关：true 时导出链路视为不可用 */
    private boolean exporterUnavailable = false;

    public double getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(double sampleRate) {
        this.sampleRate = sampleRate;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public long getExportTimeoutMillis() {
        return exportTimeoutMillis;
    }

    public void setExportTimeoutMillis(long exportTimeoutMillis) {
        this.exportTimeoutMillis = exportTimeoutMillis;
    }

    public boolean isExporterUnavailable() {
        return exporterUnavailable;
    }

    public void setExporterUnavailable(boolean exporterUnavailable) {
        this.exporterUnavailable = exporterUnavailable;
    }
}
