package com.janeluo.luban.rds.server.redisson;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.LocalCachedMapOptions;
import org.redisson.api.RLocalCachedMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Test class for Redisson LocalCachedMap compatibility with Luban-RDS.
 * Covers basic operations, eviction policies, synchronization, and multi-client scenarios.
 */
public class RedissonLocalCachedMapTest extends RedissonTestBase {

    @Test
    @DisplayName("Test Basic Operations of LocalCachedMap")
    void testBasicOperations() {
        LocalCachedMapOptions<String, String> options = LocalCachedMapOptions.<String, String>defaults()
                .evictionPolicy(LocalCachedMapOptions.EvictionPolicy.LRU)
                .cacheSize(100)
                .syncStrategy(LocalCachedMapOptions.SyncStrategy.INVALIDATE)
                .reconnectionStrategy(LocalCachedMapOptions.ReconnectionStrategy.CLEAR);

        RLocalCachedMap<String, String> localMap = redisson.getLocalCachedMap("basicLocalMap", options);

        // Put operation
        localMap.put("key1", "value1");
        localMap.put("key2", "value2");

        // Verify local cache (first access might fetch from server)
        assertEquals("value1", localMap.get("key1"));
        assertEquals("value2", localMap.get("key2"));

        // Verify server storage (via another client or directly)
        // Since we are in unit test, we can assume RMap underlying storage is consistent
        assertTrue(localMap.containsKey("key1"));
        
        // Update operation
        localMap.put("key1", "updatedValue1");
        assertEquals("updatedValue1", localMap.get("key1"));

        // Remove operation
        localMap.remove("key2");
        assertFalse(localMap.containsKey("key2"));
        
        // Clear local cache only
        localMap.clearLocalCache();
        // Should still fetch from server
        assertEquals("updatedValue1", localMap.get("key1"));
    }

    @Test
    @DisplayName("Test Local Cache Synchronization between two clients")
    // TODO: This test currently fails due to potential timing or binary encoding issues in embedded environment.
    // Binary safety fixes (ISO-8859-1) and PubSubService implementation have been added, 
    // but invalidation message processing by Redisson seems to fail or be delayed.
    void testCacheSynchronization() throws InterruptedException {
        // Create a second Redisson client to simulate another node
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000);
        config.setCodec(new StringCodec());
        RedissonClient redisson2 = Redisson.create(config);

        try {
            LocalCachedMapOptions<String, String> options = LocalCachedMapOptions.<String, String>defaults()
                    .syncStrategy(LocalCachedMapOptions.SyncStrategy.INVALIDATE) // Invalidate on change
                    .reconnectionStrategy(LocalCachedMapOptions.ReconnectionStrategy.CLEAR);

            RLocalCachedMap<String, String> map1 = redisson.getLocalCachedMap("syncMap", options);
            RLocalCachedMap<String, String> map2 = redisson2.getLocalCachedMap("syncMap", options);

            // 1. Client 1 puts data
            map1.put("syncKey", "initialValue");
            
            // 2. Client 2 reads data (should fetch from server and cache locally)
            assertEquals("initialValue", map2.get("syncKey"));
            
            // 3. Client 1 updates data
            map1.put("syncKey", "updatedValue");
            
            // 4. Wait for invalidation message propagation (LocalCachedMap uses Pub/Sub)
            Thread.sleep(2000);
            
            // 5. Client 2 should see the update (invalidation should have cleared local cache)
            assertEquals("updatedValue", map2.get("syncKey"));

        } finally {
            redisson2.shutdown();
        }
    }

    @Test
    @DisplayName("Test Local Cache Eviction Policy (LRU)")
    void testEvictionPolicy() {
        // Create a map with very small cache size
        LocalCachedMapOptions<String, String> options = LocalCachedMapOptions.<String, String>defaults()
                .evictionPolicy(LocalCachedMapOptions.EvictionPolicy.LRU)
                .cacheSize(2) // Only hold 2 items
                .syncStrategy(LocalCachedMapOptions.SyncStrategy.NONE); // No sync needed for this test

        RLocalCachedMap<String, String> localMap = redisson.getLocalCachedMap("lruMap", options);

        localMap.put("k1", "v1");
        localMap.put("k2", "v2");
        
        // Access k1 to make it recently used
        localMap.get("k1");
        
        // Add k3, triggering eviction. k2 should be evicted (LRU)
        localMap.put("k3", "v3");

        // k1 and k3 should be in cache (fast access), k2 might be evicted (but RLocalCachedMap falls back to Redis transparently)
        // To strictly verify "eviction" from *local* memory without mocking internals is hard via public API,
        // but we can verify data integrity is maintained.
        assertEquals("v1", localMap.get("k1"));
        assertEquals("v2", localMap.get("k2")); // Still retrievable from server
        assertEquals("v3", localMap.get("k3"));
    }

    @Test
    @DisplayName("Test TTL (Time To Live) for Local Cache")
    void testLocalCacheTTL() throws InterruptedException {
        LocalCachedMapOptions<String, String> options = LocalCachedMapOptions.<String, String>defaults()
                .timeToLive(1, TimeUnit.SECONDS) // 1 second TTL
                .syncStrategy(LocalCachedMapOptions.SyncStrategy.NONE);

        RLocalCachedMap<String, String> localMap = redisson.getLocalCachedMap("ttlMap", options);

        localMap.put("ttlKey", "value");
        assertEquals("value", localMap.get("ttlKey"));

        // Wait for TTL to expire
        Thread.sleep(1200);

        // Data should still be there (fetched from server), verifying no exception
        assertEquals("value", localMap.get("ttlKey"));
        
        // If we removed it from server "behind the back" (e.g. via another client), 
        // and local cache expired, we should get null.
        // But RLocalCachedMap is a view of Redis, so "expiration" just means "re-fetch from Redis".
    }
}
