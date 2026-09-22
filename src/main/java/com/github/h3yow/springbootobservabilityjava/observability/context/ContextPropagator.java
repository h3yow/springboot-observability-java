package com.github.h3yow.springbootobservabilityjava.observability.context;

import java.util.concurrent.Callable;

/**
 * 上下文传播器：在提交异步任务时捕获当前线程的上下文快照，
 * 在执行线程上绑定快照、执行后恢复执行线程原有状态。
 * <p>保证：异步/并发分支归属同一请求；执行线程不被污染，线程池复用不串号。
 */
public final class ContextPropagator {

    private ContextPropagator() {
    }

    public static Runnable wrap(Runnable task) {
        RequestContext snapshot = RequestContextHolder.current().orElse(null);
        return () -> {
            RequestContext previous = RequestContextHolder.current().orElse(null);
            if (snapshot != null) {
                RequestContextHolder.set(snapshot);
            }
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    public static <T> Callable<T> wrap(Callable<T> task) {
        RequestContext snapshot = RequestContextHolder.current().orElse(null);
        return () -> {
            RequestContext previous = RequestContextHolder.current().orElse(null);
            if (snapshot != null) {
                RequestContextHolder.set(snapshot);
            }
            try {
                return task.call();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(RequestContext previous) {
        RequestContextHolder.clear();
        if (previous != null) {
            RequestContextHolder.set(previous);
        }
    }
}
