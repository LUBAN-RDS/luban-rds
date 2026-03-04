package com.janeluo.luban.rds.core.slowlog;

import java.util.List;

/**
 * Slow log entry structure.
 */
public class SlowLogEntry {
    private final long id;
    private final long timestamp; // Unix timestamp in seconds
    private final long duration; // Execution time in microseconds
    private final List<String> args;
    private final String clientIp;
    private final String clientName;

    public SlowLogEntry(long id, long timestamp, long duration, List<String> args, String clientIp, String clientName) {
        this.id = id;
        this.timestamp = timestamp;
        this.duration = duration;
        this.args = args;
        this.clientIp = clientIp;
        this.clientName = clientName;
    }

    public long getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getDuration() {
        return duration;
    }

    public List<String> getArgs() {
        return args;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getClientName() {
        return clientName;
    }
}
