package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;
import com.janeluo.luban.rds.common.context.ServerContext;
import com.janeluo.luban.rds.common.context.PubSubService;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public class StringCommandHandler implements CommandHandler {
    
    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.SET,
        RdsCommandConstant.SETNX,
        RdsCommandConstant.GET,
        RdsCommandConstant.INCR,
        RdsCommandConstant.DECR,
        RdsCommandConstant.INCRBY,
        RdsCommandConstant.DECRBY,
        RdsCommandConstant.APPEND,
        RdsCommandConstant.STRLEN,
        RdsCommandConstant.MSET,
        RdsCommandConstant.MGET,
        RdsCommandConstant.GETSET,
        RdsCommandConstant.SETRANGE,
        RdsCommandConstant.GETRANGE
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();
        
        switch (command) {
            case RdsCommandConstant.SET:
                return handleSet(database, args, store);
            case RdsCommandConstant.SETNX:
                return handleSetNx(database, args, store);
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
            case RdsCommandConstant.GETSET:
                return handleGetSet(database, args, store);
            case RdsCommandConstant.SETRANGE:
                return handleSetRange(database, args, store);
            case RdsCommandConstant.GETRANGE:
                return handleGetRange(database, args, store);
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
        
        // 解析SET命令选项
        long expireSeconds = -1;
        long expireMilliseconds = -1;
        boolean nx = false;
        boolean xx = false;
        
        for (int i = 3; i < args.length; i++) {
            String option = args[i].toUpperCase();
            switch (option) {
                case "EX":
                    if (i + 1 >= args.length) {
                        return "-ERR syntax error\r\n";
                    }
                    try {
                        expireSeconds = Long.parseLong(args[i + 1]);
                        i++;
                    } catch (NumberFormatException e) {
                        return "-ERR value is not an integer or out of range\r\n";
                    }
                    break;
                case "PX":
                    if (i + 1 >= args.length) {
                        return "-ERR syntax error\r\n";
                    }
                    try {
                        expireMilliseconds = Long.parseLong(args[i + 1]);
                        i++;
                    } catch (NumberFormatException e) {
                        return "-ERR value is not an integer or out of range\r\n";
                    }
                    break;
                case "NX":
                    nx = true;
                    break;
                case "XX":
                    xx = true;
                    break;
                default:
                    return "-ERR syntax error\r\n";
            }
        }
        
        // 检查NX和XX不能同时使用
        if (nx && xx) {
            return "-ERR NX and XX options are mutually exclusive\r\n";
        }
        
        // 检查键是否存在
        if (nx && store.exists(database, key)) {
            return RdsResponseConstant.NULL_BULK;
        }
        if (xx && !store.exists(database, key)) {
            return RdsResponseConstant.NULL_BULK;
        }
        
        try {
            if (expireSeconds > 0) {
                store.setWithExpire(database, key, value, expireSeconds);
            } else if (expireMilliseconds > 0) {
                store.setWithExpireMs(database, key, value, expireMilliseconds);
            } else {
                store.set(database, key, value);
            }
            // 发布键空间通知，支持Redis标准的键空间通知机制
            publishKeyspaceNotification(database, key, "set");
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
    
    /**
     * 发布键空间通知
     * @param database 数据库编号
     * @param key 键名
     * @param operation 操作类型
     */
    private void publishKeyspaceNotification(int database, String key, String operation) {
        PubSubService pubSubService = ServerContext.getPubSubService();
        if (pubSubService != null) {
            // 发布键空间通知：__keyspace@<db>__:<key>
            String keyspaceChannel = "__keyspace@" + database + "__:" + key;
            pubSubService.publish(keyspaceChannel, operation);
            
            // 发布键事件通知：__keyevent@<db>__:<operation>
            String keyeventChannel = "__keyevent@" + database + "__:" + operation;
            pubSubService.publish(keyeventChannel, key);
        }
    }
    
    private Object handleSetNx(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return RdsResponseConstant.wrongArgsError("setnx");
        }
        
        String key = args[1];
        String value = args[2];
        Object existing = store.get(database, key);
        if (existing == null) {
            try {
                store.set(database, key, value);
                publishKeyspaceNotification(database, key, "setnx");
                return RdsResponseConstant.ONE;
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.startsWith("OOM command not allowed")) {
                    RuntimeConfig.incErrorRepliesOom();
                    return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
                }
                throw e;
            }
        }
        return RdsResponseConstant.ZERO;
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
        // 使用ISO-8859-1编码计算字节长度
        byte[] bytes = strValue.getBytes(StandardCharsets.ISO_8859_1);
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
            publishKeyspaceNotification(database, key, "incr");
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
            publishKeyspaceNotification(database, key, "decr");
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
            publishKeyspaceNotification(database, key, "incrby");
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
        publishKeyspaceNotification(database, key, "decrby");
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
            publishKeyspaceNotification(database, key, "append");
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
            // 原子性执行MSET操作
            store.mset(database, keysAndValues);
            // 为每个键发布键空间通知
            for (int i = 0; i < keysAndValues.length; i += 2) {
                String key = keysAndValues[i];
                publishKeyspaceNotification(database, key, "mset");
            }
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
    
    /**
     * 处理GETSET命令
     */
    private Object handleGetSet(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'getset' command\r\n";
        }
        
        String key = args[1];
        String value = args[2];
        
        try {
            Object oldValue = store.get(database, key);
            store.set(database, key, value);
            publishKeyspaceNotification(database, key, "getset");
            
            if (oldValue == null) {
                return RdsResponseConstant.NULL_BULK;
            }
            
            String strValue = oldValue.toString();
            byte[] bytes = strValue.getBytes(StandardCharsets.ISO_8859_1);
            return "$" + bytes.length + "\r\n" + strValue + "\r\n";
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    /**
     * 处理SETRANGE命令
     */
    private Object handleSetRange(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'setrange' command\r\n";
        }
        
        String key = args[1];
        long offset;
        String value = args[3];
        
        try {
            offset = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        try {
            Object oldValueObj = store.get(database, key);
            String oldValue = oldValueObj != null ? oldValueObj.toString() : "";
            
            StringBuilder sb = new StringBuilder(oldValue);
            if (offset > sb.length()) {
                // 填充空白字符
                sb.append(new String(new char[(int)(offset - sb.length())]).replace('\0', ' '));
            }
            
            // 替换指定范围的字符串
            if (offset < sb.length()) {
                sb.replace((int)offset, Math.min((int)offset + value.length(), sb.length()), value);
            } else {
                sb.append(value);
            }
            
            String newValue = sb.toString();
            store.set(database, key, newValue);
            publishKeyspaceNotification(database, key, "setrange");
            
            return RdsResponseConstant.intResponse(newValue.length());
        } catch (RuntimeException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.startsWith("OOM command not allowed")) {
                RuntimeConfig.incErrorRepliesOom();
                return "-OOM command not allowed when used memory > 'maxmemory'\r\n";
            }
            throw e;
        }
    }
    
    /**
     * 处理GETRANGE命令
     */
    private Object handleGetRange(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'getrange' command\r\n";
        }
        
        String key = args[1];
        long start, end;
        
        try {
            start = Long.parseLong(args[2]);
            end = Long.parseLong(args[3]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        Object valueObj = store.get(database, key);
        if (valueObj == null) {
            return RdsResponseConstant.NULL_BULK;
        }
        
        String value = valueObj.toString();
        int length = value.length();
        
        // 处理负索引
        if (start < 0) {
            start = length + start;
            if (start < 0) start = 0;
        }
        if (end < 0) {
            end = length + end;
            if (end < 0) end = -1;
        }
        
        // 确保范围有效
        if (start >= length || end < 0) {
            return "$0\r\n\r\n";
        }
        if (end >= length) {
            end = length - 1;
        }
        
        String result = value.substring((int)start, (int)end + 1);
        byte[] bytes = result.getBytes(StandardCharsets.ISO_8859_1);
        return "$" + bytes.length + "\r\n" + result + "\r\n";
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
                byte[] bytes = str.getBytes(StandardCharsets.ISO_8859_1);
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
