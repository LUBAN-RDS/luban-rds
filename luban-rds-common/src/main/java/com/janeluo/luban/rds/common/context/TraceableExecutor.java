package com.janeluo.luban.rds.common.context;

import java.util.concurrent.Executor;

/**
 * 支持 TraceId 传递的 Executor 包装器
 * 
 * <p>用于在异步执行时自动传递 TraceId 到子线程
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class TraceableExecutor implements Executor {

    private final Executor delegate;

    public TraceableExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        String traceId = TraceContext.getTraceId();
        delegate.execute(new TraceableRunnable(command, traceId));
    }

    /**
     * 创建 TraceableExecutor
     *
     * @param delegate 原始 Executor
     * @return 包装后的 Executor
     */
    public static TraceableExecutor wrap(Executor delegate) {
        return new TraceableExecutor(delegate);
    }
}