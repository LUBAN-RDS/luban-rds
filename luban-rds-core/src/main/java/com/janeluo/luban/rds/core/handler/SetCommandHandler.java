package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * Set类型命令处理器
 * 
 * <p>负责处理Redis Set类型相关的所有命令，包括：
 * <ul>
 *   <li>SADD/SREM - 集合元素添加和删除</li>
 *   <li>SMEMBERS/SISMEMBER - 集合成员获取和检查</li>
 *   <li>SCARD - 集合基数获取</li>
 *   <li>SINTER/SUNION/SDIFF - 集合交集/并集/差集操作</li>
 *   <li>SSCAN - 集合迭代扫描</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class SetCommandHandler implements CommandHandler {
    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.SADD,
        RdsCommandConstant.SREM,
        RdsCommandConstant.SMEMBERS,
        RdsCommandConstant.SISMEMBER,
        RdsCommandConstant.SCARD,
        RdsCommandConstant.SINTER,
        RdsCommandConstant.SUNION,
        RdsCommandConstant.SDIFF,
        RdsCommandConstant.SSCAN
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
            case RdsCommandConstant.SINTER:
                return handleSInter(database, args, store);
            case RdsCommandConstant.SUNION:
                return handleSUnion(database, args, store);
            case RdsCommandConstant.SDIFF:
                return handleSDiff(database, args, store);
            case RdsCommandConstant.SSCAN:
                return handleSScan(database, args, store);
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
    
    private Object handleSInter(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'sinter' command\r\n";
        }
        
        String[] keys = new String[args.length - 1];
        System.arraycopy(args, 1, keys, 0, keys.length);
        
        Set<String> result = store.sinter(database, keys);
        
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(result.size()).append("\r\n");
        
        for (String value : result) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            sb.append("$").append(bytes.length).append("\r\n").append(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).append("\r\n");
        }
        
        return sb.toString();
    }
    
    private Object handleSUnion(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'sunion' command\r\n";
        }
        
        String[] keys = new String[args.length - 1];
        System.arraycopy(args, 1, keys, 0, keys.length);
        
        Set<String> result = store.sunion(database, keys);
        
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(result.size()).append("\r\n");
        
        for (String value : result) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            sb.append("$").append(bytes.length).append("\r\n").append(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).append("\r\n");
        }
        
        return sb.toString();
    }
    
    private Object handleSDiff(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'sdiff' command\r\n";
        }
        
        String[] keys = new String[args.length - 1];
        System.arraycopy(args, 1, keys, 0, keys.length);
        
        Set<String> result = store.sdiff(database, keys);
        
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(result.size()).append("\r\n");
        
        for (String value : result) {
            byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            sb.append("$").append(bytes.length).append("\r\n").append(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).append("\r\n");
        }
        
        return sb.toString();
    }
    
    private Object handleSScan(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sscan' command\r\n";
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
        
        java.util.List<Object> scan = store.sscan(database, key, cursor, pattern, count);
        long newCursor = (Long) scan.get(0);
        
        StringBuilder resp = new StringBuilder();
        resp.append("*2\r\n");
        resp.append(RdsResponseConstant.bulkString(String.valueOf(newCursor)));
        int memberCount = scan.size() - 1;
        resp.append("*").append(memberCount).append("\r\n");
        for (int i = 1; i < scan.size(); i++) {
            String v = scan.get(i).toString();
            resp.append(RdsResponseConstant.bulkString(v));
        }
        return resp.toString();
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
