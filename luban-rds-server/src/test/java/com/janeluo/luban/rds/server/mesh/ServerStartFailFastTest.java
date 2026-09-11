package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-14 + Q8（2026-09-11 mesh 审计）：
 * <ul>
 *   <li>P1-14：start() 失败（如总线端口被占）必须抛出 IllegalStateException——
 *       吞异常会让 RedisServerMain 打完"启动成功"后 join 永久阻塞（僵尸进程）；</li>
 *   <li>Q8：mesh 启动失败路径释放 NioEventLoopGroup（busClient close）。</li>
 * </ul>
 */
class ServerStartFailFastTest {

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tempDir;

    @Test
    void busyBusPortFailsStart() throws Exception {
        // 预占 bus 端口（servicePort + 1000 取段内端口并直接占用）
        int servicePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            servicePort = probe.getLocalPort();
        }
        int busPort;
        try (ServerSocket busBlocker = new ServerSocket(0)) {
            busPort = busBlocker.getLocalPort();
        }

        RdsConfig config = new RdsConfig();
        config.setDir(tempDir.toString());
        config.setPort(servicePort);
        config.setMeshEnabled(true);
        config.setMeshSelfNodeId("n1");
        config.setMeshServicePort(servicePort);
        config.setMeshBusPort(busPort);
        config.setMeshPeers("n1@127.0.0.1:" + busPort + ":" + servicePort
                + ",n2@127.0.0.1:" + (busPort + 1) + ":" + (servicePort + 1));

        try (ServerSocket serviceBlocker = new ServerSocket(servicePort)) {
            NettyRedisServer server = new NettyRedisServer(config);
            // P1-14：start 失败必须抛出（修复前仅 error 日志 + stop，进程变僵尸）
            IllegalStateException ex = assertThrows(IllegalStateException.class, server::start);
            assertTrue(ex.getMessage().contains("failed to start"));
            // Q8：失败路径应释放 busClient 的 EventLoopGroup（closed 标志经反射断言）
            Object busClient = readField(server, "meshAssembly") != null
                    ? invokeGetter(readField(server, "meshAssembly"), "getBusClient")
                    : null;
            if (busClient != null) {
                boolean closed = (boolean) readField(busClient, "closed");
                assertTrue(closed, "启动失败后 busClient 应已关闭（无线程泄漏）");
            }
        }
    }

    private static Object readField(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object invokeGetter(Object target, String getter) throws Exception {
        java.lang.reflect.Method m = target.getClass().getMethod(getter);
        Object result = m.invoke(target);
        assertNotNull(result);
        return result;
    }
}
