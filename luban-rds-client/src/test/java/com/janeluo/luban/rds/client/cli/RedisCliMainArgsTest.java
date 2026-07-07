package com.janeluo.luban.rds.client.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RedisCliMain} 与 {@link ClusterSetupCommand} 的纯逻辑单元测试
 * <p>
 * 不发起任何网络请求，仅验证参数解析与槽位/从节点分配算法。
 * </p>
 *
 * @author janeluo
 * @since 1.0.0
 */
class RedisCliMainArgsTest {

    // ============ NodeAddress ============

    @Test
    @DisplayName("NodeAddress.parse 解析标准 host:port")
    void testNodeAddressParse() {
        NodeAddress addr = NodeAddress.parse("192.168.8.161:9736");
        assertEquals("192.168.8.161", addr.getHost());
        assertEquals(9736, addr.getPort());
        assertEquals("192.168.8.161:9736", addr.toAddress());
    }

    @Test
    @DisplayName("NodeAddress.parse 拒绝非法格式")
    void testNodeAddressParseInvalid() {
        assertThrows(ClusterSetupException.class, () -> NodeAddress.parse(null));
        assertThrows(ClusterSetupException.class, () -> NodeAddress.parse(""));
        assertThrows(ClusterSetupException.class, () -> NodeAddress.parse("noport"));
        assertThrows(ClusterSetupException.class, () -> NodeAddress.parse("host:"));
        assertThrows(ClusterSetupException.class, () -> NodeAddress.parse(":9736"));
        assertThrows(ClusterSetupException.class, () -> NodeAddress.parse("host:abc"));
        assertThrows(ClusterSetupException.class, () -> NodeAddress.parse("host:0"));
        assertThrows(ClusterSetupException.class, () -> NodeAddress.parse("host:65536"));
    }

    @Test
    @DisplayName("NodeAddress 支持带空格的输入")
    void testNodeAddressParseTrim() {
        NodeAddress addr = NodeAddress.parse("  192.168.8.161:9736  ");
        assertEquals("192.168.8.161", addr.getHost());
        assertEquals(9736, addr.getPort());
    }

    // ============ 参数解析 ============

    @Test
    @DisplayName("解析 --cluster create 基本参数")
    void testParseBasicCreate() {
        RedisCliMain.CliOptions options = RedisCliMain.parseArgs(new String[] {
                "--cluster", "create",
                "192.168.8.161:9736", "192.168.8.161:9737", "192.168.8.161:9738"
        });
        assertEquals(3, options.nodes.size());
        assertEquals(0, options.replicas);
        assertEquals(9736, options.nodes.get(0).getPort());
    }

    @Test
    @DisplayName("解析 --cluster-replicas 1")
    void testParseWithReplicas() {
        RedisCliMain.CliOptions options = RedisCliMain.parseArgs(new String[] {
                "--cluster", "create",
                "192.168.8.161:9736", "192.168.8.161:9737", "192.168.8.161:9738",
                "192.168.8.161:9739", "192.168.8.161:9740", "192.168.8.161:9741",
                "--cluster-replicas", "1"
        });
        assertEquals(6, options.nodes.size());
        assertEquals(1, options.replicas);
    }

    @Test
    @DisplayName("--cluster-replicas 可出现在节点列表之前")
    void testParseReplicasBeforeNodes() {
        RedisCliMain.CliOptions options = RedisCliMain.parseArgs(new String[] {
                "--cluster", "create", "--cluster-replicas", "1",
                "192.168.8.161:9736", "192.168.8.161:9737",
                "192.168.8.161:9738", "192.168.8.161:9739"
        });
        assertEquals(4, options.nodes.size());
        assertEquals(1, options.replicas);
    }

    @Test
    @DisplayName("无参数时打印帮助并返回 null")
    void testParseNoArgs() {
        RedisCliMain.CliOptions options = RedisCliMain.parseArgs(new String[] {});
        assertEquals(null, options);
    }

    @Test
    @DisplayName("--help 打印帮助并返回 null")
    void testParseHelp() {
        RedisCliMain.CliOptions options = RedisCliMain.parseArgs(new String[] {"--help"});
        assertEquals(null, options);
    }

    @Test
    @DisplayName("节点数与 replicas 不匹配时抛异常")
    void testParseInvalidNodeCount() {
        // 5 个节点, replicas=1 => 需要 2*(1+1)=4 的倍数，5 不匹配
        assertThrows(ClusterSetupException.class, () -> RedisCliMain.parseArgs(new String[] {
                "--cluster", "create",
                "192.168.8.161:9736", "192.168.8.161:9737", "192.168.8.161:9738",
                "192.168.8.161:9739", "192.168.8.161:9740",
                "--cluster-replicas", "1"
        }));
    }

    @Test
    @DisplayName("不支持的 --cluster 子命令抛异常")
    void testParseUnsupportedSubcommand() {
        assertThrows(ClusterSetupException.class, () -> RedisCliMain.parseArgs(new String[] {
                "--cluster", "reshard", "192.168.8.161:9736"
        }));
    }

    @Test
    @DisplayName("负数 replicas 抛异常")
    void testParseNegativeReplicas() {
        assertThrows(ClusterSetupException.class, () -> RedisCliMain.parseArgs(new String[] {
                "--cluster", "create",
                "192.168.8.161:9736", "192.168.8.161:9737",
                "--cluster-replicas", "-1"
        }));
    }

    @Test
    @DisplayName("未知选项抛异常")
    void testParseUnknownOption() {
        assertThrows(ClusterSetupException.class, () -> RedisCliMain.parseArgs(new String[] {
                "--cluster", "create", "--unknown-flag"
        }));
    }

    // ============ 槽位分配算法 ============

    @Test
    @DisplayName("3 个主节点的槽位均分 (0-5461, 5462-10922, 10923-16383)")
    void testSlotRanges3Masters() {
        int[][] ranges = ClusterSetupCommand.computeSlotRangesForMasters(3);
        // 16384 / 3 = 5461 余 1，余数补给 master0 => master0 得 5462 个槽位
        assertEquals(0, ranges[0][0]);
        assertEquals(5461, ranges[0][1]);
        assertEquals(5462, ranges[1][0]);
        assertEquals(10922, ranges[1][1]);
        assertEquals(10923, ranges[2][0]);
        assertEquals(16383, ranges[2][1]);
    }

    @Test
    @DisplayName("槽位范围覆盖全部 16384 且无重叠")
    void testSlotRangesCoverAll() {
        for (int masters = 1; masters <= 6; masters++) {
            int[][] ranges = ClusterSetupCommand.computeSlotRangesForMasters(masters);
            assertEquals(0, ranges[0][0], "起始应为 0 (masters=" + masters + ")");
            for (int i = 1; i < masters; i++) {
                assertEquals(ranges[i - 1][1] + 1, ranges[i][0],
                        "范围应连续 (masters=" + masters + ", i=" + i + ")");
            }
            assertEquals(16383, ranges[masters - 1][1], "末尾应为 16383 (masters=" + masters + ")");
        }
    }

    @Test
    @DisplayName("computeMasterCount 校验节点数与 replicas 关系")
    void testComputeMasterCount() {
        assertEquals(3, ClusterSetupCommand.computeMasterCount(3, 0));
        assertEquals(3, ClusterSetupCommand.computeMasterCount(6, 1));
        assertEquals(2, ClusterSetupCommand.computeMasterCount(6, 2));
        assertEquals(1, ClusterSetupCommand.computeMasterCount(3, 2));
    }

    @Test
    @DisplayName("computeMasterCount 拒绝不匹配的节点数")
    void testComputeMasterCountInvalid() {
        assertThrows(ClusterSetupException.class, () -> ClusterSetupCommand.computeMasterCount(5, 1));
        assertThrows(ClusterSetupException.class, () -> ClusterSetupCommand.computeMasterCount(7, 1));
        assertThrows(ClusterSetupException.class, () -> ClusterSetupCommand.computeMasterCount(3, -1));
    }

    // ============ 从节点分配 ============

    @Test
    @DisplayName("6 节点 replicas=1 时从节点交错排布")
    void testReplicaGroupsInterleaved() {
        List<NodeAddress> nodes = buildNodes(6);
        ClusterSetupCommand cmd = new ClusterSetupCommand(nodes, 1);
        List<List<Integer>> groups = cmd.computeReplicaGroups(3);

        // master0 -> replica index 3, master1 -> 4, master2 -> 5
        assertEquals(Arrays.asList(3), groups.get(0));
        assertEquals(Arrays.asList(4), groups.get(1));
        assertEquals(Arrays.asList(5), groups.get(2));
    }

    @Test
    @DisplayName("9 节点 replicas=2 时从节点交错排布")
    void testReplicaGroupsInterleaved2() {
        List<NodeAddress> nodes = buildNodes(9);
        ClusterSetupCommand cmd = new ClusterSetupCommand(nodes, 2);
        List<List<Integer>> groups = cmd.computeReplicaGroups(3);

        // 第 1 轮: master0->3, master1->4, master2->5
        // 第 2 轮: master0->6, master1->7, master2->8
        assertEquals(Arrays.asList(3, 6), groups.get(0));
        assertEquals(Arrays.asList(4, 7), groups.get(1));
        assertEquals(Arrays.asList(5, 8), groups.get(2));
    }

    @Test
    @DisplayName("replicas=0 时从节点组为空")
    void testReplicaGroupsZero() {
        List<NodeAddress> nodes = buildNodes(3);
        ClusterSetupCommand cmd = new ClusterSetupCommand(nodes, 0);
        List<List<Integer>> groups = cmd.computeReplicaGroups(3);
        assertTrue(groups.get(0).isEmpty());
        assertTrue(groups.get(1).isEmpty());
        assertTrue(groups.get(2).isEmpty());
    }

    @Test
    @DisplayName("computeSlotRanges 扁平数组与二维数组一致")
    void testFlatSlotRanges() {
        int[][] ranges2d = ClusterSetupCommand.computeSlotRangesForMasters(3);
        int[] flat = ClusterSetupCommand.computeSlotRanges(3);
        assertArrayEquals(new int[] {0, 5461, 5462, 10922, 10923, 16383}, flat);
        for (int i = 0; i < 3; i++) {
            assertEquals(ranges2d[i][0], flat[i * 2]);
            assertEquals(ranges2d[i][1], flat[i * 2 + 1]);
        }
    }

    // ============ ReplySupport ============

    @Test
    @DisplayName("ReplySupport.assertOk 接受 OK 字符串")
    void testAssertOkSuccess() {
        ReplySupport.assertOk("OK", "test");
    }

    @Test
    @DisplayName("ReplySupport.assertOk 拒绝非 OK 回复")
    void testAssertOkFailure() {
        assertThrows(ClusterSetupException.class, () -> ReplySupport.assertOk("ERR something", "test"));
        assertThrows(ClusterSetupException.class, () -> ReplySupport.assertOk(null, "test"));
        assertThrows(ClusterSetupException.class, () -> ReplySupport.assertOk(123L, "test"));
    }

    @Test
    @DisplayName("ReplySupport.requireString 提取字符串")
    void testRequireString() {
        assertEquals("abc", ReplySupport.requireString("abc", "test"));
        assertThrows(ClusterSetupException.class, () -> ReplySupport.requireString(null, "test"));
        assertThrows(ClusterSetupException.class, () -> ReplySupport.requireString(42, "test"));
    }

    @Test
    @DisplayName("ReplySupport.requireLong 提取整数")
    void testRequireLong() {
        assertEquals(42L, ReplySupport.requireLong(42L, "test"));
        assertEquals(42L, ReplySupport.requireLong(42, "test"));
        assertEquals(42L, ReplySupport.requireLong("42", "test"));
        assertThrows(ClusterSetupException.class, () -> ReplySupport.requireLong(null, "test"));
        assertThrows(ClusterSetupException.class, () -> ReplySupport.requireLong("abc", "test"));
    }

    // ============ 静态便捷 API ============

    @Test
    @DisplayName("createCluster 校验空节点列表")
    void testCreateClusterEmptyNodes() {
        assertThrows(ClusterSetupException.class,
                () -> ClusterSetupCommand.createCluster(new String[0], 1, false));
        assertThrows(ClusterSetupException.class,
                () -> ClusterSetupCommand.createCluster(null, 1, false));
    }

    @Test
    @DisplayName("createCluster 校验节点数与 replicas 不匹配")
    void testCreateClusterInvalidCount() {
        // 5 个节点, replicas=1 => 需要 2 的倍数
        assertThrows(ClusterSetupException.class, () -> ClusterSetupCommand.createCluster(
                new String[] {"127.0.0.1:9736", "127.0.0.1:9737", "127.0.0.1:9738",
                        "127.0.0.1:9739", "127.0.0.1:9740"}, 1, false));
    }

    @Test
    @DisplayName("createCluster 校验非法地址格式")
    void testCreateClusterInvalidAddress() {
        assertThrows(ClusterSetupException.class, () -> ClusterSetupCommand.createCluster(
                new String[] {"127.0.0.1:9736", "noport"}, 0, false));
    }

    @Test
    @DisplayName("RedisCliMain.run 解析失败抛异常（不调用 System.exit）")
    void testRunInvalidArgsThrows() {
        // 节点数与 replicas 不匹配，应在解析阶段抛异常而非 System.exit
        assertThrows(ClusterSetupException.class, () -> RedisCliMain.run(new String[] {
                "--cluster", "create",
                "127.0.0.1:9736", "127.0.0.1:9737", "127.0.0.1:9738",
                "127.0.0.1:9739", "127.0.0.1:9740",
                "--cluster-replicas", "1"
        }));
    }

    @Test
    @DisplayName("RedisCliMain.run 无参数打印帮助正常返回")
    void testRunNoArgsReturnsNormally() {
        // 不抛异常即通过
        RedisCliMain.run(new String[0]);
    }

    // ============ 辅助方法 ============

    private static List<NodeAddress> buildNodes(int count) {
        List<NodeAddress> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            nodes.add(NodeAddress.parse("192.168.8.161:" + (9736 + i)));
        }
        return nodes;
    }
}
