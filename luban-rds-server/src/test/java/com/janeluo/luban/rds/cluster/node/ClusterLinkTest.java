package com.janeluo.luban.rds.cluster.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClusterLink 集群节点连接信息模型单元测试
 *
 * <p>覆盖构造方法、getter/setter、交互时间更新、重置及 toString。
 * 包含正向与边界值测试。</p>
 */
@DisplayName("ClusterLink 集群连接模型测试")
class ClusterLinkTest {

    private ClusterLink link;

    @BeforeEach
    void setUp() {
        link = new ClusterLink();
    }

    // ==================== 构造方法测试 ====================

    @Nested
    @DisplayName("构造方法测试")
    class ConstructorTest {

        @Test
        @DisplayName("默认构造方法初始化默认值")
        void testDefaultConstructor() {
            assertAll("默认值校验",
                    () -> assertNotNull(link),
                    () -> assertFalse(link.isConnected()),
                    () -> assertTrue(link.getLastInteractionTime() > 0),
                    () -> assertEquals(0, link.getOutboundBufferSize())
            );
        }

        @Test
        @DisplayName("带参数构造方法设置所有字段")
        void testParameterizedConstructor() {
            long time = System.currentTimeMillis();
            ClusterLink customLink = new ClusterLink(true, time, 1024);
            assertAll("参数构造校验",
                    () -> assertTrue(customLink.isConnected()),
                    () -> assertEquals(time, customLink.getLastInteractionTime()),
                    () -> assertEquals(1024, customLink.getOutboundBufferSize())
            );
        }

        @Test
        @DisplayName("带参数构造方法边界值：未连接、零缓冲区")
        void testParameterizedConstructorBoundary() {
            ClusterLink boundaryLink = new ClusterLink(false, 0, 0);
            assertFalse(boundaryLink.isConnected());
            assertEquals(0, boundaryLink.getLastInteractionTime());
            assertEquals(0, boundaryLink.getOutboundBufferSize());
        }
    }

    // ==================== Getter/Setter 测试 ====================

    @Nested
    @DisplayName("Getter/Setter 测试")
    class GetterSetterTest {

        @Test
        @DisplayName("设置和获取连接状态")
        void testConnected() {
            link.setConnected(true);
            assertTrue(link.isConnected());
            link.setConnected(false);
            assertFalse(link.isConnected());
        }

        @Test
        @DisplayName("设置和获取最后交互时间")
        void testLastInteractionTime() {
            long time = System.currentTimeMillis();
            link.setLastInteractionTime(time);
            assertEquals(time, link.getLastInteractionTime());
        }

        @Test
        @DisplayName("设置和获取出站缓冲区大小")
        void testOutboundBufferSize() {
            link.setOutboundBufferSize(2048);
            assertEquals(2048, link.getOutboundBufferSize());
        }

        @Test
        @DisplayName("设置负的出站缓冲区大小（无校验，允许负值）")
        void testNegativeOutboundBufferSize() {
            link.setOutboundBufferSize(-100);
            assertEquals(-100, link.getOutboundBufferSize());
        }
    }

    // ==================== 交互时间更新测试 ====================

    @Nested
    @DisplayName("交互时间更新测试")
    class InteractionTimeTest {

        @Test
        @DisplayName("更新交互时间使时间戳增大")
        void testUpdateInteractionTime() throws InterruptedException {
            long before = link.getLastInteractionTime();
            Thread.sleep(10);
            link.updateInteractionTime();
            long after = link.getLastInteractionTime();
            assertTrue(after > before);
        }
    }

    // ==================== 重置方法测试 ====================

    @Nested
    @DisplayName("重置方法测试")
    class ResetTest {

        @Test
        @DisplayName("重置后连接断开、缓冲区清零")
        void testReset() {
            link.setConnected(true);
            link.setOutboundBufferSize(1024);
            long beforeReset = link.getLastInteractionTime();

            link.reset();

            assertAll("重置后校验",
                    () -> assertFalse(link.isConnected()),
                    () -> assertEquals(0, link.getOutboundBufferSize()),
                    () -> assertTrue(link.getLastInteractionTime() >= beforeReset)
            );
        }
    }

    // ==================== toString 测试 ====================

    @Nested
    @DisplayName("toString 测试")
    class ToStringTest {

        @Test
        @DisplayName("toString 包含关键字段")
        void testToString() {
            link.setConnected(true);
            link.setOutboundBufferSize(512);
            String str = link.toString();
            assertAll("toString 校验",
                    () -> assertTrue(str.contains("connected=true")),
                    () -> assertTrue(str.contains("outboundBufferSize=512")),
                    () -> assertTrue(str.contains("ClusterLink"))
            );
        }
    }
}
