package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.context.InfoProvider;
import com.janeluo.luban.rds.common.context.ServerContext;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class CommonCommandHandlerInfoTest {

    private CommonCommandHandler handler;
    private MemoryStore store; // null is fine for this test as we mock provider

    @Before
    public void setUp() {
        handler = new CommonCommandHandler();
        // Set mock provider
        ServerContext.setInfoProvider(new MockInfoProvider());
    }

    @After
    public void tearDown() {
        ServerContext.setInfoProvider(null);
    }

    @Test
    public void testHandleInfoAll() {
        String[] args = {"INFO"};
        Object result = handler.handle(0, args, store);
        assertTrue(result instanceof String);
        String response = (String) result;
        assertTrue(response.contains("# Server"));
        assertTrue(response.contains("redis_version:1.0.0"));
        assertTrue(response.contains("# Clients"));
        assertTrue(response.contains("connected_clients:10"));
    }

    @Test
    public void testHandleInfoSection() {
        String[] args = {"INFO", "server"};
        Object result = handler.handle(0, args, store);
        assertTrue(result instanceof String);
        String response = (String) result;
        assertTrue(response.contains("# Server"));
        assertTrue(response.contains("redis_version:1.0.0"));
        assertTrue(!response.contains("# Clients"));
    }
    
    @Test
    public void testHandleInfoNoProvider() {
        ServerContext.setInfoProvider(null);
        String[] args = {"INFO"};
        Object result = handler.handle(0, args, store);
        assertTrue(result instanceof String);
        String response = (String) result;
        // Fallback response
        assertTrue(response.contains("# Server"));
        assertTrue(response.contains("redis_version:1.0.0"));
    }

    private static class MockInfoProvider implements InfoProvider {
        @Override
        public Map<String, Object> getInfo(String section) {
            Map<String, Object> info = new HashMap<>();
            if ("server".equalsIgnoreCase(section)) {
                info.put("redis_version", "1.0.0");
            } else if ("clients".equalsIgnoreCase(section)) {
                info.put("connected_clients", 10);
            }
            return info;
        }
    }
}
