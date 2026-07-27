package com.janeluo.luban.rds.persistence;

import com.janeluo.luban.rds.core.store.MemoryStore;

/**
 * 持久化服务接口
 * 
 * <p>定义数据持久化的基本操作契约，支持RDB和AOF两种持久化方式。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public interface PersistService {
    
    /**
     * 持久化数据
     *
     * @param memoryStore 内存存储实例
     */
    void persist(MemoryStore memoryStore);
    
    /**
     * 加载持久化数据
     *
     * @param memoryStore 内存存储实例
     */
    void load(MemoryStore memoryStore);
    
    /**
     * 获取持久化信息
     *
     * @return 包含持久化统计和状态的Map
     */
    java.util.Map<String, Object> getInfo();

    /**
     * 记录写命令到持久化介质（主要用于 AOF）。
     *
     * <p>接收原始 RESP 帧字节，由调用方（如命令处理 / 复制传播）保证帧的完整性。
     * 采用 ISO-8859-1 编码写入以实现二进制安全。default 空实现使非 AOF 实现
     * （RdbPersistService / NonePersistService / CompositePersistService 等）无需修改。
     *
     * @param respFrame 原始 RESP 命令帧字节（不可为 null）
     * @since 1.0.0
     */
    default void recordCommand(byte[] respFrame) {
        // 默认空实现：非 AOF 持久化服务不记录单条写命令
    }

    /**
     * 关闭持久化服务
     */
    void close();
}
