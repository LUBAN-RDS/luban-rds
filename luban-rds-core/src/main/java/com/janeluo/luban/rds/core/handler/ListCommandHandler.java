package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Set;

public class ListCommandHandler implements CommandHandler {
    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.LPUSH,
        RdsCommandConstant.RPUSH,
        RdsCommandConstant.LPOP,
        RdsCommandConstant.RPOP,
        RdsCommandConstant.LLEN,
        RdsCommandConstant.LRANGE,
        RdsCommandConstant.LREM,
        RdsCommandConstant.LINDEX,
        RdsCommandConstant.LSET
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();
        
        switch (command) {
            case RdsCommandConstant.LPUSH:
                return handleLPush(database, args, store);
            case RdsCommandConstant.RPUSH:
                return handleRPush(database, args, store);
            case RdsCommandConstant.LPOP:
                return handleLPop(database, args, store);
            case RdsCommandConstant.RPOP:
                return handleRPop(database, args, store);
            case RdsCommandConstant.LLEN:
                return handleLLen(database, args, store);
            case RdsCommandConstant.LRANGE:
                return handleLRange(database, args, store);
            case RdsCommandConstant.LREM:
                return handleLRem(database, args, store);
            case RdsCommandConstant.LINDEX:
                return handleLIndex(database, args, store);
            case RdsCommandConstant.LSET:
                return handleLSet(database, args, store);
            default:
                return "-ERR unknown command\r\n";
        }
    }
    
    private Object handleLPush(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'lpush' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 lpush 方法
        String[] values = new String[args.length - 2];
        System.arraycopy(args, 2, values, 0, values.length);
        try {
            int size = store.lpush(database, key, values);
            return ":" + size + "\r\n";
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleRPush(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'rpush' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 rpush 方法
        String[] values = new String[args.length - 2];
        System.arraycopy(args, 2, values, 0, values.length);
        try {
            int size = store.rpush(database, key, values);
            return ":" + size + "\r\n";
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleLPop(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'lpop' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 lpop 方法
        String value = store.lpop(database, key);
        
        if (value == null) {
            return "$-1\r\n";
        }
        
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return "$" + bytes.length + "\r\n" + new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1) + "\r\n";
    }
    
    private Object handleRPop(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'rpop' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 rpop 方法
        String value = store.rpop(database, key);
        
        if (value == null) {
            return "$-1\r\n";
        }
        
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return "$" + bytes.length + "\r\n" + new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1) + "\r\n";
    }
    
    private Object handleLLen(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'llen' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 llen 方法
        int len = store.llen(database, key);
        
        return ":" + len + "\r\n";
    }
    
    private Object handleLRem(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'lrem' command\r\n";
        }
        
        String key = args[1];
        int count;
        try {
            count = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        String value = args[3];
        
        int removed = store.lrem(database, key, count, value);
        return ":" + removed + "\r\n";
    }

    private Object handleLIndex(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'lindex' command\r\n";
        }
        
        String key = args[1];
        int index;
        try {
            index = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        try {
            String value = store.lindex(database, key, index);
            if (value == null) {
                return "$-1\r\n";
            }
            int byteLen = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            return "$" + byteLen + "\r\n" + value + "\r\n";
        } catch (RuntimeException e) {
             if (e.getMessage() != null && e.getMessage().startsWith("WRONGTYPE")) {
                return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
            }
            throw e;
        }
    }
    
    private Object handleLSet(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'lset' command\r\n";
        }
        
        String key = args[1];
        int index;
        try {
            index = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        String value = args[3];
        
        try {
            store.lset(database, key, index, value);
            return "+OK\r\n";
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("ERR no such key")) {
                return "-ERR no such key\r\n";
            }
            if (msg != null && msg.startsWith("ERR index out of range")) {
                return "-ERR index out of range\r\n";
            }
            if (msg != null && msg.startsWith("WRONGTYPE")) {
                return "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
            }
            throw e;
        }
    }
    
    private Object handleLRange(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'lrange' command\r\n";
        }
        
        String key = args[1];
        long start, stop;
        
        try {
            start = Long.parseLong(args[2]);
            stop = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        // 使用优化的 lrange 方法
        List<String> subList = store.lrange(database, key, start, stop);
        
        if (subList.isEmpty()) {
            return "*0\r\n";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("*");
        result.append(subList.size());
        result.append("\r\n");
        
        for (String value : subList) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            result.append("$").append(bytes.length).append("\r\n").append(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).append("\r\n");
        }
        
        return result.toString();
    }
    
    private List<String> getList(int database, MemoryStore store, String key) {
        Object value = store.get(database, key);
        if (value == null) {
            return Lists.newArrayList();
        }
        
        if (!(value instanceof List)) {
            return Lists.newArrayList();
        }
        
        List<?> rawList = (List<?>) value;
        List<String> list = Lists.newArrayList();
        
        for (Object item : rawList) {
            list.add(item.toString());
        }
        
        return list;
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
