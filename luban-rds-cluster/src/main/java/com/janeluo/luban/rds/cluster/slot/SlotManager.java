package com.janeluo.luban.rds.cluster.slot;

import java.util.BitSet;

/**
 * 槽位管理器接口
 * <p>
 * 负责管理集群槽位的分配、查询和迁移状态
 * </p>
 */
public interface SlotManager {

    /**
     * 分配槽位给当前节点
     *
     * @param slots 要分配的槽位号（可变参数）
     * @throws IllegalArgumentException 如果槽位号无效
     */
    void addSlots(int... slots);

    /**
     * 分配槽位范围给当前节点
     *
     * @param start 起始槽位（包含）
     * @param end   结束槽位（包含）
     * @throws IllegalArgumentException 如果槽位号无效或范围不合法
     */
    void addSlotRange(int start, int end);

    /**
     * 移除槽位
     *
     * @param slots 要移除的槽位号（可变参数）
     * @throws IllegalArgumentException 如果槽位号无效
     */
    void delSlots(int... slots);

    /**
     * 移除槽位范围
     *
     * @param start 起始槽位（包含）
     * @param end   结束槽位（包含）
     * @throws IllegalArgumentException 如果槽位号无效或范围不合法
     */
    void delSlotRange(int start, int end);

    /**
     * 获取槽位所属节点ID
     *
     * @param slot 槽位号
     * @return 节点ID，如果槽位未分配则返回null
     * @throws IllegalArgumentException 如果槽位号无效
     */
    String getSlotOwner(int slot);

    /**
     * 检查槽位是否属于当前节点
     *
     * @param slot 槽位号
     * @return 是否属于当前节点
     * @throws IllegalArgumentException 如果槽位号无效
     */
    boolean isSlotLocal(int slot);

    /**
     * 获取当前节点分配的所有槽位
     *
     * @return 槽位BitSet（16384位）
     */
    BitSet getMySlots();

    /**
     * 设置槽位所属节点
     *
     * @param slot   槽位号
     * @param nodeId 节点ID（null表示取消分配）
     * @throws IllegalArgumentException 如果槽位号无效
     */
    void setSlotOwner(int slot, String nodeId);

    /**
     * 获取当前节点负责的槽位数量
     *
     * @return 槽位数量
     */
    int getMySlotCount();

    /**
     * 清空当前节点的所有槽位
     */
    void clearMySlots();

    /**
     * 检查槽位是否已分配
     *
     * @param slot 槽位号
     * @return 是否已分配给任何节点
     * @throws IllegalArgumentException 如果槽位号无效
     */
    boolean isSlotAssigned(int slot);

    /**
     * 获取当前节点ID
     *
     * @return 当前节点ID
     */
    String getMyNodeId();

    /**
     * 设置当前节点ID
     *
     * @param nodeId 节点ID
     */
    void setMyNodeId(String nodeId);

    /**
     * 获取未分配的槽位数量
     *
     * @return 未分配的槽位数量
     */
    int getUnassignedSlotCount();

    /**
     * 检查所有槽位是否都已分配
     *
     * @return 是否所有槽位都已分配
     */
    boolean isAllSlotsAssigned();

    // ==================== 槽位迁移相关方法 ====================

    /**
     * 检查槽位是否正在迁移中
     *
     * @param slot 槽位号
     * @return 是否正在迁移
     * @throws IllegalArgumentException 如果槽位号无效
     */
    boolean isSlotMigrating(int slot);

    /**
     * 获取槽位迁移的目标节点ID
     *
     * @param slot 槽位号
     * @return 目标节点ID，如果未在迁移则返回null
     * @throws IllegalArgumentException 如果槽位号无效
     */
    String getMigratingTarget(int slot);

    /**
     * 设置槽位迁移状态
     *
     * @param slot      槽位号
     * @param targetNodeId 目标节点ID（null表示取消迁移状态）
     * @throws IllegalArgumentException 如果槽位号无效
     */
    void setSlotMigrating(int slot, String targetNodeId);

    /**
     * 检查槽位是否正在导入中
     *
     * @param slot 槽位号
     * @return 是否正在导入
     * @throws IllegalArgumentException 如果槽位号无效
     */
    boolean isSlotImporting(int slot);

    /**
     * 获取槽位导入的源节点ID
     *
     * @param slot 槽位号
     * @return 源节点ID，如果未在导入则返回null
     * @throws IllegalArgumentException 如果槽位号无效
     */
    String getImportingSource(int slot);

    /**
     * 设置槽位导入状态
     *
     * @param slot      槽位号
     * @param sourceNodeId 源节点ID（null表示取消导入状态）
     * @throws IllegalArgumentException 如果槽位号无效
     */
    void setSlotImporting(int slot, String sourceNodeId);
}
