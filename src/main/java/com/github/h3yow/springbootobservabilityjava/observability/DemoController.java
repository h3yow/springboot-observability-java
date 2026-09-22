package com.github.h3yow.springbootobservabilityjava.observability;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * Demo endpoints used to exercise the observability pipeline, including an
 * async/concurrent endpoint whose branches must all belong to the same
 * request.
 */
@RestController
public class DemoController {

    private final Executor observabilityExecutor;

    public DemoController(@Qualifier("observabilityExecutor") Executor executor) {
        this.observabilityExecutor = executor;
    }

    /** Simple success endpoint. */
    @GetMapping("/demo/work")
    public Map<String, Object> work() throws InterruptedException {
        Thread.sleep(5);
        RequestContext ctx = RequestContextHolder.current();
        return Map.of("ok", true, "requestId", ctx == null ? "none" : ctx.requestId());
    }

    /** Always fails with 500, to exercise failure accounting. */
    @GetMapping("/demo/fail")
    public Map<String, Object> fail() {
        throw new IllegalStateException("intentional failure for observability demo");
    }

    /**
     * Fans out to several concurrent branches. Every branch must observe the
     * same request id as the request thread — never another request's id and
     * never "none".
     */
    @GetMapping("/demo/fanout")
    public Map<String, Object> fanout() throws Exception {
        RequestContext parent = RequestContextHolder.current();
        String parentId = parent == null ? "none" : parent.requestId();

        List<Callable<String>> branches = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int branch = i;
            branches.add(() -> {
                Thread.sleep(3 + branch);
                RequestContext ctx = RequestContextHolder.current();
                return ctx == null ? "none" : ctx.requestId();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> branch : branches) {
            futures.add(java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return branch.call();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }, observabilityExecutor));
        }
        List<String> branchIds = new ArrayList<>();
        for (Future<String> f : futures) {
            branchIds.add(f.get());
        }
        return Map.of("parentRequestId", parentId, "branchRequestIds", branchIds);
    }
}
