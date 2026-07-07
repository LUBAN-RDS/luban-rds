package com.janeluo.luban.rds.client.cli;

/**
 * RESP 回复判定与取值工具
 * <p>
 * {@link com.janeluo.luban.rds.client.NettyRedisClient#executeCommand} 通过
 * {@code RedisProtocolParser.parseResp} 解析回复，解析后类型字节已被剥离：
 * <ul>
 *   <li>{@code +OK} → {@code String "OK"}</li>
 *   <li>{@code -ERR ...} → {@code String "ERR ..."}（保留错误前缀）</li>
 *   <li>{@code :n} → {@code Integer}</li>
 *   <li>{@code $N\r\n...\r\n} → {@code String}（bulk 内容）或 {@code null}</li>
 *   <li>{@code *N ...} → {@code java.util.List}</li>
 * </ul>
 * 本工具统一处理上述回复，避免编排逻辑中散落 {@code instanceof} 判定。
 * </p>
 *
 * @author janeluo
 * @since 1.0.0
 */
public final class ReplySupport {

    private ReplySupport() {
    }

    /**
     * 断言回复为状态回复 {@code +OK}
     *
     * @param reply     原始回复
     * @param operation 操作描述，用于错误信息
     * @throws ClusterSetupException 回复非 OK 时抛出
     */
    public static void assertOk(Object reply, String operation) {
        if (reply == null) {
            throw new ClusterSetupException(operation + " 失败: 无响应（可能超时或连接断开）");
        }
        if (!(reply instanceof String)) {
            throw new ClusterSetupException(
                    operation + " 失败: 非预期响应类型 " + reply.getClass().getSimpleName() + " (" + reply + ")");
        }
        String s = (String) reply;
        if (!"OK".equals(s)) {
            throw new ClusterSetupException(operation + " 失败: " + s);
        }
    }

    /**
     * 要求回复为字符串（bulk 或简单字符串）
     *
     * @param reply     原始回复
     * @param operation 操作描述
     * @return 字符串内容
     * @throws ClusterSetupException 类型不符或为空时抛出
     */
    public static String requireString(Object reply, String operation) {
        if (reply == null) {
            throw new ClusterSetupException(operation + " 失败: 无响应");
        }
        if (!(reply instanceof String)) {
            throw new ClusterSetupException(
                    operation + " 失败: 非预期响应类型 " + reply.getClass().getSimpleName() + " (" + reply + ")");
        }
        return (String) reply;
    }

    /**
     * 要求回复为整数
     *
     * @param reply     原始回复
     * @param operation 操作描述
     * @return 整数值
     * @throws ClusterSetupException 类型不符时抛出
     */
    public static long requireLong(Object reply, String operation) {
        if (reply == null) {
            throw new ClusterSetupException(operation + " 失败: 无响应");
        }
        if (reply instanceof Number) {
            return ((Number) reply).longValue();
        }
        try {
            return Long.parseLong(reply.toString());
        } catch (NumberFormatException e) {
            throw new ClusterSetupException(operation + " 失败: 非预期响应 " + reply);
        }
    }
}
