package com.github.h3yow.springbootobservabilityjava.observability.support;

import com.github.h3yow.springbootobservabilityjava.observability.RequestContext;
import com.github.h3yow.springbootobservabilityjava.observability.RequestContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test-only endpoint that lets a test deterministically position a real HTTP
 * request <em>after the filter has pinned its sampling decision at entry</em>
 * but <em>before request processing completes</em>.
 *
 * <p>Two latches (reset before every test via {@link #reset()}):
 * <ul>
 *   <li>{@code pinnedAtEntry} — the request counts down on this latch from
 *       inside the controller body. By the time the body runs,
 *       {@code RequestContextFilter} has already called
 *       {@code sampler.decide(context)}, so awaiting it guarantees the verdict
 *       was pinned under the policy in force at request entry;</li>
 *   <li>{@code mayFinish} — the body blocks on this latch until the test
 *       flips the rate and explicitly allows the request to complete.</li>
 * </ul>
 *
 * <p>The synchronization is explicit (no {@code Thread.sleep}), so the
 * "in-flight request does not flip when the rate changes mid-flight" tests are
 * deterministic and order-independent.
 */
@RestController
public class InFlightProbeController {

    /** Released once by the request after the filter pinned the verdict at entry. */
    public static volatile CountDownLatch pinnedAtEntry = new CountDownLatch(1);
    /** The request waits on this until the test lets it finish. */
    public static volatile CountDownLatch mayFinish = new CountDownLatch(1);

    /** Fresh latches for the next test. */
    public static void reset() {
        pinnedAtEntry = new CountDownLatch(1);
        mayFinish = new CountDownLatch(1);
    }

    /** Lets the blocked request proceed. */
    public static void allowCompletion() {
        mayFinish.countDown();
    }

    /** Awaits the "verdict pinned at entry" signal, failing the test if it never arrives. */
    public static void awaitPinned() throws InterruptedException {
        if (!pinnedAtEntry.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("in-flight request never pinned its sampling decision at entry");
        }
    }

    @GetMapping("/testsupport/inflight")
    public Map<String, Object> inFlight() throws InterruptedException {
        String requestId = currentView();
        // The filter (which runs before this body) has already pinned the
        // decision, so this signal means "I am in flight with a fixed verdict".
        pinnedAtEntry.countDown();
        mayFinish.await(10, TimeUnit.SECONDS);
        return Map.of("ok", true, "requestId", requestId == null ? "none" : requestId);
    }

    private static String currentView() {
        RequestContext c = RequestContextHolder.current();
        return c == null ? null : c.requestId();
    }
}
