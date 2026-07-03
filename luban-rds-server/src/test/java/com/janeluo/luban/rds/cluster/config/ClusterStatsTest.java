package com.janeluo.luban.rds.cluster.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClusterStats 集群统计信息模型单元测试
 *
 * <p>覆盖默认值、所有 getter/setter、消息计数累加、健康判断及 toString。
 * 包含正向与边界值测试。</p>
 */
@DisplayName("ClusterStats 集群统计模型测试")
class ClusterStatsTest {

    private ClusterStats stats;

    @BeforeEach
    void setUp() {
        stats = new ClusterStats();
    }

    // ==================== 默认值测试 ====================

    @Nested
    @DisplayName("默认值测试")
    class DefaultValueTest {

        @Test
        @DisplayName("默认构造方法初始化所有字段为默认值")
        void testDefaultValues() {
            assertAll("默认值校验",
                    () -> assertEquals("fail", stats.getState()),
                    () -> assertEquals(0, stats.getSlotsAssigned()),
                    () -> assertEquals(0, stats.getSlotsOk()),
                    () -> assertEquals(0, stats.getSlotsPfail()),
                    () -> assertEquals(0, stats.getSlotsFail()),
                    () -> assertEquals(0, stats.getKnownNodes()),
                    () -> assertEquals(0, stats.getSize()),
                    () -> assertEquals(0, stats.getCurrentEpoch()),
                    () -> assertEquals(0, stats.getMyEpoch()),
                    () -> assertEquals(0, stats.getMessagesSent()),
                    () -> assertEquals(0, stats.getMessagesReceived())
            );
        }
    }

    // ==================== Getter/Setter 测试 ====================

    @Nested
    @DisplayName("Getter/Setter 测试")
    class GetterSetterTest {

        @Test
        @DisplayName("设置和获取集群状态")
        void testState() {
            stats.setState("ok");
            assertEquals("ok", stats.getState());
        }

        @Test
        @DisplayName("设置和获取已分配槽位数量")
        void testSlotsAssigned() {
            stats.setSlotsAssigned(16384);
            assertEquals(16384, stats.getSlotsAssigned());
        }

        @Test
        @DisplayName("设置和获取正常槽位数量")
        void testSlotsOk() {
            stats.setSlotsOk(16000);
            assertEquals(16000, stats.getSlotsOk());
        }

        @Test
        @DisplayName("设置和获取 PFAIL 槽位数量")
        void testSlotsPfail() {
            stats.setSlotsPfail(100);
            assertEquals(100, stats.getSlotsPfail());
        }

        @Test
        @DisplayName("设置和获取 FAIL 槽位数量")
        void testSlotsFail() {
            stats.setSlotsFail(50);
            assertEquals(50, stats.getSlotsFail());
        }

        @Test
        @DisplayName("设置和获取已知节点数量")
        void testKnownNodes() {
            stats.setKnownNodes(6);
            assertEquals(6, stats.getKnownNodes());
        }

        @Test
        @DisplayName("设置和获取集群规模（主节点数）")
        void testSize() {
            stats.setSize(3);
            assertEquals(3, stats.getSize());
        }

        @Test
        @DisplayName("设置和获取当前配置纪元")
        void testCurrentEpoch() {
            stats.setCurrentEpoch(10L);
            assertEquals(10L, stats.getCurrentEpoch());
        }

        @Test
        @DisplayName("设置和获取当前节点配置纪元")
        void testMyEpoch() {
            stats.setMyEpoch(5L);
            assertEquals(5L, stats.getMyEpoch());
        }

        @Test
        @DisplayName("设置和获取已发送消息数量")
        void testMessagesSent() {
            stats.setMessagesSent(1000L);
            assertEquals(1000L, stats.getMessagesSent());
        }

        @Test
        @DisplayName("设置和获取已接收消息数量")
        void testMessagesReceived() {
            stats.setMessagesReceived(2000L);
            assertEquals(2000L, stats.getMessagesReceived());
        }
    }

    // ==================== 消息计数累加测试 ====================

    @Nested
    @DisplayName("消息计数累加测试")
    class MessageIncrementTest {

        @Test
        @DisplayName("累加已发送消息计数")
        void testIncrementMessagesSent() {
            stats.incrementMessagesSent(100);
            assertEquals(100, stats.getMessagesSent());
            stats.incrementMessagesSent(50);
            assertEquals(150, stats.getMessagesSent());
        }

        @Test
        @DisplayName("累加已接收消息计数")
        void testIncrementMessagesReceived() {
            stats.incrementMessagesReceived(200);
            assertEquals(200, stats.getMessagesReceived());
            stats.incrementMessagesReceived(300);
            assertEquals(500, stats.getMessagesReceived());
        }

        @Test
        @DisplayName("累加零值不影响计数")
        void testIncrementZero() {
            stats.incrementMessagesSent(100);
            stats.incrementMessagesSent(0);
            assertEquals(100, stats.getMessagesSent());
        }

        @Test
        @DisplayName("累加负值会减少计数（无校验）")
        void testIncrementNegative() {
            stats.incrementMessagesSent(100);
            stats.incrementMessagesSent(-30);
            assertEquals(70, stats.getMessagesSent());
        }
    }

    // ==================== 健康判断测试 ====================

    @Nested
    @DisplayName("健康判断测试")
    class HealthyTest {

        @Test
        @DisplayName("状态为 ok 时集群健康")
        void testIsHealthyWhenOk() {
            stats.setState("ok");
            assertTrue(stats.isHealthy());
        }

        @Test
        @DisplayName("状态为 OK（大写）时集群健康（大小写不敏感）")
        void testIsHealthyCaseInsensitive() {
            stats.setState("OK");
            assertTrue(stats.isHealthy());
        }

        @Test
        @DisplayName("状态为 fail 时集群不健康")
        void testIsNotHealthyWhenFail() {
            stats.setState("fail");
            assertFalse(stats.isHealthy());
        }

        @Test
        @DisplayName("默认状态为 fail 时集群不健康")
        void testIsNotHealthyByDefault() {
            assertFalse(stats.isHealthy());
        }
    }

    // ==================== toString 测试 ====================

    @Nested
    @DisplayName("toString 测试")
    class ToStringTest {

        @Test
        @DisplayName("toString 包含所有关键字段")
        void testToString() {
            stats.setState("ok");
            stats.setSlotsAssigned(16384);
            stats.setSlotsOk(16384);
            stats.setKnownNodes(6);
            stats.setSize(3);
            stats.setCurrentEpoch(10L);
            stats.setMessagesSent(1000L);

            String str = stats.toString();
            assertAll("toString 校验",
                    () -> assertTrue(str.contains("state='ok'")),
                    () -> assertTrue(str.contains("slotsAssigned=16384")),
                    () -> assertTrue(str.contains("slotsOk=16384")),
                    () -> assertTrue(str.contains("knownNodes=6")),
                    () -> assertTrue(str.contains("size=3")),
                    () -> assertTrue(str.contains("currentEpoch=10")),
                    () -> assertTrue(str.contains("messagesSent=1000")),
                    () -> assertTrue(str.contains("ClusterStats"))
            );
        }
    }
}
