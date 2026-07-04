package com.janeluo.luban.rds.server.cluster.testinfra;

import com.janeluo.luban.rds.client.NettyRedisClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs({OS.LINUX, OS.MAC, OS.WINDOWS})
class ProcessManagerTest {

    @Test
    void testStartAndStopProcess() throws Exception {
        String classpath = System.getProperty("java.class.path");
        ProcessManager pm = new ProcessManager(classpath);
        try {
            ProcessManager.ProcessNodeConfig config = ProcessManager.ProcessNodeConfig.builder()
                    .processId("test-node")
                    .port(8120)
                    .clusterEnabled(false)
                    .build();

            ProcessManager.ManagedProcess proc = pm.startProcess(config);
            assertTrue(pm.isAlive("test-node"));

            NettyRedisClient client = new NettyRedisClient("127.0.0.1", 8120);
            try {
                client.connect();
                client.set("testkey", "testvalue");
                assertEquals("testvalue", client.get("testkey"));
            } finally {
                client.disconnect();
            }

            pm.stopProcess("test-node", true);
            assertFalse(pm.isAlive("test-node"));
        } finally {
            pm.stopAll();
        }
    }
}
