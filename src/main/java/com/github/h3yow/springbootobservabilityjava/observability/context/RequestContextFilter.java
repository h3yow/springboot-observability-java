package com.github.h3yow.springbootobservabilityjava.observability.context;

import com.github.h3yow.springbootobservabilityjava.observability.export.BoundedEventExporter;
import com.github.h3yow.springbootobservabilityjava.observability.export.DropReason;
import com.github.h3yow.springbootobservabilityjava.observability.export.RequestEvent;
import com.github.h3yow.springbootobservabilityjava.observability.metrics.MetricsRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求入口过滤器：
 * <ul>
 *   <li>每个请求创建唯一上下文；外部传入的 X-Request-Id 合法则采纳，否则重新生成；</li>
 *   <li>绑定前校验当前线程无残留上下文，防止跨请求复用造成错误归属；</li>
 *   <li>请求结束时按接口维度记录指标并提交采集事件，最后强制清理上下文；</li>
 *   <li>/observability/** 为自身读取端点，不参与采集，避免自指噪声。</li>
 * </ul>
 */
public class RequestContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String SELF_PREFIX = "/observability";
    private static final int MAX_REQUEST_ID_LENGTH = 128;

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);

    private final MetricsRegistry metricsRegistry;
    private final BoundedEventExporter eventExporter;

    public RequestContextFilter(MetricsRegistry metricsRegistry, BoundedEventExporter eventExporter) {
        this.metricsRegistry = metricsRegistry;
        this.eventExporter = eventExporter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(SELF_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (RequestContextHolder.current().isPresent()) {
            // 线程上残留其它请求的上下文：拒绝复用，先清理再建新，杜绝错误归属
            log.warn("stale context detected on thread, requestId={}, clearing before bind",
                    RequestContextHolder.currentRequestIdOr("none"));
            RequestContextHolder.clear();
        }
        String requestId = resolveRequestId(request);
        RequestContext context = new RequestContext(requestId, System.nanoTime());
        RequestContextHolder.set(context);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        String endpoint = request.getMethod() + " " + request.getRequestURI();
        boolean success = false;
        try {
            filterChain.doFilter(request, response);
            success = response.getStatus() < 500;
        } catch (ServletException | IOException | RuntimeException e) {
            success = false;
            throw e;
        } finally {
            long latencyNanos = System.nanoTime() - context.getStartNanos();
            metricsRegistry.record(endpoint, success, latencyNanos);
            DropReason drop = eventExporter.submit(new RequestEvent(requestId, endpoint, success, latencyNanos));
            log.info("request completed: requestId={}, endpoint={}, success={}, latencyMs={}, exportDropReason={}",
                    requestId, endpoint, success, latencyNanos / 1_000_000, drop == null ? "ACCEPTED" : drop);
            RequestContextHolder.clear();
        }
    }

    private String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        if (incoming != null && !incoming.isBlank() && incoming.length() <= MAX_REQUEST_ID_LENGTH) {
            return incoming.trim();
        }
        return UUID.randomUUID().toString();
    }
}
