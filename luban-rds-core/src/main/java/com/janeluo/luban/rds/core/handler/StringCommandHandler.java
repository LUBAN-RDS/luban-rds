package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public class StringCommandHandler implements CommandHandler {
    
    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.SET,
        RdsCommandConstant.GET,
        RdsCommandConstant.INCR,
        RdsCommandConstant.DECR,
        RdsCommandConstant.INCRBY,
        RdsCommandConstant.DECRBY,
        RdsCommandConstant.APPEND,
        RdsCommandConstant.STRLEN,
        RdsCommandConstant.MSET,
        RdsCommandConstant.MGET
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();
        
        switch (command) {
            case RdsCommandConstant.SET:
                return handleSet(database, args, store);
            case RdsCommandConstant.GET:
                return handleGet(database, args, store);
            case RdsCommandConstant.INCR:
                return handleIncr(database, args, store);
            case RdsCommandConstant.DECR:
                return handleDecr(database, args, store);
            case RdsCommandConstant.INCRBY:
                return handleIncrBy(database, args, store);
            case RdsCommandConstant.DECRBY:
                return handleDecrBy(database, args, store);
            case RdsCommandConstant.APPEND:
                return handleAppend(database, args, store);
            case RdsCommandConstant.STRLEN:
                return handleStrLen(database, args, store);
            case RdsCommandConstant.MSET:
                return handleMSet(database, args, store);
            case RdsCommandConstant.MGET:
                return handleMGet(database, args, store);
            default:
                return "-ERR unknown command\r\n";
        }
    }
    
    private Object handleSet(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'set' command\r\n";
        }
        
        String key = args[1];
        String value = args[2];
        try {
            store.set(database, key, value);
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
    
    private Object handleGet(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return RdsResponseConstant.wrongArgsError("get");
        }
        
        String key = args[1];
        Object value = store.get(database, key);
        if (value == null) {
            return RdsResponseConstant.NULL_BULK;
        }
        
        String strValue = value.toString();
        // 使用UTF-8编码计算字节长度
        byte[] bytes = strValue.getBytes(StandardCharsets.UTF_8);
        return "$" + bytes.length + "\r\n" + strValue + "\r\n";
    }
    
    private Object handleIncr(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'incr' command\r\n";
        }
        
        String key = args[1];
        Object value = store.get(database, key);
        long num = 0;
        
        if (value != null) {
            try {
                num = Long.parseLong(value.toString());
            } catch (NumberFormatException e) {
                return RdsResponseConstant.ERR_NOT_INTEGER;
            }
        }
        
        num++;
        try {
            store.set(database, key, String.valueOf(num));
            return RdsResponseConstant.intResponse(num);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleDecr(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'decr' command\r\n";
        }
        
        String key = args[1];
        Object value = store.get(database, key);
        long num = 0;
        
        if (value != null) {
            try {
                num = Long.parseLong(value.toString());
            } catch (NumberFormatException e) {
                return RdsResponseConstant.ERR_NOT_INTEGER;
            }
        }
        
        num--;
        try {
            store.set(database, key, String.valueOf(num));
            return RdsResponseConstant.intResponse(num);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleIncrBy(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return RdsResponseConstant.wrongArgsError("incrby");
        }
        
        String key = args[1];
        long increment;
        
        try {
            increment = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return RdsResponseConstant.ERR_NOT_INTEGER;
        }
        
        Object value = store.get(database, key);
        long num = 0;
        
        if (value != null) {
            try {
                num = Long.parseLong(value.toString());
            } catch (NumberFormatException e) {
                return RdsResponseConstant.ERR_NOT_INTEGER;
            }
        }
        
        num += increment;
        try {
            store.set(database, key, String.valueOf(num));
            return RdsResponseConstant.intResponse(num);
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleDecrBy(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return RdsResponseConstant.wrongArgsError("decrby");
        }
        
        String key = args[1];
        long decrement;
        
        try {
            decrement = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return RdsResponseConstant.ERR_NOT_INTEGER;
        }
        
        Object value = store.get(database, key);
        long num = 0;
        
        if (value != null) {
            try {
                num = Long.parseLong(value.toString());
            } catch (NumberFormatException e) {
                return RdsResponseConstant.ERR_NOT_INTEGER;
            }
        }
        
        num -= decrement;
        store.set(database, key, String.valueOf(num));
        return RdsResponseConstant.intResponse(num);
    }
    
    private Object handleAppend(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return RdsResponseConstant.wrongArgsError("append");
        }
        
        String key = args[1];
        String appendValue = args[2];
        
        Object value = store.get(database, key);
        String strValue = value != null ? value.toString() : "";
        strValue += appendValue;
        
        try {
            store.set(database, key, strValue);
            return RdsResponseConstant.intResponse(strValue.length());
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    private Object handleStrLen(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return RdsResponseConstant.wrongArgsError("strlen");
        }
        
        String key = args[1];
        Object value = store.get(database, key);
        if (value == null) {
            return RdsResponseConstant.ZERO;
        }
        
        return RdsResponseConstant.intResponse(value.toString().length());
    }

    private Object handleMSet(int database, String[] args, MemoryStore store) {
        if (args.length < 3 || args.length % 2 == 0) {
            return "-ERR wrong number of arguments for 'mset' command\r\n";
        }
        
        String[] keysAndValues = new String[args.length - 1];
        System.arraycopy(args, 1, keysAndValues, 0, args.length - 1);
        
        try {
            store.mset(database, keysAndValues);
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

    private Object handleMGet(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'mget' command\r\n";
        }
        
        String[] keys = new String[args.length - 1];
        System.arraycopy(args, 1, keys, 0, args.length - 1);
        
        java.util.List<Object> values = store.mget(database, keys);
        
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(values.size()).append("\r\n");
        for (Object val : values) {
            // Redis MGET returns nil for non-string values
            if (val == null || !(val instanceof String)) {
                sb.append("$-1\r\n");
            } else {
                String str = (String) val;
                byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
                sb.append("$").append(bytes.length).append("\r\n").append(str).append("\r\n");
            }
        }
        return sb.toString();
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
