package com.janeluo.luban.rds.common.context;

import org.slf4j.MDC;

import java.util.Map;

/**
 * 支持 TraceId 传递的 Runnable 包装器
 * 
 * <p>用于在异步执行时自动传递 TraceId 到子线程
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class TraceableRunnable implements Runnable {

    private final Runnable delegate;
    private final String traceId;
    private final Map<String, String> contextMap;

    public TraceableRunnable(Runnable delegate, String traceId) {
        this.delegate = delegate;
        this.traceId = traceId;
        this.contextMap = MDC.getCopyOfContextMap();
    }

    @Override
    public void run() {
        try {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            } else if (traceId != null) {
                TraceContext.setTraceId(traceId);
            }
            delegate.run();
        } finally {
            MDC.clear();
        }
    }

    /**
     * 包装 Runnable
     *
     * @param runnable 原始 Runnable
     * @return 包装后的 Runnable
     */
    public static TraceableRunnable wrap(Runnable runnable) {
        return new TraceableRunnable(runnable, TraceContext.getTraceId());
    }

    /**
     * 使用指定 TraceId 包装 Runnable
     *
     * @param runnable 原始 Runnable
     * @param traceId  TraceId
     * @return 包装后的 Runnable
     */
    public static TraceableRunnable wrap(Runnable runnable, String traceId) {
        return new TraceableRunnable(runnable, traceId);
    }
}