package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.ConfigLoader;
import com.janeluo.luban.rds.common.config.RdsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LubanRDS 服务器启动入口
 * 
 * 支持以下启动方式：
 * 1. 无参数启动：使用默认配置
 * 2. 指定端口启动：java -jar luban-rds.jar 9736
 * 3. 指定配置文件启动：java -jar luban-rds.jar --config /path/to/luban-rds.conf
 */
public class RedisServerMain {
    private static final Logger logger = LoggerFactory.getLogger(RedisServerMain.class);
    
    private static final String DEFAULT_CONFIG_FILE = "luban-rds.conf";

    public static void main(String[] args) {
        RdsConfig config = parseArgs(args);

        // 阶段 12：cluster / mesh 互斥校验（DESIGN §8：同一进程只能启用其一）。
        // 在构造 NettyRedisServer 之前拦截，给出清晰错误（构造函数也会再校验一次兜底）。
        if (config.isClusterEnabled() && config.isMeshEnabled()) {
            logger.error("cluster-enabled 与 mesh-enabled 不能同时启用（DESIGN §8 互斥），启动中止");
            System.exit(2);
        }

        // 创建并启动服务器
        final RedisServer server = new NettyRedisServer(config);
        
        try {
            // 启动服务器
            server.start();
            logger.info("LubanRDS 服务器启动成功，监听端口: {}", config.getPort());
            logger.info("持久化模式: {}, 数据目录: {}", config.getPersistMode(), config.getDir());
            
            // 注册关闭钩子，确保服务器能够优雅关闭
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("正在关闭 LubanRDS 服务器...");
                server.stop();
                logger.info("LubanRDS 服务器已停止");
            }));
            
            // 保持主线程运行
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("启动 LubanRDS 服务器失败", e);
            server.stop();
            System.exit(1);
        }
    }
    
    /**
     * 解析命令行参数
     */
    private static RdsConfig parseArgs(String[] args) {
        RdsConfig config = null;
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            if ("--config".equals(arg) || "-c".equals(arg)) {
                // 指定配置文件路径
                if (i + 1 < args.length) {
                    String configPath = args[++i];
                    logger.info("从配置文件加载: {}", configPath);
                    config = ConfigLoader.load(configPath);
                } else {
                    logger.error("--config 参数需要指定配置文件路径");
                    printUsage();
                    System.exit(1);
                }
            } else if ("--port".equals(arg) || "-p".equals(arg)) {
                // 指定端口
                if (i + 1 < args.length) {
                    if (config == null) {
                        config = new RdsConfig();
                    }
                    try {
                        int port = Integer.parseInt(args[++i]);
                        if (port > 0 && port <= 65535) {
                            config.setPort(port);
                        } else {
                            logger.error("无效的端口号: {}", port);
                            System.exit(1);
                        }
                    } catch (NumberFormatException e) {
                        logger.error("端口号格式错误: {}", args[i]);
                        System.exit(1);
                    }
                }
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                System.exit(0);
            } else if ("--mesh-enabled".equals(arg)) {
                // 阶段 12：启用 mesh 模式（无需参数值，存在即 yes）
                if (config == null) {
                    config = new RdsConfig();
                }
                config.setMeshEnabled(true);
                logger.info("启用 mesh 模式（--mesh-enabled）");
            } else if ("--mesh-peers".equals(arg)) {
                // 阶段 12：mesh peers 列表
                // 格式：nodeId1@host1:busPort1,nodeId2@host2:busPort2,...
                if (i + 1 < args.length) {
                    if (config == null) {
                        config = new RdsConfig();
                    }
                    config.setMeshPeers(args[++i]);
                    logger.info("mesh peers 已设置: {}", config.getMeshPeers());
                } else {
                    logger.error("--mesh-peers 参数需要指定 peers 列表");
                    printUsage();
                    System.exit(1);
                }
            } else if ("--mesh-self-node-id".equals(arg)) {
                // 阶段 12：本节点 nodeId
                if (i + 1 < args.length) {
                    if (config == null) {
                        config = new RdsConfig();
                    }
                    config.setMeshSelfNodeId(args[++i]);
                } else {
                    logger.error("--mesh-self-node-id 参数需要指定 nodeId");
                    System.exit(1);
                }
            } else if ("--mesh-bus-port".equals(arg)) {
                // 阶段 12：mesh 总线端口
                if (i + 1 < args.length) {
                    if (config == null) {
                        config = new RdsConfig();
                    }
                    try {
                        config.setMeshBusPort(Integer.parseInt(args[++i]));
                    } catch (NumberFormatException e) {
                        logger.error("--mesh-bus-port 参数需要整数端口");
                        System.exit(1);
                    }
                }
            } else if (!arg.startsWith("-")) {
                // 兼容旧版本：第一个非选项参数作为端口号
                try {
                    int port = Integer.parseInt(arg);
                    if (port > 0 && port <= 65535) {
                        if (config == null) {
                            config = new RdsConfig();
                        }
                        config.setPort(port);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("忽略无效参数: {}", arg);
                }
            }
        }
        
        // 如果没有指定配置，尝试从类路径加载默认配置
        if (config == null) {
            config = ConfigLoader.loadFromClasspath(DEFAULT_CONFIG_FILE);
        }
        
        return config;
    }
    
    /**
     * 打印使用说明
     */
    private static void printUsage() {
        System.out.println("LubanRDS - 轻量级 Redis 兼容服务器");
        System.out.println();
        System.out.println("用法: java -jar luban-rds.jar [选项]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  -c, --config <file>   指定配置文件路径");
        System.out.println("  -p, --port <port>     指定监听端口");
        System.out.println("  -h, --help            显示帮助信息");
        System.out.println("  --mesh-enabled                 启用 mesh 模式（与 cluster 互斥）");
        System.out.println("  --mesh-peers <peers>           mesh 节点列表（nodeId@host:busPort 逗号分隔）");
        System.out.println("  --mesh-self-node-id <id>       本节点 nodeId（未指定取 peers 首个）");
        System.out.println("  --mesh-bus-port <port>         mesh 总线端口");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  java -jar luban-rds.jar");
        System.out.println("  java -jar luban-rds.jar -p 9736");
        System.out.println("  java -jar luban-rds.jar -c /etc/luban-rds.conf");
        System.out.println("  java -jar luban-rds.jar -p 6379 --mesh-enabled \\");
        System.out.println("    --mesh-peers n1@127.0.0.1:11000,n2@127.0.0.1:11001,n3@127.0.0.1:11002 \\");
        System.out.println("    --mesh-self-node-id n1");
    }
}
