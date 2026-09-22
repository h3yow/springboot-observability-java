package com.github.h3yow.springbootobservabilityjava.observability.config;

import com.github.h3yow.springbootobservabilityjava.observability.context.ContextPropagator;
import com.github.h3yow.springbootobservabilityjava.observability.context.RequestContextFilter;
import com.github.h3yow.springbootobservabilityjava.observability.export.BoundedEventExporter;
import com.github.h3yow.springbootobservabilityjava.observability.metrics.MetricsRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 装配可观测性组件与带上下文传播的异步执行器。
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfig {

    @Bean
    public MetricsRegistry metricsRegistry() {
        return new MetricsRegistry();
    }

    @Bean
    public BoundedEventExporter boundedEventExporter(ObservabilityProperties properties) {
        return new BoundedEventExporter(properties);
    }

    @Bean
    public FilterRegistrationBean<RequestContextFilter> observabilityRequestContextFilter(
            MetricsRegistry metricsRegistry, BoundedEventExporter eventExporter) {
        FilterRegistrationBean<RequestContextFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RequestContextFilter(metricsRegistry, eventExporter));
        registration.setOrder(Integer.MIN_VALUE); // 最优先，确保上下文最先建立
        registration.addUrlPatterns("/*");
        return registration;
    }

    /**
     * 异步任务装饰器：提交时捕获上下文快照，执行时绑定、执行后恢复。
     */
    @Bean
    public TaskDecorator observabilityTaskDecorator() {
        return ContextPropagator::wrap;
    }

    @Bean(name = "observabilityExecutor")
    public Executor observabilityExecutor(TaskDecorator observabilityTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("obs-async-");
        executor.setTaskDecorator(observabilityTaskDecorator);
        executor.initialize();
        return executor;
    }
}
