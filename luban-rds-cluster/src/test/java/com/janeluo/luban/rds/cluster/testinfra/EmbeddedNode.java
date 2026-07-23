package com.janeluo.luban.rds.cluster.testinfra;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.bus.ClusterBusServer;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.protocol.Command;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 嵌入式集群节点，仅依赖 cluster + core 模块。
 * <p>
 * 提供内嵌的 RESP 服务器和集群总线，供客户端兼容性测试使用。
 * </p>
 */
public class EmbeddedNode {

    private final String nodeId;
    private final int port;
    private final int busPort;
    private final ClusterConfig clusterConfig;
    private final SlotManager slotManager;
    private final ClusterStateManager stateManager;
    private final ClusterBusClient busClient;
    private final GossipProtocol gossipProtocol;
    private final ClusterCommandHandler clusterCommandHandler;
    private final ClusterBusServer clusterBusServer;
    private final MemoryStore memoryStore;
    private final DefaultCommandHandler commandHandler;
    private final RedisProtocolParser protocolParser;
    private volatile Channel serverChannel;
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private volatile boolean started;

    public EmbeddedNode(int port) {
        this.port = port;
        this.busPort = port + 10000;
        this.nodeId = generateNodeId();

        this.clusterConfig = new ClusterConfig(nodeId);
        this.slotManager = new DefaultSlotManager(nodeId);
        this.stateManager = new ClusterStateManager(clusterConfig);

        ClusterNode myNode = new ClusterNode(nodeId);
        myNode.setIp("127.0.0.1");
        myNode.setPort(port);
        myNode.setBusPort(busPort);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);

        this.busClient = new ClusterBusClient(clusterConfig, null);
        this.gossipProtocol = new GossipProtocol(clusterConfig, busClient, 15000);
        busClient.setGossipProtocol(gossipProtocol);

        this.memoryStore = new DefaultMemoryStore();
        this.commandHandler = new DefaultCommandHandler();
        this.protocolParser = new RedisProtocolParser();

        this.clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, gossipProtocol, null, memoryStore);
        this.clusterBusServer = new ClusterBusServer(port, clusterConfig, gossipProtocol);
    }

    public void start() {
        if (started) {
            throw new IllegalStateException("EmbeddedNode already started");
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new RespHandler());
                        }
                    });
            serverChannel = b.bind(port).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IllegalStateException("Interrupted while binding port " + port, e);
        } catch (Exception e) {
            shutdownGroups();
            throw new IllegalStateException("Failed to bind port " + port, e);
        }
        clusterBusServer.start();
        gossipProtocol.start();
        started = true;
    }

    public void stop() {
        if (!started) {
            return;
        }
        gossipProtocol.stop();
        busClient.close();
        clusterBusServer.stop();
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverChannel = null;
        }
        shutdownGroups();
        started = false;
    }

    private void shutdownGroups() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
    }

    public ClusterConfig getClusterConfig() {
        return clusterConfig;
    }

    public SlotManager getSlotManager() {
        return slotManager;
    }

    public int getPort() {
        return port;
    }

    public String getNodeId() {
        return nodeId;
    }

    private static String generateNodeId() {
        byte[] bytes = new byte[20];
        ThreadLocalRandom.current().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(40);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    /**
     * 简易 RESP 协议处理器，将命令委托给 ClusterCommandHandler 和 DefaultCommandHandler。
     */
    private class RespHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf)) {
                return;
            }
            ByteBuf buf = (ByteBuf) msg;
            try {
                // 直接将 ByteBuf 传给协议解析器（它是 RESP 格式的原始字节）
                buf.markReaderIndex();
                Command command = protocolParser.parse(buf);
                if (command == null || command.getName() == null) {
                    buf.resetReaderIndex();
                    writeError(ctx, "ERR empty command");
                    return;
                }

                String cmd = command.getName().toUpperCase();
                String response;

                if ("CLUSTER".equalsIgnoreCase(cmd)) {
                    // ClusterCommandHandler.handle expects args[0] = subcommand (e.g., "SLOTS")
                    // but command.getArgs() includes "CLUSTER" as first element, so skip it
                    String[] subArgs = new String[command.getArgs().length - 1];
                    System.arraycopy(command.getArgs(), 1, subArgs, 0, subArgs.length);
                    response = clusterCommandHandler.handle(subArgs);
                } else if ("COMMAND".equalsIgnoreCase(cmd)) {
                    response = "+OK\r\n";
                } else if ("PING".equalsIgnoreCase(cmd)) {
                    response = "+PONG\r\n";
                } else {
                    Object result = commandHandler.handle(command.getName(), 0,
                            command.getArgs(), memoryStore);
                    response = formatResponse(result);
                }

                if (response != null) {
                    byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                    ByteBuf out = ctx.alloc().buffer(respBytes.length);
                    out.writeBytes(respBytes);
                    ctx.writeAndFlush(out);
                }
            } catch (Exception e) {
                writeError(ctx, "ERR " + e.getMessage());
            }
        }

        /**
         * 将 DefaultCommandHandler 的返回值格式化为 RESP 字符串。
         */
        private String formatResponse(Object result) {
            if (result == null) {
                return "$-1\r\n";
            }
            if (result instanceof String) {
                String s = (String) result;
                // 已经是 RESP 格式（以 + - : $ * 开头）
                if (s.startsWith("+") || s.startsWith("-") || s.startsWith(":")
                        || s.startsWith("$") || s.startsWith("*")) {
                    return s;
                }
                return "$" + s.length() + "\r\n" + s + "\r\n";
            }
            if (result instanceof Number) {
                return ":" + result + "\r\n";
            }
            if (result instanceof byte[]) {
                byte[] b = (byte[]) result;
                return "$" + b.length + "\r\n" + new String(b, StandardCharsets.UTF_8) + "\r\n";
            }
            return "$" + result.toString().length() + "\r\n" + result.toString() + "\r\n";
        }

        private void writeError(ChannelHandlerContext ctx, String err) {
            byte[] errBytes = ("-" + err + "\r\n").getBytes(StandardCharsets.UTF_8);
            ByteBuf out = ctx.alloc().buffer(errBytes.length);
            out.writeBytes(errBytes);
            ctx.writeAndFlush(out);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
