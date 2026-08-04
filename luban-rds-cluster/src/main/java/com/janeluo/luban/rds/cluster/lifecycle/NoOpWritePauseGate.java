package com.janeluo.luban.rds.cluster.lifecycle;

/**
 * {@link WritePauseGate} 的 no-op 默认实现（P1-12）。
 * <p>
 * 供非集群模式、单元测试或未装配 server 写暂停门控的场景使用。
 * 永不暂停（isPaused() 恒为 false），保证手动 failover 在无 server 支持时仍可降级运行。
 * </p>
 */
public class NoOpWritePauseGate implements WritePauseGate {

    @Override
    public void pause() {
        // no-op
    }

    @Override
    public void resume() {
        // no-op
    }

    @Override
    public boolean isPaused() {
        return false;
    }
}
