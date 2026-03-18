package com.janeluo.luban.rds.cluster.migration;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 槽位迁移管理器
 * <p>
 * 管理槽位的导入和导出过程，实现 Redis 集群的槽位迁移功能
 * </p>
 */
public class SlotMigrationManager {

    private static final Logger logger = LoggerFactory.getLogger(SlotMigrationManager.class);

    /**
     * 集群配置
     */
    private final ClusterConfig clusterConfig;

    /**
     * 槽位管理器
     */
    private final SlotManager slotManager;

    /**
     * 内存存储
     */
    private final MemoryStore memoryStore;

    /**
     * 正在迁移的槽位状态（槽位 -> 迁移状态）
     */
    private final Map<Integer, MigrationState> migratingSlots;

    /**
     * 正在导入的槽位状态（槽位 -> 导入状态）
     */
    private final Map<Integer, ImportState> importingSlots;

    /**
     * 默认数据库索引
     */
    private static final int DEFAULT_DATABASE = 0;

    /**
     * 构造方法
     *
     * @param clusterConfig 集群配置
     * @param slotManager   槽位管理器
     * @param memoryStore   内存存储
     */
    public SlotMigrationManager(ClusterConfig clusterConfig, 
                                SlotManager slotManager, 
                                MemoryStore memoryStore) {
        this.clusterConfig = clusterConfig;
        this.slotManager = slotManager;
        this.memoryStore = memoryStore;
        this.migratingSlots = new ConcurrentHashMap<>();
        this.importingSlots = new ConcurrentHashMap<>();
    }

    // ==================== 迁移状态管理 ====================

    /**
     * 设置槽位为 IMPORTING 状态
     *
     * @param slot         槽位号
     * @param sourceNodeId 源节点ID
     */
    public void setImporting(int slot, String sourceNodeId) {
        SlotUtils.validateSlot(slot);
        
        // 检查是否已经在迁移状态
        if (migratingSlots.containsKey(slot)) {
            throw new IllegalStateException("槽位 " + slot + " 正在迁移中，无法设置导入状态");
        }
        
        // 创建导入状态
        ImportState importState = new ImportState(slot, sourceNodeId);
        importingSlots.put(slot, importState);
        
        // 设置槽位管理器的导入状态
        slotManager.setSlotImporting(slot, sourceNodeId);
        
        logger.info("槽位 {} 设置为 IMPORTING 状态，源节点: {}", slot, sourceNodeId);
    }

    /**
     * 设置槽位为 MIGRATING 状态
     *
     * @param slot         槽位号
     * @param targetNodeId 目标节点ID
     */
    public void setMigrating(int slot, String targetNodeId) {
        SlotUtils.validateSlot(slot);
        
        // 检查是否已经在导入状态
        if (importingSlots.containsKey(slot)) {
            throw new IllegalStateException("槽位 " + slot + " 正在导入中，无法设置迁移状态");
        }
        
        // 检查槽位是否属于当前节点
        if (!slotManager.isSlotLocal(slot)) {
            throw new IllegalStateException("槽位 " + slot + " 不属于当前节点，无法迁移");
        }
        
        // 创建迁移状态
        MigrationState migrationState = new MigrationState(slot, targetNodeId);
        
        // 获取槽位中的键数量
        int keysCount = memoryStore.countKeysInSlot(DEFAULT_DATABASE, slot);
        migrationState.setKeysCount(keysCount);
        
        migratingSlots.put(slot, migrationState);
        
        // 设置槽位管理器的迁移状态
        slotManager.setSlotMigrating(slot, targetNodeId);
        
        logger.info("槽位 {} 设置为 MIGRATING 状态，目标节点: {}，键数量: {}", 
                slot, targetNodeId, keysCount);
    }

    /**
     * 清除槽位的迁移状态
     *
     * @param slot 槽位号
     */
    public void clearMigrationState(int slot) {
        SlotUtils.validateSlot(slot);
        
        migratingSlots.remove(slot);
        importingSlots.remove(slot);
        
        slotManager.setSlotMigrating(slot, null);
        slotManager.setSlotImporting(slot, null);
        
        logger.info("槽位 {} 的迁移状态已清除", slot);
    }

    /**
     * 获取槽位迁移状态
     *
     * @param slot 槽位号
     * @return 迁移状态，如果未在迁移则返回 null
     */
    public MigrationState getMigrationState(int slot) {
        return migratingSlots.get(slot);
    }

    /**
     * 获取槽位导入状态
     *
     * @param slot 槽位号
     * @return 导入状态，如果未在导入则返回 null
     */
    public ImportState getImportState(int slot) {
        return importingSlots.get(slot);
    }

    /**
     * 检查槽位是否正在迁移
     *
     * @param slot 槽位号
     * @return 是否正在迁移
     */
    public boolean isMigrating(int slot) {
        return migratingSlots.containsKey(slot);
    }

    /**
     * 检查槽位是否正在导入
     *
     * @param slot 槽位号
     * @return 是否正在导入
     */
    public boolean isImporting(int slot) {
        return importingSlots.containsKey(slot);
    }

    // ==================== 键访问控制 ====================

    /**
     * 检查键是否可以访问
     * <p>
     * 在迁移过程中需要特殊处理：
     * - 如果槽位正在迁移，且键还存在，返回 true
     * - 如果槽位正在迁移，且键不存在，返回 ASK 重定向
     * - 如果槽位正在导入，且带有 ASKING 标志，返回 true
     * </p>
     *
     * @param key      键名
     * @param isAsking 是否带有 ASKING 标志
     * @return 是否可以访问
     */
    public boolean canAccessKey(String key, boolean isAsking) {
        int slot = SlotUtils.keyHashSlot(key);
        
        // 检查是否正在导入
        ImportState importState = importingSlots.get(slot);
        if (importState != null) {
            // 导入状态下，只有带 ASKING 标志才能访问
            return isAsking;
        }
        
        // 检查是否正在迁移
        MigrationState migrationState = migratingSlots.get(slot);
        if (migrationState != null) {
            // 迁移状态下，键还存在则可以访问
            return memoryStore.exists(DEFAULT_DATABASE, key);
        }
        
        // 正常情况
        return true;
    }

    /**
     * 获取键的重定向信息
     *
     * @param key 键名
     * @return 重定向信息 [type, nodeId, slot]，如果不需要重定向返回 null
     */
    public String[] getRedirectInfo(String key) {
        int slot = SlotUtils.keyHashSlot(key);
        
        // 检查是否正在导入
        ImportState importState = importingSlots.get(slot);
        if (importState != null) {
            // ASK 重定向到源节点
            return new String[]{"ASK", importState.getSourceNodeId(), String.valueOf(slot)};
        }
        
        // 检查是否正在迁移
        MigrationState migrationState = migratingSlots.get(slot);
        if (migrationState != null) {
            // 键不存在时，MOVED 重定向到目标节点
            if (!memoryStore.exists(DEFAULT_DATABASE, key)) {
                return new String[]{"MOVED", migrationState.getTargetNodeId(), String.valueOf(slot)};
            }
        }
        
        return null;
    }

    // ==================== 键导入导出 ====================

    /**
     * 导入单个键
     *
     * @param key   键名
     * @param value 键值数据（序列化后的字节数组）
     * @param ttl   过期时间（毫秒）
     * @return 是否导入成功
     */
    public boolean importKey(String key, byte[] value, long ttl) {
        int slot = SlotUtils.keyHashSlot(key);
        
        // 检查槽位是否在导入状态
        ImportState importState = importingSlots.get(slot);
        if (importState == null) {
            logger.warn("槽位 {} 未处于导入状态，无法导入键 {}", slot, key);
            return false;
        }
        
        try {
            // 反序列化并存储键值
            Object deserializedValue = deserializeValue(value);
            
            if (ttl > 0) {
                memoryStore.setWithExpireMs(DEFAULT_DATABASE, key, deserializedValue, ttl);
            } else {
                memoryStore.set(DEFAULT_DATABASE, key, deserializedValue);
            }
            
            // 更新导入计数
            importState.incrementImportedCount();
            
            logger.debug("成功导入键 {}，槽位: {}", key, slot);
            return true;
            
        } catch (Exception e) {
            logger.error("导入键 {} 失败", key, e);
            return false;
        }
    }

    /**
     * 导出单个键
     *
     * @param key 键名
     * @return 导出结果
     */
    public ExportResult exportKey(String key) {
        int slot = SlotUtils.keyHashSlot(key);
        
        // 检查槽位是否在迁移状态
        MigrationState migrationState = migratingSlots.get(slot);
        if (migrationState == null) {
            return ExportResult.failure(key, "槽位 " + slot + " 未处于迁移状态");
        }
        
        // 检查键是否存在
        if (!memoryStore.exists(DEFAULT_DATABASE, key)) {
            return ExportResult.notFound(key);
        }
        
        try {
            // 获取键值
            Object value = memoryStore.get(DEFAULT_DATABASE, key);
            
            // 获取过期时间
            long ttl = memoryStore.pttl(DEFAULT_DATABASE, key);
            if (ttl < 0) {
                ttl = 0; // 无过期时间
            }
            
            // 获取键类型
            String type = memoryStore.type(DEFAULT_DATABASE, key);
            
            // 序列化键值
            byte[] serializedValue = serializeValue(value);
            
            // 更新迁移计数
            migrationState.incrementMigratedCount();
            
            logger.debug("成功导出键 {}，槽位: {}, 类型: {}", key, slot, type);
            return ExportResult.success(key, serializedValue, ttl, type);
            
        } catch (Exception e) {
            logger.error("导出键 {} 失败", key, e);
            return ExportResult.failure(key, "导出失败: " + e.getMessage());
        }
    }

    /**
     * 获取槽位中的所有键
     *
     * @param slot 槽位号
     * @param count 最大返回数量
     * @return 键列表
     */
    public List<String> getKeysInSlot(int slot, int count) {
        return memoryStore.getKeysInSlot(DEFAULT_DATABASE, slot, count);
    }

    /**
     * 删除已迁移的键
     *
     * @param key 键名
     * @return 是否删除成功
     */
    public boolean deleteMigratedKey(String key) {
        int slot = SlotUtils.keyHashSlot(key);
        
        // 检查槽位是否在迁移状态
        if (!migratingSlots.containsKey(slot)) {
            logger.warn("槽位 {} 未处于迁移状态，无法删除键 {}", slot, key);
            return false;
        }
        
        boolean deleted = memoryStore.del(DEFAULT_DATABASE, key);
        if (deleted) {
            logger.debug("已删除迁移键 {}", key);
        }
        return deleted;
    }

    // ==================== 迁移完成处理 ====================

    /**
     * 完成槽位迁移
     *
     * @param slot       槽位号
     * @param newOwnerId 新的拥有者节点ID
     */
    public void finishMigration(int slot, String newOwnerId) {
        SlotUtils.validateSlot(slot);
        
        MigrationState migrationState = migratingSlots.get(slot);
        if (migrationState != null) {
            migrationState.markCompleted();
            migratingSlots.remove(slot);
            slotManager.setSlotMigrating(slot, null);
            logger.info("槽位 {} 迁移完成，新拥有者: {}", slot, newOwnerId);
        }
        
        ImportState importState = importingSlots.get(slot);
        if (importState != null) {
            importState.markCompleted();
            importingSlots.remove(slot);
            slotManager.setSlotImporting(slot, null);
            logger.info("槽位 {} 导入完成", slot);
        }
        
        // 更新槽位归属
        slotManager.setSlotOwner(slot, newOwnerId);
    }

    /**
     * 取消槽位迁移
     *
     * @param slot 槽位号
     */
    public void cancelMigration(int slot) {
        SlotUtils.validateSlot(slot);
        
        MigrationState migrationState = migratingSlots.get(slot);
        if (migrationState != null) {
            migrationState.markFailed("迁移被取消");
            migratingSlots.remove(slot);
            slotManager.setSlotMigrating(slot, null);
            logger.info("槽位 {} 迁移已取消", slot);
        }
        
        ImportState importState = importingSlots.get(slot);
        if (importState != null) {
            importState.markFailed("导入被取消");
            importingSlots.remove(slot);
            slotManager.setSlotImporting(slot, null);
            logger.info("槽位 {} 导入已取消", slot);
        }
    }

    // ==================== 统计信息 ====================

    /**
     * 获取正在迁移的槽位数量
     *
     * @return 槽位数量
     */
    public int getMigratingSlotCount() {
        return migratingSlots.size();
    }

    /**
     * 获取正在导入的槽位数量
     *
     * @return 槽位数量
     */
    public int getImportingSlotCount() {
        return importingSlots.size();
    }

    /**
     * 获取迁移状态摘要
     *
     * @return 状态摘要字符串
     */
    public String getMigrationSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Migration Summary:\n");
        
        if (!migratingSlots.isEmpty()) {
            sb.append("Migrating slots:\n");
            for (MigrationState state : migratingSlots.values()) {
                sb.append("  ").append(state.toString()).append("\n");
            }
        }
        
        if (!importingSlots.isEmpty()) {
            sb.append("Importing slots:\n");
            for (ImportState state : importingSlots.values()) {
                sb.append("  ").append(state.toString()).append("\n");
            }
        }
        
        if (migratingSlots.isEmpty() && importingSlots.isEmpty()) {
            sb.append("No active migrations.\n");
        }
        
        return sb.toString();
    }

    // ==================== 序列化工具方法 ====================

    /**
     * 序列化值
     *
     * @param value 值对象
     * @return 序列化后的字节数组
     * @throws IOException 序列化失败
     */
    private byte[] serializeValue(Object value) throws IOException {
        if (value == null) {
            return new byte[0];
        }
        
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            return baos.toByteArray();
        }
    }

    /**
     * 反序列化值
     *
     * @param data 序列化数据
     * @return 值对象
     * @throws IOException            IO异常
     * @throws ClassNotFoundException 类未找到异常
     */
    private Object deserializeValue(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) {
            return null;
        }
        
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(data);
             java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais)) {
            return ois.readObject();
        }
    }
}
