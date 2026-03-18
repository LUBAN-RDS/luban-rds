package com.janeluo.luban.rds.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationConstantsTest {

    @Test
    @DisplayName("测试复制ID长度")
    void testReplIdLength() {
        assertEquals(40, ReplicationConstants.REPL_ID_LENGTH);
    }

    @Test
    @DisplayName("测试默认复制ID")
    void testDefaultReplId() {
        assertEquals("0000000000000000000000000000000000000000", ReplicationConstants.DEFAULT_REPL_ID);
        assertEquals(40, ReplicationConstants.DEFAULT_REPL_ID.length());
    }

    @Test
    @DisplayName("测试复制能力常量")
    void testReplicationCapabilities() {
        assertEquals("psync2", ReplicationConstants.REPL_CAPA_PSYNC2);
        assertEquals("psync", ReplicationConstants.REPL_CAPA_PSYNC);
        assertEquals("eof", ReplicationConstants.REPL_CAPA_EOF);
    }

    @Test
    @DisplayName("测试默认心跳间隔")
    void testDefaultPingPeriod() {
        assertEquals(10, ReplicationConstants.DEFAULT_PING_PERIOD);
    }

    @Test
    @DisplayName("测试默认复制超时")
    void testDefaultReplTimeout() {
        assertEquals(60, ReplicationConstants.DEFAULT_REPL_TIMEOUT);
    }

    @Test
    @DisplayName("测试默认积压缓冲区大小")
    void testDefaultBacklogSize() {
        assertEquals(1024 * 1024, ReplicationConstants.DEFAULT_BACKLOG_SIZE);
    }

    @Test
    @DisplayName("测试默认积压缓冲区TTL")
    void testDefaultBacklogTtl() {
        assertEquals(3600, ReplicationConstants.DEFAULT_BACKLOG_TTL);
    }

    @Test
    @DisplayName("测试复制协议版本")
    void testReplProtocolVersion() {
        assertEquals(4, ReplicationConstants.REPL_PROTOCOL_VERSION);
    }

    @Test
    @DisplayName("测试从节点监听端口偏移")
    void testDefaultSlaveListenPortOffset() {
        assertEquals(10000, ReplicationConstants.DEFAULT_SLAVE_LISTEN_PORT_OFFSET);
    }

    @Test
    @DisplayName("测试私有构造函数")
    void testPrivateConstructor() throws Exception {
        var constructor = ReplicationConstants.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        ReplicationConstants instance = constructor.newInstance();
        assertNotNull(instance);
    }
}