package com.janeluo.luban.rds.mesh.gateway;

/**
 * Q4（2026-09-11 mesh 审计 P2）：未知命令（含拼写错误）不进 Raft——
 * gate 写入口预检未注册命令时抛出，handler 层映射为 {@code -ERR unknown command 'X'}。
 * <p>此前未知命令会生成 Raft 日志条目、三节点 apply 时各回一个错误串（日志垃圾 + 读放大）。</p>
 */
public class UnknownCommandException extends RuntimeException {

    private final String commandName;

    public UnknownCommandException(String commandName) {
        super("unknown command '" + (commandName == null ? "" : commandName) + "'");
        this.commandName = commandName;
    }

    public String getCommandName() {
        return commandName;
    }
}
