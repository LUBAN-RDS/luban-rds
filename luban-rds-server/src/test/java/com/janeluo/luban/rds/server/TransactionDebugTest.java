package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import static org.junit.Assert.*;

public class TransactionDebugTest {
    @Test
    public void testTransactionWithRealRedis() throws Exception {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        JedisPool jedisPool = new JedisPool(poolConfig, "localhost", 6379, 10000);
        
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del("test:key");
            jedis.set("test:key", "0");
            Transaction t = jedis.multi();
            t.incr("test:key");
            t.incrBy("test:key", 5);
            java.util.List<Object> res = t.exec();
            
            System.out.println("Real Redis - res.size() = " + res.size());
            for (int i = 0; i < res.size(); i++) {
                Object item = res.get(i);
                System.out.println("Real Redis - res[" + i + "] type = " + (item != null ? item.getClass().getName() : "null"));
                System.out.println("Real Redis - res[" + i + "] value = " + item);
            }
        } finally {
            jedisPool.close();
        }
    }
    
    @Test
    public void testTransactionWithLubanRds() throws Exception {
        RdsConfig config = new RdsConfig();
        config.setPort(6381);
        NettyRedisServer server = new NettyRedisServer(config);
        server.start();
        
        Thread.sleep(1000);
        
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        JedisPool jedisPool = new JedisPool(poolConfig, "localhost", config.getPort(), 10000);
        
        try (Jedis jedis = jedisPool.getResource()) {
            Thread.sleep(500);
            
            jedis.del("test:key");
            jedis.set("test:key", "0");
            Transaction t = jedis.multi();
            t.incr("test:key");
            t.incrBy("test:key", 5);
            java.util.List<Object> res = t.exec();
            
            System.out.println("LubanRDS - res.size() = " + res.size());
            for (int i = 0; i < res.size(); i++) {
                Object item = res.get(i);
                System.out.println("LubanRDS - res[" + i + "] type = " + (item != null ? item.getClass().getName() : "null"));
                System.out.println("LubanRDS - res[" + i + "] value = " + item);
                if (item instanceof byte[]) {
                    System.out.println("LubanRDS - res[" + i + "] value (as string) = " + new String((byte[]) item));
                }
            }
        } finally {
            Thread.sleep(500);
            jedisPool.close();
            server.stop();
        }
    }
}
