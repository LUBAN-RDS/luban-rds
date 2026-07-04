package com.janeluo.luban.rds.server.cluster.testinfra;

import com.janeluo.luban.rds.client.NettyRedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 多 JVM 进程管理器。
 *
 * <p>负责在测试中启动、停止、监控多个独立的 JVM 子进程，
 * 每个子进程运行一个 {@link TestServerMain} 实例。
 */
public class ProcessManager {
    private static final Logger log = LoggerFactory.getLogger(ProcessManager.class);

    private final String classpath;
    private final String javaPath;
    private final Map<String, ManagedProcess> processes = new ConcurrentHashMap<>();

    public ProcessManager(String classpath) {
        this.classpath = classpath;
        this.javaPath = ProcessHandle.current().info().command().orElse("java");
    }

    public ManagedProcess startProcess(ProcessNodeConfig config) {
        List<String> cmd = new ArrayList<>();
        cmd.add(javaPath);
        cmd.add("-cp");
        cmd.add(classpath);
        if (config.getJvmArgs() != null) {
            cmd.addAll(config.getJvmArgs());
        }
        cmd.add(config.getMainClass());
        cmd.add("--port");
        cmd.add(String.valueOf(config.getPort()));
        if (config.isClusterEnabled()) {
            cmd.add("--cluster-enabled");
            cmd.add("yes");
        }

        log.info("启动进程: {}", String.join(" ", cmd));

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Path logFile = Path.of("target", "test-logs", config.getProcessId() + ".log");
            Path parent = logFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            pb.redirectOutput(logFile.toFile());

            process = pb.start();
            ManagedProcess managed = new ManagedProcess(
                    config.getProcessId(), process, config.getPort(), logFile);
            processes.put(config.getProcessId(), managed);

            waitForPort(config.getPort(), 60000);
            return managed;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            processes.remove(config.getProcessId());
            throw new RuntimeException("启动进程失败: " + config.getProcessId(), e);
        }
    }

    public void stopProcess(String processId, boolean graceful) {
        ManagedProcess managed = processes.get(processId);
        if (managed == null) {
            return;
        }

        if (graceful) {
            log.info("优雅停止进程 {}", processId);
            NettyRedisClient client = new NettyRedisClient("127.0.0.1", managed.getPort());
            try {
                client.connect();
                client.executeCommand("SHUTDOWN");
            } catch (Exception e) {
                log.warn("优雅停止失败，强制终止 {}", processId);
            } finally {
                try {
                    client.disconnect();
                } catch (Exception ignore) {
                    // best-effort cleanup
                }
            }
            // Wait for process to exit, force-kill if needed
            try {
                if (!managed.getProcess().waitFor(5, TimeUnit.SECONDS)) {
                    managed.getProcess().destroyForcibly();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                managed.getProcess().destroyForcibly();
            }
        } else {
            log.info("强制停止进程 {}", processId);
            managed.getProcess().destroyForcibly();
        }

        processes.remove(processId);
    }

    public boolean isAlive(String processId) {
        ManagedProcess managed = processes.get(processId);
        return managed != null && managed.getProcess().isAlive();
    }

    public String getOutput(String processId) {
        ManagedProcess managed = processes.get(processId);
        if (managed == null) {
            return "";
        }
        try {
            return Files.readString(managed.getLogFile());
        } catch (IOException e) {
            return "";
        }
    }

    public void stopAll() {
        for (String id : new ArrayList<>(processes.keySet())) {
            stopProcess(id, false);
        }
    }

    private void waitForPort(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket("127.0.0.1", port)) {
                return; // 端口已就绪
            } catch (Exception e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("等待端口 " + port + " 被中断", ie);
                }
            }
        }
        throw new RuntimeException("等待端口 " + port + " 超时 (" + timeoutMs + "ms)");
    }

    /**
     * 已管理的子进程句柄。
     */
    public static class ManagedProcess {
        private final String processId;
        private final Process process;
        private final int port;
        private final Path logFile;

        public ManagedProcess(String processId, Process process, int port, Path logFile) {
            this.processId = processId;
            this.process = process;
            this.port = port;
            this.logFile = logFile;
        }

        public String getProcessId() {
            return processId;
        }

        public Process getProcess() {
            return process;
        }

        public int getPort() {
            return port;
        }

        public Path getLogFile() {
            return logFile;
        }
    }

    /**
     * 子进程节点配置（Builder 模式）。
     */
    public static class ProcessNodeConfig {
        private final String processId;
        private final String mainClass;
        private final int port;
        private final boolean clusterEnabled;
        private final List<String> jvmArgs;

        private ProcessNodeConfig(Builder builder) {
            this.processId = builder.processId;
            this.mainClass = builder.mainClass;
            this.port = builder.port;
            this.clusterEnabled = builder.clusterEnabled;
            this.jvmArgs = builder.jvmArgs;
        }

        public String getProcessId() {
            return processId;
        }

        public String getMainClass() {
            return mainClass;
        }

        public int getPort() {
            return port;
        }

        public boolean isClusterEnabled() {
            return clusterEnabled;
        }

        public List<String> getJvmArgs() {
            return jvmArgs;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String processId;
            private String mainClass =
                    "com.janeluo.luban.rds.server.cluster.testinfra.TestServerMain";
            private int port;
            private boolean clusterEnabled = true;
            private List<String> jvmArgs = new ArrayList<>();

            public Builder processId(String id) {
                this.processId = id;
                return this;
            }

            public Builder mainClass(String cls) {
                this.mainClass = cls;
                return this;
            }

            public Builder port(int port) {
                this.port = port;
                return this;
            }

            public Builder clusterEnabled(boolean enabled) {
                this.clusterEnabled = enabled;
                return this;
            }

            public Builder jvmArgs(List<String> args) {
                this.jvmArgs = args;
                return this;
            }

            public ProcessNodeConfig build() {
                if (processId == null) {
                    processId = "node-" + port;
                }
                if (port == 0) {
                    throw new IllegalArgumentException("port required");
                }
                return new ProcessNodeConfig(this);
            }
        }
    }
}
