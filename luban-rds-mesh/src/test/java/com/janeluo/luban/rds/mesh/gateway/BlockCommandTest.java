package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BLOCK 类命令禁用单测（阶段 9 / DESIGN §9 风险表 / 决策 17）。
 * <p>
 * v1 在 mesh 模式禁用 BLPOP/BRPOP/BZPOPMIN/BZPOPMAX（恒禁用）与 XREAD 带 BLOCK 选项（条件禁用），
 * 到达 gate 返回 {@code -ERR BLOCK commands are not supported in mesh mode\r\n}。
 * </p>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class BlockCommandTest {

    private static final byte[] BLOCK_ERR =
            "-ERR BLOCK commands are not supported in mesh mode\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    /** 构造一个 Leader gate（租约有效、本地读）。 */
    private static MeshWriteGate leaderGate(DefaultMemoryStore store) {
        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(new com.janeluo.luban.rds.mesh.election.LeaseManager() {
            {
                refreshOnMajorityAck(System.currentTimeMillis());
            }
        });
        return new MeshWriteGate(node, store, new DefaultCommandHandler());
    }

    // ==================== isBlockCommand 静态判定 ====================

    @Test
    void isBlockCommand_blockingListOps_returnTrue() {
        assertTrue(MeshWriteGate.isBlockCommand("BLPOP"));
        assertTrue(MeshWriteGate.isBlockCommand("BRPOP"));
        assertTrue(MeshWriteGate.isBlockCommand("BZPOPMIN"));
        assertTrue(MeshWriteGate.isBlockCommand("BZPOPMAX"));
    }

    @Test
    void isBlockCommand_caseInsensitive() {
        assertTrue(MeshWriteGate.isBlockCommand("blpop"));
        assertTrue(MeshWriteGate.isBlockCommand("Brpop"));
        assertTrue(MeshWriteGate.isBlockCommand("BLPOP  "));
    }

    @Test
    void isBlockCommand_normalCommands_returnFalse() {
        assertFalse(MeshWriteGate.isBlockCommand("GET"));
        assertFalse(MeshWriteGate.isBlockCommand("SET"));
        assertFalse(MeshWriteGate.isBlockCommand("LPUSH"));
        assertFalse(MeshWriteGate.isBlockCommand("RPOP"));   // 非阻塞 RPOP 允许
        assertFalse(MeshWriteGate.isBlockCommand("LPOP"));   // 非阻塞 LPOP 允许
        assertFalse(MeshWriteGate.isBlockCommand("XREAD"));  // XREAD 本身允许（需看 BLOCK 选项）
    }

    @Test
    void isBlockCommand_nullOrEmpty_returnFalse() {
        assertFalse(MeshWriteGate.isBlockCommand(null));
        assertFalse(MeshWriteGate.isBlockCommand(""));
    }

    // ==================== XREAD BLOCK 选项判定 ====================

    @Test
    void isBlockCommand_xreadWithoutBlock_returnFalse() {
        // XREAD COUNT 10 STREAMS ... → 普通读，允许
        assertFalse(MeshWriteGate.isBlockCommand("XREAD",
                new String[]{"XREAD", "COUNT", "10", "STREAMS", "mystream", "0"}));
        assertFalse(MeshWriteGate.isBlockCommand("XREAD",
                new String[]{"XREAD", "STREAMS", "mystream", "$"}));
    }

    @Test
    void isBlockCommand_xreadWithBlock_returnTrue() {
        // XREAD BLOCK 1000 STREAMS ... → 阻塞，禁用
        assertTrue(MeshWriteGate.isBlockCommand("XREAD",
                new String[]{"XREAD", "BLOCK", "1000", "STREAMS", "mystream", "$"}));
        // 大小写不敏感
        assertTrue(MeshWriteGate.isBlockCommand("xread",
                new String[]{"xread", "block", "0", "STREAMS", "k", "0"}));
    }

    @Test
    void isBlockCommand_xreadArgsButNotXread_returnFalse() {
        // 非 XREAD 命令的参数含 "BLOCK" 子串不算（如命令名非 XREAD）
        assertFalse(MeshWriteGate.isBlockCommand("GET",
                new String[]{"GET", "BLOCK"}));  // GET 不在禁用集，且不是 XREAD
    }

    // ==================== read 入口拒绝 BLOCK 命令 ====================

    @Test
    void read_blockCommand_returnsBlockErrorBeforeLeaderCheck() {
        // 即便不是 Leader 也应返回 BLOCK 错误（命令级禁用，优先于 MOVED）
        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(false);  // 非 Leader
        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler());

        byte[] resp = gate.read(0, new String[]{"BLPOP", "mylist", "0"});

        assertArrayEquals(BLOCK_ERR, resp);
    }

    @Test
    void read_blockCommands_returnErrorEvenAsLeader() {
        DefaultMemoryStore store = new DefaultMemoryStore();
        store.set(0, "foo", "bar");
        MeshWriteGate gate = leaderGate(store);

        // 各 BLOCK 命令到达 read 入口均返回 BLOCK 错误
        assertArrayEquals(BLOCK_ERR, gate.read(0, new String[]{"BLPOP", "k", "0"}));
        assertArrayEquals(BLOCK_ERR, gate.read(0, new String[]{"BRPOP", "k", "0"}));
        assertArrayEquals(BLOCK_ERR, gate.read(0, new String[]{"BZPOPMIN", "k", "0"}));
        assertArrayEquals(BLOCK_ERR, gate.read(0, new String[]{"BZPOPMAX", "k", "0"}));
        // XREAD 带 BLOCK 选项
        assertArrayEquals(BLOCK_ERR,
                gate.read(0, new String[]{"XREAD", "BLOCK", "1000", "STREAMS", "k", "$"}));
    }

    @Test
    void read_normalCommands_notAffected() {
        DefaultMemoryStore store = new DefaultMemoryStore();
        store.set(0, "foo", "bar");
        store.lpush(0, "mylist", "v1");
        MeshWriteGate gate = leaderGate(store);

        // GET 不受影响
        byte[] getResp = gate.read(0, new String[]{"GET", "foo"});
        String getS = new String(getResp, StandardCharsets.ISO_8859_1);
        assertTrue(getS.startsWith("$3\r\nbar"), "GET 应正常返回 bar: " + getS);

        // LPOP 非阻塞弹出不受影响
        byte[] lpopResp = gate.read(0, new String[]{"LPOP", "mylist"});
        String lpopS = new String(lpopResp, StandardCharsets.ISO_8859_1);
        assertTrue(lpopS.contains("v1"), "LPOP 应正常返回 v1: " + lpopS);

        // XREAD 不带 BLOCK（普通读）不受影响：用 XRANGE 等价的 XREAD COUNT
        // XREAD 在 DefaultCommandHandler 走 StreamCommandHandler，此处只验证不返回 BLOCK_ERR
        byte[] xreadResp = gate.read(0, new String[]{"XREAD", "COUNT", "10", "STREAMS", "s", "0"});
        assertFalse(java.util.Arrays.equals(xreadResp, BLOCK_ERR),
                "非阻塞 XREAD 不应返回 BLOCK 错误");
    }

    // ==================== blockCommandError 工具 ====================

    @Test
    void blockCommandError_returnsErrBytes() {
        byte[] err = MeshWriteGate.blockCommandError();
        assertEquals(new String(BLOCK_ERR, StandardCharsets.ISO_8859_1),
                new String(err, StandardCharsets.ISO_8859_1));
    }

    @Test
    void blockCommandError_returnsDefensiveCopy() {
        // 返回副本，修改不影响常量
        byte[] err1 = MeshWriteGate.blockCommandError();
        byte[] err2 = MeshWriteGate.blockCommandError();
        assertFalse(err1 == err2, "应返回新副本");
        assertArrayEquals(err1, err2);
    }
}
