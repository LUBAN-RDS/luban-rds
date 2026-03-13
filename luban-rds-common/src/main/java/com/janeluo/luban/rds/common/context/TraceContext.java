package com.janeluo.luban.rds.common.context;

import org.slf4j.MDC;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分布式追踪上下文
 * 
 * <p>用于管理请求链路的 TraceId，支持：
 * <ul>
 *   <li>全局唯一的 TraceId 生成</li>
 *   <li>自动注入到日志 MDC 中</li>
 *   <li>多线程环境下的 TraceId 传递</li>
 * </ul>
 * 
 * <p>TraceId 格式：{时间戳(毫秒)}-{机器标识}-{序列号}
 * <p>示例：1704067200000-a1b2c3d4-000001
 * 
 * @author janeluo
 * @since 1.0.0
 */
public final class TraceContext {

    /**
     * MDC 中 TraceId 的键名
     */
    public static final String TRACE_ID_KEY = "traceId";

    /**
     * TraceId 最大长度限制
     */
    private static final int MAX_TRACE_ID_LENGTH = 64;

    /**
     * 序列号生成器
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    /**
     * 机器标识（取机器名哈希的后8位十六进制）
     */
    private static final String MACHINE_ID;

    /**
     * 进程ID
     */
    private static final String PROCESS_ID;

    static {
        String machineId;
        String processId;
        try {
            String hostName = java.net.InetAddress.getLocalHost().getHostName();
            int hash = hostName.hashCode();
            machineId = String.format("%08x", hash & 0xFFFFFFFFL);
        } catch (Exception e) {
            machineId = String.format("%08x", ThreadLocalRandom.current().nextInt());
        }
        MACHINE_ID = machineId;

        String pid = "0000";
        try {
            String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            if (processName != null && processName.contains("@")) {
                pid = processName.split("@")[0];
            }
        } catch (Exception ignored) {
        }
        PROCESS_ID = pid;
    }

    private TraceContext() {
    }

    /**
     * 生成全局唯一的 TraceId
     * 
     * <p>格式：{时间戳(毫秒)}-{机器标识}-{进程ID}-{序列号}
     * <p>示例：1704067200000-a1b2c3d4-1234-000001
     *
     * @return 新的 TraceId
     */
    public static String generateTraceId() {
        long timestamp = System.currentTimeMillis();
        long seq = SEQUENCE.incrementAndGet() % 1000000;
        return String.format("%d-%s-%s-%06d", timestamp, MACHINE_ID, PROCESS_ID, seq);
    }

    /**
     * 设置当前请求的 TraceId
     * 
     * <p>同时将 TraceId 放入 MDC，自动注入到日志中
     *
     * @param traceId TraceId 值
     */
    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isEmpty()) {
            String validTraceId = traceId.length() > MAX_TRACE_ID_LENGTH 
                    ? traceId.substring(0, MAX_TRACE_ID_LENGTH) 
                    : traceId;
            MDC.put(TRACE_ID_KEY, validTraceId);
        }
    }

    /**
     * 获取当前请求的 TraceId
     *
     * @return 当前 TraceId，如果未设置则返回 null
     */
    public static String getTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * 清除当前请求的 TraceId
     * 
     * <p>应在请求处理完成后调用，避免 TraceId 泄露
     */
    public static void clearTraceId() {
        MDC.remove(TRACE_ID_KEY);
    }

    /**
     * 检查当前是否存在 TraceId
     *
     * @return 如果已设置 TraceId 则返回 true
     */
    public static boolean hasTraceId() {
        return MDC.get(TRACE_ID_KEY) != null;
    }

    /**
     * 启动新的追踪上下文
     * 
     * <p>生成新的 TraceId 并设置到上下文中
     *
     * @return 生成的 TraceId
     */
    public static String startTrace() {
        String traceId = generateTraceId();
        setTraceId(traceId);
        return traceId;
    }

    /**
     * 启动新的追踪上下文（使用指定的 TraceId）
     * 
     * <p>如果 traceId 为空，则自动生成新的 TraceId
     *
     * @param traceId 指定的 TraceId，可为 null
     * @return 实际使用的 TraceId
     */
    public static String startTrace(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            traceId = generateTraceId();
        }
        setTraceId(traceId);
        return traceId;
    }

    /**
     * 结束追踪上下文
     * 
     * <p>清除当前的 TraceId，应在请求处理完成后调用
     */
    public static void endTrace() {
        clearTraceId();
    }

    /**
     * 获取 MDC 键名
     *
     * @return MDC 中 TraceId 的键名
     */
    public static String getTraceIdKey() {
        return TRACE_ID_KEY;
    }
}