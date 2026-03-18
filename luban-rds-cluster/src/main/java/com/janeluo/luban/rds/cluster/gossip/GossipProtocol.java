package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterLink;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
     * 随机数生成器
     */
    private final Random random;

    /**
     * 是否已启动
     */
    private final AtomicBoolean started;

    /**
     * 节点最后通信时间记录
     */
    private final ConcurrentHashMap<String, Long> lastInteractionTimes;

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
        this.random = new Random();
        this.started = new AtomicBoolean(false);
        this.lastInteractionTimes = new ConcurrentHashMap<>();
    }

    /**
     * 启动 Gossip 协议
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            logger.info("启动 Gossip 协议, nodeTimeout={}ms, gossipInterval={}ms",
                    nodeTimeout, gossipInterval);

            // 启动定时 Gossip 任务
            GossipTask gossipTask = new GossipTask(this, failureDetector);
            scheduler.scheduleAtFixedRate(gossipTask, 0, gossipInterval, TimeUnit.MILLISECONDS);

            logger.info("Gossip 协议已启动");
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

        // 添加 Gossip 节点信息
        List<GossipNodeInfo> gossipNodes = selectGossipNodes();
        for (GossipNodeInfo gossipNode : gossipNodes) {
            ping.addGossipNode(gossipNode);
        }

        logger.debug("发送 PING 到节点: {}, gossipCount={}", node.getNodeId(), gossipNodes.size());

        if (busClient != null) {
            busClient.send(node.getNodeId(), ping);
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
        logger.debug("收到 PING 消息: from={}", ping.getSenderNodeId());

        // 更新发送方节点信息
        updateNodeFromPingMessage(ping);

        // 处理 Gossip 信息
        processGossipNodes(ping.getGossipNodes());

        // 创建 PONG 响应
        ClusterNode myNode = clusterConfig.getMyNode();
        PongMessage pong = new PongMessage(myNode.getNodeId(), System.currentTimeMillis());

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
        logger.debug("收到 PONG 消息: from={}", pong.getSenderNodeId());

        // 更新发送方节点信息
        updateNodeFromPongMessage(pong);

        // 处理 Gossip 信息
        processGossipNodes(pong.getGossipNodes());

        // 更新节点最后通信时间
        updateNodeLastInteraction(pong.getSenderNodeId());

        // 清除节点的 FAIL/PFAIL 状态
        failureDetector.clearNodeFailState(pong.getSenderNodeId());
    }

    /**
     * 发送 MEET 消息
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

        MeetMessage meet = new MeetMessage();
        meet.setSenderNodeId(myNode.getNodeId());
        meet.setSenderIp(myNode.getIp());
        meet.setSenderPort(myNode.getPort());
        meet.setSenderBusPort(myNode.getBusPort());
        meet.setSenderConfigEpoch(myNode.getConfigEpoch());
        meet.setCurrentEpoch(clusterConfig.getCurrentEpoch());

        // 添加 Gossip 节点信息
        List<GossipNodeInfo> gossipNodes = selectGossipNodes();
        for (GossipNodeInfo gossipNode : gossipNodes) {
            meet.addGossipNode(gossipNode);
        }

        logger.info("发送 MEET 消息: target={}:{}", ip, port);

        if (busClient != null) {
            // 先连接再发送
            String tempNodeId = "temp_" + System.currentTimeMillis();
            busClient.connect(tempNodeId, ip, port);
            // 稍后发送消息（实际实现需要等待连接建立）
        }
    }

    /**
     * 处理 MEET 消息
     *
     * @param meet 收到的 MEET 消息
     */
    public void handleMeet(MeetMessage meet) {
        logger.info("收到 MEET 消息: from={}", meet.getSenderNodeId());

        // 检查发送方节点是否已存在
        ClusterNode senderNode = clusterConfig.getNode(meet.getSenderNodeId());
        if (senderNode == null) {
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
        }

        // 更新节点信息
        updateNodeFromMeetMessage(meet);

        // 处理 Gossip 信息
        processGossipNodes(meet.getGossipNodes());
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
    }

    /**
     * 获取随机节点列表用于 Gossip
     *
     * @return Gossip 节点信息列表
     */
    public List<GossipNodeInfo> selectGossipNodes() {
        Collection<ClusterNode> allNodes = clusterConfig.getAllNodes();
        List<ClusterNode> candidateNodes = new ArrayList<>();

        // 过滤掉本节点和 FAIL 状态的节点
        for (ClusterNode node : allNodes) {
            if (!node.isMyself() && !node.isFail()) {
                candidateNodes.add(node);
            }
        }

        // 随机选择节点
        Collections.shuffle(candidateNodes, random);
        int count = Math.min(GOSSIP_NODE_COUNT, candidateNodes.size());

        List<GossipNodeInfo> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ClusterNode node = candidateNodes.get(i);
            GossipNodeInfo nodeInfo = convertToGossipNodeInfo(node);
            result.add(nodeInfo);
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
     */
    private void updateNodeFromPingMessage(PingMessage ping) {
        String senderNodeId = ping.getSenderNodeId();
        ClusterNode senderNode = clusterConfig.getNode(senderNodeId);

        if (senderNode != null) {
            senderNode.updateLastPongTime();
            ClusterLink link = senderNode.getLink();
            if (link != null) {
                link.setConnected(true);
                link.updateInteractionTime();
            }
        }
    }

    /**
     * 从 PONG 消息更新节点信息
     */
    private void updateNodeFromPongMessage(PongMessage pong) {
        String senderNodeId = pong.getSenderNodeId();
        ClusterNode senderNode = clusterConfig.getNode(senderNodeId);

        if (senderNode != null) {
            senderNode.updateLastPongTime();
            ClusterLink link = senderNode.getLink();
            if (link != null) {
                link.setConnected(true);
                link.updateInteractionTime();
            }
        }
    }

    /**
     * 从 MEET 消息更新节点信息
     */
    private void updateNodeFromMeetMessage(MeetMessage meet) {
        String senderNodeId = meet.getSenderNodeId();
        ClusterNode senderNode = clusterConfig.getNode(senderNodeId);

        if (senderNode != null) {
            senderNode.setConfigEpochIfGreater(meet.getSenderConfigEpoch());
            clusterConfig.setEpochIfGreater(meet.getCurrentEpoch());
            senderNode.updateLastPongTime();
            ClusterLink link = senderNode.getLink();
            if (link != null) {
                link.setConnected(true);
                link.updateInteractionTime();
            }
        }
    }

    /**
     * 处理 Gossip 节点信息
     *
     * @param gossipNodes Gossip 节点信息列表
     */
    private void processGossipNodes(List<GossipNodeInfo> gossipNodes) {
        if (gossipNodes == null || gossipNodes.isEmpty()) {
            return;
        }

        for (GossipNodeInfo nodeInfo : gossipNodes) {
            String nodeId = nodeInfo.getNodeId();
            ClusterNode node = clusterConfig.getNode(nodeId);

            // 跳过本节点
            if (nodeId != null && nodeId.equals(clusterConfig.getMyNodeId())) {
                continue;
            }

            if (node == null) {
                // 发现新节点
                node = new ClusterNode(
                        nodeId,
                        nodeInfo.getIp(),
                        nodeInfo.getPort(),
                        nodeInfo.getBusPort()
                );
                clusterConfig.addNode(node);
                logger.info("通过 Gossip 发现新节点: nodeId={}, address={}",
                        nodeId, node.getFullAddress());
            }

            // 更新配置纪元
            node.setConfigEpochIfGreater(nodeInfo.getConfigEpoch());

            // 处理状态标志
            Set<ClusterNodeState> flags = nodeInfo.getFlags();
            if (flags != null) {
                if (flags.contains(ClusterNodeState.FAIL)) {
                    node.addState(ClusterNodeState.FAIL);
                    node.removeState(ClusterNodeState.PFAIL);
                }
                if (flags.contains(ClusterNodeState.PFAIL)) {
                    if (!node.isFail()) {
                        node.addState(ClusterNodeState.PFAIL);
                    }
                }
            }
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

        // 构建状态标志集合
        Set<ClusterNodeState> flags = new HashSet<>();
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
}
