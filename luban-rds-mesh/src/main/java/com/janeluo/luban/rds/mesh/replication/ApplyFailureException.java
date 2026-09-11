package com.janeluo.luban.rds.mesh.replication;

/**
 * P1-5（2026-09-11 mesh 审计）：apply 阶段基础设施级失败——节点本地异常（存储 OOM、
 * Lua 引擎故障等）导致命令未能确定性地执行。此前被 LogApplier 转成 {@code -ERR} 字符串
 * 静默吞掉、lastApplied 照常推进 → 三节点状态分叉不可见。
 * <p>本异常触发 apply 循环 <b>fail-stop</b>：lastApplied 冻结、apply 挂起，人工恢复。</p>
 */
public class ApplyFailureException extends RuntimeException {

    public ApplyFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
