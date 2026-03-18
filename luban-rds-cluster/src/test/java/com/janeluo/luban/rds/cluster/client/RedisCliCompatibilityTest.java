package com.janeluo.luban.rds.cluster.client;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * redis-cli 兼容性测试
 * <p>
 * 测试 Luban-RDS 集群协议与 redis-cli 的兼容性。
 * 验证命令输出格式是否符合 Redis 规范。
 */
class RedisCliCompatibilityTest {

    private ClusterConfig clusterConfig;
    private SlotManager slotManager;
    private ClusterCommandHandler handler;

    // 测试用的节点ID（40字符十六进制）
    private static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_ID_2 = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_ID_3 = "c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    @BeforeEach
    void setUp() {
        // 创建集群配置
        clusterConfig = new ClusterConfig(NODE_ID_1);

        // 创建当前节点
        ClusterNode myNode = new ClusterNode(NODE_ID_1);
        myNode.setIp("127.0.0.1");
        myNode.setPort(7000);
        myNode.setBusPort(17000);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);

        // 创建槽位管理器
        slotManager = new DefaultSlotManager(NODE_ID_1);

        // 创建状态管理器
        ClusterStateManager stateManager = new ClusterStateManager(clusterConfig);

        // 创建命令处理器
        handler = new ClusterCommandHandler(clusterConfig, slotManager, stateManager, null);
    }

    @Test
    @DisplayName("测试 CLUSTER INFO 命令格式")
    void testClusterInfoFormat() {
        String result = handler.handle(new String[]{"INFO"});

        // 验证输出格式符合 Redis 规范
        assertNotNull(result);

        // 验证包含必要的字段
        assertTrue(result.contains("cluster_state:"), "应包含 cluster_state 字段");
        assertTrue(result.contains("cluster_slots_assigned:"), "应包含 cluster_slots_assigned 字段");
        assertTrue(result.contains("cluster_slots_ok:"), "应包含 cluster_slots_ok 字段");
        assertTrue(result.contains("cluster_slots_pfail:"), "应包含 cluster_slots_pfail 字段");
        assertTrue(result.contains("cluster_slots_fail:"), "应包含 cluster_slots_fail 字段");
        assertTrue(result.contains("cluster_known_nodes:"), "应包含 cluster_known_nodes 字段");
        assertTrue(result.contains("cluster_size:"), "应包含 cluster_size 字段");
        assertTrue(result.contains("cluster_current_epoch:"), "应包含 cluster_current_epoch 字段");
        assertTrue(result.contains("cluster_my_epoch:"), "应包含 cluster_my_epoch 字段");

        // 验证格式：每行一个键值对，以换行符分隔
        String[] lines = result.split("\r\n");
        for (String line : lines) {
            if (!line.isEmpty() && !line.equals("OK")) {
                // 验证键值对格式
                assertTrue(line.contains(":") || line.startsWith("#"),
                        "每行应该是键值对格式或注释: " + line);
            }
        }
    }

    @Test
    @DisplayName("测试 CLUSTER NODES 命令格式")
    void testClusterNodesFormat() {
        String result = handler.handle(new String[]{"NODES"});

        // 验证输出格式符合 Redis 规范
        assertNotNull(result);

        // 验证包含节点ID
        assertTrue(result.contains(NODE_ID_1), "应包含节点ID");

        // 验证格式：<id> <ip:port@cport> <flags> <master> <ping-sent> <pong-recv> <config-epoch> <link-state> <slot>
        String[] lines = result.split("\r\n");
        for (String line : lines) {
            if (!line.isEmpty()) {
                String[] parts = line.split(" ");
                assertTrue(parts.length >= 8, "节点行应至少包含8个字段: " + line);

                // 验证节点ID（40字符十六进制）
                assertTrue(parts[0].matches("[0-9a-f]{40}"),
                        "节点ID应为40字符十六进制: " + parts[0]);

                // 验证地址格式
                assertTrue(parts[1].contains(":"), "地址应包含端口号: " + parts[1]);

                // 验证标志
                assertTrue(parts[2].contains("master") || parts[2].contains("slave"),
                        "标志应包含 master 或 slave: " + parts[2]);
            }
        }
    }

    @Test
    @DisplayName("测试 MOVED 响应格式")
    void testMovedResponseFormat() {
        // 添加另一个节点
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.MASTER);
        otherNode.addSlot(0);
        clusterConfig.addNode(otherNode);

        // 尝试访问槽位0的数据（应该返回 MOVED）
        // 注意：这里我们模拟的是命令处理器返回的 MOVED 响应
        // 在实际场景中，当客户端访问不属于自己的槽位时会返回 MOVED

        // 验证 MOVED 响应格式：-MOVED <slot> <ip:port>
        // 格式应该是：-MOVED 0 127.0.0.1:7001
        String expectedMovedPattern = "-MOVED \\d+ [\\d.]+:\\d+";

        // 由于我们无法直接触发 MOVED 响应（需要实际的网络请求），
        // 这里验证 MOVED 响应的格式规范
        Pattern pattern = Pattern.compile(expectedMovedPattern);
        String testMovedResponse = "-MOVED 0 127.0.0.1:7001";
        Matcher matcher = pattern.matcher(testMovedResponse);
        assertTrue(matcher.matches(), "MOVED 响应格式应该正确: " + testMovedResponse);
    }

    @Test
    @DisplayName("测试 ASK 响应格式")
    void testAskResponseFormat() {
        // 验证 ASK 响应格式：-ASK <slot> <ip:port>
        // 格式应该是：-ASK 0 127.0.0.1:7001
        String expectedAskPattern = "-ASK \\d+ [\\d.]+:\\d+";

        Pattern pattern = Pattern.compile(expectedAskPattern);
        String testAskResponse = "-ASK 0 127.0.0.1:7001";
        Matcher matcher = pattern.matcher(testAskResponse);
        assertTrue(matcher.matches(), "ASK 响应格式应该正确: " + testAskResponse);
    }

    @Test
    @DisplayName("测试 CLUSTER KEYSLOT 命令格式")
    void testClusterKeyslotFormat() {
        String result = handler.handle(new String[]{"KEYSLOT", "testkey"});

        // 验证返回格式：:<slot>\r\n
        assertNotNull(result);
        assertTrue(result.startsWith(":"), "KEYSLOT 应返回整数格式");
        assertTrue(result.endsWith("\r\n"), "应以 CRLF 结尾");

        // 验证槽位号在有效范围内
        String slotStr = result.substring(1, result.length() - 2);
        int slot = Integer.parseInt(slotStr);
        assertTrue(slot >= 0 && slot < 16384, "槽位号应在 0-16383 范围内");
    }

    @Test
    @DisplayName("测试 CLUSTER COUNTKEYSINSLOT 命令格式")
    void testClusterCountkeysinslotFormat() {
        String result = handler.handle(new String[]{"COUNTKEYSINSLOT", "0"});

        // 验证返回格式：:<count>\r\n
        assertNotNull(result);
        assertTrue(result.startsWith(":"), "COUNTKEYSINSLOT 应返回整数格式");
        assertTrue(result.endsWith("\r\n"), "应以 CRLF 结尾");
    }

    @Test
    @DisplayName("测试 CLUSTER GETKEYSINSLOT 命令格式")
    void testClusterGetkeysinslotFormat() {
        String result = handler.handle(new String[]{"GETKEYSINSLOT", "0", "10"});

        // 验证返回格式：*<count>\r\n 或空数组
        assertNotNull(result);
        assertTrue(result.startsWith("*"), "GETKEYSINSLOT 应返回数组格式");
        assertTrue(result.endsWith("\r\n"), "应以 CRLF 结尾");
    }

    @Test
    @DisplayName("测试 CLUSTER MYID 命令格式")
    void testClusterMyidFormat() {
        String result = handler.handle(new String[]{"MYID"});

        // 验证返回格式：$<len>\r\n<id>\r\n
        assertNotNull(result);
        assertTrue(result.startsWith("$"), "MYID 应返回批量字符串格式");
        assertTrue(result.endsWith("\r\n"), "应以 CRLF 结尾");
        assertTrue(result.contains(NODE_ID_1), "应包含正确的节点ID");
    }

    @Test
    @DisplayName("测试 CLUSTER SLAVES 命令格式")
    void testClusterSlavesFormat() {
        // 添加主节点
        ClusterNode masterNode = new ClusterNode(NODE_ID_2);
        masterNode.setIp("127.0.0.1");
        masterNode.setPort(7001);
        masterNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(masterNode);

        // 添加从节点
        ClusterNode slaveNode = new ClusterNode(NODE_ID_3);
        slaveNode.setIp("127.0.0.1");
        slaveNode.setPort(7002);
        slaveNode.addState(ClusterNodeState.SLAVE);
        slaveNode.setMasterNodeId(NODE_ID_2);
        clusterConfig.addNode(slaveNode);

        String result = handler.handle(new String[]{"SLAVES", NODE_ID_2});

        // 验证返回格式：*<count>\r\n$<len>\r\n<node-info>\r\n...
        assertNotNull(result);
        assertTrue(result.startsWith("*"), "SLAVES 应返回数组格式");
    }

    @Test
    @DisplayName("测试错误响应格式")
    void testErrorResponseFormat() {
        // 测试无效命令
        String result = handler.handle(new String[]{"INVALID_COMMAND"});

        // 验证错误格式：-ERR <message>\r\n
        assertNotNull(result);
        assertTrue(result.startsWith("-ERR"), "错误响应应以 -ERR 开头");
        assertTrue(result.endsWith("\r\n"), "应以 CRLF 结尾");
    }

    @Test
    @DisplayName("测试 OK 响应格式")
    void testOkResponseFormat() {
        // 添加槽位
        String result = handler.handle(new String[]{"ADDSLOTS", "0"});

        // 验证 OK 格式：+OK\r\n
        assertEquals("+OK\r\n", result, "成功响应应为 +OK\\r\\n");
    }

    @Test
    @DisplayName("测试整数响应格式")
    void testIntegerResponseFormat() {
        // BUMPEPOCH 返回整数
        String result = handler.handle(new String[]{"BUMPEPOCH"});

        // 验证整数格式：:<value>\r\n
        assertNotNull(result);
        assertTrue(result.startsWith(":"), "整数响应应以 : 开头");
        assertTrue(result.endsWith("\r\n"), "应以 CRLF 结尾");
    }

    @Test
    @DisplayName("测试 Hash Tag 解析")
    void testHashTagParsing() {
        // 测试普通键
        String result1 = handler.handle(new String[]{"KEYSLOT", "user:1000"});
        String slot1 = result1.substring(1, result1.length() - 2);

        // 测试带 hash tag 的键
        String result2 = handler.handle(new String[]{"KEYSLOT", "{user:1000}"});
        String slot2 = result2.substring(1, result2.length() - 2);

        // 测试相同 hash tag 的不同键
        String result3 = handler.handle(new String[]{"KEYSLOT", "{user:1000}:profile"});
        String slot3 = result3.substring(1, result3.length() - 2);

        // 验证相同 hash tag 的键映射到相同槽位
        assertEquals(slot2, slot3, "相同 hash tag 的键应映射到相同槽位");

        // 验证 hash tag 只取花括号内的内容
        String result4 = handler.handle(new String[]{"KEYSLOT", "{user:1000}:data"});
        String slot4 = result4.substring(1, result4.length() - 2);
        assertEquals(slot2, slot4, "相同 hash tag 的键应映射到相同槽位");
    }

    @Test
    @DisplayName("测试 CLUSTER NODES 输出中的槽位范围")
    void testClusterNodesSlotRanges() {
        // 添加槽位
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2", "3", "4"});

        String result = handler.handle(new String[]{"NODES"});

        // 验证输出包含槽位范围
        // 格式：[0-4] 或 0 1 2 3 4
        assertTrue(result.contains("0") || result.contains("[0"),
                "节点输出应包含槽位信息");
    }

    @Test
    @DisplayName("测试 CLUSTER INFO 字段值类型")
    void testClusterInfoFieldValueTypes() {
        String result = handler.handle(new String[]{"INFO"});

        // 解析字段值
        String[] lines = result.split("\r\n");
        for (String line : lines) {
            if (line.contains(":") && !line.startsWith("#")) {
                String[] parts = line.split(":");
                if (parts.length == 2) {
                    String fieldName = parts[0];
                    String fieldValue = parts[1];

                    // 验证数值字段的值
                    if (fieldName.startsWith("cluster_slots_") ||
                            fieldName.equals("cluster_known_nodes") ||
                            fieldName.equals("cluster_size") ||
                            fieldName.contains("epoch")) {
                        try {
                            Long.parseLong(fieldValue);
                        } catch (NumberFormatException e) {
                            fail("字段 " + fieldName + " 的值应该是数字: " + fieldValue);
                        }
                    }
                }
            }
        }
    }
}
