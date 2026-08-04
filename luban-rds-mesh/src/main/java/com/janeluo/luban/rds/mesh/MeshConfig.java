package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mesh 节点配置（阶段 3 简化版；DESIGN.md §6 文件树）。
 * <p>
 * 阶段 3 只含选举/心跳/租约相关参数；完整配置（serviceAddr↔busAddr 映射、busPort、快照阈值、dbDir 等）
 * 由后续阶段补全（阶段 10 快照、阶段 12 配置装载）。
 * </p>
 *
 * <h3>关键参数</h3>
 * <ul>
 *   <li>{@code electionTimeoutMinMs/MaxMs}：选举超时区间（默认 150-300ms，DESIGN §5.2）</li>
 *   <li>{@code heartbeatIntervalMs}：Leader 心跳间隔（默认 100ms，DESIGN §5.7）</li>
 *   <li>{@code leaseDurationMs}：租约时长 = 2 × electionTimeout（默认 600ms，DESIGN §5.7）</li>
 *   <li>{@code peerBusAddrs}：peer nodeId → bus 地址（host:port），供 {@link MeshBusClient} 建连</li>
 *   <li>{@code peerNodeIds}：peer nodeId 集合（含自身过滤）</li>
 * </ul>
 */
public class MeshConfig {

    private final String selfNodeId;
    private final Set<String> peerNodeIds;
    /** peer nodeId → "host:port" 总线地址（不含自身）。 */
    private final Map<String, String> peerBusAddrs;
    private final int totalNodes;

    private final long electionTimeoutMinMs;
    private final long electionTimeoutMaxMs;
    private final long heartbeatIntervalMs;
    private final long leaseDurationMs;

    private MeshConfig(Builder b) {
        this.selfNodeId = b.selfNodeId;
        this.peerNodeIds = Collections.unmodifiableSet(new java.util.LinkedHashSet<>(b.peerNodeIds));
        Map<String, String> addrs = new LinkedHashMap<>(b.peerBusAddrs);
        addrs.remove(b.selfNodeId); // 过滤自身
        this.peerBusAddrs = Collections.unmodifiableMap(addrs);
        this.totalNodes = b.totalNodes > 0 ? b.totalNodes : (this.peerNodeIds.size());
        this.electionTimeoutMinMs = b.electionTimeoutMinMs;
        this.electionTimeoutMaxMs = b.electionTimeoutMaxMs;
        this.heartbeatIntervalMs = b.heartbeatIntervalMs;
        this.leaseDurationMs = b.leaseDurationMs;
    }

    public String getSelfNodeId() {
        return selfNodeId;
    }

    /** peer nodeId 集合（含自身，调用方过滤）。 */
    public Set<String> getPeerNodeIds() {
        return peerNodeIds;
    }

    /** 不含自身的 peer 集合。 */
    public Set<String> getOtherNodeIds() {
        java.util.Set<String> others = new java.util.LinkedHashSet<>(peerNodeIds);
        others.remove(selfNodeId);
        return others;
    }

    public Map<String, String> getPeerBusAddrs() {
        return peerBusAddrs;
    }

    /** 集群总节点数（含自己），用于多数派判定。 */
    public int getTotalNodes() {
        return totalNodes;
    }

    public long getElectionTimeoutMinMs() {
        return electionTimeoutMinMs;
    }

    public long getElectionTimeoutMaxMs() {
        return electionTimeoutMaxMs;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public long getLeaseDurationMs() {
        return leaseDurationMs;
    }

    /** 多数派票数 = totalNodes / 2 + 1。 */
    public int majority() {
        return totalNodes / 2 + 1;
    }

    public static Builder builder(String selfNodeId) {
        return new Builder(selfNodeId);
    }

    public static class Builder {
        private final String selfNodeId;
        private final Set<String> peerNodeIds = new java.util.LinkedHashSet<>();
        private final Map<String, String> peerBusAddrs = new LinkedHashMap<>();
        private int totalNodes = 0;

        private long electionTimeoutMinMs = 150;
        private long electionTimeoutMaxMs = 300;
        private long heartbeatIntervalMs = 100;
        private long leaseDurationMs = 600;

        public Builder(String selfNodeId) {
            if (selfNodeId == null) {
                throw new IllegalArgumentException("selfNodeId 不能为 null");
            }
            this.selfNodeId = selfNodeId;
            this.peerNodeIds.add(selfNodeId);
        }

        /** 添加一个 peer（nodeId + bus 地址 host:port）。 */
        public Builder addPeer(String nodeId, String busAddr) {
            this.peerNodeIds.add(nodeId);
            if (busAddr != null) {
                this.peerBusAddrs.put(nodeId, busAddr);
            }
            return this;
        }

        /** 显式设置集群总节点数（默认 = peerNodeIds.size()）。 */
        public Builder totalNodes(int total) {
            this.totalNodes = total;
            return this;
        }

        public Builder electionTimeout(long minMs, long maxMs) {
            if (minMs <= 0 || maxMs < minMs) {
                throw new IllegalArgumentException("非法选举超时区间");
            }
            this.electionTimeoutMinMs = minMs;
            this.electionTimeoutMaxMs = maxMs;
            return this;
        }

        public Builder heartbeatIntervalMs(long ms) {
            if (ms <= 0) {
                throw new IllegalArgumentException("heartbeatIntervalMs 必须 > 0");
            }
            this.heartbeatIntervalMs = ms;
            return this;
        }

        public Builder leaseDurationMs(long ms) {
            if (ms <= 0) {
                throw new IllegalArgumentException("leaseDurationMs 必须 > 0");
            }
            this.leaseDurationMs = ms;
            return this;
        }

        public MeshConfig build() {
            if (totalNodes <= 0) {
                totalNodes = peerNodeIds.size();
            }
            return new MeshConfig(this);
        }
    }
}
