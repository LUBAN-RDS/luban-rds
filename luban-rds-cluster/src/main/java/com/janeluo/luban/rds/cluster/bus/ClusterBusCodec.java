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

                // N-38：编码预检——超过对端解码器单帧上限的消息直接拒绝写出（不写帧、
                // 不关闭连接）。旧实现无条件写出，对端解码器判为非法帧后关闭整条连接，
                // 该连接上所有心跳/迁移中断 + 重连 churn。消息发送方（如 MIGRATE 的
                // sendAndWait）会因收不到 ACK 超时，得到明确错误而非连接被拔。
                int bodyLength = data.length - GossipMessage.HEADER_LENGTH;
                if (bodyLength > Decoder.MAX_BODY_LENGTH) {
                    logger.error("拒绝发送超限消息: type={}, bodyLength={}, 上限={}，本帧丢弃（连接保持）",
                            msg.getType(), bodyLength, Decoder.MAX_BODY_LENGTH);
                    return;
                }

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

        /**
         * 单条消息体最大长度（16MB），超过视为非法并关闭连接。
         * <p>
         * N-38：public 供编码预检（Encoder）与 MIGRATE 载荷预检（MigrateCommandHandler）
         * 共享同一上限，避免"编码端无预检、解码端超限断连"的不对称。
         * </p>
         */
        public static final int MAX_BODY_LENGTH = 16 * 1024 * 1024;

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
                    logger.error("未知的消息类型编码: {}，关闭连接", typeCode);
                    ctx.close();
                    return;
                }

                // 读取消息长度（大端序）
                int messageLength = in.readInt();

                // 检查消息长度是否合法
                if (messageLength < 0 || messageLength > MAX_BODY_LENGTH) {
                    logger.error("消息长度非法: {}，关闭连接", messageLength);
                    ctx.close();
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
                    // 解码失败（毒消息）关闭连接，避免反复触发
                    logger.error("解码消息失败: type={}, length={}, error={}，关闭连接",
                            type, totalLength, e.getMessage(), e);
                    ctx.close();
                    return;
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
