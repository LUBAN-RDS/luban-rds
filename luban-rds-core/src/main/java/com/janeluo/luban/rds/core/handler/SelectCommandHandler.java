package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.google.common.collect.Sets;

import java.util.Set;

public class SelectCommandHandler implements CommandHandler {
    private final Set<String> supportedCommands = Sets.newHashSet(
        "SELECT"
    );
    
    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        if (args.length < 2) {
            return "ERR wrong number of arguments for 'select' command";
        }
        
        try {
            Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            return "ERR value is not an integer or out of range";
        }
        
        // SELECT命令的实际处理逻辑在RedisServerHandler中，这里只做参数验证
        return "OK";
    }
    
    @Override
    public Set<String> supportedCommands() {
        return supportedCommands;
    }
}
