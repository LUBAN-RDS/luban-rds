package com.janeluo.luban.rds.mesh.perf;

import java.util.Arrays;

/**
 * 单场景性能结果：延迟样本统计（p50/p95/p99/max/avg）+ JSON 序列化。
 * <p>
 * 延迟单位统一为微秒（μs），与 benchmark 模块 L3-*.json 口径一致。
 * 统计基于全量样本排序后的线性插值分位数；无延迟样本的吞吐类场景（选举、管道、故障恢复）
 * 用 {@link #throughputOnly} 记录。
 * </p>
 */
public class MeshPerfResult {

    private final String scenario;
    private final String params;
    private final long totalOps;
    private final long durationMs;
    private final double opsPerSec;
    private final long p50Us;
    private final long p95Us;
    private final long p99Us;
    private final long maxUs;
    private final long avgUs;
    private final long errorCount;

    private MeshPerfResult(String scenario, String params, long totalOps, long durationMs,
                           double opsPerSec, long p50Us, long p95Us, long p99Us, long maxUs,
                           long avgUs, long errorCount) {
        this.scenario = scenario;
        this.params = params;
        this.totalOps = totalOps;
        this.durationMs = durationMs;
        this.opsPerSec = opsPerSec;
        this.p50Us = p50Us;
        this.p95Us = p95Us;
        this.p99Us = p99Us;
        this.maxUs = maxUs;
        this.avgUs = avgUs;
        this.errorCount = errorCount;
    }

    /**
     * 从延迟样本构建结果（样本单位 μs）。
     *
     * @param scenario  场景名
     * @param params    场景参数描述（如 {@code threads=8} / {@code bus=netty}）
     * @param durationMs 总耗时（ms）
     * @param latencyUs 每操作延迟样本（μs）
     * @param errorCount 失败操作数
     */
    public static MeshPerfResult fromSamples(String scenario, String params, long durationMs,
                                             long[] latencyUs, long errorCount) {
        long[] sorted = latencyUs.clone();
        Arrays.sort(sorted);
        long total = sorted.length;
        long sum = 0;
        for (long v : sorted) {
            sum += v;
        }
        return new MeshPerfResult(scenario, params, total, durationMs,
                safeOpsPerSec(total, durationMs),
                percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                total == 0 ? 0 : sorted[sorted.length - 1],
                total == 0 ? 0 : sum / total,
                errorCount);
    }

    /** 无延迟样本场景（选举/管道/故障恢复）：仅记录耗时与吞吐（管道场景）。 */
    public static MeshPerfResult throughputOnly(String scenario, String params, long totalOps,
                                                long durationMs) {
        return new MeshPerfResult(scenario, params, totalOps, durationMs,
                safeOpsPerSec(totalOps, durationMs), 0, 0, 0, 0, 0, 0);
    }

    private static double safeOpsPerSec(long ops, long durationMs) {
        if (durationMs <= 0) {
            return 0;
        }
        return ops / (durationMs / 1000.0);
    }

    /** 线性插值分位数（sorted 升序，p ∈ (0,1]）。 */
    private static long percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        if (sorted.length == 1) {
            return sorted[0];
        }
        double pos = p * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted[lo];
        }
        long vlo = sorted[lo];
        long vhi = sorted[hi];
        return vlo + (long) ((vhi - vlo) * (pos - lo));
    }

    // ==================== 输出 ====================

    /** 单行 JSON（无换行，供数组拼接）。 */
    public String toJson() {
        return "{"
                + "\"scenario\":\"" + scenario + "\","
                + "\"params\":\"" + params + "\","
                + "\"totalOps\":" + totalOps + ","
                + "\"durationMs\":" + durationMs + ","
                + "\"opsPerSec\":" + opsPerSec + ","
                + "\"latencyUs\":{\"p50\":" + p50Us + ",\"p95\":" + p95Us + ",\"p99\":" + p99Us
                + ",\"max\":" + maxUs + ",\"avg\":" + avgUs + "},"
                + "\"errorCount\":" + errorCount + "}";
    }

    /** markdown 表格行（不含首尾竖线）。 */
    public String toMarkdownRow() {
        return scenario + " | " + params + " | " + totalOps + " | " + durationMs
                + " | " + String.format("%.0f", opsPerSec)
                + " | " + p50Us + " | " + p95Us + " | " + p99Us + " | " + maxUs + " | " + avgUs
                + " | " + errorCount;
    }

    /** 日志摘要。 */
    public String summary() {
        return String.format("ops=%d wall=%dms ops/s=%.0f p50=%dμs p95=%dμs p99=%dμs max=%dμs avg=%dμs err=%d",
                totalOps, durationMs, opsPerSec, p50Us, p95Us, p99Us, maxUs, avgUs, errorCount);
    }

    // ==================== getters ====================

    public String getScenario() {
        return scenario;
    }

    public String getParams() {
        return params;
    }

    public long getTotalOps() {
        return totalOps;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public double getOpsPerSec() {
        return opsPerSec;
    }

    public long getP50Us() {
        return p50Us;
    }

    public long getP95Us() {
        return p95Us;
    }

    public long getP99Us() {
        return p99Us;
    }

    public long getMaxUs() {
        return maxUs;
    }

    public long getAvgUs() {
        return avgUs;
    }

    public long getErrorCount() {
        return errorCount;
    }
}
