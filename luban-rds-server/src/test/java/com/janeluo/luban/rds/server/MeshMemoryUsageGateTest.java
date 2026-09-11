package com.janeluo.luban.rds.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-19（2026-09-11 mesh 审计）：MEMORY USAGE 是数据依赖读，mesh 模式须走 gate
 * 租约读（Leader 执行 / follower MOVED），不得本地短路返回陈旧数据。
 * <p>直接锁定路由判定；数据无关运维命令（INFO/CONFIG/TIME 等）保持本地。</p>
 */
class MeshMemoryUsageGateTest {

    @Test
    void memoryRoutesThroughGate() {
        assertTrue(RedisServerHandler.shouldUseMeshGate("MEMORY"),
                "MEMORY 不应本地短路（应走 gate 租约读）");
        assertTrue(RedisServerHandler.shouldUseMeshGate("memory"),
                "大小写不敏感：memory 应走 gate");
    }

    @Test
    void nodeLocalOpsCommandsStayLocal() {
        for (String cmd : new String[]{"INFO", "CONFIG", "TIME", "SLOWLOG", "CLIENT",
                "ROLE", "LASTSAVE", "WAIT", "COMMAND", "PING", "AUTH"}) {
            assertFalse(RedisServerHandler.shouldUseMeshGate(cmd),
                    cmd + " 属节点本地运维/连接命令，应保持本地路径");
        }
    }

    @Test
    void keyspaceCommandsRouteThroughGate() {
        assertTrue(RedisServerHandler.shouldUseMeshGate("SET"));
        assertTrue(RedisServerHandler.shouldUseMeshGate("GET"));
        assertTrue(RedisServerHandler.shouldUseMeshGate("UNKNOWN_CMD"));
    }
}
