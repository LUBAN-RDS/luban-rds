package com.janeluo.luban.rds.persistence;

import com.janeluo.luban.rds.persistence.impl.AofPersistService;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import com.janeluo.luban.rds.core.store.MemoryStore;

public class PersistServiceFactory {
    public static final String PERSIST_MODE_RDB = "rdb";
    public static final String PERSIST_MODE_AOF = "aof";
    public static final String PERSIST_MODE_BOTH = "both";
    
    /**
     * 创建持久化服务实例
     * @param persistMode 持久化模式：rdb, aof, both
     * @param dataDir 数据目录
     * @param rdbSaveInterval RDB保存间隔（秒）
     * @param aofFsyncInterval AOF fsync间隔（秒）
     * @return 持久化服务实例
     */
    public static PersistService createPersistService(String persistMode, String dataDir, int rdbSaveInterval, int aofFsyncInterval) {
        switch (persistMode.toLowerCase()) {
            case PERSIST_MODE_RDB:
                return new RdbPersistService(dataDir);
            case PERSIST_MODE_AOF:
                return new AofPersistService(dataDir, aofFsyncInterval);
            case PERSIST_MODE_BOTH:
                // 当使用both模式时，返回一个复合的持久化服务
                return new CompositePersistService(
                    new RdbPersistService(dataDir),
                    new AofPersistService(dataDir, aofFsyncInterval)
                );
            default:
                throw new IllegalArgumentException("Invalid persist mode: " + persistMode);
        }
    }
    
    /**
     * 复合持久化服务，同时支持RDB和AOF
     */
    private static class CompositePersistService implements PersistService {
        private final PersistService rdbService;
        private final PersistService aofService;
        
        public CompositePersistService(PersistService rdbService, PersistService aofService) {
            this.rdbService = rdbService;
            this.aofService = aofService;
        }
        
        @Override
        public void persist(MemoryStore memoryStore) {
            rdbService.persist(memoryStore);
            aofService.persist(memoryStore);
        }
        
        @Override
        public java.util.Map<String, Object> getInfo() {
            java.util.Map<String, Object> info = new java.util.HashMap<>();
            info.putAll(rdbService.getInfo());
            info.putAll(aofService.getInfo());
            return info;
        }

        @Override
        public void load(MemoryStore memoryStore) {
            // 先加载RDB，再加载AOF，这样AOF中的命令会覆盖RDB中的数据
            rdbService.load(memoryStore);
            aofService.load(memoryStore);
        }
        
        @Override
        public void close() {
            rdbService.close();
            aofService.close();
        }
    }
}
