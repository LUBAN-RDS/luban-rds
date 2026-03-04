package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import java.util.Set;

public interface CommandHandler {
    Object handle(int database, String[] args, MemoryStore store);
    
    Set<String> supportedCommands();
}
