package com.janeluo.luban.rds.replication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WAIT 命令执行器
 * 
 * 实现等待指定数量从节点同步的功能
 * 支持：
 * - 等待指定数量的从节点同步到当前偏移量
 * - 超时机制
 * - 返回已同步从节点数量
 */
public class WaitCommandExecutor {
    
    private static final Logger logger = LoggerFactory.getLogger(WaitCommandExecutor.class);
    
    /**
     * 默认超时时间（毫秒）
     */
    private static final long DEFAULT_TIMEOUT = 0;
    
    /**
     * 主节点复制管理器
     */
    private final MasterReplicationManager replicationManager;
    
    public WaitCommandExecutor(MasterReplicationManager replicationManager) {
        this.replicationManager = replicationManager;
    }
    
    /**
     * 执行 WAIT 命令
     * 
     * @param numSlaves 需要等待的从节点数量
     * @param timeout 超时时间（毫秒），0 表示不等待
     * @return 已同步的从节点数量
     */
    public int execute(int numSlaves, long timeout) {
        if (numSlaves <= 0) {
            return 0;
        }
        
        // 获取当前主节点偏移量
        long currentOffset = replicationManager.getBacklog().getMasterReplOffset();
        
        // 如果不需要等待，直接返回当前已同步数量
        if (timeout == 0) {
            return replicationManager.getSyncedSlavesCount(currentOffset);
        }
        
        // 如果已经满足条件，直接返回
        int currentSynced = replicationManager.getSyncedSlavesCount(currentOffset);
        if (currentSynced >= numSlaves) {
            logger.debug("WAIT: Already have {} synced slaves (required: {})", currentSynced, numSlaves);
            return currentSynced;
        }
        
        // 创建等待锁
        CountDownLatch latch = new CountDownLatch(numSlaves);
        AtomicInteger syncedCount = new AtomicInteger(currentSynced);
        
        // 创建同步检查任务
        SyncCheckTask checkTask = new SyncCheckTask(
            replicationManager, currentOffset, numSlaves, latch, syncedCount);
        
        // 启动检查线程
        Thread checkThread = new Thread(checkTask, "wait-sync-check");
        checkThread.setDaemon(true);
        checkThread.start();
        
        // 等待超时
        try {
            boolean completed = latch.await(timeout, TimeUnit.MILLISECONDS);
            
            if (completed) {
                logger.debug("WAIT: Completed, {} slaves synced", syncedCount.get());
            } else {
                logger.debug("WAIT: Timeout after {} ms, {} slaves synced", timeout, syncedCount.get());
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("WAIT: Interrupted while waiting for slaves");
        }
        
        // 停止检查任务
        checkTask.stop();
        
        return syncedCount.get();
    }
    
    /**
     * 同步检查任务
     */
    private static class SyncCheckTask implements Runnable {
        
        private final MasterReplicationManager replicationManager;
        private final long targetOffset;
        private final int numSlaves;
        private final CountDownLatch latch;
        private final AtomicInteger syncedCount;
        private volatile boolean running = true;
        
        // 检查间隔（毫秒）
        private static final long CHECK_INTERVAL = 10;
        
        public SyncCheckTask(MasterReplicationManager replicationManager, long targetOffset,
                            int numSlaves, CountDownLatch latch, AtomicInteger syncedCount) {
            this.replicationManager = replicationManager;
            this.targetOffset = targetOffset;
            this.numSlaves = numSlaves;
            this.latch = latch;
            this.syncedCount = syncedCount;
        }
        
        @Override
        public void run() {
            while (running && latch.getCount() > 0) {
                try {
                    // 检查每个从节点的同步状态
                    int currentSynced = replicationManager.getSyncedSlavesCount(targetOffset);
                    
                    // 计算新增的同步从节点数量
                    int newSynced = currentSynced - syncedCount.get();
                    
                    // 更新计数
                    for (int i = 0; i < newSynced && latch.getCount() > 0; i++) {
                        latch.countDown();
                    }
                    
                    syncedCount.set(currentSynced);
                    
                    // 如果已经满足条件，退出
                    if (currentSynced >= numSlaves) {
                        break;
                    }
                    
                    // 等待一段时间再检查
                    Thread.sleep(CHECK_INTERVAL);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        
        public void stop() {
            running = false;
        }
    }
    
    /**
     * 解析 WAIT 命令参数
     * 
     * @param args 命令参数
     * @return WaitParams 对象，如果参数无效则返回 null
     */
    public static WaitParams parseArgs(String[] args) {
        if (args == null || args.length < 3) {
            return null;
        }
        
        try {
            int numSlaves = Integer.parseInt(args[1]);
            long timeout = Long.parseLong(args[2]);
            
            if (numSlaves < 0 || timeout < 0) {
                return null;
            }
            
            return new WaitParams(numSlaves, timeout);
            
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * WAIT 命令参数
     */
    public static class WaitParams {
        private final int numSlaves;
        private final long timeout;
        
        public WaitParams(int numSlaves, long timeout) {
            this.numSlaves = numSlaves;
            this.timeout = timeout;
        }
        
        public int getNumSlaves() {
            return numSlaves;
        }
        
        public long getTimeout() {
            return timeout;
        }
    }
}
