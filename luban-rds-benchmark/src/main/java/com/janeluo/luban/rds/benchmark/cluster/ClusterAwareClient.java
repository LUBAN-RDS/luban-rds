package com.janeluo.luban.rds.benchmark.cluster;

import com.janeluo.luban.rds.client.NettyRedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class ClusterAwareClient {
    private static final Logger log = LoggerFactory.getLogger(ClusterAwareClient.class);

    private final Map<String, NettyRedisClient> clients = new HashMap<>();
    private final String defaultHost;
    private final int defaultPort;
    private final AtomicInteger redirectCount = new AtomicInteger(0);

    public ClusterAwareClient(String host, int port) {
        this.defaultHost = host;
        this.defaultPort = port;
    }

    public void connect() {
        getOrCreateClient(defaultHost, defaultPort);
    }

    public Object execute(String command, String... args) {
        int maxRedirects = 5;
        String host = defaultHost;
        int port = defaultPort;

        for (int attempt = 0; attempt <= maxRedirects; attempt++) {
            NettyRedisClient client = getOrCreateClient(host, port);
            Object result = client.executeCommand(command, args);
            String resultStr = result.toString();

            // RESP parser strips the leading "-" from error responses, so a MOVED/ASK
            // redirect may arrive as "MOVED ..." or "-MOVED ..." depending on the path.
            // Normalize before checking so redirect handling works in both cases.
            String normalized = resultStr.startsWith("-") ? resultStr.substring(1) : resultStr;

            if (normalized.startsWith("MOVED")) {
                redirectCount.incrementAndGet();
                // 解析 MOVED <slot> <ip>:<port>
                String[] parts = normalized.replace("MOVED ", "").trim().split(" ");
                String[] hostPort = parts[1].split(":");
                host = hostPort[0];
                port = Integer.parseInt(hostPort[1]);
                continue;
            }

            if (normalized.startsWith("ASK")) {
                redirectCount.incrementAndGet();
                String[] parts = normalized.replace("ASK ", "").trim().split(" ");
                String[] hostPort = parts[1].split(":");
                host = hostPort[0];
                port = Integer.parseInt(hostPort[1]);
                // 发送 ASKING 后重试
                NettyRedisClient askClient = getOrCreateClient(host, port);
                askClient.executeCommand("ASKING");
                continue;
            }

            return result;
        }
        throw new RuntimeException("超过最大重定向次数: " + maxRedirects);
    }

    public void set(String key, String value) {
        execute("SET", key, value);
    }

    public String get(String key) {
        Object result = execute("GET", key);
        return result.toString();
    }

    public int getRedirectCount() {
        return redirectCount.get();
    }

    public void disconnect() {
        for (NettyRedisClient client : clients.values()) {
            try { client.disconnect(); } catch (Exception e) { log.warn("断开连接失败", e); }
        }
        clients.clear();
    }

    private NettyRedisClient getOrCreateClient(String host, int port) {
        String key = host + ":" + port;
        return clients.computeIfAbsent(key, k -> {
            NettyRedisClient client = new NettyRedisClient(host, port);
            client.connect();
            return client;
        });
    }
}
