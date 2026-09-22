package com.github.h3yow.springbootobservabilityjava.observability.context;

import java.util.Optional;

/**
 * 基于 ThreadLocal 的上下文持有者。
 * <p>规则：
 * <ul>
 *   <li>同一线程重复 set 且上下文不同视为重复注入，抛出异常，避免错误归属；</li>
 *   <li>请求结束必须 clear，防止线程复用导致跨请求串号；</li>
 *   <li>异步分支通过 {@link ContextPropagator} 显式传播，不依赖隐式继承。</li>
 * </ul>
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> HOLDER = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    public static Optional<RequestContext> current() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static String currentRequestIdOr(String fallback) {
        RequestContext ctx = HOLDER.get();
        return ctx == null ? fallback : ctx.getRequestId();
    }

    /**
     * 绑定上下文。若当前线程已绑定不同请求，视为重复注入并拒绝。
     */
    public static void set(RequestContext context) {
        RequestContext existing = HOLDER.get();
        if (existing != null && existing != context
                && !existing.getRequestId().equals(context.getRequestId())) {
            throw new IllegalStateException(
                    "request context already bound: existing=" + existing.getRequestId()
                            + ", rejected=" + context.getRequestId());
        }
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }
}
