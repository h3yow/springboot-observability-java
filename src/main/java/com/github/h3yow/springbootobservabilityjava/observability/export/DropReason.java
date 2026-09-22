package com.github.h3yow.springbootobservabilityjava.observability.export;

/**
 * 事件被拒绝或被丢弃的可区分原因。
 */
public enum DropReason {
    /** 采样未命中，按策略丢弃 */
    SAMPLED_OUT,
    /** 缓冲队列已满，拒绝入队 */
    QUEUE_FULL,
    /** 导出器不可用（降级开关打开） */
    EXPORTER_UNAVAILABLE,
    /** 导出超时 */
    EXPORT_TIMEOUT,
    /** 导出过程抛异常 */
    EXPORT_FAILURE
}
