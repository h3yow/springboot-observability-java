package com.github.h3yow.springbootobservabilityjava.observability;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Propagates the request context across threads.
 *
 * <p>The context is captured on the submitting thread and re-attached on the
 * worker thread for the duration of the task, then always detached so pooled
 * threads never leak attribution into unrelated work.
 *
 * <p>Safety rules:
 * <ul>
 *   <li>If the worker thread already carries the <em>same</em> context
 *       (re-entrant dispatch), the task simply runs under it.</li>
 *   <li>If the worker thread carries a <em>different</em> context, that is
 *       cross-request reuse: the task fails loudly instead of mis-attributing.</li>
 *   <li>If no context exists on the submitting thread, the task runs
 *       unattributed; nothing is invented.</li>
 * </ul>
 */
public final class ContextPropagator {

    private ContextPropagator() {
    }

    /** Wraps a {@link Runnable} so it runs under the submitting thread's context. */
    public static Runnable wrap(Runnable task) {
        RequestContext captured = RequestContextHolder.current();
        return () -> runWith(captured, task);
    }

    /** Wraps a {@link Callable} so it runs under the submitting thread's context. */
    public static <T> Callable<T> wrap(Callable<T> task) {
        RequestContext captured = RequestContextHolder.current();
        return () -> callWith(captured, task);
    }

    /** Returns an {@link Executor} that propagates the context of each submitter. */
    public static Executor wrap(Executor executor) {
        return command -> executor.execute(wrap(command));
    }

    /** Returns an {@link ExecutorService} view that propagates the context of each submitter. */
    public static ExecutorService wrap(ExecutorService executor) {
        return new ExecutorService() {
            @Override
            public void execute(Runnable command) {
                executor.execute(wrap(command));
            }

            @Override
            public <T> Future<T> submit(Callable<T> task) {
                return executor.submit(wrap(task));
            }

            @Override
            public <T> Future<T> submit(Runnable task, T result) {
                return executor.submit(wrap(task), result);
            }

            @Override
            public Future<?> submit(Runnable task) {
                return executor.submit(wrap(task));
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
                    throws InterruptedException {
                return executor.invokeAll(wrapAll(tasks));
            }

            @Override
            public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                                 long timeout, TimeUnit unit) throws InterruptedException {
                return executor.invokeAll(wrapAll(tasks), timeout, unit);
            }

            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
                    throws InterruptedException, ExecutionException {
                return executor.invokeAny(wrapAll(tasks));
            }

            @Override
            public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
                    throws InterruptedException, ExecutionException, TimeoutException {
                return executor.invokeAny(wrapAll(tasks), timeout, unit);
            }

            private <T> Collection<? extends Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks) {
                return tasks.stream().map(ContextPropagator::wrap).toList();
            }

            @Override
            public void shutdown() {
                executor.shutdown();
            }

            @Override
            public List<Runnable> shutdownNow() {
                return executor.shutdownNow();
            }

            @Override
            public boolean isShutdown() {
                return executor.isShutdown();
            }

            @Override
            public boolean isTerminated() {
                return executor.isTerminated();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                return executor.awaitTermination(timeout, unit);
            }
        };
    }

    private static void runWith(RequestContext captured, Runnable task) {
        boolean attached = enter(captured);
        try {
            task.run();
        } finally {
            exit(attached);
        }
    }

    private static <T> T callWith(RequestContext captured, Callable<T> task) throws Exception {
        boolean attached = enter(captured);
        try {
            return task.call();
        } finally {
            exit(attached);
        }
    }

    /** @return true if this invocation attached the context (and must detach it). */
    private static boolean enter(RequestContext captured) {
        if (captured == null) {
            return false;
        }
        RequestContext existing = RequestContextHolder.current();
        if (existing == null) {
            RequestContextHolder.attach(captured);
            return true;
        }
        if (existing.requestId().equals(captured.requestId())) {
            // Same request re-entering: run under the existing binding.
            return false;
        }
        throw new IllegalStateException(
                "Cross-request context reuse detected: thread already bound to requestId="
                        + existing.requestId() + ", refusing to run task of requestId="
                        + captured.requestId());
    }

    private static void exit(boolean attached) {
        if (attached) {
            RequestContextHolder.detach();
        }
    }
}
