package com.janeluo.luban.rds.server.cluster.testinfra;

import java.util.Random;

public class TestNodeConfig {
    private final String nodeId;
    private final String ip;
    private final int port;
    private final int busPort;
    private final boolean clusterEnabled;
    private final boolean persistenceEnabled;

    private TestNodeConfig(Builder builder) {
        this.nodeId = builder.nodeId;
        this.ip = builder.ip;
        this.port = builder.port;
        this.busPort = builder.busPort > 0 ? builder.busPort : builder.port + 10000;
        this.clusterEnabled = builder.clusterEnabled;
        this.persistenceEnabled = builder.persistenceEnabled;
    }

    public String getNodeId() { return nodeId; }
    public String getIp() { return ip; }
    public int getPort() { return port; }
    public int getBusPort() { return busPort; }
    public boolean isClusterEnabled() { return clusterEnabled; }
    public boolean isPersistenceEnabled() { return persistenceEnabled; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String nodeId;
        private String ip = "127.0.0.1";
        private int port;
        private int busPort = 0;
        private boolean clusterEnabled = true;
        private boolean persistenceEnabled = false;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder ip(String ip) { this.ip = ip; return this; }
        public Builder port(int port) { this.port = port; return this; }
        public Builder busPort(int busPort) { this.busPort = busPort; return this; }
        public Builder clusterEnabled(boolean enabled) { this.clusterEnabled = enabled; return this; }
        public Builder persistenceEnabled(boolean enabled) { this.persistenceEnabled = enabled; return this; }

        public TestNodeConfig build() {
            if (nodeId == null) {
                nodeId = generateNodeId();
            }
            if (port == 0) {
                throw new IllegalArgumentException("port must be set");
            }
            return new TestNodeConfig(this);
        }

        private String generateNodeId() {
            Random rnd = new Random();
            StringBuilder sb = new StringBuilder(40);
            String chars = "0123456789abcdef";
            for (int i = 0; i < 40; i++) {
                sb.append(chars.charAt(rnd.nextInt(16)));
            }
            return sb.toString();
        }
    }
}
