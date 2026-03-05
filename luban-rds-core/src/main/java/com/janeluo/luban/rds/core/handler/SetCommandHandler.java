package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;

import java.util.Set;

public class SetCommandHandler implements CommandHandler {
    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.SADD,
        RdsCommandConstant.SREM,
        RdsCommandConstant.SMEMBERS,
        RdsCommandConstant.SISMEMBER,
        RdsCommandConstant.SCARD
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();
        
        switch (command) {
            case RdsCommandConstant.SADD:
                return handleSAdd(database, args, store);
            case RdsCommandConstant.SREM:
                return handleSRem(database, args, store);
            case RdsCommandConstant.SMEMBERS:
                return handleSMembers(database, args, store);
            case RdsCommandConstant.SISMEMBER:
                return handleSIsMember(database, args, store);
            case RdsCommandConstant.SCARD:
                return handleSCard(database, args, store);
            default:
                return "-ERR unknown command\r\n";
        }
    }
    
    private Object handleSAdd(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sadd' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 sadd 方法
        String[] members = new String[args.length - 2];
        System.arraycopy(args, 2, members, 0, members.length);
        try {
            int added = store.sadd(database, key, members);
            return ":" + added + "\r\n";
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleSRem(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'srem' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 srem 方法
        String[] members = new String[args.length - 2];
        System.arraycopy(args, 2, members, 0, members.length);
        int removed = store.srem(database, key, members);
        
        return ":" + removed + "\r\n";
    }
    
    private Object handleSMembers(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'smembers' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 smembers 方法
        Set<String> set = store.smembers(database, key);
        
        StringBuilder result = new StringBuilder();
        result.append("*");
        result.append(set.size());
        result.append("\r\n");
        
        for (String value : set) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            result.append("$").append(bytes.length).append("\r\n").append(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).append("\r\n");
        }
        
        return result.toString();
    }
    
    private Object handleSIsMember(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sismember' command\r\n";
        }
        
        String key = args[1];
        String member = args[2];
        
        // 使用优化的 sismember 方法
        boolean isMember = store.sismember(database, key, member);
        
        return isMember ? ":1\r\n" : ":0\r\n";
    }
    
    private Object handleSCard(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'scard' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 scard 方法
        int size = store.scard(database, key);
        
        return ":" + size + "\r\n";
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
