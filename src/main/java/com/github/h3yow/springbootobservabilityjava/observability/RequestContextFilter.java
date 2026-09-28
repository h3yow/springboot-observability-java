package com.github.h3yow.springbootobservabilityjava.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Entry point of the request-level observability pipeline.
 *
 * <p>For each inbound request this filter:
 * <ol>
 *   <li>creates exactly one {@link RequestContext} (honoring an inbound
 *       request-id header, generating one otherwise) and binds it to the
 *       request thread;</li>
 *   <li>echoes the request id on the response so callers can correlate;</li>
 *   <li>on completion, records endpoint metrics for <em>every</em> request and,
 *       if the per-request {@link Sampler} decision keeps it, submits one
 *       export event — always in a {@code finally} block, so the context is
 *       detached and the request thread is never leaked into the pool.
 *       Suppressed events are logged with the decision basis as
 *       {@code NOT_SAMPLED}, distinct from queue/exporter drops.</li>
 * </ol>
 *
 * <p>Duplicate injection is rejected: if a context is already bound to the
 * request thread, the filter fails fast instead of re-attributing the
 * request.
 */
public class RequestContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestContextFilter.class);

    private final MetricsRegistry metricsRegistry;
    private final MetricsExportQueue exportQueue;
    private final Sampler sampler;
    private final ObservabilityProperties properties;

    public RequestContextFilter(MetricsRegistry metricsRegistry,
                                MetricsExportQueue exportQueue,
                                Sampler sampler,
                                ObservabilityProperties properties) {
        this.metricsRegistry = metricsRegistry;
        this.exportQueue = exportQueue;
        this.sampler = sampler;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (RequestContextHolder.current() != null) {
            // Duplicate injection / cross-request reuse must not re-attribute.
            throw new ServletException("RequestContext already bound for requestId="
                    + RequestContextHolder.current().requestId());
        }

        String endpoint = endpointName(request);
        RequestContext context = RequestContext.create(
                request.getHeader(properties.getRequestIdHeader()), endpoint);
        RequestContextHolder.attach(context);
        response.setHeader(properties.getRequestIdHeader(), context.requestId());

        boolean success = false;
        try {
            filterChain.doFilter(request, response);
            success = response.getStatus() < 500;
        } finally {
            long latencyNanos = context.elapsedNanos();
            // Sampling affects export only: success/failure counts and latency
            // stats always cover every completed request.
            metricsRegistry.record(endpoint, success, latencyNanos);

            SamplingDecision decision = sampler.decide(context);
            if (!decision.sampled()) {
                log.warn("metrics event not sampled: requestId={} {}", context.requestId(), decision.basis());
            } else {
                DropReason drop = exportQueue.submit(
                        new MetricsEvent(context.requestId(), endpoint, success, latencyNanos));
                if (drop != null) {
                    log.warn("metrics event dropped: requestId={} endpoint={} reason={}",
                            context.requestId(), endpoint, drop);
                }
            }
            RequestContextHolder.detach();
        }
    }

    /** Stable metric name: {@code METHOD /pattern}, never the raw URI with ids. */
    private String endpointName(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
