package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * MONITOR客户端管理器
 * 
 * <p>管理MONITOR客户端并广播命令，采用高性能非阻塞设计，
 * 使用环形缓冲区和异步工作线程实现。
 * 
 * <p>特性：
 * <ul>
 *   <li>无锁环形缓冲区实现高吞吐量</li>
 *   <li>异步工作线程处理命令广播</li>
 *   <li>支持历史命令回放</li>
 *   <li>支持数据库和模式过滤</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class MonitorManager {
    
    private static final Logger logger = LoggerFactory.getLogger(MonitorManager.class);
    
    /** 单例实例 */
    private static final MonitorManager INSTANCE = new MonitorManager();

    // ==================== 历史命令缓冲区 ====================
    
    /** 历史缓冲区大小（16384条记录，约1.6MB） */
    private static final int HISTORY_BUFFER_SIZE = 16384;
    
    /** 历史缓冲区掩码，用于快速取模 */
    private static final int HISTORY_BUFFER_MASK = HISTORY_BUFFER_SIZE - 1;
    
    /** 历史命令环形缓冲区 */
    private final String[] historyBuffer = new String[HISTORY_BUFFER_SIZE];
    
    /** 历史命令写入位置 */
    private final AtomicLong historyCursor = new AtomicLong(0);

    // ==================== 事件队列（MPSC 环形缓冲区） ====================
    
    /** 事件队列大小（65536条记录，用于处理突发流量） */
    private static final int QUEUE_BUFFER_SIZE = 65536;
    
    /** 事件队列掩码，用于快速取模 */
    private static final int QUEUE_BUFFER_MASK = QUEUE_BUFFER_SIZE - 1;
    
    /** 事件队列缓冲区 */
    private final MonitorEvent[] queueBuffer = new MonitorEvent[QUEUE_BUFFER_SIZE];
    
    /** 队列头指针（生产者索引） */
    private final AtomicLong queueHead = new AtomicLong(0);
    
    /** 队列尾指针（消费者索引） */
    private final AtomicLong queueTail = new AtomicLong(0);

    // ==================== 监控客户端管理 ====================
    
    /** 监控客户端映射表 */
    private final Map<Channel, MonitorContext> monitorClients = new ConcurrentHashMap<>();

    // ==================== 工作线程 ====================
    
    /** 工作线程执行器 */
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Monitor-Worker");
        t.setDaemon(true);
        return t;
    });

    // ==================== 内存优化 ====================
    
    /** StringBuilder 线程本地缓存，避免频繁创建对象 */
    private final ThreadLocal<StringBuilder> stringBuilderPool = ThreadLocal.withInitial(() -> new StringBuilder(512));

    /**
     * 私有构造函数
     * 
     * <p>预分配事件对象并启动工作线程。
     */
    private MonitorManager() {
        // 预分配事件对象
        for (int i = 0; i < QUEUE_BUFFER_SIZE; i++) {
            queueBuffer[i] = new MonitorEvent();
        }
        startWorker();
    }

    /**
     * 获取单例实例
     * 
     * @return MonitorManager 单例
     */
    public static MonitorManager getInstance() {
        return INSTANCE;
    }

    /**
     * 监控客户端上下文
     * 
     * <p>存储每个 MONITOR 客户端的过滤条件。
     */
    public static class MonitorContext {
        /** 客户端连接通道 */
        final Channel channel;
        
        /** 数据库过滤（null 表示监控所有数据库） */
        final Integer dbFilter;
        
        /** 命令模式过滤（null 表示监控所有命令） */
        final Pattern patternFilter;

        /**
         * 构造监控上下文
         * 
         * @param channel 客户端通道
         * @param dbFilter 数据库过滤（-1 表示不过滤）
         * @param pattern 命令模式（支持通配符，"*" 或空表示不过滤）
         */
        public MonitorContext(Channel channel, Integer dbFilter, String pattern) {
            this.channel = channel;
            this.dbFilter = dbFilter;
            this.patternFilter = (pattern != null && !pattern.equals("*") && !pattern.isEmpty()) 
                ? Pattern.compile(pattern) : null;
        }
    }

    /**
     * 监控事件
     * 
     * <p>用于在环形缓冲区中传递监控事件数据。
     * 采用可重用对象设计，避免频繁创建对象。
     */
    private static class MonitorEvent {
        /**
         * 事件就绪标志
         * volatile 确保生产者和消费者之间的内存可见性
         */
        volatile boolean ready = false;
        
        /** 时间戳（毫秒） */
        long timestamp;
        
        /** 数据库编号 */
        int db;
        
        /** 客户端地址 */
        String clientAddress;
        
        /** 命令名称 */
        String command;
        
        /** 命令参数（包含命令名） */
        String[] args;
    }

    /**
     * 提交命令到监控队列
     * 
     * <p>此方法是无锁的，执行速度极快（纳秒级），
     * 不会阻塞命令处理线程。
     * 
     * <p>当队列满时，会丢弃事件以保证性能。
     * 
     * @param db 数据库编号
     * @param clientAddress 客户端地址（格式：ip:port）
     * @param command 命令名称
     * @param args 命令参数数组（包含命令名作为第一个元素）
     */
    public void submit(int db, String clientAddress, String command, String[] args) {
        // 1. 原子获取队列槽位
        long currentHead = queueHead.getAndIncrement();
        
        // 2. 检查队列是否已满
        // 使用 wrap point 检测：如果 head - size > tail，说明队列已满
        long wrapPoint = currentHead - QUEUE_BUFFER_SIZE;
        if (queueTail.get() <= wrapPoint) {
            // 队列已满，丢弃事件以避免阻塞主线程
            // 必须标记为 ready=true 让消费者继续处理
            int idx = (int) (currentHead & QUEUE_BUFFER_MASK);
            MonitorEvent e = queueBuffer[idx];
            e.command = null; // 标记为空/已丢弃
            e.ready = true;
            return;
        }

        // 3. 写入事件数据
        int idx = (int) (currentHead & QUEUE_BUFFER_MASK);
        MonitorEvent e = queueBuffer[idx];
        
        e.timestamp = System.currentTimeMillis();
        e.db = db;
        e.clientAddress = clientAddress;
        e.command = command;
        e.args = (args == null) ? new String[0] : args;
        
        // 4. 发布事件（设置 ready 标志）
        e.ready = true;
    }

    /**
     * 添加监控客户端
     * 
     * <p>支持扩展语法：MONITOR [DB &lt;dbid&gt;] [MATCH &lt;pattern&gt;]
     * 
     * <p>添加成功后，客户端会收到历史命令回放。
     * 
     * @param channel 客户端通道
     * @param db 数据库过滤（-1 表示监控所有数据库）
     * @param pattern 命令模式过滤（支持通配符）
     */
    public void addMonitor(Channel channel, int db, String pattern) {
        if (monitorClients.size() >= RuntimeConfig.getMonitorMaxClients()) {
            logger.warn("Monitor客户端被拒绝: 已达最大客户端数, 当前={}", monitorClients.size());
            channel.writeAndFlush(Unpooled.copiedBuffer("-ERR max number of monitoring clients reached\r\n", StandardCharsets.UTF_8));
            return;
        }

        MonitorContext context = new MonitorContext(channel, db == -1 ? null : db, pattern);
        monitorClients.put(channel, context);
        channel.writeAndFlush(Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));
        logger.debug("Monitor客户端已添加: channel={}, db={}, pattern={}, 总数={}", 
            channel.remoteAddress(), db, pattern, monitorClients.size());
        dumpHistory(channel, context);
    }

    /**
     * 移除监控客户端
     * 
     * @param channel 客户端通道
     */
    public void removeMonitor(Channel channel) {
        MonitorContext removed = monitorClients.remove(channel);
        if (removed != null) {
            logger.debug("Monitor客户端已移除: channel={}, 剩余={}", 
                channel.remoteAddress(), monitorClients.size());
        }
    }
    
    /**
     * 获取当前监控客户端数量
     * 
     * @return 客户端数量
     */
    public int getMonitorClientCount() {
        return monitorClients.size();
    }

    /**
     * 启动工作线程
     */
    private void startWorker() {
        workerExecutor.submit(this::workerLoop);
    }

    /**
     * 工作线程主循环
     * 
     * <p>从事件队列中消费事件，格式化后广播给所有监控客户端。
     * 使用无锁环形缓冲区实现高吞吐量。
     */
    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                long currentTail = queueTail.get();
                int idx = (int) (currentTail & QUEUE_BUFFER_MASK);
                MonitorEvent event = queueBuffer[idx];

                if (event.ready) {
                    // 处理事件
                    if (event.command != null) {
                        String logLine = formatLog(event);
                        
                        // 存储到历史缓冲区
                        long seq = historyCursor.getAndIncrement();
                        historyBuffer[(int)(seq & HISTORY_BUFFER_MASK)] = logLine;
                        
                        // 广播给监控客户端
                        if (!monitorClients.isEmpty()) {
                            broadcast(logLine, event);
                        }
                    }
                    
                    // 重置事件状态
                    event.ready = false;
                    event.args = null;
                    
                    // 推进队列尾指针
                    queueTail.lazySet(currentTail + 1);
                } else {
                    // 无事件时短暂休眠，避免空转
                    java.util.concurrent.locks.LockSupport.parkNanos(100); 
                }
            } catch (Exception e) {
                logger.error("Error in Monitor worker", e);
            }
        }
    }

    /**
     * 格式化监控日志
     * 
     * <p>输出格式符合 Redis MONITOR 协议：
     * <pre>
     * "timestamp" [db client_addr] "command" "arg1" "arg2" ...
     * </pre>
     * 
     * <p>示例：
     * <pre>
     * "1682345678.123456" [0 127.0.0.1:1234] "SET" "key" "value"
     * </pre>
     * 
     * <p>注意：参数值直接用双引号包围，内部不需要额外转义。
     * 这是 Redis MONITOR 的标准格式。
     * 
     * @param event 监控事件
     * @return 格式化后的日志字符串
     */
    private String formatLog(MonitorEvent event) {
        StringBuilder sb = stringBuilderPool.get();
        sb.setLength(0);
        
        // 时间戳格式："秒.微秒"（用引号包围，6位微秒）
        long seconds = event.timestamp / 1000;
        long micros = (event.timestamp % 1000) * 1000;
        
        sb.append("\"").append(seconds).append(".");
        // 补零到6位微秒
        if (micros < 10) sb.append("00000");
        else if (micros < 100) sb.append("0000");
        else if (micros < 1000) sb.append("000");
        else if (micros < 10000) sb.append("00");
        else if (micros < 100000) sb.append("0");
        sb.append(micros).append("\"");
        
        // 数据库和客户端地址
        sb.append(" [").append(event.db).append(" ").append(event.clientAddress).append("]");
        
        // 命令参数（args[0] 是命令名）
        // Redis MONITOR 格式：每个参数用双引号包围，直接输出原始值
        for (String arg : event.args) {
            sb.append(" \"").append(arg != null ? arg : "").append("\"");
        }
        
        return sb.toString();
    }

    /**
     * 广播日志给所有监控客户端
     * 
     * <p>使用 RESP Bulk String 格式发送：$length\r\ncontent\r\n
     * 
     * @param logLine 格式化后的日志行
     * @param event 原始事件（用于过滤判断）
     */
    private void broadcast(String logLine, MonitorEvent event) {
        ByteBuf message = null;
        try {
            for (MonitorContext ctx : monitorClients.values()) {
                if (shouldSend(ctx, event)) {
                    if (message == null) {
                        // RESP Bulk String 格式: $length\r\ncontent\r\n
                        // 注意：length 必须是字节长度，不是字符长度
                        byte[] contentBytes = logLine.getBytes(StandardCharsets.UTF_8);
                        byte[] headerBytes = ("$" + contentBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8);
                        byte[] footerBytes = "\r\n".getBytes(StandardCharsets.UTF_8);
                        
                        // 合并为一个字节数组
                        byte[] fullMessage = new byte[headerBytes.length + contentBytes.length + footerBytes.length];
                        System.arraycopy(headerBytes, 0, fullMessage, 0, headerBytes.length);
                        System.arraycopy(contentBytes, 0, fullMessage, headerBytes.length, contentBytes.length);
                        System.arraycopy(footerBytes, 0, fullMessage, headerBytes.length + contentBytes.length, footerBytes.length);
                        
                        message = Unpooled.wrappedBuffer(fullMessage);
                    }
                    ctx.channel.writeAndFlush(message.retainedDuplicate());
                }
            }
        } finally {
            if (message != null) {
                message.release();
            }
        }
    }

    /**
     * 判断是否应该发送事件给指定客户端
     * 
     * @param ctx 客户端上下文
     * @param event 监控事件
     * @return true 表示应该发送
     */
    private boolean shouldSend(MonitorContext ctx, MonitorEvent event) {
        // 检查数据库过滤
        if (ctx.dbFilter != null && ctx.dbFilter != event.db) {
            return false;
        }
        // 检查命令模式过滤
        if (ctx.patternFilter != null) {
            return ctx.patternFilter.matcher(event.command).find();
        }
        return true;
    }

    /**
     * 向新客户端回放历史命令
     * 
     * <p>使用 RESP Bulk String 格式发送。
     * 
     * @param channel 客户端通道
     * @param ctx 客户端上下文
     */
    private void dumpHistory(Channel channel, MonitorContext ctx) {
        long cursor = historyCursor.get();
        long start = Math.max(0, cursor - HISTORY_BUFFER_SIZE);
        
        for (long i = start; i < cursor; i++) {
            int index = (int) (i & HISTORY_BUFFER_MASK);
            String log = historyBuffer[index];
            if (log != null) {
                if (passesStringFilter(log, ctx)) {
                    // RESP Bulk String 格式: $length\r\ncontent\r\n
                    // 注意：length 必须是字节长度，不是字符长度
                    byte[] contentBytes = log.getBytes(StandardCharsets.UTF_8);
                    byte[] headerBytes = ("$" + contentBytes.length + "\r\n").getBytes(StandardCharsets.UTF_8);
                    byte[] footerBytes = "\r\n".getBytes(StandardCharsets.UTF_8);
                    
                    byte[] fullMessage = new byte[headerBytes.length + contentBytes.length + footerBytes.length];
                    System.arraycopy(headerBytes, 0, fullMessage, 0, headerBytes.length);
                    System.arraycopy(contentBytes, 0, fullMessage, headerBytes.length, contentBytes.length);
                    System.arraycopy(footerBytes, 0, fullMessage, headerBytes.length + contentBytes.length, footerBytes.length);
                    
                    channel.writeAndFlush(Unpooled.wrappedBuffer(fullMessage));
                }
            }
        }
    }
    
    /**
     * 检查日志是否通过字符串过滤
     * 
     * <p>用于历史回放时的过滤判断。
     * 
     * @param log 日志字符串
     * @param ctx 客户端上下文
     * @return true 表示通过过滤
     */
    private boolean passesStringFilter(String log, MonitorContext ctx) {
        if (ctx.dbFilter == null && ctx.patternFilter == null) return true;
        
        if (ctx.dbFilter != null) {
            String dbPrefix = "[" + ctx.dbFilter + " ";
            if (!log.contains(dbPrefix)) return false;
        }
        return true;
    }
}
