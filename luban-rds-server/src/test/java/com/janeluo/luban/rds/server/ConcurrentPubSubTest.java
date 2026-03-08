package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RdsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发 Pub/Sub 测试
 * 测试多线程环境下的发布订阅功能
 */
@DisplayName("Concurrent Pub/Sub Tests")
class ConcurrentPubSubTest {

    private NettyRedisServer server;
    private int port;
    private JedisPool jedisPool;
    private String testDataDir;

    @BeforeEach
    void setUp() {
        port = findRandomPort();
        testDataDir = System.getProperty("java.io.tmpdir") + "/luban-rds-test-" + UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
        if (server != null && server.isRunning()) {
            server.stop();
            server = null;
        }
        Thread.sleep(200);
    }

    private int findRandomPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }

    private JedisPool createJedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(30);
        poolConfig.setMaxIdle(15);
        poolConfig.setMinIdle(2);
        return new JedisPool(poolConfig, "localhost", port, 5000);
    }

    private void startServer() throws InterruptedException {
        RdsConfig config = new RdsConfig();
        config.setPort(port);
        config.setWorkerThreads(4);
        config.setBusinessThreads(4);
        config.setPersistMode("rdb");
        config.setDir(testDataDir);
        config.setRdbSaveInterval(3600);

        server = new NettyRedisServer(config);
        server.start();
        
        Thread.sleep(300);
        
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            try (Jedis jedis = new Jedis("localhost", port, 1000)) {
                jedis.ping();
                break;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        
        jedisPool = createJedisPool();
    }

    @Test
    @DisplayName("Test concurrent subscribe and publish")
    void testConcurrentSubscribeAndPublish() throws Exception {
        startServer();

        int subscriberCount = 5;
        CountDownLatch subscribeLatch = new CountDownLatch(subscriberCount);
        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch messageLatch = new CountDownLatch(subscriberCount);
        ExecutorService executor = Executors.newFixedThreadPool(subscriberCount + 1);

        for (int i = 0; i < subscriberCount; i++) {
            executor.submit(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.subscribe(new JedisPubSub() {
                        @Override
                        public void onSubscribe(String channel, int subscribedChannels) {
                            subscribeLatch.countDown();
                        }

                        @Override
                        public void onMessage(String channel, String message) {
                            messageCount.incrementAndGet();
                            messageLatch.countDown();
                        }
                    }, "test-channel");
                } catch (Exception e) {
                    // 订阅结束或出错
                }
            });
        }

        assertTrue(subscribeLatch.await(10, TimeUnit.SECONDS), "All subscribers should be ready");

        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish("test-channel", "test-message");
            }
        });

        assertTrue(messageLatch.await(10, TimeUnit.SECONDS), "All subscribers should receive message");
        assertEquals(subscriberCount, messageCount.get(), "All subscribers should receive the message");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("Test multiple channels concurrent publish")
    void testMultipleChannelsConcurrentPublish() throws Exception {
        startServer();

        int channelCount = 3;
        int subscribersPerChannel = 3;
        int totalSubscribers = channelCount * subscribersPerChannel;

        CountDownLatch subscribeLatch = new CountDownLatch(totalSubscribers);
        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch messageLatch = new CountDownLatch(totalSubscribers);
        ExecutorService executor = Executors.newFixedThreadPool(totalSubscribers + channelCount);

        for (int ch = 0; ch < channelCount; ch++) {
            final String channel = "channel-" + ch;
            for (int s = 0; s < subscribersPerChannel; s++) {
                executor.submit(() -> {
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.subscribe(new JedisPubSub() {
                            @Override
                            public void onSubscribe(String ch, int subscribedChannels) {
                                subscribeLatch.countDown();
                            }

                            @Override
                            public void onMessage(String ch, String message) {
                                messageCount.incrementAndGet();
                                messageLatch.countDown();
                            }
                        }, channel);
                    } catch (Exception e) {
                        // 订阅结束或出错
                    }
                });
            }
        }

        assertTrue(subscribeLatch.await(15, TimeUnit.SECONDS), "All subscribers should be ready");

        for (int ch = 0; ch < channelCount; ch++) {
            final String channel = "channel-" + ch;
            final String message = "message-for-" + channel;
            executor.submit(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.publish(channel, message);
                }
            });
        }

        assertTrue(messageLatch.await(15, TimeUnit.SECONDS), "All subscribers should receive messages");
        assertEquals(totalSubscribers, messageCount.get(), "All subscribers should receive their channel's message");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("Test concurrent publish to single subscriber")
    void testConcurrentPublishToSingleSubscriber() throws Exception {
        startServer();

        int publisherCount = 5;
        int messagesPerPublisher = 10;

        CountDownLatch subscribeLatch = new CountDownLatch(1);
        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch messageLatch = new CountDownLatch(publisherCount * messagesPerPublisher);
        ExecutorService executor = Executors.newFixedThreadPool(publisherCount + 1);

        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        subscribeLatch.countDown();
                    }

                    @Override
                    public void onMessage(String channel, String message) {
                        messageCount.incrementAndGet();
                        messageLatch.countDown();
                    }
                }, "concurrent-channel");
            } catch (Exception e) {
                // 订阅结束或出错
            }
        });

        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS), "Subscriber should be ready");

        for (int p = 0; p < publisherCount; p++) {
            final int publisherId = p;
            executor.submit(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    for (int m = 0; m < messagesPerPublisher; m++) {
                        jedis.publish("concurrent-channel", "msg-" + publisherId + "-" + m);
                    }
                }
            });
        }

        assertTrue(messageLatch.await(30, TimeUnit.SECONDS), "All messages should be received");
        assertEquals(publisherCount * messagesPerPublisher, messageCount.get(), 
                "All messages should be received by subscriber");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("Test subscribe and unsubscribe concurrent")
    void testSubscribeAndUnsubscribeConcurrent() throws Exception {
        startServer();

        int subscriberCount = 5;
        CountDownLatch subscribeLatch = new CountDownLatch(subscriberCount);
        CountDownLatch unsubscribeLatch = new CountDownLatch(subscriberCount);
        ExecutorService executor = Executors.newFixedThreadPool(subscriberCount);

        for (int i = 0; i < subscriberCount; i++) {
            executor.submit(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    JedisPubSub pubSub = new JedisPubSub() {
                        @Override
                        public void onSubscribe(String channel, int subscribedChannels) {
                            subscribeLatch.countDown();
                        }

                        @Override
                        public void onUnsubscribe(String channel, int subscribedChannels) {
                            unsubscribeLatch.countDown();
                        }
                    };
                    
                    new Thread(() -> {
                        try {
                            Thread.sleep(500);
                            pubSub.unsubscribe("unsub-channel");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                    
                    jedis.subscribe(pubSub, "unsub-channel");
                } catch (Exception e) {
                    // 订阅结束
                }
            });
        }

        assertTrue(subscribeLatch.await(10, TimeUnit.SECONDS), "All subscribers should subscribe");
        assertTrue(unsubscribeLatch.await(10, TimeUnit.SECONDS), "All subscribers should unsubscribe");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("Test pattern subscribe concurrent")
    void testPatternSubscribeConcurrent() throws Exception {
        startServer();

        int subscriberCount = 3;
        CountDownLatch subscribeLatch = new CountDownLatch(subscriberCount);
        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch messageLatch = new CountDownLatch(3);
        ExecutorService executor = Executors.newFixedThreadPool(subscriberCount + 1);

        for (int i = 0; i < subscriberCount; i++) {
            executor.submit(() -> {
                try (Jedis jedis = jedisPool.getResource()) {
                    jedis.psubscribe(new JedisPubSub() {
                        @Override
                        public void onPSubscribe(String pattern, int subscribedChannels) {
                            subscribeLatch.countDown();
                        }

                        @Override
                        public void onPMessage(String pattern, String channel, String message) {
                            messageCount.incrementAndGet();
                            messageLatch.countDown();
                        }
                    }, "news-*");
                } catch (Exception e) {
                    // 订阅结束或出错
                }
            });
        }

        assertTrue(subscribeLatch.await(10, TimeUnit.SECONDS), "All pattern subscribers should be ready");

        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish("news-sports", "sports news");
                jedis.publish("news-tech", "tech news");
                jedis.publish("news-weather", "weather news");
            }
        });

        assertTrue(messageLatch.await(10, TimeUnit.SECONDS), "All pattern subscribers should receive messages");
        assertEquals(3, messageCount.get(), "Each pattern subscriber should receive 3 messages");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("Test publish without subscribers")
    void testPublishWithoutSubscribers() throws Exception {
        startServer();

        try (Jedis jedis = jedisPool.getResource()) {
            Long receivers = jedis.publish("no-subscriber-channel", "test-message");
            assertEquals(0L, receivers, "Should have 0 receivers when no subscribers");
        }
    }

    @Test
    @DisplayName("Test multiple publishers concurrent")
    void testMultiplePublishersConcurrent() throws Exception {
        startServer();

        int publisherCount = 10;
        int messagesPerPublisher = 20;
        int totalMessages = publisherCount * messagesPerPublisher;

        CountDownLatch subscribeLatch = new CountDownLatch(1);
        AtomicInteger messageCount = new AtomicInteger(0);
        CountDownLatch messageLatch = new CountDownLatch(totalMessages);
        ExecutorService executor = Executors.newFixedThreadPool(publisherCount + 1);

        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        subscribeLatch.countDown();
                    }

                    @Override
                    public void onMessage(String channel, String message) {
                        messageCount.incrementAndGet();
                        messageLatch.countDown();
                    }
                }, "multi-pub-channel");
            } catch (Exception e) {
                // 订阅结束或出错
            }
        });

        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS), "Subscriber should be ready");

        CountDownLatch publishStartLatch = new CountDownLatch(1);
        for (int p = 0; p < publisherCount; p++) {
            final int publisherId = p;
            executor.submit(() -> {
                try {
                    publishStartLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        for (int m = 0; m < messagesPerPublisher; m++) {
                            jedis.publish("multi-pub-channel", "publisher-" + publisherId + "-msg-" + m);
                        }
                    }
                } catch (Exception e) {
                    // 忽略错误
                }
            });
        }

        publishStartLatch.countDown();

        assertTrue(messageLatch.await(60, TimeUnit.SECONDS), "All messages should be received");
        assertEquals(totalMessages, messageCount.get(), "All messages should be received");

        executor.shutdownNow();
    }

    @Test
    @DisplayName("Test subscriber connection close cleanup")
    void testSubscriberConnectionCloseCleanup() throws Exception {
        startServer();

        CountDownLatch subscribeLatch = new CountDownLatch(1);
        
        Jedis subscriberJedis = new Jedis("localhost", port);
        
        Thread subscriberThread = new Thread(() -> {
            try {
                subscriberJedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        subscribeLatch.countDown();
                    }
                }, "cleanup-channel");
            } catch (Exception e) {
                // 连接关闭时会抛出异常
            }
        });
        subscriberThread.start();

        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS), "Subscriber should be ready");

        subscriberJedis.close();
        subscriberThread.join(2000);

        try (Jedis jedis = jedisPool.getResource()) {
            Long receivers = jedis.publish("cleanup-channel", "test-after-close");
            assertEquals(0L, receivers, "Should have 0 receivers after subscriber disconnect");
        }
    }

    @Test
    @DisplayName("Test high volume pub/sub")
    void testHighVolumePubSub() throws Exception {
        startServer();

        int messageCount = 1000;
        CountDownLatch subscribeLatch = new CountDownLatch(1);
        AtomicInteger receivedCount = new AtomicInteger(0);
        CountDownLatch messageLatch = new CountDownLatch(messageCount);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        subscribeLatch.countDown();
                    }

                    @Override
                    public void onMessage(String channel, String message) {
                        receivedCount.incrementAndGet();
                        messageLatch.countDown();
                    }
                }, "high-volume-channel");
            } catch (Exception e) {
                // 订阅结束或出错
            }
        });

        assertTrue(subscribeLatch.await(5, TimeUnit.SECONDS), "Subscriber should be ready");

        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                for (int i = 0; i < messageCount; i++) {
                    jedis.publish("high-volume-channel", "message-" + i);
                }
            }
        });

        assertTrue(messageLatch.await(60, TimeUnit.SECONDS), "All messages should be received");
        assertEquals(messageCount, receivedCount.get(), "All messages should be received");

        executor.shutdownNow();
    }
}
