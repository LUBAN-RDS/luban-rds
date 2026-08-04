package com.janeluo.luban.rds.mesh.bus;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mesh 总线帧编解码器（与 {@code ClusterBusCodec} 同构，但独立于 cluster，不复用代码）。
 * <p>
 * 帧格式（见 DESIGN.md §4.2）：45B 帧头 = 40B senderNodeId(ASCII) + 1B type + 4B length(大端)，
 * 无 term 字段；body ≤ {@link MeshFrame#MAX_BODY_LENGTH}（16MB）。
 * </p>
 * <p>
 * senderNodeId 支持 ≤ 40 字符的任意 ASCII 字符串：编码时短 nodeId 右填 NUL (0x00) 补齐到 40B，
 * 解码时 trim 尾部 NUL 还原。这样人工可读的 nodeId（如 {@code "node-9736"}）也能直接使用，
 * 无需强制 40 字符 hex。
 * </p>
 */
public final class MeshBusCodec {

    private static final Logger logger = LoggerFactory.getLogger(MeshBusCodec.class);

    private MeshBusCodec() {
    }

    /**
     * 编码器：{@link MeshFrame} → 字节流。
     * <p>
     * 写入 40B nodeId + 1B type + 4B length(大端) + body。编码前预检 body 长度，
     * 超过 {@link MeshFrame#MAX_BODY_LENGTH} 的帧直接丢弃（不写帧、不断连），
     * 避免对端解码器判为非法帧后关闭整条连接。
     * </p>
     * <p>
     * nodeId 字段：≤ 40 字符的 ASCII 字符串，不足 40 时右填 NUL (0x00) 补齐；
     * 超过 40 字符视为配置错误，丢弃本帧。
     * </p>
     */
    public static class Encoder extends MessageToByteEncoder<MeshFrame> {

        @Override
        protected void encode(ChannelHandlerContext ctx, MeshFrame msg, ByteBuf out) throws Exception {
            if (msg == null) {
                logger.warn("尝试编码空 MeshFrame");
                return;
            }

            byte[] body = msg.getBody();
            int bodyLength = body.length;

            // 编码预检：超限帧丢弃（保持连接）
            if (bodyLength > MeshFrame.MAX_BODY_LENGTH) {
                logger.error("拒绝发送超限 MeshFrame: type=0x{}, bodyLength={}, 上限={}，本帧丢弃（连接保持）",
                        Integer.toHexString(msg.getType() & 0xFF), bodyLength, MeshFrame.MAX_BODY_LENGTH);
                return;
            }

            // 校验 senderNodeId：≤ 40 字符（解码端固定读 40B）。短 nodeId 右填 NUL 补齐到 40B，
            // 使人工可读的 nodeId（如 "node-9736"）也能正确编解码（NUL 在 nodeId 中不会出现）。
            String senderNodeId = msg.getSenderNodeId();
            if (senderNodeId == null || senderNodeId.length() > MeshFrame.NODE_ID_LENGTH) {
                logger.error("senderNodeId 长度非法（上限 {}，实际 {}），丢弃本帧",
                        MeshFrame.NODE_ID_LENGTH, senderNodeId == null ? 0 : senderNodeId.length());
                return;
            }

            // 写 40B nodeId（ASCII 字节，不足 40 右填 NUL）
            byte[] nodeIdBytes = new byte[MeshFrame.NODE_ID_LENGTH];
            byte[] raw = senderNodeId.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(raw, 0, nodeIdBytes, 0, raw.length);
            // 剩余字节保持 0x00（NUL）填充
            out.writeBytes(nodeIdBytes);

            // 写 1B type
            out.writeByte(msg.getType());

            // 写 4B length（大端）—— ByteBuf.writeInt 即大端，与 cluster Decoder.readInt() 对称
            out.writeInt(bodyLength);

            // 写 body
            if (bodyLength > 0) {
                out.writeBytes(body);
            }

            if (logger.isDebugEnabled()) {
                logger.debug("编码 MeshFrame: type=0x{}, bodyLength={}, total={}, sender={}",
                        Integer.toHexString(msg.getType() & 0xFF), bodyLength,
                        MeshFrame.HEADER_LENGTH + bodyLength, senderNodeId);
            }
        }
    }

    /**
     * 解码器：字节流 → {@link MeshFrame}。
     * <p>
     * 支持粘包（循环解多帧）与半包（可读不足时 reset 等下次）。
     * type 非法或 length 越界（&lt;0 或 &gt; 16MB）视为毒帧，关闭连接避免反复触发。
     * </p>
     */
    public static class Decoder extends ByteToMessageDecoder {

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            // 循环处理粘包（一条 TCP 报文可能含多个帧）
            while (in.readableBytes() >= MeshFrame.HEADER_LENGTH) {
                in.markReaderIndex();

                // 读 40B nodeId（ASCII，可能右填 NUL），trim 尾部 NUL 还原原始 nodeId
                byte[] nodeIdBytes = new byte[MeshFrame.NODE_ID_LENGTH];
                in.readBytes(nodeIdBytes);
                String senderNodeId = trimNulPadding(nodeIdBytes);

                // 读 1B type
                byte typeCode = in.readByte();

                // 校验 type 合法性，非法关闭连接（毒帧防护）
                MessageType type;
                try {
                    type = MessageType.fromCode(typeCode);
                } catch (IllegalArgumentException e) {
                    logger.error("未知 mesh 消息类型编码: 0x{}，关闭连接",
                            Integer.toHexString(typeCode & 0xFF));
                    ctx.close();
                    return;
                }

                // 读 4B length（大端）—— readInt 即大端
                int bodyLength = in.readInt();

                // 校验 length 范围
                if (bodyLength < 0 || bodyLength > MeshFrame.MAX_BODY_LENGTH) {
                    logger.error("MeshFrame body 长度非法: {}，关闭连接", bodyLength);
                    ctx.close();
                    return;
                }

                // 半包：body 未到齐，reset 等下次
                if (in.readableBytes() < bodyLength) {
                    in.resetReaderIndex();
                    return;
                }

                // 读 body
                byte[] body = new byte[bodyLength];
                if (bodyLength > 0) {
                    in.readBytes(body);
                }

                MeshFrame frame = new MeshFrame(senderNodeId, type.getCode(), body);
                out.add(frame);

                if (logger.isDebugEnabled()) {
                    logger.debug("解码 MeshFrame: type={}, bodyLength={}, sender={}",
                            type, bodyLength, senderNodeId);
                }
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.error("MeshBus 解码器异常: {}", cause.getMessage(), cause);
            ctx.close();
        }
    }

    /**
     * 去除 nodeId 字节数组尾部的 NUL (0x00) 填充，还原为 ASCII 字符串。
     * <p>编码端对短 nodeId 右填 NUL 补齐到 40B，这里反向还原。NUL (0x00) 不在合法 nodeId 字符集内。</p>
     *
     * @param nodeIdBytes 固定 40B 的 nodeId 字段（可能含尾部 NUL 填充）
     * @return 去除尾部 NUL 后的 nodeId 字符串
     */
    private static String trimNulPadding(byte[] nodeIdBytes) {
        int len = nodeIdBytes.length;
        while (len > 0 && nodeIdBytes[len - 1] == 0x00) {
            len--;
        }
        return new String(nodeIdBytes, 0, len, StandardCharsets.US_ASCII);
    }
}
