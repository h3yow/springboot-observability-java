package com.github.h3yow.springbootobservabilityjava.observability.export;

import com.github.h3yow.springbootobservabilityjava.observability.config.ObservabilityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 有界缓冲 + 后台导出的降级实现：
 * <ul>
 *   <li>submit 永不阻塞业务线程：只做采样判断与非阻塞 offer；</li>
 *   <li>队列容量可配置，满了立即拒绝并记 QUEUE_FULL，不无界增长；</li>
 *   <li>降级开关打开时直接拒绝并记 EXPORTER_UNAVAILABLE；</li>
 *   <li>采样未命中记 SAMPLED_OUT；导出超时记 EXPORT_TIMEOUT；导出异常记 EXPORT_FAILURE；</li>
 *   <li>每种拒绝/丢弃都有独立计数，可经本地接口读取，绝不静默丢弃。</li>
 * </ul>
 */
public class BoundedEventExporter implements EventExporter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BoundedEventExporter.class);

    private final ObservabilityProperties properties;
    private final ArrayBlockingQueue<RequestEvent> queue;
    private final Map<DropReason, AtomicLong> dropCounts = new ConcurrentHashMap<>();
    private final AtomicLong exported = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final ExecutorService sinkExecutor;
    private final Thread worker;
    private volatile boolean running = true;

    /** 导出目标，默认仅计数；测试可替换为慢速/失败 sink 以复现降级。 */
    private volatile Consumer<RequestEvent> sink = event -> {
    };

    public BoundedEventExporter(ObservabilityProperties properties) {
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.getQueueCapacity());
        this.sinkExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "obs-export-sink");
            t.setDaemon(true);
            return t;
        });
        this.worker = new Thread(this::drainLoop, "obs-export-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    public void setSink(Consumer<RequestEvent> sink) {
        this.sink = sink;
    }

    /**
     * 非阻塞提交。返回 null 表示已接受（入队成功），否则返回可区分的拒绝原因。
     */
    @Override
    public DropReason submit(RequestEvent event) {
        if (properties.isExporterUnavailable()) {
            return record(DropReason.EXPORTER_UNAVAILABLE);
        }
        if (ThreadLocalRandom.current().nextDouble() >= properties.getSampleRate()) {
            return record(DropReason.SAMPLED_OUT);
        }
        if (!queue.offer(event)) {
            return record(DropReason.QUEUE_FULL);
        }
        return null;
    }

    private DropReason record(DropReason reason) {
        dropCounts.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
        return reason;
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            RequestEvent event;
            try {
                event = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == null) {
                continue;
            }
            inFlight.incrementAndGet();
            try {
                Future<?> future = sinkExecutor.submit(() -> sink.accept(event));
                future.get(properties.getExportTimeoutMillis(), TimeUnit.MILLISECONDS);
                exported.incrementAndGet();
            } catch (TimeoutException e) {
                record(DropReason.EXPORT_TIMEOUT);
                log.warn("export timeout: requestId={}, reason={}", event.requestId(), DropReason.EXPORT_TIMEOUT);
            } catch (Exception e) {
                record(DropReason.EXPORT_FAILURE);
                log.warn("export failure: requestId={}, reason={}, error={}",
                        event.requestId(), DropReason.EXPORT_FAILURE, e.getMessage());
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    public Map<DropReason, Long> dropCounts() {
        Map<DropReason, Long> result = new EnumMap<>(DropReason.class);
        for (DropReason reason : DropReason.values()) {
            AtomicLong counter = dropCounts.get(reason);
            result.put(reason, counter == null ? 0L : counter.get());
        }
        return result;
    }

    public long exportedCount() {
        return exported.get();
    }

    public int backlog() {
        return queue.size() + inFlight.get();
    }

    /** 等待队列清空且在途导出完成，供测试做确定性断言。 */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (queue.isEmpty() && inFlight.get() == 0) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    @Override
    public void close() {
        running = false;
        worker.interrupt();
        sinkExecutor.shutdownNow();
    }
}
