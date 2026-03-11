package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.persistence.PersistService;
import com.janeluo.luban.rds.core.store.MemoryStore;

import java.util.Collections;
import java.util.Map;

/**
 * 无持久化服务实现
 * 
 * <p>不执行任何持久化操作的空实现，用于禁用持久化功能。
 */
public class NonePersistService implements PersistService {

    @Override
    public void persist(MemoryStore memoryStore) {
        // 无操作
    }

    @Override
    public Map<String, Object> getInfo() {
        return Collections.singletonMap("persist_mode", "none");
    }

    @Override
    public void load(MemoryStore memoryStore) {
        // 无操作
    }

    @Override
    public void close() {
        // 无操作
    }
}