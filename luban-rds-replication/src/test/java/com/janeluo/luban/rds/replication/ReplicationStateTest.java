package com.janeluo.luban.rds.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationStateTest {

    @Test
    @DisplayName("测试枚举值名称")
    void testGetName() {
        assertEquals("disconnected", ReplicationState.DISCONNECTED.getName());
        assertEquals("connecting", ReplicationState.CONNECTING.getName());
        assertEquals("handshake_ping", ReplicationState.HANDSHAKE_PING.getName());
        assertEquals("handshake_auth", ReplicationState.HANDSHAKE_AUTH.getName());
        assertEquals("handshake_replconf_port", ReplicationState.HANDSHAKE_REPLCONF_PORT.getName());
        assertEquals("full_sync", ReplicationState.FULL_SYNC.getName());
        assertEquals("loading_rdb", ReplicationState.LOADING_RDB.getName());
        assertEquals("partial_sync", ReplicationState.PARTIAL_SYNC.getName());
        assertEquals("online", ReplicationState.ONLINE.getName());
        assertEquals("error", ReplicationState.ERROR.getName());
    }

    @Test
    @DisplayName("测试握手状态判断")
    void testIsHandshake() {
        assertTrue(ReplicationState.HANDSHAKE_PING.isHandshake());
        assertTrue(ReplicationState.HANDSHAKE_AUTH.isHandshake());
        assertTrue(ReplicationState.HANDSHAKE_REPLCONF_PORT.isHandshake());
        assertTrue(ReplicationState.HANDSHAKE_REPLCONF_IP.isHandshake());
        assertTrue(ReplicationState.HANDSHAKE_REPLCONF_CAPA.isHandshake());
        assertTrue(ReplicationState.HANDSHAKE_REPLCONF_ACK.isHandshake());
        
        assertFalse(ReplicationState.DISCONNECTED.isHandshake());
        assertFalse(ReplicationState.CONNECTING.isHandshake());
        assertFalse(ReplicationState.FULL_SYNC.isHandshake());
        assertFalse(ReplicationState.PARTIAL_SYNC.isHandshake());
        assertFalse(ReplicationState.LOADING_RDB.isHandshake());
        assertFalse(ReplicationState.ONLINE.isHandshake());
        assertFalse(ReplicationState.ERROR.isHandshake());
    }

    @Test
    @DisplayName("测试同步状态判断")
    void testIsSyncing() {
        assertTrue(ReplicationState.FULL_SYNC.isSyncing());
        assertTrue(ReplicationState.PARTIAL_SYNC.isSyncing());
        assertTrue(ReplicationState.LOADING_RDB.isSyncing());
        
        assertFalse(ReplicationState.DISCONNECTED.isSyncing());
        assertFalse(ReplicationState.CONNECTING.isSyncing());
        assertFalse(ReplicationState.HANDSHAKE_PING.isSyncing());
        assertFalse(ReplicationState.HANDSHAKE_AUTH.isSyncing());
        assertFalse(ReplicationState.ONLINE.isSyncing());
        assertFalse(ReplicationState.ERROR.isSyncing());
    }

    @Test
    @DisplayName("测试在线状态判断")
    void testIsOnline() {
        assertTrue(ReplicationState.ONLINE.isOnline());
        
        assertFalse(ReplicationState.DISCONNECTED.isOnline());
        assertFalse(ReplicationState.CONNECTING.isOnline());
        assertFalse(ReplicationState.HANDSHAKE_PING.isOnline());
        assertFalse(ReplicationState.FULL_SYNC.isOnline());
        assertFalse(ReplicationState.PARTIAL_SYNC.isOnline());
        assertFalse(ReplicationState.LOADING_RDB.isOnline());
        assertFalse(ReplicationState.ERROR.isOnline());
    }

    @Test
    @DisplayName("测试断开状态判断")
    void testIsDisconnected() {
        assertTrue(ReplicationState.DISCONNECTED.isDisconnected());
        assertTrue(ReplicationState.ERROR.isDisconnected());
        
        assertFalse(ReplicationState.CONNECTING.isDisconnected());
        assertFalse(ReplicationState.HANDSHAKE_PING.isDisconnected());
        assertFalse(ReplicationState.FULL_SYNC.isDisconnected());
        assertFalse(ReplicationState.PARTIAL_SYNC.isDisconnected());
        assertFalse(ReplicationState.LOADING_RDB.isDisconnected());
        assertFalse(ReplicationState.ONLINE.isDisconnected());
    }

    @Test
    @DisplayName("测试枚举值数量")
    void testEnumCount() {
        ReplicationState[] states = ReplicationState.values();
        assertEquals(13, states.length);
    }
}