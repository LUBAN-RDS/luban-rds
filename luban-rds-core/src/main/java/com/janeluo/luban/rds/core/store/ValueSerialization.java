package com.janeluo.luban.rds.core.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * 存储值对象的序列化/反序列化工具（迁移与 RESTORE 共用，单一白名单来源）。
 * <p>
 * 使用 Java 原生序列化跨节点传输值对象。反序列化侧必须使用
 * {@link #deserialize(byte[])} 的统一白名单过滤（仅允许基本类型、数组、字符串、
 * 常用集合与项目内部可序列化类型），拒绝任意其他类，防止跨节点反序列化 RCE。
 * </p>
 * <p>
 * 注意：{@code SlotMigrationManager}/{@code MigrateCommandHandler} 与 core 模块的
 * RESTORE 命令处理器均委托本工具，保证白名单单一来源，避免两处漂移。
 * </p>
 */
public final class ValueSerialization {

    private ValueSerialization() {
    }

    /**
     * 序列化值对象。
     *
     * @param value 值对象
     * @return 序列化字节
     * @throws IOException 序列化失败（对象不可序列化等）
     */
    public static byte[] serialize(Object value) throws IOException {
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
     * 反序列化值对象（带白名单过滤）。
     *
     * @param data 序列化数据
     * @return 值对象，data 为 null 或空时返回 null
     * @throws IOException            反序列化 IO 异常
     * @throws ClassNotFoundException 类未找到异常
     */
    public static Object deserialize(byte[] data) throws IOException, ClassNotFoundException {
        if (data == null || data.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            // 反序列化白名单（JDK 9+ ObjectInputFilter）：仅允许基本类型、数组、字符串、
            // 常用集合，以及本项目内部可序列化类型（使 zset/stream 等可跨节点迁移）。
            // 拒绝任意其他类的反序列化，防止跨节点反序列化 RCE。
            // 安全考量：迁移是受信任的集群内部操作（节点间已建立总线连接），且仅允许
            // 项目自身包前缀，对其他包仍 REJECTED，RCE 攻击面不扩大。
            ois.setObjectInputFilter(filterInfo -> {
                Class<?> clazz = filterInfo.serialClass();
                if (clazz == null) {
                    // 非类过滤（如数组长度、深度），允许
                    return ObjectInputFilter.Status.UNDECIDED;
                }
                if (clazz.isPrimitive() || clazz.isArray()) {
                    return ObjectInputFilter.Status.ALLOWED;
                }
                String name = clazz.getName();
                if (name.startsWith("java.lang.") || name.startsWith("java.util.")
                        || name.startsWith("java.math.")) {
                    return ObjectInputFilter.Status.ALLOWED;
                }
                // 允许本项目内部可序列化类型（Stream/StreamEntry/StreamId 等），
                // 否则 zset/stream 跨节点迁移会因 filter REJECTED 而静默失败
                if (name.startsWith("com.janeluo.luban.rds.core.stream.")
                        || name.startsWith("com.janeluo.luban.rds.core.store.")) {
                    return ObjectInputFilter.Status.ALLOWED;
                }
                return ObjectInputFilter.Status.REJECTED;
            });
            return ois.readObject();
        }
    }
}
