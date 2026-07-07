package com.janeluo.luban.rds.client.cli;

import com.janeluo.luban.rds.client.NettyRedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code --cluster create} 命令的编排实现
 * <p>
 * 通过 RESP 远程调用各节点上已实现的 {@code CLUSTER} 子命令完成集群搭建：
 * </p>
 * <ol>
 *   <li>探测每个节点的 {@code CLUSTER MYID}</li>
 *   <li>以首节点为锚点，对其它节点发送 {@code CLUSTER MEET} 组建拓扑</li>
 *   <li>将 16384 个槽位均分给 master 节点（{@code CLUSTER ADDSLOTS}）</li>
 *   <li>对从节点发送 {@code CLUSTER REPLICATE} 配置主从</li>
 *   <li>校验 {@code CLUSTER INFO} 状态</li>
 * </ol>
 *
 * @author janeluo
 * @since 1.0.0
 */
public class ClusterSetupCommand {

    private static final Logger logger = LoggerFactory.getLogger(ClusterSetupCommand.class);

    /**
     * Redis 集群槽位总数
     */
    public static final int CLUSTER_SLOTS = 16384;

    /**
     * MEET 后等待 gossip 传播的单次间隔（毫秒）
     */
    private static final long MEET_POLL_INTERVAL_MS = 500L;

    /**
     * MEET 后最长等待 gossip 传播的总时间（毫秒）
     */
    private static final long MEET_POLL_TOTAL_MS = 5000L;

    /**
     * 最后校验前的等待时间（毫秒）
     */
    private static final long FINAL_CHECK_DELAY_MS = 2000L;

    private final List<NodeAddress> nodes;
    private final int replicas;

    /**
     * @param nodes    全部节点地址列表，前 {@code masters} 个为主节点，其余为从节点
     * @param replicas 每个主节点的从节点数量（≥0）
     */
    public ClusterSetupCommand(List<NodeAddress> nodes, int replicas) {
        this.nodes = nodes;
        this.replicas = replicas;
    }

    /**
     * 执行集群创建
     *
     * @throws ClusterSetupException 任一步骤失败时抛出
     */
    public void execute() {
        int total = nodes.size();
        int masters = total / (1 + replicas);
        if (masters <= 0) {
            throw new ClusterSetupException("节点数量不足以构成集群: total=" + total + ", replicas=" + replicas);
        }

        Map<NodeAddress, String> nodeIdMap = new LinkedHashMap<>();
        List<String> masterIds = new ArrayList<>();

        System.out.println(">>> Performing hash slots allocation on " + total + " nodes...");
        printAllocationPlan(masters);

        // 1. 探测每个节点的 nodeId
        System.out.println(">>> Fetching node ids from each node...");
        fetchNodeIds(nodeIdMap, masterIds, masters);

        // 2. MEET 组建拓扑
        System.out.println(">>> Trying to connect each node via CLUSTER MEET ...");
        meetAllNodes(nodeIdMap);
        System.out.println("[OK] All nodes joined the cluster.");

        // 3. 分配槽位
        System.out.println(">>> Assign slots to masters ...");
        assignSlots(masterIds);
        System.out.println("[OK] All slots assigned.");

        // 4. 配置从节点
        if (replicas > 0) {
            System.out.println(">>> Configure replicas ...");
            configureReplicas(nodeIdMap, masterIds, masters);
            System.out.println("[OK] All replicas configured.");
        }

        // 5. 校验
        System.out.println(">>> Check cluster state ...");
        verifyCluster(nodeIdMap);
        System.out.println("[OK] All " + CLUSTER_SLOTS + " slots covered.");
    }

    /**
     * 打印槽位分配方案
     */
    private void printAllocationPlan(int masters) {
        int[] slotRanges = computeSlotRanges(masters);
        List<List<Integer>> replicaGroups = computeReplicaGroups(masters);

        for (int i = 0; i < masters; i++) {
            int start = slotRanges[i * 2];
            int end = slotRanges[i * 2 + 1];
            System.out.println("Master[" + i + "] -> " + nodes.get(i) + " (slots " + start + "-" + end + ")");
        }
        if (replicas > 0) {
            for (int i = 0; i < masters; i++) {
                for (int replicaIdx : replicaGroups.get(i)) {
                    System.out.println("Adding replica " + nodes.get(replicaIdx) + " to " + nodes.get(i));
                }
            }
        }
    }

    /**
     * 探测每个节点的 nodeId
     */
    private void fetchNodeIds(Map<NodeAddress, String> nodeIdMap, List<String> masterIds, int masters) {
        for (int i = 0; i < nodes.size(); i++) {
            NodeAddress addr = nodes.get(i);
            String nodeId = fetchNodeId(addr);
            nodeIdMap.put(addr, nodeId);
            if (i < masters) {
                masterIds.add(nodeId);
            }
            System.out.println("  " + addr + " -> " + nodeId);
        }
    }

    /**
     * 获取单个节点的 nodeId（CLUSTER MYID）
     */
    private String fetchNodeId(NodeAddress addr) {
        NettyRedisClient client = new NettyRedisClient(addr.getHost(), addr.getPort());
        try {
            client.connect();
            if (!client.isConnected()) {
                throw new ClusterSetupException("无法连接到节点 " + addr + "（请确认服务已启动且 cluster-enabled yes）");
            }
            Object reply = client.executeCommand("CLUSTER", "MYID");
            String nodeId = ReplySupport.requireString(reply, "CLUSTER MYID " + addr);
            if (nodeId.length() != 40) {
                throw new ClusterSetupException(
                        "节点 " + addr + " 返回的 nodeId 长度异常: " + nodeId + "（期望 40 位 hex）");
            }
            return nodeId;
        } finally {
            client.disconnect();
        }
    }

    /**
     * 以首节点为锚点，向其发送 CLUSTER MEET 加入其它节点
     */
    private void meetAllNodes(Map<NodeAddress, String> nodeIdMap) {
        NodeAddress anchor = nodes.get(0);
        NettyRedisClient client = new NettyRedisClient(anchor.getHost(), anchor.getPort());
        try {
            client.connect();
            if (!client.isConnected()) {
                throw new ClusterSetupException("无法连接到锚点节点 " + anchor);
            }
            for (int i = 1; i < nodes.size(); i++) {
                NodeAddress target = nodes.get(i);
                Object reply = client.executeCommand("CLUSTER", "MEET", target.getHost(),
                        String.valueOf(target.getPort()));
                ReplySupport.assertOk(reply, "CLUSTER MEET " + target);
            }
            // 等待 gossip 传播，轮询 CLUSTER NODES 确认所有节点都被识别
            waitForNodesPropagation(client, nodeIdMap.size());
        } finally {
            client.disconnect();
        }
    }

    /**
     * 轮询 CLUSTER NODES，确认已知节点数量达到预期
     */
    private void waitForNodesPropagation(NettyRedisClient client, int expectedCount) {
        long deadline = System.currentTimeMillis() + MEET_POLL_TOTAL_MS;
        while (System.currentTimeMillis() < deadline) {
            Object reply = client.executeCommand("CLUSTER", "NODES");
            String nodesText = ReplySupport.requireString(reply, "CLUSTER NODES");
            int known = countKnownNodes(nodesText);
            if (known >= expectedCount) {
                return;
            }
            sleep(MEET_POLL_INTERVAL_MS);
        }
        logger.warn("MEET 后 gossip 传播未在 {}ms 内完成，继续后续步骤", MEET_POLL_TOTAL_MS);
    }

    /**
     * 统计 CLUSTER NODES 输出中的有效节点行数
     */
    private static int countKnownNodes(String nodesText) {
        if (nodesText == null || nodesText.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : nodesText.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * 为每个 master 分配槽位（CLUSTER ADDSLOTS）
     */
    private void assignSlots(List<String> masterIds) {
        int masters = masterIds.size();
        int[][] ranges = computeSlotRangesForMasters(masters);
        // 期望为每组 master 构造 ADDSLOTS 的参数列表
        for (int i = 0; i < masters; i++) {
            int start = ranges[i][0];
            int end = ranges[i][1];
            NodeAddress addr = nodes.get(i);
            String[] addslotsArgs = buildCommandArgs("ADDSLOTS", start, end);
            NettyRedisClient client = new NettyRedisClient(addr.getHost(), addr.getPort());
            try {
                client.connect();
                if (!client.isConnected()) {
                    throw new ClusterSetupException("无法连接到主节点 " + addr + " 分配槽位");
                }
                Object reply = client.executeCommand("CLUSTER", addslotsArgs);
                ReplySupport.assertOk(reply, "CLUSTER ADDSLOTS on " + addr + " (slots " + start + "-" + end + ")");
            } finally {
                client.disconnect();
            }
        }
    }

    /**
     * 构造 CLUSTER 子命令的完整参数数组（子命令名 + 槽位号）
     *
     * @param subcommand 子命令名，如 "ADDSLOTS"
     * @param start      起始槽位（含）
     * @param end        结束槽位（含）
     * @return 参数数组，可作为 {@code executeCommand} 的 varargs 传入
     */
    private static String[] buildCommandArgs(String subcommand, int start, int end) {
        String[] args = new String[end - start + 2];
        args[0] = subcommand;
        for (int i = start; i <= end; i++) {
            args[i - start + 1] = String.valueOf(i);
        }
        return args;
    }

    /**
     * 为每个从节点配置主节点（CLUSTER REPLICATE）
     */
    private void configureReplicas(Map<NodeAddress, String> nodeIdMap, List<String> masterIds, int masters) {
        List<List<Integer>> replicaGroups = computeReplicaGroups(masters);
        for (int m = 0; m < masters; m++) {
            String masterId = masterIds.get(m);
            for (int replicaIdx : replicaGroups.get(m)) {
                NodeAddress replicaAddr = nodes.get(replicaIdx);
                NettyRedisClient client = new NettyRedisClient(replicaAddr.getHost(), replicaAddr.getPort());
                try {
                    client.connect();
                    if (!client.isConnected()) {
                        throw new ClusterSetupException("无法连接到从节点 " + replicaAddr + " 配置复制");
                    }
                    Object reply = client.executeCommand("CLUSTER", "REPLICATE", masterId);
                    ReplySupport.assertOk(reply,
                            "CLUSTER REPLICATE on " + replicaAddr + " -> " + masterId);
                } finally {
                    client.disconnect();
                }
            }
        }
    }

    /**
     * 校验集群状态：CLUSTER INFO 中 cluster_state=ok 且 slots_assigned=16384
     */
    private void verifyCluster(Map<NodeAddress, String> nodeIdMap) {
        sleep(FINAL_CHECK_DELAY_MS);
        NodeAddress anchor = nodes.get(0);
        NettyRedisClient client = new NettyRedisClient(anchor.getHost(), anchor.getPort());
        try {
            client.connect();
            if (!client.isConnected()) {
                throw new ClusterSetupException("无法连接到节点 " + anchor + " 校验集群状态");
            }
            Object infoReply = client.executeCommand("CLUSTER", "INFO");
            String info = ReplySupport.requireString(infoReply, "CLUSTER INFO");
            Map<String, String> infoMap = parseClusterInfo(info);
            String state = infoMap.get("cluster_state");
            String slotsAssigned = infoMap.get("cluster_slots_assigned");
            System.out.println("cluster_state:" + state);
            System.out.println("cluster_slots_assigned:" + slotsAssigned);

            if (!"ok".equals(state)) {
                throw new ClusterSetupException("集群状态不为 ok: " + state);
            }
            int assigned = Integer.parseInt(slotsAssigned);
            if (assigned != CLUSTER_SLOTS) {
                throw new ClusterSetupException(
                        "已分配槽位数不为 " + CLUSTER_SLOTS + ": " + assigned);
            }
        } finally {
            client.disconnect();
        }
    }

    /**
     * 解析 CLUSTER INFO 文本为 key->value 映射
     */
    private static Map<String, String> parseClusterInfo(String info) {
        Map<String, String> map = new HashMap<>();
        for (String line : info.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && colon < line.length() - 1) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * 计算每个 master 的槽位范围 [start, end]
     * <p>
     * 与 redis-cli 一致：整数除法均分，余数依次补给前几个 master。
     * </p>
     *
     * @param masters 主节点数量
     * @return 二维数组，{@code ranges[i] = {start, end}}
     */
    public static int[][] computeSlotRangesForMasters(int masters) {
        int[][] ranges = new int[masters][2];
        int base = CLUSTER_SLOTS / masters;
        int remainder = CLUSTER_SLOTS % masters;
        int cursor = 0;
        for (int i = 0; i < masters; i++) {
            int count = base + (i < remainder ? 1 : 0);
            int start = cursor;
            int end = cursor + count - 1;
            ranges[i][0] = start;
            ranges[i][1] = end;
            cursor = end + 1;
        }
        return ranges;
    }

    /**
     * 计算槽位范围的扁平数组，便于打印
     *
     * @param masters 主节点数量
     * @return 长度为 {@code masters * 2} 的数组，{@code [m0start, m0end, m1start, m1end, ...]}
     */
    public static int[] computeSlotRanges(int masters) {
        int[][] ranges = computeSlotRangesForMasters(masters);
        int[] flat = new int[masters * 2];
        for (int i = 0; i < masters; i++) {
            flat[i * 2] = ranges[i][0];
            flat[i * 2 + 1] = ranges[i][1];
        }
        return flat;
    }

    /**
     * 计算每个 master 对应的从节点在 {@link #nodes} 列表中的索引
     * <p>
     * 与 redis-cli 一致：master i 的第 j 个从节点位于
     * {@code masters + j * masters + i}，即交错排布而非连续。
     * </p>
     *
     * @param masters 主节点数量
     * @return 每个主节点对应的从节点索引列表
     */
    public List<List<Integer>> computeReplicaGroups(int masters) {
        List<List<Integer>> groups = new ArrayList<>(masters);
        for (int i = 0; i < masters; i++) {
            groups.add(new ArrayList<>());
        }
        if (replicas <= 0) {
            return groups;
        }
        for (int r = 0; r < replicas; r++) {
            for (int m = 0; m < masters; m++) {
                int replicaIdx = masters + r * masters + m;
                if (replicaIdx < nodes.size()) {
                    groups.get(m).add(replicaIdx);
                }
            }
        }
        return groups;
    }

    /**
     * 主节点数量计算
     *
     * @param total    节点总数
     * @param replicas 每主节点从节点数
     * @return 主节点数量
     */
    public static int computeMasterCount(int total, int replicas) {
        if (replicas < 0) {
            throw new ClusterSetupException("replicas 不能为负数: " + replicas);
        }
        int divisor = 1 + replicas;
        if (total % divisor != 0) {
            throw new ClusterSetupException("节点总数 " + total + " 必须是 (1+replicas)=" + divisor
                    + " 的整数倍");
        }
        return total / divisor;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
