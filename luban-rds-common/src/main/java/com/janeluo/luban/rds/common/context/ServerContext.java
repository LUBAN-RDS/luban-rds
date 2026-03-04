package com.janeluo.luban.rds.common.context;

/**
 * 服务器上下文，用于在各模块间共享信息提供者等全局组件
 */
public class ServerContext {
    private static InfoProvider infoProvider;

    public static void setInfoProvider(InfoProvider provider) {
        infoProvider = provider;
    }

    public static InfoProvider getInfoProvider() {
        return infoProvider;
    }
}
