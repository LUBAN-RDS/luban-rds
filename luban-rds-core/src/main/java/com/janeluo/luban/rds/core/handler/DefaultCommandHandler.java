package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Maps;

import java.util.Map;

/**
 * 默认命令处理器
 * 
 * <p>负责管理所有命令处理器的注册和路由分发。
 * 根据命令名称将请求路由到对应的处理器执行。
 * 
 * <p>支持的功能：
 * <ul>
 *   <li>命令处理器动态注册</li>
 *   <li>命令路由分发</li>
 *   <li>访问密码验证支持</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class DefaultCommandHandler {
    
    /**
     * 命令处理器映射表
     */
    private final Map<String, CommandHandler> commandHandlers = Maps.newConcurrentMap();
    
    /**
     * 访问密码，空字符串表示不需要密码
     */
    private final String requirepass;
    
    /**
     * 使用默认配置创建命令处理器（无密码）
     */
    public DefaultCommandHandler() {
        this("");
    }
    
    /**
     * 使用指定密码创建命令处理器
     * 
     * @param requirepass 访问密码，空字符串表示不需要密码
     */
    public DefaultCommandHandler(String requirepass) {
        this.requirepass = requirepass != null ? requirepass : "";
        registerHandlers();
    }
    
    private void registerHandlers() {
        // 注册各种命令处理器
        registerHandler(new StringCommandHandler());
        registerHandler(new HashCommandHandler());
        registerHandler(new ListCommandHandler());
        registerHandler(new SetCommandHandler());
        registerHandler(new ZSetCommandHandler());
        registerHandler(new CommonCommandHandler());
        registerHandler(new ClientCommandHandler());
        registerHandler(new SelectCommandHandler());
        registerHandler(new LuaCommandHandler());
        registerHandler(new SlowLogCommandHandler());
        registerHandler(new RdsMemoryCommandHandler());
        // 注册 Stream 命令处理器
        registerHandler(new StreamCommandHandler());
        registerHandler(new StreamGroupCommandHandler());
        // 注册 RESTORE 命令处理器（P0-新3：MIGRATE 目标端导入的复制/AOF 传播还原）
        registerHandler(new RestoreCommandHandler());
        // 注册AUTH命令处理器
        registerHandler(new AuthCommandHandler(requirepass));
    }
    
    private void registerHandler(CommandHandler handler) {
        for (String command : handler.supportedCommands()) {
            commandHandlers.put(command.toUpperCase(), handler);
        }
    }
    
    public Object handle(String command, int database, String[] args, MemoryStore store) {
        CommandHandler handler = commandHandlers.get(command.toUpperCase());
        if (handler == null) {
            return "-ERR unknown command '" + command + "'\r\n";
        }
        
        return handler.handle(database, args, store);
    }
    
    /**
     * 获取配置的密码
     */
    public String getRequirepass() {
        return requirepass;
    }

    /**
     * 解析 EVAL/EVALSHA 命令的脚本文本。
     * <p>
     * 供集群从节点在路由判定时分析脚本只读性使用
     * （配合 {@link LuaScriptAnalyzer#isReadOnlyScript(String)}）。
     * <ul>
     *   <li>EVAL：直接取 {@code args[1]}（脚本文本）</li>
     *   <li>EVALSHA：按 {@code args[1]}（SHA1）查 LuaCommandHandler 脚本缓存</li>
     * </ul>
     *
     * @param commandName 命令名（EVAL/EVALSHA，大小写不敏感）
     * @param args        命令参数（含命令名，与 {@link #handle} 入参一致）
     * @return 脚本文本；非脚本命令、参数不全或 EVALSHA 未命中缓存返回 null
     */
    public String resolveScriptBody(String commandName, String[] args) {
        if (commandName == null || args == null || args.length < 2) {
            return null;
        }
        String cmd = commandName.toUpperCase();
        if ("EVAL".equals(cmd)) {
            return args[1];
        }
        if ("EVALSHA".equals(cmd)) {
            CommandHandler handler = commandHandlers.get("EVALSHA");
            if (handler instanceof LuaCommandHandler) {
                return ((LuaCommandHandler) handler).getScriptBySha1(args[1]);
            }
            return null;
        }
        return null;
    }
    
    /**
     * 是否需要密码验证
     */
    public boolean isAuthRequired() {
        return requirepass != null && !requirepass.isEmpty();
    }
}
