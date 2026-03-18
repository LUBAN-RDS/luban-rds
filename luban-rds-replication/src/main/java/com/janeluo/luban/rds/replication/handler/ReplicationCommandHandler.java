package com.janeluo.luban.rds.replication.handler;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.replication.*;
import com.janeluo.luban.rds.core.handler.CommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 复制命令处理器
 */
public class ReplicationCommandHandler implements CommandHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(ReplicationCommandHandler.class);
    private static final Set<String> SUPPORTED_COMMANDS = new HashSet<>();
    
    static {
        SUPPORTED_COMMANDS.add("SLAVEOF");
        SUPPORTED_COMMANDS.add("REPLICAOF");
        SUPPORTED_COMMANDS.add("PSYNC");
        SUPPORTED_COMMANDS.add("SYNC");
        SUPPORTED_COMMANDS.add("REPLCONF");
        SUPPORTED_COMMANDS.add("WAIT");
    }
    
    private final RdsConfig config;
    private final MasterReplicationManager replicationManager;
    
    public ReplicationCommandHandler(RdsConfig config) {
        this.config = config;
        this.replicationManager = MasterReplicationManager.getInstance();
    }
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        return null;
    }
    
    public String handleWithChannel(ChannelHandlerContext ctx, String[] args) {
        if (args == null || args.length == 0) {
            return "-ERR empty command\r\n";
        }
        
        String command = args[0].toUpperCase();
        
        switch (command) {
            case "SLAVEOF":
            case "REPLICAOF":
                return handleSlaveof(ctx, args);
            case "PSYNC":
                return handlePsync(ctx, args);
            case "SYNC":
                return handleSync(ctx, args);
            case "REPLCONF":
                return handleReplconf(ctx, args);
            case "WAIT":
                return handleWait(ctx, args);
            default:
                return "-ERR unknown command: " + command + "\r\n";
        }
    }
    
    private String handleSlaveof(ChannelHandlerContext ctx, String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for '" + args[0].toLowerCase() + "' command\r\n";
        }
        
        if ("NO".equalsIgnoreCase(args[1]) && "ONE".equalsIgnoreCase(args[2])) {
            return "+OK\r\n";
        }
        
        if (config.isClusterEnabled()) {
            return "-ERR can't set master in cluster mode, use CLUSTER REPLICATE instead\r\n";
        }
        
        return "+OK\r\n";
    }
    
    private String handlePsync(ChannelHandlerContext ctx, String[] args) {
        MasterReplicationManager.PsyncResponse response = replicationManager.handlePsync(ctx.channel(), args);
        
        if (response.getResponse() != null) {
            ctx.channel().writeAndFlush(response.getResponse());
            
            if (response.getBacklogData() != null && response.getBacklogData().length > 0) {
                ctx.channel().writeAndFlush(response.getBacklogData());
            }
        }
        
        return null;
    }
    
    private String handleSync(ChannelHandlerContext ctx, String[] args) {
        String[] psyncArgs = {"PSYNC", "?", "-1"};
        return handlePsync(ctx, psyncArgs);
    }
    
    private String handleReplconf(ChannelHandlerContext ctx, String[] args) {
        return replicationManager.handleReplconf(ctx.channel(), args);
    }
    
    private String handleWait(ChannelHandlerContext ctx, String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'wait' command\r\n";
        }
        
        int syncedReplicas = 0;
        long currentOffset = replicationManager.getBacklog().getMasterReplOffset();
        
        for (SlaveInfo slave : replicationManager.getSlaves()) {
            if (slave.isOnline() && slave.getOffset() >= currentOffset) {
                syncedReplicas++;
            }
        }
        
        return ":" + syncedReplicas + "\r\n";
    }
    
    @Override
    public Set<String> supportedCommands() {
        return SUPPORTED_COMMANDS;
    }
    
    public MasterReplicationManager getReplicationManager() {
        return replicationManager;
    }
}
