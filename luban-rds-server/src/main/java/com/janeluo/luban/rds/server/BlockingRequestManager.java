package com.janeluo.luban.rds.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * 阻塞请求管理器
 * 
 * 管理 BLPOP/BRPOP 等阻塞命令的等待队列。
 * 当列表为空时，客户端请求会被挂起，直到有元素被推入列表或超时。
 * 
 * Redis BLPOP/BRPOP 规范：
 * 1. 如果列表有元素，立即弹出并返回
 * 2. 如果列表为空，阻塞等待直到有元素被推入
 * 3. 可以设置超时时间（0表示无限等待）
 * 4. 支持多个 key，按顺序检查
 * 5. 多个客户端同时等待同一个 key 时，按 FIFO 顺序服务
 */
public class BlockingRequestManager {
    
    private static final Logger logger = LoggerFactory.getLogger(BlockingRequestManager.class);
    
    private static final BlockingRequestManager INSTANCE = new BlockingRequestManager();
    
    public static BlockingRequestManager getInstance() {
        return INSTANCE;
    }
    
    private BlockingRequestManager() {
        // 启动超时检查线程
        timeoutChecker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "blocking-request-timeout-checker");
            t.setDaemon(true);
            return t;
        });
        timeoutChecker.scheduleAtFixedRate(this::checkTimeouts, 50, 50, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 阻塞请求类型
     */
    public enum BlockingType {
        BLPOP,  // 从左侧弹出
        BRPOP   // 从右侧弹出
    }
    
    /**
     * 阻塞请求
     */
    public static class BlockingRequest {
        final Channel channel;
        final int database;
        final String[] keys;          // 等待的 key 列表
        final BlockingType type;      // BLPOP 或 BRPOP
        final long timeoutMs;         // 超时时间（毫秒），0 表示无限等待
        final long createTime;        // 创建时间
        final String requestId;       // 唯一标识
        final CompletableFuture<String[]> future;  // 用于阻塞等待
        
        volatile boolean cancelled = false;
        volatile boolean completed = false;
        
        BlockingRequest(Channel channel, int database, String[] keys, BlockingType type, long timeoutMs) {
            this.channel = channel;
            this.database = database;
            this.keys = keys;
            this.type = type;
            this.timeoutMs = timeoutMs;
            this.createTime = System.currentTimeMillis();
            this.requestId = UUID.randomUUID().toString();
            this.future = new CompletableFuture<>();
        }
        
        /**
         * 计算剩余超时时间（毫秒）
         */
        long remainingTimeout() {
            if (timeoutMs == 0) return Long.MAX_VALUE;
            long elapsed = System.currentTimeMillis() - createTime;
            return Math.max(0, timeoutMs - elapsed);
        }
        
        /**
         * 是否已超时
         */
        boolean isTimedOut() {
            if (timeoutMs == 0) return false;
            return System.currentTimeMillis() - createTime >= timeoutMs;
        }
        
        /**
         * 完成请求（成功获取元素）
         */
        boolean complete(String key, String value) {
            if (completed || cancelled) return false;
            completed = true;
            return future.complete(new String[]{key, value});
        }
        
        /**
         * 超时完成
         */
        boolean completeTimeout() {
            if (completed || cancelled) return false;
            completed = true;
            return future.complete(null);
        }
        
        /**
         * 取消请求
         */
        void cancel() {
            if (completed || cancelled) return;
            cancelled = true;
            future.complete(null);
        }
    }
    
    // 按 database:key 分组的等待队列
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<BlockingRequest>> waitingRequests = new ConcurrentHashMap<>();
    
    // 按 channel 分组的请求（用于断开连接时清理）
    private final ConcurrentHashMap<Channel, Set<BlockingRequest>> requestsByChannel = new ConcurrentHashMap<>();
    
    // 所有活跃请求（用于超时检查）
    private final ConcurrentSkipListSet<BlockingRequest> activeRequests = new ConcurrentSkipListSet<>(
        Comparator.comparingLong(r -> r.createTime)
    );
    
    private final ScheduledExecutorService timeoutChecker;
    
    /**
     * 添加阻塞请求并等待结果
     * 
     * @param channel 客户端通道
     * @param database 数据库编号
     * @param keys 等待的 key 列表
     * @param type BLPOP 或 BRPOP
     * @param timeoutMs 超时时间（毫秒），0 表示无限等待
     * @return 阻塞请求对象，可通过 get() 方法等待结果
     */
    public BlockingRequest addRequest(Channel channel, int database, String[] keys, BlockingType type, long timeoutMs) {
        BlockingRequest request = new BlockingRequest(channel, database, keys, type, timeoutMs);
        
        // 注册到每个 key 的等待队列
        for (String key : keys) {
            String mapKey = createKey(database, key);
            waitingRequests.computeIfAbsent(mapKey, k -> new ConcurrentLinkedDeque<>()).add(request);
        }
        
        // 注册到 channel 映射
        requestsByChannel.computeIfAbsent(channel, c -> ConcurrentHashMap.newKeySet()).add(request);
        
        // 添加到活跃请求集合
        activeRequests.add(request);
        
        // 监听 channel 关闭事件
        channel.closeFuture().addListener((ChannelFutureListener) future -> {
            cancelRequest(request);
        });
        
        logger.debug("Added blocking request: requestId={}, keys={}, timeout={}ms", 
            request.requestId, Arrays.toString(keys), timeoutMs);
        
        return request;
    }
    
    /**
     * 取消阻塞请求
     */
    public void cancelRequest(BlockingRequest request) {
        if (request.cancelled || request.completed) return;
        request.cancel();
        
        // 从所有等待队列中移除
        for (String key : request.keys) {
            String mapKey = createKey(request.database, key);
            ConcurrentLinkedDeque<BlockingRequest> queue = waitingRequests.get(mapKey);
            if (queue != null) {
                queue.remove(request);
            }
        }
        
        // 从 channel 映射中移除
        Set<BlockingRequest> channelRequests = requestsByChannel.get(request.channel);
        if (channelRequests != null) {
            channelRequests.remove(request);
        }
        
        // 从活跃请求集合中移除
        activeRequests.remove(request);
        
        logger.debug("Cancelled blocking request: requestId={}", request.requestId);
    }
    
    /**
     * 当有元素被推入列表时，尝试唤醒等待的客户端
     * 
     * @param database 数据库编号
     * @param key 列表 key
     * @param popValue 弹出的值
     * @return 如果有客户端被唤醒，返回对应的请求；否则返回 null
     */
    public BlockingRequest tryWakeUp(int database, String key, String popValue) {
        String mapKey = createKey(database, key);
        ConcurrentLinkedDeque<BlockingRequest> queue = waitingRequests.get(mapKey);
        
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        
        // 遍历等待队列，找到第一个有效的请求
        while (!queue.isEmpty()) {
            BlockingRequest request = queue.pollFirst();
            
            if (request == null || request.cancelled || request.completed) {
                continue;
            }
            
            // 从所有其他 key 的等待队列中也移除这个请求
            for (String otherKey : request.keys) {
                if (!otherKey.equals(key)) {
                    String otherMapKey = createKey(request.database, otherKey);
                    ConcurrentLinkedDeque<BlockingRequest> otherQueue = waitingRequests.get(otherMapKey);
                    if (otherQueue != null) {
                        otherQueue.remove(request);
                    }
                }
            }
            
            // 从 channel 映射中移除
            Set<BlockingRequest> channelRequests = requestsByChannel.get(request.channel);
            if (channelRequests != null) {
                channelRequests.remove(request);
            }
            
            // 从活跃请求集合中移除
            activeRequests.remove(request);
            
            // 完成请求
            request.complete(key, popValue);
            
            logger.debug("Woke up blocking request: requestId={}, key={}, value={}", 
                request.requestId, key, popValue);
            
            return request;
        }
        
        return null;
    }
    
    /**
     * 检查是否有等待指定 key 的请求，并根据请求类型弹出元素
     * 
     * @param database 数据库编号
     * @param key 列表 key
     * @param lpopFunction 从左侧弹出的函数
     * @param rpopFunction 从右侧弹出的函数
     * @return 如果有客户端被唤醒，返回对应的请求；否则返回 null
     */
    public BlockingRequest tryWakeUpWithPop(int database, String key, 
                                              java.util.function.Supplier<String> lpopFunction,
                                              java.util.function.Supplier<String> rpopFunction) {
        String mapKey = createKey(database, key);
        ConcurrentLinkedDeque<BlockingRequest> queue = waitingRequests.get(mapKey);
        
        if (queue == null || queue.isEmpty()) {
            return null;
        }
        
        // 获取第一个等待者
        BlockingRequest request = queue.peekFirst();
        if (request == null || request.completed || request.cancelled) {
            return null;
        }
        
        // 根据请求类型弹出元素
        String popValue;
        if (request.type == BlockingType.BLPOP) {
            popValue = lpopFunction.get();
        } else {
            popValue = rpopFunction.get();
        }
        
        if (popValue == null) {
            return null;
        }
        
        // 唤醒等待者
        return tryWakeUp(database, key, popValue);
    }
    
    /**
     * 检查超时的请求
     */
    private void checkTimeouts() {
        Iterator<BlockingRequest> iterator = activeRequests.iterator();
        while (iterator.hasNext()) {
            BlockingRequest request = iterator.next();
            
            if (request.isTimedOut() && !request.completed && !request.cancelled) {
                logger.info("Request timed out: requestId={}, timeoutMs={}, elapsed={}ms", 
                    request.requestId, request.timeoutMs, System.currentTimeMillis() - request.createTime);
                
                // 从所有等待队列中移除
                for (String key : request.keys) {
                    String mapKey = createKey(request.database, key);
                    ConcurrentLinkedDeque<BlockingRequest> queue = waitingRequests.get(mapKey);
                    if (queue != null) {
                        queue.remove(request);
                    }
                }
                
                // 从 channel 映射中移除
                Set<BlockingRequest> channelRequests = requestsByChannel.get(request.channel);
                if (channelRequests != null) {
                    channelRequests.remove(request);
                }
                
                // 从活跃请求集合中移除
                activeRequests.remove(request);
                
                // 超时完成
                request.completeTimeout();
            }
        }
    }
    
    /**
     * 获取指定 key 的等待请求数量
     */
    public int getWaitingCount(int database, String key) {
        String mapKey = createKey(database, key);
        ConcurrentLinkedDeque<BlockingRequest> queue = waitingRequests.get(mapKey);
        return queue != null ? queue.size() : 0;
    }
    
    /**
     * 获取总等待请求数量
     */
    public int getTotalWaitingCount() {
        return activeRequests.size();
    }
    
    /**
     * 清理指定 channel 的所有请求
     */
    public void cleanupChannel(Channel channel) {
        Set<BlockingRequest> requests = requestsByChannel.remove(channel);
        if (requests != null) {
            for (BlockingRequest request : requests) {
                cancelRequest(request);
            }
        }
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        timeoutChecker.shutdown();
        try {
            if (!timeoutChecker.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutChecker.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutChecker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    private String createKey(int database, String key) {
        return database + ":" + key;
    }
}