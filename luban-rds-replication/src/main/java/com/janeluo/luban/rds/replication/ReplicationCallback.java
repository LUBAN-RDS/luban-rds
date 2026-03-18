package com.janeluo.luban.rds.replication;

import io.netty.buffer.ByteBuf;

/**
 * 复制回调接口
 */
public interface ReplicationCallback {
    
    void onConnectionFailed(Throwable cause);
    void onHandshakeFailed(String error);
    void onDisconnected();
    void onFullSync(String replId, long offset);
    void onPartialSync(String replId, long offset);
    void onRdbData(ByteBuf data);
    void onOnline();
    void onCommandPropagation(ByteBuf data);
    String getReplId();
    long getReplOffset();
}
