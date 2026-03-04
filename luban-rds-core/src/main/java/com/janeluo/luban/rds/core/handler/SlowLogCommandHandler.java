package com.janeluo.luban.rds.core.handler;

import com.google.common.collect.Sets;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.core.slowlog.SlowLogEntry;
import com.janeluo.luban.rds.core.slowlog.SlowLogManager;
import com.janeluo.luban.rds.core.store.MemoryStore;

import java.util.List;
import java.util.Set;

public class SlowLogCommandHandler implements CommandHandler {

    private final Set<String> supportedCommands = Sets.newHashSet("SLOWLOG");

    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'slowlog' command\r\n";
        }

        String subcommand = args[1].toUpperCase();
        SlowLogManager manager = SlowLogManager.getInstance();

        switch (subcommand) {
            case "GET":
                int count = 10; // Default count
                if (args.length >= 3) {
                    try {
                        count = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        return "-ERR value is not an integer or out of range\r\n";
                    }
                }
                List<SlowLogEntry> entries = manager.get(count);
                return formatEntries(entries);
            case "LEN":
                long len = manager.len();
                return ":" + len + "\r\n";
            case "RESET":
                manager.reset();
                return RdsResponseConstant.OK;
            default:
                return "-ERR unknown subcommand '" + subcommand + "'. Try SLOWLOG GET, LEN, RESET.\r\n";
        }
    }

    private String formatEntries(List<SlowLogEntry> entries) {
        StringBuilder response = new StringBuilder();
        response.append("*").append(entries.size()).append("\r\n");

        for (SlowLogEntry entry : entries) {
            response.append("*6\r\n"); // Each entry is an array of 6 elements
            
            // 1. ID
            response.append(":").append(entry.getId()).append("\r\n");
            
            // 2. Timestamp
            response.append(":").append(entry.getTimestamp()).append("\r\n");
            
            // 3. Duration
            response.append(":").append(entry.getDuration()).append("\r\n");
            
            // 4. Args
            List<String> cmdArgs = entry.getArgs();
            response.append("*").append(cmdArgs.size()).append("\r\n");
            for (String arg : cmdArgs) {
                response.append("$").append(arg.length()).append("\r\n").append(arg).append("\r\n");
            }
            
            // 5. Client IP
            String clientIp = entry.getClientIp();
            if (clientIp == null) {
                response.append("$-1\r\n");
            } else {
                response.append("$").append(clientIp.length()).append("\r\n").append(clientIp).append("\r\n");
            }
            
            // 6. Client Name
            String clientName = entry.getClientName();
            if (clientName == null) {
                response.append("$-1\r\n");
            } else {
                response.append("$").append(clientName.length()).append("\r\n").append(clientName).append("\r\n");
            }
        }
        return response.toString();
    }

    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
