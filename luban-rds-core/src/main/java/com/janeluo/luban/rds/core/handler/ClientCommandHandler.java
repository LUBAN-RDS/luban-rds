package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;

import java.util.Set;

public class ClientCommandHandler implements CommandHandler {
    private final Set<String> supportedCommands = Sets.newHashSet(
        "CLIENT"
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        if (args.length < 1) {
            return "-ERR wrong number of arguments for 'client' command\r\n";
        }
        
        String subCommand = args[1].toUpperCase();
        
        switch (subCommand) {
            case "KILL":
                return handleClientKill(args, store);
            case "LIST":
                return handleClientList(args, store);
            case "GETNAME":
                return handleClientGetname(args, store);
            case "PAUSE":
                return handleClientPause(args, store);
            case "SETNAME":
                return handleClientSetname(args, store);
            default:
                return "-ERR unknown subcommand '" + subCommand + "' for 'client' command\r\n";
        }
    }
    
    private Object handleClientKill(String[] args, MemoryStore store) {
        // 模拟关闭客户端连接
        return "+OK\r\n";
    }
    
    private Object handleClientList(String[] args, MemoryStore store) {
        // 模拟客户端连接列表
        String clientList = "id=1 addr=127.0.0.1:9736 fd=6 name= age=0 idle=0 flags=N db=0 sub=0 psub=0 multi=-1 qbuf=0 qbuf-free=32768 obl=0 oll=0 omem=0 events=r cmd=client\r\n";
        return "$" + clientList.length() + "\r\n" + clientList + "\r\n";
    }
    
    private Object handleClientGetname(String[] args, MemoryStore store) {
        // 默认返回nil，实际实现需要访问客户端连接信息
        return "$-1\r\n";
    }
    
    private Object handleClientPause(String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'client pause' command\r\n";
        }
        
        try {
            Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        // 模拟暂停客户端命令执行
        return "+OK\r\n";
    }
    
    private Object handleClientSetname(String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'client setname' command\r\n";
        }
        
        // 模拟设置连接名称，实际实现需要访问客户端连接信息
        return "+OK\r\n";
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
