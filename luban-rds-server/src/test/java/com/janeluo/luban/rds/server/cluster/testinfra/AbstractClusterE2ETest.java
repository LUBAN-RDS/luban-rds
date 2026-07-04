package com.janeluo.luban.rds.server.cluster.testinfra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.time.Instant;

@Tag("e2e")
public abstract class AbstractClusterE2ETest {
    protected ProcessManager processManager;
    protected MetricsCollector metrics;
    protected static final int BASE_PORT = 8000;

    @BeforeEach
    void setUp() {
        String classpath = System.getProperty("java.class.path");
        processManager = new ProcessManager(classpath);
        metrics = new MetricsCollector(
                getClass().getSimpleName(), getClass().getName(), "L2");
        metrics.startCollection(5000);

        // JVM shutdown hook 兜底清理
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (processManager != null) processManager.stopAll();
        }));
    }

    @AfterEach
    void tearDown() {
        metrics.writeToJson(getMetricsPath());
        if (processManager != null) processManager.stopAll();
    }

    protected Path getMetricsPath() {
        return Path.of("target", "test-metrics",
                "L2-" + getClass().getSimpleName() + "-" + Instant.now().toEpochMilli() + ".json");
    }

    protected ProcessManager.ProcessNodeConfig nodeConfig(int port, boolean clusterEnabled) {
        return ProcessManager.ProcessNodeConfig.builder()
                .processId("e2e-node-" + port)
                .port(port)
                .clusterEnabled(clusterEnabled)
                .build();
    }
}
