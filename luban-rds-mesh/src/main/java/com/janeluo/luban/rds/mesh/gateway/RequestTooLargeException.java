package com.janeluo.luban.rds.mesh.gateway;

/**
 * Q7（2026-09-11 mesh 审计 P2）：写条目超过编码大小上限——propose 前预检直接失败。
 * <p>此前超限条目会追加进 log 后经总线发送，被 Encoder（16MB 上限）静默丢弃，
 * Leader 陷入 100ms 重发-被丢死循环，propose future 永久悬挂。</p>
 */
public class RequestTooLargeException extends RuntimeException {

    public RequestTooLargeException(String message) {
        super(message);
    }
}
