package com.janeluo.luban.rds.persistence;

import com.janeluo.luban.rds.core.store.MemoryStore;

public interface PersistService {
    /**
     * 持久化数据
     * @param memoryStore 内存存储实例
     */
    void persist(MemoryStore memoryStore);
    
    /**
     * 加载持久化数据
     * @param memoryStore 内存存储实例
     */
    void load(MemoryStore memoryStore);
    
    /**
     * 获取持久化信息
     * @return 包含持久化统计和状态的Map
     */
    java.util.Map<String, Object> getInfo();
    
    /**
     * 关闭持久化服务
     */
    void close();
}
