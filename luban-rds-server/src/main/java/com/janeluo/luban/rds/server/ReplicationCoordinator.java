package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.PersistService;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import com.janeluo.luban.rds.replication.MasterReplicationManager;
import com.janeluo.luban.rds.replication.ReplicationController;
import com.janeluo.luban.rds.replication.SlaveReplicationService;
import com.janeluo.luban.rds.replication.handler.ReplicationCommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 复制协调器
 * <p>
 * 实现 {@link ReplicationLifecycleListener}，将集群角色生命周期事件
 * （CLUSTER REPLICATE / failover 提升 / 降级）与复制组件生命周期绑定。
 * </p>
 * <p>
 * 职责：
 * <ul>
 *   <li>初始化 {@link MasterReplicationManager} 单例（backlog、memoryStore、RDB 快照、requirepass）</li>
 *   <li>创建 {@link ReplicationCommandHandler} 供 {@code RedisServerHandler} 注入</li>
 *   <li>按需启停 {@link SlaveReplicationService}（连接/断开上游 master）</li>
 *   <li>提供 {@link #replicateTo(ClusterNode)} / {@link #promoteToMaster()} / {@link #demoteToSlave(ClusterNode)}
 *       供集群模块回调</li>
 * </ul>
 * </p>
 * <p>
 * 线程模型：生命周期回调由 Gossip / ClusterCommandHandler 线程发起，
 * {@code startSlave} / {@code stopSlave} 用 synchronized 保护，避免并发启停同一从节点服务。
 * </p>
 */
public class ReplicationCoordinator implements ReplicationLifecycleListener, ReplicationController {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationCoordinator.class);

    private final RdsConfig config;
    private final MemoryStore memoryStore;
    private final RdbPersistService rdbPersistService;

    /**
     * 主节点复制管理器（单例），setup() 后非 null
     */
    private volatile MasterReplicationManager masterManager;

    /**
     * 复制命令处理器，setup() 后非 null
     */
    private volatile ReplicationCommandHandler replicationCommandHandler;

    /**
     * 当前从节点复制服务（若本节点为 slave），可能为 null
     */
    private volatile SlaveReplicationService slaveService;

    /**
     * 当前已建立复制连接的 master 地址（ip:port），用于幂等判断
     */
    private volatile String currentMasterAddress;

    /**
     * @param config           RDS 配置
     * @param memoryStore      内存存储
     * @param persistService   持久化服务（仅用于推断数据目录；协调器内部会创建独立的 RDB 服务实例供复制快照使用）
     */
    public ReplicationCoordinator(RdsConfig config, MemoryStore memoryStore, PersistService persistService) {
        this.config = config;
        this.memoryStore = memoryStore;
        // 复制模块的 MasterReplicationManager / SlaveReplicationService 需要 RdbPersistService
        // 来生成 RDB 快照（全量同步）与加载 RDB。从 persistService 解析数据目录，
        // 创建一个独立的 RdbPersistService 实例，避免与 server 的持久化服务共享线程池与文件句柄。
        String dataDir = resolveDataDir(persistService);
        this.rdbPersistService = new RdbPersistService(dataDir);
    }

    private String resolveDataDir(PersistService persistService) {
        if (persistService instanceof RdbPersistService) {
            return ((RdbPersistService) persistService).getDataDir();
        }
        // 回退到 config.getDir()，保证非 RDB 模式（aof/both/none）下仍可工作
        String dir = config.getDir();
        return dir != null ? dir : ".";
    }

    /**
     * 初始化主节点复制管理器与复制命令处理器。
     * 若配置了 replicaof，同时启动从节点复制服务。
     */
    public synchronized void setup() {
        // 1. 初始化 MasterReplicationManager 单例
        int backlogSize = (int) config.getReplBacklogSize();
        MasterReplicationManager.initialize(backlogSize);
        this.masterManager = MasterReplicationManager.getInstance();
        this.masterManager.setMemoryStore(memoryStore);
        this.masterManager.setRdbPersistService(rdbPersistService);
        if (config.getRequirepass() != null) {
            this.masterManager.setRequirepass(config.getRequirepass());
        }
        logger.info("MasterReplicationManager 已初始化: backlogSize={}", backlogSize);

        // 2. 创建复制命令处理器
        this.replicationCommandHandler = new ReplicationCommandHandler(config);
        // 注入自身，使 SLAVEOF 命令能真正触发复制启停（server 依赖 replication，无循环依赖）
        this.replicationCommandHandler.setReplicationCoordinator(this);
        logger.info("ReplicationCommandHandler 已创建");

        // 3. 若配置了 replicaof，启动从节点复制服务
        String replicaof = config.getReplicaof();
        if (replicaof != null && !replicaof.isEmpty()) {
            startSlave(replicaof);
        }
    }

    /**
     * 作为 slave 连接到指定 master 地址。
     * <p>
     * 支持两种地址格式：{@code "host:port"} 与 {@code "host port"}。
     * 若已存在指向相同 master 的复制连接，本次调用幂等（直接返回）。
     * </p>
     *
     * @param masterAddress master 地址
     */
    public synchronized void startSlave(String masterAddress) {
        if (masterAddress == null || masterAddress.isEmpty()) {
            logger.warn("startSlave: master 地址为空，忽略");
            return;
        }

        String normalized = normalizeAddress(masterAddress);
        if (normalized == null) {
            logger.warn("startSlave: 无法解析 master 地址 '{}'", masterAddress);
            return;
        }

        // 幂等：相同目标不重复建连
        if (normalized.equals(currentMasterAddress) && slaveService != null) {
            logger.debug("startSlave: 已在复制 {}，跳过重复连接", normalized);
            return;
        }

        // 停止旧连接
        stopSlaveInternal();

        try {
            SlaveReplicationService service = new SlaveReplicationService(config);
            service.setMemoryStore(memoryStore);
            service.setRdbPersistService(rdbPersistService);
            // 显式注入 master 地址，避免修改共享 RdsConfig.replicaof（线程安全）。
            // SlaveReplicationService.start() 会优先使用注入地址，不回退读 config。
            service.setMasterAddress(normalized);
            service.start();
            this.slaveService = service;
            this.currentMasterAddress = normalized;
            logger.info("从节点复制服务已启动，连接 master: {}", normalized);
        } catch (Exception e) {
            logger.error("启动从节点复制服务失败: master={}", normalized, e);
            this.slaveService = null;
            this.currentMasterAddress = null;
        }
    }

    /**
     * 停止从节点复制服务。
     * <p>
     * 仅停止复制连接并清除协调器内部跟踪的 master 地址，不修改共享
     * {@link RdsConfig#getReplicaof()}（避免线程安全问题）。config.replicaof
     * 仅在启动时由配置文件设置一次，运行时复制生命周期由本协调器管理。
     * </p>
     */
    public synchronized void stopSlave() {
        stopSlaveInternal();
    }

    private void stopSlaveInternal() {
        if (slaveService != null) {
            try {
                slaveService.stop();
            } catch (Exception e) {
                logger.warn("停止从节点复制服务时异常", e);
            }
            slaveService = null;
        }
        currentMasterAddress = null;
    }

    /**
     * 关闭协调器：停止从节点复制 + 关闭主节点复制管理器。
     */
    public synchronized void shutdown() {
        stopSlaveInternal();
        if (masterManager != null) {
            try {
                masterManager.shutdown();
            } catch (Exception e) {
                logger.warn("关闭 MasterReplicationManager 时异常", e);
            }
        }
        logger.info("ReplicationCoordinator 已关闭");
    }

    // ==================== ReplicationLifecycleListener 实现 ====================

    @Override
    public void replicateTo(ClusterNode master) {
        if (master == null) {
            logger.warn("replicateTo: master 节点为 null，忽略");
            return;
        }
        String address = master.getIp() + ":" + master.getPort();
        logger.info("集群生命周期 replicateTo: master={}, address={}", master.getNodeId(), address);
        startSlave(address);
    }

    @Override
    public void promoteToMaster() {
        logger.info("集群生命周期 promoteToMaster: 停止上游复制，本节点提升为 master");
        // 提升为 master：停止上游复制连接，但保留本地已同步数据。
        // MasterReplicationManager 继续承载 slave 连接。
        //
        // 注意：集群模式下的复制生命周期（slave/master 切换）完全由本协调器通过
        // currentMasterAddress 跟踪，不依赖 RdsConfig.replicaof。config.replicaof
        // 仅在启动时由配置文件设置一次（standalone 模式），运行时不再被修改，
        // 因此提升为 master 后即便重启，只要集群配置（nodes.conf）驱动 CLUSTER
        // REPLICATE 重建复制关系，就不会误将本节点重新降为 slave。
        stopSlaveInternal();
    }

    @Override
    public void demoteToSlave(ClusterNode master) {
        if (master == null) {
            logger.warn("demoteToSlave: master 节点为 null，忽略");
            return;
        }
        String address = master.getIp() + ":" + master.getPort();
        logger.info("集群生命周期 demoteToSlave: master={}, address={}", master.getNodeId(), address);
        startSlave(address);
    }

    // ==================== Getter ====================

    public ReplicationCommandHandler getReplicationCommandHandler() {
        return replicationCommandHandler;
    }

    public MasterReplicationManager getMasterManager() {
        return masterManager;
    }

    /**
     * @return 本节点当前是否处于 slave 复制状态
     */
    public boolean isSlave() {
        return slaveService != null;
    }

    // ==================== 工具方法 ====================

    /**
     * 将 master 地址规范化为 {@code "host:port"} 格式。
     * 支持 {@code "host:port"} 与 {@code "host port"} 两种输入。
     *
     * @param masterAddress 原始地址
     * @return 规范化地址，无法解析返回 null
     */
    private static String normalizeAddress(String masterAddress) {
        String trimmed = masterAddress.trim();
        String host;
        String port;
        if (trimmed.contains(":")) {
            int idx = trimmed.lastIndexOf(':');
            host = trimmed.substring(0, idx).trim();
            port = trimmed.substring(idx + 1).trim();
        } else {
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                return null;
            }
            host = parts[0].trim();
            port = parts[1].trim();
        }
        if (host.isEmpty() || port.isEmpty()) {
            return null;
        }
        try {
            Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return null;
        }
        return host + ":" + port;
    }
}
