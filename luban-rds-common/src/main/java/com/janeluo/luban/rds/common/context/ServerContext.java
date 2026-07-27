package com.janeluo.luban.rds.common.context;

import com.janeluo.luban.rds.common.config.RdsConfig;

/**
 * 服务器上下文
 * 用于在各模块间共享信息提供者等全局组件
 */
public final class ServerContext {

    private static volatile InfoProvider infoProvider;
    private static volatile PubSubService pubSubService;
    private static volatile RdsConfig config;
    private static volatile AofRewriteCallback aofRewriteCallback;

    private ServerContext() {
    }

    /**
     * 设置信息提供者
     *
     * @param provider 信息提供者实例
     */
    public static void setInfoProvider(InfoProvider provider) {
        infoProvider = provider;
    }

    /**
     * 获取信息提供者
     *
     * @return 信息提供者实例
     */
    public static InfoProvider getInfoProvider() {
        return infoProvider;
    }

    /**
     * 设置发布订阅服务
     *
     * @param service 发布订阅服务实例
     */
    public static void setPubSubService(PubSubService service) {
        pubSubService = service;
    }

    /**
     * 获取发布订阅服务
     *
     * @return 发布订阅服务实例
     */
    public static PubSubService getPubSubService() {
        return pubSubService;
    }
    
    /**
     * 设置配置
     *
     * @param rdsConfig 配置实例
     */
    public static void setConfig(RdsConfig rdsConfig) {
        config = rdsConfig;
    }
    
    /**
     * 获取配置
     *
     * @return 配置实例
     */
    public static RdsConfig getConfig() {
        return config;
    }

    /**
     * 设置 AOF 重写回调。
     *
     * <p>AOF 重写命令（BGREWRITEAOF）由 {@code core} 模块的
     * {@code CommonCommandHandler} 处理，而 {@code AofPersistService.rewrite}
     * 位于 {@code persistence} 模块，{@code core} 无法直接依赖 {@code persistence}。
     * 通过此回调解耦：{@code NettyRedisServer} 在装配时将
     * {@code () -> aofPersistService.rewrite(memoryStore)} 注册为回调
     * （{@code memoryStore} 由回调闭包捕获），
     * {@code CommonCommandHandler.handleBgrewriteaof} 通过 {@link #getAofRewriteCallback()}
     * 获取并异步触发，避免跨模块依赖。
     *
     * @param callback AOF 重写回调，{@code null} 表示未启用 AOF 重写（no-op）
     */
    public static void setAofRewriteCallback(AofRewriteCallback callback) {
        aofRewriteCallback = callback;
    }

    /**
     * 获取 AOF 重写回调。
     *
     * @return AOF 重写回调，未注册时返回 {@code null}
     */
    public static AofRewriteCallback getAofRewriteCallback() {
        return aofRewriteCallback;
    }

    /**
     * AOF 重写回调接口。
     *
     * <p>封装 {@code AofPersistService.rewrite(MemoryStore)} 的触发动作，由 {@code server}
     * 模块在装配时通过闭包捕获 {@code memoryStore} 注入实现，使 {@code core} 模块的命令
     * 处理器能触发 AOF 重写而不直接依赖 {@code persistence} 模块，亦不引入
     * {@code common -> core} 的反向依赖。
     */
    @FunctionalInterface
    public interface AofRewriteCallback {
        /**
         * 触发 AOF 重写，基于当前内存存储重建 AOF 文件。
         */
        void rewrite();
    }
}
