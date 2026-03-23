package com.janeluo.luban.rds.core.acl;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * ACL 命令类别定义
 * 
 * <p>定义 Redis 7.2 ACL 中的命令类别，用于权限控制。
 * 参考：https://redis.io/commands/acl-cat
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class ACLCommandCategories {
    
    private static final Map<String, Set<String>> CATEGORY_COMMANDS = new HashMap<>();
    
    static {
        // @admin - 管理命令
        CATEGORY_COMMANDS.put("@admin", new HashSet<>(Arrays.asList(
            "ACL", "BGREWRITEAOF", "BGSAVE", "CLIENT", "CONFIG", "DEBUG", "FLUSHALL", 
            "FLUSHDB", "KEYS", "LASTSAVE", "MONITOR", "REPLICAOF", "ROLE", "SAVE",
            "SHUTDOWN", "SLAVEOF", "SLOWLOG", "SYNC", "PSYNC"
        )));
        
        // @read - 读取命令
        CATEGORY_COMMANDS.put("@read", new HashSet<>(Arrays.asList(
            "GET", "MGET", "GETRANGE", "GETBIT", "STRLEN", "SUBSTR", 
            "HGET", "HGETALL", "HMGET", "HKEYS", "HVALS", "HLEN", "HEXISTS", "HSTRLEN",
            "LINDEX", "LLEN", "LRANGE", "LPOS",
            "SCARD", "SDIFF", "SINTER", "SISMEMBER", "SMEMBERS", "SRANDMEMBER", "SUNION",
            "ZCARD", "ZCOUNT", "ZRANGE", "ZRANGEBYSCORE", "ZRANK", "ZREVRANGE", 
            "ZREVRANGEBYSCORE", "ZREVRANK", "ZSCORE", "ZMSCORE",
            "XLEN", "XRANGE", "XREVRANGE", "XINFO", "XPENDING",
            "GEOHASH", "GEOPOS", "GEODIST", "GEORADIUS", "GEORADIUSBYMEMBER",
            "BITCOUNT", "BITPOS", "BITFIELD_RO"
        )));
        
        // @write - 写入命令
        CATEGORY_COMMANDS.put("@write", new HashSet<>(Arrays.asList(
            "SET", "SETNX", "SETEX", "SETRANGE", "SETBIT", "MSET", "MSETNX", "APPEND",
            "INCR", "INCRBY", "INCRBYFLOAT", "DECR", "DECRBY",
            "HSET", "HMSET", "HSETNX", "HINCRBY", "HINCRBYFLOAT", "HDEL",
            "LPUSH", "LPUSHX", "RPUSH", "RPUSHX", "LPOP", "RPOP", "LREM", "LSET", "LTRIM",
            "SADD", "SREM", "SMOVE", "SPOP",
            "ZADD", "ZINCRBY", "ZREM", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE",
            "XADD", "XDEL", "XTRIM", "XGROUP", "XACK", "XCLAIM", "XAUTOCLAIM",
            "BITFIELD", "BITOP"
        )));
        
        // @string - 字符串命令
        CATEGORY_COMMANDS.put("@string", new HashSet<>(Arrays.asList(
            "GET", "SET", "SETNX", "SETEX", "GETSET", "GETRANGE", "SETRANGE", "STRLEN",
            "APPEND", "INCR", "INCRBY", "INCRBYFLOAT", "DECR", "DECRBY", "MGET", "MSET",
            "MSETNX", "GETBIT", "SETBIT", "BITCOUNT", "BITPOS", "BITOP", "BITFIELD",
            "SUBSTR"
        )));
        
        // @list - 列表命令
        CATEGORY_COMMANDS.put("@list", new HashSet<>(Arrays.asList(
            "LPUSH", "LPUSHX", "RPUSH", "RPUSHX", "LPOP", "RPOP", "BLPOP", "BRPOP", 
            "LLEN", "LINDEX", "LSET", "LRANGE", "LREM", "LTRIM", "RPOPLPUSH", 
            "BRPOPLPUSH", "LINSERT", "LPOS"
        )));
        
        // @set - 集合命令
        CATEGORY_COMMANDS.put("@set", new HashSet<>(Arrays.asList(
            "SADD", "SREM", "SISMEMBER", "SMEMBERS", "SCARD", "SPOP", "SRANDMEMBER",
            "SMOVE", "SDIFF", "SINTER", "SUNION", "SDIFFSTORE", "SINTERSTORE", 
            "SUNIONSTORE", "SSCAN"
        )));
        
        // @sortedset - 有序集合命令
        CATEGORY_COMMANDS.put("@sortedset", new HashSet<>(Arrays.asList(
            "ZADD", "ZINCRBY", "ZREM", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE", 
            "ZREMRANGEBYLEX", "ZSCORE", "ZMSCORE", "ZRANK", "ZREVRANK", "ZCARD",
            "ZCOUNT", "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZREVRANGEBYSCORE",
            "ZRANGEBYLEX", "ZREVRANGEBYLEX", "ZLEXCOUNT", "ZUNIONSTORE", "ZINTERSTORE",
            "ZPOPMAX", "ZPOPMIN", "ZSCAN", "ZDIFF", "ZDIFFSTORE", "ZINTER", "ZUNION"
        )));
        
        // @hash - 哈希命令
        CATEGORY_COMMANDS.put("@hash", new HashSet<>(Arrays.asList(
            "HSET", "HGET", "HMSET", "HMGET", "HGETALL", "HKEYS", "HVALS", "HLEN",
            "HEXISTS", "HDEL", "HSETNX", "HINCRBY", "HINCRBYFLOAT", "HSTRLEN", "HSCAN"
        )));
        
        // @stream - 流命令
        CATEGORY_COMMANDS.put("@stream", new HashSet<>(Arrays.asList(
            "XADD", "XLEN", "XRANGE", "XREVRANGE", "XDEL", "XTRIM", "XREAD", "XINFO",
            "XGROUP", "XREADGROUP", "XACK", "XPENDING", "XCLAIM", "XAUTOCLAIM",
            "XGROUP CREATE", "XGROUP DESTROY", "XGROUP CREATECONSUMER", 
            "XGROUP DELCONSUMER", "XGROUP SETID", "XGROUP HELP"
        )));
        
        // @pubsub - Pub/Sub 命令
        CATEGORY_COMMANDS.put("@pubsub", new HashSet<>(Arrays.asList(
            "PUBLISH", "SUBSCRIBE", "UNSUBSCRIBE", "PSUBSCRIBE", "PUNSUBSCRIBE", 
            "PUBSUB", "SSUBSCRIBE", "SUNSUBSCRIBE", "SPUBLISH"
        )));
        
        // @transaction - 事务命令
        CATEGORY_COMMANDS.put("@transaction", new HashSet<>(Arrays.asList(
            "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH"
        )));
        
        // @scripting - 脚本命令
        CATEGORY_COMMANDS.put("@scripting", new HashSet<>(Arrays.asList(
            "EVAL", "EVALSHA", "SCRIPT", "EVAL_RO", "EVALSHA_RO", "FUNCTION"
        )));
        
        // @fast - 快速命令（O(1)）
        CATEGORY_COMMANDS.put("@fast", new HashSet<>(Arrays.asList(
            "GET", "SET", "SETNX", "DEL", "EXISTS", "INCR", "DECR", "LPUSH", "RPUSH",
            "LPOP", "RPOP", "SADD", "SREM", "SISMEMBER", "SCARD", "HSET", "HGET",
            "HDEL", "HEXISTS", "HLEN", "PUBLISH", "PFCOUNT", "PFADD", "ZCARD",
            "ZSCORE", "ZADD", "ZREM"
        )));
        
        // @slow - 慢速命令
        CATEGORY_COMMANDS.put("@slow", new HashSet<>(Arrays.asList(
            "KEYS", "SORT", "XRANGE", "XREVRANGE", "ZRANGE", "ZREVRANGE", "LRANGE",
            "SMEMBERS", "HGETALL", "SDIFF", "SINTER", "SUNION", "ZUNIONSTORE", 
            "ZINTERSTORE", "BITOP"
        )));
        
        // @blocking - 阻塞命令
        CATEGORY_COMMANDS.put("@blocking", new HashSet<>(Arrays.asList(
            "BLPOP", "BRPOP", "BRPOPLPUSH", "BLMOVE", "BLMPOP", "BZPOPMIN", "BZPOPMAX",
            "XREAD", "XREADGROUP"
        )));
        
        // @dangerous - 危险命令
        CATEGORY_COMMANDS.put("@dangerous", new HashSet<>(Arrays.asList(
            "FLUSHALL", "FLUSHDB", "KEYS", "DEBUG", "SHUTDOWN", "SAVE", "BGSAVE",
            "BGREWRITEAOF", "REPLICAOF", "SLAVEOF", "SYNC", "PSYNC", "CONFIG", 
            "CLUSTER", "ACL"
        )));
        
        // @connection - 连接命令
        CATEGORY_COMMANDS.put("@connection", new HashSet<>(Arrays.asList(
            "AUTH", "ECHO", "PING", "QUIT", "SELECT", "SWAPDB", "COMMAND"
        )));
        
        // @keyspace - 键空间命令
        CATEGORY_COMMANDS.put("@keyspace", new HashSet<>(Arrays.asList(
            "DEL", "DUMP", "EXISTS", "EXPIRE", "EXPIREAT", "KEYS", "MIGRATE", "MOVE",
            "OBJECT", "PERSIST", "PEXPIRE", "PEXPIREAT", "PTTL", "RANDOMKEY", "RENAME",
            "RENAMENX", "RESTORE", "SCAN", "SORT", "TOUCH", "TTL", "TYPE", "UNLINK",
            "WAIT", "COPY"
        )));
        
        // @geo - 地理位置命令
        CATEGORY_COMMANDS.put("@geo", new HashSet<>(Arrays.asList(
            "GEOADD", "GEOHASH", "GEOPOS", "GEODIST", "GEORADIUS", "GEORADIUSBYMEMBER",
            "GEOSEARCH", "GEOSEARCHSTORE"
        )));
        
        // @hyperloglog - HyperLogLog 命令
        CATEGORY_COMMANDS.put("@hyperloglog", new HashSet<>(Arrays.asList(
            "PFADD", "PFCOUNT", "PFMERGE"
        )));
        
        // @json - JSON 命令（RedisJSON 模块）
        CATEGORY_COMMANDS.put("@json", new HashSet<>(Arrays.asList(
            "JSON.GET", "JSON.SET", "JSON.DEL", "JSON.MGET", "JSON.CLEAR", "JSON.TYPE",
            "JSON.NUMINCRBY", "JSON.NUMMULTBY", "JSON.STRAPPEND", "JSON.STRLEN",
            "JSON.ARRAPPEND", "JSON.ARRINDEX", "JSON.ARRINSERT", "JSON.ARRLEN",
            "JSON.ARRPOP", "JSON.ARRTRIM", "JSON.OBJKEYS", "JSON.OBJLEN", "JSON.DEBUG"
        )));
    }
    
    /**
     * 判断命令是否属于某个类别
     *
     * @param command 命令名称（大写）
     * @param category 类别名称（如 @read, @write）
     * @return 是否属于该类别
     */
    public static boolean isCommandInCategory(String command, String category) {
        if (command == null || category == null) {
            return false;
        }
        
        String upperCommand = command.toUpperCase();
        String lowerCategory = category.toLowerCase();
        
        // @all 表示所有命令
        if ("@all".equals(lowerCategory)) {
            return true;
        }
        
        Set<String> commands = CATEGORY_COMMANDS.get(lowerCategory);
        return commands != null && commands.contains(upperCommand);
    }
    
    /**
     * 获取命令所属的所有类别
     *
     * @param command 命令名称
     * @return 类别集合
     */
    public static Set<String> getCommandCategories(String command) {
        Set<String> categories = new HashSet<>();
        String upperCommand = command.toUpperCase();
        
        for (Map.Entry<String, Set<String>> entry : CATEGORY_COMMANDS.entrySet()) {
            if (entry.getValue().contains(upperCommand)) {
                categories.add(entry.getKey());
            }
        }
        
        return categories;
    }
    
    /**
     * 获取某个类别下的所有命令
     *
     * @param category 类别名称
     * @return 命令集合
     */
    public static Set<String> getCategoryCommands(String category) {
        return CATEGORY_COMMANDS.getOrDefault(category.toLowerCase(), Collections.emptySet());
    }
    
    /**
     * 获取所有类别名称
     *
     * @return 类别名称集合
     */
    public static Set<String> getAllCategories() {
        return Collections.unmodifiableSet(CATEGORY_COMMANDS.keySet());
    }
}
