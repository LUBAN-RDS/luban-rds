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
 * 
 * 处理复制相关的命令：
 * - SLAVEOF/REPLICAOF: 设置主节点
 * - PSYNC: 部分同步
 * - SYNC: 全量同步
 * - REPLCONF: 复制配置
 * - WAIT: 等待从节点同步
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
    private final WaitCommandExecutor waitExecutor;
    private final ReadOnlyModeManager readOnlyModeManager;
    
    public ReplicationCommandHandler(RdsConfig config) {
        this.config = config;
        this.replicationManager = MasterReplicationManager.getInstance();
        this.waitExecutor = new WaitCommandExecutor(replicationManager);
        this.readOnlyModeManager = new ReadOnlyModeManager();
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
            // 取消从节点状态
            readOnlyModeManager.setSlave(false);
            readOnlyModeManager.setReadOnly(false);
            return "+OK\r\n";
        }
        
        if (config.isClusterEnabled()) {
            return "-ERR can't set master in cluster mode, use CLUSTER REPLICATE instead\r\n";
        }
        
        // 设置为从节点
        readOnlyModeManager.setSlave(true);
        
        return "+OK\r\n";
    }
    
    private String handlePsync(ChannelHandlerContext ctx, String[] args) {
        MasterReplicationManager.PsyncResponse response = replicationManager.handlePsync(ctx.channel(), args);
        
        if (response.getResponse() != null) {
            ctx.channel().writeAndFlush(response.getResponse());
            
            if (response.getBacklogData() != null && response.getBacklogData().length > 0) {
                ctx.channel().writeAndFlush(response.getBacklogData());
            }
            
            // 如果需要 RDB 传输，启动全量同步
            if (response.isNeedRdb()) {
                replicationManager.performFullSync(ctx.channel());
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
        // 检查参数数量
        if (args.length < 3) {
            return "-ERR wrong number of arguments for '" + args[0].toLowerCase() + "' command\r\n";
        }
        
        // 解析参数
        WaitCommandExecutor.WaitParams params = WaitCommandExecutor.parseArgs(args);
        if (params == null) {
            return "-ERR syntax error\r\n";
        }
        
        // 执行 WAIT 命令
        int syncedCount = waitExecutor.execute(params.getNumSlaves(), params.getTimeout());
        
        return ":" + syncedCount + "\r\n";
    }
    
    @Override
    public Set<String> supportedCommands() {
        return SUPPORTED_COMMANDS;
    }
    
    public MasterReplicationManager getReplicationManager() {
        return replicationManager;
    }
    
    public ReadOnlyModeManager getReadOnlyModeManager() {
        return readOnlyModeManager;
    }
    
    /**
     * 检查命令是否应该被拦截（只读模式）
     * 
     * @param command 命令名称
     * @return 错误消息，如果不需要拦截则返回 null
     */
    public String checkReadOnlyIntercept(String command) {
        return readOnlyModeManager.interceptWriteCommand(command);
    }
    
    /**
     * 处理 CONFIG SET 命令（只读相关）
     */
    public String handleConfigSet(String[] args) {
        if (args.length < 3) {
            return null;
        }
        return readOnlyModeManager.handleConfigSet(args[1], args[2]);
    }
    
    /**
     * 处理 CONFIG GET 命令（只读相关）
     */
    public String handleConfigGet(String parameter) {
        return readOnlyModeManager.handleConfigGet(parameter);
    }
}
