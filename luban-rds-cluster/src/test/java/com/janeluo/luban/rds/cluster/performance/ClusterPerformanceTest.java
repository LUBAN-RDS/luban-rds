package com.janeluo.luban.rds.cluster.performance;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集群性能测试
 * <p>
 * 验证集群模块各组件的性能指标
 * </p>
 */
class ClusterPerformanceTest {

    private DefaultSlotManager slotManager;
    private ClusterConfig clusterConfig;

    /**
     * 性能测试迭代次数
     */
    private static final int ITERATIONS = 10000;

    /**
     * 并发线程数
     */
    private static final int CONCURRENT_THREADS = 16;

    @BeforeEach
    void setUp() {
        // 节点ID必须是40字符的十六进制字符串
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        slotManager = new DefaultSlotManager(nodeId);
        clusterConfig = new ClusterConfig(nodeId);
        
        // 添加本节点到配置
        ClusterNode myNode = new ClusterNode(nodeId);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        myNode.setIp("127.0.0.1");
        myNode.setPort(6379);
        myNode.setBusPort(16379);
        clusterConfig.addNode(myNode);
    }

    @Test
    @DisplayName("测试槽位查询性能 - 应小于 1ms")
    void testSlotQueryPerformance() {
        // 先分配一些槽位
        slotManager.addSlotRange(0, 5460);  // 分配约 1/3 的槽位

        // 预热
        for (int i = 0; i < 1000; i++) {
            slotManager.isSlotLocal(i % SlotUtils.CLUSTER_SLOTS);
            slotManager.getSlotOwner(i % SlotUtils.CLUSTER_SLOTS);
        }

        // 测试 isSlotLocal 性能
        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            slotManager.isSlotLocal(i % SlotUtils.CLUSTER_SLOTS);
        }
        long isSlotLocalTime = System.nanoTime() - startTime;

        // 测试 getSlotOwner 性能
        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            slotManager.getSlotOwner(i % SlotUtils.CLUSTER_SLOTS);
        }
        long getSlotOwnerTime = System.nanoTime() - startTime;

        // 计算平均时间（纳秒转毫秒）
        double avgIsSlotLocalMs = isSlotLocalTime / (double) ITERATIONS / 1_000_000;
        double avgGetSlotOwnerMs = getSlotOwnerTime / (double) ITERATIONS / 1_000_000;

        System.out.println("槽位查询性能测试结果:");
        System.out.println("  isSlotLocal 平均耗时: " + String.format("%.6f", avgIsSlotLocalMs) + " ms");
        System.out.println("  getSlotOwner 平均耗时: " + String.format("%.6f", avgGetSlotOwnerMs) + " ms");

        // 验证平均时间小于 1ms
        assertTrue(avgIsSlotLocalMs < 1.0, 
                "isSlotLocal 平均耗时应小于 1ms，实际: " + avgIsSlotLocalMs + " ms");
        assertTrue(avgGetSlotOwnerMs < 1.0, 
                "getSlotOwner 平均耗时应小于 1ms，实际: " + avgGetSlotOwnerMs + " ms");
    }

    @Test
    @DisplayName("测试 MOVED 响应生成性能 - 应小于 1ms")
    void testMovedResponsePerformance() {
        // 模拟 MOVED 响应生成
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        String ip = "127.0.0.1";
        int port = 6379;

        // 预热
        for (int i = 0; i < 1000; i++) {
            generateMovedResponse(i % SlotUtils.CLUSTER_SLOTS, ip, port);
        }

        // 测试
        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            generateMovedResponse(i % SlotUtils.CLUSTER_SLOTS, ip, port);
        }
        long totalTime = System.nanoTime() - startTime;

        // 计算平均时间
        double avgTimeMs = totalTime / (double) ITERATIONS / 1_000_000;

        System.out.println("MOVED 响应生成性能测试结果:");
        System.out.println("  平均耗时: " + String.format("%.6f", avgTimeMs) + " ms");

        // 验证平均时间小于 1ms
        assertTrue(avgTimeMs < 1.0, 
                "MOVED 响应生成平均耗时应小于 1ms，实际: " + avgTimeMs + " ms");
    }

    /**
     * 生成 MOVED 响应
     */
    private String generateMovedResponse(int slot, String ip, int port) {
        return "-MOVED " + slot + " " + ip + ":" + port + "\r\n";
    }

    @Test
    @DisplayName("测试 Gossip 心跳处理性能")
    void testGossipHeartbeatPerformance() {
        // 模拟 100 节点集群
        for (int i = 0; i < 100; i++) {
            // 节点ID必须是40字符的十六进制字符串
            String nodeId = String.format("%040x", i);
            ClusterNode node = new ClusterNode(nodeId);
            node.setIp("192.168.1." + (i % 256));
            node.setPort(6379 + i);
            node.setBusPort(16379 + i);
            node.addState(ClusterNodeState.MASTER);
            clusterConfig.addNode(node);
        }

        // 测试获取所有节点性能
        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            clusterConfig.getAllNodes().size();
        }
        long getAllNodesTime = System.nanoTime() - startTime;

        // 测试节点查找性能
        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            String nodeId = String.format("%040x", i % 100);
            clusterConfig.getNode(nodeId);
        }
        long getNodeTime = System.nanoTime() - startTime;

        // 计算平均时间
        double avgGetAllNodesMs = getAllNodesTime / (double) ITERATIONS / 1_000_000;
        double avgGetNodeMs = getNodeTime / (double) ITERATIONS / 1_000_000;

        System.out.println("Gossip 心跳处理性能测试结果:");
        System.out.println("  getAllNodes 平均耗时: " + String.format("%.6f", avgGetAllNodesMs) + " ms");
        System.out.println("  getNode 平均耗时: " + String.format("%.6f", avgGetNodeMs) + " ms");

        // 验证平均时间小于 10ms
        assertTrue(avgGetAllNodesMs < 10.0, 
                "getAllNodes 平均耗时应小于 10ms，实际: " + avgGetAllNodesMs + " ms");
        assertTrue(avgGetNodeMs < 1.0, 
                "getNode 平均耗时应小于 1ms，实际: " + avgGetNodeMs + " ms");
    }

    @Test
    @DisplayName("测试并发槽位操作")
    void testConcurrentSlotOperations() throws InterruptedException {
        // 分配初始槽位
        slotManager.addSlotRange(0, 1000);

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
        AtomicLong totalTime = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);

        // 并发测试
        for (int t = 0; t < CONCURRENT_THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    long threadStartTime = System.nanoTime();
                    
                    for (int i = 0; i < ITERATIONS / CONCURRENT_THREADS; i++) {
                        int slot = (threadId * 1000 + i) % SlotUtils.CLUSTER_SLOTS;
                        
                        // 读操作
                        boolean isLocal = slotManager.isSlotLocal(slot);
                        String owner = slotManager.getSlotOwner(slot);
                        
                        // 统计操作
                        slotManager.getMySlotCount();
                        slotManager.getUnassignedSlotCount();
                    }
                    
                    long threadTime = System.nanoTime() - threadStartTime;
                    totalTime.addAndGet(threadTime);
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 计算平均时间
        double avgTimeMs = totalTime.get() / (double) ITERATIONS / 1_000_000;

        System.out.println("并发槽位操作性能测试结果:");
        System.out.println("  线程数: " + CONCURRENT_THREADS);
        System.out.println("  总操作数: " + ITERATIONS);
        System.out.println("  平均耗时: " + String.format("%.6f", avgTimeMs) + " ms");
        System.out.println("  错误数: " + errorCount.get());

        // 验证无错误
        assertEquals(0, errorCount.get(), "并发操作不应有错误");
        
        // 验证平均时间合理
        assertTrue(avgTimeMs < 5.0, 
                "并发操作平均耗时应小于 5ms，实际: " + avgTimeMs + " ms");
    }

    @Test
    @DisplayName("测试 BitSet vs 遍历性能对比")
    void testBitSetVsIterationPerformance() {
        // 使用 BitSet 缓存的方式
        BitSet assignedBitSet = new BitSet(SlotUtils.CLUSTER_SLOTS);
        for (int i = 0; i < 5000; i++) {
            assignedBitSet.set(i);
        }

        // 预热
        for (int i = 0; i < 1000; i++) {
            assignedBitSet.cardinality();
        }

        // 测试 BitSet 方式
        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            assignedBitSet.cardinality();
        }
        long bitSetTime = System.nanoTime() - startTime;

        // 测试遍历方式
        String[] slotOwners = new String[SlotUtils.CLUSTER_SLOTS];
        for (int i = 0; i < 5000; i++) {
            slotOwners[i] = "node-id";
        }

        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            int count = 0;
            for (int j = 0; j < SlotUtils.CLUSTER_SLOTS; j++) {
                if (slotOwners[j] != null) {
                    count++;
                }
            }
        }
        long iterationTime = System.nanoTime() - startTime;

        // 计算平均时间
        double avgBitSetMs = bitSetTime / (double) ITERATIONS / 1_000_000;
        double avgIterationMs = iterationTime / (double) ITERATIONS / 1_000_000;

        System.out.println("BitSet vs 遍历性能对比:");
        System.out.println("  BitSet 方式平均耗时: " + String.format("%.6f", avgBitSetMs) + " ms");
        System.out.println("  遍历方式平均耗时: " + String.format("%.6f", avgIterationMs) + " ms");
        System.out.println("  性能提升: " + String.format("%.2f", (double) iterationTime / bitSetTime) + "x");

        // BitSet 应该比遍历快
        assertTrue(bitSetTime < iterationTime, 
                "BitSet 方式应该比遍历方式更快");
    }

    @Test
    @DisplayName("测试 EnumSet vs HashSet 性能对比")
    void testEnumSetVsHashSetPerformance() {
        // 测试 EnumSet
        Set<ClusterNodeState> enumSet = EnumSet.noneOf(ClusterNodeState.class);
        enumSet.add(ClusterNodeState.MASTER);
        enumSet.add(ClusterNodeState.MYSELF);

        // 预热
        for (int i = 0; i < 1000; i++) {
            enumSet.contains(ClusterNodeState.MASTER);
        }

        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            enumSet.contains(ClusterNodeState.MASTER);
            enumSet.contains(ClusterNodeState.SLAVE);
            enumSet.add(ClusterNodeState.FAIL);
            enumSet.remove(ClusterNodeState.FAIL);
        }
        long enumSetTime = System.nanoTime() - startTime;

        // 测试 HashSet
        Set<ClusterNodeState> hashSet = new HashSet<>();
        hashSet.add(ClusterNodeState.MASTER);
        hashSet.add(ClusterNodeState.MYSELF);

        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            hashSet.contains(ClusterNodeState.MASTER);
            hashSet.contains(ClusterNodeState.SLAVE);
            hashSet.add(ClusterNodeState.FAIL);
            hashSet.remove(ClusterNodeState.FAIL);
        }
        long hashSetTime = System.nanoTime() - startTime;

        // 计算平均时间
        double avgEnumSetMs = enumSetTime / (double) ITERATIONS / 1_000_000;
        double avgHashSetMs = hashSetTime / (double) ITERATIONS / 1_000_000;

        System.out.println("EnumSet vs HashSet 性能对比:");
        System.out.println("  EnumSet 方式平均耗时: " + String.format("%.6f", avgEnumSetMs) + " ms");
        System.out.println("  HashSet 方式平均耗时: " + String.format("%.6f", avgHashSetMs) + " ms");
        System.out.println("  性能提升: " + String.format("%.2f", (double) hashSetTime / enumSetTime) + "x");

        // EnumSet 通常比 HashSet 快，但在某些情况下可能差异不大
        // 主要优势在于内存占用和批量操作
        // 不强制要求 EnumSet 更快，只记录性能对比
    }

    @Test
    @DisplayName("测试槽位分配统计性能")
    void testSlotAssignmentStatisticsPerformance() {
        // 分配槽位（使用 addSlotRange 批量分配）
        slotManager.addSlotRange(0, 9999);

        // 预热
        for (int i = 0; i < 1000; i++) {
            slotManager.getUnassignedSlotCount();
            slotManager.isAllSlotsAssigned();
        }

        // 测试使用缓存的统计方法
        long startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            slotManager.getUnassignedSlotCount();
            slotManager.isAllSlotsAssigned();
        }
        long cachedTime = System.nanoTime() - startTime;

        // 计算平均时间
        double avgCachedMs = cachedTime / (double) ITERATIONS / 1_000_000;

        System.out.println("槽位分配统计性能测试结果:");
        System.out.println("  缓存方式平均耗时: " + String.format("%.6f", avgCachedMs) + " ms");

        // 验证平均时间小于 0.1ms（缓存方式应该非常快）
        assertTrue(avgCachedMs < 0.1, 
                "缓存方式平均耗时应小于 0.1ms，实际: " + avgCachedMs + " ms");
    }

    @Test
    @DisplayName("测试 ClusterConfig 槽位操作性能")
    void testClusterConfigSlotPerformance() {
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        
        // 测试槽位设置性能
        long startTime = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            clusterConfig.setSlotOwner(i, nodeId);
        }
        long setTime = System.nanoTime() - startTime;

        // 测试槽位查询性能
        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            clusterConfig.getSlotOwner(i % 5000);
        }
        long getTime = System.nanoTime() - startTime;

        // 测试已分配槽位计数性能
        startTime = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            clusterConfig.getAssignedSlotCount();
        }
        long countTime = System.nanoTime() - startTime;

        // 计算平均时间
        double avgSetMs = setTime / 5000.0 / 1_000_000;
        double avgGetMs = getTime / (double) ITERATIONS / 1_000_000;
        double avgCountMs = countTime / (double) ITERATIONS / 1_000_000;

        System.out.println("ClusterConfig 槽位操作性能测试结果:");
        System.out.println("  setSlotOwner 平均耗时: " + String.format("%.6f", avgSetMs) + " ms");
        System.out.println("  getSlotOwner 平均耗时: " + String.format("%.6f", avgGetMs) + " ms");
        System.out.println("  getAssignedSlotCount 平均耗时: " + String.format("%.6f", avgCountMs) + " ms");

        // 验证性能
        assertTrue(avgGetMs < 1.0, 
                "getSlotOwner 平均耗时应小于 1ms，实际: " + avgGetMs + " ms");
        assertTrue(avgCountMs < 0.1, 
                "getAssignedSlotCount 平均耗时应小于 0.1ms，实际: " + avgCountMs + " ms");
    }
}
