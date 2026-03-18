package com.janeluo.luban.rds.cluster.slot;

import org.junit.Before;
import org.junit.Test;

import java.util.BitSet;

import static org.junit.Assert.*;

/**
 * DefaultSlotManager 单元测试
 * 验证槽位管理功能的正确性
 */
public class DefaultSlotManagerTest {

    private static final String NODE_ID_1 = "1234567890abcdef1234567890abcdef12345678";
    private static final String NODE_ID_2 = "abcdef1234567890abcdef1234567890abcdef12";

    private DefaultSlotManager slotManager;

    @Before
    public void setUp() {
        slotManager = new DefaultSlotManager(NODE_ID_1);
    }

    /**
     * 测试基本构造
     */
    @Test
    public void testConstructor() {
        DefaultSlotManager manager = new DefaultSlotManager();
        assertNull(manager.getMyNodeId());
        assertEquals(0, manager.getMySlotCount());

        DefaultSlotManager manager2 = new DefaultSlotManager(NODE_ID_1);
        assertEquals(NODE_ID_1, manager2.getMyNodeId());
        assertEquals(0, manager2.getMySlotCount());
    }

    /**
     * 测试添加单个槽位
     */
    @Test
    public void testAddSlot() {
        slotManager.addSlots(0);
        assertTrue(slotManager.isSlotLocal(0));
        assertEquals(1, slotManager.getMySlotCount());
        assertEquals(NODE_ID_1, slotManager.getSlotOwner(0));
    }

    /**
     * 测试添加多个槽位
     */
    @Test
    public void testAddSlots() {
        slotManager.addSlots(0, 1, 2, 3, 4);
        assertEquals(5, slotManager.getMySlotCount());

        for (int i = 0; i < 5; i++) {
            assertTrue(slotManager.isSlotLocal(i));
            assertEquals(NODE_ID_1, slotManager.getSlotOwner(i));
        }
    }

    /**
     * 测试添加槽位范围
     */
    @Test
    public void testAddSlotRange() {
        slotManager.addSlotRange(0, 99);
        assertEquals(100, slotManager.getMySlotCount());

        for (int i = 0; i < 100; i++) {
            assertTrue(slotManager.isSlotLocal(i));
        }

        // 验证范围外的槽位
        assertFalse(slotManager.isSlotLocal(100));
        assertFalse(slotManager.isSlotLocal(16383));
    }

    /**
     * 测试移除槽位
     */
    @Test
    public void testDelSlots() {
        slotManager.addSlots(0, 1, 2, 3, 4);
        assertEquals(5, slotManager.getMySlotCount());

        slotManager.delSlots(0, 2, 4);
        assertEquals(2, slotManager.getMySlotCount());

        assertFalse(slotManager.isSlotLocal(0));
        assertTrue(slotManager.isSlotLocal(1));
        assertFalse(slotManager.isSlotLocal(2));
        assertTrue(slotManager.isSlotLocal(3));
        assertFalse(slotManager.isSlotLocal(4));
    }

    /**
     * 测试移除槽位范围
     */
    @Test
    public void testDelSlotRange() {
        slotManager.addSlotRange(0, 99);
        assertEquals(100, slotManager.getMySlotCount());

        slotManager.delSlotRange(0, 49);
        assertEquals(50, slotManager.getMySlotCount());

        for (int i = 0; i < 50; i++) {
            assertFalse(slotManager.isSlotLocal(i));
        }
        for (int i = 50; i < 100; i++) {
            assertTrue(slotManager.isSlotLocal(i));
        }
    }

    /**
     * 测试获取本节点槽位
     */
    @Test
    public void testGetMySlots() {
        slotManager.addSlots(0, 100, 1000, 10000);

        BitSet mySlots = slotManager.getMySlots();
        assertEquals(4, mySlots.cardinality());

        assertTrue(mySlots.get(0));
        assertTrue(mySlots.get(100));
        assertTrue(mySlots.get(1000));
        assertTrue(mySlots.get(10000));

        // 验证返回的是副本
        mySlots.set(9999);
        assertFalse(slotManager.isSlotLocal(9999));
    }

    /**
     * 测试设置槽位所属节点
     */
    @Test
    public void testSetSlotOwner() {
        // 分配给当前节点
        slotManager.setSlotOwner(0, NODE_ID_1);
        assertTrue(slotManager.isSlotLocal(0));
        assertEquals(NODE_ID_1, slotManager.getSlotOwner(0));

        // 分配给其他节点
        slotManager.setSlotOwner(1, NODE_ID_2);
        assertFalse(slotManager.isSlotLocal(1));
        assertEquals(NODE_ID_2, slotManager.getSlotOwner(1));

        // 取消分配
        slotManager.setSlotOwner(0, null);
        assertFalse(slotManager.isSlotLocal(0));
        assertNull(slotManager.getSlotOwner(0));
    }

    /**
     * 测试清空槽位
     */
    @Test
    public void testClearMySlots() {
        slotManager.addSlotRange(0, 99);
        assertEquals(100, slotManager.getMySlotCount());

        slotManager.clearMySlots();
        assertEquals(0, slotManager.getMySlotCount());

        for (int i = 0; i < 100; i++) {
            assertFalse(slotManager.isSlotLocal(i));
            assertNull(slotManager.getSlotOwner(i));
        }
    }

    /**
     * 测试检查槽位是否已分配
     */
    @Test
    public void testIsSlotAssigned() {
        assertFalse(slotManager.isSlotAssigned(0));

        slotManager.addSlots(0);
        assertTrue(slotManager.isSlotAssigned(0));

        slotManager.setSlotOwner(1, NODE_ID_2);
        assertTrue(slotManager.isSlotAssigned(1));

        slotManager.delSlots(0);
        assertFalse(slotManager.isSlotAssigned(0));
    }

    /**
     * 测试未分配槽位数量
     */
    @Test
    public void testGetUnassignedSlotCount() {
        assertEquals(SlotUtils.CLUSTER_SLOTS, slotManager.getUnassignedSlotCount());

        slotManager.addSlotRange(0, 99);
        assertEquals(SlotUtils.CLUSTER_SLOTS - 100, slotManager.getUnassignedSlotCount());

        slotManager.setSlotOwner(100, NODE_ID_2);
        assertEquals(SlotUtils.CLUSTER_SLOTS - 101, slotManager.getUnassignedSlotCount());
    }

    /**
     * 测试是否所有槽位都已分配
     */
    @Test
    public void testIsAllSlotsAssigned() {
        assertFalse(slotManager.isAllSlotsAssigned());

        // 分配所有槽位
        slotManager.addSlotRange(0, SlotUtils.CLUSTER_SLOTS - 1);
        assertTrue(slotManager.isAllSlotsAssigned());

        // 取消一个
        slotManager.delSlots(0);
        assertFalse(slotManager.isAllSlotsAssigned());
    }

    /**
     * 测试重复分配槽位
     */
    @Test(expected = IllegalStateException.class)
    public void testAddSlotAlreadyAssignedToOther() {
        slotManager.setSlotOwner(0, NODE_ID_2);
        slotManager.addSlots(0); // 应该抛出异常
    }

    /**
     * 测试重复分配给同一节点
     */
    @Test
    public void testAddSlotAlreadyAssignedToSelf() {
        slotManager.addSlots(0);
        slotManager.addSlots(0); // 重复分配给自己应该成功
        assertTrue(slotManager.isSlotLocal(0));
    }

    /**
     * 测试无效槽位号
     */
    @Test(expected = IllegalArgumentException.class)
    public void testAddInvalidSlot() {
        slotManager.addSlots(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddSlotOutOfRange() {
        slotManager.addSlots(16384);
    }

    /**
     * 测试无效槽位范围
     */
    @Test(expected = IllegalArgumentException.class)
    public void testAddInvalidSlotRange() {
        slotManager.addSlotRange(100, 50); // start > end
    }

    /**
     * 测试设置节点ID
     */
    @Test
    public void testSetMyNodeId() {
        slotManager.setMyNodeId(NODE_ID_2);
        assertEquals(NODE_ID_2, slotManager.getMyNodeId());
    }

    /**
     * 测试统计信息
     */
    @Test
    public void testGetStatistics() {
        slotManager.addSlotRange(0, 99);
        String stats = slotManager.getStatistics();

        assertNotNull(stats);
        assertTrue(stats.contains("总槽位=16384"));
        assertTrue(stats.contains("本节点槽位=100"));
        assertTrue(stats.contains("已分配=100"));
        assertTrue(stats.contains("未分配=16284"));
    }

    /**
     * 测试 toString
     */
    @Test
    public void testToString() {
        slotManager.addSlots(0, 1, 2);
        String str = slotManager.toString();

        assertNotNull(str);
        assertTrue(str.contains(NODE_ID_1));
        assertTrue(str.contains("mySlotCount=3"));
    }

    /**
     * 测试空参数处理
     */
    @Test
    public void testEmptyParams() {
        // 空数组不应该抛出异常
        slotManager.addSlots();
        slotManager.delSlots();

        assertEquals(0, slotManager.getMySlotCount());
    }

    /**
     * 测试 null 参数处理
     */
    @Test
    public void testNullParams() {
        // null 数组不应该抛出异常
        slotManager.addSlots((int[]) null);
        slotManager.delSlots((int[]) null);

        assertEquals(0, slotManager.getMySlotCount());
    }

    /**
     * 测试边界槽位
     */
    @Test
    public void testBoundarySlots() {
        // 第一个和最后一个槽位
        slotManager.addSlots(0, SlotUtils.CLUSTER_SLOTS - 1);
        assertTrue(slotManager.isSlotLocal(0));
        assertTrue(slotManager.isSlotLocal(SlotUtils.CLUSTER_SLOTS - 1));
        assertEquals(2, slotManager.getMySlotCount());
    }

    /**
     * 测试多线程安全性
     */
    @Test
    public void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int slotsPerThread = 100;
        Thread[] threads = new Thread[threadCount];

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                int startSlot = threadId * slotsPerThread;
                for (int i = 0; i < slotsPerThread; i++) {
                    slotManager.addSlots(startSlot + i);
                }
            });
        }

        // 启动所有线程
        for (Thread thread : threads) {
            thread.start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            thread.join();
        }

        // 验证结果
        assertEquals(threadCount * slotsPerThread, slotManager.getMySlotCount());
    }

    /**
     * 测试并发读写
     */
    @Test
    public void testConcurrentReadWrite() throws InterruptedException {
        // 先添加一些槽位
        slotManager.addSlotRange(0, 999);

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                slotManager.delSlots(i);
                slotManager.addSlots(i);
            }
        });

        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                slotManager.isSlotLocal(i);
                slotManager.getSlotOwner(i);
                slotManager.getMySlotCount();
            }
        });

        writer.start();
        reader.start();

        writer.join();
        reader.join();

        // 验证最终状态一致
        assertEquals(1000, slotManager.getMySlotCount());
    }
}
