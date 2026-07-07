package com.janeluo.luban.rds.cluster.slot;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DefaultSlotManager 默认槽位管理器单元测试
 *
 * <p>覆盖槽位 CRUD、范围操作、槽位归属设置、迁移/导入状态管理、
 * 线程安全、边界值与异常处理。包含正向、负向与边界值测试。</p>
 */
@DisplayName("DefaultSlotManager 槽位管理器测试")
class DefaultSlotManagerTest {

    private static final String MY_NODE_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String OTHER_NODE_ID = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";

    private DefaultSlotManager slotManager;

    @BeforeEach
    void setUp() {
        slotManager = new DefaultSlotManager(MY_NODE_ID);
    }

    // ==================== 构造方法测试 ====================

    @Nested
    @DisplayName("构造方法测试")
    class ConstructorTest {

        @Test
        @DisplayName("默认构造方法初始化空状态")
        void testDefaultConstructor() {
            DefaultSlotManager manager = new DefaultSlotManager();
            assertAll("默认构造校验",
                    () -> assertNull(manager.getMyNodeId()),
                    () -> assertEquals(0, manager.getMySlotCount()),
                    () -> assertEquals(SlotUtils.CLUSTER_SLOTS, manager.getUnassignedSlotCount()),
                    () -> assertFalse(manager.isAllSlotsAssigned())
            );
        }

        @Test
        @DisplayName("带节点ID的构造方法")
        void testConstructorWithNodeId() {
            assertEquals(MY_NODE_ID, slotManager.getMyNodeId());
        }
    }

    // ==================== addSlots 测试 ====================

    @Nested
    @DisplayName("addSlots 添加槽位测试")
    class AddSlotsTest {

        @Test
        @DisplayName("添加单个槽位")
        void testAddSingleSlot() {
            slotManager.addSlots(0);
            assertAll("添加单个槽位校验",
                    () -> assertTrue(slotManager.isSlotLocal(0)),
                    () -> assertEquals(MY_NODE_ID, slotManager.getSlotOwner(0)),
                    () -> assertTrue(slotManager.isSlotAssigned(0)),
                    () -> assertEquals(1, slotManager.getMySlotCount()),
                    () -> assertEquals(SlotUtils.CLUSTER_SLOTS - 1, slotManager.getUnassignedSlotCount())
            );
        }

        @Test
        @DisplayName("添加多个槽位")
        void testAddMultipleSlots() {
            slotManager.addSlots(0, 1, 2, 100, 1000);
            assertEquals(5, slotManager.getMySlotCount());
            assertTrue(slotManager.isSlotLocal(0));
            assertTrue(slotManager.isSlotLocal(1000));
        }

        @Test
        @DisplayName("添加 null 槽位数组不抛异常")
        void testAddNullSlots() {
            slotManager.addSlots((int[]) null);
            assertEquals(0, slotManager.getMySlotCount());
        }

        @Test
        @DisplayName("添加空槽数组不抛异常")
        void testAddEmptySlots() {
            slotManager.addSlots();
            assertEquals(0, slotManager.getMySlotCount());
        }

        @Test
        @DisplayName("重复添加相同槽位不增加计数")
        void testReAddSameSlot() {
            slotManager.addSlots(0);
            slotManager.addSlots(0);
            assertEquals(1, slotManager.getMySlotCount());
        }

        @Test
        @DisplayName("添加已被其他节点占有的槽位应抛出异常")
        void testAddConflictSlot() {
            slotManager.setSlotOwner(0, OTHER_NODE_ID);
            assertThrows(IllegalStateException.class, () -> slotManager.addSlots(0));
        }

        @Test
        @DisplayName("添加无效槽位号应抛出异常")
        void testAddInvalidSlot() {
            assertThrows(IllegalArgumentException.class, () -> slotManager.addSlots(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.addSlots(SlotUtils.CLUSTER_SLOTS));
        }

        @Test
        @DisplayName("边界槽位 0 和 16383")
        void testAddBoundarySlots() {
            slotManager.addSlots(0, SlotUtils.CLUSTER_SLOTS - 1);
            assertEquals(2, slotManager.getMySlotCount());
        }
    }

    // ==================== addSlotRange 测试 ====================

    @Nested
    @DisplayName("addSlotRange 添加槽位范围测试")
    class AddSlotRangeTest {

        @Test
        @DisplayName("添加有效范围")
        void testAddValidRange() {
            slotManager.addSlotRange(0, 99);
            assertAll("范围添加校验",
                    () -> assertEquals(100, slotManager.getMySlotCount()),
                    () -> assertTrue(slotManager.isSlotLocal(0)),
                    () -> assertTrue(slotManager.isSlotLocal(99)),
                    () -> assertFalse(slotManager.isSlotLocal(100))
            );
        }

        @Test
        @DisplayName("添加单个元素范围（start == end）")
        void testAddSingleElementRange() {
            slotManager.addSlotRange(500, 500);
            assertEquals(1, slotManager.getMySlotCount());
            assertTrue(slotManager.isSlotLocal(500));
        }

        @Test
        @DisplayName("起始大于结束应抛出异常")
        void testAddInvalidRange() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.addSlotRange(100, 50));
        }

        @Test
        @DisplayName("无效槽位号应抛出异常")
        void testAddRangeInvalidSlot() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.addSlotRange(-1, 100));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.addSlotRange(0, SlotUtils.CLUSTER_SLOTS));
        }

        @Test
        @DisplayName("范围包含已被其他节点占有的槽位应抛出异常")
        void testAddRangeWithConflict() {
            slotManager.setSlotOwner(50, OTHER_NODE_ID);
            assertThrows(IllegalStateException.class,
                    () -> slotManager.addSlotRange(0, 100));
        }
    }

    // ==================== delSlots 测试 ====================

    @Nested
    @DisplayName("delSlots 删除槽位测试")
    class DelSlotsTest {

        @Test
        @DisplayName("删除单个槽位")
        void testDelSingleSlot() {
            slotManager.addSlots(0, 1, 2);
            slotManager.delSlots(1);
            assertAll("删除槽位校验",
                    () -> assertEquals(2, slotManager.getMySlotCount()),
                    () -> assertFalse(slotManager.isSlotLocal(1)),
                    () -> assertFalse(slotManager.isSlotAssigned(1)),
                    () -> assertTrue(slotManager.isSlotLocal(0))
            );
        }

        @Test
        @DisplayName("删除多个槽位")
        void testDelMultipleSlots() {
            slotManager.addSlots(0, 1, 2, 3, 4);
            slotManager.delSlots(1, 3);
            assertEquals(3, slotManager.getMySlotCount());
        }

        @Test
        @DisplayName("删除 null 槽位数组不抛异常")
        void testDelNullSlots() {
            slotManager.addSlots(0);
            slotManager.delSlots((int[]) null);
            assertEquals(1, slotManager.getMySlotCount());
        }

        @Test
        @DisplayName("删除空槽数组不抛异常")
        void testDelEmptySlots() {
            slotManager.addSlots(0);
            slotManager.delSlots();
            assertEquals(1, slotManager.getMySlotCount());
        }

        @Test
        @DisplayName("删除未分配的槽位不抛异常")
        void testDelUnassignedSlot() {
            slotManager.delSlots(0);
            assertEquals(0, slotManager.getMySlotCount());
        }

        @Test
        @DisplayName("删除无效槽位号应抛出异常")
        void testDelInvalidSlot() {
            assertThrows(IllegalArgumentException.class, () -> slotManager.delSlots(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.delSlots(SlotUtils.CLUSTER_SLOTS));
        }
    }

    // ==================== delSlotRange 测试 ====================

    @Nested
    @DisplayName("delSlotRange 删除槽位范围测试")
    class DelSlotRangeTest {

        @Test
        @DisplayName("删除有效范围")
        void testDelValidRange() {
            slotManager.addSlotRange(0, 99);
            slotManager.delSlotRange(0, 49);
            assertEquals(50, slotManager.getMySlotCount());
            assertFalse(slotManager.isSlotLocal(0));
            assertTrue(slotManager.isSlotLocal(50));
        }

        @Test
        @DisplayName("起始大于结束应抛出异常")
        void testDelInvalidRange() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.delSlotRange(100, 50));
        }

        @Test
        @DisplayName("无效槽位号应抛出异常")
        void testDelRangeInvalidSlot() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.delSlotRange(-1, 100));
        }
    }

    // ==================== setSlotOwner 测试 ====================

    @Nested
    @DisplayName("setSlotOwner 设置槽位归属测试")
    class SetSlotOwnerTest {

        @Test
        @DisplayName("设置为本节点")
        void testSetOwnerToMyself() {
            slotManager.setSlotOwner(0, MY_NODE_ID);
            assertAll("设置本节点校验",
                    () -> assertTrue(slotManager.isSlotLocal(0)),
                    () -> assertEquals(MY_NODE_ID, slotManager.getSlotOwner(0)),
                    () -> assertTrue(slotManager.isSlotAssigned(0))
            );
        }

        @Test
        @DisplayName("设置为其他节点")
        void testSetOwnerToOther() {
            slotManager.setSlotOwner(0, OTHER_NODE_ID);
            assertAll("设置其他节点校验",
                    () -> assertFalse(slotManager.isSlotLocal(0)),
                    () -> assertEquals(OTHER_NODE_ID, slotManager.getSlotOwner(0)),
                    () -> assertTrue(slotManager.isSlotAssigned(0))
            );
        }

        @Test
        @DisplayName("设置为 null 取消分配")
        void testSetOwnerToNull() {
            slotManager.setSlotOwner(0, MY_NODE_ID);
            slotManager.setSlotOwner(0, null);
            assertAll("取消分配校验",
                    () -> assertFalse(slotManager.isSlotLocal(0)),
                    () -> assertNull(slotManager.getSlotOwner(0)),
                    () -> assertFalse(slotManager.isSlotAssigned(0))
            );
        }

        @Test
        @DisplayName("从其他节点重新分配到本节点")
        void testReassignFromOtherToMyself() {
            slotManager.setSlotOwner(0, OTHER_NODE_ID);
            slotManager.setSlotOwner(0, MY_NODE_ID);
            assertTrue(slotManager.isSlotLocal(0));
            assertEquals(MY_NODE_ID, slotManager.getSlotOwner(0));
        }

        @Test
        @DisplayName("无效槽位号应抛出异常")
        void testSetOwnerInvalidSlot() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.setSlotOwner(-1, MY_NODE_ID));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.setSlotOwner(SlotUtils.CLUSTER_SLOTS, MY_NODE_ID));
        }
    }

    // ==================== 查询方法测试 ====================

    @Nested
    @DisplayName("查询方法测试")
    class QueryTest {

        @Test
        @DisplayName("getMySlots 返回的是副本")
        void testGetMySlotsReturnsClone() {
            slotManager.addSlots(0, 1, 2);
            BitSet snapshot = slotManager.getMySlots();
            snapshot.set(100);
            // 修改副本不应影响原始数据
            assertFalse(slotManager.isSlotLocal(100));
        }

        @Test
        @DisplayName("isSlotAssigned 未分配返回 false")
        void testIsSlotAssignedFalse() {
            assertFalse(slotManager.isSlotAssigned(0));
        }

        @Test
        @DisplayName("getSlotOwner 越界应抛出异常")
        void testGetSlotOwnerOverflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.getSlotOwner(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.getSlotOwner(SlotUtils.CLUSTER_SLOTS));
        }

        @Test
        @DisplayName("isSlotLocal 越界应抛出异常")
        void testIsSlotLocalOverflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.isSlotLocal(-1));
        }

        @Test
        @DisplayName("isSlotAssigned 越界应抛出异常")
        void testIsSlotAssignedOverflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.isSlotAssigned(-1));
        }

        @Test
        @DisplayName("getUnassignedSlotCount 未分配时为全部槽位")
        void testGetUnassignedSlotCount() {
            assertEquals(SlotUtils.CLUSTER_SLOTS, slotManager.getUnassignedSlotCount());
            slotManager.addSlots(0, 1, 2);
            assertEquals(SlotUtils.CLUSTER_SLOTS - 3, slotManager.getUnassignedSlotCount());
        }

        @Test
        @DisplayName("isAllSlotsAssigned 全部分配后为 true")
        void testIsAllSlotsAssigned() {
            assertFalse(slotManager.isAllSlotsAssigned());
            slotManager.addSlotRange(0, SlotUtils.CLUSTER_SLOTS - 1);
            assertTrue(slotManager.isAllSlotsAssigned());
        }
    }

    // ==================== clearMySlots 测试 ====================

    @Nested
    @DisplayName("clearMySlots 清空槽位测试")
    class ClearMySlotsTest {

        @Test
        @DisplayName("清空本节点所有槽位")
        void testClearMySlots() {
            slotManager.addSlots(0, 1, 2, 3, 4);
            slotManager.clearMySlots();
            assertAll("清空校验",
                    () -> assertEquals(0, slotManager.getMySlotCount()),
                    () -> assertFalse(slotManager.isSlotLocal(0)),
                    () -> assertFalse(slotManager.isSlotAssigned(0))
            );
        }

        @Test
        @DisplayName("清空不影响其他节点的槽位")
        void testClearDoesNotAffectOther() {
            slotManager.setSlotOwner(0, MY_NODE_ID);
            slotManager.setSlotOwner(1, OTHER_NODE_ID);
            slotManager.clearMySlots();
            assertFalse(slotManager.isSlotLocal(0));
            assertTrue(slotManager.isSlotAssigned(1));
            assertEquals(OTHER_NODE_ID, slotManager.getSlotOwner(1));
        }

        @Test
        @DisplayName("无槽位时清空不抛异常")
        void testClearEmptySlots() {
            slotManager.clearMySlots();
            assertEquals(0, slotManager.getMySlotCount());
        }
    }

    // ==================== 节点ID管理测试 ====================

    @Nested
    @DisplayName("节点ID管理测试")
    class NodeIdTest {

        @Test
        @DisplayName("设置和获取节点ID")
        void testSetGetMyNodeId() {
            slotManager.setMyNodeId(OTHER_NODE_ID);
            assertEquals(OTHER_NODE_ID, slotManager.getMyNodeId());
        }
    }

    // ==================== 槽位迁移测试 ====================

    @Nested
    @DisplayName("槽位迁移测试")
    class MigrationTest {

        @Test
        @DisplayName("设置槽位迁移状态")
        void testSetSlotMigrating() {
            slotManager.setSlotMigrating(0, OTHER_NODE_ID);
            assertAll("迁移状态校验",
                    () -> assertTrue(slotManager.isSlotMigrating(0)),
                    () -> assertEquals(OTHER_NODE_ID, slotManager.getMigratingTarget(0))
            );
        }

        @Test
        @DisplayName("取消槽位迁移状态")
        void testUnsetSlotMigrating() {
            slotManager.setSlotMigrating(0, OTHER_NODE_ID);
            slotManager.setSlotMigrating(0, null);
            assertFalse(slotManager.isSlotMigrating(0));
            assertNull(slotManager.getMigratingTarget(0));
        }

        @Test
        @DisplayName("未设置迁移时返回 false 和 null")
        void testMigrationNotSet() {
            assertFalse(slotManager.isSlotMigrating(0));
            assertNull(slotManager.getMigratingTarget(0));
        }

        @Test
        @DisplayName("迁移方法越界应抛出异常")
        void testMigrationOverflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.isSlotMigrating(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.getMigratingTarget(SlotUtils.CLUSTER_SLOTS));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.setSlotMigrating(-1, OTHER_NODE_ID));
        }
    }

    // ==================== 槽位导入测试 ====================

    @Nested
    @DisplayName("槽位导入测试")
    class ImportTest {

        @Test
        @DisplayName("设置槽位导入状态")
        void testSetSlotImporting() {
            slotManager.setSlotImporting(0, OTHER_NODE_ID);
            assertAll("导入状态校验",
                    () -> assertTrue(slotManager.isSlotImporting(0)),
                    () -> assertEquals(OTHER_NODE_ID, slotManager.getImportingSource(0))
            );
        }

        @Test
        @DisplayName("取消槽位导入状态")
        void testUnsetSlotImporting() {
            slotManager.setSlotImporting(0, OTHER_NODE_ID);
            slotManager.setSlotImporting(0, null);
            assertFalse(slotManager.isSlotImporting(0));
            assertNull(slotManager.getImportingSource(0));
        }

        @Test
        @DisplayName("未设置导入时返回 false 和 null")
        void testImportNotSet() {
            assertFalse(slotManager.isSlotImporting(0));
            assertNull(slotManager.getImportingSource(0));
        }

        @Test
        @DisplayName("导入方法越界应抛出异常")
        void testImportOverflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.isSlotImporting(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.getImportingSource(SlotUtils.CLUSTER_SLOTS));
            assertThrows(IllegalArgumentException.class,
                    () -> slotManager.setSlotImporting(-1, OTHER_NODE_ID));
        }
    }

    // ==================== 统计与 toString 测试 ====================

    @Nested
    @DisplayName("统计与 toString 测试")
    class StatisticsAndToStringTest {

        @Test
        @DisplayName("getStatistics 包含统计信息")
        void testGetStatistics() {
            slotManager.addSlots(0, 1, 2);
            String stats = slotManager.getStatistics();
            assertAll("统计信息校验",
                    () -> assertTrue(stats.contains("总槽位")),
                    () -> assertTrue(stats.contains("本节点槽位=3")),
                    () -> assertTrue(stats.contains("已分配=3"))
            );
        }

        @Test
        @DisplayName("toString 包含节点ID和槽位数量")
        void testToString() {
            slotManager.addSlots(0, 1);
            String str = slotManager.toString();
            assertTrue(str.contains(MY_NODE_ID));
            assertTrue(str.contains("DefaultSlotManager"));
        }
    }

    // ==================== 线程安全测试 ====================

    @Nested
    @DisplayName("线程安全测试")
    class ThreadSafetyTest {

        @Test
        @DisplayName("多线程并发添加槽位，总数正确")
        void testConcurrentAddSlots() throws InterruptedException {
            int threadCount = 10;
            int slotsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int base = t * slotsPerThread;
                executor.submit(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        for (int i = 0; i < slotsPerThread; i++) {
                            slotManager.addSlots(base + i);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

            assertEquals(0, errors.get());
            assertEquals(threadCount * slotsPerThread, slotManager.getMySlotCount());
        }

        @Test
        @DisplayName("多线程并发读写不抛异常")
        void testConcurrentReadWrite() throws InterruptedException {
            slotManager.addSlotRange(0, 999);
            int threadCount = 8;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        for (int i = 0; i < 200; i++) {
                            if (threadId % 2 == 0) {
                                slotManager.getSlotOwner(i);
                                slotManager.isSlotLocal(i);
                            } else {
                                slotManager.getMySlotCount();
                                slotManager.getMySlots();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
            assertEquals(0, errors.get());
        }
    }

    // ==================== 重启恢复（从 ClusterConfig 重建 SlotManager）测试 ====================

    /**
     * 回归测试：模拟全集群重启后，从已恢复的 ClusterConfig 重建 SlotManager 槽位表。
     * <p>
     * 重启路径 {@code NettyRedisServer.restoreClusterFromConfig} 只写 ClusterConfig，
     * 命令路由 {@code RedisServerHandler.checkSlotAndRedirect} 只读 SlotManager。
     * 修复方案 {@code seedSlotManagerFromConfig} 对每个已分配槽位调用
     * {@code slotManager.setSlotOwner(slot, owner)} 重建 SlotManager。
     * 此测试直接验证该 seed 逻辑的正确性。
     * </p>
     */
    @Nested
    @DisplayName("重启恢复：从 ClusterConfig 重建 SlotManager")
    class RestartSeedTest {

        /**
         * 模拟 seedSlotManagerFromConfig 的核心逻辑：
         * 遍历 0..16383，对每个已分配槽位调用 setSlotOwner(slot, owner)。
         */
        private void seedFromConfig(DefaultSlotManager manager,
                                    String[] slotAssignment, String myNodeId) {
            for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
                String owner = slotAssignment[i];
                if (owner != null) {
                    manager.setSlotOwner(i, owner);
                }
            }
            // myNodeId 仅用于断言，setSlotOwner 内部已据此区分 mySlots
            assertEquals(myNodeId, manager.getMyNodeId());
        }

        @Test
        @DisplayName("重启后本节点拥有的槽位判定为本地，其他节点槽位判定为已分配")
        void testSeedFromRecoveredConfig() {
            // 模拟从 nodes.conf 恢复的 ClusterConfig.slotAssignment
            // 本节点 MY_NODE_ID 拥有 0-5460，OTHER_NODE_ID 拥有 5461-10922，
            // 第三个节点拥有 10923-16383
            String thirdNodeId = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";
            String[] slotAssignment = new String[SlotUtils.CLUSTER_SLOTS];
            for (int i = 0; i <= 5460; i++) {
                slotAssignment[i] = MY_NODE_ID;
            }
            for (int i = 5461; i <= 10922; i++) {
                slotAssignment[i] = OTHER_NODE_ID;
            }
            for (int i = 10923; i <= 16383; i++) {
                slotAssignment[i] = thirdNodeId;
            }

            // 新建空的 SlotManager（模拟 initClusterMode 中 new DefaultSlotManager(nodeId)）
            DefaultSlotManager recovered = new DefaultSlotManager(MY_NODE_ID);
            // 重启前 slotManager 全空
            assertEquals(0, recovered.getMySlotCount());
            assertFalse(recovered.isSlotAssigned(0));

            // 执行 seed
            seedFromConfig(recovered, slotAssignment, MY_NODE_ID);

            // 验证：本节点槽位判定为本地（命令路由 isSlotLocal 返回 true，不重定向）
            assertTrue(recovered.isSlotLocal(0));
            assertTrue(recovered.isSlotLocal(5460));
            assertEquals(5461, recovered.getMySlotCount());

            // 验证：其他节点槽位判定为已分配但非本地（路由可给出正确 MOVED 目标）
            assertTrue(recovered.isSlotAssigned(5461));
            assertFalse(recovered.isSlotLocal(5461));
            assertEquals(OTHER_NODE_ID, recovered.getSlotOwner(5461));
            assertEquals(thirdNodeId, recovered.getSlotOwner(16383));

            // 验证：全部 16384 槽位已分配（isAllSlotsAssigned 为 true）
            assertTrue(recovered.isAllSlotsAssigned());
        }

        @Test
        @DisplayName("seed 前空 SlotManager 对本节点槽位返回未分配（重现 bug 现象）")
        void testEmptySlotManagerBeforeSeedReturnsUnassigned() {
            // 重现 bug：未 seed 时本节点槽位被判定为未分配 → 命令返回 CLUSTERDOWN
            DefaultSlotManager empty = new DefaultSlotManager(MY_NODE_ID);
            assertFalse(empty.isSlotAssigned(0));
            assertFalse(empty.isSlotLocal(0));
            assertFalse(empty.isAllSlotsAssigned());
        }
    }
}
