package com.janeluo.luban.rds.mesh.core;

import com.janeluo.luban.rds.mesh.lifecycle.MeshConfigPersister;

import java.io.IOException;

/**
 * 基于文件的 {@link PersistentStateStore} 实现（DESIGN §7.4）。
 *
 * <p>委托给 {@link MeshConfigPersister}，后者负责 raft-nodes.conf 的原子写
 * （tmp + fsync + ATOMIC_MOVE）与启动加载。<b>文件损坏抛异常</b>（不静默重置 term）。</p>
 *
 * <h3>异常转换</h3>
 * <p>{@link MeshConfigPersister#save} 抛 {@link IOException} 时包装为 {@link RuntimeException}
 * （保持接口契约「persist 失败抛 RuntimeException」），便于调用方在 persistHook 内统一 catch。</p>
 */
public class FileBasedPersistentStateStore implements PersistentStateStore {

    private final MeshConfigPersister persister;

    public FileBasedPersistentStateStore(MeshConfigPersister persister) {
        if (persister == null) {
            throw new IllegalArgumentException("persister 不能为 null");
        }
        this.persister = persister;
    }

    /** 便捷构造：直接传 dbDir。 */
    public FileBasedPersistentStateStore(String dbDir) {
        this(new MeshConfigPersister(dbDir));
    }

    @Override
    public void persist(MeshState state, String nodeId) {
        try {
            persister.save(state, nodeId);
        } catch (IOException e) {
            throw new RuntimeException("raft-nodes.conf 持久化失败: " + persister.getRaftNodesFile(), e);
        }
    }

    @Override
    public MeshState load(String nodeId) {
        try {
            return persister.load(nodeId);
        } catch (IOException e) {
            throw new RuntimeException("raft-nodes.conf 加载失败: " + persister.getRaftNodesFile(), e);
        }
    }

    /** 取底层 persister（供调用方访问 dump.rdb.index 辅助方法）。 */
    public MeshConfigPersister getPersister() {
        return persister;
    }
}
