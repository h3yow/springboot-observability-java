package com.github.h3yow.springbootobservabilityjava.observability.export;

/**
 * 事件导出器：非阻塞提交，拒绝时返回可区分原因。
 */
public interface EventExporter {

    /**
     * 尝试提交事件，返回 null 表示接受，否则返回拒绝原因。
     */
    DropReason submit(RequestEvent event);
}
