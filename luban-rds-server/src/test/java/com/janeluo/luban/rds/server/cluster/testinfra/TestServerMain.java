package com.janeluo.luban.rds.server.cluster.testinfra;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.server.RedisServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试专用服务器启动入口（仅供 ProcessManager 在子进程中启动）。
 *
 * <p>解析 --port 和 --cluster-enabled 参数，启动 NettyRedisServer，
 * 并阻塞主线程直到被关闭。
 */
public class TestServerMain {
    private static final Logger log = LoggerFactory.getLogger(TestServerMain.class);

    public static void main(String[] args) {
        int port = 0;
        boolean clusterEnabled = false;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--port".equals(arg) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if ("--cluster-enabled".equals(arg) && i + 1 < args.length) {
                clusterEnabled = "yes".equalsIgnoreCase(args[++i]);
            }
        }
        if (port <= 0) {
            log.error("--port 参数必填");
            System.exit(1);
        }

        RdsConfig config = new RdsConfig();
        config.setPort(port);
        config.setClusterEnabled(clusterEnabled);

        final int serverPort = port;
        RedisServer server = new NettyRedisServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("关闭测试服务器 port={}", serverPort);
            server.stop();
        }));

        try {
            server.start();
            log.info("测试服务器启动成功 port={} cluster={}", port, clusterEnabled);
            Thread.currentThread().join();
        } catch (Exception e) {
            log.error("测试服务器启动失败", e);
            server.stop();
            System.exit(1);
        }
    }
}
