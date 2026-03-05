package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HashCommandHandler implements CommandHandler {
    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.HSET,
        RdsCommandConstant.HSETNX,
        RdsCommandConstant.HMSET,
        RdsCommandConstant.HGET,
        RdsCommandConstant.HMGET,
        RdsCommandConstant.HGETALL,
        RdsCommandConstant.HDEL,
        RdsCommandConstant.HEXISTS,
        RdsCommandConstant.HKEYS,
        RdsCommandConstant.HVALS,
        RdsCommandConstant.HLEN,
        RdsCommandConstant.HINCRBY,
        RdsCommandConstant.HSCAN
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();
        
        switch (command) {
            case RdsCommandConstant.HSET:
                return handleHSet(database, args, store);
            case RdsCommandConstant.HSETNX:
                return handleHSetNx(database, args, store);
            case RdsCommandConstant.HMSET:
                return handleHMSet(database, args, store);
            case RdsCommandConstant.HGET:
                return handleHGet(database, args, store);
            case RdsCommandConstant.HMGET:
                return handleHMGet(database, args, store);
            case RdsCommandConstant.HGETALL:
                return handleHGetAll(database, args, store);
            case RdsCommandConstant.HDEL:
                return handleHDel(database, args, store);
            case RdsCommandConstant.HEXISTS:
                return handleHExists(database, args, store);
            case RdsCommandConstant.HKEYS:
                return handleHKeys(database, args, store);
            case RdsCommandConstant.HVALS:
                return handleHVals(database, args, store);
            case RdsCommandConstant.HLEN:
                return handleHLen(database, args, store);
            case RdsCommandConstant.HINCRBY:
                return handleHIncrBy(database, args, store);
            case RdsCommandConstant.HSCAN:
                return handleHScan(database, args, store);
            default:
                return "-ERR unknown command\r\n";
        }
    }
    
    private Object handleHSet(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'hset' command\r\n";
        }
        
        String key = args[1];
        String field = args[2];
        String value = args[3];
        
        // 使用优化的 hset 方法，直接操作 Hash，避免复制整个 Map
        try {
            int hsetResult = store.hset(database, key, field, value);
            return ":" + hsetResult + "\r\n";
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleHSetNx(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'hsetnx' command\r\n";
        }
        
        String key = args[1];
        String field = args[2];
        String value = args[3];
        
        try {
            int result = store.hsetnx(database, key, field, value);
            return ":" + result + "\r\n";
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }

    private Object handleHMSet(int database, String[] args, MemoryStore store) {
        if (args.length < 4 || args.length % 2 != 0) {
            return "-ERR wrong number of arguments for 'hmset' command\r\n";
        }
        
        String key = args[1];
        String[] fieldsAndValues = new String[args.length - 2];
        System.arraycopy(args, 2, fieldsAndValues, 0, args.length - 2);
        
        try {
            store.hmset(database, key, fieldsAndValues);
            return RdsResponseConstant.OK;
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleHGet(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'hget' command\r\n";
        }
        
        String key = args[1];
        String field = args[2];
        
        // 使用优化的 hget 方法
        String value = store.hget(database, key, field);
        
        if (value == null) {
            return "$-1\r\n";
        }
        
        // 使用 UTF-8 字节长度
        int byteLength = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return "$" + byteLength + "\r\n" + value + "\r\n";
    }

    private Object handleHMGet(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'hmget' command\r\n";
        }
        
        String key = args[1];
        String[] fields = new String[args.length - 2];
        System.arraycopy(args, 2, fields, 0, args.length - 2);
        
        java.util.List<String> values = store.hmget(database, key, fields);
        
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(values.size()).append("\r\n");
        for (String val : values) {
            if (val == null) {
                sb.append("$-1\r\n");
            } else {
                int bytes = val.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                sb.append("$").append(bytes).append("\r\n").append(val).append("\r\n");
            }
        }
        return sb.toString();
    }
    
    private Object handleHGetAll(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'hgetall' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 hgetall 方法
        Map<String, String> hash = store.hgetall(database, key);
        
        if (hash.isEmpty()) {
            return "*0\r\n";
        }
        
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : hash.entrySet()) {
            result.add(entry.getKey());
            result.add(entry.getValue());
        }
        
        return result;
    }
    
    private Object handleHDel(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'hdel' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 hdel 方法
        String[] fields = new String[args.length - 2];
        System.arraycopy(args, 2, fields, 0, fields.length);
        int deleted = store.hdel(database, key, fields);
        
        return ":" + deleted + "\r\n";
    }
    
    private Object handleHExists(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'hexists' command\r\n";
        }
        
        String key = args[1];
        String field = args[2];
        
        // 使用优化的 hexists 方法
        boolean exists = store.hexists(database, key, field);
        
        return exists ? ":1\r\n" : ":0\r\n";
    }
    
    private Object handleHKeys(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'hkeys' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 hgetall 方法获取所有字段
        Map<String, String> hash = store.hgetall(database, key);
        
        StringBuilder result = new StringBuilder();
        result.append("*");
        result.append(hash.size());
        result.append("\r\n");
        
        for (String field : hash.keySet()) {
            int byteLen = field.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            result.append("$").append(byteLen).append("\r\n").append(field).append("\r\n");
        }
        
        return result.toString();
    }
    
    private Object handleHVals(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'hvals' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 hgetall 方法获取所有值
        Map<String, String> hash = store.hgetall(database, key);
        
        StringBuilder result = new StringBuilder();
        result.append("*");
        result.append(hash.size());
        result.append("\r\n");
        
        for (String value : hash.values()) {
            int byteLen = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            result.append("$").append(byteLen).append("\r\n").append(value).append("\r\n");
        }
        
        return result.toString();
    }
    
    private Object handleHLen(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'hlen' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 hlen 方法
        int len = store.hlen(database, key);
        
        return ":" + len + "\r\n";
    }

    private Object handleHIncrBy(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'hincrby' command\r\n";
        }
        
        String key = args[1];
        String field = args[2];
        long increment;
        try {
            increment = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        try {
            long newValue = store.hincrby(database, key, field, increment);
            return ":" + newValue + "\r\n";
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            if (msg.startsWith("ERR hash value is not an integer")) {
                return "-ERR hash value is not an integer\r\n";
            }
            throw e;
        }
    }
    
    private Object handleHScan(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'hscan' command\r\n";
        }
        
        String key = args[1];
        long cursor;
        try {
            cursor = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        String pattern = "*";
        int count = 10;
        for (int i = 3; i < args.length; i++) {
            if ("MATCH".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                pattern = args[i + 1];
                i++;
            } else if ("COUNT".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    count = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ex) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
                i++;
            }
        }
        
        java.util.List<Object> scan = store.hscan(database, key, cursor, pattern, count);
        long newCursor = (Long) scan.get(0);
        
        StringBuilder resp = new StringBuilder();
        resp.append("*2\r\n");
        resp.append(RdsResponseConstant.bulkString(String.valueOf(newCursor)));
        int pairCount = scan.size() - 1;
        resp.append("*").append(pairCount).append("\r\n");
        for (int i = 1; i < scan.size(); i++) {
            String v = scan.get(i).toString();
            resp.append(RdsResponseConstant.bulkString(v));
        }
        return resp.toString();
    }
    
    private Map<String, String> getHash(int database, MemoryStore store, String key) {
        Object value = store.get(database, key);
        if (value == null) {
            return Maps.newHashMap();
        }
        
        if (!(value instanceof Map)) {
            return Maps.newHashMap();
        }
        
        Map<?, ?> rawHash = (Map<?, ?>) value;
        Map<String, String> hash = Maps.newHashMap();
        
        for (Map.Entry<?, ?> entry : rawHash.entrySet()) {
            hash.put(entry.getKey().toString(), entry.getValue().toString());
        }
        
        return hash;
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
