package com.janeluo.luban.rds.cluster.slot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 默认槽位管理器实现
 * <p>
 * 线程安全的槽位管理实现，使用：
 * - BitSet 存储本节点的槽位（16384位 = 2KB）
 * - String[] 存储每个槽位的所属节点ID
 * - ReadWriteLock 保证线程安全
 * </p>
 */
public class DefaultSlotManager implements SlotManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSlotManager.class);

    /**
     * 当前节点ID
     */
    private volatile String myNodeId;

    /**
     * 当前节点负责的槽位（16384位）
     */
    private final BitSet mySlots;

    /**
     * 每个槽位的所属节点ID（null表示未分配）
     */
    private final String[] slotOwners;

    /**
     * 读写锁，保证线程安全
     */
    private final ReadWriteLock lock;

    /**
     * 正在迁移的槽位（槽位 -> 目标节点ID）
     */
    private final Map<Integer, String> migratingSlots;

    /**
     * 正在导入的槽位（槽位 -> 源节点ID）
     */
    private final Map<Integer, String> importingSlots;

    /**
     * 默认构造方法
     */
    public DefaultSlotManager() {
        this.mySlots = new BitSet(SlotUtils.CLUSTER_SLOTS);
        this.slotOwners = new String[SlotUtils.CLUSTER_SLOTS];
        this.lock = new ReentrantReadWriteLock();
        this.migratingSlots = new ConcurrentHashMap<>();
        this.importingSlots = new ConcurrentHashMap<>();
    }

    /**
     * 带节点ID的构造方法
     *
     * @param nodeId 当前节点ID
     */
    public DefaultSlotManager(String nodeId) {
        this();
        this.myNodeId = nodeId;
    }

    @Override
    public void addSlots(int... slots) {
        if (slots == null || slots.length == 0) {
            return;
        }

        lock.writeLock().lock();
        try {
            for (int slot : slots) {
                SlotUtils.validateSlot(slot);
                if (slotOwners[slot] != null && !myNodeId.equals(slotOwners[slot])) {
                    logger.warn("槽位 {} 已分配给节点 {}，无法重复分配", slot, slotOwners[slot]);
                    throw new IllegalStateException(
                            "槽位 " + slot + " 已分配给节点 " + slotOwners[slot]);
                }
                mySlots.set(slot);
                slotOwners[slot] = myNodeId;
            }
            logger.debug("节点 {} 添加槽位: {}", myNodeId, Arrays.toString(slots));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void addSlotRange(int start, int end) {
        SlotUtils.validateSlot(start);
        SlotUtils.validateSlot(end);
        if (start > end) {
            throw new IllegalArgumentException("起始槽位不能大于结束槽位");
        }

        lock.writeLock().lock();
        try {
            // 先检查是否有冲突
            for (int slot = start; slot <= end; slot++) {
                if (slotOwners[slot] != null && !myNodeId.equals(slotOwners[slot])) {
                    logger.warn("槽位 {} 已分配给节点 {}，无法重复分配", slot, slotOwners[slot]);
                    throw new IllegalStateException(
                            "槽位 " + slot + " 已分配给节点 " + slotOwners[slot]);
                }
            }
            // 批量设置
            mySlots.set(start, end + 1);
            for (int slot = start; slot <= end; slot++) {
                slotOwners[slot] = myNodeId;
            }
            logger.debug("节点 {} 添加槽位范围: {}-{}", myNodeId, start, end);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delSlots(int... slots) {
        if (slots == null || slots.length == 0) {
            return;
        }

        lock.writeLock().lock();
        try {
            for (int slot : slots) {
                SlotUtils.validateSlot(slot);
                mySlots.clear(slot);
                slotOwners[slot] = null;
            }
            logger.debug("节点 {} 移除槽位: {}", myNodeId, Arrays.toString(slots));
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void delSlotRange(int start, int end) {
        SlotUtils.validateSlot(start);
        SlotUtils.validateSlot(end);
        if (start > end) {
            throw new IllegalArgumentException("起始槽位不能大于结束槽位");
        }

        lock.writeLock().lock();
        try {
            mySlots.clear(start, end + 1);
            for (int slot = start; slot <= end; slot++) {
                slotOwners[slot] = null;
            }
            logger.debug("节点 {} 移除槽位范围: {}-{}", myNodeId, start, end);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String getSlotOwner(int slot) {
        SlotUtils.validateSlot(slot);

        lock.readLock().lock();
        try {
            return slotOwners[slot];
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isSlotLocal(int slot) {
        SlotUtils.validateSlot(slot);

        lock.readLock().lock();
        try {
            return mySlots.get(slot);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public BitSet getMySlots() {
        lock.readLock().lock();
        try {
            return (BitSet) mySlots.clone();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void setSlotOwner(int slot, String nodeId) {
        SlotUtils.validateSlot(slot);

        lock.writeLock().lock();
        try {
            if (nodeId == null) {
                // 取消分配
                mySlots.clear(slot);
                slotOwners[slot] = null;
            } else if (nodeId.equals(myNodeId)) {
                // 分配给当前节点
                mySlots.set(slot);
                slotOwners[slot] = nodeId;
            } else {
                // 分配给其他节点
                mySlots.clear(slot);
                slotOwners[slot] = nodeId;
            }
            logger.debug("槽位 {} 设置为节点 {}", slot, nodeId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public int getMySlotCount() {
        lock.readLock().lock();
        try {
            return mySlots.cardinality();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void clearMySlots() {
        lock.writeLock().lock();
        try {
            // 清除本节点的槽位
            for (int i = mySlots.nextSetBit(0); i >= 0; i = mySlots.nextSetBit(i + 1)) {
                slotOwners[i] = null;
            }
            mySlots.clear();
            logger.debug("节点 {} 清空所有槽位", myNodeId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isSlotAssigned(int slot) {
        SlotUtils.validateSlot(slot);

        lock.readLock().lock();
        try {
            return slotOwners[slot] != null;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String getMyNodeId() {
        return myNodeId;
    }

    @Override
    public void setMyNodeId(String nodeId) {
        this.myNodeId = nodeId;
    }

    @Override
    public int getUnassignedSlotCount() {
        lock.readLock().lock();
        try {
            int count = 0;
            for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
                if (slotOwners[i] == null) {
                    count++;
                }
            }
            return count;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean isAllSlotsAssigned() {
        lock.readLock().lock();
        try {
            for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
                if (slotOwners[i] == null) {
                    return false;
                }
            }
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 槽位迁移相关方法实现 ====================

    @Override
    public boolean isSlotMigrating(int slot) {
        SlotUtils.validateSlot(slot);
        lock.readLock().lock();
        try {
            return migratingSlots.containsKey(slot);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String getMigratingTarget(int slot) {
        SlotUtils.validateSlot(slot);
        lock.readLock().lock();
        try {
            return migratingSlots.get(slot);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void setSlotMigrating(int slot, String targetNodeId) {
        SlotUtils.validateSlot(slot);
        lock.writeLock().lock();
        try {
            if (targetNodeId == null) {
                migratingSlots.remove(slot);
                logger.debug("槽位 {} 取消迁移状态", slot);
            } else {
                migratingSlots.put(slot, targetNodeId);
                logger.debug("槽位 {} 设置迁移目标节点: {}", slot, targetNodeId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean isSlotImporting(int slot) {
        SlotUtils.validateSlot(slot);
        lock.readLock().lock();
        try {
            return importingSlots.containsKey(slot);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String getImportingSource(int slot) {
        SlotUtils.validateSlot(slot);
        lock.readLock().lock();
        try {
            return importingSlots.get(slot);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void setSlotImporting(int slot, String sourceNodeId) {
        SlotUtils.validateSlot(slot);
        lock.writeLock().lock();
        try {
            if (sourceNodeId == null) {
                importingSlots.remove(slot);
                logger.debug("槽位 {} 取消导入状态", slot);
            } else {
                importingSlots.put(slot, sourceNodeId);
                logger.debug("槽位 {} 设置导入源节点: {}", slot, sourceNodeId);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 获取槽位分配状态的统计信息
     *
     * @return 统计信息字符串
     */
    public String getStatistics() {
        lock.readLock().lock();
        try {
            int myCount = mySlots.cardinality();
            int assignedCount = 0;
            int unassignedCount = 0;

            for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
                if (slotOwners[i] != null) {
                    assignedCount++;
                } else {
                    unassignedCount++;
                }
            }

            return String.format(
                    "SlotManager统计: 总槽位=%d, 本节点槽位=%d, 已分配=%d, 未分配=%d",
                    SlotUtils.CLUSTER_SLOTS, myCount, assignedCount, unassignedCount);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public String toString() {
        return "DefaultSlotManager{" +
                "myNodeId='" + myNodeId + '\'' +
                ", mySlotCount=" + getMySlotCount() +
                '}';
    }
}
