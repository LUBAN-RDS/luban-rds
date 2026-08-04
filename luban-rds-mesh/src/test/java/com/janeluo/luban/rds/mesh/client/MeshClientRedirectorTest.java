package com.janeluo.luban.rds.mesh.client;

import com.janeluo.luban.rds.common.util.SlotUtils;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeshClientRedirector} 单元测试（阶段 6 / DESIGN §5.3 场景 3）。
 * <p>
 * 验证点：
 * <ul>
 *   <li>已知 Leader：formatResponse 返回 {@code "-MOVED <真实CRC16 slot> <ip:port>\r\n"}，
 *       slot 与 {@link SlotUtils#getSlot} 一致（真实 CRC16，非占位）；</li>
 *   <li>无 Leader（serviceAddr 为 null/空）：返回 {@code "-MESHDOWN ...\r\n"}；</li>
 *   <li>异常只带 leaderNodeId 时，经 {@code nodeIdToServiceAddr} 映射补全地址；</li>
 *   <li>key 为 null 时 slot=0；</li>
 *   <li>便捷重载 {@link #formatResponse(String, String)} 与主方法一致。</li>
 * </ul>
 * </p>
 *
 * @author janeluo
 * @since 阶段 6
 */
class MeshClientRedirectorTest {

    // 已知 Redis Cluster 标准 slot 值（CRC16(key) % 16384）
    private static final int SLOT_FOO = 12182;   // CRC16("foo") % 16384
    private static final int SLOT_BAR = 5061;    // CRC16("bar") % 16384
    private static final int SLOT_MYKEY = 14687; // CRC16("mykey") % 16384

    /** 健全性：SlotUtils 返回 Redis 标准值（确保后续断言的「真实 CRC16」基准正确）。 */
    @Test
    void slotUtilsReturnsStandardRedisValues() {
        assertEquals(SLOT_FOO, SlotUtils.getSlot("foo"),
                "SlotUtils.getSlot(foo) 应为 Redis 标准 slot 12182");
        assertEquals(SLOT_BAR, SlotUtils.getSlot("bar"));
        assertEquals(SLOT_MYKEY, SlotUtils.getSlot("mykey"));
    }

    @Test
    void formatResponse_knownLeader_returnsMovedWithRealSlot() {
        MeshClientRedirector r = new MeshClientRedirector();
        // service 端口（6379），非 bus 端口（11000）
        MovedToLeaderException e = new MovedToLeaderException("node-A", "10.0.0.1:6379", "foo");

        String resp = r.formatResponse(e);

        String expected = "-MOVED " + SLOT_FOO + " 10.0.0.1:6379\r\n";
        assertEquals(expected, resp,
                "已知 Leader 应返回 -MOVED <真实CRC16 slot> <service ip:port>\\r\\n");
    }

    @Test
    void formatResponse_slotMatchesSlotUtilsForMultipleKeys() {
        // 关键断言：slot 必须用真实 CRC16（SlotUtils.getSlot），非占位值。
        // 不同 key 必须产生不同 slot，证明不是固定值。
        MeshClientRedirector r = new MeshClientRedirector();

        String respFoo = r.formatResponse(new MovedToLeaderException("n", "1.1.1.1:6379", "foo"));
        String respBar = r.formatResponse(new MovedToLeaderException("n", "1.1.1.1:6379", "bar"));
        String respMykey = r.formatResponse(new MovedToLeaderException("n", "1.1.1.1:6379", "mykey"));

        assertEquals("-MOVED " + SlotUtils.getSlot("foo") + " 1.1.1.1:6379\r\n", respFoo);
        assertEquals("-MOVED " + SlotUtils.getSlot("bar") + " 1.1.1.1:6379\r\n", respBar);
        assertEquals("-MOVED " + SlotUtils.getSlot("mykey") + " 1.1.1.1:6379\r\n", respMykey);

        // 不同 key → 不同 slot（证明非占位）
        assertTrue(!respFoo.equals(respBar), "不同 key 应产生不同 slot");
        assertTrue(!respFoo.equals(respMykey));
    }

    @Test
    void formatResponse_hashTagSlotHonored() {
        // {tag}key1 与 {tag}key2 应映射到同一 slot（hash tag 语义）
        MeshClientRedirector r = new MeshClientRedirector();
        String r1 = r.formatResponse(new MovedToLeaderException("n", "1.1.1.1:6379", "{tag}key1"));
        String r2 = r.formatResponse(new MovedToLeaderException("n", "1.1.1.1:6379", "{tag}key2"));
        assertEquals(SlotUtils.getSlot("{tag}key1"), SlotUtils.getSlot("{tag}key2"));
        assertEquals(r1, r2, "hash tag 相同的 key 应在同一 slot");
    }

    @Test
    void formatResponse_nullLeaderServiceAddr_returnsMeshdown() {
        MeshClientRedirector r = new MeshClientRedirector();
        // 无 Leader：serviceAddr=null
        MovedToLeaderException e = new MovedToLeaderException(null, null, "foo");

        String resp = r.formatResponse(e);

        assertEquals("-MESHDOWN The mesh cluster has no leader\r\n", resp,
                "无 Leader 应返回 -MESHDOWN");
    }

    @Test
    void formatResponse_emptyLeaderServiceAddr_returnsMeshdown() {
        MeshClientRedirector r = new MeshClientRedirector();
        MovedToLeaderException e = new MovedToLeaderException(null, "", "foo");
        assertEquals("-MESHDOWN The mesh cluster has no leader\r\n", r.formatResponse(e));
    }

    @Test
    void formatResponse_leaderNodeIdResolvedViaMap() {
        // 异常只带 leaderNodeId（serviceAddr 为空），经 nodeIdToServiceAddr 映射补全
        Map<String, String> map = new HashMap<>();
        map.put("leader-node-2", "10.0.0.2:6380");
        MeshClientRedirector r = new MeshClientRedirector(map);

        // serviceAddr=null 但 leaderNodeId 已知
        MovedToLeaderException e = new MovedToLeaderException("leader-node-2", null, "bar");
        String resp = r.formatResponse(e);

        assertEquals("-MOVED " + SLOT_BAR + " 10.0.0.2:6380\r\n", resp,
                "leaderNodeId 应经映射补全为 serviceAddr");
    }

    @Test
    void formatResponse_leaderNodeIdNotInMap_returnsMeshdown() {
        // serviceAddr 为空且 nodeId 不在映射中 → MESHDOWN
        Map<String, String> map = new HashMap<>();
        map.put("other", "10.0.0.9:6379");
        MeshClientRedirector r = new MeshClientRedirector(map);

        MovedToLeaderException e = new MovedToLeaderException("unknown-node", null, "foo");
        assertEquals("-MESHDOWN The mesh cluster has no leader\r\n", r.formatResponse(e));
    }

    @Test
    void formatResponse_serviceAddrPreferOverMap() {
        // serviceAddr 非空时优先用之，不查 map（map 中即使有该 nodeId 也以异常的 serviceAddr 为准）
        Map<String, String> map = new HashMap<>();
        map.put("node-X", "10.0.0.99:9999");
        MeshClientRedirector r = new MeshClientRedirector(map);

        MovedToLeaderException e = new MovedToLeaderException("node-X", "10.0.0.1:6379", "foo");
        String resp = r.formatResponse(e);

        assertEquals("-MOVED " + SLOT_FOO + " 10.0.0.1:6379\r\n", resp,
                "异常携带的 serviceAddr 优先于 map 映射");
    }

    @Test
    void formatResponse_nullKey_slotZero() {
        MeshClientRedirector r = new MeshClientRedirector();
        MovedToLeaderException e = new MovedToLeaderException("n", "1.1.1.1:6379", null);
        String resp = r.formatResponse(e);
        // SlotUtils.getSlot((String)null) == 0
        assertEquals(0, SlotUtils.getSlot((String) null), "前置：SlotUtils.getSlot(null)=0");
        assertEquals("-MOVED 0 1.1.1.1:6379\r\n", resp, "key 为 null 时 slot=0");
    }

    @Test
    void formatResponse_overloadMatchesMain() {
        // 便捷重载应与主方法（构造完整异常）结果一致
        MeshClientRedirector r = new MeshClientRedirector();
        String overload = r.formatResponse("10.0.0.1:6379", "foo");
        String main = r.formatResponse(new MovedToLeaderException(null, "10.0.0.1:6379", "foo"));
        assertEquals(main, overload);
    }

    @Test
    void formatResponse_overloadNullAddr_meshdown() {
        MeshClientRedirector r = new MeshClientRedirector();
        assertEquals("-MESHDOWN The mesh cluster has no leader\r\n",
                r.formatResponse(null, "foo"));
    }

    @Test
    void mesgdownConstantIsCorrect() {
        assertEquals("-MESHDOWN The mesh cluster has no leader\r\n",
                MeshClientRedirector.MESHDOWN_RESPONSE);
    }

    @Test
    void getServiceAddr_looksUpNode() {
        Map<String, String> map = new HashMap<>();
        map.put("n1", "10.0.0.1:6379");
        MeshClientRedirector r = new MeshClientRedirector(map);
        assertEquals("10.0.0.1:6379", r.getServiceAddr("n1"));
        assertEquals(null, r.getServiceAddr("missing"));
        assertEquals(null, r.getServiceAddr(null));
    }

    @Test
    void movedResponseEndsWithCrlf() {
        MeshClientRedirector r = new MeshClientRedirector();
        String resp = r.formatResponse(new MovedToLeaderException("n", "1.1.1.1:6379", "foo"));
        assertNotNull(resp);
        assertTrue(resp.endsWith("\r\n"), "RESP 响应必须以 \\r\\n 结尾");
    }

    // ==================== D2: 自重定向守卫 ====================

    /**
     * D2：解析出的 Leader 地址等于本节点自身地址时，不下发 MOVED（会触发客户端死循环），
     * 改发 MESHDOWN 让客户端退避重试。
     * <p>复现场景：单机多实例漏配第三段 servicePort，nodeIdToServiceAddr 全塌缩到同一地址，
     * 非 Leader 节点 MOVED 到自己 → Redisson "MOVED redirection loop detected"。</p>
     */
    @Test
    void formatResponse_leaderAddrEqualsSelf_returnsMeshdownNotMoved() {
        // 本节点自身地址 = 10.0.0.1:6379；Leader 地址也解析到 10.0.0.1:6379（塌缩）
        Map<String, String> map = new HashMap<>();
        map.put("leader-node", "10.0.0.1:6379");
        MeshClientRedirector r = new MeshClientRedirector(map, "10.0.0.1:6379");

        MovedToLeaderException e = new MovedToLeaderException("leader-node", null, "foo");
        String resp = r.formatResponse(e);

        assertEquals(MeshClientRedirector.MESHDOWN_SELF_REDIRECT_RESPONSE, resp,
                "Leader 地址等于自身时应返回自重定向 MESHDOWN，而非 MOVED 到自己");
        assertTrue(resp.startsWith("-MESHDOWN"), "应是 MESHDOWN 响应: " + resp);
    }

    /**
     * D2：Leader 地址与自身地址不同时，正常下发 MOVED（守卫不误伤正常重定向）。
     */
    @Test
    void formatResponse_leaderAddrDifferentFromSelf_normalMoved() {
        Map<String, String> map = new HashMap<>();
        map.put("leader-node", "10.0.0.2:6380");
        // 本节点是 10.0.0.1:6379，Leader 是 10.0.0.2:6380 → 正常 MOVED
        MeshClientRedirector r = new MeshClientRedirector(map, "10.0.0.1:6379");

        MovedToLeaderException e = new MovedToLeaderException("leader-node", null, "foo");
        String resp = r.formatResponse(e);

        assertEquals("-MOVED " + SLOT_FOO + " 10.0.0.2:6380\r\n", resp,
                "Leader 地址与自身不同时应正常 MOVED");
    }

    /**
     * D2：未注入 selfServiceAddr（旧构造器 / 测试场景）时不触发守卫，保持原行为。
     */
    @Test
    void formatResponse_noSelfAddr_guardNotTriggered() {
        Map<String, String> map = new HashMap<>();
        map.put("leader-node", "10.0.0.1:6379");
        // 旧构造器：selfServiceAddr=null
        MeshClientRedirector r = new MeshClientRedirector(map);

        MovedToLeaderException e = new MovedToLeaderException("leader-node", null, "foo");
        String resp = r.formatResponse(e);

        // 无自身地址注入 → 不触发守卫 → 正常 MOVED（即便地址恰好等于自己）
        assertEquals("-MOVED " + SLOT_FOO + " 10.0.0.1:6379\r\n", resp);
    }

    /**
     * D2：异常直接携带 serviceAddr（非经 map 解析）等于自身时，守卫同样生效。
     */
    @Test
    void formatResponse_explicitServiceAddrEqualsSelf_guardTriggered() {
        MeshClientRedirector r = new MeshClientRedirector(new HashMap<>(), "10.0.0.1:6379");
        // 异常直接带 serviceAddr=10.0.0.1:6379（等于自身）
        MovedToLeaderException e = new MovedToLeaderException(null, "10.0.0.1:6379", "foo");
        String resp = r.formatResponse(e);
        assertEquals(MeshClientRedirector.MESHDOWN_SELF_REDIRECT_RESPONSE, resp);
    }

    @Test
    void selfRedirectMeshdownConstantIsCorrect() {
        assertEquals("-MESHDOWN redirect target is self; cluster topology unstable\r\n",
                MeshClientRedirector.MESHDOWN_SELF_REDIRECT_RESPONSE);
    }
}
