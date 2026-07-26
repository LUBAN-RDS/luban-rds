package com.janeluo.luban.rds.replication;

/**
 * 复制状态枚举
 * 参考 Redis 复制状态机实现
 */
public enum ReplicationState {
    
    /**
     * 未连接状态（初始状态）
     */
    DISCONNECTED("disconnected"),
    
    /**
     * 正在连接主节点
     */
    CONNECTING("connecting"),
    
    /**
     * 正在进行握手 - PING 阶段
     */
    HANDSHAKE_PING("handshake_ping"),
    
    /**
     * 正在进行握手 - AUTH 阶段
     */
    HANDSHAKE_AUTH("handshake_auth"),
    
    /**
     * 正在进行握手 - REPLCONF 端口阶段
     */
    HANDSHAKE_REPLCONF_PORT("handshake_replconf_port"),
    
    /**
     * 正在进行握手 - REPLCONF IP 阶段
     */
    HANDSHAKE_REPLCONF_IP("handshake_replconf_ip"),
    
    /**
     * 正在进行握手 - REPLCONF capa 阶段
     */
    HANDSHAKE_REPLCONF_CAPA("handshake_replconf_capa"),
    
    /**
     * 正在进行握手 - REPLCONF ACK 阶段
     */
    HANDSHAKE_REPLCONF_ACK("handshake_replconf_ack"),
    
    /**
     * 正在进行握手 - PSYNC 阶段
     * 已发送 PSYNC 命令，等待主节点返回 +FULLRESYNC 或 +CONTINUE 响应
     */
    HANDSHAKE_PSYNC("handshake_psync"),
    
    /**
     * 正在进行全量同步
     */
    FULL_SYNC("full_sync"),
    
    /**
     * 正在加载 RDB 数据
     */
    LOADING_RDB("loading_rdb"),
    
    /**
     * 正在进行部分重同步
     */
    PARTIAL_SYNC("partial_sync"),
    
    /**
     * 已连接，正在接收增量数据
     */
    ONLINE("online"),
    
    /**
     * 连接错误
     */
    ERROR("error");
    
    private final String name;
    
    ReplicationState(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
    
    /**
     * 判断是否处于握手阶段
     */
    public boolean isHandshake() {
        return this == HANDSHAKE_PING || 
               this == HANDSHAKE_AUTH || 
               this == HANDSHAKE_REPLCONF_PORT ||
               this == HANDSHAKE_REPLCONF_IP ||
               this == HANDSHAKE_REPLCONF_CAPA ||
               this == HANDSHAKE_REPLCONF_ACK ||
               this == HANDSHAKE_PSYNC;
    }
    
    /**
     * 判断是否处于同步阶段
     */
    public boolean isSyncing() {
        return this == FULL_SYNC || this == PARTIAL_SYNC || this == LOADING_RDB;
    }
    
    /**
     * 判断是否已在线
     */
    public boolean isOnline() {
        return this == ONLINE;
    }
    
    /**
     * 判断是否已断开连接
     */
    public boolean isDisconnected() {
        return this == DISCONNECTED || this == ERROR;
    }
}
