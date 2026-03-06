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
     * 是否需要密码验证
     */
    public boolean isAuthRequired() {
        return requirepass != null && !requirepass.isEmpty();
    }
}
