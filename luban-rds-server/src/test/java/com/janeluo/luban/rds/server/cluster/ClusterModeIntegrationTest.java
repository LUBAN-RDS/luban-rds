package com.janeluo.luban.rds.server.cluster;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群模式集成测试
 *
 * <p>测试 RedisServerHandler 在集群模式和非集群模式下的行为差异，
 * 包括重定向检查、无键命令跳过、CLUSTER 命令分发等场景。
 */
@DisplayName("集群模式集成测试")
class ClusterModeIntegrationTest extends AbstractClusterHandlerTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = null;
    }

    @Test
    @DisplayName("非集群模式下键命令不进行重定向检查")
    void testNonClusterModeNoRedirect() {
        channel = createNonClusterChannel();
        String response = sendCommand(channel, "SET", "foo", "bar");
        assertEquals("+OK\r\n", response);
        String getResponse = sendCommand(channel, "GET", "foo");
        assertNotNull(getResponse);
        assertTrue(getResponse.contains("bar"));
    }

    @Test
    @DisplayName("非集群模式下ASKING/READONLY/READWRITE命令正常处理")
    void testNonClusterModeAskingReadonlyReadwrite() {
        channel = createNonClusterChannel();
        assertEquals("+OK\r\n", sendCommand(channel, "ASKING"));
        assertEquals("+OK\r\n", sendCommand(channel, "READONLY"));
        assertEquals("+OK\r\n", sendCommand(channel, "READWRITE"));
    }

    @Test
    @DisplayName("集群模式下无键命令跳过重定向检查")
    void testNoKeyCommandsSkipRedirect() {
        channel = createClusterChannel();
        assertEquals("+OK\r\n", sendCommand(channel, "ASKING"));
        assertEquals("+OK\r\n", sendCommand(channel, "READONLY"));
        assertEquals("+OK\r\n", sendCommand(channel, "READWRITE"));
    }

    @Test
    @DisplayName("集群模式下键命令触发重定向检查")
    void testKeyCommandsTriggerRedirect() {
        channel = createClusterChannel();
        String response = sendCommand(channel, "GET", "foo");
        assertEquals("-CLUSTERDOWN Hash slot not served\r\n", response);
    }

    @Test
    @DisplayName("CLUSTER INFO 返回集群状态信息")
    void testClusterInfo() {
        channel = createClusterChannel();
        String response = sendCommand(channel, "CLUSTER", "INFO");
        assertNotNull(response);
        assertTrue(response.contains("cluster_state:"));
        assertTrue(response.contains("cluster_known_nodes:"));
    }

    @Test
    @DisplayName("CLUSTER NODES 返回节点列表信息")
    void testClusterNodes() {
        channel = createClusterChannel();
        String response = sendCommand(channel, "CLUSTER", "NODES");
        assertNotNull(response);
        assertTrue(response.contains(NODE_ID_1));
        assertTrue(response.contains("127.0.0.1:7000"));
    }

    @Test
    @DisplayName("CLUSTER KEYSLOT 返回键的槽位号")
    void testClusterKeyslot() {
        channel = createClusterChannel();
        String response = sendCommand(channel, "CLUSTER", "KEYSLOT", "foo");
        assertNotNull(response);
        assertTrue(response.startsWith(":"));
        assertTrue(response.endsWith("\r\n"));
    }

    @Test
    @DisplayName("CLUSTER ADDSLOTS 分配槽位并更新集群状态")
    void testClusterAddslots() {
        channel = createClusterChannel();
        String response = sendCommand(channel, "CLUSTER", "ADDSLOTS", "0", "1", "2");
        assertEquals("+OK\r\n", response);
        String infoResponse = sendCommand(channel, "CLUSTER", "INFO");
        assertNotNull(infoResponse);
        assertTrue(infoResponse.contains("cluster_slots_assigned:3"));
    }
}
