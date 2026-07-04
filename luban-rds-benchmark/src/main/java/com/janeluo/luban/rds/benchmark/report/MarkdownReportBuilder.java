package com.janeluo.luban.rds.benchmark.report;

import java.util.Date;
import java.util.List;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

public class MarkdownReportBuilder {

    public String build(List<ReportGenerator.MetricFile> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 集群模式全面测试报告\n\n");
        sb.append("生成时间: ").append(new Date()).append("\n\n");
        sb.append("指标文件数: ").append(metrics.size()).append("\n\n");

        long l1Count = metrics.stream().filter(m -> "L1".equals(m.layer)).count();
        long l2Count = metrics.stream().filter(m -> "L2".equals(m.layer)).count();
        long l3Count = metrics.stream().filter(m -> "L3".equals(m.layer)).count();
        sb.append("| 层 | 文件数 |\n|---|---|\n");
        sb.append("| L1 快速集成 | ").append(l1Count).append(" |\n");
        sb.append("| L2 端到端 | ").append(l2Count).append(" |\n");
        sb.append("| L3 性能基准 | ").append(l3Count).append(" |\n\n");

        // L3 性能对比
        List<ReportGenerator.MetricFile> l3Metrics = metrics.stream()
                .filter(m -> "L3".equals(m.layer)).collect(Collectors.toList());
        if (!l3Metrics.isEmpty()) {
            sb.append("## L3 性能基准结果\n\n");
            sb.append("| 场景 | 模式 | 吞吐量(ops/s) | P50(μs) | P95(μs) | P99(μs) |\n");
            sb.append("|------|------|---------------|---------|---------|---------|\n");
            for (ReportGenerator.MetricFile m : l3Metrics) {
                sb.append("| ").append(m.scenarioName != null ? m.scenarioName : "-");
                sb.append(" | ").append(m.mode != null ? m.mode : "-");
                sb.append(" | ").append(m.throughput > 0 ? String.format("%.0f", m.throughput) : "-");
                sb.append(" | ").append(m.p50 > 0 ? String.format("%.0f", m.p50) : "-");
                sb.append(" | ").append(m.p95 > 0 ? String.format("%.0f", m.p95) : "-");
                sb.append(" | ").append(m.p99 > 0 ? String.format("%.0f", m.p99) : "-");
                sb.append(" |\n");
            }
            sb.append("\n");
        }

        // L1/L2 测试结果
        sb.append("## L1/L2 测试结果\n\n");
        sb.append("| 层 | 测试名 | 测试类 | 耗时(ms) |\n");
        sb.append("|---|---|---|---|\n");
        for (ReportGenerator.MetricFile m : metrics) {
            if (!"L3".equals(m.layer)) {
                sb.append("| ").append(m.layer != null ? m.layer : "-");
                sb.append(" | ").append(m.testName != null ? m.testName : "-");
                sb.append(" | ").append(m.testClass != null ? m.testClass : "-");
                sb.append(" | ").append(m.duration > 0 ? String.format("%.0f", m.duration) : "-");
                sb.append(" |\n");
            }
        }
        sb.append("\n");

        // 结论模板
        sb.append("## 结论与建议\n\n");
        if (!l3Metrics.isEmpty()) {
            OptionalDouble singleThroughput = l3Metrics.stream()
                    .filter(m -> "single".equals(m.mode))
                    .mapToDouble(m -> m.throughput)
                    .findFirst();
            OptionalDouble clusterThroughput = l3Metrics.stream()
                    .filter(m -> m.mode != null && m.mode.startsWith("cluster-3"))
                    .mapToDouble(m -> m.throughput)
                    .findFirst();
            if (singleThroughput.isPresent() && clusterThroughput.isPresent()) {
                double ratio = clusterThroughput.getAsDouble() / singleThroughput.getAsDouble() * 100;
                sb.append("- 集群模式吞吐量为单进程的 ").append(String.format("%.1f", ratio)).append("%\n");
            }
            sb.append("- 详细分析见各指标数据\n");
        }
        sb.append("\n*报告由 ReportGenerator 自动生成*\n");

        return sb.toString();
    }
}
