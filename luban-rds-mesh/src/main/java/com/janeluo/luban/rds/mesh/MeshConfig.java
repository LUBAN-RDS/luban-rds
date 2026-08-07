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
 *   <li>{@code electionTimeoutMinMs/MaxMs}：选举超时区间（默认 300-600ms，DESIGN §5.2）</li>
 *   <li>{@code heartbeatIntervalMs}：Leader 心跳间隔（默认 100ms，DESIGN §5.7）</li>
 *   <li>{@code leaseDurationMs}：租约时长 = 2 × electionTimeout（默认 1200ms，DESIGN §5.7）</li>
 *   <li>{@code readConsistency}：读一致性模式（{@link ReadConsistency#LEASE} 默认 / READ_INDEX，
 *       DESIGN §5.7），决定读路径用租约本地读还是主动确认后再读</li>
 *   <li>{@code readLeaseWaitMs}：lease 模式下租约失效时的等待上限（默认 1000ms）</li>
 *   <li>{@code peerBusAddrs}：peer nodeId → bus 地址（host:port），供 {@link MeshBusClient} 建连</li>
 *   <li>{@code peerNodeIds}：peer nodeId 集合（含自身过滤）</li>
 * </ul>
 */
public class MeshConfig {

    /**
     * 读一致性模式（DESIGN §5.7）。
     * <ul>
     *   <li>{@link #LEASE}：默认。租约有效直接本地读；失效则 {@code LeaseManager.awaitValid}
     *       被动等下一轮心跳续租（不发额外心跳）。</li>
     *   <li>{@link #READ_INDEX}：时钟不可靠环境。读前主动确认当前 Leader 仍是多数派认可的真 Leader
     *       （发一轮同步心跳等多数派 ACK 续租，等价于「主动等待当前心跳完成」）；确认后才本地读。
     *       阶段 7 简化实现见 {@code MeshWriteGate.ensureReadIndex()}。</li>
     * </ul>
     */
    public enum ReadConsistency {
        /** 租约模式：被动等续租，租约有效直接本地读（默认）。 */
        LEASE,
        /** read-index 模式：主动确认（同步等待当前心跳多数派 ACK 续租）后读。 */
        READ_INDEX
    }

    private final String selfNodeId;
    private final Set<String> peerNodeIds;
    /** peer nodeId → "host:port" 总线地址（不含自身）。 */
    private final Map<String, String> peerBusAddrs;
    private final int totalNodes;

    private final long electionTimeoutMinMs;
    private final long electionTimeoutMaxMs;
    private final long heartbeatIntervalMs;
    private final long leaseDurationMs;
    /** 读一致性模式（DESIGN §5.7），默认 LEASE。 */
    private final ReadConsistency readConsistency;
    /** lease 模式租约失效时的 awaitValid 等待上限（ms）。 */
    private final long readLeaseWaitMs;

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
        this.readConsistency = b.readConsistency;
        this.readLeaseWaitMs = b.readLeaseWaitMs;
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

    /** 读一致性模式（DESIGN §5.7），默认 {@link ReadConsistency#LEASE}。 */
    public ReadConsistency getReadConsistency() {
        return readConsistency;
    }

    /** lease 模式租约失效时的 awaitValid 等待上限（ms）。 */
    public long getReadLeaseWaitMs() {
        return readLeaseWaitMs;
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

        private long electionTimeoutMinMs = 300;
        private long electionTimeoutMaxMs = 600;
        private long heartbeatIntervalMs = 100;
        private long leaseDurationMs = 1200;   // 2× 选举超时上限（保持与 DESIGN §5.7 比例）
        /** 读一致性模式（DESIGN §5.7），默认 LEASE。 */
        private ReadConsistency readConsistency = ReadConsistency.LEASE;
        /** lease 模式租约失效时的 awaitValid 等待上限（ms），默认 1000。 */
        private long readLeaseWaitMs = 1_000L;

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

        /**
         * 读一致性模式（DESIGN §5.7）：{@code LEASE}（默认，被动等续租）或 {@code READ_INDEX}
         * （主动确认后读）。null 时保持默认 {@link ReadConsistency#LEASE}。
         */
        public Builder readConsistency(ReadConsistency mode) {
            if (mode != null) {
                this.readConsistency = mode;
            }
            return this;
        }

        /** lease 模式租约失效时的 awaitValid 等待上限（ms）。 */
        public Builder readLeaseWaitMs(long ms) {
            if (ms < 0) {
                throw new IllegalArgumentException("readLeaseWaitMs 不能为负: " + ms);
            }
            this.readLeaseWaitMs = ms;
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
