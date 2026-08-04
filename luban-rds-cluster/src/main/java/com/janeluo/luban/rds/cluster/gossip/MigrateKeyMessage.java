package com.janeluo.luban.rds.cluster.gossip;

import java.nio.charset.StandardCharsets;

/**
 * 键迁移请求消息
 * <p>
 * 用于 MIGRATE 命令通过集群总线将单个键传输到目标节点。
 * 携带键名、序列化后的键值、TTL、是否替换标志。
 * </p>
 * <p>
 * 消息体格式：
 * - 键名长度（4 字节，大端序）+ 键名（UTF-8）
 * - 键值长度（4 字节，大端序）+ 键值（原始字节）
 * - TTL（8 字节，大端序）
 * - replace 标志（1 字节，0=false，1=true）
 * - requestId（8 字节，大端序，P1-20；尾部追加，旧节点/旧消息忽略）
 * </p>
 */
public class MigrateKeyMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 要迁移的键名
     */
    private String key;

    /**
     * 序列化后的键值
     */
    private byte[] value;

    /**
     * 过期时间（毫秒），0 表示无过期
     */
    private long ttl;

    /**
     * 是否替换目标节点上的现有键
     */
    private boolean replace;

    /**
     * 目标数据库号（N-30：MIGRATE host port key db timeout 的 db 参数透传）。
     * <p>
     * 修复：旧实现解析出 destDb 后未进消息，目标节点 importKey 硬编码 db0，
     * 源 db3 键被删、目标 db0 出现键——数据可达性错乱。默认 0 兼容旧消息。
     * </p>
     */
    private int destDb;

    /**
     * 默认构造方法
     */
    public MigrateKeyMessage() {
        this.type = GossipMessageType.MIGRATE_KEY;
    }

    /**
     * 完整构造方法
     *
     * @param senderNodeId 发送者节点ID
     * @param key          键名
     * @param value        序列化后的键值
     * @param ttl          过期时间（毫秒）
     * @param replace      是否替换目标键
     * @param destDb       目标数据库号
     */
    public MigrateKeyMessage(String senderNodeId, String key, byte[] value, long ttl, boolean replace, int destDb) {
        super(senderNodeId, GossipMessageType.MIGRATE_KEY);
        this.key = key;
        this.value = value;
        this.ttl = ttl;
        this.replace = replace;
        this.destDb = destDb;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public byte[] getValue() {
        return value;
    }

    public void setValue(byte[] value) {
        this.value = value;
    }

    public long getTtl() {
        return ttl;
    }

    public void setTtl(long ttl) {
        this.ttl = ttl;
    }

    public boolean isReplace() {
        return replace;
    }

    public void setReplace(boolean replace) {
        this.replace = replace;
    }

    public int getDestDb() {
        return destDb;
    }

    public void setDestDb(int destDb) {
        this.destDb = destDb;
    }

    @Override
    protected byte[] encodeBody() {
        byte[] keyBytes = key != null ? key.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] valueBytes = value != null ? value : new byte[0];
        // 末尾追加 8 字节 requestId（P1-20）与 4 字节 destDb（N-30），向后兼容：
        // 旧节点忽略多余字节（requestId 之前布局保持不变）
        int totalLength = 4 + keyBytes.length + 4 + valueBytes.length + 8 + 1 + 8 + 4;
        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入键名长度和键名
        data[offset++] = (byte) (keyBytes.length >> 24);
        data[offset++] = (byte) (keyBytes.length >> 16);
        data[offset++] = (byte) (keyBytes.length >> 8);
        data[offset++] = (byte) keyBytes.length;
        if (keyBytes.length > 0) {
            System.arraycopy(keyBytes, 0, data, offset, keyBytes.length);
            offset += keyBytes.length;
        }

        // 写入键值长度和键值
        data[offset++] = (byte) (valueBytes.length >> 24);
        data[offset++] = (byte) (valueBytes.length >> 16);
        data[offset++] = (byte) (valueBytes.length >> 8);
        data[offset++] = (byte) valueBytes.length;
        if (valueBytes.length > 0) {
            System.arraycopy(valueBytes, 0, data, offset, valueBytes.length);
            offset += valueBytes.length;
        }

        // 写入 TTL（大端序）
        data[offset++] = (byte) (ttl >> 56);
        data[offset++] = (byte) (ttl >> 48);
        data[offset++] = (byte) (ttl >> 40);
        data[offset++] = (byte) (ttl >> 32);
        data[offset++] = (byte) (ttl >> 24);
        data[offset++] = (byte) (ttl >> 16);
        data[offset++] = (byte) (ttl >> 8);
        data[offset++] = (byte) ttl;

        // 写入 replace 标志
        data[offset++] = (byte) (replace ? 1 : 0);

        // 写入 requestId（8 字节大端序，P1-20 请求-响应关联）
        data[offset++] = (byte) (requestId >> 56);
        data[offset++] = (byte) (requestId >> 48);
        data[offset++] = (byte) (requestId >> 40);
        data[offset++] = (byte) (requestId >> 32);
        data[offset++] = (byte) (requestId >> 24);
        data[offset++] = (byte) (requestId >> 16);
        data[offset++] = (byte) (requestId >> 8);
        data[offset++] = (byte) requestId;

        // 写入 destDb（4 字节大端序，N-30）
        data[offset++] = (byte) (destDb >> 24);
        data[offset++] = (byte) (destDb >> 16);
        data[offset++] = (byte) (destDb >> 8);
        data[offset++] = (byte) destDb;

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 4) {
            throw new IllegalArgumentException("MIGRATE_KEY 消息体长度不足: 至少需要 4 字节，实际 "
                    + (body == null ? 0 : body.length));
        }
        int offset = 0;

        // 读取键名长度和键名
        int keyLen = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);
        if (keyLen < 0 || offset + keyLen > body.length) {
            throw new IllegalArgumentException("MIGRATE_KEY 消息键名段数据不足: keyLen=" + keyLen);
        }
        if (keyLen > 0) {
            byte[] keyBytes = new byte[keyLen];
            System.arraycopy(body, offset, keyBytes, 0, keyLen);
            this.key = new String(keyBytes, StandardCharsets.UTF_8);
            offset += keyLen;
        }

        // 读取键值长度和键值
        if (offset + 4 > body.length) {
            throw new IllegalArgumentException("MIGRATE_KEY 消息键值长度字段数据不足");
        }
        int valueLen = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);
        if (valueLen < 0 || offset + valueLen > body.length) {
            throw new IllegalArgumentException("MIGRATE_KEY 消息键值段数据不足: valueLen=" + valueLen);
        }
        if (valueLen > 0) {
            this.value = new byte[valueLen];
            System.arraycopy(body, offset, this.value, 0, valueLen);
            offset += valueLen;
        } else {
            this.value = new byte[0];
        }

        // 读取 TTL
        if (offset + 8 > body.length) {
            throw new IllegalArgumentException("MIGRATE_KEY 消息 TTL 字段数据不足");
        }
        this.ttl = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((body[offset++] & 0xFF));

        // 读取 replace 标志
        if (offset < body.length) {
            this.replace = (body[offset++] & 0xFF) == 1;
        }

        // 读取 requestId（P1-20，尾部追加，长度守卫向后兼容旧消息）
        if (offset + 8 <= body.length) {
            this.requestId = ((long) (body[offset++] & 0xFF) << 56) |
                    ((long) (body[offset++] & 0xFF) << 48) |
                    ((long) (body[offset++] & 0xFF) << 40) |
                    ((long) (body[offset++] & 0xFF) << 32) |
                    ((long) (body[offset++] & 0xFF) << 24) |
                    ((long) (body[offset++] & 0xFF) << 16) |
                    ((long) (body[offset++] & 0xFF) << 8) |
                    (body[offset++] & 0xFF);
        }

        // 读取 destDb（N-30，末尾追加，长度守卫向后兼容旧消息；缺省为 db0）
        if (offset + 4 <= body.length) {
            this.destDb = ((body[offset++] & 0xFF) << 24) |
                    ((body[offset++] & 0xFF) << 16) |
                    ((body[offset++] & 0xFF) << 8) |
                    (body[offset++] & 0xFF);
        }
    }

    @Override
    public String toString() {
        return "MigrateKeyMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", key='" + key + '\'' +
                ", valueSize=" + (value != null ? value.length : 0) +
                ", ttl=" + ttl +
                ", replace=" + replace +
                '}';
    }
}
