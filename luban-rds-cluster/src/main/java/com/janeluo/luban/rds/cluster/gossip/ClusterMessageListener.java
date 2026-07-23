package com.janeluo.luban.rds.cluster.gossip;

/**
 * 集群跨节点消息监听器。
 * <p>
 * 用于将集群总线（cluster bus）传播的消息（如跨节点 PUBLISH）投递给上层模块，
 * 避免 cluster 模块反向依赖 server 模块的具体实现（如 PubSubManager）。
 * </p>
 * <p>
 * 由 server 模块实现并通过 {@link GossipProtocol#setPublishListener(ClusterMessageListener)} 注入。
 * </p>
 */
@FunctionalInterface
public interface ClusterMessageListener {

    /**
     * 收到跨节点消息时调用。
     *
     * @param channel  频道名（PUBLISH 的 channel）
     * @param message  消息内容（PUBLISH 的 message 字节）
     * @param senderId 发送方节点ID
     */
    void onMessage(String channel, byte[] message, String senderId);
}
