package com.janeluo.luban.rds.client.cli;

import java.util.Objects;

/**
 * 集群节点地址值对象
 * <p>
 * 解析 {@code host:port} 形式的字符串，提供 host 与 port 的类型安全访问。
 * </p>
 *
 * @author janeluo
 * @since 1.0.0
 */
public final class NodeAddress {

    private final String host;
    private final int port;

    private NodeAddress(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 解析 {@code host:port} 字符串为 {@link NodeAddress}
     *
     * @param raw 形如 {@code 192.168.8.161:9736} 的字符串
     * @return 节点地址对象
     * @throws ClusterSetupException 格式非法时抛出
     */
    public static NodeAddress parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new ClusterSetupException("节点地址不能为空");
        }
        String trimmed = raw.trim();
        int colonIndex = trimmed.lastIndexOf(':');
        if (colonIndex <= 0 || colonIndex == trimmed.length() - 1) {
            throw new ClusterSetupException("无效的节点地址格式: " + raw + "，应为 host:port");
        }
        String host = trimmed.substring(0, colonIndex);
        String portStr = trimmed.substring(colonIndex + 1);
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            throw new ClusterSetupException("无效的端口号: " + portStr);
        }
        if (port <= 0 || port > 65535) {
            throw new ClusterSetupException("端口号超出范围 (1-65535): " + port);
        }
        if (host.isEmpty()) {
            throw new ClusterSetupException("主机名不能为空: " + raw);
        }
        return new NodeAddress(host, port);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * @return {@code host:port} 形式的字符串
     */
    public String toAddress() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof NodeAddress)) {
            return false;
        }
        NodeAddress that = (NodeAddress) o;
        return port == that.port && Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return toAddress();
    }
}
