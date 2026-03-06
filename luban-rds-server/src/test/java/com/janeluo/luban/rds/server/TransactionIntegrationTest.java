package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import static org.junit.Assert.*;
public class TransactionIntegrationTest {
    @Test
    public void testMultiExecAndGet() throws Exception {
        RdsConfig config = new RdsConfig();
        config.setPort(6381);
        NettyRedisServer server = new NettyRedisServer(config);
        server.start();
        
        Thread.sleep(1000);
        
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        JedisPool jedisPool = new JedisPool(poolConfig, "localhost", config.getPort(), 10000);
        
        try (Jedis j1 = jedisPool.getResource()) {
            Thread.sleep(500);
            
            j1.del("t:key");
            j1.set("t:key", "0");
            Transaction t = j1.multi();
            t.incr("t:key");
            t.incrBy("t:key", 5);
            java.util.List<Object> res = t.exec();
            assertNotNull(res);
            System.out.println("DEBUG: res.size() = " + res.size());
            for (int i = 0; i < res.size(); i++) {
                Object item = res.get(i);
                System.out.println("DEBUG: res[" + i + "] type = " + (item != null ? item.getClass().getName() : "null"));
                if (item instanceof byte[]) {
                    System.out.println("DEBUG: res[" + i + "] value (as string) = " + new String((byte[]) item));
                } else {
                    System.out.println("DEBUG: res[" + i + "] value = " + item);
                }
            }
            assertEquals(2, res.size());
            assertEquals(1L, ((Number) res.get(0)).longValue());
            assertEquals(6L, ((Number) res.get(1)).longValue());
            String val = j1.get("t:key");
            assertEquals("6", val);
        } finally {
            Thread.sleep(500);
            jedisPool.close();
            server.stop();
        }
    }
    
    @Test
    public void testWatchAbortOnExternalChange() throws Exception {
        RdsConfig config = new RdsConfig();
        config.setPort(6382);
        NettyRedisServer server = new NettyRedisServer(config);
        server.start();
        
        Thread.sleep(1000);
        
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        JedisPool jedisPool1 = new JedisPool(poolConfig, "localhost", config.getPort(), 10000);
        JedisPool jedisPool2 = new JedisPool(poolConfig, "localhost", config.getPort(), 10000);
        
        try (Jedis j1 = jedisPool1.getResource();
             Jedis j2 = jedisPool2.getResource()) {
            Thread.sleep(500);
            
            j1.del("w:key");
            j1.watch("w:key");
            Transaction t2 = j1.multi();
            t2.set("w:key", "a");
            
            Thread.sleep(500);
            
            j2.set("w:key", "b");
            
            Thread.sleep(500);
            
            java.util.List<Object> res = t2.exec();
            assertNull(res);
        } finally {
            Thread.sleep(500);
            jedisPool1.close();
            jedisPool2.close();
            server.stop();
        }
    }
}
