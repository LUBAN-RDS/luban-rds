package com.janeluo.luban.rds.sentinel.core;

import com.janeluo.luban.rds.sentinel.config.SentinelConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 哨兵管理器
 * 管理多个哨兵实例
 */
public class SentinelManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SentinelManager.class);
    
    /**
     * 单例实例
     */
    private static volatile SentinelManager instance;
    
    /**
     * 哨兵实例映射
     */
    private final Map<String, Sentinel> sentinels = new ConcurrentHashMap<>();
    
    /**
     * 默认哨兵实例
     */
    private volatile Sentinel defaultSentinel;
    
    private SentinelManager() {
        // 私有构造函数
    }
    
    /**
     * 获取单例实例
     */
    public static SentinelManager getInstance() {
        if (instance == null) {
            synchronized (SentinelManager.class) {
                if (instance == null) {
                    instance = new SentinelManager();
                }
            }
        }
        return instance;
    }
    
    /**
     * 创建并启动哨兵
     */
    public Sentinel createSentinel(SentinelConfig config) {
        Sentinel sentinel = new Sentinel(config);
        sentinels.put(config.getSentinelId(), sentinel);
        
        if (defaultSentinel == null) {
            defaultSentinel = sentinel;
        }
        
        logger.info("Created sentinel instance: {}", config.getSentinelId());
        return sentinel;
    }
    
    /**
     * 启动哨兵
     */
    public void startSentinel(String sentinelId) {
        Sentinel sentinel = sentinels.get(sentinelId);
        if (sentinel != null) {
            sentinel.start();
        }
    }
    
    /**
     * 启动所有哨兵
     */
    public void startAll() {
        for (Sentinel sentinel : sentinels.values()) {
            sentinel.start();
        }
    }
    
    /**
     * 停止哨兵
     */
    public void stopSentinel(String sentinelId) {
        Sentinel sentinel = sentinels.get(sentinelId);
        if (sentinel != null) {
            sentinel.shutdown();
        }
    }
    
    /**
     * 停止所有哨兵
     */
    public void stopAll() {
        for (Sentinel sentinel : sentinels.values()) {
            sentinel.shutdown();
        }
    }
    
    /**
     * 移除哨兵
     */
    public void removeSentinel(String sentinelId) {
        Sentinel sentinel = sentinels.remove(sentinelId);
        if (sentinel != null) {
            sentinel.shutdown();
        }
        
        if (defaultSentinel != null && defaultSentinel.getSentinelId().equals(sentinelId)) {
            defaultSentinel = sentinels.isEmpty() ? null : sentinels.values().iterator().next();
        }
    }
    
    /**
     * 获取哨兵
     */
    public Sentinel getSentinel(String sentinelId) {
        return sentinels.get(sentinelId);
    }
    
    /**
     * 获取默认哨兵
     */
    public Sentinel getDefaultSentinel() {
        return defaultSentinel;
    }
    
    /**
     * 获取所有哨兵
     */
    public Map<String, Sentinel> getSentinels() {
        return new ConcurrentHashMap<>(sentinels);
    }
    
    /**
     * 获取哨兵数量
     */
    public int getSentinelCount() {
        return sentinels.size();
    }
    
    /**
     * 检查是否有哨兵运行
     */
    public boolean hasRunningSentinel() {
        for (Sentinel sentinel : sentinels.values()) {
            if (sentinel.isRunning()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 重置方法（用于测试）
     */
    public static synchronized void reset() {
        if (instance != null) {
            instance.stopAll();
            instance.sentinels.clear();
            instance.defaultSentinel = null;
            instance = null;
        }
    }
}
