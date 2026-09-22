package com.github.h3yow.springbootobservabilityjava.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Concurrency scenarios for request-context attribution: every async branch
 * must belong to its own request, never to another request, and never leak
 * into pooled threads.
 */
class RequestContextPropagationTest {

    private static final Logger log = LoggerFactory.getLogger(RequestContextPropagationTest.class);

    @AfterEach
    void cleanUp() {
        RequestContextHolder.detach();
    }

    @Test
    void duplicateAttachIsRejected() {
        RequestContext first = RequestContext.create("req-dup-1", "GET /a");
        RequestContextHolder.attach(first);
        log.info("attached requestId={} on thread={}", first.requestId(), Thread.currentThread().getName());

        assertThatThrownBy(() -> RequestContextHolder.attach(RequestContext.create("req-dup-2", "GET /b")))
                .isInstanceOf(IllegalStateException.class);
        log.info("verdict: duplicate attach rejected, context stays requestId={}",
                RequestContextHolder.current().requestId());
        assertThat(RequestContextHolder.current().requestId()).isEqualTo("req-dup-1");
    }

    @Test
    void detachClearsThread() {
        RequestContextHolder.attach(RequestContext.create("req-detach-1", "GET /a"));
        RequestContextHolder.detach();
        log.info("verdict: after detach current={}", RequestContextHolder.current());
        assertThat(RequestContextHolder.current()).isNull();
    }

    @Test
    void concurrentBranchesBelongToTheirOwnRequest() throws Exception {
        int requests = 16;
        int branchesPerRequest = 8;
        ExecutorService requestPool = Executors.newFixedThreadPool(requests);
        ExecutorService branchPool = Executors.newFixedThreadPool(8);
        var propagatingBranchPool = ContextPropagator.wrap(branchPool);

        // requestId -> set of requestIds observed by its branches (must be a singleton)
        Map<String, List<String>> observed = new ConcurrentHashMap<>();
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();
        for (int r = 0; r < requests; r++) {
            String requestId = "req-prop-" + r;
            futures.add(requestPool.submit(() -> {
                RequestContextHolder.attach(RequestContext.create(requestId, "GET /demo/fanout"));
                try {
                    startGun.await(); // maximize interleaving
                    List<Future<String>> branchFutures = new ArrayList<>();
                    for (int b = 0; b < branchesPerRequest; b++) {
                        int branch = b;
                        branchFutures.add(propagatingBranchPool.submit(() -> {
                            Thread.sleep(1 + (branch % 3)); // force interleaving
                            RequestContext ctx = RequestContextHolder.current();
                            String seen = ctx == null ? "none" : ctx.requestId();
                            log.info("branch requestId={} branch={} observed={} thread={}",
                                    requestId, branch, seen, Thread.currentThread().getName());
                            return seen;
                        }));
                    }
                    List<String> seen = observed.computeIfAbsent(requestId, k -> new ArrayList<>());
                    for (Future<String> f : branchFutures) {
                        seen.add(f.get());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    RequestContextHolder.detach();
                }
            }));
        }
        startGun.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }

        for (Map.Entry<String, List<String>> entry : observed.entrySet()) {
            String requestId = entry.getKey();
            List<String> seen = entry.getValue();
            long distinct = seen.stream().distinct().count();
            log.info("verdict: requestId={} branches={} distinctObserved={} allMatch={}",
                    requestId, seen.size(), distinct,
                    seen.stream().allMatch(requestId::equals));
            assertThat(seen).hasSize(branchesPerRequest);
            assertThat(seen).allMatch(requestId::equals);
        }
        assertThat(observed).hasSize(requests);
        requestPool.shutdownNow();
        branchPool.shutdownNow();
    }

    @Test
    void pooledThreadsDoNotLeakContextBetweenTasks() throws Exception {
        ExecutorService pool = ContextPropagator.wrap(Executors.newFixedThreadPool(2));

        RequestContextHolder.attach(RequestContext.create("req-leak-1", "GET /a"));
        Future<String> first = pool.submit(() -> {
            RequestContext ctx = RequestContextHolder.current();
            return ctx == null ? "none" : ctx.requestId();
        });
        String firstSeen = first.get(5, TimeUnit.SECONDS);
        RequestContextHolder.detach();

        // Submit a task WITHOUT any context on the submitting thread: it must
        // see no context, never the previous request's leftover.
        Future<String> second = pool.submit(() -> {
            RequestContext ctx = RequestContextHolder.current();
            return ctx == null ? "none" : ctx.requestId();
        });
        String secondSeen = second.get(5, TimeUnit.SECONDS);
        log.info("verdict: withContext observed={} withoutContext observed={}", firstSeen, secondSeen);

        assertThat(firstSeen).isEqualTo("req-leak-1");
        assertThat(secondSeen).isEqualTo("none");
        pool.shutdownNow();
    }

    @Test
    void submitWithoutContextRunsUnattributed() throws Exception {
        ExecutorService pool = ContextPropagator.wrap(Executors.newSingleThreadExecutor());
        Future<String> result = pool.submit(() -> {
            RequestContext ctx = RequestContextHolder.current();
            return ctx == null ? "none" : ctx.requestId();
        });
        String seen = result.get(5, TimeUnit.SECONDS);
        log.info("verdict: no-context submit observed={}", seen);
        assertThat(seen).isEqualTo("none");
        pool.shutdownNow();
    }
}
