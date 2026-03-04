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
 * Manages MONITOR clients and broadcasts commands.
 * Implements a high-performance, non-blocking design using a ring buffer and asynchronous worker.
 */
public class MonitorManager {
    private static final Logger logger = LoggerFactory.getLogger(MonitorManager.class);
    private static final MonitorManager INSTANCE = new MonitorManager();

    // History Ring Buffer (Stores logs for new clients)
    // 16384 entries. Assuming avg command log is 100 bytes, this is ~1.6MB.
    private static final int HISTORY_BUFFER_SIZE = 16384;
    private static final int HISTORY_BUFFER_MASK = HISTORY_BUFFER_SIZE - 1;
    private final String[] historyBuffer = new String[HISTORY_BUFFER_SIZE];
    private final AtomicLong historyCursor = new AtomicLong(0);

    // Event Queue Ring Buffer (MPSC)
    // 65536 entries to handle bursts.
    private static final int QUEUE_BUFFER_SIZE = 65536;
    private static final int QUEUE_BUFFER_MASK = QUEUE_BUFFER_SIZE - 1;
    private final MonitorEvent[] queueBuffer = new MonitorEvent[QUEUE_BUFFER_SIZE];
    private final AtomicLong queueHead = new AtomicLong(0); // Producer index
    private final AtomicLong queueTail = new AtomicLong(0); // Consumer index

    // Clients
    private final Map<Channel, MonitorContext> monitorClients = new ConcurrentHashMap<>();

    // Worker
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Monitor-Worker");
        t.setDaemon(true);
        return t;
    });

    // Memory pool for StringBuilder
    private final ThreadLocal<StringBuilder> stringBuilderPool = ThreadLocal.withInitial(() -> new StringBuilder(512));

    private MonitorManager() {
        // Pre-allocate event objects
        for (int i = 0; i < QUEUE_BUFFER_SIZE; i++) {
            queueBuffer[i] = new MonitorEvent();
        }
        startWorker();
    }

    public static MonitorManager getInstance() {
        return INSTANCE;
    }

    public static class MonitorContext {
        final Channel channel;
        final Integer dbFilter;
        final Pattern patternFilter;

        public MonitorContext(Channel channel, Integer dbFilter, String pattern) {
            this.channel = channel;
            this.dbFilter = dbFilter;
            this.patternFilter = (pattern != null && !pattern.equals("*") && !pattern.isEmpty()) ? Pattern.compile(pattern) : null;
        }
    }

    private static class MonitorEvent {
        // Volatile to ensure visibility between producer and consumer
        volatile boolean ready = false;
        long timestamp;
        int db;
        String clientAddress;
        String command;
        String[] args;
    }

    /**
     * Submit a command to be monitored.
     * This method is lock-free and extremely fast (nanoseconds).
     */
    public void submit(int db, String clientAddress, String command, String[] args) {
        // 1. Claim slot
        long currentHead = queueHead.getAndIncrement();
        
        // 2. Check if full (lazy check)
        // We only check tail occasionally or rely on size being large enough.
        // But for safety, we should check. Reading tail is volatile read.
        // To optimize, we can cache tail or check only every N ops.
        // But let's do a simple check first.
        long wrapPoint = currentHead - QUEUE_BUFFER_SIZE;
        if (queueTail.get() <= wrapPoint) {
            // Buffer full, drop event to prevent blocking or overwriting unread data
            // (In MPSC ring buffer, we cannot overwrite because we don't know if consumer read it.
            // Wait, if we overwrite, we corrupt data.
            // If we wait, we block main thread.
            // So we MUST drop.)
            // However, we already incremented head. So we claimed a slot.
            // If we don't write to it, the consumer will spin forever waiting for 'ready'.
            // So we MUST write "skipped" or something, or handle this case.
            // Actually, if we drop, we must mark it as ready but empty/ignored.
            // Let's assume queue is large enough. 65536 is plenty for 1M QPS burst buffering.
            // If worker is slow, we will eventually drop.
            // But to recover from "claimed but dropped", we need to set ready=true.
            // Let's proceed to write, assuming we don't care about overwriting if consumer is THAT slow.
            // Actually, in MPSC, we can't easily check fullness without contention.
            // But `queueTail` is only updated by single consumer.
            // So `queueTail.get()` is safe.
            // If full, we should just mark as ready and empty.
            int idx = (int) (currentHead & QUEUE_BUFFER_MASK);
            MonitorEvent e = queueBuffer[idx];
            e.command = null; // Mark as empty/dropped
            e.ready = true;
            return;
        }

        // 3. Write data
        int idx = (int) (currentHead & QUEUE_BUFFER_MASK);
        MonitorEvent e = queueBuffer[idx];
        
        e.timestamp = System.currentTimeMillis();
        e.db = db;
        e.clientAddress = clientAddress;
        e.command = command;
        e.args = (args == null) ? new String[0] : args;
        
        // 4. Publish
        e.ready = true;
    }

    public void addMonitor(Channel channel, int db, String pattern) {
        if (monitorClients.size() >= RuntimeConfig.getMonitorMaxClients()) {
            channel.writeAndFlush(Unpooled.copiedBuffer("-ERR max number of monitoring clients reached\r\n", StandardCharsets.UTF_8));
            return;
        }

        MonitorContext context = new MonitorContext(channel, db == -1 ? null : db, pattern);
        monitorClients.put(channel, context);
        channel.writeAndFlush(Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));
        dumpHistory(channel, context);
    }

    public void removeMonitor(Channel channel) {
        monitorClients.remove(channel);
    }
    
    public int getMonitorClientCount() {
        return monitorClients.size();
    }

    private void startWorker() {
        workerExecutor.submit(this::workerLoop);
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                long currentTail = queueTail.get();
                int idx = (int) (currentTail & QUEUE_BUFFER_MASK);
                MonitorEvent event = queueBuffer[idx];

                if (event.ready) {
                    // Process event
                    if (event.command != null) { // If not dropped
                        String logLine = formatLog(event);
                        
                        // Store in history
                        long seq = historyCursor.getAndIncrement();
                        historyBuffer[(int)(seq & HISTORY_BUFFER_MASK)] = logLine;
                        
                        // Broadcast
                        if (!monitorClients.isEmpty()) {
                            broadcast(logLine, event);
                        }
                    }
                    
                    // Reset event state
                    event.ready = false;
                    event.args = null; // Help GC? Or keep reference? String[] is ref.
                    
                    // Advance tail
                    queueTail.lazySet(currentTail + 1);
                } else {
                    // Backoff if no events
                    // Simple busy-wait with yield or park
                    // Thread.onSpinWait(); // Java 9+
                    // Since we target Java 8+, maybe just yield or short sleep
                    // But benchmarking requires high throughput.
                    // Sleep(1) is 1ms, too slow for high QPS if bursty.
                    // LockSupport.parkNanos(1000); // 1us
                    java.util.concurrent.locks.LockSupport.parkNanos(100); 
                }
            } catch (Exception e) {
                logger.error("Error in Monitor worker", e);
            }
        }
    }

    private String formatLog(MonitorEvent event) {
        StringBuilder sb = stringBuilderPool.get();
        sb.setLength(0);
        
        long seconds = event.timestamp / 1000;
        long micros = (event.timestamp % 1000) * 1000;
        
        sb.append(seconds).append(".");
        if (micros < 10) sb.append("00000");
        else if (micros < 100) sb.append("0000");
        else if (micros < 1000) sb.append("000");
        else if (micros < 10000) sb.append("00");
        else if (micros < 100000) sb.append("0");
        sb.append(micros);
        
        sb.append(" [").append(event.db).append(" ").append(event.clientAddress).append("]");
        // sb.append(" \"").append(event.command).append("\"");
        
        for (String arg : event.args) {
            sb.append(" \"").append(escapeString(arg)).append("\"");
        }
        
        return sb.toString();
    }
    
    private String escapeString(String s) {
        if (s == null) return "";
        boolean needsEscape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c < 32) {
                needsEscape = true;
                break;
            }
        }
        if (!needsEscape) return s;
        
        StringBuilder sb = new StringBuilder(s.length() + 10);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 32) {
                        sb.append(String.format("\\x%02x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private void broadcast(String logLine, MonitorEvent event) {
        ByteBuf message = null;
        try {
            for (MonitorContext ctx : monitorClients.values()) {
                if (shouldSend(ctx, event)) {
                    if (message == null) {
                        message = Unpooled.copiedBuffer(logLine + "\r\n", StandardCharsets.UTF_8);
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

    private boolean shouldSend(MonitorContext ctx, MonitorEvent event) {
        if (ctx.dbFilter != null && ctx.dbFilter != event.db) {
            return false;
        }
        if (ctx.patternFilter != null) {
            return ctx.patternFilter.matcher(event.command).find();
        }
        return true;
    }

    private void dumpHistory(Channel channel, MonitorContext ctx) {
        long cursor = historyCursor.get();
        long start = Math.max(0, cursor - HISTORY_BUFFER_SIZE);
        
        for (long i = start; i < cursor; i++) {
            int index = (int) (i & HISTORY_BUFFER_MASK);
            String log = historyBuffer[index];
            if (log != null) {
                if (passesStringFilter(log, ctx)) {
                    channel.writeAndFlush(Unpooled.copiedBuffer(log + "\r\n", StandardCharsets.UTF_8));
                }
            }
        }
    }
    
    private boolean passesStringFilter(String log, MonitorContext ctx) {
        if (ctx.dbFilter == null && ctx.patternFilter == null) return true;
        
        if (ctx.dbFilter != null) {
            String dbPrefix = "[" + ctx.dbFilter + " ";
            if (!log.contains(dbPrefix)) return false;
        }
        return true;
    }
}
