package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipMessageType;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 集群总线消息编解码器
 * <p>
 * 提供基于 Netty 的消息编解码功能，用于集群节点间的网络通信
 * </p>
 */
public class ClusterBusCodec {

    private static final Logger logger = LoggerFactory.getLogger(ClusterBusCodec.class);

    /**
     * 消息编码器
     * <p>
     * 将 GossipMessage 对象编码为字节流
     * </p>
     */
    public static class Encoder extends MessageToByteEncoder<GossipMessage> {

        @Override
        protected void encode(ChannelHandlerContext ctx, GossipMessage msg, ByteBuf out) throws Exception {
            if (msg == null) {
                logger.warn("尝试编码空消息");
                return;
            }

            try {
                byte[] data = msg.encode();
                out.writeBytes(data);
                
                if (logger.isDebugEnabled()) {
                    logger.debug("编码消息: type={}, length={}, sender={}",
                            msg.getType(), data.length, msg.getSenderNodeId());
                }
            } catch (Exception e) {
                logger.error("编码消息失败: type={}, error={}",
                        msg.getType(), e.getMessage(), e);
                throw e;
            }
        }
    }

    /**
     * 消息解码器
     * <p>
     * 将字节流解码为 GossipMessage 对象
     * 支持粘包和半包处理
     * </p>
     */
    public static class Decoder extends ByteToMessageDecoder {

        /**
         * 消息头长度
         */
        private static final int HEADER_LENGTH = GossipMessage.HEADER_LENGTH;

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            // 循环处理可能存在的多条消息（粘包）
            while (in.readableBytes() >= HEADER_LENGTH) {
                // 标记当前读取位置
                in.markReaderIndex();

                // 读取消息头
                // 跳过节点ID（40字节）
                in.skipBytes(GossipMessage.NODE_ID_LENGTH);

                // 读取消息类型
                byte typeCode = in.readByte();
                GossipMessageType type = GossipMessageType.fromCode(typeCode);
                if (type == null) {
                    logger.error("未知的消息类型编码: {}", typeCode);
                    in.clear();
                    return;
                }

                // 读取消息长度（大端序）
                int messageLength = in.readInt();

                // 检查消息长度是否合法
                if (messageLength < 0) {
                    logger.error("消息长度无效: {}", messageLength);
                    in.clear();
                    return;
                }

                // 检查是否有足够的可读字节
                int totalLength = HEADER_LENGTH + messageLength;
                if (in.readableBytes() < messageLength) {
                    // 半包，重置读取位置等待更多数据
                    in.resetReaderIndex();
                    return;
                }

                // 重置到消息起始位置
                in.resetReaderIndex();

                // 读取完整消息
                byte[] data = new byte[totalLength];
                in.readBytes(data);

                try {
                    // 解码消息
                    GossipMessage message = GossipMessage.parseMessage(data);
                    out.add(message);

                    if (logger.isDebugEnabled()) {
                        logger.debug("解码消息: type={}, length={}, sender={}",
                                message.getType(), totalLength, message.getSenderNodeId());
                    }
                } catch (Exception e) {
                    logger.error("解码消息失败: type={}, length={}, error={}",
                            type, totalLength, e.getMessage(), e);
                    // 继续处理下一条消息
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.error("解码器异常: {}", cause.getMessage(), cause);
            ctx.close();
        }
    }

    /**
     * 私有构造方法，防止实例化
     */
    private ClusterBusCodec() {
    }
}
