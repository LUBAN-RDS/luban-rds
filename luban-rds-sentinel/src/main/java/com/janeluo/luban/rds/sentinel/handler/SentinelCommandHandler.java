package com.janeluo.luban.rds.sentinel.handler;

import com.janeluo.luban.rds.sentinel.core.MasterState;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SentinelInstance;
import com.janeluo.luban.rds.sentinel.core.SlaveState;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 哨兵命令处理器
 * 处理 SENTINEL 相关命令
 */
public class SentinelCommandHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(SentinelCommandHandler.class);
    
    private final Sentinel sentinel;
    
    public SentinelCommandHandler(Sentinel sentinel) {
        this.sentinel = sentinel;
    }
    
    /**
     * 处理命令
     */
    public String handleCommand(ChannelHandlerContext ctx, String[] args) {
        if (args == null || args.length == 0) {
            return "-ERR empty command\r\n";
        }
        
        String command = args[0].toUpperCase();
        
        if (!command.equals("SENTINEL") && !command.equals("PING") && 
            !command.equals("INFO") && !command.equals("SUBSCRIBE") && 
            !command.equals("PSUBSCRIBE") && !command.equals("PUBLISH")) {
            return "-ERR unknown command: " + command + "\r\n";
        }
        
        if (command.equals("PING")) {
            return handlePing();
        }
        
        if (command.equals("INFO")) {
            return handleInfo(args);
        }
        
        if (command.equals("SENTINEL")) {
            return handleSentinel(ctx, args);
        }
        
        return "-ERR unknown command: " + command + "\r\n";
    }
    
    /**
     * 处理 PING 命令
     */
    private String handlePing() {
        return "+PONG\r\n";
    }
    
    /**
     * 处理 INFO 命令
     */
    private String handleInfo(String[] args) {
        StringBuilder info = new StringBuilder();
        
        info.append("# Sentinel\r\n");
        info.append("sentinel_masters:").append(sentinel.getMasters().size()).append("\r\n");
        info.append("sentinel_tilt:0\r\n");
        info.append("sentinel_running_scripts:0\r\n");
        info.append("sentinel_scripts_queue_length:0\r\n");
        info.append("sentinel_simulate_failure_flags:0\r\n");
        info.append("master0:name=mymaster,status=ok,address=127.0.0.1:6379,slaves=0,sentinels=1\r\n");
        
        return "$" + info.length() + "\r\n" + info.toString() + "\r\n";
    }
    
    /**
     * 处理 SENTINEL 命令
     */
    private String handleSentinel(ChannelHandlerContext ctx, String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'sentinel' command\r\n";
        }
        
        String subCommand = args[1].toUpperCase();
        
        switch (subCommand) {
            case "MONITOR":
                return handleMonitor(args);
            case "REMOVE":
                return handleRemove(args);
            case "MASTER":
                return handleMaster(args);
            case "SLAVES":
                return handleSlaves(args);
            case "SENTINELS":
                return handleSentinels(args);
            case "GET-MASTER-ADDR-BY-NAME":
                return handleGetMasterAddrByName(args);
            case "FAILOVER":
                return handleFailover(args);
            case "CKQUORUM":
                return handleCkquorum(args);
            case "SET":
                return handleSet(args);
            case "INFO":
                return handleSentinelInfo(args);
            case "MASTERS":
                return handleMasters(args);
            case "RESET":
                return handleReset(args);
            case "IS-MASTER-DOWN-BY-ADDR":
                return handleIsMasterDownByAddr(args);
            default:
                return "-ERR unknown sentinel subcommand: " + subCommand + "\r\n";
        }
    }
    
    /**
     * SENTINEL MONITOR name host port quorum
     */
    private String handleMonitor(String[] args) {
        if (args.length < 6) {
            return "-ERR wrong number of arguments for 'sentinel monitor' command\r\n";
        }
        
        String name = args[2];
        String host = args[3];
        int port;
        int quorum;
        
        try {
            port = Integer.parseInt(args[4]);
            quorum = Integer.parseInt(args[5]);
        } catch (NumberFormatException e) {
            return "-ERR invalid port or quorum number\r\n";
        }
        
        if (quorum < 1) {
            return "-ERR quorum must be at least 1\r\n";
        }
        
        sentinel.monitorMaster(name, host, port, quorum);
        
        logger.info("Sentinel {} started monitoring master {} at {}:{} with quorum {}", 
                   sentinel.getSentinelId(), name, host, port, quorum);
        
        return "+OK\r\n";
    }
    
    /**
     * SENTINEL REMOVE name
     */
    private String handleRemove(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sentinel remove' command\r\n";
        }
        
        String name = args[2];
        
        sentinel.removeMaster(name);
        
        logger.info("Sentinel {} stopped monitoring master {}", sentinel.getSentinelId(), name);
        
        return "+OK\r\n";
    }
    
    /**
     * SENTINEL MASTER name
     */
    private String handleMaster(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sentinel master' command\r\n";
        }
        
        String name = args[2];
        MasterState master = sentinel.getMasterState(name);
        
        if (master == null) {
            return "-ERR No such master with that name\r\n";
        }
        
        String stats = sentinel.getHealthChecker().getMasterStats(name);
        return "$" + stats.length() + "\r\n" + stats + "\r\n";
    }
    
    /**
     * SENTINEL SLAVES name
     */
    private String handleSlaves(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sentinel slaves' command\r\n";
        }
        
        String name = args[2];
        MasterState master = sentinel.getMasterState(name);
        
        if (master == null) {
            return "-ERR No such master with that name\r\n";
        }
        
        List<String> slaveInfos = new ArrayList<>();
        
        for (SlaveState slave : master.getSlaves().values()) {
            slaveInfos.add(formatSlaveInfo(slave));
        }
        
        return formatArrayResponse(slaveInfos);
    }
    
    /**
     * SENTINEL SENTINELS name
     */
    private String handleSentinels(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sentinel sentinels' command\r\n";
        }
        
        String name = args[2];
        MasterState master = sentinel.getMasterState(name);
        
        if (master == null) {
            return "-ERR No such master with that name\r\n";
        }
        
        List<String> sentinelInfos = new ArrayList<>();
        
        for (SentinelInstance si : master.getSentinels().values()) {
            sentinelInfos.add(formatSentinelInfo(si));
        }
        
        return formatArrayResponse(sentinelInfos);
    }
    
    /**
     * SENTINEL GET-MASTER-ADDR-BY-NAME name
     */
    private String handleGetMasterAddrByName(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sentinel get-master-addr-by-name' command\r\n";
        }
        
        String name = args[2];
        String[] addr = sentinel.getMasterAddrByName(name);
        
        if (addr == null) {
            return "$-1\r\n";
        }
        
        return "*" + addr.length + "\r\n" +
               "$" + addr[0].length() + "\r\n" + addr[0] + "\r\n" +
               "$" + addr[1].length() + "\r\n" + addr[1] + "\r\n";
    }
    
    /**
     * SENTINEL FAILOVER name
     */
    private String handleFailover(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sentinel failover' command\r\n";
        }
        
        String name = args[2];
        MasterState master = sentinel.getMasterState(name);
        
        if (master == null) {
            return "-ERR No such master with that name\r\n";
        }
        
        sentinel.startFailover(name);
        
        return "+OK\r\n";
    }
    
    /**
     * SENTINEL CKQUORUM name
     */
    private String handleCkquorum(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sentinel ckquorum' command\r\n";
        }
        
        String name = args[2];
        MasterState master = sentinel.getMasterState(name);
        
        if (master == null) {
            return "-ERR No such master with that name\r\n";
        }
        
        int totalSentinels = master.getSentinels().size() + 1;
        int quorum = master.getQuorum();
        
        if (totalSentinels < quorum) {
            return "-ERR Not enough sentinels available. " +
                   "Available: " + totalSentinels + ", Required: " + quorum + "\r\n";
        }
        
        return "+OK " + totalSentinels + " sentinels available, quorum: " + quorum + "\r\n";
    }
    
    /**
     * SENTINEL SET name option value
     */
    private String handleSet(String[] args) {
        if (args.length < 5) {
            return "-ERR wrong number of arguments for 'sentinel set' command\r\n";
        }
        
        String name = args[2];
        String option = args[3].toLowerCase();
        String value = args[4];
        
        MasterState master = sentinel.getMasterState(name);
        if (master == null) {
            return "-ERR No such master with that name\r\n";
        }
        
        try {
            switch (option) {
                case "quorum":
                    master.setQuorum(Integer.parseInt(value));
                    break;
                case "down-after-milliseconds":
                    master.setDownAfterMilliseconds(Long.parseLong(value));
                    break;
                case "failover-timeout":
                    master.setFailoverTimeout(Long.parseLong(value));
                    break;
                case "parallel-syncs":
                    master.setParallelSyncs(Integer.parseInt(value));
                    break;
                default:
                    return "-ERR unknown option: " + option + "\r\n";
            }
            
            return "+OK\r\n";
        } catch (NumberFormatException e) {
            return "-ERR invalid value for option: " + option + "\r\n";
        }
    }
    
    /**
     * SENTINEL INFO
     */
    private String handleSentinelInfo(String[] args) {
        StringBuilder info = new StringBuilder();
        
        info.append("# Sentinel\r\n");
        info.append("sentinel_masters:").append(sentinel.getMasters().size()).append("\r\n");
        info.append("sentinel_tilt:0\r\n");
        info.append("sentinel_running_scripts:0\r\n");
        info.append("sentinel_scripts_queue_length:0\r\n");
        info.append("sentinel_simulate_failure_flags:0\r\n");
        
        int index = 0;
        for (MasterState master : sentinel.getMasters().values()) {
            info.append("master").append(index).append(":")
                .append("name=").append(master.getName())
                .append(",status=").append(master.isODown() ? "odown" : "ok")
                .append(",address=").append(master.getHost()).append(":").append(master.getPort())
                .append(",slaves=").append(master.getSlaves().size())
                .append(",sentinels=").append(master.getSentinels().size() + 1)
                .append("\r\n");
            index++;
        }
        
        return "$" + info.length() + "\r\n" + info.toString() + "\r\n";
    }
    
    /**
     * SENTINEL MASTERS
     */
    private String handleMasters(String[] args) {
        List<String> masterInfos = new ArrayList<>();
        
        for (MasterState master : sentinel.getMasters().values()) {
            masterInfos.add(formatMasterInfo(master));
        }
        
        return formatArrayResponse(masterInfos);
    }
    
    /**
     * SENTINEL RESET pattern
     */
    private String handleReset(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'sentinel reset' command\r\n";
        }
        
        String pattern = args[2];
        int resetCount = 0;
        
        for (String masterName : sentinel.getMasters().keySet()) {
            if (pattern.equals("*") || masterName.matches(pattern.replace("*", ".*"))) {
                sentinel.removeMaster(masterName);
                resetCount++;
            }
        }
        
        return ":" + resetCount + "\r\n";
    }
    
    /**
     * SENTINEL IS-MASTER-DOWN-BY-ADDR ip port currentepoch runid
     */
    private String handleIsMasterDownByAddr(String[] args) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'sentinel is-master-down-by-addr' command\r\n";
        }
        
        String ip = args[2];
        int port;
        
        try {
            port = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            return "-ERR invalid port number\r\n";
        }
        
        // 查找对应的主节点
        MasterState targetMaster = null;
        for (MasterState master : sentinel.getMasters().values()) {
            if (master.getHost().equals(ip) && master.getPort() == port) {
                targetMaster = master;
                break;
            }
        }
        
        if (targetMaster == null) {
            return "*3\r\n$-1\r\n$-1\r\n:0\r\n";
        }
        
        // 检查是否下线
        int down = targetMaster.isSDown() ? 1 : 0;
        
        // 处理投票请求
        String leader = null;
        if (args.length >= 6) {
            String reqEpoch = args[4];
            String reqRunid = args[5];
            
            if (!reqRunid.equals("*")) {
                // 投票请求
                boolean voted = sentinel.voteForLeader(targetMaster.getName(), reqEpoch, reqRunid);
                if (voted) {
                    leader = sentinel.getSentinelId();
                }
            }
        }
        
        // 返回响应
        StringBuilder response = new StringBuilder("*3\r\n");
        
        // 下线状态
        response.append(":").append(down).append("\r\n");
        
        // 领导者
        if (leader != null) {
            response.append("$").append(leader.length()).append("\r\n").append(leader).append("\r\n");
        } else {
            response.append("$-1\r\n");
        }
        
        // 投票纪元
        response.append(":").append(sentinel.getCurrentEpoch()).append("\r\n");
        
        return response.toString();
    }
    
    /**
     * 格式化从节点信息
     */
    private String formatSlaveInfo(SlaveState slave) {
        StringBuilder info = new StringBuilder();
        
        info.append("name:").append(slave.getSlaveId()).append("\r\n");
        info.append("ip:").append(slave.getHost()).append("\r\n");
        info.append("port:").append(slave.getPort()).append("\r\n");
        info.append("runid:").append(slave.getReplId() != null ? slave.getReplId() : "?").append("\r\n");
        info.append("flags:").append(slave.isSDown() ? "s_down,slave" : "slave").append("\r\n");
        info.append("link-pending-commands:0\r\n");
        info.append("link-refcount:1\r\n");
        info.append("last-ping-sent:0\r\n");
        info.append("last-ok-ping-reply:").append(
            (System.currentTimeMillis() - slave.getLastOkPingReply()) / 1000).append("\r\n");
        info.append("last-ping-reply:").append(
            (System.currentTimeMillis() - slave.getLastPongTime()) / 1000).append("\r\n");
        info.append("down-after-milliseconds:").append(
            sentinel.getConfig().getDownAfterMilliseconds()).append("\r\n");
        info.append("info-refresh:").append(
            (System.currentTimeMillis() - slave.getLastPongTime()) / 1000).append("\r\n");
        info.append("role-reported:slave\r\n");
        info.append("role-reported-time:").append(System.currentTimeMillis()).append("\r\n");
        info.append("master-host:").append(slave.getMasterHost() != null ? slave.getMasterHost() : "?").append("\r\n");
        info.append("master-port:").append(slave.getMasterPort()).append("\r\n");
        info.append("slave-priority:").append(slave.getPriority()).append("\r\n");
        info.append("slave-repl-offset:").append(slave.getReplOffset()).append("\r\n");
        info.append("slave-repl-lag:").append(slave.getLag()).append("\r\n");
        
        return info.toString();
    }
    
    /**
     * 格式化哨兵信息
     */
    private String formatSentinelInfo(SentinelInstance si) {
        StringBuilder info = new StringBuilder();
        
        info.append("name:").append(si.getSentinelId()).append("\r\n");
        info.append("ip:").append(si.getHost()).append("\r\n");
        info.append("port:").append(si.getPort()).append("\r\n");
        info.append("runid:").append(si.getSentinelId()).append("\r\n");
        info.append("flags:sentinel\r\n");
        info.append("link-pending-commands:0\r\n");
        info.append("link-refcount:1\r\n");
        info.append("last-ping-sent:0\r\n");
        info.append("last-ok-ping-reply:").append(
            (System.currentTimeMillis() - si.getLastPongTime()) / 1000).append("\r\n");
        info.append("last-ping-reply:").append(
            (System.currentTimeMillis() - si.getLastPongTime()) / 1000).append("\r\n");
        info.append("down-after-milliseconds:").append(
            sentinel.getConfig().getDownAfterMilliseconds()).append("\r\n");
        info.append("last-hello-message:").append(
            (System.currentTimeMillis() - si.getLastHelloTime()) / 1000).append("\r\n");
        info.append("voted_leader:").append(si.getVotedLeader() != null ? si.getVotedLeader() : "?").append("\r\n");
        info.append("voted_leader_epoch:").append(si.getVotedLeaderEpoch()).append("\r\n");
        
        return info.toString();
    }
    
    /**
     * 格式化主节点信息
     */
    private String formatMasterInfo(MasterState master) {
        StringBuilder info = new StringBuilder();
        
        info.append("name:").append(master.getName()).append("\r\n");
        info.append("ip:").append(master.getHost()).append("\r\n");
        info.append("port:").append(master.getPort()).append("\r\n");
        info.append("runid:").append(master.getReplId() != null ? master.getReplId() : "?").append("\r\n");
        info.append("flags:").append(getFlagsString(master)).append("\r\n");
        info.append("num-slaves:").append(master.getSlaves().size()).append("\r\n");
        info.append("num-other-sentinels:").append(master.getSentinels().size()).append("\r\n");
        info.append("quorum:").append(master.getQuorum()).append("\r\n");
        
        return info.toString();
    }
    
    /**
     * 获取标志字符串
     */
    private String getFlagsString(MasterState master) {
        StringBuilder flags = new StringBuilder();
        
        if (master.isODown()) {
            flags.append("o_down,");
        }
        if (master.isSDown()) {
            flags.append("s_down,");
        }
        if (master.isFailoverInProgress()) {
            flags.append("failover_in_progress,");
        }
        flags.append("master");
        
        return flags.toString();
    }
    
    /**
     * 格式化数组响应
     */
    private String formatArrayResponse(List<String> items) {
        if (items.isEmpty()) {
            return "*0\r\n";
        }
        
        StringBuilder response = new StringBuilder();
        response.append("*").append(items.size()).append("\r\n");
        
        for (String item : items) {
            response.append("*2\r\n");
            String[] lines = item.split("\r\n");
            for (String line : lines) {
                if (!line.isEmpty()) {
                    int colonIndex = line.indexOf(':');
                    if (colonIndex > 0) {
                        String key = line.substring(0, colonIndex);
                        String value = line.substring(colonIndex + 1);
                        response.append("$").append(key.length()).append("\r\n").append(key).append("\r\n");
                        response.append("$").append(value.length()).append("\r\n").append(value).append("\r\n");
                    }
                }
            }
        }
        
        return response.toString();
    }
}
