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

    /**
     * 运行时复制控制器（由 server 层 {@code ReplicationCoordinator} 注入）。
     * <p>
     * 用于在收到 {@code SLAVEOF host port} 时触发 {@link ReplicationController#startSlave(String)}，
     * 收到 {@code SLAVEOF NO ONE} 时触发 {@link ReplicationController#stopSlave()}。
     * 可能为 null（如单元测试未注入），此时仅切换只读标志，不触发实际复制启停。
     * </p>
     */
    private ReplicationController coordinator;

    public ReplicationCommandHandler(RdsConfig config) {
        this.config = config;
        this.replicationManager = MasterReplicationManager.getInstance();
        this.waitExecutor = new WaitCommandExecutor(replicationManager);
        this.readOnlyModeManager = new ReadOnlyModeManager();
    }

    /**
     * 注入运行时复制控制器。
     * <p>
     * 由 server 层在构造本处理器后立即调用，将 {@code ReplicationCoordinator}
     * （实现 {@link ReplicationController}）注入，使 {@code SLAVEOF} 能真正启停复制。
     * </p>
     *
     * @param coordinator 复制控制器，可为 null（仅切换只读标志）
     */
    public void setReplicationCoordinator(ReplicationController coordinator) {
        this.coordinator = coordinator;
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
            // SLAVEOF NO ONE：停止复制连接，清除从节点只读标志
            if (coordinator != null) {
                try {
                    coordinator.stopSlave();
                } catch (Exception e) {
                    logger.warn("SLAVEOF NO ONE: 停止复制服务异常", e);
                }
            }
            readOnlyModeManager.setSlave(false);
            readOnlyModeManager.setReadOnly(false);
            return "+OK\r\n";
        }

        if (config.isClusterEnabled()) {
            return "-ERR can't set master in cluster mode, use CLUSTER REPLICATE instead\r\n";
        }

        // SLAVEOF host port：校验 host/port 后触发复制启动
        String host = args[1];
        String port = args[2];
        try {
            Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return "-ERR invalid port number: " + port + "\r\n";
        }

        if (coordinator != null) {
            // coordinator.startSlave 内部支持 "host:port" 与 "host port" 两种格式（normalizeAddress），
            // 这里传 "host port"（空格分隔），由 coordinator 统一规范化为 "host:port"。
            String masterAddress = host + " " + port;
            try {
                coordinator.startSlave(masterAddress);
            } catch (Exception e) {
                logger.error("SLAVEOF: 启动复制服务失败 master={}", masterAddress, e);
            }
        }

        // 切换为从节点只读模式
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
