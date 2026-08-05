package com.janeluo.luban.rds.mesh.lifecycle;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshConfig.ReadConsistency;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshBusServer;
import com.janeluo.luban.rds.mesh.client.MeshClientRedirector;
import com.janeluo.luban.rds.mesh.client.MeshClusterCommands;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.gateway.MeshWriteGate;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.mesh.replication.SnapshotManager;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import com.janeluo.luban.rds.replication.RdbDataLoader;
import com.janeluo.luban.rds.replication.RdbSnapshotGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mesh 启动装配入口（DESIGN.md §5.5 / §6 / IMPLEMENTATION_PLAN 阶段 12）。
 * <p>
 * 仿 {@code NettyRedisServer.initClusterMode}：从 {@link RdsConfig} 读取 mesh.* 配置，
 * 按 §5.5 启动顺序恢复节点状态并装配全部 mesh 组件，返回 {@link MeshAssembly}。
 * </p>
 *
 * <h3>装配顺序（DESIGN §5.5）</h3>
 * <ol>
 *   <li>解析 peers：{@code mesh-peers}（{@code nodeId@host:busPort} 列表）→ MeshConfig。</li>
 *   <li>{@link MeshConfigPersister} 加载 raft-nodes.conf → MeshState（无则新建空 state）。</li>
 *   <li>{@link MeshStartupLoader} 按 §5.5 顺序恢复：raft-nodes.conf → dump.rdb 衔接 → logTail 重放。</li>
 *   <li>创建 LogApplier（apply 到 raw store，不写 AOF）。</li>
 *   <li>创建 MeshNode（注入 state/config/busClient/busServer/persistHook/snapshotManager）。</li>
 *   <li>创建 SnapshotManager（chunked 发送/接收 + 周期快照），注入到 MeshNode。</li>
 *   <li>创建 MeshWriteGate（meshNode/rawStore/handler/config）。</li>
 *   <li>创建 MeshClientRedirector（nodeId→serviceAddr 映射）。</li>
 *   <li>创建 MeshClusterCommands（leader 供应商 + allNodes）。</li>
 *   <li>创建 MeshLifecycleListener，注册到 MeshNode。</li>
 *   <li><b>不在此处启动 MeshNode</b>——启动时机由 {@code NettyRedisServer.start()} 统一驱动
 *       （与 clusterBusServer.start / gossipProtocol.start 同位置）。</li>
 * </ol>
 * </p>
 *
 * <h3>nodeId 推断</h3>
 * <p>
 * {@code mesh-self-node-id} 未配置时，按 {@code mesh-peers} 列表<b>第一个</b>条目作为本节点
 * （简化单进程多实例测试场景；生产建议显式配置 mesh-self-node-id）。</p>
 *
 * <h3>线程模型</h3>
 * <p>
 * bootstrap 本身不启动后台线程——所有组件创建后，MeshNode.start()（由调用方驱动）才启动
 * ElectionTimer 与心跳。busServer.start / busClient.start 也由调用方在合适时机驱动。
 * </p>
 *
 * @author janeluo
 * @since 阶段 12
 */
public class MeshBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(MeshBootstrap.class);

    /**
     * 装配并（部分）启动 mesh 集群，返回装配产物 {@link MeshAssembly}。
     * <p>
     * <b>不启动 MeshNode / busServer / busClient</b>——这些由 {@code NettyRedisServer.start()}
     * 在网络层就绪后统一驱动（避免在构造函数中启动长连接导致端口占用时序问题）。
     * 调用方拿到 assembly 后，需在 {@code start()} 中调用
     * {@code assembly.getBusServer().start() / assembly.getBusClient().start(peers) /
     * assembly.getMeshNode().start()}，在 {@code stop()} 中反向关闭。
     * </p>
     *
     * @param config    全局 RdsConfig（读 mesh.* / dir）
     * @param rawStore  真实 MemoryStore（apply 唯一目标 + 读路径直接读；mesh 与 server 共享同一实例）
     * @param handler   命令处理器（apply 与读路径复用同一个 handler）
     * @return 装配产物（meshNode / writeGate / redirector / clusterCommands / busClient / busServer）
     * @throws IllegalStateException peers 配置非法、持久化加载硬失败等
     */
    public MeshAssembly bootstrap(RdsConfig config, MemoryStore rawStore, DefaultCommandHandler handler) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        if (rawStore == null) {
            throw new IllegalArgumentException("rawStore 不能为 null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }

        logger.info("开始装配 mesh 集群...");

        // 1. 解析 peers + 构建 MeshConfig（含 nodeId→serviceAddr / nodeId→busAddr 映射）
        PeerTopology topo = parsePeers(config);
        MeshConfig meshConfig = buildMeshConfig(config, topo);

        // 2. 创建 busClient / busServer / handler（在 MeshNode 之前，便于注入）
        MeshBusHandler busHandler = new MeshBusHandler();
        MeshBusClient busClient = new MeshBusClient(topo.selfNodeId, busHandler);
        // busPort：显式配置优先，否则取 peers 拓扑中本节点条目
        int busPort = config.getMeshBusPort() > 0
                ? config.getMeshBusPort() : topo.selfBusPort;
        MeshBusServer busServer = new MeshBusServer(topo.selfNodeId, busPort, busHandler);

        // 3. raft-nodes.conf 读写器 + RDB 加载服务（dump.rdb 衔接用）
        String dbDir = config.getDir();
        MeshConfigPersister persister = new MeshConfigPersister(dbDir);
        RdbPersistService rdbPersistService = new RdbPersistService(dbDir);

        // 4. LogApplier（apply 到 raw store，不写 AOF）
        LogApplier applier = new LogApplier(handler, rawStore);

        // 5. MeshStartupLoader 按 §5.5 顺序恢复：raft-nodes.conf → dump.rdb → logTail 重放
        MeshState state = loadStartupState(persister, rdbPersistService, applier, rawStore, dbDir,
                topo.selfNodeId);

        // 6. 创建 MeshNode（注入 state/config/busClient/stateMachine/applier/rawStore）
        RaftStateMachine stateMachine = new RaftStateMachine();
        MeshNode meshNode = new MeshNode(meshConfig, state, busClient, stateMachine, applier, rawStore);

        // 6.1 注册入站消息消费者：把 MeshNode.onMessage 注入共享的 @Sharable busHandler。
        // busHandler 同时挂在 MeshBusServer（peer→本节点 inbound）与 MeshBusClient（本节点→peer 出站连接上的应答 inbound）
        // 的 pipeline 上，一次注册即覆盖双向入站；否则所有 Raft RPC（PreVote/RequestVote/AppendEntries）到站即丢，
        // 选举永远无法收敛（症状：日志「未注册 messageConsumer，MeshFrame 仅记录日志」+ 客户端 MESHDOWN）。
        busHandler.setMessageConsumer(meshNode::onMessage);
        logger.info("mesh 总线消息消费者已注册: nodeId={}, consumer=MeshNode::onMessage", topo.selfNodeId);

        // persistHook：MeshState 变更时落盘 raft-nodes.conf（term/votedFor/log/lastIncluded）
        // 每次 propose/append 全量序列化 logTail + fsync，是写路径主要成本；
        // config.mesh-persist=no 时跳过（测试/低持久性场景，崩溃后状态不完整）。
        if (config.isMeshPersistEnabled()) {
            meshNode.setPersistHook(() -> {
                try {
                    persister.save(state, topo.selfNodeId);
                } catch (IOException e) {
                    throw new RuntimeException("raft-nodes.conf 保存失败", e);
                }
            });
        }

        // 7. SnapshotManager（chunked 发送/接收 + 周期快照）
        RdbSnapshotGenerator snapshotGenerator = new RdbSnapshotGenerator(rdbPersistService, dbDir);
        RdbDataLoader dataLoader = new RdbDataLoader(rdbPersistService, dbDir);
        SnapshotManager snapshotManager = new SnapshotManager(
                topo.selfNodeId, state, busClient, rawStore,
                snapshotGenerator, dataLoader, dbDir,
                SnapshotManager.DEFAULT_CHUNK_SIZE_BYTES,
                config.getMeshSnapshotLogThreshold() > 0
                        ? config.getMeshSnapshotLogThreshold()
                        : SnapshotManager.DEFAULT_SNAPSHOT_LOG_THRESHOLD,
                meshNode.getPersistHookRef(),
                idx -> {
                    try {
                        persister.saveDumpRdbIndex(idx);
                    } catch (IOException e) {
                        throw new RuntimeException("dump.rdb.index 保存失败", e);
                    }
                });
        meshNode.setSnapshotManager(snapshotManager);

        // 8. MeshWriteGate（meshNode/rawStore/handler/config + nodeId→serviceAddr 映射 + 本节点地址）
        //    映射与 redirector 同源，供 redirectResponse 把 Leader nodeId 解析成真实 ip:port；
        //    selfServiceAddr 供自重定向守卫（解析出的 leaderAddr 等于自己时改发 MESHDOWN，防死循环）。
        MeshWriteGate writeGate = new MeshWriteGate(meshNode, rawStore, handler, meshConfig,
                topo.nodeIdToServiceAddr, topo.selfServiceAddr);

        // 9. MeshClientRedirector（nodeId→serviceAddr 映射 + 本节点地址，自重定向守卫）
        MeshClientRedirector redirector = new MeshClientRedirector(topo.nodeIdToServiceAddr, topo.selfServiceAddr);

        // 10. MeshClusterCommands（leader 供应商 + allNodes + 在线判定）
        MeshClusterCommands clusterCommands = buildClusterCommands(meshConfig, meshNode, topo, busClient);

        // 11. MeshLifecycleListener + 注册到 MeshNode
        MeshLifecycleListener lifecycleListener =
                new MeshLifecycleListener(topo.selfNodeId, clusterCommands);
        meshNode.setRoleChangeListener(lifecycleListener);

        logger.info("mesh 集群装配完成: nodeId={}, peers={}, busPort={}, term={}, role={}",
                abbrev(topo.selfNodeId), topo.nodeIdToServiceAddr.size() - 1, busPort,
                state.currentTerm, state.role);

        return new MeshAssembly(meshNode, writeGate, redirector, clusterCommands,
                lifecycleListener, busClient, busServer, snapshotManager);
    }

    // ==================== 启动状态恢复（§5.5）====================

    /**
     * 按 §5.5 顺序恢复启动状态：raft-nodes.conf → dump.rdb 衔接 → logTail 重放。
     * <p>恢复硬失败（IO 错误、JSON 损坏）时抛异常中止启动（不静默重置 term，DESIGN §5.5）。</p>
     */
    private MeshState loadStartupState(MeshConfigPersister persister,
                                       RdbPersistService rdbPersistService,
                                       LogApplier applier, MemoryStore rawStore,
                                       String dbDir, String nodeId) {
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, rdbPersistService, applier, rawStore, dbDir);
        try {
            MeshStartupLoader.StartupResult result = loader.load(nodeId);
            logger.info("mesh 启动状态恢复完成: isTrusted={}, firstStart={}, replayed={}, nodeId={}",
                    result.isTrusted, result.firstStart, result.replayedCount, abbrev(nodeId));
            return result.state;
        } catch (IOException e) {
            // 启动硬故障：IO 错误或 raft-nodes.conf 损坏，中止启动（DESIGN §5.5）
            throw new IllegalStateException("mesh 启动状态加载失败（raft-nodes.conf/dump.rdb）: "
                    + "nodeId=" + abbrev(nodeId), e);
        }
    }

    // ==================== peers 解析 ====================

    /**
     * 解析 {@code mesh-peers}（{@code nodeId@host:busPort} 逗号分隔）→ 拓扑信息。
     * <p>
     * 推断 selfNodeId：优先 {@code mesh-self-node-id}；未配置时取 peers 第一个条目。
     * 构建 nodeId→serviceAddr（{@code host:servicePort}）与 nodeId→busAddr（{@code host:busPort}）映射。
     * </p>
     *
     * @throws IllegalStateException peers 为空或格式非法
     */
    private PeerTopology parsePeers(RdsConfig config) {
        String peersRaw = config.getMeshPeers();
        if (peersRaw == null || peersRaw.trim().isEmpty()) {
            throw new IllegalStateException("mesh-enabled=yes 但 mesh-peers 未配置或为空");
        }

        // service 端口：mesh-service-port 优先，否则用全局 port
        int servicePort = config.getMeshServicePort() > 0
                ? config.getMeshServicePort() : config.getPort();

        Map<String, String> nodeIdToBusAddr = new LinkedHashMap<>();
        Map<String, String> nodeIdToServiceAddr = new LinkedHashMap<>();
        // 记录每个 nodeId 是否显式给出了第三段 :servicePort（用于 D1 地址塌缩校验）
        Map<String, Boolean> nodeIdToExplicitServicePort = new LinkedHashMap<>();
        java.util.List<String> orderedNodeIds = new java.util.ArrayList<>();

        String[] entries = peersRaw.split(",");
        for (String entry : entries) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            // 格式：nodeId@host:busPort[:servicePort]
            //   - busPort：节点间 Raft 总线端口（必填）
            //   - servicePort：对外 RESP 服务端口（可选；缺省回落全局 mesh-service-port / port）
            //     单机多实例（各节点 RESP 端口不同）必须显式给出，否则 service 地址会塌缩到同一端口。
            int atIdx = e.indexOf('@');
            if (atIdx <= 0 || atIdx == e.length() - 1) {
                throw new IllegalStateException("mesh-peers 条目格式非法（应为 nodeId@host:busPort[:servicePort]）: " + e);
            }
            String nodeId = e.substring(0, atIdx).trim();
            String hostPorts = e.substring(atIdx + 1).trim();
            // busPort 在最后一个 ':' 之前；若存在第三段 servicePort，则 hostPorts 形如 host:busPort:servicePort
            // 用 split 限制 3 段，避免 IPv6 host 干扰（当前不支持 IPv6，与原实现一致）。
            String[] parts = hostPorts.split(":");
            if (parts.length < 2) {
                throw new IllegalStateException("mesh-peers 条目缺少 host:busPort: " + e);
            }
            String host = parts[0].trim();
            int busPort;
            try {
                busPort = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalStateException("mesh-peers 条目 busPort 非整数: " + e);
            }
            if (nodeId.isEmpty() || host.isEmpty() || busPort <= 0) {
                throw new IllegalStateException("mesh-peers 条目字段不完整: " + e);
            }
            // 可选第三段：per-node servicePort（单机多实例必需）
            int nodeServicePort = servicePort;
            boolean explicitServicePort = false;
            if (parts.length >= 3) {
                try {
                    nodeServicePort = Integer.parseInt(parts[2].trim());
                } catch (NumberFormatException nfe) {
                    throw new IllegalStateException("mesh-peers 条目 servicePort 非整数: " + e);
                }
                if (nodeServicePort <= 0) {
                    throw new IllegalStateException("mesh-peers 条目 servicePort 必须 > 0: " + e);
                }
                explicitServicePort = true;
            }
            nodeIdToBusAddr.put(nodeId, host + ":" + busPort);
            nodeIdToServiceAddr.put(nodeId, host + ":" + nodeServicePort);
            nodeIdToExplicitServicePort.put(nodeId, explicitServicePort);
            orderedNodeIds.add(nodeId);
        }

        // D1: 同 host 下未显式指定 servicePort 的条目，service 地址（host:port）必须唯一。
        // 单机多实例（同 host、各节点 RESP 端口不同）若漏配第三段，所有节点 service 地址会塌缩到
        // 同一 host:port（本进程全局 port），非 Leader 节点 MOVED 到自己 → Redisson 死循环。
        // 此处把该配置错误前移到启动期，给出可操作修复示例。
        validateServiceAddrUniqueness(nodeIdToServiceAddr, nodeIdToExplicitServicePort);

        if (orderedNodeIds.isEmpty()) {
            throw new IllegalStateException("mesh-enabled=yes 但 mesh-peers 解析后无有效节点");
        }

        // 推断 selfNodeId
        String selfNodeId = config.getMeshSelfNodeId();
        if (selfNodeId == null || selfNodeId.trim().isEmpty()) {
            selfNodeId = orderedNodeIds.get(0);
            logger.info("mesh-self-node-id 未配置，取 peers 首个节点作为本节点: {}", abbrev(selfNodeId));
        }
        if (!nodeIdToBusAddr.containsKey(selfNodeId)) {
            throw new IllegalStateException("mesh-self-node-id=" + abbrev(selfNodeId)
                    + " 不在 mesh-peers 列表中");
        }

        // 本节点 busPort：从 peers 拓扑取（调用方可用 config.getMeshBusPort() 覆盖）
        String selfBusAddr = nodeIdToBusAddr.get(selfNodeId);
        int selfBusPort = Integer.parseInt(selfBusAddr.substring(selfBusAddr.lastIndexOf(':') + 1));
        // 本节点 service 地址：供装配层注入 redirector/gate 做自重定向守卫（D2）
        String selfServiceAddr = nodeIdToServiceAddr.get(selfNodeId);

        PeerTopology topo = new PeerTopology();
        topo.selfNodeId = selfNodeId;
        topo.selfBusPort = selfBusPort;
        topo.selfServiceAddr = selfServiceAddr;
        topo.nodeIdToBusAddr = nodeIdToBusAddr;
        topo.nodeIdToServiceAddr = nodeIdToServiceAddr;
        topo.orderedNodeIds = orderedNodeIds;
        return topo;
    }

    /**
     * D1: 同 host 下未显式指定 servicePort 的条目，service 地址（host:port）必须唯一。
     * <p>
     * 单机多实例（同 host、各节点 RESP 端口不同）若漏配第三段 :servicePort，parsePeers 会用
     * 本进程全局 port 平铺到所有节点，service 地址全部塌缩到同一 host:port。非 Leader 节点
     * MOVED 到自己 → Redisson "MOVED redirection loop detected" 死循环。
     * </p>
     * <p>仅对「未显式给出第三段」的塌缩报错；显式指定相同 servicePort 视为用户意图，不报错
     * （D2 自重定向守卫仍兜底）。</p>
     */
    private void validateServiceAddrUniqueness(Map<String, String> nodeIdToServiceAddr,
                                                Map<String, Boolean> nodeIdToExplicitServicePort) {
        // 按 host:port 分组，统计同地址下「未显式指定 servicePort」的 nodeId
        Map<String, java.util.List<String>> addrToImplicitNodeIds = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : nodeIdToServiceAddr.entrySet()) {
            String nodeId = entry.getKey();
            String addr = entry.getValue();
            // 只关心未显式指定第三段的条目
            if (!Boolean.TRUE.equals(nodeIdToExplicitServicePort.get(nodeId))) {
                addrToImplicitNodeIds.computeIfAbsent(addr, k -> new java.util.ArrayList<>()).add(nodeId);
            }
        }
        for (Map.Entry<String, java.util.List<String>> e : addrToImplicitNodeIds.entrySet()) {
            if (e.getValue().size() > 1) {
                throw new IllegalStateException(
                        "mesh-peers service 地址塌缩: 多个节点 (" + e.getValue() + ") 解析到同一 service 地址 "
                                + e.getKey() + "，且均未显式指定 :servicePort 第三段。"
                                + "单机多实例部署（同 host、各节点 RESP 端口不同）必须为每个 peer 显式给出 servicePort，"
                                + "例如: mesh-peers=n1@127.0.0.1:11000:9736,n2@127.0.0.1:11001:9737,n3@127.0.0.1:11002:9738。"
                                + "否则非 Leader 节点会 MOVED 重定向到自己，触发客户端死循环。");
            }
        }
    }

    /**
     * 构建 {@link MeshConfig}：把 RdsConfig.mesh.* 映射为 MeshConfig.Builder。
     * <p>peers 取拓扑中除自身外的节点（Builder.addPeer 自动过滤自身 busAddr）。</p>
     */
    private MeshConfig buildMeshConfig(RdsConfig config, PeerTopology topo) {
        MeshConfig.Builder b = MeshConfig.builder(topo.selfNodeId);
        for (String nodeId : topo.orderedNodeIds) {
            b.addPeer(nodeId, topo.nodeIdToBusAddr.get(nodeId));
        }
        b.totalNodes(topo.orderedNodeIds.size());

        // 选举/心跳/租约参数（带默认值兜底）
        long minMs = config.getMeshElectionTimeoutMinMs() > 0
                ? config.getMeshElectionTimeoutMinMs() : 150L;
        long maxMs = config.getMeshElectionTimeoutMaxMs() > 0
                ? config.getMeshElectionTimeoutMaxMs() : 300L;
        b.electionTimeout(minMs, maxMs);

        if (config.getMeshHeartbeatIntervalMs() > 0) {
            b.heartbeatIntervalMs(config.getMeshHeartbeatIntervalMs());
        }
        if (config.getMeshLeaseDurationMs() > 0) {
            b.leaseDurationMs(config.getMeshLeaseDurationMs());
        }
        if (config.getMeshReadLeaseWaitMs() > 0) {
            b.readLeaseWaitMs(config.getMeshReadLeaseWaitMs());
        }

        // 读一致性模式
        String mode = config.getMeshReadConsistency();
        if (mode != null && !mode.isEmpty()) {
            try {
                b.readConsistency(ReadConsistency.valueOf(mode.trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                logger.warn("mesh-read-consistency 值非法（{}），保持默认 LEASE", mode);
            }
        }
        return b.build();
    }

    /**
     * 构建 {@link MeshClusterCommands}：leader 供应商 + allNodes 拓扑 + 在线判定。
     */
    private MeshClusterCommands buildClusterCommands(MeshConfig meshConfig, MeshNode meshNode,
                                                     PeerTopology topo, MeshBusClient busClient) {
        // leader nodeId 供应商：动态读 meshNode.getLeaderId()
        java.util.function.Supplier<String> leaderNodeIdSupplier = meshNode::getLeaderId;
        // leader serviceAddr 供应商：leaderId → 查 nodeIdToServiceAddr 映射
        java.util.function.Supplier<String> leaderAddrSupplier = () -> {
            String lid = meshNode.getLeaderId();
            if (lid == null) {
                return null;
            }
            return topo.nodeIdToServiceAddr.get(lid);
        };

        // allNodes 拓扑（NodeInfo 列表，用于 CLUSTER NODES 的 3 节点展示）
        Map<String, MeshClusterCommands.NodeInfo> allNodes = new LinkedHashMap<>();
        for (String nodeId : topo.orderedNodeIds) {
            String busAddr = topo.nodeIdToBusAddr.get(nodeId);
            String serviceAddr = topo.nodeIdToServiceAddr.get(nodeId);
            String host = hostOf(serviceAddr);
            int port = portOf(serviceAddr);
            int busPort = portOf(busAddr);
            MeshClusterCommands.NodeRole role = nodeId.equals(topo.selfNodeId)
                    ? MeshClusterCommands.NodeRole.LEADER : MeshClusterCommands.NodeRole.FOLLOWER;
            allNodes.put(nodeId, new MeshClusterCommands.NodeInfo(nodeId, host, port, busPort, role));
        }

        return new MeshClusterCommands(leaderNodeIdSupplier, leaderAddrSupplier, allNodes,
                topo.selfNodeId, busClient::isConnected);
    }

    private static String hostOf(String hostPort) {
        if (hostPort == null) {
            return "";
        }
        int idx = hostPort.lastIndexOf(':');
        return idx > 0 ? hostPort.substring(0, idx) : hostPort;
    }

    private static int portOf(String hostPort) {
        if (hostPort == null) {
            return 0;
        }
        int idx = hostPort.lastIndexOf(':');
        if (idx <= 0 || idx == hostPort.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(hostPort.substring(idx + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String abbrev(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    // ==================== peers 拓扑内部容器 ====================

    /** peers 解析结果（nodeId→busAddr / nodeId→serviceAddr 映射 + 本节点信息）。 */
    private static final class PeerTopology {
        String selfNodeId;
        int selfBusPort;
        /** 本节点 service 地址（host:port），供装配层注入 redirector/gate 做自重定向守卫。 */
        String selfServiceAddr;
        Map<String, String> nodeIdToBusAddr;
        Map<String, String> nodeIdToServiceAddr;
        java.util.List<String> orderedNodeIds;
    }
}
