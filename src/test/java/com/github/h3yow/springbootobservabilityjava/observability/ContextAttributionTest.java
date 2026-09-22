package com.github.h3yow.springbootobservabilityjava.observability;

import com.github.h3yow.springbootobservabilityjava.observability.context.ContextPropagator;
import com.github.h3yow.springbootobservabilityjava.observability.context.RequestContext;
import com.github.h3yow.springbootobservabilityjava.observability.context.RequestContextFilter;
import com.github.h3yow.springbootobservabilityjava.observability.context.RequestContextHolder;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文归属验证：并发请求互不串号、异步分支归属同一请求、重复注入被拒绝。
 * 判定依据均打印请求标识。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ContextAttributionTest {

    private static final Logger log = LoggerFactory.getLogger(ContextAttributionTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void concurrentRequestsNeverMixContexts() throws Exception {
        int requestCount = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < requestCount; i++) {
            String requestId = "req-concurrent-" + i;
            tasks.add(() -> {
                HttpHeaders headers = new HttpHeaders();
                headers.set(RequestContextFilter.REQUEST_ID_HEADER, requestId);
                ResponseEntity<Map> response = restTemplate.exchange(
                        "/demo/async", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
                Map body = response.getBody();
                String branchA = String.valueOf(body.get("branchA"));
                String branchB = String.valueOf(body.get("branchB"));
                String echoed = response.getHeaders().getFirst(RequestContextFilter.REQUEST_ID_HEADER);
                // 判定依据：响应头、异步分支A、异步分支B 三处的请求标识都必须等于本请求标识
                boolean attributed = requestId.equals(echoed)
                        && branchA.endsWith(requestId)
                        && branchB.endsWith(requestId);
                log.info("attribution check: requestId={}, echoed={}, branchA={}, branchB={}, pass={}",
                        requestId, echoed, branchA, branchB, attributed);
                return requestId + "=" + attributed;
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        for (Future<String> future : futures) {
            assertThat(future.get()).endsWith("=true");
        }
        pool.shutdown();
    }

    @Test
    void duplicateInjectionWithDifferentIdIsRejected() {
        RequestContextHolder.clear();
        RequestContextHolder.set(new RequestContext("req-original", System.nanoTime()));
        // 判定依据：同一线程绑定不同请求标识时必须抛出异常，拒绝错误归属
        assertThatThrownBy(() -> RequestContextHolder.set(new RequestContext("req-intruder", System.nanoTime())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("req-original")
                .hasMessageContaining("req-intruder");
        log.info("duplicate injection rejected: existing=req-original, rejected=req-intruder, pass=true");
        RequestContextHolder.clear();
    }

    @Test
    void propagatorRestoresWorkerThreadAndDoesNotLeak() throws Exception {
        RequestContextHolder.clear();
        RequestContextHolder.set(new RequestContext("req-parent", System.nanoTime()));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            String inBranch = pool.submit(ContextPropagator.wrap(
                    (Callable<String>) () -> RequestContextHolder.currentRequestIdOr("none"))).get();
            // 判定依据：分支内读到父请求标识
            assertThat(inBranch).isEqualTo("req-parent");
            // 判定依据：分支执行完毕后，池线程上不得残留上下文（防止线程复用串号）
            String afterBranch = pool.submit(
                    () -> RequestContextHolder.currentRequestIdOr("none")).get();
            assertThat(afterBranch).isEqualTo("none");
            log.info("propagation check: requestId=req-parent, inBranch={}, afterBranch={}, pass=true",
                    inBranch, afterBranch);
        } finally {
            pool.shutdown();
            RequestContextHolder.clear();
        }
    }
}
