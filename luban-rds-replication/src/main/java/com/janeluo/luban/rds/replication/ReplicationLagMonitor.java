package com.janeluo.luban.rds.replication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 复制延迟监控器
 * 
 * 监控主从复制延迟，包括：
 * - 记录每个从节点的复制偏移量
 * - 计算并记录复制延迟
 * - 提供延迟统计信息
 * - 支持延迟告警
 */
public class ReplicationLagMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(ReplicationLagMonitor.class);
    
    /**
     * 默认监控间隔（秒）
     */
    private static final int DEFAULT_MONITOR_INTERVAL = 1;
    
    /**
     * 默认延迟告警阈值（字节）
     */
    private static final long DEFAULT_LAG_ALERT_THRESHOLD = 1024 * 1024; // 1MB
    
    /**
     * 主节点复制管理器
     */
    private final MasterReplicationManager replicationManager;
    
    /**
     * 从节点延迟历史记录
     */
    private final Map<String, LagHistory> lagHistories = new ConcurrentHashMap<>();
    
    /**
     * 延迟告警阈值（字节）
     */
    private volatile long lagAlertThreshold = DEFAULT_LAG_ALERT_THRESHOLD;
    
    /**
     * 延迟告警回调
     */
    private volatile LagAlertCallback alertCallback;
    
    /**
     * 监控调度器
     */
    private ScheduledExecutorService monitorScheduler;
    
    /**
     * 统计信息
     */
    private final AtomicLong totalSamples = new AtomicLong(0);
    private final AtomicLong alertCount = new AtomicLong(0);
    
    public ReplicationLagMonitor(MasterReplicationManager replicationManager) {
        this.replicationManager = replicationManager;
    }
    
    /**
     * 启动监控
     */
    public synchronized void start() {
        if (monitorScheduler != null && !monitorScheduler.isShutdown()) {
            return;
        }
        
        monitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "replication-lag-monitor");
            t.setDaemon(true);
            return t;
        });
        
        monitorScheduler.scheduleAtFixedRate(
            this::monitorLag,
            DEFAULT_MONITOR_INTERVAL,
            DEFAULT_MONITOR_INTERVAL,
            TimeUnit.SECONDS
        );
        
        logger.info("Replication lag monitor started");
    }
    
    /**
     * 停止监控
     */
    public synchronized void stop() {
        if (monitorScheduler != null) {
            monitorScheduler.shutdown();
            try {
                if (!monitorScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    monitorScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                monitorScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            monitorScheduler = null;
        }
        
        logger.info("Replication lag monitor stopped");
    }
    
    /**
     * 监控延迟
     */
    private void monitorLag() {
        try {
            long masterOffset = replicationManager.getBacklog().getMasterReplOffset();
            
            for (SlaveInfo slave : replicationManager.getSlaves()) {
                String slaveId = slave.getSlaveId();
                long slaveOffset = slave.getOffset();
                long lag = masterOffset - slaveOffset;
                
                // 更新从节点延迟
                slave.updateReplicationLag(masterOffset);
                
                // 记录延迟历史
                recordLag(slaveId, lag);
                
                // 检查是否需要告警
                if (lag > lagAlertThreshold) {
                    alertCount.incrementAndGet();
                    
                    if (alertCallback != null) {
                        alertCallback.onLagAlert(slaveId, lag, lagAlertThreshold);
                    }
                    
                    logger.warn("Replication lag alert: slave={}, lag={} bytes, threshold={} bytes",
                               slaveId, lag, lagAlertThreshold);
                }
                
                totalSamples.incrementAndGet();
            }
            
        } catch (Exception e) {
            logger.error("Error monitoring replication lag", e);
        }
    }
    
    /**
     * 记录延迟历史
     */
    private void recordLag(String slaveId, long lag) {
        LagHistory history = lagHistories.computeIfAbsent(slaveId, k -> new LagHistory());
        history.record(lag);
    }
    
    /**
     * 获取从节点延迟历史
     */
    public LagHistory getLagHistory(String slaveId) {
        return lagHistories.get(slaveId);
    }
    
    /**
     * 获取所有从节点的延迟历史
     */
    public Map<String, LagHistory> getAllLagHistories() {
        return new HashMap<>(lagHistories);
    }
    
    /**
     * 获取当前延迟
     */
    public long getCurrentLag(String slaveId) {
        LagHistory history = lagHistories.get(slaveId);
        return history != null ? history.getCurrentLag() : 0;
    }
    
    /**
     * 获取平均延迟
     */
    public double getAverageLag(String slaveId) {
        LagHistory history = lagHistories.get(slaveId);
        return history != null ? history.getAverageLag() : 0;
    }
    
    /**
     * 获取最大延迟
     */
    public long getMaxLag(String slaveId) {
        LagHistory history = lagHistories.get(slaveId);
        return history != null ? history.getMaxLag() : 0;
    }
    
    /**
     * 设置延迟告警阈值
     */
    public void setLagAlertThreshold(long threshold) {
        this.lagAlertThreshold = threshold;
        logger.info("Lag alert threshold set to {} bytes", threshold);
    }
    
    /**
     * 获取延迟告警阈值
     */
    public long getLagAlertThreshold() {
        return lagAlertThreshold;
    }
    
    /**
     * 设置延迟告警回调
     */
    public void setAlertCallback(LagAlertCallback callback) {
        this.alertCallback = callback;
    }
    
    /**
     * 获取总采样次数
     */
    public long getTotalSamples() {
        return totalSamples.get();
    }
    
    /**
     * 获取告警次数
     */
    public long getAlertCount() {
        return alertCount.get();
    }
    
    /**
     * 清除延迟历史
     */
    public void clearHistory(String slaveId) {
        lagHistories.remove(slaveId);
    }
    
    /**
     * 清除所有延迟历史
     */
    public void clearAllHistory() {
        lagHistories.clear();
    }
    
    /**
     * 获取延迟监控信息
     */
    public String getLagInfo() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("# Replication Lag\r\n");
        sb.append("lag_monitor_status:").append(monitorScheduler != null ? "running" : "stopped").append("\r\n");
        sb.append("lag_alert_threshold:").append(lagAlertThreshold).append("\r\n");
        sb.append("lag_total_samples:").append(totalSamples.get()).append("\r\n");
        sb.append("lag_alert_count:").append(alertCount.get()).append("\r\n");
        
        for (Map.Entry<String, LagHistory> entry : lagHistories.entrySet()) {
            String slaveId = entry.getKey();
            LagHistory history = entry.getValue();
            
            sb.append("\r\n# Slave: ").append(slaveId).append("\r\n");
            sb.append("lag_current:").append(history.getCurrentLag()).append("\r\n");
            sb.append("lag_average:").append(String.format("%.2f", history.getAverageLag())).append("\r\n");
            sb.append("lag_max:").append(history.getMaxLag()).append("\r\n");
            sb.append("lag_min:").append(history.getMinLag()).append("\r\n");
            sb.append("lag_samples:").append(history.getSampleCount()).append("\r\n");
        }
        
        return sb.toString();
    }
    
    /**
     * 延迟历史记录
     */
    public static class LagHistory {
        
        /**
         * 最大历史记录数
         */
        private static final int MAX_HISTORY_SIZE = 1000;
        
        private final LinkedList<Long> lagHistory = new LinkedList<>();
        private volatile long currentLag = 0;
        private volatile long maxLag = 0;
        private volatile long minLag = Long.MAX_VALUE;
        private volatile double totalLag = 0;
        private volatile long sampleCount = 0;
        
        /**
         * 记录延迟
         */
        public synchronized void record(long lag) {
            currentLag = lag;
            
            lagHistory.addLast(lag);
            if (lagHistory.size() > MAX_HISTORY_SIZE) {
                lagHistory.removeFirst();
            }
            
            maxLag = Math.max(maxLag, lag);
            minLag = Math.min(minLag, lag);
            totalLag += lag;
            sampleCount++;
        }
        
        /**
         * 获取当前延迟
         */
        public long getCurrentLag() {
            return currentLag;
        }
        
        /**
         * 获取平均延迟
         */
        public double getAverageLag() {
            if (sampleCount == 0) {
                return 0;
            }
            return totalLag / sampleCount;
        }
        
        /**
         * 获取最大延迟
         */
        public long getMaxLag() {
            return maxLag;
        }
        
        /**
         * 获取最小延迟
         */
        public long getMinLag() {
            return minLag == Long.MAX_VALUE ? 0 : minLag;
        }
        
        /**
         * 获取采样次数
         */
        public long getSampleCount() {
            return sampleCount;
        }
        
        /**
         * 获取历史记录
         */
        public synchronized List<Long> getHistory() {
            return new ArrayList<>(lagHistory);
        }
        
        /**
         * 清除历史
         */
        public synchronized void clear() {
            lagHistory.clear();
            currentLag = 0;
            maxLag = 0;
            minLag = Long.MAX_VALUE;
            totalLag = 0;
            sampleCount = 0;
        }
    }
    
    /**
     * 延迟告警回调接口
     */
    public interface LagAlertCallback {
        void onLagAlert(String slaveId, long lag, long threshold);
    }
}
