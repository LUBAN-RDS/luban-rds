package com.janeluo.luban.rds.server.cluster.e2e.recovery;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.client.RedisClient;
import com.janeluo.luban.rds.server.cluster.testinfra.AbstractClusterE2ETest;
import com.janeluo.luban.rds.server.cluster.testinfra.ProcessManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FaultRecoveryTest extends AbstractClusterE2ETest {

    @Test
    void testGracefulShutdownRecovery() throws Exception {
        int port = BASE_PORT;
        ProcessManager.ProcessNodeConfig config = nodeConfig(port, false);
        processManager.startProcess(config);

        // 写入数据
        RedisClient client = new NettyRedisClient("127.0.0.1", port);
        client.connect();
        try {
            client.set("persistkey", "persistvalue");
            assertEquals("persistvalue", client.get("persistkey"));
        } finally {
            client.disconnect();
        }

        // 优雅停止
        processManager.stopProcess("e2e-node-" + port, true);
        assertFalse(processManager.isAlive("e2e-node-" + port));

        // 重启
        processManager.startProcess(config);

        // 验证重启后进程能响应命令（默认无持久化，数据会丢失）
        RedisClient client2 = new NettyRedisClient("127.0.0.1", port);
        client2.connect();
        try {
            client2.set("newkey", "newvalue");
            assertEquals("newvalue", client2.get("newkey"));
        } finally {
            client2.disconnect();
        }
    }

    @Test
    void testForceKillDetection() throws Exception {
        int port = BASE_PORT + 10;
        ProcessManager.ProcessNodeConfig config = nodeConfig(port, false);
        processManager.startProcess(config);

        assertTrue(processManager.isAlive("e2e-node-" + port));

        // 强制杀死
        processManager.stopProcess("e2e-node-" + port, false);
        assertFalse(processManager.isAlive("e2e-node-" + port));

        // 等待操作系统释放端口（force kill 后端口可能短暂仍可连接）
        Thread.sleep(1500);

        // 验证进程已终止
        // 注意：NettyRedisClient.connect() 在连接失败时会吞掉异常（不抛出），
        // 因此需要通过 isConnected() 判断连接是否成功
        RedisClient client = new NettyRedisClient("127.0.0.1", port);
        try {
            client.connect();
            assertFalse(client.isConnected(), "应无法连接到已杀死的进程");
        } catch (Exception e) {
            // 预期：连接失败（部分实现会抛出异常）
        } finally {
            try {
                client.disconnect();
            } catch (Exception ignore) {
                // best-effort cleanup
            }
        }
    }

    @Test
    void testResourceCleanupAfterStop() throws Exception {
        int port = BASE_PORT + 20;
        ProcessManager.ProcessNodeConfig config = nodeConfig(port, false);
        processManager.startProcess(config);

        assertTrue(processManager.isAlive("e2e-node-" + port));

        // 停止进程
        processManager.stopProcess("e2e-node-" + port, true);
        Thread.sleep(1000);

        // 验证端口已释放
        try (Socket socket = new Socket("127.0.0.1", port)) {
            fail("端口应已释放");
        } catch (Exception e) {
            // 预期：端口不可连接
        }
    }
}
