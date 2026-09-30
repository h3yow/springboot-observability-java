package com.github.h3yow.springbootobservabilityjava.observability;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.web.servlet.function.RequestPredicates.GET;

/**
 * Test-only barrier used to keep requests deterministically "in flight" while
 * the sampling policy is changed. The barrier carries no stereotype annotation:
 * it exists only as a bean created by {@link GateConfig}, which the relevant
 * test imports. {@code @TestConfiguration} is excluded from component scanning,
 * so without the explicit {@code @Import} nothing is registered — neither in
 * the shipped application nor in unrelated test contexts.
 *
 * <p>Flow: {@link #arm(int)} arms a fresh gate; requests to
 * {@code GET /test/inflight} block until {@link #release()} is called.
 */
final class InFlightGate {

    private InFlightGate() {
    }

    @TestConfiguration
    static class GateConfig {

        @Bean
        GateController gateController() {
            return new GateController();
        }

        /** Functional mapping: keeps the barrier free of any scan stereotype. */
        @Bean
        RouterFunction<ServerResponse> inFlightGateRoute(GateController gate) {
            return RouterFunctions.route(GET("/test/inflight"), request -> gate.blocked());
        }
    }

    public static class GateController {

        private final AtomicReference<CountDownLatch> latch =
                new AtomicReference<>(new CountDownLatch(1));
        private final AtomicInteger arrived = new AtomicInteger();

        /** Arms a closed gate expecting {@code expected} requests to arrive. */
        public synchronized void arm(int expected) {
            arrived.set(0);
            latch.set(new CountDownLatch(1));
            notifyAll();
        }

        /** Opens the gate and lets all waiting requests finish. */
        public synchronized void release() {
            latch.get().countDown();
            notifyAll();
        }

        /** Waits until all expected requests have reached (and are blocked on) the gate. */
        public synchronized void awaitArrivals(int expected) throws InterruptedException {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (arrived.get() < expected && System.nanoTime() < deadline) {
                wait(20);
            }
            if (arrived.get() < expected) {
                throw new AssertionError("only " + arrived.get() + " of " + expected
                        + " requests reached the in-flight gate");
            }
        }

        public ServerResponse blocked() {
            RequestContext context = RequestContextHolder.current();
            arrived.incrementAndGet();
            try {
                latch.get().await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ServerResponse.status(500).build();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requestId", context == null ? null : context.requestId());
            out.put("endpoint", context == null ? null : context.endpoint());
            return ServerResponse.ok().body(out);
        }
    }
}
