package com.janeluo.luban.rds.common.context;

/**
 * 服务器上下文
 * 用于在各模块间共享信息提供者等全局组件
 */
public final class ServerContext {

    private static volatile InfoProvider infoProvider;
    private static volatile PubSubService pubSubService;

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
}
