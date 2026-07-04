package com.janeluo.luban.rds.benchmark.report;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

public class HtmlReportBuilder {

    public String build(List<ReportGenerator.MetricFile> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<title>集群模式测试报告</title>\n");
        sb.append("<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>\n");
        sb.append("<style>\n");
        sb.append("body { font-family: sans-serif; margin: 20px; background: #f5f5f5; }\n");
        sb.append("h1 { color: #333; } h2 { color: #666; border-bottom: 2px solid #ddd; padding-bottom: 5px; }\n");
        sb.append("table { border-collapse: collapse; width: 100%; margin: 10px 0; }\n");
        sb.append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n");
        sb.append("th { background: #4CAF50; color: white; }\n");
        sb.append("tr:nth-child(even) { background: #f2f2f2; }\n");
        sb.append(".chart-container { margin: 20px 0; }\n");
        sb.append(".summary { background: white; padding: 15px; border-radius: 5px; margin: 10px 0; }\n");
        sb.append("</style>\n</head>\n<body>\n");

        sb.append("<h1>集群模式全面测试报告</h1>\n");
        sb.append("<div class=\"summary\">\n");
        sb.append("<p>生成时间: ").append(new Date()).append("</p>\n");
        sb.append("<p>指标文件数: ").append(metrics.size()).append("</p>\n");

        long l1Count = metrics.stream().filter(m -> "L1".equals(m.layer)).count();
        long l2Count = metrics.stream().filter(m -> "L2".equals(m.layer)).count();
        long l3Count = metrics.stream().filter(m -> "L3".equals(m.layer)).count();
        sb.append("<p>L1 测试: ").append(l1Count).append(" | L2 测试: ").append(l2Count)
          .append(" | L3 基准: ").append(l3Count).append("</p>\n");
        sb.append("</div>\n");

        // L3 性能对比表
        List<ReportGenerator.MetricFile> l3Metrics = metrics.stream()
                .filter(m -> "L3".equals(m.layer)).collect(Collectors.toList());
        if (!l3Metrics.isEmpty()) {
            sb.append("<h2>L3 性能基准结果</h2>\n");
            sb.append("<table>\n<tr><th>场景</th><th>模式</th><th>吞吐量(ops/s)</th>")
              .append("<th>P50延迟(μs)</th><th>P95延迟(μs)</th><th>P99延迟(μs)</th></tr>\n");
            for (ReportGenerator.MetricFile m : l3Metrics) {
                sb.append("<tr><td>").append(escapeHtml(m.scenarioName))
                  .append("</td><td>").append(escapeHtml(m.mode))
                  .append("</td><td>").append(m.throughput > 0 ? String.format("%.0f", m.throughput) : "-")
                  .append("</td><td>").append(m.p50 > 0 ? String.format("%.0f", m.p50) : "-")
                  .append("</td><td>").append(m.p95 > 0 ? String.format("%.0f", m.p95) : "-")
                  .append("</td><td>").append(m.p99 > 0 ? String.format("%.0f", m.p99) : "-")
                  .append("</td></tr>\n");
            }
            sb.append("</table>\n");

            // 图表
            sb.append("<div class=\"chart-container\">\n");
            sb.append("<canvas id=\"latencyChart\" width=\"800\" height=\"400\"></canvas>\n");
            sb.append("</div>\n");
            sb.append("<script>\n");
            sb.append("var ctx = document.getElementById('latencyChart').getContext('2d');\n");
            sb.append("var data = ").append(buildChartData(l3Metrics)).append(";\n");
            sb.append("new Chart(ctx, { type: 'bar', data: data, ");
            sb.append("options: { responsive: true, scales: { y: { beginAtZero: true } } } });\n");
            sb.append("</script>\n");
        }

        // L1/L2 测试结果表
        sb.append("<h2>L1/L2 测试结果</h2>\n");
        sb.append("<table>\n<tr><th>层</th><th>测试名</th><th>测试类</th><th>耗时(ms)</th></tr>\n");
        for (ReportGenerator.MetricFile m : metrics) {
            if (!"L3".equals(m.layer)) {
                sb.append("<tr><td>").append(escapeHtml(m.layer))
                  .append("</td><td>").append(escapeHtml(m.testName))
                  .append("</td><td>").append(escapeHtml(m.testClass))
                  .append("</td><td>").append(m.duration > 0 ? String.format("%.0f", m.duration) : "-")
                  .append("</td></tr>\n");
            }
        }
        sb.append("</table>\n");

        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private String buildChartData(List<ReportGenerator.MetricFile> metrics) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ labels: [");
        sb.append(metrics.stream()
                .map(m -> "\"" + escapeHtml(m.mode) + "\"")
                .collect(Collectors.joining(",")));
        sb.append("], datasets: [");
        sb.append("{ label: 'P50延迟(μs)', data: [");
        sb.append(metrics.stream()
                .map(m -> String.valueOf(m.p50 > 0 ? m.p50 : 0))
                .collect(Collectors.joining(",")));
        sb.append("], backgroundColor: 'rgba(54,162,235,0.5)' },");
        sb.append("{ label: 'P99延迟(μs)', data: [");
        sb.append(metrics.stream()
                .map(m -> String.valueOf(m.p99 > 0 ? m.p99 : 0))
                .collect(Collectors.joining(",")));
        sb.append("], backgroundColor: 'rgba(255,99,132,0.5)' }");
        sb.append("] }");
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "-";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
