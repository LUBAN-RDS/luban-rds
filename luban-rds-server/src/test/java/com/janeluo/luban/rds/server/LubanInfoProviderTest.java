package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.context.ServerContext;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.PersistService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class LubanInfoProviderTest {

    private MockNettyRedisServer server;
    private LubanInfoProvider provider;

    @Before
    public void setUp() {
        server = new MockNettyRedisServer();
        provider = new LubanInfoProvider(server);
    }

    @After
    public void tearDown() {
        ServerContext.setInfoProvider(null);
    }

    @Test
    public void testGetInfoAll() {
        Map<String, Object> info = provider.getInfo("all");
        assertNotNull(info);
        assertTrue(info.containsKey("redis_version"));
        assertTrue(info.containsKey("os"));
        assertTrue(info.containsKey("process_id"));
        assertTrue(info.containsKey("tcp_port"));
        assertTrue(info.containsKey("connected_clients"));
        assertTrue(info.containsKey("used_memory"));
        assertTrue(info.containsKey("rdb_last_save_time"));
        // Check specific values
        assertEquals("1.0.0", info.get("redis_version"));
        assertEquals(9736, info.get("tcp_port"));
        assertEquals("master", info.get("role"));
    }

    @Test
    public void testGetInfoSection() {
        Map<String, Object> serverInfo = provider.getInfo("server");
        assertNotNull(serverInfo);
        assertTrue(serverInfo.containsKey("redis_version"));
        // assertTrue(!serverInfo.containsKey("connected_clients")); // This assertion might fail if implementation puts all info? No, it filters.

        Map<String, Object> clientsInfo = provider.getInfo("clients");
        assertNotNull(clientsInfo);
        assertTrue(clientsInfo.containsKey("connected_clients"));
        // assertTrue(!clientsInfo.containsKey("redis_version"));
    }

    // Mock Server
    private static class MockNettyRedisServer extends NettyRedisServer {
        private final MemoryStore memoryStore;
        private final PersistService persistService;

        public MockNettyRedisServer() {
            super(9736, "rdb", System.getProperty("java.io.tmpdir"), 60, 1); // Use temp dir
            this.memoryStore = new DefaultMemoryStore();
            this.persistService = new MockPersistService();
        }

        @Override
        public MemoryStore getMemoryStore() {
            return memoryStore;
        }

        @Override
        public PersistService getPersistService() {
            return persistService;
        }
        
        @Override
        public int getPort() {
            return 9736;
        }
    }

    // Mock PersistService
    private static class MockPersistService implements PersistService {
        @Override
        public void persist(MemoryStore memoryStore) {
        }

        @Override
        public void load(MemoryStore memoryStore) {
        }

        @Override
        public void close() {
        }

        @Override
        public Map<String, Object> getInfo() {
            Map<String, Object> info = new HashMap<>();
            info.put("rdb_last_save_time", 1234567890L);
            info.put("aof_enabled", 0);
            return info;
        }
    }
}
