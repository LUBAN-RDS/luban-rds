package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import java.util.Set;

/**
 * 命令处理器接口
 * 
 * <p>所有Redis命令处理器的顶层接口，定义了命令处理的基本契约。
 * 每个具体实现负责处理特定类型的Redis命令。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public interface CommandHandler {
    
    /**
     * 处理Redis命令
     *
     * @param database 数据库索引
     * @param args 命令参数数组，args[0]为命令名
     * @param store 内存存储实例
     * @return 命令执行结果，RESP协议格式的响应
     */
    Object handle(int database, String[] args, MemoryStore store);
    
    /**
     * 获取该处理器支持的命令集合
     *
     * @return 支持的命令名称集合
     */
    Set<String> supportedCommands();
}
