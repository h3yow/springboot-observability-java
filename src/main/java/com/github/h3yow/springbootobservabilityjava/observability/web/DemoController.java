package com.github.h3yow.springbootobservabilityjava.observability.web;

import com.github.h3yow.springbootobservabilityjava.observability.context.RequestContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 演示接口：同步、失败与异步并发三种路径，用于验证上下文归属与指标采集。
 */
@RestController
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final Executor observabilityExecutor;

    public DemoController(@Qualifier("observabilityExecutor") Executor observabilityExecutor) {
        this.observabilityExecutor = observabilityExecutor;
    }

    @GetMapping("/demo/ok")
    public Map<String, String> ok() {
        String requestId = RequestContextHolder.currentRequestIdOr("none");
        log.info("demo ok: requestId={}", requestId);
        return Map.of("requestId", requestId, "result", "ok");
    }

    @GetMapping("/demo/fail")
    public Map<String, String> fail() {
        String requestId = RequestContextHolder.currentRequestIdOr("none");
        log.info("demo fail: requestId={}", requestId);
        throw new IllegalStateException("simulated failure, requestId=" + requestId);
    }

    /**
     * 异步并发：两个分支在独立线程执行，必须归属同一请求且互不串号。
     */
    @GetMapping("/demo/async")
    public CompletableFuture<Map<String, String>> async() {
        String requestId = RequestContextHolder.currentRequestIdOr("none");
        log.info("demo async start: requestId={}", requestId);
        CompletableFuture<String> branchA = CompletableFuture.supplyAsync(
                () -> branch("A"), observabilityExecutor);
        CompletableFuture<String> branchB = CompletableFuture.supplyAsync(
                () -> branch("B"), observabilityExecutor);
        return branchA.thenCombine(branchB,
                (a, b) -> Map.of("requestId", requestId, "branchA", a, "branchB", b));
    }

    private String branch(String name) {
        String branchRequestId = RequestContextHolder.currentRequestIdOr("none");
        log.info("async branch {}: requestId={}, thread={}", name, branchRequestId, Thread.currentThread().getName());
        return name + ":" + branchRequestId;
    }
}
