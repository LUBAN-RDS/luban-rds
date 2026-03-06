package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * AUTH命令处理器
 * 
 * <p>处理Redis AUTH命令，用于客户端密码验证。
 * 当服务器配置了requirepass时，客户端需要先通过AUTH命令验证才能执行其他命令。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class AuthCommandHandler implements CommandHandler {
    
    /**
     * 支持的命令集合
     */
    private final Set<String> supportedCommands = Sets.newHashSet("AUTH");
    
    /**
     * 配置的密码
     */
    private final String requirepass;
    
    public AuthCommandHandler(String requirepass) {
        this.requirepass = requirepass != null ? requirepass : "";
    }
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();
        
        if ("AUTH".equals(command)) {
            return handleAuth(args);
        }
        
        return "-ERR unknown command '" + command + "'\r\n";
    }
    
    /**
     * 处理 AUTH 命令
     * AUTH password
     */
    private Object handleAuth(String[] args) {
        // 如果没有配置密码
        if (requirepass == null || requirepass.isEmpty()) {
            return "-ERR Client sent AUTH, but no password is set\r\n";
        }
        
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'auth' command\r\n";
        }
        
        String password = args[1];
        
        if (requirepass.equals(password)) {
            return "+OK\r\n";
        } else {
            return "-ERR invalid password\r\n";
        }
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
