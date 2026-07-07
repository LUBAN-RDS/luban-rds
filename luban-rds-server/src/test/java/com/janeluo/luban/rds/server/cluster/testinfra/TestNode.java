package com.janeluo.luban.rds.server.cluster.testinfra;

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
import com.janeluo.luban.rds.server.RedisServerHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

public class TestNode {

    private final TestNodeConfig config;
    private final ClusterConfig clusterConfig;
    private final SlotManager slotManager;
    private final ClusterStateManager stateManager;
    private final GossipProtocol gossipProtocol;
    private final ClusterCommandHandler clusterCommandHandler;
    private ClusterBusServer clusterBusServer;
    private volatile Channel serverChannel;
    private volatile EventLoopGroup bossGroup;
    private volatile EventLoopGroup workerGroup;
    private final MemoryStore memoryStore;
    private final DefaultCommandHandler commandHandler;
    private final RedisProtocolParser protocolParser;
    private volatile boolean started;

    public TestNode(TestNodeConfig config) {
        this.config = config;
        this.clusterConfig = new ClusterConfig(config.getNodeId());
        this.slotManager = new DefaultSlotManager(config.getNodeId());
        this.stateManager = new ClusterStateManager(clusterConfig);

        ClusterNode myNode = new ClusterNode(config.getNodeId());
        myNode.setIp(config.getIp());
        myNode.setPort(config.getPort());
        myNode.setBusPort(config.getBusPort());
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);

        this.gossipProtocol = new GossipProtocol(clusterConfig, null, 15000);
        this.clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, gossipProtocol, null);
        this.clusterBusServer = new ClusterBusServer(
                config.getPort(), clusterConfig, gossipProtocol);

        this.memoryStore = new DefaultMemoryStore();
        this.commandHandler = new DefaultCommandHandler();
        this.protocolParser = new RedisProtocolParser();
    }

    public void start() {
        if (started) {
            throw new IllegalStateException("TestNode already started");
        }
        System.out.println("启动测试节点 " + config.getNodeId() + " on port " + config.getPort());

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
                     RedisServerHandler handler = new RedisServerHandler(
                             memoryStore, commandHandler, protocolParser, 0,
                             config.isClusterEnabled(), clusterConfig, slotManager);
                     if (config.isClusterEnabled()) {
                         handler.setClusterCommandHandler(clusterCommandHandler);
                     }
                     ch.pipeline().addLast(handler);
                 }
             });
            serverChannel = b.bind(config.getPort()).sync().channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw new IllegalStateException("Interrupted while binding port " + config.getPort(), e);
        } catch (Exception e) {
            shutdownGroups();
            throw new IllegalStateException("Failed to bind port " + config.getPort(), e);
        }

        if (config.isClusterEnabled()) {
            clusterBusServer.start();
            gossipProtocol.start();
        }
        started = true;
    }

    public void stop() {
        if (!started) return;
        System.out.println("停止测试节点 " + config.getNodeId());
        if (config.isClusterEnabled()) {
            gossipProtocol.stop();
            clusterBusServer.stop();
        }
        closeServerChannel();
        shutdownGroups();
        started = false;
    }

    public void forceStop() {
        if (!started) return;
        if (config.isClusterEnabled()) {
            gossipProtocol.stop();
            clusterBusServer.stop();
        }
        closeServerChannel();
        shutdownGroups();
        started = false;
    }

    private void closeServerChannel() {
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            serverChannel = null;
        }
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

    public ClusterConfig getClusterConfig() { return clusterConfig; }
    public SlotManager getSlotManager() { return slotManager; }
    public ClusterCommandHandler getClusterCommandHandler() { return clusterCommandHandler; }
    public GossipProtocol getGossipProtocol() { return gossipProtocol; }
    public ClusterBusServer getClusterBusServer() { return clusterBusServer; }
    public TestNodeConfig getConfig() { return config; }
    public boolean isStarted() { return started; }
    public int getPort() { return config.getPort(); }
    public String getNodeId() { return config.getNodeId(); }
}
