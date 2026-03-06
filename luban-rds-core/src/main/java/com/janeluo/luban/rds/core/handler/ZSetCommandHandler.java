package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;

import java.util.Set;

/**
 * ZSet（有序集合）类型命令处理器
 * 
 * <p>负责处理Redis ZSet类型相关的所有命令，包括：
 * <ul>
 *   <li>ZADD - 有序集合元素添加</li>
 *   <li>ZRANGE/ZRANGEBYSCORE - 有序集合范围获取</li>
 *   <li>ZSCORE - 元素分数获取</li>
 *   <li>ZREM - 有序集合元素删除</li>
 *   <li>ZCARD - 有序集合基数获取</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class ZSetCommandHandler implements CommandHandler {
    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.ZADD,
        RdsCommandConstant.ZRANGE,
        RdsCommandConstant.ZRANGEBYSCORE,
        RdsCommandConstant.ZSCORE,
        RdsCommandConstant.ZREM,
        RdsCommandConstant.ZCARD
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();
        
        switch (command) {
            case RdsCommandConstant.ZADD:
                return handleZAdd(database, args, store);
            case RdsCommandConstant.ZRANGE:
                return handleZRange(database, args, store);
            case RdsCommandConstant.ZRANGEBYSCORE:
                return handleZRangeByScore(database, args, store);
            case RdsCommandConstant.ZSCORE:
                return handleZScore(database, args, store);
            case RdsCommandConstant.ZREM:
                return handleZRem(database, args, store);
            case RdsCommandConstant.ZCARD:
                return handleZCard(database, args, store);
            default:
                return "-ERR unknown command\r\n";
        }
    }
    
    private Object handleZAdd(int database, String[] args, MemoryStore store) {
        if (args.length < 4 || args.length % 2 != 0) {
            return "-ERR wrong number of arguments for 'zadd' command\r\n";
        }
        
        String key = args[1];
        int added = 0;
        
        // 使用优化的 zadd 方法
        try {
            for (int i = 2; i < args.length; i += 2) {
                double score;
                try {
                    score = Double.parseDouble(args[i]);
                } catch (NumberFormatException e) {
                    return "-ERR value is not a valid float\r\n";
                }
                String member = args[i + 1];
                added += store.zadd(database, key, score, member);
            }
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
    
    private Object handleZRange(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'zrange' command\r\n";
        }
        
        String key = args[1];
        long start, stop;
        
        try {
            start = Long.parseLong(args[2]);
            stop = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        // 使用优化的 zrange 方法（已排序，无需再排序）
        java.util.List<String> resultList = store.zrange(database, key, start, stop);
        
        if (resultList.isEmpty()) {
            return "*0\r\n";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("*");
        result.append(resultList.size());
        result.append("\r\n");
        
        for (String member : resultList) {
            byte[] bytes = member.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            result.append("$").append(bytes.length).append("\r\n").append(member).append("\r\n");
        }
        
        return result.toString();
    }
    
    private Object handleZRangeByScore(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'zrangebyscore' command\r\n";
        }
        
        String key = args[1];
        double min, max;
        try {
            min = Double.parseDouble(args[2]);
            max = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            return "-ERR value is not a valid float\r\n";
        }
        
        int offset = 0;
        int count = -1; // unlimited
        
        // Parse LIMIT
        for (int i = 4; i < args.length; i++) {
            if ("LIMIT".equalsIgnoreCase(args[i]) && i + 2 < args.length) {
                try {
                    offset = Integer.parseInt(args[i+1]);
                    count = Integer.parseInt(args[i+2]);
                    i += 2;
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
            }
        }
        
        java.util.List<String> resultList = store.zrangeByScore(database, key, min, max, offset, count);
        
        if (resultList.isEmpty()) {
            return "*0\r\n";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("*");
        result.append(resultList.size());
        result.append("\r\n");
        
        for (String member : resultList) {
            byte[] bytes = member.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            result.append("$").append(bytes.length).append("\r\n").append(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)).append("\r\n");
        }
        
        return result.toString();
    }

    private Object handleZScore(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'zscore' command\r\n";
        }
        
        String key = args[1];
        String member = args[2];
        
        // 使用优化的 zscore 方法
        Double score = store.zscore(database, key, member);
        
        if (score == null) {
            return "$-1\r\n";
        }
        
        String scoreStr = score.toString();
        byte[] bytes = scoreStr.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return "$" + bytes.length + "\r\n" + new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1) + "\r\n";
    }
    
    private Object handleZRem(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'zrem' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 zrem 方法
        String[] members = new String[args.length - 2];
        System.arraycopy(args, 2, members, 0, members.length);
        int removed = store.zrem(database, key, members);
        
        return ":" + removed + "\r\n";
    }
    
    private Object handleZCard(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'zcard' command\r\n";
        }
        
        String key = args[1];
        // 使用优化的 zcard 方法
        int size = store.zcard(database, key);
        
        return ":" + size + "\r\n";
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
