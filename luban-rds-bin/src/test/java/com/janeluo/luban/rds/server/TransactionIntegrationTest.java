package com.janeluo.luban.rds.server;
import com.janeluo.luban.rds.common.config.RdsConfig;
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
        
        // 给服务器一些启动时间
        Thread.sleep(1000);
        
        // 创建 Jedis 池，设置更长的超时时间
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        JedisPool jedisPool = new JedisPool(poolConfig, "localhost", config.getPort(), 10000);
        
        try (Jedis j1 = jedisPool.getResource()) {
            // 给连接一些时间建立
            Thread.sleep(500);
            
            j1.del("t:key");
            j1.set("t:key", "0");
            Transaction t = j1.multi();
            t.incr("t:key");
            t.incrBy("t:key", 5);
            java.util.List<Object> res = t.exec();
            assertNotNull(res);
            assertEquals(2, res.size());
            assertEquals(1L, ((Number) res.get(0)).longValue());
            assertEquals(6L, ((Number) res.get(1)).longValue());
            String val = j1.get("t:key");
            assertEquals("6", val);
        } finally {
            // 给服务器一些时间处理最后的请求
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
        
        // 给服务器一些启动时间
        Thread.sleep(1000);
        
        // 创建 Jedis 池，设置更长的超时时间
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        JedisPool jedisPool1 = new JedisPool(poolConfig, "localhost", config.getPort(), 10000);
        JedisPool jedisPool2 = new JedisPool(poolConfig, "localhost", config.getPort(), 10000);
        
        try (Jedis j1 = jedisPool1.getResource();
             Jedis j2 = jedisPool2.getResource()) {
            // 给连接一些时间建立
            Thread.sleep(500);
            
            j1.del("w:key");
            j1.watch("w:key");
            Transaction t2 = j1.multi();
            t2.set("w:key", "a");
            
            // 给服务器一些时间处理 WATCH 命令
            Thread.sleep(500);
            
            j2.set("w:key", "b");
            
            // 给服务器一些时间处理 SET 命令
            Thread.sleep(500);
            
            java.util.List<Object> res = t2.exec();
            assertNull(res);
        } finally {
            // 给服务器一些时间处理最后的请求
            Thread.sleep(500);
            jedisPool1.close();
            jedisPool2.close();
            server.stop();
        }
    }
}
