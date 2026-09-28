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
 *   <li>on completion, records endpoint metrics and submits one export
 *       event — always in a {@code finally} block, so the context is
 *       detached and the request thread is never leaked into the pool.</li>
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

        // Decide at request entry against the ratios in force right now; the
        // verdict is memoized on the context, so a later runtime ratio change
        // can neither re-attribute nor re-decide this in-flight request.
        SamplingDecision decision = sampler.decide(context);
        log.info("sampling decision: requestId={} endpoint={} sampled={} ratio={} basis={}",
                context.requestId(), endpoint, decision.sampled(), decision.ratio(),
                decision.basis());

        boolean success = false;
        try {
            filterChain.doFilter(request, response);
            success = response.getStatus() < 500;
        } finally {
            long latencyNanos = context.elapsedNanos();
            // Metrics always reflect every completed request; sampling only
            // gates export, never success/failure or latency accounting.
            metricsRegistry.record(endpoint, success, latencyNanos);
            if (decision.sampled()) {
                DropReason drop = exportQueue.submit(
                        new MetricsEvent(context.requestId(), endpoint, success, latencyNanos));
                if (drop != null) {
                    log.warn("metrics event dropped: requestId={} endpoint={} reason={}",
                            context.requestId(), endpoint, drop);
                }
            } else {
                log.info("metrics event not exported: requestId={} endpoint={} reason={} basis={}",
                        context.requestId(), endpoint, DropReason.SAMPLED_OUT, decision.basis());
            }
            RequestContextHolder.detach();
        }
    }

    /** Stable metric name: {@code METHOD /pattern}, never the raw URI with ids. */
    private String endpointName(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
