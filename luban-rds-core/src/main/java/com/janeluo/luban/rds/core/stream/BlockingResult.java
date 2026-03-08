package com.janeluo.luban.rds.core.stream;

import java.util.List;

/**
 * 阻塞命令结果
 * 
 * <p>用于 XREAD 和 XREADGROUP 命令的阻塞模式。
 * 当命令需要阻塞等待新消息时，返回此对象而不是普通的响应字符串。
 * 
 * <p>RedisServerHandler 会检测到此返回值类型，并执行阻塞等待逻辑。
 *
 * @author janeluo
 * @since 1.0.0
 */
public class BlockingResult {

    /**
     * 阻塞类型：XREAD 命令
     */
    public static final String TYPE_XREAD = "XREAD";

    /**
     * 阻塞类型：XREADGROUP 命令
     */
    public static final String TYPE_XREADGROUP = "XREADGROUP";

    /**
     * 阻塞类型
     */
    private final String type;

    /**
     * 数据库索引
     */
    private final int database;

    /**
     * 要监听的流键名列表
     */
    private final List<String> keys;

    /**
     * 对应的起始 ID 列表
     */
    private final List<StreamId> startIds;

    /**
     * 阻塞超时时间（毫秒），0 表示无限等待
     */
    private final long timeout;

    /**
     * 读取数量限制
     */
    private final int count;

    /**
     * 消费者组名称（仅 XREADGROUP 使用）
     */
    private final String group;

    /**
     * 消费者名称（仅 XREADGROUP 使用）
     */
    private final String consumer;

    /**
     * 是否 NOACK（仅 XREADGROUP 使用）
     */
    private final boolean noack;

    /**
     * 创建 XREAD 阻塞结果
     *
     * @param database 数据库索引
     * @param keys     流键名列表
     * @param startIds 起始 ID 列表
     * @param timeout  超时时间（毫秒）
     * @param count    读取数量限制
     * @return 阻塞结果对象
     */
    public static BlockingResult forXRead(int database, List<String> keys, 
                                          List<StreamId> startIds, long timeout, int count) {
        return new BlockingResult(TYPE_XREAD, database, keys, startIds, timeout, count, null, null, false);
    }

    /**
     * 创建 XREADGROUP 阻塞结果
     *
     * @param database 数据库索引
     * @param keys     流键名列表
     * @param startIds 起始 ID 列表
     * @param timeout  超时时间（毫秒）
     * @param count    读取数量限制
     * @param group    消费者组名称
     * @param consumer 消费者名称
     * @param noack    是否 NOACK
     * @return 阻塞结果对象
     */
    public static BlockingResult forXReadGroup(int database, List<String> keys, 
                                               List<StreamId> startIds, long timeout, int count,
                                               String group, String consumer, boolean noack) {
        return new BlockingResult(TYPE_XREADGROUP, database, keys, startIds, timeout, count, group, consumer, noack);
    }

    /**
     * 私有构造函数
     */
    private BlockingResult(String type, int database, List<String> keys, 
                          List<StreamId> startIds, long timeout, int count,
                          String group, String consumer, boolean noack) {
        this.type = type;
        this.database = database;
        this.keys = keys;
        this.startIds = startIds;
        this.timeout = timeout;
        this.count = count;
        this.group = group;
        this.consumer = consumer;
        this.noack = noack;
    }

    public String getType() {
        return type;
    }

    public int getDatabase() {
        return database;
    }

    public List<String> getKeys() {
        return keys;
    }

    public List<StreamId> getStartIds() {
        return startIds;
    }

    public long getTimeout() {
        return timeout;
    }

    public int getCount() {
        return count;
    }

    public String getGroup() {
        return group;
    }

    public String getConsumer() {
        return consumer;
    }

    public boolean isNoack() {
        return noack;
    }

    /**
     * 是否是 XREAD 命令
     */
    public boolean isXRead() {
        return TYPE_XREAD.equals(type);
    }

    /**
     * 是否是 XREADGROUP 命令
     */
    public boolean isXReadGroup() {
        return TYPE_XREADGROUP.equals(type);
    }

    @Override
    public String toString() {
        return "BlockingResult{" +
                "type='" + type + '\'' +
                ", database=" + database +
                ", keys=" + keys +
                ", startIds=" + startIds +
                ", timeout=" + timeout +
                ", count=" + count +
                ", group='" + group + '\'' +
                ", consumer='" + consumer + '\'' +
                ", noack=" + noack +
                '}';
    }
}
