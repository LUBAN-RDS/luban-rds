package com.janeluo.luban.rds.mesh.client;

import com.janeluo.luban.rds.mesh.client.MeshClusterCommands.NodeInfo;
import com.janeluo.luban.rds.mesh.client.MeshClusterCommands.NodeRole;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeshClusterCommands} 单元测试（阶段 8 / DESIGN §5.6 场景 6）。
 * <p>
 * 验证点：
 * <ul>
 *   <li><b>CLUSTER SLOTS</b>：返回 {@code [[0, 16383, [ip, port, nodeId], []]]}；
 *       手工解析 RESP 嵌套数组，断言 startSlot=0/endSlot=16383/Leader 信息正确；
 *       无 Leader 时返回空数组 {@code *0\r\n}。</li>
 *   <li><b>CLUSTER NODES</b>：3 行，Leader 行 {@code myself,master} 持 {@code 0-16383}，
 *       Follower 行 {@code slave <leaderId>}；格式对齐 Redis CLUSTER NODES（行尾 {@code \n}）；
 *       bulk string 包装正确。</li>
 *   <li><b>CLUSTER INFO</b>：{@code cluster_state:ok}、{@code cluster_known_nodes:3}、
 *       {@code cluster_slots_ok:16384} 等关键字段；无 Leader 时 {@code cluster_state:fail}。</li>
 *   <li><b>RESP 字节格式</b>：手工解析返回的字节，验证 RESP 数组/bulk 结构与 Redis 协议规范一致。</li>
 *   <li><b>Leader 变更</b>：SLOTS/NODES 动态反映新 Leader（经 Supplier）。</li>
 * </ul>
 * </p>
 *
 * @author janeluo
 * @since 阶段 8
 */
class MeshClusterCommandsTest {

    // 测试用 nodeId（40 字符十六进制，对齐 Redis 标准）
    private static final String NODE_A = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_B = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_C = "c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    /** 构建标准 3 节点拓扑（A=初始 Leader，B/C=Follower）。 */
    private Map<String, NodeInfo> buildThreeNodes() {
        Map<String, NodeInfo> nodes = new LinkedHashMap<>();
        nodes.put(NODE_A, new NodeInfo(NODE_A, "192.168.1.1", 6379, 16379, NodeRole.LEADER));
        nodes.put(NODE_B, new NodeInfo(NODE_B, "192.168.1.2", 6380, 16380, NodeRole.FOLLOWER));
        nodes.put(NODE_C, new NodeInfo(NODE_C, "192.168.1.3", 6381, 16381, NodeRole.FOLLOWER));
        return nodes;
    }

    /** 构建一个固定 Leader（A）的 MeshClusterCommands。 */
    private MeshClusterCommands withLeaderA() {
        return new MeshClusterCommands(
                () -> NODE_A,
                () -> "192.168.1.1:6379",
                buildThreeNodes(),
                NODE_A);
    }

    // ==================== CLUSTER SLOTS ====================

    @Test
    void clusterSlots_returnsSingleRangeAllSlotsPointToLeader() {
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterSlots();
        String s = new String(resp, StandardCharsets.ISO_8859_1);

        // 外层 *1（1 个 slot range）
        assertTrue(s.startsWith("*1\r\n"), "SLOTS 应以 *1\\r\\n 开头（1 个 range）");
        // 内层 *4（startSlot, endSlot, masterInfo[3]）
        assertTrue(s.contains("*4\r\n"), "内层应为 *4\\r\\n（startSlot/endSlot/masterInfo[3]）");
        // startSlot=0, endSlot=16383
        assertTrue(s.contains(":0\r\n"), "startSlot 应为 :0");
        assertTrue(s.contains(":16383\r\n"), "endSlot 应为 :16383");
    }

    @Test
    void clusterSlots_masterEndpointFormatBulkIpIntegerPortBulkNodeId() {
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterSlots();
        String s = new String(resp, StandardCharsets.ISO_8859_1);

        // master endpoint: *3\r\n $<iplen>\r\n<ip>\r\n :<port>\r\n $<nodeIdLen>\r\n<nodeId>\r\n
        // ip 是 bulk string
        assertTrue(s.contains("$11\r\n192.168.1.1\r\n"), "ip 应为 bulk string $11\\r\\n192.168.1.1");
        // port 是 integer（对齐 luban-rds-cluster ClusterCommandHandler.appendNodeEndpoint）
        assertTrue(s.contains(":6379\r\n"), "port 应为 integer :6379");
        // nodeId 是 bulk string（40 字符）
        assertTrue(s.contains("$40\r\n" + NODE_A + "\r\n"), "nodeId 应为 bulk string $40");
    }

    @Test
    void clusterSlots_exactExpectedRespBytes() {
        // 完整 RESP 字节精确断言（Redis 7.0 格式：start/end/master/replicas）
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterSlots();

        String expected = "*1\r\n"
                + "*4\r\n"
                + ":0\r\n"
                + ":16383\r\n"
                + "*3\r\n"
                + "$11\r\n192.168.1.1\r\n"
                + ":6379\r\n"
                + "$40\r\n" + NODE_A + "\r\n"
                + "*0\r\n";
        byte[] expectedBytes = expected.getBytes(StandardCharsets.ISO_8859_1);
        assertArrayEquals(expectedBytes, resp, "SLOTS RESP 字节应精确匹配（含 replicas 空数组）");
    }

    /**
     * 回归：*4 声明 4 元素必须恰好发送 4 元素（start/end/master/replicas）。
     * 缺第 4 元素（旧 bug）会让 redis-cli/Lettuce/Jedis 的严格 RESP 解析器
     * 在拓扑刷新时永久等待——用行级结构验证元素数与字节边界齐全。
     */
    @Test
    void clusterSlots_strictParserConsumesEntireResponse() {
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterSlots();
        String s = new String(resp, StandardCharsets.ISO_8859_1);
        // 结构：*1 | *4 | :0 | :16383 | *3 | $11 | ip | :6379 | $40 | nodeId | *0
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String seg : s.split("\r\n")) {
            lines.add(seg);
        }
        assertEquals("*1", lines.get(0), "外层数组");
        assertEquals("*4", lines.get(1), "内层 4 元素头");
        assertEquals(11, lines.size(), "总行数 = 11（4 元素齐全）");
        assertEquals("*0", lines.get(lines.size() - 1), "末行应为 replicas 空数组");
        assertTrue(s.endsWith("\r\n"), "响应应以 CRLF 结尾");
    }

    @Test
    void clusterSlots_noLeader_returnsEmptyArray() {
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> null, () -> null, buildThreeNodes(), NODE_A);
        byte[] resp = cmd.clusterSlots();
        String s = new String(resp, StandardCharsets.ISO_8859_1);
        assertEquals("*0\r\n", s, "无 Leader 时 SLOTS 应返回空数组 *0\\r\\n");
    }

    @Test
    void clusterSlots_emptyLeaderAddr_fallsBackToNodeInfo() {
        // leaderAddr 为空但 leaderNodeId 已知且在 allNodes 中 → 回退 NodeInfo 的 ip/port
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> NODE_A, () -> "", buildThreeNodes(), NODE_A);
        String s = new String(cmd.clusterSlots(), StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("*1\r\n"), "空 addr 但有 nodeId 应回退 NodeInfo");
        assertTrue(s.contains("$11\r\n192.168.1.1\r\n"), "ip 应来自 NodeInfo");
        assertTrue(s.contains(":6379\r\n"), "port 应来自 NodeInfo");
    }

    @Test
    void clusterSlots_emptyLeaderAddrAndNodeIdNotInNodes_returnsEmptyArray() {
        // leaderAddr 空 + leaderNodeId 不在 allNodes → 无法补全，返回空数组
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> "unknown-node", () -> "", buildThreeNodes(), NODE_A);
        assertEquals("*0\r\n", new String(cmd.clusterSlots(), StandardCharsets.ISO_8859_1));
    }

    @Test
    void clusterSlots_leaderAddrOnly_fallsBackToNodeInfo() {
        // leaderAddr 为 null 但 leaderNodeId 已知且在 allNodes 中 → 回退用 NodeInfo 的 ip/port
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> NODE_A, () -> null, buildThreeNodes(), NODE_A);
        byte[] resp = cmd.clusterSlots();
        String s = new String(resp, StandardCharsets.ISO_8859_1);
        assertTrue(s.startsWith("*1\r\n"), "回退 NodeInfo 后应有 slot range");
        assertTrue(s.contains("$11\r\n192.168.1.1\r\n"), "ip 应来自 NodeInfo");
        assertTrue(s.contains(":6379\r\n"), "port 应来自 NodeInfo");
    }

    @Test
    void clusterSlots_reflectsLeaderChangeViaSupplier() {
        // Leader 从 A 切到 B：SLOTS 应反映新 Leader 的 ip/port/nodeId
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> NODE_B, () -> "192.168.1.2:6380", buildThreeNodes(), NODE_A);
        byte[] resp = cmd.clusterSlots();
        String s = new String(resp, StandardCharsets.ISO_8859_1);
        assertTrue(s.contains("$11\r\n192.168.1.2\r\n"), "Leader 变更后 ip 应为新 Leader 的 ip");
        assertTrue(s.contains(":6380\r\n"), "Leader 变更后 port 应为新 Leader 的 port");
        assertTrue(s.contains("$40\r\n" + NODE_B + "\r\n"), "nodeId 应为新 Leader 的 nodeId");
    }

    @Test
    void clusterSlots_dynamicSupplierReflectsMidLifecycleChange() {
        // 模拟运行时 Leader 切换：同一实例两次调用结果不同
        final String[] currentLeader = {NODE_A};
        final String[] currentAddr = {"192.168.1.1:6379"};
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> currentLeader[0], () -> currentAddr[0], buildThreeNodes(), NODE_A);

        String first = new String(cmd.clusterSlots(), StandardCharsets.ISO_8859_1);
        assertTrue(first.contains(NODE_A), "首次：Leader=A");

        // Leader 切换到 C
        currentLeader[0] = NODE_C;
        currentAddr[0] = "192.168.1.3:6381";
        String second = new String(cmd.clusterSlots(), StandardCharsets.ISO_8859_1);
        assertTrue(second.contains(NODE_C), "切换后：Leader=C");
        assertTrue(second.contains("192.168.1.3"), "切换后 ip 应为 C 的地址");
    }

    // ==================== CLUSTER NODES ====================

    @Test
    void clusterNodes_returnsBulkStringWithThreeLines() {
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterNodes();
        String s = new String(resp, StandardCharsets.ISO_8859_1);

        // bulk string 包装：$<len>\r\n...\r\n
        assertTrue(s.startsWith("$"), "NODES 应为 bulk string（$<len>\\r\\n 开头）");
        assertTrue(s.endsWith("\r\n"), "bulk string 应以 \\r\\n 结尾");

        // 提取 payload（去掉 $<len>\r\n 前缀和 \r\n 后缀）
        String payload = extractBulkPayload(s);

        // 3 行（按 \n 切分，最后一行末尾也有 \n → 末尾空串忽略）
        String[] lines = payload.split("\n", -1);
        assertEquals(4, lines.length, "应有 3 行 + 1 个末尾空串（因末尾 \\n）");
        // 实际非空行 = 3
        long nonEmpty = java.util.Arrays.stream(lines).filter(l -> !l.isEmpty()).count();
        assertEquals(3, nonEmpty, "应有 3 个非空节点行");
    }

    @Test
    void clusterNodes_leaderLineHasMyselfMasterAndSlots() {
        MeshClusterCommands cmd = withLeaderA();
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));

        // Leader 行（A）：myself,master 持 0-16383
        String leaderLine = findLineContaining(payload, NODE_A);
        assertNotNull(leaderLine, "应包含 Leader A 的行");
        assertTrue(leaderLine.contains("myself,master"), "Leader 行应有 myself,master 标志");
        assertTrue(leaderLine.contains(" 0-16383"), "Leader 行应持 0-16383");
        assertTrue(leaderLine.contains(" - "), "Leader 行的 masterId 字段应为 -");
        assertTrue(leaderLine.contains("192.168.1.1:6379@16379"), "Leader 行地址应为 ip:port@busPort");
    }

    @Test
    void clusterNodes_leaderLineEndsWithConnectedAndSlots() {
        // 单独验证行尾格式：... connected 0-16383\n
        MeshClusterCommands cmd = withLeaderA();
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));
        String leaderLine = findLineContaining(payload, NODE_A);
        assertTrue(leaderLine.endsWith("connected 0-16383"),
                "Leader 行应以 'connected 0-16383' 结尾，实际: " + leaderLine);
    }

    @Test
    void clusterNodes_followerLinesHaveSlaveAndLeaderId() {
        MeshClusterCommands cmd = withLeaderA();
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));

        // Follower 行（B、C）：slave <leaderNodeId>，不持 slot
        String followerB = findLineContaining(payload, NODE_B);
        String followerC = findLineContaining(payload, NODE_C);
        assertNotNull(followerB, "应包含 Follower B 的行");
        assertNotNull(followerC, "应包含 Follower C 的行");

        assertTrue(followerB.contains("slave"), "Follower B 行应有 slave 标志");
        assertTrue(followerB.contains(" " + NODE_A + " "), "Follower B 行的 masterId 应为 Leader A 的 nodeId");
        assertTrue(followerB.contains("192.168.1.2:6380@16380"), "Follower B 地址");
        assertTrue(followerB.endsWith("connected"), "Follower 行应以 connected 结尾，无 slot");

        assertTrue(followerC.contains("slave"), "Follower C 行应有 slave 标志");
        assertTrue(followerC.contains(" " + NODE_A + " "), "Follower C 行的 masterId 应为 Leader A 的 nodeId");
        assertTrue(followerC.endsWith("connected"), "Follower C 行应以 connected 结尾，无 slot");
    }

    @Test
    void clusterNodes_fullLeaderLineFormat() {
        // 完整行格式断言：<id> <ip:port@cport> <flags> <masterId> <ping> <pong> <epoch> <link> <slots>
        MeshClusterCommands cmd = withLeaderA();
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));
        String leaderLine = findLineContaining(payload, NODE_A);

        // 期望：a1b2... 192.168.1.1:6379@16379 myself,master - 0 0 1 connected 0-16383
        String expected =
                NODE_A + " 192.168.1.1:6379@16379 myself,master - 0 0 1 connected 0-16383";
        assertEquals(expected, leaderLine, "Leader 行应精确匹配 Redis CLUSTER NODES 格式");
    }

    @Test
    void clusterNodes_followerLineFormat() {
        MeshClusterCommands cmd = withLeaderA();
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));
        String followerB = findLineContaining(payload, NODE_B);

        // 期望：b1b2... 192.168.1.2:6380@16380 slave <leaderId> 0 0 1 connected
        String expected =
                NODE_B + " 192.168.1.2:6380@16380 slave " + NODE_A + " 0 0 1 connected";
        assertEquals(expected, followerB, "Follower 行应精确匹配 Redis CLUSTER NODES 格式");
    }

    @Test
    void clusterNodes_noCarriageReturnInPayload() {
        // 行尾必须为裸 \n，不得残留 \r（否则 Redisson split("\n") 后 slot 字段变 "0-16383\r" 抛异常）
        MeshClusterCommands cmd = withLeaderA();
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));
        assertEquals(-1, payload.indexOf('\r'),
                "NODES payload 不得包含 \\r（行尾必须裸 \\n）");
    }

    @Test
    void clusterNodes_noLeader_allMasterNoSlots() {
        // 无 Leader：3 节点都标 master（standalone，无主从关系），不持 slot
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> null, () -> null, buildThreeNodes(), NODE_A);
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));

        String[] lines = payload.split("\n", -1);
        long nonEmpty = java.util.Arrays.stream(lines).filter(l -> !l.isEmpty()).count();
        assertEquals(3, nonEmpty, "无 Leader 时仍应输出 3 节点行");

        // 无 Leader 时所有节点 masterId 字段为 "-"（不挂 masterId），无 slot
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            // flags 字段含 "master"（self 行为 "myself,master"，其余为 "master"）
            assertTrue(line.matches("^\\S+ \\S+ (myself,)?master .*"),
                    "无 Leader 时节点应标 master（或 myself,master），实际: " + line);
            assertTrue(line.contains(" - "), "无 Leader 时 masterId 字段应为 -");
            assertTrue(!line.contains("0-16383"), "无 Leader 时不应有 slot 分配");
        }
    }

    @Test
    void clusterNodes_reflectsLeaderChange() {
        // Leader 从 A 切到 B：B 行变 myself,master 持 slot，A/C 行变 slave
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> NODE_B, () -> "192.168.1.2:6380", buildThreeNodes(), NODE_B);
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));

        String newLeaderLine = findLineContaining(payload, NODE_B);
        assertTrue(newLeaderLine.contains("myself,master"), "新 Leader B 行应为 myself,master");
        assertTrue(newLeaderLine.contains(" 0-16383"), "新 Leader B 行应持 slot");

        String oldLeaderLine = findLineContaining(payload, NODE_A);
        assertTrue(oldLeaderLine.contains("slave"), "旧 Leader A 行应变 slave");
        assertTrue(oldLeaderLine.contains(" " + NODE_B + " "), "旧 Leader A 的 masterId 应为新 Leader B");
    }

    @Test
    void clusterNodes_busPortDefaultsToPortPlus10000WhenZero() {
        // NodeInfo.busPort <=0 时用 port+10000
        Map<String, NodeInfo> nodes = new LinkedHashMap<>();
        nodes.put(NODE_A, new NodeInfo(NODE_A, "10.0.0.1", 7000, 0, NodeRole.LEADER));
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> NODE_A, () -> "10.0.0.1:7000", nodes, NODE_A);
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));
        String line = findLineContaining(payload, NODE_A);
        assertTrue(line.contains("10.0.0.1:7000@17000"),
                "busPort=0 时应用 port+10000：10.0.0.1:7000@17000，实际: " + line);
    }

    // ==================== CLUSTER NODES 死节点标记（P2）====================

    @Test
    void clusterNodes_offlineNodeMarkedDisconnected() {
        Map<String, MeshClusterCommands.NodeInfo> nodes = buildThreeNodes();
        // node-C 离线
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> NODE_A, () -> "192.168.1.1:6379", nodes, NODE_A,
                id -> !NODE_C.equals(id));
        String s = new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1);
        assertTrue(s.contains(NODE_C + " 192.168.1.3:6381@16381 slave " + NODE_A
                        + " 0 0 1 disconnected"),
                "离线节点 linkState 应为 disconnected: " + s);
        assertTrue(s.contains("connected"), "在线节点仍应 connected");
        // 主节点槽位输出不受影响
        assertTrue(s.contains("0-16383"), "Leader 应仍持 0-16383");
    }

    @Test
    void clusterNodes_defaultPredicateAllConnected() {
        MeshClusterCommands cmd = withLeaderA(); // 旧构造器
        String payload = extractBulkPayload(new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1));
        assertEquals(3, payload.split("\n").length, "3 行节点");
        assertTrue(!payload.contains("disconnected"), "旧构造器默认全部 connected");
    }

    @Test
    void clusterNodes_selfAlwaysConnectedEvenWhenPredicateSaysOffline() {
        // 谓词恒 false（模拟出站链路全断），但 myself 行仍应 connected
        Map<String, MeshClusterCommands.NodeInfo> nodes = buildThreeNodes();
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> NODE_A, () -> "192.168.1.1:6379", nodes, NODE_A,
                id -> false);
        String s = new String(cmd.clusterNodes(), StandardCharsets.ISO_8859_1);
        assertTrue(s.contains(NODE_A + " 192.168.1.1:6379@16379 myself,master - 0 0 1 connected"),
                "myself 行应恒 connected: " + s);
        assertTrue(s.contains("disconnected"), "其他节点按谓词标 disconnected");
    }

    // ==================== CLUSTER INFO ====================

    @Test
    void clusterInfo_returnsBulkStringWithKeyFields() {
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterInfo();
        String s = new String(resp, StandardCharsets.ISO_8859_1);

        assertTrue(s.startsWith("$"), "INFO 应为 bulk string");
        assertTrue(s.endsWith("\r\n"), "bulk string 应以 \\r\\n 结尾");

        String payload = extractBulkPayload(s);
        assertTrue(payload.contains("cluster_enabled:1\r\n"), "应有 cluster_enabled:1");
        assertTrue(payload.contains("cluster_state:ok\r\n"), "有 Leader 时 cluster_state:ok");
        assertTrue(payload.contains("cluster_slots_assigned:16384\r\n"), "slots_assigned=16384");
        assertTrue(payload.contains("cluster_slots_ok:16384\r\n"), "slots_ok=16384");
        assertTrue(payload.contains("cluster_slots_pfail:0\r\n"), "slots_pfail=0");
        assertTrue(payload.contains("cluster_slots_fail:0\r\n"), "slots_fail=0");
        assertTrue(payload.contains("cluster_known_nodes:3\r\n"), "known_nodes=3");
        assertTrue(payload.contains("cluster_size:1\r\n"), "mesh 无分片 size=1");
        assertTrue(payload.contains("cluster_current_epoch:1\r\n"), "current_epoch=1");
        assertTrue(payload.contains("cluster_my_epoch:1\r\n"), "my_epoch=1");
        assertTrue(payload.contains("cluster_stats_messages_sent:0\r\n"), "messages_sent=0");
        assertTrue(payload.contains("cluster_stats_messages_received:0\r\n"), "messages_received=0");
    }

    @Test
    void clusterInfo_eachLineEndsWithCrlf() {
        MeshClusterCommands cmd = withLeaderA();
        String payload = extractBulkPayload(new String(cmd.clusterInfo(), StandardCharsets.ISO_8859_1));
        String[] lines = payload.split("\r\n", -1);
        // 最后一项为空串（因末尾 \r\n），其余每行应含 ":"
        assertTrue(lines.length > 1, "INFO 应有多行");
        for (int i = 0; i < lines.length - 1; i++) {
            assertTrue(lines[i].contains(":"), "每行应为 key:value 格式: " + lines[i]);
        }
        assertEquals("", lines[lines.length - 1], "末尾应以 \\r\\n 结尾（split 后空串）");
    }

    @Test
    void clusterInfo_noLeader_stateFailAndZeroSlots() {
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> null, () -> null, buildThreeNodes(), NODE_A);
        String payload = extractBulkPayload(new String(cmd.clusterInfo(), StandardCharsets.ISO_8859_1));

        assertTrue(payload.contains("cluster_state:fail\r\n"), "无 Leader 时 cluster_state:fail");
        assertTrue(payload.contains("cluster_slots_assigned:0\r\n"), "无 Leader 时 slots_assigned=0");
        assertTrue(payload.contains("cluster_slots_ok:0\r\n"), "无 Leader 时 slots_ok=0");
        assertTrue(payload.contains("cluster_size:0\r\n"), "无 Leader 时 size=0");
        assertTrue(payload.contains("cluster_known_nodes:3\r\n"), "无 Leader 仍 3 节点");
    }

    @Test
    void clusterInfo_reflectsLeaderChange() {
        final String[] leader = {null};
        final String[] addr = {null};
        MeshClusterCommands cmd = new MeshClusterCommands(
                () -> leader[0], () -> addr[0], buildThreeNodes(), NODE_A);

        String before = extractBulkPayload(new String(cmd.clusterInfo(), StandardCharsets.ISO_8859_1));
        assertTrue(before.contains("cluster_state:fail"), "初始无 Leader：fail");

        leader[0] = NODE_A;
        addr[0] = "192.168.1.1:6379";
        String after = extractBulkPayload(new String(cmd.clusterInfo(), StandardCharsets.ISO_8859_1));
        assertTrue(after.contains("cluster_state:ok"), "选出 Leader 后：ok");
        assertTrue(after.contains("cluster_slots_ok:16384"), "选出 Leader 后 slots_ok=16384");
    }

    // ==================== RESP 手工解析（验证字节结构可被标准客户端解析）====================

    @Test
    void respParsing_slotsCanBeParsedAsNestedArray() {
        // 模拟标准客户端解析 SLOTS 响应：手工逐字节解析 RESP
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterSlots();

        RespParser p = new RespParser(resp);
        // 外层 *1
        assertEquals('*', (char) p.readByte(), "外层应以 * 开头");
        assertEquals(1, p.readNumberUntilCrLn(), "外层数组 1 个元素");
        // 内层 *4
        assertEquals('*', (char) p.readByte());
        assertEquals(4, p.readNumberUntilCrLn(), "内层 4 个元素");
        // startSlot :0
        assertEquals(':', (char) p.readByte());
        assertEquals(0, p.readNumberUntilCrLn(), "startSlot=0");
        // endSlot :16383
        assertEquals(':', (char) p.readByte());
        assertEquals(16383, p.readNumberUntilCrLn(), "endSlot=16383");
        // master endpoint *3
        assertEquals('*', (char) p.readByte());
        assertEquals(3, p.readNumberUntilCrLn(), "master endpoint 3 元素");
        // ip bulk string
        assertEquals('$', (char) p.readByte());
        int ipLen = p.readNumberUntilCrLn();
        String ip = p.readAscii(ipLen);
        p.skipCrLn();
        assertEquals("192.168.1.1", ip, "解析出的 ip");
        // port integer
        assertEquals(':', (char) p.readByte());
        assertEquals(6379, p.readNumberUntilCrLn(), "解析出的 port=6379");
        // nodeId bulk string
        assertEquals('$', (char) p.readByte());
        int idLen = p.readNumberUntilCrLn();
        String nodeId = p.readAscii(idLen);
        p.skipCrLn();
        assertEquals(NODE_A, nodeId, "解析出的 nodeId");
        assertEquals(40, idLen, "nodeId 长度 40");
        // replicas 空数组（第 4 元素，Redis 7.0 格式必需）
        assertEquals('*', (char) p.readByte());
        assertEquals(0, p.readNumberUntilCrLn(), "replicas 空数组 0 元素");
        // 应已消费完毕
        assertTrue(p.atEnd(), "RESP 字节应已全部消费");
    }

    @Test
    void respParsing_nodesBulkStringStructure() {
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterNodes();

        RespParser p = new RespParser(resp);
        assertEquals('$', (char) p.readByte(), "应以 $ 开头（bulk string）");
        int len = p.readNumberUntilCrLn();
        assertTrue(len > 0, "bulk 长度应 > 0");
        byte[] payload = p.readBytes(len);
        p.skipCrLn();
        assertTrue(p.atEnd(), "应已消费完毕");

        String payloadStr = new String(payload, StandardCharsets.ISO_8859_1);
        assertTrue(payloadStr.contains(NODE_A), "payload 应含 nodeId A");
    }

    @Test
    void respParsing_infoBulkStringStructure() {
        MeshClusterCommands cmd = withLeaderA();
        byte[] resp = cmd.clusterInfo();

        RespParser p = new RespParser(resp);
        assertEquals('$', (char) p.readByte());
        int len = p.readNumberUntilCrLn();
        byte[] payload = p.readBytes(len);
        p.skipCrLn();
        assertTrue(p.atEnd());

        String payloadStr = new String(payload, StandardCharsets.ISO_8859_1);
        assertTrue(payloadStr.endsWith("\r\n"), "INFO payload 应以 \\r\\n 结尾");
        assertTrue(payloadStr.contains("cluster_state:ok"));
    }

    // ==================== 辅助方法 ====================

    /** 从 bulk string 响应（{@code $<len>\r\n...\r\n}）提取 payload 文本。 */
    private static String extractBulkPayload(String bulkResp) {
        // 找第一个 \r\n（$<len>\r\n）
        int crlfIdx = bulkResp.indexOf("\r\n");
        assertTrue(crlfIdx > 0, "非法 bulk string：" + bulkResp);
        // payload 从 crlfIdx+2 开始，到末尾去掉最后 2 字节 \r\n
        return bulkResp.substring(crlfIdx + 2, bulkResp.length() - 2);
    }

    /**
     * 找 nodeId 为指定值的行（按行首第一个 token 匹配，避免 slave 行的 masterId 字段误匹配）。
     */
    private static String findLineContaining(String payload, String nodeId) {
        for (String line : payload.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            // 行格式：<nodeId> <ip:port@cport> ... —— 取第一个空格前的 token 比对
            int spaceIdx = line.indexOf(' ');
            String firstToken = spaceIdx > 0 ? line.substring(0, spaceIdx) : line;
            if (firstToken.equals(nodeId)) {
                return line;
            }
        }
        return null;
    }

    /**
     * 极简 RESP 字节解析器（仅用于测试断言，逐字节消费）。
     */
    private static final class RespParser {
        private final byte[] data;
        private int pos;

        RespParser(byte[] data) {
            this.data = data;
        }

        byte readByte() {
            return data[pos++];
        }

        int readNumberUntilCrLn() {
            int n = 0;
            while (pos < data.length && data[pos] != '\r') {
                n = n * 10 + (data[pos] - '0');
                pos++;
            }
            skipCrLn();
            return n;
        }

        String readAscii(int len) {
            String s = new String(data, pos, len, StandardCharsets.ISO_8859_1);
            pos += len;
            return s;
        }

        byte[] readBytes(int len) {
            byte[] b = new byte[len];
            System.arraycopy(data, pos, b, 0, len);
            pos += len;
            return b;
        }

        void skipCrLn() {
            // 跳过 \r\n
            if (pos < data.length && data[pos] == '\r') {
                pos++;
            }
            if (pos < data.length && data[pos] == '\n') {
                pos++;
            }
        }

        boolean atEnd() {
            return pos >= data.length;
        }
    }
}
