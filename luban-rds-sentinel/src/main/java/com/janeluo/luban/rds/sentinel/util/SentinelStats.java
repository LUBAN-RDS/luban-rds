package com.janeluo.luban.rds.sentinel.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 哨兵统计信息
 */
public class SentinelStats {
    
    /**
     * 监控的主节点数量
     */
    private final AtomicInteger mastersMonitored = new AtomicInteger(0);
    
    /**
     * 主观下线事件计数
     */
    private final AtomicLong sDownEvents = new AtomicLong(0);
    
    /**
     * 客观下线事件计数
     */
    private final AtomicLong oDownEvents = new AtomicLong(0);
    
    /**
     * 故障转移事件计数
     */
    private final AtomicLong failoverEvents = new AtomicLong(0);
    
    /**
     * 成功的故障转移计数
     */
    private final AtomicLong failoverSuccess = new AtomicLong(0);
    
    /**
     * 失败的故障转移计数
     */
    private final AtomicLong failoverFailed = new AtomicLong(0);
    
    /**
     * 发送的 PING 命令计数
     */
    private final AtomicLong pingSent = new AtomicLong(0);
    
    /**
     * 接收的 PONG 响应计数
     */
    private final AtomicLong pongReceived = new AtomicLong(0);
    
    /**
     * 发送的 INFO 命令计数
     */
    private final AtomicLong infoSent = new AtomicLong(0);
    
    /**
     * 发送的 Hello 消息计数
     */
    private final AtomicLong helloSent = new AtomicLong(0);
    
    /**
     * 接收的命令计数
     */
    private final AtomicLong commandsReceived = new AtomicLong(0);
    
    /**
     * 处理的命令计数
     */
    private final AtomicLong commandsProcessed = new AtomicLong(0);
    
    /**
     * 启动时间
     */
    private final long startTime = System.currentTimeMillis();
    
    // Increment methods
    
    public void incrementMastersMonitored() { mastersMonitored.incrementAndGet(); }
    public void decrementMastersMonitored() { mastersMonitored.decrementAndGet(); }
    public void incrementSDownEvents() { sDownEvents.incrementAndGet(); }
    public void incrementODownEvents() { oDownEvents.incrementAndGet(); }
    public void incrementFailoverEvents() { failoverEvents.incrementAndGet(); }
    public void incrementFailoverSuccess() { failoverSuccess.incrementAndGet(); }
    public void incrementFailoverFailed() { failoverFailed.incrementAndGet(); }
    public void incrementPingSent() { pingSent.incrementAndGet(); }
    public void incrementPongReceived() { pongReceived.incrementAndGet(); }
    public void incrementInfoSent() { infoSent.incrementAndGet(); }
    public void incrementHelloSent() { helloSent.incrementAndGet(); }
    public void incrementCommandsReceived() { commandsReceived.incrementAndGet(); }
    public void incrementCommandsProcessed() { commandsProcessed.incrementAndGet(); }
    
    // Getter methods
    
    public int getMastersMonitored() { return mastersMonitored.get(); }
    public long getSDownEvents() { return sDownEvents.get(); }
    public long getODownEvents() { return oDownEvents.get(); }
    public long getFailoverEvents() { return failoverEvents.get(); }
    public long getFailoverSuccess() { return failoverSuccess.get(); }
    public long getFailoverFailed() { return failoverFailed.get(); }
    public long getPingSent() { return pingSent.get(); }
    public long getPongReceived() { return pongReceived.get(); }
    public long getInfoSent() { return infoSent.get(); }
    public long getHelloSent() { return helloSent.get(); }
    public long getCommandsReceived() { return commandsReceived.get(); }
    public long getCommandsProcessed() { return commandsProcessed.get(); }
    public long getStartTime() { return startTime; }
    
    /**
     * 获取运行时间（毫秒）
     */
    public long getUptime() {
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * 获取统计信息字符串
     */
    public String getStatsString() {
        StringBuilder stats = new StringBuilder();
        
        stats.append("# Sentinel Stats\r\n");
        stats.append("masters_monitored:").append(mastersMonitored.get()).append("\r\n");
        stats.append("s_down_events:").append(sDownEvents.get()).append("\r\n");
        stats.append("o_down_events:").append(oDownEvents.get()).append("\r\n");
        stats.append("failover_events:").append(failoverEvents.get()).append("\r\n");
        stats.append("failover_success:").append(failoverSuccess.get()).append("\r\n");
        stats.append("failover_failed:").append(failoverFailed.get()).append("\r\n");
        stats.append("ping_sent:").append(pingSent.get()).append("\r\n");
        stats.append("pong_received:").append(pongReceived.get()).append("\r\n");
        stats.append("info_sent:").append(infoSent.get()).append("\r\n");
        stats.append("hello_sent:").append(helloSent.get()).append("\r\n");
        stats.append("commands_received:").append(commandsReceived.get()).append("\r\n");
        stats.append("commands_processed:").append(commandsProcessed.get()).append("\r\n");
        stats.append("uptime_ms:").append(getUptime()).append("\r\n");
        
        return stats.toString();
    }
    
    /**
     * 重置统计信息
     */
    public void reset() {
        mastersMonitored.set(0);
        sDownEvents.set(0);
        oDownEvents.set(0);
        failoverEvents.set(0);
        failoverSuccess.set(0);
        failoverFailed.set(0);
        pingSent.set(0);
        pongReceived.set(0);
        infoSent.set(0);
        helloSent.set(0);
        commandsReceived.set(0);
        commandsProcessed.set(0);
    }
}
