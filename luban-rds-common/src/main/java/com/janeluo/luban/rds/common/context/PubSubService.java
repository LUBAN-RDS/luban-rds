package com.janeluo.luban.rds.common.context;

/**
 * 发布订阅服务接口
 * 定义消息发布的核心功能
 */
public interface PubSubService {

    /**
     * 向指定频道发布消息
     *
     * @param channel 频道名称
     * @param message 消息内容
     * @return 接收到消息的客户端数量
     */
    int publish(String channel, String message);
}
