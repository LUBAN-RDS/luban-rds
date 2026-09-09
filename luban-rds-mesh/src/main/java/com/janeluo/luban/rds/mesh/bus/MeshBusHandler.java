package com.janeluo.luban.rds.mesh.bus;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.function.BiConsumer;

/**
 * Mesh 总线入站分发处理器（阶段 1：日志 + 回调；阶段 2：按 type 反序列化为 RPC 类转发给 MeshNode）。
 * <p>
 * 标注 {@link ChannelHandler.Sharable}，使 server（入站）与 client（出站）可共享同一实例
 * （上层通过 {@link #setMessageConsumer(BiConsumer)} 注册唯一的上层消息消费者）。
 * </p>
 * <p>
 * 设计上仿照 {@code ClusterBusHandler} 的入站处理位置，但阶段 1 不做 PING/PONG 等握手
 * 分支——所有收到的 {@link MeshFrame} 统一交由注册的 consumer 处理（典型由 MeshNode 注入）。
 * </p>
 */
@ChannelHandler.Sharable
public class MeshBusHandler extends SimpleChannelInboundHandler<MeshFrame> {

    private static final Logger logger = LoggerFactory.getLogger(MeshBusHandler.class);

    /**
     * 上层消息消费者：参数为 (fromNodeId, frame)。阶段 2 由 MeshNode 注入，按 type 反序列化 body。
     * volatile：构造后通过 setter 注入，保证 EventLoop 线程与主线程的可见性。
     */
    private volatile BiConsumer<String, MeshFrame> messageConsumer;

    /** 本节点 nodeId（MeshBootstrap 接线）；null = 未接线，跳过身份冲突检查。 */
    private volatile String selfNodeId;

    /** 身份冲突 ERROR 节流窗口（ms）：mis-config 下冲突帧随心跳 50ms 一条，不限流即刷屏。 */
    private static final long CONFLICT_LOG_INTERVAL_MS = 5_000;

    /** 上次身份冲突 ERROR 时刻（ms）。 */
    private volatile long lastConflictLogAt;

    /**
     * 接线本节点 nodeId，启用入站身份冲突检查。由 MeshBootstrap 在装配时调用一次。
     */
    public void setSelfNodeId(String selfNodeId) {
        this.selfNodeId = selfNodeId;
    }

    /**
     * 注册上层消息消费者。
     *
     * @param consumer 消费者；null 表示清除（仅打日志）
     */
    public void setMessageConsumer(BiConsumer<String, MeshFrame> consumer) {
        this.messageConsumer = consumer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MeshFrame frame) {
        String fromNodeId = frame.getSenderNodeId();

        // 每帧日志放 trace：心跳/复制帧 10~100/s，info 会淹没真正的业务日志
        logger.trace("收到 MeshFrame: type=0x{}, from={}, bodyLen={}, remote={}",
                Integer.toHexString(frame.getType() & 0xFF),
                fromNodeId,
                frame.getBodyLength(),
                formatRemoteAddress(ctx));

        // 身份冲突防线（9/9 事故）：两台机器配置了同一 mesh-node-id 时，"自称是我"的帧
        // 必然来自对方——转发进 Raft 层会导致投票/复制响应互相错记、matchIndex 永不推进、
        // leader 积压补发打爆直接内存。丢弃并显式 ERROR 指向配置根因。
        String self = this.selfNodeId;
        if (self != null && self.equals(fromNodeId)) {
            long now = System.currentTimeMillis();
            if (now - lastConflictLogAt >= CONFLICT_LOG_INTERVAL_MS) {
                lastConflictLogAt = now;
                logger.error("mesh 身份冲突：收到 senderNodeId={} 与本节点相同的帧 (type=0x{}, remote={})，"
                                + "该帧已丢弃——请检查 mesh-node-id 是否与其他节点配置重复",
                        fromNodeId, Integer.toHexString(frame.getType() & 0xFF), formatRemoteAddress(ctx));
            }
            return;
        }

        BiConsumer<String, MeshFrame> consumer = this.messageConsumer;
        if (consumer != null) {
            try {
                consumer.accept(fromNodeId, frame);
            } catch (Exception e) {
                logger.error("上层消费者处理 MeshFrame 失败: type=0x{}, from={}",
                        Integer.toHexString(frame.getType() & 0xFF), fromNodeId, e);
            }
        } else {
            // 阶段 1 未注册消费者时，仅日志（等价于 echo 前的占位）
            logger.debug("未注册 messageConsumer，MeshFrame 仅记录日志: {}", frame);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 连接生命周期降为 debug（MeshBusClient 侧已有 info 级的连接/断开日志）
        logger.debug("Mesh 总线连接建立: remote={}", formatRemoteAddress(ctx));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("Mesh 总线连接断开: remote={}", formatRemoteAddress(ctx));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Mesh 总线连接异常: remote={}", formatRemoteAddress(ctx), cause);
        ctx.close();
    }

    /**
     * 安全格式化远程地址，避免 remoteAddress 为 null 时抛异常。
     */
    private String formatRemoteAddress(ChannelHandlerContext ctx) {
        try {
            java.net.SocketAddress addr = ctx.channel().remoteAddress();
            if (addr instanceof InetSocketAddress) {
                InetSocketAddress inet = (InetSocketAddress) addr;
                return inet.getAddress() != null
                        ? inet.getAddress().getHostAddress() + ":" + inet.getPort()
                        : "unknown:" + inet.getPort();
            }
            return addr != null ? addr.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
