package com.janeluo.luban.rds.core.stream;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * ConsumerGroup 单元测试
 * 
 * <p>测试消费者组的创建、消费者管理、待处理消息管理等功能
 */
public class ConsumerGroupTest {

    private ConsumerGroup group;
    private static final String GROUP_NAME = "test-group";
    private static final StreamId START_ID = new StreamId(1000, 0);

    @Before
    public void setUp() {
        group = new ConsumerGroup(GROUP_NAME, START_ID);
    }

    // ==================== 消费者组创建测试 ====================

    @Test
    public void testCreateGroup() {
        assertEquals(GROUP_NAME, group.getName());
        assertEquals(START_ID, group.getLastDeliveredId());
        assertTrue(group.getCreatedAt() > 0);
        assertEquals(0, group.getConsumerCount());
        assertEquals(0L, group.getPendingCount());
    }

    @Test(expected = NullPointerException.class)
    public void testCreateGroupNullName() {
        new ConsumerGroup(null, START_ID);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateGroupEmptyName() {
        new ConsumerGroup("", START_ID);
    }

    @Test(expected = NullPointerException.class)
    public void testCreateGroupNullStartId() {
        new ConsumerGroup(GROUP_NAME, null);
    }

    // ==================== 消费者创建/删除测试 ====================

    @Test
    public void testCreateConsumer() {
        Consumer consumer = group.createConsumer("consumer1");

        assertNotNull(consumer);
        assertEquals("consumer1", consumer.getName());
        assertEquals(1, group.getConsumerCount());
        assertTrue(group.hasConsumer("consumer1"));
    }

    @Test
    public void testCreateConsumerDuplicate() {
        Consumer consumer1 = group.createConsumer("consumer1");
        Consumer consumer2 = group.createConsumer("consumer1");

        assertSame(consumer1, consumer2);
        assertEquals(1, group.getConsumerCount());
    }

    @Test(expected = NullPointerException.class)
    public void testCreateConsumerNullName() {
        group.createConsumer(null);
    }

    @Test
    public void testGetConsumer() {
        group.createConsumer("consumer1");

        Consumer consumer = group.getConsumer("consumer1");

        assertNotNull(consumer);
        assertEquals("consumer1", consumer.getName());
    }

    @Test
    public void testGetConsumerNonExistent() {
        Consumer consumer = group.getConsumer("nonexistent");
        assertNull(consumer);
    }

    @Test
    public void testHasConsumer() {
        group.createConsumer("consumer1");

        assertTrue(group.hasConsumer("consumer1"));
        assertFalse(group.hasConsumer("consumer2"));
    }

    @Test
    public void testGetConsumers() {
        group.createConsumer("consumer1");
        group.createConsumer("consumer2");

        List<Consumer> consumers = group.getConsumers();

        assertEquals(2, consumers.size());
    }

    @Test
    public void testDeleteConsumer() {
        group.createConsumer("consumer1");
        assertEquals(1, group.getConsumerCount());

        Consumer deleted = group.deleteConsumer("consumer1");

        assertNotNull(deleted);
        assertEquals("consumer1", deleted.getName());
        assertEquals(0, group.getConsumerCount());
        assertFalse(group.hasConsumer("consumer1"));
    }

    @Test
    public void testDeleteConsumerNonExistent() {
        Consumer deleted = group.deleteConsumer("nonexistent");
        assertNull(deleted);
    }

    // ==================== 待处理消息管理测试 ====================

    @Test
    public void testAddPendingMessage() {
        StreamId messageId = new StreamId(2000, 0);

        PendingMessage pending = group.addPendingMessage(messageId, "consumer1");

        assertNotNull(pending);
        assertEquals(messageId, pending.getId());
        assertEquals("consumer1", pending.getConsumerName());
        assertEquals(1L, group.getPendingCount());
        assertTrue(group.hasPendingMessage(messageId));
    }

    @Test
    public void testAddPendingMessageCreatesConsumer() {
        StreamId messageId = new StreamId(2000, 0);

        group.addPendingMessage(messageId, "consumer1");

        // 消费者应该被自动创建
        assertTrue(group.hasConsumer("consumer1"));
        assertEquals(1, group.getConsumerCount());
    }

    @Test
    public void testAddPendingMessageDuplicate() {
        StreamId messageId = new StreamId(2000, 0);

        PendingMessage pending1 = group.addPendingMessage(messageId, "consumer1");
        PendingMessage pending2 = group.addPendingMessage(messageId, "consumer2");

        // 相同 ID 的消息不应该重复添加
        assertSame(pending1, pending2);
        assertEquals(1L, group.getPendingCount());
        assertEquals("consumer1", pending1.getConsumerName()); // 消费者不变
    }

    @Test(expected = NullPointerException.class)
    public void testAddPendingMessageNullId() {
        group.addPendingMessage(null, "consumer1");
    }

    @Test(expected = NullPointerException.class)
    public void testAddPendingMessageNullConsumer() {
        group.addPendingMessage(new StreamId(2000, 0), null);
    }

    // ==================== 消息确认测试 ====================

    @Test
    public void testAckMessage() {
        StreamId messageId = new StreamId(2000, 0);
        group.addPendingMessage(messageId, "consumer1");
        assertEquals(1L, group.getPendingCount());

        PendingMessage acked = group.ackMessage(messageId);

        assertNotNull(acked);
        assertEquals(messageId, acked.getId());
        assertEquals(0L, group.getPendingCount());
        assertFalse(group.hasPendingMessage(messageId));
    }

    @Test
    public void testAckMessageNonExistent() {
        PendingMessage acked = group.ackMessage(new StreamId(999, 999));
        assertNull(acked);
    }

    @Test(expected = NullPointerException.class)
    public void testAckMessageNull() {
        group.ackMessage(null);
    }

    @Test
    public void testAckMessageRemovesFromConsumer() {
        StreamId messageId = new StreamId(2000, 0);
        group.addPendingMessage(messageId, "consumer1");

        Consumer consumer = group.getConsumer("consumer1");
        assertEquals(1, consumer.getPendingCount());

        group.ackMessage(messageId);

        assertEquals(0, consumer.getPendingCount());
    }

    // ==================== 消息转移测试（XCLAIM） ====================

    @Test
    public void testClaimMessage() {
        StreamId messageId = new StreamId(2000, 0);
        group.addPendingMessage(messageId, "consumer1");

        PendingMessage claimed = group.claimMessage(messageId, "consumer2");

        assertNotNull(claimed);
        assertEquals("consumer2", claimed.getConsumerName());
        assertEquals(2, claimed.getDeliveryCount()); // 重新传递计数增加
    }

    @Test
    public void testClaimMessageUpdatesConsumerPel() {
        StreamId messageId = new StreamId(2000, 0);
        group.addPendingMessage(messageId, "consumer1");

        group.claimMessage(messageId, "consumer2");

        Consumer consumer1 = group.getConsumer("consumer1");
        Consumer consumer2 = group.getConsumer("consumer2");

        assertEquals(0, consumer1.getPendingCount());
        assertEquals(1, consumer2.getPendingCount());
    }

    @Test
    public void testClaimMessageNonExistent() {
        PendingMessage claimed = group.claimMessage(new StreamId(999, 999), "consumer1");
        assertNull(claimed);
    }

    @Test(expected = NullPointerException.class)
    public void testClaimMessageNullId() {
        group.claimMessage(null, "consumer1");
    }

    @Test(expected = NullPointerException.class)
    public void testClaimMessageNullConsumer() {
        group.addPendingMessage(new StreamId(2000, 0), "consumer1");
        group.claimMessage(new StreamId(2000, 0), null);
    }

    // ==================== 待处理消息查询测试 ====================

    @Test
    public void testGetPendingMessage() {
        StreamId messageId = new StreamId(2000, 0);
        group.addPendingMessage(messageId, "consumer1");

        PendingMessage pending = group.getPendingMessage(messageId);

        assertNotNull(pending);
        assertEquals(messageId, pending.getId());
    }

    @Test
    public void testGetPendingMessageNonExistent() {
        PendingMessage pending = group.getPendingMessage(new StreamId(999, 999));
        assertNull(pending);
    }

    @Test
    public void testHasPendingMessage() {
        StreamId messageId = new StreamId(2000, 0);
        group.addPendingMessage(messageId, "consumer1");

        assertTrue(group.hasPendingMessage(messageId));
        assertFalse(group.hasPendingMessage(new StreamId(999, 999)));
    }

    @Test
    public void testGetPendingMessages() {
        group.addPendingMessage(new StreamId(1000, 0), "consumer1");
        group.addPendingMessage(new StreamId(2000, 0), "consumer1");
        group.addPendingMessage(new StreamId(3000, 0), "consumer2");

        List<PendingMessage> all = group.getPendingMessages(null, null, -1, null, 0);
        assertEquals(3, all.size());

        // 按范围查询
        List<PendingMessage> range = group.getPendingMessages(
            new StreamId(1500, 0), new StreamId(2500, 0), -1, null, 0);
        assertEquals(1, range.size());
        assertEquals(new StreamId(2000, 0), range.get(0).getId());
    }

    @Test
    public void testGetPendingMessagesByConsumer() {
        group.addPendingMessage(new StreamId(1000, 0), "consumer1");
        group.addPendingMessage(new StreamId(2000, 0), "consumer1");
        group.addPendingMessage(new StreamId(3000, 0), "consumer2");

        List<PendingMessage> consumer1Messages = group.getPendingMessages(
            null, null, -1, "consumer1", 0);
        assertEquals(2, consumer1Messages.size());

        List<PendingMessage> consumer2Messages = group.getPendingMessages(
            null, null, -1, "consumer2", 0);
        assertEquals(1, consumer2Messages.size());
    }

    @Test
    public void testGetPendingMessagesWithCount() {
        group.addPendingMessage(new StreamId(1000, 0), "consumer1");
        group.addPendingMessage(new StreamId(2000, 0), "consumer1");
        group.addPendingMessage(new StreamId(3000, 0), "consumer1");

        List<PendingMessage> messages = group.getPendingMessages(null, null, 2, null, 0);
        assertEquals(2, messages.size());
    }

    @Test
    public void testGetPendingMessagesWithMinIdleTime() throws InterruptedException {
        group.addPendingMessage(new StreamId(1000, 0), "consumer1");
        Thread.sleep(50); // 等待 50ms
        group.addPendingMessage(new StreamId(2000, 0), "consumer1");

        // 只获取空闲时间超过 25ms 的消息
        List<PendingMessage> messages = group.getPendingMessages(null, null, -1, null, 25);
        assertTrue(messages.size() >= 1);
    }

    @Test
    public void testGetAllPendingMessages() {
        group.addPendingMessage(new StreamId(1000, 0), "consumer1");
        group.addPendingMessage(new StreamId(2000, 0), "consumer1");

        List<PendingMessage> all = group.getAllPendingMessages();

        assertEquals(2, all.size());
    }

    @Test
    public void testGetPendingIdRange() {
        group.addPendingMessage(new StreamId(1000, 0), "consumer1");
        group.addPendingMessage(new StreamId(2000, 0), "consumer1");
        group.addPendingMessage(new StreamId(3000, 0), "consumer1");

        StreamId[] range = group.getPendingIdRange();

        assertNotNull(range);
        assertEquals(2, range.length);
        assertEquals(new StreamId(1000, 0), range[0]);
        assertEquals(new StreamId(3000, 0), range[1]);
    }

    @Test
    public void testGetPendingIdRangeEmpty() {
        StreamId[] range = group.getPendingIdRange();
        assertNull(range);
    }

    // ==================== 最后传递 ID 更新测试 ====================

    @Test
    public void testSetLastDeliveredId() {
        StreamId newId = new StreamId(2000, 0);

        group.setLastDeliveredId(newId);

        assertEquals(newId, group.getLastDeliveredId());
    }

    @Test(expected = NullPointerException.class)
    public void testSetLastDeliveredIdNull() {
        group.setLastDeliveredId(null);
    }

    // ==================== equals 和 hashCode 测试 ====================

    @Test
    public void testEquals() {
        ConsumerGroup group1 = new ConsumerGroup("group1", START_ID);
        ConsumerGroup group2 = new ConsumerGroup("group1", START_ID);
        ConsumerGroup group3 = new ConsumerGroup("group2", START_ID);

        assertEquals(group1, group2);
        assertNotEquals(group1, group3);
        assertEquals(group1, group1);
        assertNotEquals(group1, null);
        assertNotEquals(group1, "string");
    }

    @Test
    public void testHashCode() {
        ConsumerGroup group1 = new ConsumerGroup("group1", START_ID);
        ConsumerGroup group2 = new ConsumerGroup("group1", START_ID);

        assertEquals(group1.hashCode(), group2.hashCode());
    }

    // ==================== toString 测试 ====================

    @Test
    public void testToString() {
        String str = group.toString();

        assertTrue(str.contains(GROUP_NAME));
        assertTrue(str.contains("consumerCount=0"));
        assertTrue(str.contains("pendingCount=0"));
    }

    // ==================== 复杂场景测试 ====================

    @Test
    public void testMultipleConsumersWithMessages() {
        // 创建多个消费者并添加消息
        for (int i = 0; i < 5; i++) {
            StreamId messageId = new StreamId(1000 + i, 0);
            group.addPendingMessage(messageId, "consumer" + (i % 2));
        }

        assertEquals(2, group.getConsumerCount());
        assertEquals(5L, group.getPendingCount());

        // 验证每个消费者的待处理消息数
        Consumer consumer0 = group.getConsumer("consumer0");
        Consumer consumer1 = group.getConsumer("consumer1");

        assertEquals(3, consumer0.getPendingCount()); // 0, 2, 4
        assertEquals(2, consumer1.getPendingCount()); // 1, 3
    }

    @Test
    public void testAckAllMessages() {
        // 添加多条消息
        for (int i = 0; i < 5; i++) {
            group.addPendingMessage(new StreamId(1000 + i, 0), "consumer1");
        }

        assertEquals(5L, group.getPendingCount());

        // 确认所有消息
        for (int i = 0; i < 5; i++) {
            group.ackMessage(new StreamId(1000 + i, 0));
        }

        assertEquals(0L, group.getPendingCount());
        Consumer consumer = group.getConsumer("consumer1");
        assertEquals(0, consumer.getPendingCount());
    }

    @Test
    public void testDeleteConsumerWithPendingMessages() {
        // 添加消息
        group.addPendingMessage(new StreamId(1000, 0), "consumer1");
        group.addPendingMessage(new StreamId(2000, 0), "consumer1");

        assertEquals(2L, group.getPendingCount());

        // 删除消费者
        group.deleteConsumer("consumer1");

        // 消费者被删除，但消息仍在全局 PEL 中
        assertEquals(0, group.getConsumerCount());
        assertEquals(2L, group.getPendingCount());
        assertTrue(group.hasPendingMessage(new StreamId(1000, 0)));
        assertTrue(group.hasPendingMessage(new StreamId(2000, 0)));
    }

    @Test
    public void testClaimMessageToNewConsumer() {
        // 添加消息到 consumer1
        group.addPendingMessage(new StreamId(1000, 0), "consumer1");

        // 转移到新消费者 consumer2（自动创建）
        group.claimMessage(new StreamId(1000, 0), "consumer2");

        assertTrue(group.hasConsumer("consumer2"));
        Consumer consumer2 = group.getConsumer("consumer2");
        assertEquals(1, consumer2.getPendingCount());
    }
}
