package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.cluster.lifecycle.WritePauseGate;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 服务端写暂停门控实现（P1-12）。
 * <p>
 * 基于 {@link AtomicBoolean} 的轻量实现，用于手动故障转移（CLUSTER FAILOVER 普通模式）
 * 与 CLIENT PAUSE 命令：暂停期间 {@link RedisServerHandler} 写路径拒绝写命令。
 * </p>
 * <p>
 * 无暂停时 {@link #isPaused()} 为单次 volatile 读，零开销。
 * </p>
 */
public class ServerWritePauseGate implements WritePauseGate {

    private final AtomicBoolean paused = new AtomicBoolean(false);

    @Override
    public void pause() {
        paused.set(true);
    }

    @Override
    public void resume() {
        paused.set(false);
    }

    @Override
    public boolean isPaused() {
        return paused.get();
    }
}
