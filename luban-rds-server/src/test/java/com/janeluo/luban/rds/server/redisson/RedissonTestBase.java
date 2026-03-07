package com.janeluo.luban.rds.server.redisson;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

import java.io.IOException;
import java.net.ServerSocket;

public abstract class RedissonTestBase {

    protected static NettyRedisServer server;
    protected static RedissonClient redisson;
    protected static int port;

    @BeforeAll
    public static void setUpAll() {
        port = findRandomPort();
        server = new NettyRedisServer(port);
        server.start();

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://127.0.0.1:" + port)
                .setRetryAttempts(3)
                .setRetryInterval(100)
                .setTimeout(3000); // 3 seconds timeout
        
        // Use StringCodec to avoid binary data corruption issues in Luban-RDS (which stores values as Strings)
        config.setCodec(new StringCodec());
        
        redisson = Redisson.create(config);
    }

    @AfterAll
    public static void tearDownAll() {
        if (redisson != null) {
            redisson.shutdown();
        }
        if (server != null) {
            server.stop();
        }
    }

    @BeforeEach
    public void setUp() {
        // Clear data before each test
        if (server != null && server.getMemoryStore() != null) {
            server.getMemoryStore().flushAll();
        }
    }

    private static int findRandomPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }
}
