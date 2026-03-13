package com.janeluo.luban.rds.common.context;

import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 支持 TraceId 传递的 Callable 包装器
 * 
 * <p>用于在异步执行时自动传递 TraceId 到子线程
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class TraceableCallable<V> implements Callable<V> {

    private final Callable<V> delegate;
    private final String traceId;
    private final Map<String, String> contextMap;

    public TraceableCallable(Callable<V> delegate, String traceId) {
        this.delegate = delegate;
        this.traceId = traceId;
        this.contextMap = MDC.getCopyOfContextMap();
    }

    @Override
    public V call() throws Exception {
        try {
            if (contextMap != null) {
                MDC.setContextMap(contextMap);
            } else if (traceId != null) {
                TraceContext.setTraceId(traceId);
            }
            return delegate.call();
        } finally {
            MDC.clear();
        }
    }

    /**
     * 包装 Callable
     *
     * @param callable 原始 Callable
     * @return 包装后的 Callable
     */
    public static <V> TraceableCallable<V> wrap(Callable<V> callable) {
        return new TraceableCallable<>(callable, TraceContext.getTraceId());
    }

    /**
     * 使用指定 TraceId 包装 Callable
     *
     * @param callable 原始 Callable
     * @param traceId  TraceId
     * @return 包装后的 Callable
     */
    public static <V> TraceableCallable<V> wrap(Callable<V> callable, String traceId) {
        return new TraceableCallable<>(callable, traceId);
    }
}