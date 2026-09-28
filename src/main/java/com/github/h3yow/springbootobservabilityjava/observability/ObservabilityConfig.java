package com.github.h3yow.springbootobservabilityjava.observability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Wires the observability pipeline beans.
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfig {

    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    @Bean
    public LoggingMetricsExporter loggingMetricsExporter() {
        return new LoggingMetricsExporter();
    }

    @Bean
    public Sampler sampler(ObservabilityProperties properties) {
        return new Sampler(properties.getDefaultSampleRate(), properties.getSampleRates());
    }

    @Bean(destroyMethod = "close")
    public MetricsExportQueue metricsExportQueue(ObservabilityProperties properties,
                                                 LoggingMetricsExporter exporter) {
        MetricsExportQueue queue = new MetricsExportQueue(
                properties.getExportQueueCapacity(),
                properties.getOfferTimeoutMillis(),
                properties.getExportTimeoutMillis(),
                exporter);
        queue.start();
        return queue;
    }

    @Bean
    public FilterRegistrationBean<RequestContextFilter> requestObservabilityFilter(
            MetricsRegistry metricsRegistry, MetricsExportQueue exportQueue,
            Sampler sampler, ObservabilityProperties properties) {
        FilterRegistrationBean<RequestContextFilter> registration = new FilterRegistrationBean<>(
                new RequestContextFilter(metricsRegistry, exportQueue, sampler, properties));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * Executor for async/concurrent branches. Every submitted task is wrapped
     * by {@link ContextPropagator}, so branches always belong to the same
     * request and never to each other's.
     */
    @Bean(name = "observabilityExecutor")
    public Executor observabilityExecutor() {
        return ContextPropagator.wrap(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "observability-worker");
            t.setDaemon(true);
            return t;
        }));
    }
}
