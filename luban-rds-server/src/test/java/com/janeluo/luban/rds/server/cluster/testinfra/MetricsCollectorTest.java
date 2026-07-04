package com.janeluo.luban.rds.server.cluster.testinfra;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectorTest {

    @Test
    void testRecordLatencyAndWriteJson(@TempDir Path tempDir) throws Exception {
        Path jsonPath = tempDir.resolve("test-metrics.json");
        MetricsCollector collector = new MetricsCollector("test", "TestClass", "L1");
        collector.startCollection(100);

        collector.recordLatency("GET", 100);
        collector.recordLatency("GET", 200);
        collector.recordLatency("GET", 300);

        Thread.sleep(300); // 等待采样
        collector.writeToJson(jsonPath);

        String content = Files.readString(jsonPath);
        assertTrue(content.contains("\"testName\": \"test\""));
        assertTrue(content.contains("\"count\": 3"));
        assertTrue(content.contains("\"min\": 100"));
        assertTrue(content.contains("\"max\": 300"));
        assertTrue(content.contains("\"resources\""));
    }
}
