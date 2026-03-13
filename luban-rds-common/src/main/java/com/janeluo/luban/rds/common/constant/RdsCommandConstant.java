package com.janeluo.luban.rds.common.constant;

/**
 * Redis 命令常量类
 * 定义所有支持的 Redis 命令名称常量
 */
public final class RdsCommandConstant {

    private RdsCommandConstant() {
    }

    // ==================== 字符串命令 ====================

    public static final String SET = "SET";
    public static final String SETNX = "SETNX";
    public static final String GET = "GET";
    public static final String INCR = "INCR";
    public static final String DECR = "DECR";
    public static final String INCRBY = "INCRBY";
    public static final String DECRBY = "DECRBY";
    public static final String APPEND = "APPEND";
    public static final String STRLEN = "STRLEN";
    public static final String MSET = "MSET";
    public static final String MGET = "MGET";
    public static final String GETSET = "GETSET";
    public static final String SETRANGE = "SETRANGE";
    public static final String GETRANGE = "GETRANGE";
    public static final String PSETEX = "PSETEX";

    // ==================== 哈希命令 ====================

    public static final String HSET = "HSET";
    public static final String HSETNX = "HSETNX";
    public static final String HMSET = "HMSET";
    public static final String HGET = "HGET";
    public static final String HMGET = "HMGET";
    public static final String HGETALL = "HGETALL";
    public static final String HDEL = "HDEL";
    public static final String HEXISTS = "HEXISTS";
    public static final String HKEYS = "HKEYS";
    public static final String HVALS = "HVALS";
    public static final String HLEN = "HLEN";
    public static final String HINCRBY = "HINCRBY";
    public static final String HSCAN = "HSCAN";

    // ==================== 列表命令 ====================

    public static final String LPUSH = "LPUSH";
    public static final String RPUSH = "RPUSH";
    public static final String LPOP = "LPOP";
    public static final String RPOP = "RPOP";
    public static final String LLEN = "LLEN";
    public static final String LRANGE = "LRANGE";
    public static final String LREM = "LREM";
    public static final String LINDEX = "LINDEX";
    public static final String LSET = "LSET";
    public static final String BLPOP = "BLPOP";
    public static final String BRPOP = "BRPOP";
    public static final String LTRIM = "LTRIM";

    // ==================== 集合命令 ====================

    public static final String SADD = "SADD";
    public static final String SREM = "SREM";
    public static final String SMEMBERS = "SMEMBERS";
    public static final String SISMEMBER = "SISMEMBER";
    public static final String SCARD = "SCARD";
    public static final String SINTER = "SINTER";
    public static final String SUNION = "SUNION";
    public static final String SDIFF = "SDIFF";
    public static final String SSCAN = "SSCAN";

    // ==================== 有序集合命令 ====================

    public static final String ZADD = "ZADD";
    public static final String ZRANGE = "ZRANGE";
    public static final String ZRANGEBYSCORE = "ZRANGEBYSCORE";
    public static final String ZSCORE = "ZSCORE";
    public static final String ZREM = "ZREM";
    public static final String ZCARD = "ZCARD";
    public static final String ZSCAN = "ZSCAN";
    public static final String ZREMRANGEBYSCORE = "ZREMRANGEBYSCORE";
    public static final String ZREMRANGEBYRANK = "ZREMRANGEBYRANK";
    public static final String ZRANK = "ZRANK";
    public static final String ZREVRANK = "ZREVRANK";
    public static final String ZINCRBY = "ZINCRBY";
    public static final String ZCOUNT = "ZCOUNT";
    public static final String ZPOPMAX = "ZPOPMAX";
    public static final String ZPOPMIN = "ZPOPMIN";
    public static final String ZREVRANGE = "ZREVRANGE";

    // ==================== 通用命令 ====================

    public static final String EXISTS = "EXISTS";
    public static final String DEL = "DEL";
    public static final String EXPIRE = "EXPIRE";
    public static final String PEXPIRE = "PEXPIRE";
    public static final String TTL = "TTL";
    public static final String PTTL = "PTTL";
    public static final String FLUSHALL = "FLUSHALL";
    public static final String TYPE = "TYPE";
    public static final String PING = "PING";
    public static final String ECHO = "ECHO";
    public static final String SELECT = "SELECT";
    public static final String INFO = "INFO";
    public static final String SCAN = "SCAN";
    public static final String PUBLISH = "PUBLISH";

    // ==================== 事务命令 ====================

    public static final String MULTI = "MULTI";
    public static final String EXEC = "EXEC";
    public static final String DISCARD = "DISCARD";
    public static final String WATCH = "WATCH";
    public static final String UNWATCH = "UNWATCH";

    // ==================== 服务器命令 ====================

    public static final String BGREWRITEAOF = "BGREWRITEAOF";
    public static final String BGSAVE = "BGSAVE";
    public static final String LASTSAVE = "LASTSAVE";
    public static final String FLUSHDB = "FLUSHDB";
    public static final String DBSIZE = "DBSIZE";
    public static final String TIME = "TIME";

    // ==================== 客户端命令 ====================

    public static final String CLIENT_KILL = "CLIENT KILL";
    public static final String CLIENT_LIST = "CLIENT LIST";
    public static final String CLIENT_GETNAME = "CLIENT GETNAME";
    public static final String CLIENT_PAUSE = "CLIENT PAUSE";
    public static final String CLIENT_SETNAME = "CLIENT SETNAME";

    // ==================== 集群命令 ====================

    public static final String CLUSTER_SLOTS = "CLUSTER SLOTS";

    // ==================== 命令信息命令 ====================

    public static final String COMMAND = "COMMAND";
    public static final String COMMAND_COUNT = "COMMAND COUNT";
    public static final String COMMAND_GETKEYS = "COMMAND GETKEYS";
    public static final String COMMAND_INFO = "COMMAND INFO";

    // ==================== 配置命令 ====================

    public static final String CONFIG_GET = "CONFIG GET";
    public static final String CONFIG_REWRITE = "CONFIG REWRITE";
    public static final String CONFIG_SET = "CONFIG SET";
    public static final String CONFIG_RESETSTAT = "CONFIG RESETSTAT";

    // ==================== 调试命令 ====================

    public static final String DEBUG_OBJECT = "DEBUG OBJECT";
    public static final String DEBUG_SEGFAULT = "DEBUG SEGFAULT";

    // ==================== Stream 命令 ====================

    public static final String XADD = "XADD";
    public static final String XLEN = "XLEN";
    public static final String XRANGE = "XRANGE";
    public static final String XREVRANGE = "XREVRANGE";
    public static final String XDEL = "XDEL";
    public static final String XTRIM = "XTRIM";
    public static final String XREAD = "XREAD";
    public static final String XINFO = "XINFO";
    
    // ==================== Stream 消费者组命令 ====================
    
    public static final String XGROUP = "XGROUP";
    public static final String XREADGROUP = "XREADGROUP";
    public static final String XACK = "XACK";
    public static final String XPENDING = "XPENDING";
    public static final String XCLAIM = "XCLAIM";
    public static final String XAUTOCLAIM = "XAUTOCLAIM";
}
