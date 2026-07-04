package com.janeluo.luban.rds.benchmark.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ReportGenerator {
    private static final Logger log = LoggerFactory.getLogger(ReportGenerator.class);

    public void generate(Path metricsDir, Path outputDir) {
        log.info("读取指标文件: {}", metricsDir);
        List<MetricFile> metrics = loadMetrics(metricsDir);
        log.info("加载了 {} 个指标文件", metrics.size());

        try {
            Files.createDirectories(outputDir);
            HtmlReportBuilder htmlBuilder = new HtmlReportBuilder();
            MarkdownReportBuilder mdBuilder = new MarkdownReportBuilder();

            Path htmlPath = outputDir.resolve("cluster-test-report.html");
            Path mdPath = outputDir.resolve("cluster-test-report.md");

            Files.writeString(htmlPath, htmlBuilder.build(metrics));
            Files.writeString(mdPath, mdBuilder.build(metrics));

            log.info("HTML 报告: {}", htmlPath);
            log.info("Markdown 报告: {}", mdPath);
        } catch (IOException e) {
            log.error("生成报告失败", e);
        }
    }

    private List<MetricFile> loadMetrics(Path metricsDir) {
        List<MetricFile> files = new ArrayList<>();
        if (!Files.exists(metricsDir)) return files;
        try (Stream<Path> stream = Files.list(metricsDir)) {
            stream.filter(p -> p.toString().endsWith(".json"))
                  .forEach(p -> {
                      try {
                          String content = Files.readString(p);
                          MetricFile metric = parseMetric(content, p.getFileName().toString());
                          if (metric != null) files.add(metric);
                      } catch (Exception e) {
                          log.warn("解析指标文件失败: {}", p, e);
                      }
                  });
        } catch (IOException e) {
            log.error("读取指标目录失败", e);
        }
        return files;
    }

    private MetricFile parseMetric(String content, String filename) {
        MetricFile metric = new MetricFile();
        metric.filename = filename;
        metric.content = content;
        // 简单提取关键字段
        metric.layer = extractField(content, "layer");
        metric.testName = extractField(content, "testName");
        metric.testClass = extractField(content, "testClass");
        metric.mode = extractField(content, "mode");
        metric.scenarioName = extractField(content, "scenarioName");
        metric.throughput = extractNumber(content, "throughputOpsPerSec");
        metric.p50 = extractNumber(content, "p50");
        metric.p95 = extractNumber(content, "p95");
        metric.p99 = extractNumber(content, "p99");
        metric.duration = extractNumber(content, "duration");
        return metric;
    }

    private String extractField(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":");
        if (idx < 0) return null;
        idx += field.length() + 3; // skip "field":
        // 跳过空格
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx < json.length() && json.charAt(idx) == '"') {
            int end = json.indexOf('"', idx + 1);
            if (end < 0) return null;
            return json.substring(idx + 1, end);
        }
        return null;
    }

    private double extractNumber(String json, String field) {
        int idx = json.indexOf("\"" + field + "\":");
        if (idx < 0) return -1;
        idx += field.length() + 3; // skip "field":
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        StringBuilder num = new StringBuilder();
        while (idx < json.length() && (Character.isDigit(json.charAt(idx)) || json.charAt(idx) == '.')) {
            num.append(json.charAt(idx));
            idx++;
        }
        try { return Double.parseDouble(num.toString()); } catch (Exception e) { return -1; }
    }

    public static class MetricFile {
        public String filename;
        public String content;
        public String layer;
        public String testName;
        public String testClass;
        public String mode;
        public String scenarioName;
        public double throughput;
        public double p50;
        public double p95;
        public double p99;
        public double duration;
    }

    public static void main(String[] args) {
        Path metricsDir = Path.of("target", "test-metrics");
        Path outputDir = Path.of("target", "reports");
        new ReportGenerator().generate(metricsDir, outputDir);
    }
}
