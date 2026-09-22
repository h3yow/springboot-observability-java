package com.github.h3yow.springbootobservabilityjava.observability.context;

/**
 * 请求级执行上下文：携带请求标识与起始时间，归属判定均以此为准。
 */
public final class RequestContext {

    private final String requestId;
    private final long startNanos;

    public RequestContext(String requestId, long startNanos) {
        this.requestId = requestId;
        this.startNanos = startNanos;
    }

    public String getRequestId() {
        return requestId;
    }

    public long getStartNanos() {
        return startNanos;
    }
}
