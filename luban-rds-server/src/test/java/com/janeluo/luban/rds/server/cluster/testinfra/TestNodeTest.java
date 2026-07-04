package com.janeluo.luban.rds.server.cluster.testinfra;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestNodeTest {

    @Test
    void testNodeStartAndStop() {
        TestNodeConfig config = TestNodeConfig.builder()
                .port(7001)
                .clusterEnabled(true)
                .build();
        TestNode node = new TestNode(config);
        try {
            node.start();
            assertTrue(node.isStarted());
            assertEquals(7001, node.getPort());
            assertNotNull(node.getClusterConfig());
            assertNotNull(node.getSlotManager());
        } finally {
            node.stop();
        }
        assertFalse(node.isStarted());
    }
}
