package com.github.h3yow.springbootobservabilityjava.observability.export;

/**
 * 一次请求完成后的采集事件。
 */
public record RequestEvent(String requestId, String endpoint, boolean success, long latencyNanos) {
}
