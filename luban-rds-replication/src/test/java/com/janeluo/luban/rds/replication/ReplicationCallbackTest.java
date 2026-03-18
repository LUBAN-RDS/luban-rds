package com.janeluo.luban.rds.replication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationCallbackTest {

    @Test
    @DisplayName("测试接口方法签名")
    void testInterfaceMethods() throws NoSuchMethodException {
        assertEquals(void.class, ReplicationCallback.class.getMethod("onConnectionFailed", Throwable.class).getReturnType());
        assertEquals(void.class, ReplicationCallback.class.getMethod("onHandshakeFailed", String.class).getReturnType());
        assertEquals(void.class, ReplicationCallback.class.getMethod("onDisconnected").getReturnType());
        assertEquals(void.class, ReplicationCallback.class.getMethod("onFullSync", String.class, long.class).getReturnType());
        assertEquals(void.class, ReplicationCallback.class.getMethod("onPartialSync", String.class, long.class).getReturnType());
        assertEquals(void.class, ReplicationCallback.class.getMethod("onRdbData", io.netty.buffer.ByteBuf.class).getReturnType());
        assertEquals(void.class, ReplicationCallback.class.getMethod("onOnline").getReturnType());
        assertEquals(void.class, ReplicationCallback.class.getMethod("onCommandPropagation", io.netty.buffer.ByteBuf.class).getReturnType());
        assertEquals(String.class, ReplicationCallback.class.getMethod("getReplId").getReturnType());
        assertEquals(long.class, ReplicationCallback.class.getMethod("getReplOffset").getReturnType());
    }

    @Test
    @DisplayName("测试回调实现")
    void testCallbackImplementation() {
        ReplicationCallback callback = new ReplicationCallback() {
            @Override
            public void onConnectionFailed(Throwable cause) {}
            @Override
            public void onHandshakeFailed(String error) {}
            @Override
            public void onDisconnected() {}
            @Override
            public void onFullSync(String replId, long offset) {}
            @Override
            public void onPartialSync(String replId, long offset) {}
            @Override
            public void onRdbData(io.netty.buffer.ByteBuf data) {}
            @Override
            public void onOnline() {}
            @Override
            public void onCommandPropagation(io.netty.buffer.ByteBuf data) {}
            @Override
            public String getReplId() { return "test-repl-id"; }
            @Override
            public long getReplOffset() { return 100L; }
        };
        
        assertEquals("test-repl-id", callback.getReplId());
        assertEquals(100L, callback.getReplOffset());
    }
}