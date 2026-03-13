package com.janeluo.luban.rds.core.stream;

import java.util.List;

/**
 * 阻塞命令结果
 * 
 * <p>用于 BLPOP/BRPOP/XREAD/XREADGROUP 命令的阻塞模式。
 * 当命令需要阻塞等待新消息/元素时，返回此对象而不是普通的响应字符串。
 * 
 * <p>RedisServerHandler 会检测到此返回值类型，并执行阻塞等待逻辑。
 *
 * @author janeluo
 * @since 1.0.0
 */
public class BlockingResult {

    /**
     * 阻塞类型：BLPOP 命令
     */
    public static final String TYPE_BLPOP = "BLPOP";

    /**
     * 阻塞类型：BRPOP 命令
     */
    public static final String TYPE_BRPOP = "BRPOP";

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
     * 要监听的键名列表（List 或 Stream）
     */
    private final List<String> keys;

    /**
     * List 阻塞时的键数组（用于快速访问）
     */
    private final String[] keyArray;

    /**
     * 对应的起始 ID 列表（仅 Stream 使用）
     */
    private final List<StreamId> startIds;

    /**
     * 阻塞超时时间（毫秒），0 表示无限等待
     */
    private final long timeout;

    /**
     * 读取数量限制（仅 Stream 使用）
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
     * 创建 BLPOP 阻塞结果
     *
     * @param database 数据库索引
     * @param keys     键名数组
     * @param timeout  超时时间（秒）
     * @return 阻塞结果对象
     */
    public static BlockingResult forBLPop(int database, String[] keys, long timeout) {
        java.util.List<String> keyList = java.util.Arrays.asList(keys);
        return new BlockingResult(TYPE_BLPOP, database, keyList, keys, null, timeout * 1000, 0, null, null, false);
    }

    /**
     * 创建 BRPOP 阻塞结果
     *
     * @param database 数据库索引
     * @param keys     键名数组
     * @param timeout  超时时间（秒）
     * @return 阻塞结果对象
     */
    public static BlockingResult forBRPop(int database, String[] keys, long timeout) {
        java.util.List<String> keyList = java.util.Arrays.asList(keys);
        return new BlockingResult(TYPE_BRPOP, database, keyList, keys, null, timeout * 1000, 0, null, null, false);
    }

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
        return new BlockingResult(TYPE_XREAD, database, keys, null, startIds, timeout, count, null, null, false);
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
        return new BlockingResult(TYPE_XREADGROUP, database, keys, null, startIds, timeout, count, group, consumer, noack);
    }

    /**
     * 私有构造函数
     */
    private BlockingResult(String type, int database, List<String> keys, String[] keyArray,
                          List<StreamId> startIds, long timeout, int count,
                          String group, String consumer, boolean noack) {
        this.type = type;
        this.database = database;
        this.keys = keys;
        this.keyArray = keyArray;
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

    public String[] getKeyArray() {
        return keyArray;
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
     * 是否是 BLPOP 命令
     */
    public boolean isBLPop() {
        return TYPE_BLPOP.equals(type);
    }

    /**
     * 是否是 BRPOP 命令
     */
    public boolean isBRPop() {
        return TYPE_BRPOP.equals(type);
    }

    /**
     * 是否是 List 阻塞类型 (BLPOP/BRPOP)
     */
    public boolean isListBlocking() {
        return isBLPop() || isBRPop();
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

    /**
     * 是否是 Stream 阻塞类型 (XREAD/XREADGROUP)
     */
    public boolean isStreamBlocking() {
        return isXRead() || isXReadGroup();
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
