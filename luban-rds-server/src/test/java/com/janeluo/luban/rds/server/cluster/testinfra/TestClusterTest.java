package com.janeluo.luban.rds.server.cluster.testinfra;

import com.janeluo.luban.rds.cluster.slot.SlotUtils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestClusterTest {

    @Test
    void testClusterStartupAndSlotAssignment() {
        TestCluster cluster = TestCluster.builder()
                .nodes(3)
                .basePort(7100)
                .build();

        try {
            cluster.start();
            cluster.assignSlotsEvenly();
            cluster.waitForClusterOnline(5000);

            assertEquals(3, cluster.getNodeCount());
            ClusterTopology topology = cluster.getTopology();
            assertNotNull(topology);
            assertEquals(3, topology.getNodes().size());

            // 验证槽位总和为 16384
            int totalSlots = topology.getNodes().stream()
                    .mapToInt(n -> n.assignedSlots)
                    .sum();
            assertEquals(SlotUtils.CLUSTER_SLOTS, totalSlots);
        } finally {
            cluster.stop();
        }
    }
}
