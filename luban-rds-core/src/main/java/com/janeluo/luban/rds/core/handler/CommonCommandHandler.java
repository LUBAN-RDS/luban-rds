package com.janeluo.luban.rds.core.handler;

import com.google.common.collect.Sets;
import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.context.InfoProvider;
import com.janeluo.luban.rds.common.context.ServerContext;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class CommonCommandHandler implements CommandHandler {
    
    private final Set<String> supportedCommands = Sets.newHashSet(
        RdsCommandConstant.EXISTS,
        RdsCommandConstant.DEL,
        RdsCommandConstant.EXPIRE,
        RdsCommandConstant.PEXPIRE,
        RdsCommandConstant.TTL,
        RdsCommandConstant.PTTL,
        RdsCommandConstant.FLUSHALL,
        RdsCommandConstant.TYPE,
        RdsCommandConstant.PING,
        RdsCommandConstant.ECHO,
        RdsCommandConstant.SELECT,
        RdsCommandConstant.INFO,
        RdsCommandConstant.SCAN,
        RdsCommandConstant.PUBLISH,
        RdsCommandConstant.DBSIZE,
        RdsCommandConstant.FLUSHDB,
        RdsCommandConstant.TIME,
        RdsCommandConstant.LASTSAVE,
        RdsCommandConstant.BGREWRITEAOF,
        RdsCommandConstant.BGSAVE,
        RdsCommandConstant.CLUSTER_SLOTS,
        RdsCommandConstant.COMMAND,
        RdsCommandConstant.COMMAND_COUNT,
        RdsCommandConstant.COMMAND_GETKEYS,
        RdsCommandConstant.COMMAND_INFO,
        RdsCommandConstant.CONFIG_GET,
        RdsCommandConstant.CONFIG_REWRITE,
        RdsCommandConstant.CONFIG_SET,
        RdsCommandConstant.CONFIG_RESETSTAT,
        RdsCommandConstant.DEBUG_OBJECT,
        RdsCommandConstant.DEBUG_SEGFAULT
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        String command = args[0].toUpperCase();
        
        switch (command) {
            case RdsCommandConstant.EXISTS:
                return handleExists(database, args, store);
            case RdsCommandConstant.DEL:
                return handleDel(database, args, store);
            case RdsCommandConstant.EXPIRE:
                return handleExpire(database, args, store);
            case RdsCommandConstant.PEXPIRE:
                return handlePExpire(database, args, store);
            case RdsCommandConstant.TTL:
                return handleTtl(database, args, store);
            case RdsCommandConstant.PTTL:
                return handlePTtl(database, args, store);
            case RdsCommandConstant.FLUSHALL:
                return handleFlushAll(args, store);
            case RdsCommandConstant.TYPE:
                return handleType(database, args, store);
            case RdsCommandConstant.PING:
                return handlePing(args, store);
            case RdsCommandConstant.ECHO:
                return handleEcho(args, store);
            case RdsCommandConstant.SELECT:
                return handleSelect(args, store);
            case RdsCommandConstant.INFO:
                return handleInfo(args, store);
            case RdsCommandConstant.SCAN:
                return handleScan(database, args, store);
            case RdsCommandConstant.PUBLISH:
                return handlePublish(args, store);
            case RdsCommandConstant.DBSIZE:
                return handleDbsize(database, args, store);
            case RdsCommandConstant.FLUSHDB:
                return handleFlushdb(database, args, store);
            case RdsCommandConstant.TIME:
                return handleTime(args, store);
            case RdsCommandConstant.LASTSAVE:
                return handleLastsave(args, store);
            case RdsCommandConstant.BGREWRITEAOF:
                return handleBgrewriteaof(args, store);
            case RdsCommandConstant.BGSAVE:
                return handleBgsave(args, store);
            case RdsCommandConstant.CLUSTER_SLOTS:
                return handleClusterSlots(args, store);
            case RdsCommandConstant.COMMAND:
                return handleCommand(args, store);
            case RdsCommandConstant.COMMAND_COUNT:
                return handleCommandCount(args, store);
            case RdsCommandConstant.COMMAND_GETKEYS:
                return handleCommandGetkeys(args, store);
            case RdsCommandConstant.COMMAND_INFO:
                return handleCommandInfo(args, store);
            case RdsCommandConstant.CONFIG_GET:
                return handleConfigGet(args, store);
            case RdsCommandConstant.CONFIG_REWRITE:
                return handleConfigRewrite(args, store);
            case RdsCommandConstant.CONFIG_SET:
                return handleConfigSet(args, store);
            case RdsCommandConstant.CONFIG_RESETSTAT:
                return handleConfigResetstat(args, store);
            case RdsCommandConstant.DEBUG_OBJECT:
                return handleDebugObject(database, args, store);
            case RdsCommandConstant.DEBUG_SEGFAULT:
                return handleDebugSegfault(args, store);
            default:
                return "-ERR unknown command\r\n";
        }
    }
    
    private Object handleExists(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'exists' command\r\n";
        }
        
        int existsCount = 0;
        for (int i = 1; i < args.length; i++) {
            if (store.exists(database, args[i])) {
                existsCount++;
            }
        }
        
        return ":" + existsCount + "\r\n";
    }
    
    private Object handleDel(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'del' command\r\n";
        }
        
        int deletedCount = 0;
        for (int i = 1; i < args.length; i++) {
            if (store.del(database, args[i])) {
                deletedCount++;
            }
        }
        
        return ":" + deletedCount + "\r\n";
    }
    
    private Object handleExpire(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'expire' command\r\n";
        }
        
        String key = args[1];
        long seconds;
        
        try {
            seconds = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        boolean success = store.expire(database, key, seconds);
        return success ? ":1\r\n" : ":0\r\n";
    }

    private Object handlePExpire(int database, String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'pexpire' command\r\n";
        }
        
        String key = args[1];
        long milliseconds;
        
        try {
            milliseconds = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        boolean success = store.pexpire(database, key, milliseconds);
        return success ? ":1\r\n" : ":0\r\n";
    }
    
    private Object handleTtl(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'ttl' command\r\n";
        }
        
        String key = args[1];
        long ttl = store.ttl(database, key);
        return ":" + ttl + "\r\n";
    }

    private Object handlePTtl(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'pttl' command\r\n";
        }
        
        String key = args[1];
        long ttl = store.pttl(database, key);
        return ":" + ttl + "\r\n";
    }
    
    private Object handleFlushAll(String[] args, MemoryStore store) {
        store.flushAll();
        return RdsResponseConstant.OK;
    }
    
    private Object handleType(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'type' command\r\n";
        }
        
        String key = args[1];
        String type = store.type(database, key);
        return "+" + type + "\r\n";
    }
    
    private Object handlePing(String[] args, MemoryStore store) {
        return RdsResponseConstant.PONG;
    }
    
    private Object handleEcho(String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'echo' command\r\n";
        }
        
        StringBuilder message = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) {
                message.append(" ");
            }
            message.append(args[i]);
        }
        
        return "$" + message.length() + "\r\n" + message.toString() + "\r\n";
    }
    
    private Object handleSelect(String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'select' command\r\n";
        }
        
        try {
            // 解析数据库索引（虽然我们只支持一个数据库）
            Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        // 由于Luban-RDS目前只支持一个数据库，忽略索引参数，直接返回OK
        return RdsResponseConstant.OK;
    }
    
    private Object handleInfo(String[] args, MemoryStore store) {
        InfoProvider provider = ServerContext.getInfoProvider();
        if (provider == null) {
            provider = new LegacyInfoProvider(store);
        }

        StringBuilder info = new StringBuilder();
        String section = (args.length > 1) ? args[1].toLowerCase() : "all";
        
        String[] sections;
        if ("all".equals(section) || "default".equals(section)) {
            sections = new String[]{"Server", "Clients", "Memory", "Persistence", "Stats", "Replication", "CPU", "Modules", "ErrorStats", "Cluster", "Keyspace"};
        } else {
            sections = new String[]{section};
        }
        
        boolean firstSection = true;
        for (String sec : sections) {
            Map<String, Object> data = provider.getInfo(sec);
            if (data != null && !data.isEmpty()) {
                if (!firstSection) {
                    info.append("\r\n");
                }
                info.append("# ").append(capitalize(sec)).append("\r\n");
                // Use TreeMap to sort keys for consistent output
                Map<String, Object> sortedData = new TreeMap<>(data);
                for (Map.Entry<String, Object> entry : sortedData.entrySet()) {
                    info.append(entry.getKey()).append(":").append(entry.getValue()).append("\r\n");
                }
                firstSection = false;
            }
        }
        
        String infoStr = info.toString();
        return "$" + infoStr.length() + "\r\n" + infoStr + "\r\n";
    }
    
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private String toHumanReadable(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.2fKB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.2fMB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2fGB", gb);
    }
    
    private Object handleScan(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'scan' command\r\n";
        }
        
        // 解析游标
        long cursor;
        try {
            cursor = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }
        
        // 解析可选参数
        String pattern = "*";
        int count = 10; // 默认值
        
        for (int i = 2; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("MATCH") && i + 1 < args.length) {
                pattern = args[i + 1];
                i++;
            } else if (args[i].equalsIgnoreCase("COUNT") && i + 1 < args.length) {
                try {
                    count = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    return "-ERR value is not an integer or out of range\r\n";
                }
                i++;
            }
        }
        
        // 调用存储的scan方法
        java.util.List<Object> scanResult = store.scan(database, cursor, pattern, count);
        
        // 构建 RESP 协议响应：两元素数组 [cursor, keys[]]
        StringBuilder response = new StringBuilder();
        response.append("*2\r\n");
        // 第一个元素：游标为 Bulk String（Redis 规范）
        String newCursorStr = String.valueOf((Long) scanResult.get(0));
        response.append(RdsResponseConstant.bulkString(newCursorStr));
        // 第二个元素：键名数组
        int keyCount = scanResult.size() - 1;
        response.append("*").append(keyCount).append("\r\n");
        for (int i = 1; i < scanResult.size(); i++) {
            String key = (String) scanResult.get(i);
            response.append(RdsResponseConstant.bulkString(key));
        }
        return response.toString();
    }
    
    private Object handlePublish(String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'publish' command\r\n";
        }
        
        com.janeluo.luban.rds.common.context.PubSubService service = com.janeluo.luban.rds.common.context.ServerContext.getPubSubService();
        if (service != null) {
            int count = service.publish(args[1], args[2]);
            return ":" + count + "\r\n";
        }
        
        return ":0\r\n";
    }
    
    private Object handleDbsize(int database, String[] args, MemoryStore store) {
        long size = store.dbsize(database);
        return ":" + size + "\r\n";
    }
    
    private Object handleFlushdb(int database, String[] args, MemoryStore store) {
        store.flushdb(database);
        return RdsResponseConstant.OK;
    }
    
    private Object handleTime(String[] args, MemoryStore store) {
        long currentTimeMillis = System.currentTimeMillis();
        long seconds = currentTimeMillis / 1000;
        long microseconds = (currentTimeMillis % 1000) * 1000;
        
        StringBuilder response = new StringBuilder();
        response.append("*2\r\n");
        response.append(":").append(seconds).append("\r\n");
        response.append(":").append(microseconds).append("\r\n");
        
        return response.toString();
    }
    
    private Object handleLastsave(String[] args, MemoryStore store) {
        long currentTimeMillis = System.currentTimeMillis();
        long seconds = currentTimeMillis / 1000;
        return ":" + seconds + "\r\n";
    }
    
    private Object handleBgrewriteaof(String[] args, MemoryStore store) {
        // 模拟异步重写AOF文件
        return "+Background append only file rewriting started\r\n";
    }
    
    private Object handleBgsave(String[] args, MemoryStore store) {
        // 模拟异步保存数据库
        return "+Background saving started\r\n";
    }
    
    private Object handleClusterSlots(String[] args, MemoryStore store) {
        // 返回空数组，表示集群未启用
        return "*0\r\n";
    }
    
    private Object handleCommand(String[] args, MemoryStore store) {
        // 模拟返回Redis命令详情数组
        return "*0\r\n";
    }
    
    private Object handleCommandCount(String[] args, MemoryStore store) {
        // 返回命令总数（模拟值）
        return ":100\r\n";
    }
    
    private Object handleCommandGetkeys(String[] args, MemoryStore store) {
        // 模拟返回给定命令的所有键
        return "*0\r\n";
    }
    
    private Object handleCommandInfo(String[] args, MemoryStore store) {
        // 模拟返回指定Redis命令描述
        return "*0\r\n";
    }
    
    private Object handleConfigGet(String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'config get' command\r\n";
        }
        
        String parameter = args[1];
        String value = "value";
        if ("lua-timeout".equalsIgnoreCase(parameter)) {
            value = String.valueOf(RuntimeConfig.getLuaScriptTimeoutMs());
        } else if ("metrics-enabled".equalsIgnoreCase(parameter)) {
            value = RuntimeConfig.isMetricsEnabled() ? "1" : "0";
        } else if ("lua-sandbox-enabled".equalsIgnoreCase(parameter)) {
            value = RuntimeConfig.isLuaSandboxEnabled() ? "1" : "0";
        } else if ("lua-allowed-modules".equalsIgnoreCase(parameter)) {
            value = RuntimeConfig.getLuaAllowedModules();
        } else if ("lua-blocked-functions".equalsIgnoreCase(parameter)) {
            value = RuntimeConfig.getLuaBlockedFunctions();
        } else if ("lua-max-return-bytes".equalsIgnoreCase(parameter)) {
            value = String.valueOf(RuntimeConfig.getLuaMaxReturnBytes());
        } else if ("lua-max-script-bytes".equalsIgnoreCase(parameter)) {
            value = String.valueOf(RuntimeConfig.getLuaMaxScriptBytes());
        } else if ("maxmemory".equalsIgnoreCase(parameter)) {
            if (store instanceof DefaultMemoryStore) {
                value = String.valueOf(((DefaultMemoryStore) store).getMaxMemory());
            } else {
                value = "0";
            }
        } else if ("maxmemory-policy".equalsIgnoreCase(parameter)) {
            if (store instanceof DefaultMemoryStore) {
                value = ((DefaultMemoryStore) store).getMaxMemoryPolicy();
            } else {
                value = "noeviction";
            }
        } else if ("maxmemory-samples".equalsIgnoreCase(parameter)) {
            if (store instanceof DefaultMemoryStore) {
                value = String.valueOf(((DefaultMemoryStore) store).getLruSampleSize());
            } else {
                value = "5";
            }
        } else if ("softmaxmemory-threshold".equalsIgnoreCase(parameter)) {
            if (store instanceof DefaultMemoryStore) {
                value = String.valueOf(((DefaultMemoryStore) store).getSoftLimitPercent());
            } else {
                value = "0";
            }
        } else if ("lua-max-ops-per-script".equalsIgnoreCase(parameter)) {
            value = String.valueOf(RuntimeConfig.getLuaMaxOpsPerScript());
        } else if ("lua-yield-ms".equalsIgnoreCase(parameter)) {
            value = String.valueOf(RuntimeConfig.getLuaYieldMs());
        } else if ("slowlog-log-slower-than".equalsIgnoreCase(parameter)) {
            value = String.valueOf(RuntimeConfig.getSlowlogLogSlowerThan());
        } else if ("slowlog-max-len".equalsIgnoreCase(parameter)) {
            value = String.valueOf(RuntimeConfig.getSlowlogMaxLen());
        } else if ("monitor-max-clients".equalsIgnoreCase(parameter)) {
            value = String.valueOf(RuntimeConfig.getMonitorMaxClients());
        }
        StringBuilder response = new StringBuilder();
        response.append("*2\r\n");
        response.append("$").append(parameter.length()).append("\r\n").append(parameter).append("\r\n");
        response.append("$").append(value.length()).append("\r\n").append(value).append("\r\n");
        return response.toString();
    }
    
    private Object handleConfigRewrite(String[] args, MemoryStore store) {
        // 模拟重写配置文件
        return "+OK\r\n";
    }
    
    private Object handleConfigSet(String[] args, MemoryStore store) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'config set' command\r\n";
        }
        
        String parameter = args[1];
        String value = args[2];
        if ("lua-timeout".equalsIgnoreCase(parameter)) {
            try {
                long ms = Long.parseLong(value);
                RuntimeConfig.setLuaScriptTimeoutMs(ms);
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("metrics-enabled".equalsIgnoreCase(parameter)) {
            if ("1".equals(value) || "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) {
                RuntimeConfig.setMetricsEnabled(true);
                return "+OK\r\n";
            } else if ("0".equals(value) || "no".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                RuntimeConfig.setMetricsEnabled(false);
                return "+OK\r\n";
            } else {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("lua-sandbox-enabled".equalsIgnoreCase(parameter)) {
            if ("1".equals(value) || "yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) {
                RuntimeConfig.setLuaSandboxEnabled(true);
                return "+OK\r\n";
            } else if ("0".equals(value) || "no".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                RuntimeConfig.setLuaSandboxEnabled(false);
                return "+OK\r\n";
            } else {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("lua-allowed-modules".equalsIgnoreCase(parameter)) {
            RuntimeConfig.setLuaAllowedModules(value);
            return "+OK\r\n";
        } else if ("lua-blocked-functions".equalsIgnoreCase(parameter)) {
            RuntimeConfig.setLuaBlockedFunctions(value);
            return "+OK\r\n";
        } else if ("lua-max-return-bytes".equalsIgnoreCase(parameter)) {
            try {
                long v = Long.parseLong(value);
                RuntimeConfig.setLuaMaxReturnBytes(v);
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("lua-max-script-bytes".equalsIgnoreCase(parameter)) {
            try {
                long v = Long.parseLong(value);
                RuntimeConfig.setLuaMaxScriptBytes(v);
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("maxmemory".equalsIgnoreCase(parameter)) {
            try {
                long m = Long.parseLong(value);
                if (store instanceof DefaultMemoryStore) {
                    ((DefaultMemoryStore) store).setMaxMemory(m);
                }
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("maxmemory-policy".equalsIgnoreCase(parameter)) {
            if (store instanceof DefaultMemoryStore) {
                ((DefaultMemoryStore) store).setMaxMemoryPolicy(value);
                return "+OK\r\n";
            }
            return "+OK\r\n";
        } else if ("maxmemory-samples".equalsIgnoreCase(parameter)) {
            try {
                int s = Integer.parseInt(value);
                if (store instanceof DefaultMemoryStore) {
                    ((DefaultMemoryStore) store).setLruSampleSize(s);
                }
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("softmaxmemory-threshold".equalsIgnoreCase(parameter)) {
            try {
                int p = Integer.parseInt(value);
                if (store instanceof DefaultMemoryStore) {
                    ((DefaultMemoryStore) store).setSoftLimitPercent(p);
                }
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("lua-max-ops-per-script".equalsIgnoreCase(parameter)) {
            try {
                long v = Long.parseLong(value);
                RuntimeConfig.setLuaMaxOpsPerScript(v);
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("lua-yield-ms".equalsIgnoreCase(parameter)) {
            try {
                long v = Long.parseLong(value);
                RuntimeConfig.setLuaYieldMs(v);
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("slowlog-log-slower-than".equalsIgnoreCase(parameter)) {
            try {
                long v = Long.parseLong(value);
                RuntimeConfig.setSlowlogLogSlowerThan(v);
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("slowlog-max-len".equalsIgnoreCase(parameter)) {
            try {
                long v = Long.parseLong(value);
                RuntimeConfig.setSlowlogMaxLen(v);
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        } else if ("monitor-max-clients".equalsIgnoreCase(parameter)) {
            try {
                int v = Integer.parseInt(value);
                RuntimeConfig.setMonitorMaxClients(v);
                return "+OK\r\n";
            } catch (NumberFormatException e) {
                return "-ERR value is not an integer or out of range\r\n";
            }
        }
        return "+OK\r\n";
    }
    
    private Object handleConfigResetstat(String[] args, MemoryStore store) {
        RuntimeConfig.resetStats();
        return "+OK\r\n";
    }
    
    private Object handleDebugObject(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'debug object' command\r\n";
        }
        
        String key = args[1];
        // 模拟返回key的调试信息
        String debugInfo = "Value at:0x7f9b1c000000 refcount:1 encoding:raw serializedlength:0 lru:0 lru_seconds_idle:0\r\n";
        return "$" + debugInfo.length() + "\r\n" + debugInfo + "\r\n";
    }
    
    private Object handleDebugSegfault(String[] args, MemoryStore store) {
        // 模拟让Redis服务崩溃
        return "-ERR Debug segfault not implemented\r\n";
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
