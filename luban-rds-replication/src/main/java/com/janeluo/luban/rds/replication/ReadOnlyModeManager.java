package com.janeluo.luban.rds.replication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 从节点只读模式管理器
 * 
 * 管理从节点的只读模式，包括：
 * - 写命令拦截
 * - 只读模式切换
 * - 配置管理
 */
public class ReadOnlyModeManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ReadOnlyModeManager.class);
    
    /**
     * 只读错误消息
     */
    public static final String READONLY_ERROR = "-READONLY You can't write against a read only replica.\r\n";
    
    /**
     * 是否处于只读模式
     */
    private final AtomicBoolean readOnly = new AtomicBoolean(true);
    
    /**
     * 是否为从节点
     */
    private final AtomicBoolean isSlave = new AtomicBoolean(false);
    
    /**
     * 写命令集合
     */
    private static final Set<String> WRITE_COMMANDS = new HashSet<>();
    
    static {
        // 字符串操作
        WRITE_COMMANDS.add("SET");
        WRITE_COMMANDS.add("SETEX");
        WRITE_COMMANDS.add("SETNX");
        WRITE_COMMANDS.add("MSET");
        WRITE_COMMANDS.add("MSETNX");
        WRITE_COMMANDS.add("APPEND");
        WRITE_COMMANDS.add("INCR");
        WRITE_COMMANDS.add("INCRBY");
        WRITE_COMMANDS.add("INCRBYFLOAT");
        WRITE_COMMANDS.add("DECR");
        WRITE_COMMANDS.add("DECRBY");
        WRITE_COMMANDS.add("GETSET");
        WRITE_COMMANDS.add("SETRANGE");
        WRITE_COMMANDS.add("PSETEX");
        
        // 键操作
        WRITE_COMMANDS.add("DEL");
        WRITE_COMMANDS.add("UNLINK");
        WRITE_COMMANDS.add("EXPIRE");
        WRITE_COMMANDS.add("EXPIREAT");
        WRITE_COMMANDS.add("PEXPIRE");
        WRITE_COMMANDS.add("PEXPIREAT");
        WRITE_COMMANDS.add("PERSIST");
        WRITE_COMMANDS.add("RENAME");
        WRITE_COMMANDS.add("RENAMENX");
        WRITE_COMMANDS.add("MOVE");
        
        // 列表操作
        WRITE_COMMANDS.add("LPUSH");
        WRITE_COMMANDS.add("LPUSHX");
        WRITE_COMMANDS.add("RPUSH");
        WRITE_COMMANDS.add("RPUSHX");
        WRITE_COMMANDS.add("LPOP");
        WRITE_COMMANDS.add("RPOP");
        WRITE_COMMANDS.add("LREM");
        WRITE_COMMANDS.add("LSET");
        WRITE_COMMANDS.add("LTRIM");
        WRITE_COMMANDS.add("RPOPLPUSH");
        
        // 集合操作
        WRITE_COMMANDS.add("SADD");
        WRITE_COMMANDS.add("SREM");
        WRITE_COMMANDS.add("SPOP");
        WRITE_COMMANDS.add("SMOVE");
        WRITE_COMMANDS.add("SINTERSTORE");
        WRITE_COMMANDS.add("SUNIONSTORE");
        WRITE_COMMANDS.add("SDIFFSTORE");
        
        // 有序集合操作
        WRITE_COMMANDS.add("ZADD");
        WRITE_COMMANDS.add("ZINCRBY");
        WRITE_COMMANDS.add("ZREM");
        WRITE_COMMANDS.add("ZREMRANGEBYRANK");
        WRITE_COMMANDS.add("ZREMRANGEBYSCORE");
        WRITE_COMMANDS.add("ZREMRANGEBYLEX");
        WRITE_COMMANDS.add("ZUNIONSTORE");
        WRITE_COMMANDS.add("ZINTERSTORE");
        
        // 哈希操作
        WRITE_COMMANDS.add("HSET");
        WRITE_COMMANDS.add("HSETNX");
        WRITE_COMMANDS.add("HMSET");
        WRITE_COMMANDS.add("HINCRBY");
        WRITE_COMMANDS.add("HINCRBYFLOAT");
        WRITE_COMMANDS.add("HDEL");
        
        // 位图操作
        WRITE_COMMANDS.add("SETBIT");
        WRITE_COMMANDS.add("BITFIELD");
        
        // HyperLogLog 操作
        WRITE_COMMANDS.add("PFADD");
        WRITE_COMMANDS.add("PFMERGE");
        
        // 地理位置操作
        WRITE_COMMANDS.add("GEOADD");
        
        // Stream 操作
        WRITE_COMMANDS.add("XADD");
        WRITE_COMMANDS.add("XTRIM");
        WRITE_COMMANDS.add("XDEL");
        WRITE_COMMANDS.add("XGROUP");
        WRITE_COMMANDS.add("XACK");
        WRITE_COMMANDS.add("XCLAIM");
        WRITE_COMMANDS.add("XSETID");
        
        // 事务操作
        WRITE_COMMANDS.add("MULTI");
        WRITE_COMMANDS.add("EXEC");
        WRITE_COMMANDS.add("DISCARD");
        WRITE_COMMANDS.add("WATCH");
        WRITE_COMMANDS.add("UNWATCH");
        
        // 脚本操作
        WRITE_COMMANDS.add("EVAL");
        WRITE_COMMANDS.add("EVALSHA");
        WRITE_COMMANDS.add("SCRIPT");
        
        // 发布订阅操作
        WRITE_COMMANDS.add("PUBLISH");
        
        // 数据库操作
        WRITE_COMMANDS.add("FLUSHDB");
        WRITE_COMMANDS.add("FLUSHALL");
        WRITE_COMMANDS.add("SWAPDB");
        
        // 复制操作
        WRITE_COMMANDS.add("SLAVEOF");
        WRITE_COMMANDS.add("REPLICAOF");
    }
    
    /**
     * 检查命令是否为写命令
     * 
     * @param command 命令名称
     * @return 是否为写命令
     */
    public boolean isWriteCommand(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        return WRITE_COMMANDS.contains(command.toUpperCase());
    }
    
    /**
     * 检查是否应该拦截命令
     * 
     * @param command 命令名称
     * @return 是否应该拦截（返回 true 表示应该拦截）
     */
    public boolean shouldIntercept(String command) {
        // 如果不是从节点，不拦截
        if (!isSlave.get()) {
            return false;
        }
        
        // 如果不是只读模式，不拦截
        if (!readOnly.get()) {
            return false;
        }
        
        // 如果是写命令，拦截
        return isWriteCommand(command);
    }
    
    /**
     * 拦截写命令并返回错误消息
     * 
     * @param command 命令名称
     * @return 错误消息，如果不需要拦截则返回 null
     */
    public String interceptWriteCommand(String command) {
        if (shouldIntercept(command)) {
            logger.debug("Intercepted write command on read-only replica: {}", command);
            return READONLY_ERROR;
        }
        return null;
    }
    
    /**
     * 设置只读模式
     * 
     * @param readOnly 是否只读
     */
    public void setReadOnly(boolean readOnly) {
        boolean oldValue = this.readOnly.getAndSet(readOnly);
        if (oldValue != readOnly) {
            logger.info("Read-only mode changed: {} -> {}", oldValue, readOnly);
        }
    }
    
    /**
     * 获取只读模式
     */
    public boolean isReadOnly() {
        return readOnly.get();
    }
    
    /**
     * 设置从节点状态
     * 
     * @param isSlave 是否为从节点
     */
    public void setSlave(boolean isSlave) {
        boolean oldValue = this.isSlave.getAndSet(isSlave);
        if (oldValue != isSlave) {
            logger.info("Slave mode changed: {} -> {}", oldValue, isSlave);
            
            // 如果变成从节点，自动启用只读模式
            if (isSlave) {
                readOnly.set(true);
                logger.info("Automatically enabled read-only mode for slave");
            }
        }
    }
    
    /**
     * 是否为从节点
     */
    public boolean isSlave() {
        return isSlave.get();
    }
    
    /**
     * 处理 CONFIG SET 命令
     * 
     * @param parameter 配置参数
     * @param value 配置值
     * @return 响应消息
     */
    public String handleConfigSet(String parameter, String value) {
        if ("slave-read-only".equalsIgnoreCase(parameter) || 
            "replica-read-only".equalsIgnoreCase(parameter)) {
            try {
                boolean newReadOnly = "yes".equalsIgnoreCase(value) || 
                                     "1".equals(value) || 
                                     "true".equalsIgnoreCase(value);
                setReadOnly(newReadOnly);
                return "+OK\r\n";
            } catch (Exception e) {
                return "-ERR invalid value for " + parameter + "\r\n";
            }
        }
        return null;
    }
    
    /**
     * 处理 CONFIG GET 命令
     * 
     * @param parameter 配置参数
     * @return 配置值
     */
    public String handleConfigGet(String parameter) {
        if ("slave-read-only".equalsIgnoreCase(parameter) || 
            "replica-read-only".equalsIgnoreCase(parameter)) {
            return "*" + 2 + "\r\n" +
                   "$" + parameter.length() + "\r\n" + parameter + "\r\n" +
                   "$" + (readOnly.get() ? "yes".length() : "no".length()) + "\r\n" + 
                   (readOnly.get() ? "yes" : "no") + "\r\n";
        }
        return null;
    }
    
    /**
     * 获取只读模式信息
     */
    public String getInfo() {
        return "slave_read_only:" + (readOnly.get() ? 1 : 0) + "\r\n";
    }
    
    /**
     * 获取写命令列表（用于调试）
     */
    public Set<String> getWriteCommands() {
        return new HashSet<>(WRITE_COMMANDS);
    }
}
