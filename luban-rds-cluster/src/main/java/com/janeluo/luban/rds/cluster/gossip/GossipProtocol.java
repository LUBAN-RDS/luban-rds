package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.bus.ClusterBusServer;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterConfigPersister;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.migration.SlotMigrationManager;
import com.janeluo.luban.rds.cluster.node.ClusterLink;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import io.netty.channel.ChannelFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gossip 协议实现
 * <p>
 * 负责节点间信息交换和故障检测，实现 Redis 集群的 Gossip 协议
 * 性能优化：使用 EnumSet、减少对象创建、批量处理消息
 * </p>
 */
public class GossipProtocol {

    private static final Logger logger = LoggerFactory.getLogger(GossipProtocol.class);

    /**
     * 默认 Gossip 间隔（毫秒）
     */
    private static final long DEFAULT_GOSSIP_INTERVAL = 1000;

    /**
     * 每次心跳携带的 Gossip 节点数量
     */
    private static final int GOSSIP_NODE_COUNT = 3;

    /**
     * 集群配置
     */
    private final ClusterConfig clusterConfig;

    /**
     * 集群总线客户端
     */
    private final ClusterBusClient busClient;

    /**
     * 定时任务调度器
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 节点超时时间（毫秒）
     */
    private final long nodeTimeout;

    /**
     * Gossip 间隔（毫秒）
     */
    private final long gossipInterval;

    /**
     * 故障检测器
     */
    private final FailureDetector failureDetector;

    /**
     * 故障转移管理器（由 NettyRedisServer 在创建后通过 setFailoverManager 注入）
     */
    private FailoverManager failoverManager;

    /**
     * 槽位迁移管理器（由 NettyRedisServer 在创建后通过 setSlotMigrationManager 注入）
     * <p>
     * 用于处理 MIGRATE_KEY 消息：目标节点收到键迁移请求后调用 importKey 导入键。
     * </p>
     */
    private SlotMigrationManager slotMigrationManager;

    /**
     * 跨节点 PUBLISH 消息监听器（由上层 server 模块注入，避免 cluster 反向依赖 server）。
     * <p>
     * 收到 PUBLISH 消息时调用，将消息投递到本地订阅者。未注入时仅记录告警。
     * </p>
     */
    private volatile ClusterMessageListener publishListener;

    /**
     * 集群状态管理器（用于消息计数统计）
     */
    private ClusterStateManager stateManager;

    /**
     * 拓扑变更回调（由 NettyRedisServer 注入，用于自动触发 nodes.conf 持久化）
     * <p>
     * 参照 Redis 7 clusterSaveConfigIfNeeded 机制：
     * 当集群拓扑发生变更时调用此回调，触发 nodes.conf 持久化。
     * </p>
     */
    private Runnable onTopologyChanged;

    /**
     * 复制生命周期监听器（由 NettyRedisServer 注入）。
     * <p>
     * 当前仅持有引用，供未来 Gossip 直接感知角色变更时使用。
     * 实际复制启停由 ClusterCommandHandler / FailoverManager 各自注入的实例处理。
     * 默认 NoOp，保证未注入时不触发复制逻辑。
     * </p>
     */
    private volatile ReplicationLifecycleListener replicationLifecycleListener =
            new NoOpReplicationLifecycleListener();

    /**
     * 随机数生成器（ThreadLocal 避免竞争）
     */
    private final ThreadLocal<Random> randomProvider;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /**
     * 节点最后通信时间记录
     */
    private final ConcurrentHashMap<String, Long> lastInteractionTimes;

    /**
     * 可复用的 GossipNodeInfo 列表（减少对象创建）
     */
    private final ThreadLocal<List<GossipNodeInfo>> reusableGossipList;

    /**
     * 构造方法
     *
     * @param clusterConfig 集群配置
     * @param busClient     集群总线客户端
     * @param nodeTimeout   节点超时时间（毫秒）
     */
    public GossipProtocol(ClusterConfig clusterConfig, ClusterBusClient busClient, long nodeTimeout) {
        this(clusterConfig, busClient, nodeTimeout, DEFAULT_GOSSIP_INTERVAL);
    }

    /**
     * 完整构造方法
     *
     * @param clusterConfig  集群配置
     * @param busClient      集群总线客户端
     * @param nodeTimeout    节点超时时间（毫秒）
     * @param gossipInterval Gossip 间隔（毫秒）
     */
    public GossipProtocol(ClusterConfig clusterConfig, ClusterBusClient busClient,
                          long nodeTimeout, long gossipInterval) {
        this.clusterConfig = clusterConfig;
        this.busClient = busClient;
        this.nodeTimeout = nodeTimeout;
        this.gossipInterval = gossipInterval;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gossip-protocol");
            t.setDaemon(true);
            return t;
        });
        this.failureDetector = new FailureDetector(clusterConfig, nodeTimeout);
        this.randomProvider = ThreadLocal.withInitial(Random::new);
        this.started = new AtomicBoolean(false);
        this.lastInteractionTimes = new ConcurrentHashMap<>();
        this.reusableGossipList = ThreadLocal.withInitial(() -> new ArrayList<>(GOSSIP_NODE_COUNT));
    }

    /**
     * 设置集群状态管理器（用于消息计数统计）
     * <p>
     * 解决构造函数顺序依赖：ClusterStateManager 在 GossipProtocol 之前创建，
     * 需要在创建后通过此方法注入引用。
     * </p>
     *
     * @param stateManager 集群状态管理器
     */
    public void setClusterStateManager(ClusterStateManager stateManager) {
        this.stateManager = stateManager;
    }

    /**
     * 注入故障转移管理器（由 NettyRedisServer 在创建 FailoverManager 后注入）。
     *
     * @param failoverManager 故障转移管理器
     */
    public void setFailoverManager(FailoverManager failoverManager) {
        this.failoverManager = failoverManager;
    }

    /**
     * 获取故障转移管理器。
     *
     * @return 故障转移管理器，未注入时返回 null
     */
    public FailoverManager getFailoverManager() {
        return failoverManager;
    }

    /**
     * 注入槽位迁移管理器（由 NettyRedisServer 在创建后注入）。
     * <p>
     * 用于处理 MIGRATE_KEY 消息：目标节点收到键迁移请求后调用 importKey 导入键。
     * </p>
     *
     * @param slotMigrationManager 槽位迁移管理器
     */
    public void setSlotMigrationManager(SlotMigrationManager slotMigrationManager) {
        this.slotMigrationManager = slotMigrationManager;
    }

    /**
     * 获取槽位迁移管理器。
     *
     * @return 槽位迁移管理器，未注入时返回 null
     */
    public SlotMigrationManager getSlotMigrationManager() {
        return slotMigrationManager;
    }

    /**
     * 设置跨节点 PUBLISH 消息监听器。
     * <p>
     * 由上层 server 模块注入（避免 cluster 反向依赖 server 模块的 PubSubManager）。
     * 收到 PUBLISH 消息时调用此监听器，将消息投递到本地订阅者。
     * </p>
     *
     * @param publishListener 消息监听器，null 表示清除
     */
    public void setPublishListener(ClusterMessageListener publishListener) {
        this.publishListener = publishListener;
    }

    /**
     * 设置拓扑变更回调（用于自动触发 nodes.conf 持久化）
     * <p>
     * 参照 Redis 7 clusterSaveConfigIfNeeded 机制：
     * 当集群拓扑发生变更（节点增删、握手完成、FAIL标记等）时调用此回调。
     * </p>
     *
     * @param onTopologyChanged 拓扑变更回调
     */
    public void setOnTopologyChanged(Runnable onTopologyChanged) {
        this.onTopologyChanged = onTopologyChanged;
    }

    /**
     * 设置复制生命周期监听器（由 NettyRedisServer 在装配时注入）。
     *
     * @param listener 复制生命周期监听器，null 时回退为 NoOp 实现
     */
    public void setReplicationLifecycleListener(ReplicationLifecycleListener listener) {
        this.replicationLifecycleListener =
                listener != null ? listener : new NoOpReplicationLifecycleListener();
    }

    /**
     * 启动 Gossip 协议
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            logger.info("启动 Gossip 协议, nodeTimeout={}ms, gossipInterval={}ms",
                    nodeTimeout, gossipInterval);

            // 启动前主动连接所有从 nodes.conf 恢复的已知节点（对齐 Redis clusterConnectAllNodes）。
            // 否则恢复的 master 节点 link 为 disconnected，sendPing 只 warn 不连接，
            // PING 永远发不出去，PFAIL 无法清除，集群重启后成孤岛、节点状态无法恢复。
            connectKnownNodes();

            // 启动定时 Gossip 任务
            GossipTask gossipTask = new GossipTask(this, failureDetector);
            scheduler.scheduleAtFixedRate(gossipTask, 0, gossipInterval, TimeUnit.MILLISECONDS);

            logger.info("Gossip 协议已启动");
        }
    }

    /**
     * 主动连接所有已知的非本节点（启动/恢复时调用）
     * <p>
     * 对齐 Redis clusterConnectAllNodes：从 nodes.conf 恢复的节点 link 为 disconnected，
     * 必须主动建立总线连接，后续 Gossip PING 才能发出，故障检测器才能在收到 PONG 后
     * 清除 PFAIL 状态。否则全集群重启后节点间互为孤岛，状态永远恢复不了。
     * </p>
     * <p>
     * 跳过 HANDSHAKE 节点（由 {@link #initiateMeetForDiscoveredNode} 处理）和 FAIL 节点
     * （不应连接已下线节点）。{@link ClusterBusClient#connect} 内部幂等，已连接则复用。
     * 连接异步且失败只记日志，不影响其他节点或启动流程。
     * </p>
     */
    private void connectKnownNodes() {
        if (busClient == null) {
            return;
        }
        int count = 0;
        for (ClusterNode node : clusterConfig.getAllNodes()) {
            if (node.isMyself() || node.hasState(ClusterNodeState.HANDSHAKE) || node.isFail()) {
                continue;
            }
            // 节点地址从 nodes.conf 恢复；缺失地址的节点跳过（防御）
            if (node.getIp() == null || node.getPort() <= 0) {
                continue;
            }
            if (busClient.isConnected(node.getNodeId())) {
                continue;
            }
            busClient.connect(node.getNodeId(), node.getIp(), node.getPort());
            count++;
        }
        if (count > 0) {
            logger.info("启动时主动连接已知节点: 数量={}", count);
        }
    }

    /**
     * 停止 Gossip 协议
     */
    public void stop() {
        if (started.compareAndSet(true, false)) {
            logger.info("停止 Gossip 协议");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("Gossip 协议已停止");
        }
    }

    /**
     * 发送 PING 到指定节点
     *
     * @param node 目标节点
     */
    public void sendPing(ClusterNode node) {
        if (node == null || node.isMyself()) {
            return;
        }

        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            logger.warn("无法发送 PING: 当前节点信息不存在");
            return;
        }

        PingMessage ping = new PingMessage(
                myNode.getNodeId(),
                System.currentTimeMillis()
        );
        // 携带发送方槽位，使对端能同步槽位归属
        ping.setSenderSlots(myNode.getSlots());
        // 携带发送方角色与 masterNodeId，使对端能同步发送方的 master/slave 角色
        // （selectGossipNodes 排除本节点，自身角色无法经 gossip section 传播）
        ping.setSenderConfigEpoch(myNode.getConfigEpoch());
        ping.setSenderFlags(buildSenderFlags(myNode));
        ping.setSenderMasterNodeId(myNode.getMasterNodeId());
        // 携带发送方集群当前纪元，使对端（尤其是重启节点）能通过心跳同步 currentEpoch
        ping.setSenderCurrentEpoch(clusterConfig.getCurrentEpoch());

        // 添加 Gossip 节点信息
        List<GossipNodeInfo> gossipNodes = selectGossipNodes();
        for (GossipNodeInfo gossipNode : gossipNodes) {
            ping.addGossipNode(gossipNode);
        }

        if (logger.isTraceEnabled()) {
            logger.trace("发送 PING 到节点: {}, gossipCount={}", node.getNodeId(), gossipNodes.size());
        }

        if (busClient != null) {
            busClient.send(node.getNodeId(), ping);
            if (stateManager != null) {
                stateManager.incrementMessagesSent(1);
            }
        }

        // 更新最后发送 PING 时间
        node.updateLastPingTime();
    }

    /**
     * 处理收到的 PING 消息
     *
     * @param ping 收到的 PING 消息
     * @return PONG 响应消息
     */
    public PongMessage handlePing(PingMessage ping) {
        if (logger.isTraceEnabled()) {
            logger.trace("收到 PING 消息: from={}", ping.getSenderNodeId());
        }

        if (stateManager != null) {
            stateManager.incrementMessagesReceived(1);
        }

        // 更新发送方节点信息（包含握手完成处理）
        updateNodeFromPingMessage(ping);

        // 处理 Gossip 信息
        processGossipNodes(ping.getGossipNodes(), ping.getSenderNodeId());

        // 更新节点最后通信时间
        updateNodeLastInteraction(ping.getSenderNodeId());

        // 收到 PING 说明节点存活，清除 FAIL/PFAIL 状态
        failureDetector.clearNodeFailState(ping.getSenderNodeId());

        // 创建 PONG 响应
        ClusterNode myNode = clusterConfig.getMyNode();
        PongMessage pong = new PongMessage(myNode.getNodeId(), System.currentTimeMillis());
        // 携带发送方槽位，使对端能同步槽位归属
        pong.setSenderSlots(myNode.getSlots());
        // 携带发送方角色与 masterNodeId，使对端能同步发送方的 master/slave 角色
        pong.setSenderConfigEpoch(myNode.getConfigEpoch());
        pong.setSenderFlags(buildSenderFlags(myNode));
        pong.setSenderMasterNodeId(myNode.getMasterNodeId());
        pong.setSenderCurrentEpoch(clusterConfig.getCurrentEpoch());

        // 添加 Gossip 节点信息
        List<GossipNodeInfo> gossipNodes = selectGossipNodes();
        for (GossipNodeInfo gossipNode : gossipNodes) {
            pong.addGossipNode(gossipNode);
        }

        return pong;
    }

    /**
     * 处理收到的 PONG 消息
     *
     * @param pong 收到的 PONG 消息
     */
    public void handlePong(PongMessage pong) {
        if (logger.isTraceEnabled()) {
            logger.trace("收到 PONG 消息: from={}", pong.getSenderNodeId());
        }

        if (stateManager != null) {
            stateManager.incrementMessagesReceived(1);
        }

        // 更新发送方节点信息
        updateNodeFromPongMessage(pong);

        // 处理 Gossip 信息
        processGossipNodes(pong.getGossipNodes(), pong.getSenderNodeId());

        // 更新节点最后通信时间
        updateNodeLastInteraction(pong.getSenderNodeId());

        // 清除节点的 FAIL/PFAIL 状态
        failureDetector.clearNodeFailState(pong.getSenderNodeId());
    }

    /**
     * 发送 MEET 消息
     * <p>
     * 在发送 MEET 消息之前，先将目标节点以临时 ID 添加到本地集群配置中（HANDSHAKE 状态）。
     * 当目标节点响应 PONG 时，ClusterBusHandler 会将临时 ID 替换为真实节点 ID。
     * </p>
     *
     * @param ip   目标节点IP
     * @param port 目标节点端口
     */
    public void sendMeet(String ip, int port) {
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            logger.warn("无法发送 MEET: 当前节点信息不存在");
            return;
        }

        int targetBusPort = port + ClusterBusServer.BUS_PORT_OFFSET;

        // 检查是否已存在同地址的节点
        ClusterNode existingNode = findNodeByAddress(ip, port);
        if (existingNode != null) {
            logger.info("目标节点已在集群中: address={}:{}, nodeId={}", ip, port, existingNode.getNodeId());
            return;
        }

        // 生成临时节点ID（40字符十六进制），用于在 PONG 响应前占位
        String tempNodeId = ClusterConfigPersister.generateNodeId();
        ClusterNode targetNode = new ClusterNode(tempNodeId, ip, port, targetBusPort);
        targetNode.addState(ClusterNodeState.HANDSHAKE);
        clusterConfig.addNode(targetNode);
        logger.info("添加目标节点到本地配置（HANDSHAKE）: tempNodeId={}, address={}:{}", tempNodeId, ip, port);

        MeetMessage meet = new MeetMessage();
        meet.setSenderNodeId(myNode.getNodeId());
        meet.setSenderIp(myNode.getIp());
        meet.setSenderPort(myNode.getPort());
        meet.setSenderBusPort(myNode.getBusPort());
        meet.setSenderConfigEpoch(myNode.getConfigEpoch());
        meet.setCurrentEpoch(clusterConfig.getCurrentEpoch());
        // 携带发送方槽位，使对端能同步槽位归属
        meet.setSenderSlots(myNode.getSlots());
        // 携带发送方角色与 masterNodeId，使对端能同步发送方的 master/slave 角色
        meet.setSenderFlags(buildSenderFlags(myNode));
        meet.setSenderMasterNodeId(myNode.getMasterNodeId());

        // 添加 Gossip 节点信息
        List<GossipNodeInfo> gossipNodes = selectGossipNodes();
        for (GossipNodeInfo gossipNode : gossipNodes) {
            meet.addGossipNode(gossipNode);
        }

        logger.info("发送 MEET 消息: target={}:{}", ip, port);

        if (busClient != null) {
            // 连接目标节点，连接成功后发送 MEET 消息
            // 使用临时节点ID作为通道映射的key，PONG 响应时会替换为真实节点ID
            ChannelFuture connectFuture = busClient.connect(tempNodeId, ip, port);
            connectFuture.addListener((ChannelFuture future) -> {
                if (future.isSuccess()) {
                    busClient.send(tempNodeId, meet);
                    if (stateManager != null) {
                        stateManager.incrementMessagesSent(1);
                    }
                } else {
                    logger.error("发送 MEET 消息失败: 无法连接到 {}:{}", ip, port, future.cause());
                }
            });
        }
    }

    /**
     * 根据IP和端口查找已存在的节点
     *
     * @param ip   节点IP
     * @param port 节点服务端口
     * @return 匹配的节点，未找到返回 null
     */
    private ClusterNode findNodeByAddress(String ip, int port) {
        for (ClusterNode node : clusterConfig.getAllNodes()) {
            if (ip.equals(node.getIp()) && port == node.getPort()) {
                return node;
            }
        }
        return null;
    }

    /**
     * 移除同地址的 HANDSHAKE 临时节点
     * <p>
     * 当通过 MEET/PONG/Gossip 获得节点的真实 ID 时，清理之前通过 sendMeet() 创建的
     * 临时 HANDSHAKE 节点（如果 MEET 连接失败，临时节点不会被 PONG 解析，需要在此清理）。
     * </p>
     *
     * @param ip           节点IP
     * @param port         节点服务端口
     * @param excludeNodeId 排除的节点ID（真实节点的ID，不会被移除）
     */
    private void removeHandshakeNodeByAddress(String ip, int port, String excludeNodeId) {
        for (ClusterNode node : clusterConfig.getAllNodes()) {
            if (node.hasState(ClusterNodeState.HANDSHAKE)
                    && !node.getNodeId().equals(excludeNodeId)
                    && ip.equals(node.getIp())
                    && port == node.getPort()) {
                clusterConfig.removeNode(node.getNodeId());
                logger.info("清理同地址的临时 HANDSHAKE 节点: tempNodeId={}, address={}:{}",
                        node.getNodeId(), ip, port);
            }
        }
    }

    /**
     * 处理 MEET 消息
     *
     * @param meet 收到的 MEET 消息
     */
    public void handleMeet(MeetMessage meet) {
        logger.info("收到 MEET 消息: from={}", meet.getSenderNodeId());

        if (stateManager != null) {
            stateManager.incrementMessagesReceived(1);
        }

        // 检查发送方节点是否已存在
        ClusterNode senderNode = clusterConfig.getNode(meet.getSenderNodeId());
        if (senderNode == null) {
            // 清理同地址的临时 HANDSHAKE 节点（MEET 连接失败时的残留）
            removeHandshakeNodeByAddress(meet.getSenderIp(), meet.getSenderPort(), meet.getSenderNodeId());

            // 创建新节点并添加到集群
            senderNode = new ClusterNode(
                    meet.getSenderNodeId(),
                    meet.getSenderIp(),
                    meet.getSenderPort(),
                    meet.getSenderBusPort()
            );
            senderNode.addState(ClusterNodeState.HANDSHAKE);
            clusterConfig.addNode(senderNode);
            logger.info("新节点加入集群: nodeId={}, address={}",
                    meet.getSenderNodeId(), senderNode.getFullAddress());

            // 拓扑变更：新节点通过 MEET 加入集群，触发 nodes.conf 持久化
            notifyTopologyChanged();

            // 建立到发送方的出站连接，确保双向 Gossip 通信
            if (busClient != null && !busClient.isConnected(meet.getSenderNodeId())) {
                logger.info("建立到 MEET 发送方的出站连接: nodeId={}, address={}:{}",
                        meet.getSenderNodeId(), meet.getSenderIp(), meet.getSenderPort());
                busClient.connect(meet.getSenderNodeId(), meet.getSenderIp(), meet.getSenderPort());
            }
        } else {
            // 已存在节点：若 MEET 消息中的地址与本地记录不一致（例如 CLUSTER MEET 用
            // 127.0.0.1 建连导致关联节点 IP 记录为 127.0.0.1，但真实节点通告 192.10.0.125），
            // 以消息中的通告地址为准。MEET 消息始终由发送方主动生成，其地址是发送方自身解析
            // 的权威地址，可作为 Gossip 地址收敛的唯一权威来源。
            String meetIp = meet.getSenderIp();
            int meetPort = meet.getSenderPort();
            int meetBusPort = meet.getSenderBusPort();
            if (!meetIp.equals(senderNode.getIp()) || meetPort != senderNode.getPort()
                    || meetBusPort != senderNode.getBusPort()) {
                logger.info("从 MEET 消息更新节点地址: nodeId={}, old={}, new={}:{}@{}",
                        meet.getSenderNodeId(), senderNode.getFullAddress(),
                        meetIp, meetPort, meetBusPort);
                senderNode.setIp(meetIp);
                senderNode.setPort(meetPort);
                senderNode.setBusPort(meetBusPort);

                // 同时更新出站连接（地址变更后旧通道可能不可用）
                if (busClient != null) {
                    busClient.connect(meet.getSenderNodeId(), meetIp, meetPort);
                }
            }
        }

        // 更新节点信息（包含握手完成处理）
        updateNodeFromMeetMessage(meet);

        // 处理 Gossip 信息
        processGossipNodes(meet.getGossipNodes(), meet.getSenderNodeId());
    }

    /**
     * 广播 FAIL 消息
     *
     * @param failedNodeId 故障节点ID
     */
    public void broadcastFail(String failedNodeId) {
        ClusterNode failedNode = clusterConfig.getNode(failedNodeId);
        if (failedNode == null) {
            logger.warn("无法广播 FAIL 消息: 节点不存在, nodeId={}", failedNodeId);
            return;
        }

        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            logger.warn("无法广播 FAIL 消息: 当前节点信息不存在");
            return;
        }

        FailMessage fail = new FailMessage(
                myNode.getNodeId(),
                failedNodeId,
                failedNode.getIp(),
                failedNode.getPort()
        );

        logger.info("广播 FAIL 消息: failedNodeId={}, failedNodeAddress={}",
                failedNodeId, failedNode.getAddress());

        if (busClient != null) {
            busClient.broadcast(fail);
        }
    }

    /**
     * 处理 FAIL 消息
     *
     * @param fail 收到的 FAIL 消息
     */
    public void handleFail(FailMessage fail) {
        logger.info("收到 FAIL 消息: failedNodeId={}, from={}",
                fail.getFailedNodeId(), fail.getSenderNodeId());

        // 校验发送方是已知节点（FAIL 消息应由达成多数共识的节点广播，
        // 拒绝未知/伪造发送方的 FAIL 声明，避免恶意节点随意标记他人下线）
        String senderNodeId = fail.getSenderNodeId();
        if (senderNodeId == null || clusterConfig.getNode(senderNodeId) == null) {
            logger.warn("收到 FAIL 消息但发送方未知，忽略: failedNodeId={}, sender={}",
                    fail.getFailedNodeId(), senderNodeId);
            return;
        }

        String failedNodeId = fail.getFailedNodeId();
        ClusterNode failedNode = clusterConfig.getNode(failedNodeId);

        if (failedNode == null) {
            logger.warn("收到 FAIL 消息但节点不存在: nodeId={}", failedNodeId);
            return;
        }

        // 标记节点为 FAIL 状态
        failedNode.addState(ClusterNodeState.FAIL);
        failedNode.removeState(ClusterNodeState.PFAIL);

        logger.info("节点已标记为 FAIL: nodeId={}", failedNodeId);

        // 拓扑变更：节点状态变为 FAIL，触发 nodes.conf 持久化
        notifyTopologyChanged();
    }

    /**
     * 处理故障转移授权请求（委托给 FailoverManager）。
     *
     * @param msg 授权请求消息
     */
    public void handleFailoverAuthRequest(FailoverAuthRequestMessage msg) {
        if (failoverManager != null) {
            failoverManager.onAuthRequest(msg);
        }
    }

    /**
     * 处理故障转移授权确认（委托给 FailoverManager）。
     *
     * @param msg 授权确认消息
     */
    public void handleFailoverAuthAck(FailoverAuthAckMessage msg) {
        if (failoverManager != null) {
            failoverManager.onAuthAck(msg);
        }
    }

    /**
     * 处理故障转移结果（委托给 FailoverManager）。
     *
     * @param msg 胜选结果消息
     */
    public void handleFailoverResult(FailoverResultMessage msg) {
        if (failoverManager != null) {
            failoverManager.onFailoverResult(msg);
        }
    }

    /**
     * 处理键迁移请求（MIGRATE_KEY）。
     * <p>
     * 目标节点收到键迁移请求后，调用 SlotMigrationManager.importKey 导入键，
     * 并返回 MIGRATE_KEY_ACK 给源节点。
     * </p>
     *
     * @param msg 键迁移请求消息
     * @return 键迁移确认消息（返回给源节点）
     */
    public MigrateKeyAckMessage handleMigrateKey(MigrateKeyMessage msg) {
        ClusterNode myNode = clusterConfig.getMyNode();
        String myNodeId = myNode != null ? myNode.getNodeId() : msg.getSenderNodeId();

        boolean success = false;
        String errorMessage = null;

        if (slotMigrationManager != null) {
            try {
                success = slotMigrationManager.importKey(msg.getKey(), msg.getValue(), msg.getTtl());
                if (!success) {
                    errorMessage = "importKey 失败：槽位未处于 IMPORTING 状态或导入异常";
                }
            } catch (Exception e) {
                errorMessage = "导入键异常: " + e.getMessage();
                logger.error("处理 MIGRATE_KEY 消息失败: key={}", msg.getKey(), e);
            }
        } else {
            errorMessage = "本节点未配置 SlotMigrationManager，无法导入键";
            logger.warn("收到 MIGRATE_KEY 但 SlotMigrationManager 未注入: key={}", msg.getKey());
        }

        return new MigrateKeyAckMessage(myNodeId, msg.getKey(), success, errorMessage);
    }

    /**
     * 处理跨节点 PUBLISH 消息。
     * <p>
     * 将消息投递给已注入的监听器（由 server 模块实现，负责转发到本地 PubSubManager）。
     * 未注入监听器时记录告警，不丢失消息。
     * </p>
     *
     * @param msg PUBLISH 消息
     */
    public void handlePublish(PublishMessage msg) {
        if (publishListener != null) {
            try {
                publishListener.onMessage(msg.getChannel(), msg.getMessage(), msg.getSenderNodeId());
            } catch (Exception e) {
                logger.error("处理 PUBLISH 消息失败: channel={}", msg.getChannel(), e);
            }
        } else {
            logger.debug("收到跨节点 PUBLISH 但未注入监听器，频道: {}", msg.getChannel());
        }
    }

    /**
     * 获取随机节点列表用于 Gossip
     *
     * @return Gossip 节点信息列表
     */
    public List<GossipNodeInfo> selectGossipNodes() {
        Collection<ClusterNode> allNodes = clusterConfig.getAllNodes();
        
        // 使用可复用的列表
        List<GossipNodeInfo> result = reusableGossipList.get();
        result.clear();
        
        // 快速过滤：如果节点数少于等于 GOSSIP_NODE_COUNT，直接处理
        if (allNodes.size() <= GOSSIP_NODE_COUNT) {
            for (ClusterNode node : allNodes) {
                if (!node.isMyself() && !node.isFail() && !node.hasState(ClusterNodeState.HANDSHAKE)) {
                    result.add(convertToGossipNodeInfo(node));
                }
            }
            return result;
        }

        // 使用数组避免多次遍历
        List<ClusterNode> candidateNodes = new ArrayList<>(allNodes.size());
        for (ClusterNode node : allNodes) {
            if (!node.isMyself() && !node.isFail() && !node.hasState(ClusterNodeState.HANDSHAKE)) {
                candidateNodes.add(node);
            }
        }

        // 随机选择节点
        Random random = randomProvider.get();
        Collections.shuffle(candidateNodes, random);
        int count = Math.min(GOSSIP_NODE_COUNT, candidateNodes.size());

        for (int i = 0; i < count; i++) {
            ClusterNode node = candidateNodes.get(i);
            result.add(convertToGossipNodeInfo(node));
        }

        return result;
    }

    /**
     * 更新节点最后通信时间
     *
     * @param nodeId 节点ID
     */
    public void updateNodeLastInteraction(String nodeId) {
        lastInteractionTimes.put(nodeId, System.currentTimeMillis());

        ClusterNode node = clusterConfig.getNode(nodeId);
        if (node != null && node.getLink() != null) {
            node.getLink().updateInteractionTime();
        }
    }

    /**
     * 获取节点最后通信时间
     *
     * @param nodeId 节点ID
     * @return 最后通信时间（毫秒），如果不存在返回 0
     */
    public long getNodeLastInteraction(String nodeId) {
        return lastInteractionTimes.getOrDefault(nodeId, 0L);
    }

    /**
     * 从 PING 消息更新节点信息
     * <p>
     * 如果节点处于 HANDSHAKE 状态，收到 PING 表示握手完成，
     * 移除 HANDSHAKE 标志并添加 MASTER 标志（默认为主节点）。
     * </p>
     */
    private void updateNodeFromPingMessage(PingMessage ping) {
        String senderNodeId = ping.getSenderNodeId();
        ClusterNode senderNode = clusterConfig.getNode(senderNodeId);

        if (senderNode != null) {
            // 握手完成：移除 HANDSHAKE，设置 MASTER
            completeHandshake(senderNode);

            senderNode.updateLastPongTime();
            ClusterLink link = senderNode.getLink();
            if (link != null) {
                link.setConnected(true);
                link.updateInteractionTime();
            }

            // 先捕获本地纪元基线，供 syncSenderRole 门控使用，
            // 避免本地纪元被先前消息提升后门控失效。
            long epochBaseline = senderNode.getConfigEpoch();

            // 同步发送方槽位归属（基于发送方已记录的配置纪元裁决冲突）
            clusterConfig.syncSlotsFromNode(senderNodeId, ping.getSenderSlots(),
                    senderNode.getConfigEpoch());

            // 同步发送方角色（master/slave）与 masterNodeId
            // selectGossipNodes 排除本节点，发送方自身角色只能通过消息头传播
            syncSenderRole(senderNode, ping.getSenderFlags(),
                    ping.getSenderMasterNodeId(), ping.getSenderConfigEpoch(), epochBaseline);

            // 同步集群级 currentEpoch（重启节点本地可能滞后，导致 epoch 仲裁门控失效）
            clusterConfig.setEpochIfGreater(ping.getSenderCurrentEpoch());
        }
    }

    /**
     * 从 PONG 消息更新节点信息
     * <p>
     * 如果节点处于 HANDSHAKE 状态，收到 PONG 表示握手完成，
     * 移除 HANDSHAKE 标志并添加 MASTER 标志（默认为主节点）。
     * </p>
     */
    private void updateNodeFromPongMessage(PongMessage pong) {
        String senderNodeId = pong.getSenderNodeId();
        ClusterNode senderNode = clusterConfig.getNode(senderNodeId);

        if (senderNode != null) {
            // 握手完成：移除 HANDSHAKE，设置 MASTER
            completeHandshake(senderNode);

            senderNode.updateLastPongTime();
            ClusterLink link = senderNode.getLink();
            if (link != null) {
                link.setConnected(true);
                link.updateInteractionTime();
            }

            // 先捕获本地纪元基线，供 syncSenderRole 门控使用，
            // 避免本地纪元被先前消息提升后门控失效。
            long epochBaseline = senderNode.getConfigEpoch();

            // 同步发送方槽位归属（基于发送方已记录的配置纪元裁决冲突）
            clusterConfig.syncSlotsFromNode(senderNodeId, pong.getSenderSlots(),
                    senderNode.getConfigEpoch());

            // 同步发送方角色（master/slave）与 masterNodeId
            syncSenderRole(senderNode, pong.getSenderFlags(),
                    pong.getSenderMasterNodeId(), pong.getSenderConfigEpoch(), epochBaseline);

            // 同步集群级 currentEpoch（重启节点本地可能滞后，导致 epoch 仲裁门控失效）
            clusterConfig.setEpochIfGreater(pong.getSenderCurrentEpoch());
        }
    }

    /**
     * 完成握手：移除 HANDSHAKE 状态，设置 MASTER 状态
     * <p>
     * 当收到 PING/PONG/MEET 响应时调用，表示节点握手已完成。
     * 默认将节点设为主节点，如果后续收到 REPLICATE 命令会改为从节点。
     * </p>
     *
     * @param node 要完成握手的节点
     */
    private void completeHandshake(ClusterNode node) {
        if (node.hasState(ClusterNodeState.HANDSHAKE)) {
            node.removeState(ClusterNodeState.HANDSHAKE);
            // 默认设为主节点（如果尚未设置 MASTER 或 SLAVE）
            if (!node.isMaster() && !node.isSlave()) {
                node.addState(ClusterNodeState.MASTER);
            }
            logger.info("握手完成: nodeId={}, address={}", node.getNodeId(), node.getFullAddress());
            // 拓扑变更：新节点完成握手，触发 nodes.conf 持久化
            notifyTopologyChanged();
        }
    }

    /**
     * 从 MEET 消息更新节点信息
     */
    private void updateNodeFromMeetMessage(MeetMessage meet) {
        String senderNodeId = meet.getSenderNodeId();
        ClusterNode senderNode = clusterConfig.getNode(senderNodeId);

        if (senderNode != null) {
            // 握手完成：移除 HANDSHAKE，设置 MASTER
            completeHandshake(senderNode);

            // 先捕获本地纪元基线，供 syncSenderRole 门控使用。
            // setConfigEpochIfGreater 会把本地纪元提升到 senderConfigEpoch，
            // 若在 syncSenderRole 内才读取 localEpoch，configEpoch>localEpoch 恒为 false，
            // 导致 slave 角色永不切换（回归缺陷）。
            long epochBaseline = senderNode.getConfigEpoch();
            senderNode.setConfigEpochIfGreater(meet.getSenderConfigEpoch());
            clusterConfig.setEpochIfGreater(meet.getCurrentEpoch());
            senderNode.updateLastPongTime();
            ClusterLink link = senderNode.getLink();
            if (link != null) {
                link.setConnected(true);
                link.updateInteractionTime();
            }

            // 同步发送方槽位归属（使用 MEET 携带的发送方配置纪元裁决冲突）
            clusterConfig.syncSlotsFromNode(senderNodeId, meet.getSenderSlots(),
                    meet.getSenderConfigEpoch());

            // 同步发送方角色（master/slave）与 masterNodeId
            syncSenderRole(senderNode, meet.getSenderFlags(),
                    meet.getSenderMasterNodeId(), meet.getSenderConfigEpoch(), epochBaseline);
        }
    }

    /**
     * 处理 Gossip 节点信息。
     * <p>
     * 除同步节点元数据与槽位归属外，还会把"消息发送方"对目标节点的 PFAIL 投票
     * 登记到 {@link FailureDetector}，使跨节点的 PFAIL 共识能够累积，
     * 从而让 {@link FailureDetector#isMajorityAgreed(String)} 在收到多数派投票后
     * 能将节点标记为 FAIL，进而触发自动故障转移。
     * </p>
     *
     * @param gossipNodes  Gossip 节点信息列表
     * @param senderNodeId 消息发送方节点 ID（作为 PFAIL 投票的投票人）
     */
    private void processGossipNodes(List<GossipNodeInfo> gossipNodes, String senderNodeId) {
        if (gossipNodes == null || gossipNodes.isEmpty()) {
            return;
        }

        for (GossipNodeInfo nodeInfo : gossipNodes) {
            String nodeId = nodeInfo.getNodeId();
            boolean isMyselfEntry = nodeId != null && nodeId.equals(clusterConfig.getMyNodeId());

            // MYSELF 走自降级分支（不再无条件跳过）：当对端对 MYSELF 的视图携带
            // 更高 configEpoch 且角色为 SLAVE 时，采纳该视图自降级为新主的 slave。
            // 这是故障转移后原主重启收敛的关键路径--FailoverResult 广播仅一次，
            // 重启节点错过后只能经 gossip 心跳的 epoch 仲裁自降级。
            if (isMyselfEntry) {
                handleMyselfGossipEntry(nodeInfo);
                continue;
            }

            ClusterNode node = clusterConfig.getNode(nodeId);

            if (node == null) {
                ClusterNode existingByAddr = findNodeByAddress(nodeInfo.getIp(), nodeInfo.getPort());
                if (existingByAddr != null) {
                    if (existingByAddr.hasState(ClusterNodeState.HANDSHAKE)
                            && !existingByAddr.getNodeId().equals(nodeId)) {
                        clusterConfig.removeNode(existingByAddr.getNodeId());
                        logger.info("清理同地址的临时 HANDSHAKE 节点: tempNodeId={}, realNodeId={}, address={}:{}",
                                existingByAddr.getNodeId(), nodeId, nodeInfo.getIp(), nodeInfo.getPort());
                    } else {
                        logger.debug("已存在同地址节点，跳过 Gossip 发现的节点: existingId={}, gossipId={}, address={}:{}",
                                existingByAddr.getNodeId(), nodeId, nodeInfo.getIp(), nodeInfo.getPort());
                        continue;
                    }
                }

                // 发现新节点，初始标记为 HANDSHAKE
                node = new ClusterNode(
                        nodeId,
                        nodeInfo.getIp(),
                        nodeInfo.getPort(),
                        nodeInfo.getBusPort()
                );
                node.addState(ClusterNodeState.HANDSHAKE);
                clusterConfig.addNode(node);
                logger.info("通过 Gossip 发现新节点: nodeId={}, address={}",
                        nodeId, node.getFullAddress());

                // 拓扑变更：通过 Gossip 发现新节点，触发 nodes.conf 持久化
                notifyTopologyChanged();

                // 主动发起总线连接并发送 MEET，推动握手完成。
                // 否则该节点会永久停留在 HANDSHAKE 状态，Gossip 拓扑无法收敛
                // （redis-cli --cluster create 会因此卡在 "Waiting for the cluster to join"）。
                initiateMeetForDiscoveredNode(node);
            }

            // 先捕获本地纪元基线，供后续角色切换门控使用。
            // setConfigEpochIfGreater 会把本地纪元提升到 gossipEpoch，
            // 若在角色判断处才读取 localEpoch，gossipEpoch>localEpoch 恒为 false，
            // 导致第三方节点角色永不切换（与 syncSenderRole 同类回归缺陷）。
            long epochBaseline = node.getConfigEpoch();

            // 更新配置纪元
            node.setConfigEpochIfGreater(nodeInfo.getConfigEpoch());

            // 处理状态标志
            // 角色切换/FAIL 标志需校验 configEpoch，避免陈旧 gossip 分片撤销故障转移
            // （对齐 Redis：角色与 FAIL 变更应通过 configEpoch 校验的消息传播）
            Set<ClusterNodeState> flags = nodeInfo.getFlags();
            long gossipEpoch = nodeInfo.getConfigEpoch();
            long localEpoch = epochBaseline;
            boolean gossipEpochAcceptable = gossipEpoch >= localEpoch;
            if (flags != null) {
                // FAIL 标志：仅当 gossip 纪元可接受时才应用，避免旧视图误标
                if (gossipEpochAcceptable && flags.contains(ClusterNodeState.FAIL)) {
                    node.addState(ClusterNodeState.FAIL);
                    node.removeState(ClusterNodeState.PFAIL);
                }
                if (flags.contains(ClusterNodeState.PFAIL)) {
                    if (!node.isFail()) {
                        node.addState(ClusterNodeState.PFAIL);
                    }
                } else if (node.isPfail() && !node.isFail()) {
                    // 发送方不认为该节点 PFAIL，传播此视图以清除本地 PFAIL
                    node.removeState(ClusterNodeState.PFAIL);
                }

                // 同步 MASTER/SLAVE 角色变更：仅当 gossip 纪元严格大于本地基线时才切换角色，
                // 相等时不切换，防止陈旧 gossip 把已提升的 master 翻回 slave（撤销故障转移）
                if (gossipEpoch > localEpoch) {
                    if (flags.contains(ClusterNodeState.MASTER) && node.isSlave()) {
                        node.removeState(ClusterNodeState.SLAVE);
                        node.addState(ClusterNodeState.MASTER);
                        node.setMasterNodeId(null);
                        // 角色切换为 MASTER 后立即按 epoch 仲裁对齐其声明的 slots，
                        // 消除"已是 MASTER 但 slots 仍归属旧 owner"的中间视图（双 master 观感根源）。
                        // 对齐 Redis Rule 2：仅当声明方 configEpoch 严格大于当前 owner 的 configEpoch 时才抢占。
                        clusterConfig.syncSlotsFromNode(nodeId, nodeInfo.getSlots(), nodeInfo.getConfigEpoch());
                    }
                    if (flags.contains(ClusterNodeState.SLAVE) && node.isMaster()) {
                        node.removeState(ClusterNodeState.MASTER);
                        node.addState(ClusterNodeState.SLAVE);
                        node.setMasterNodeId(nodeInfo.getMasterNodeId());
                        // slave 不得持有 slots（对齐 Redis：replica 广播其 master 的 slots bitmap，自身不持 slot）。
                        node.clearSlots();
                    }
                }

                // 同步 masterNodeId（从节点的主节点关系），
                // 使 Gossip 成为 FailoverResult 丢包时的完整后备收敛机制
                if (gossipEpoch >= localEpoch && nodeInfo.getMasterNodeId() != null && node.isSlave()) {
                    node.setMasterNodeId(nodeInfo.getMasterNodeId());
                }
            }

            // 同步该节点拥有的槽位归属（基于其配置纪元裁决冲突）
            clusterConfig.syncSlotsFromNode(nodeId, nodeInfo.getSlots(), nodeInfo.getConfigEpoch());

            // 将发送方对该节点的 PFAIL 投票登记到故障检测器，用于跨节点 FAIL 共识
            failureDetector.processGossipPfailVote(nodeInfo, senderNodeId);
        }
    }

    /**
     * 处理 gossip section 中关于 MYSELF 的条目
     * <p>
     * 当对端节点对 MYSELF 的视图携带严格更大的 configEpoch 且角色为 SLAVE 时，
     * 触发 MYSELF 自降级为新主的 slave。严格 epoch 门控（>localEpochBaseline）
     * 防止陈旧 gossip 回退已合法提升的 master。
     * </p>
     *
     * @param nodeInfo gossip section 中 MYSELF 的条目
     */
    private void handleMyselfGossipEntry(GossipNodeInfo nodeInfo) {
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            return;
        }
        Set<ClusterNodeState> flags = nodeInfo.getFlags();
        if (flags == null || !flags.contains(ClusterNodeState.SLAVE)) {
            return;
        }
        // 捕获本地基线，避免 setConfigEpochIfGreater 提升后门控失效
        long localEpochBaseline = myNode.getConfigEpoch();
        long gossipEpoch = nodeInfo.getConfigEpoch();
        if (gossipEpoch <= localEpochBaseline) {
            // 严格大于才切换；相等/小于忽略（防回退）
            return;
        }
        if (!myNode.isMaster()) {
            // 已是 slave 则幂等跳过（masterNodeId 同步由下方逻辑处理）
            return;
        }
        String newMasterId = nodeInfo.getMasterNodeId();
        if (newMasterId == null) {
            return;
        }
        // 委托 FailoverManager 原子完成自降级（synchronized，与 onFailoverResult 串行）
        if (failoverManager != null) {
            failoverManager.applySelfDemotion(newMasterId, gossipEpoch);
        } else {
            logger.warn("FailoverManager 未注入，MYSELF 自降级未执行: newMasterId={}", newMasterId);
        }
    }

    /**
     * 对通过 Gossip 发现的 HANDSHAKE 节点发起 MEET 握手
     * <p>
     * 与 {@link #sendMeet(String, int)} 不同，此处目标节点已通过 Gossip 携带的真实节点ID
     * 加入本地配置（HANDSHAKE 状态），因此直接以该真实节点ID建连并发送 MEET，
     * 无需再生成临时ID并依赖 PONG 响应替换。
     * </p>
     * <p>
     * 幂等保护：
     * <ul>
     *   <li>已存在连接时跳过（由 {@link ClusterBusClient#isConnected(String)} 判断）；</li>
     *   <li>{@link ClusterBusClient#connect(String, String, int)} 在通道活跃时复用现有连接。</li>
     * </ul>
     * </p>
     *
     * @param node 新发现的 HANDSHAKE 节点（已携带真实节点ID与地址）
     */
    public void initiateMeetForDiscoveredNode(ClusterNode node) {
        if (busClient == null) {
            return;
        }

        // 已有连接则无需再次 MEET
        if (busClient.isConnected(node.getNodeId())) {
            return;
        }

        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            logger.warn("无法对 Gossip 发现的节点发起 MEET: 当前节点信息不存在");
            return;
        }

        logger.info("通过 Gossip 发现新节点并发起 MEET: nodeId={}, address={}",
                node.getNodeId(), node.getFullAddress());

        MeetMessage meet = new MeetMessage();
        meet.setSenderNodeId(myNode.getNodeId());
        meet.setSenderIp(myNode.getIp());
        meet.setSenderPort(myNode.getPort());
        meet.setSenderBusPort(myNode.getBusPort());
        meet.setSenderConfigEpoch(myNode.getConfigEpoch());
        meet.setCurrentEpoch(clusterConfig.getCurrentEpoch());
        // 携带发送方槽位，使对端能同步槽位归属
        meet.setSenderSlots(myNode.getSlots());
        // 携带发送方角色与 masterNodeId，使对端能同步发送方的 master/slave 角色
        meet.setSenderFlags(buildSenderFlags(myNode));
        meet.setSenderMasterNodeId(myNode.getMasterNodeId());

        // 携带 Gossip 节点信息，便于对端同步拓扑
        List<GossipNodeInfo> gossipNodes = selectGossipNodes();
        for (GossipNodeInfo gossipNode : gossipNodes) {
            meet.addGossipNode(gossipNode);
        }

        // 以真实节点ID建连，连接成功后发送 MEET
        ChannelFuture connectFuture = busClient.connect(node.getNodeId(), node.getIp(), node.getPort());
        connectFuture.addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                busClient.send(node.getNodeId(), meet);
                if (stateManager != null) {
                    stateManager.incrementMessagesSent(1);
                }
            } else {
                logger.error("对 Gossip 发现的节点发送 MEET 失败: nodeId={}, address={}",
                        node.getNodeId(), node.getFullAddress(), future.cause());
            }
        });
    }

    /**
     * 构建发送方角色状态标志集合，用于在 PING/PONG/MEET 消息头中携带发送方角色。
     *
     * @param node 发送方节点（myNode）
     * @return 角色状态标志集合（MASTER/SLAVE），不含 MYSELF/HANDSHAKE 等本地态
     */
    private Set<ClusterNodeState> buildSenderFlags(ClusterNode node) {
        Set<ClusterNodeState> flags = EnumSet.noneOf(ClusterNodeState.class);
        if (node.isMaster()) {
            flags.add(ClusterNodeState.MASTER);
        }
        if (node.isSlave()) {
            flags.add(ClusterNodeState.SLAVE);
        }
        return flags;
    }

    /**
     * 基于消息头携带的发送方角色信息同步本地对发送方节点的角色视图。
     * <p>
     * 与 {@link #processGossipNodes} 中同步第三方节点角色的策略一致：
     * <ul>
     *   <li>仅当 {@code senderConfigEpoch > localEpochBaseline} 时切换发送方角色（MASTER↔SLAVE），
     *       防止陈旧消息回退已完成的故障转移；</li>
     *   <li>仅当 {@code senderConfigEpoch >= localEpochBaseline} 且发送方已是 slave 时同步 masterNodeId。</li>
     * </ul>
     * 这是修复 {@code CLUSTER REPLICATE} 后从节点角色无法经 Gossip 传播的关键：
     * {@code selectGossipNodes} 排除本节点，节点自身角色只能通过消息头传播。
     * </p>
     * <p>
     * <b>纪元基线语义</b>：{@code localEpochBaseline} 必须是调用方在
     * {@link ClusterNode#setConfigEpochIfGreater(long)} 之前捕获的本地纪元快照。
     * 否则若先执行 {@code setConfigEpochIfGreater} 把本地纪元提升到与消息纪元相等，
     * {@code senderConfigEpoch > localEpoch} 会恒为 false，角色切换永不发生
     * （回归缺陷：slave 经 MEET/PING/PONG 无法被对端识别为 slave）。
     * </p>
     *
     * @param sender              发送方节点（本地视图）
     * @param flags               消息头携带的发送方角色标志
     * @param masterNodeId        消息头携带的发送方 masterNodeId，null 表示主节点或不适用
     * @param senderConfigEpoch   消息头携带的发送方配置纪元
     * @param localEpochBaseline  调用前捕获的发送方本地配置纪元快照（用于门控）
     */
    private void syncSenderRole(ClusterNode sender, Set<ClusterNodeState> flags,
                                String masterNodeId, long senderConfigEpoch,
                                long localEpochBaseline) {
        if (sender == null || flags == null) {
            return;
        }

        // 角色切换：仅当消息纪元严格大于本地基线时才切换，防止陈旧 gossip 回退已提升的 master
        if (senderConfigEpoch > localEpochBaseline) {
            if (flags.contains(ClusterNodeState.MASTER) && sender.isSlave()) {
                sender.removeState(ClusterNodeState.SLAVE);
                sender.addState(ClusterNodeState.MASTER);
                sender.setMasterNodeId(null);
            }
            if (flags.contains(ClusterNodeState.SLAVE) && sender.isMaster()) {
                sender.removeState(ClusterNodeState.MASTER);
                sender.addState(ClusterNodeState.SLAVE);
                sender.setMasterNodeId(masterNodeId);
            }
        }

        // 同步 masterNodeId：纪元可接受、携带了 masterNodeId 且节点已是 slave 时才同步
        if (senderConfigEpoch >= localEpochBaseline && masterNodeId != null && sender.isSlave()) {
            sender.setMasterNodeId(masterNodeId);
        }
    }

    /**
     * 将 ClusterNode 转换为 GossipNodeInfo
     *
     * @param node 集群节点
     * @return Gossip 节点信息
     */
    private GossipNodeInfo convertToGossipNodeInfo(ClusterNode node) {
        GossipNodeInfo info = new GossipNodeInfo();
        info.setNodeId(node.getNodeId());
        info.setIp(node.getIp());
        info.setPort(node.getPort());
        info.setBusPort(node.getBusPort());
        info.setConfigEpoch(node.getConfigEpoch());

        // 构建状态标志集合（使用 EnumSet 提高性能）
        Set<ClusterNodeState> flags = EnumSet.noneOf(ClusterNodeState.class);
        if (node.isMaster()) {
            flags.add(ClusterNodeState.MASTER);
        }
        if (node.isSlave()) {
            flags.add(ClusterNodeState.SLAVE);
        }
        if (node.isFail()) {
            flags.add(ClusterNodeState.FAIL);
        }
        if (node.isPfail()) {
            flags.add(ClusterNodeState.PFAIL);
        }
        info.setFlags(flags);

        // 携带节点拥有的槽位集合，使对端能同步槽位归属
        info.setSlots(node.getSlots());

        // 携带 masterNodeId，使对端能同步 master-slave 关系
        //（作为 FailoverResult 丢包时的后备收敛机制）
        info.setMasterNodeId(node.getMasterNodeId());

        return info;
    }

    /**
     * 获取故障检测器
     *
     * @return 故障检测器
     */
    public FailureDetector getFailureDetector() {
        return failureDetector;
    }

    /**
     * 检查是否已启动
     *
     * @return 是否已启动
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * 获取节点超时时间
     *
     * @return 节点超时时间（毫秒）
     */
    public long getNodeTimeout() {
        return nodeTimeout;
    }

    /**
     * 获取集群配置
     *
     * @return 集群配置
     */
    public ClusterConfig getClusterConfig() {
        return clusterConfig;
    }

    /**
     * 如果集群配置脏了（有未持久化的拓扑变更），触发保存
     * <p>
     * 参照 Redis 7 serverCron 中 clusterSaveConfig 的周期性检查机制：
     * 由 GossipTask 在每个周期调用，确保拓扑变更最终被持久化。
     * </p>
     */
    public void saveClusterConfigIfNeeded() {
        if (clusterConfig.isDirty() && onTopologyChanged != null) {
            onTopologyChanged.run();
        }
    }

    /**
     * 通知拓扑变更（触发 nodes.conf 持久化）
     * <p>
     * 参照 Redis 7 clusterSaveConfigIfNeeded 机制：
     * 通过 ClusterConfig.markDirty() 标记脏状态，由后台 GossipTask 定期检查并触发持久化。
     * </p>
     */
    private void notifyTopologyChanged() {
        clusterConfig.markDirty();
        if (onTopologyChanged != null) {
            onTopologyChanged.run();
        }
    }
}
