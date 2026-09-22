package com.github.h3yow.springbootobservabilityjava.observability;

/**
 * ThreadLocal holder for the current request context.
 *
 * <p>Rules that keep attribution correct:
 * <ul>
 *   <li>{@link #attach(RequestContext)} refuses to overwrite an existing
 *       context on the same thread (duplicate injection / cross-request
 *       reuse is rejected loudly instead of silently re-attributing).</li>
 *   <li>{@link #detach()} must be called in a finally block by whoever
 *       attached the context, so pooled threads never leak a context into
 *       an unrelated request.</li>
 *   <li>{@link #current()} returns {@code null} when absent; callers must
 *       treat absence as "no attribution possible" rather than guessing.</li>
 * </ul>
 */
public final class RequestContextHolder {

    private static final ThreadLocal<RequestContext> CURRENT = new ThreadLocal<>();

    private RequestContextHolder() {
    }

    /** Returns the context bound to the current thread, or {@code null}. */
    public static RequestContext current() {
        return CURRENT.get();
    }

    /**
     * Binds a context to the current thread.
     *
     * @throws IllegalStateException if a context is already bound, which
     *         signals duplicate injection or cross-request reuse.
     */
    public static void attach(RequestContext context) {
        if (CURRENT.get() != null) {
            throw new IllegalStateException(
                    "RequestContext already bound to this thread: existing="
                            + CURRENT.get().requestId() + ", rejected=" + context.requestId());
        }
        CURRENT.set(context);
    }

    /**
     * Binds a context if none is bound; returns the effective context.
     * Used by propagation code where a branch may legitimately already
     * carry the same context.
     */
    public static RequestContext attachIfAbsent(RequestContext context) {
        RequestContext existing = CURRENT.get();
        if (existing == null) {
            CURRENT.set(context);
            return context;
        }
        return existing;
    }

    /** Removes the context bound to the current thread, if any. */
    public static void detach() {
        CURRENT.remove();
    }
}
