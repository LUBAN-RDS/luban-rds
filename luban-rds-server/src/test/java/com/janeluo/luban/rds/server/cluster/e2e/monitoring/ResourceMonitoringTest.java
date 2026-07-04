package com.janeluo.luban.rds.server.cluster.e2e.monitoring;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.client.RedisClient;
import com.janeluo.luban.rds.server.cluster.testinfra.AbstractClusterE2ETest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 120, unit = TimeUnit.SECONDS)
class ResourceMonitoringTest extends AbstractClusterE2ETest {

    @Test
    void testMemoryUsage_MultiProcess() throws Exception {
        // 启动 3 个进程
        for (int i = 0; i < 3; i++) {
            processManager.startProcess(nodeConfig(BASE_PORT + 100 + i, false));
        }

        // 等待启动
        Thread.sleep(1000);

        // 验证所有进程存活
        for (int i = 0; i < 3; i++) {
            assertTrue(processManager.isAlive("e2e-node-" + (BASE_PORT + 100 + i)));
        }

        // 指标已由 MetricsCollector 后台采集
        // 写入一些数据产生内存压力
        for (int i = 0; i < 3; i++) {
            RedisClient client = new NettyRedisClient("127.0.0.1", BASE_PORT + 100 + i);
            client.connect();
            try {
                for (int j = 0; j < 100; j++) {
                    client.set("memkey:" + j, new String(new char[1000]));
                }
            } finally {
                client.disconnect();
            }
        }

        // metrics JSON 会在 tearDown 中写入
        // 验证 metrics 目录存在
        assertNotNull(metrics);
    }

    @Test
    void testCpuUsage_UnderLoad() throws Exception {
        int port = BASE_PORT + 200;
        processManager.startProcess(nodeConfig(port, false));
        Thread.sleep(500);

        // 产生 CPU 负载
        RedisClient client = new NettyRedisClient("127.0.0.1", port);
        client.connect();
        assertTrue(client.isConnected(), "客户端应连接成功");
        try {
            AtomicInteger counter = new AtomicInteger(0);
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < 5000) {
                client.set("cpukey" + counter.getAndIncrement(), "value");
            }

            assertTrue(counter.get() > 100, "应执行超过 100 次操作");
        } finally {
            client.disconnect();
        }
    }

    @Test
    void testConnectionLeakDetection() throws Exception {
        int port = BASE_PORT + 210;
        processManager.startProcess(nodeConfig(port, false));
        Thread.sleep(500);

        // 大量连接/断开循环
        for (int i = 0; i < 20; i++) {
            RedisClient client = new NettyRedisClient("127.0.0.1", port);
            client.connect();
            try {
                client.set("leakkey" + i, "value");
                assertEquals("value", client.get("leakkey" + i));
            } finally {
                client.disconnect();
            }
        }

        // 验证进程仍存活
        assertTrue(processManager.isAlive("e2e-node-" + port));

        // 最终连接验证
        RedisClient finalClient = new NettyRedisClient("127.0.0.1", port);
        finalClient.connect();
        try {
            assertEquals("value", finalClient.get("leakkey0"));
        } finally {
            finalClient.disconnect();
        }
    }
}
