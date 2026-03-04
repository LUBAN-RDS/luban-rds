package com.janeluo.luban.rds.common.constant;

import org.junit.Test;
import static org.junit.Assert.*;

public class RdsCommandConstantTest {

    @Test
    public void testStringCommands() {
        assertEquals("SET", RdsCommandConstant.SET);
        assertEquals("GET", RdsCommandConstant.GET);
        assertEquals("INCR", RdsCommandConstant.INCR);
        assertEquals("DECR", RdsCommandConstant.DECR);
        assertEquals("INCRBY", RdsCommandConstant.INCRBY);
        assertEquals("DECRBY", RdsCommandConstant.DECRBY);
        assertEquals("APPEND", RdsCommandConstant.APPEND);
        assertEquals("STRLEN", RdsCommandConstant.STRLEN);
    }

    @Test
    public void testHashCommands() {
        assertEquals("HSET", RdsCommandConstant.HSET);
        assertEquals("HGET", RdsCommandConstant.HGET);
        assertEquals("HGETALL", RdsCommandConstant.HGETALL);
        assertEquals("HDEL", RdsCommandConstant.HDEL);
        assertEquals("HEXISTS", RdsCommandConstant.HEXISTS);
        assertEquals("HKEYS", RdsCommandConstant.HKEYS);
        assertEquals("HVALS", RdsCommandConstant.HVALS);
        assertEquals("HLEN", RdsCommandConstant.HLEN);
    }

    @Test
    public void testListCommands() {
        assertEquals("LPUSH", RdsCommandConstant.LPUSH);
        assertEquals("RPUSH", RdsCommandConstant.RPUSH);
        assertEquals("LPOP", RdsCommandConstant.LPOP);
        assertEquals("RPOP", RdsCommandConstant.RPOP);
        assertEquals("LLEN", RdsCommandConstant.LLEN);
        assertEquals("LRANGE", RdsCommandConstant.LRANGE);
    }

    @Test
    public void testSetCommands() {
        assertEquals("SADD", RdsCommandConstant.SADD);
        assertEquals("SREM", RdsCommandConstant.SREM);
        assertEquals("SMEMBERS", RdsCommandConstant.SMEMBERS);
        assertEquals("SISMEMBER", RdsCommandConstant.SISMEMBER);
        assertEquals("SCARD", RdsCommandConstant.SCARD);
    }

    @Test
    public void testZSetCommands() {
        assertEquals("ZADD", RdsCommandConstant.ZADD);
        assertEquals("ZRANGE", RdsCommandConstant.ZRANGE);
        assertEquals("ZSCORE", RdsCommandConstant.ZSCORE);
        assertEquals("ZREM", RdsCommandConstant.ZREM);
        assertEquals("ZCARD", RdsCommandConstant.ZCARD);
    }

    @Test
    public void testGenericCommands() {
        assertEquals("EXISTS", RdsCommandConstant.EXISTS);
        assertEquals("DEL", RdsCommandConstant.DEL);
        assertEquals("EXPIRE", RdsCommandConstant.EXPIRE);
        assertEquals("TTL", RdsCommandConstant.TTL);
        assertEquals("FLUSHALL", RdsCommandConstant.FLUSHALL);
        assertEquals("TYPE", RdsCommandConstant.TYPE);
        assertEquals("PING", RdsCommandConstant.PING);
        assertEquals("ECHO", RdsCommandConstant.ECHO);
        assertEquals("SELECT", RdsCommandConstant.SELECT);
        assertEquals("INFO", RdsCommandConstant.INFO);
        assertEquals("SCAN", RdsCommandConstant.SCAN);
    }

    @Test
    public void testTransactionCommands() {
        assertEquals("MULTI", RdsCommandConstant.MULTI);
        assertEquals("EXEC", RdsCommandConstant.EXEC);
        assertEquals("DISCARD", RdsCommandConstant.DISCARD);
        assertEquals("WATCH", RdsCommandConstant.WATCH);
        assertEquals("UNWATCH", RdsCommandConstant.UNWATCH);
    }

    @Test
    public void testServerCommands() {
        assertEquals("BGREWRITEAOF", RdsCommandConstant.BGREWRITEAOF);
        assertEquals("BGSAVE", RdsCommandConstant.BGSAVE);
        assertEquals("LASTSAVE", RdsCommandConstant.LASTSAVE);
        assertEquals("FLUSHDB", RdsCommandConstant.FLUSHDB);
        assertEquals("DBSIZE", RdsCommandConstant.DBSIZE);
        assertEquals("TIME", RdsCommandConstant.TIME);
    }

    @Test
    public void testClientCommands() {
        assertEquals("CLIENT KILL", RdsCommandConstant.CLIENT_KILL);
        assertEquals("CLIENT LIST", RdsCommandConstant.CLIENT_LIST);
        assertEquals("CLIENT GETNAME", RdsCommandConstant.CLIENT_GETNAME);
        assertEquals("CLIENT PAUSE", RdsCommandConstant.CLIENT_PAUSE);
        assertEquals("CLIENT SETNAME", RdsCommandConstant.CLIENT_SETNAME);
    }

    @Test
    public void testClusterCommands() {
        assertEquals("CLUSTER SLOTS", RdsCommandConstant.CLUSTER_SLOTS);
    }

    @Test
    public void testCommandInfoCommands() {
        assertEquals("COMMAND", RdsCommandConstant.COMMAND);
        assertEquals("COMMAND COUNT", RdsCommandConstant.COMMAND_COUNT);
        assertEquals("COMMAND GETKEYS", RdsCommandConstant.COMMAND_GETKEYS);
        assertEquals("COMMAND INFO", RdsCommandConstant.COMMAND_INFO);
    }

    @Test
    public void testConfigCommands() {
        assertEquals("CONFIG GET", RdsCommandConstant.CONFIG_GET);
        assertEquals("CONFIG REWRITE", RdsCommandConstant.CONFIG_REWRITE);
        assertEquals("CONFIG SET", RdsCommandConstant.CONFIG_SET);
        assertEquals("CONFIG RESETSTAT", RdsCommandConstant.CONFIG_RESETSTAT);
    }

    @Test
    public void testDebugCommands() {
        assertEquals("DEBUG OBJECT", RdsCommandConstant.DEBUG_OBJECT);
        assertEquals("DEBUG SEGFAULT", RdsCommandConstant.DEBUG_SEGFAULT);
    }
}
