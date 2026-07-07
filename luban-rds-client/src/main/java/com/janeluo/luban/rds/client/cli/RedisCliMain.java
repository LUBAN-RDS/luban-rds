package com.janeluo.luban.rds.client.cli;

import java.util.ArrayList;
import java.util.List;

/**
 * LubanRDS 客户端 CLI 入口
 * <p>
 * 模仿 {@code redis-cli --cluster create} 的子集，用于远程编排集群搭建。
 * </p>
 *
 * <h3>用法</h3>
 * <pre>
 * java -cp luban-rds-client.jar com.janeluo.luban.rds.client.cli.RedisCliMain \
 *      --cluster create &lt;host1:port1&gt; &lt;host2:port2&gt; ... [--cluster-replicas N]
 * </pre>
 *
 * <h3>示例</h3>
 * <pre>
 * java ... RedisCliMain --cluster create \
 *      192.168.8.161:9736 192.168.8.161:9737 192.168.8.161:9738 \
 *      192.168.8.161:9739 192.168.8.161:9740 192.168.8.161:9741 \
 *      --cluster-replicas 1
 * </pre>
 *
 * @author janeluo
 * @since 1.0.0
 */
public class RedisCliMain {

    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 1;

    public static void main(String[] args) {
        try {
            run(args);
            System.exit(EXIT_OK);
        } catch (ClusterSetupException e) {
            System.err.println("[ERR] " + e.getMessage());
            System.exit(EXIT_ERROR);
        } catch (Exception e) {
            System.err.println("[ERR] 集群创建失败: " + e.getMessage());
            e.printStackTrace();
            System.exit(EXIT_ERROR);
        }
    }

    /**
     * 通过代码调用执行 CLI（不调用 {@code System.exit}，便于嵌入）
     * <p>
     * 解析参数并执行 {@code --cluster create}，任一步骤失败抛出
     * {@link ClusterSetupException}。仅打印帮助时正常返回。
     * </p>
     *
     * <pre>
     * RedisCliMain.run(new String[] {
     *     "--cluster", "create",
     *     "127.0.0.1:9736", "127.0.0.1:9737", "127.0.0.1:9738",
     *     "127.0.0.1:9739", "127.0.0.1:9740", "127.0.0.1:9741",
     *     "--cluster-replicas", "1"
     * });
     * </pre>
     *
     * @param args 命令行参数
     * @throws ClusterSetupException 任一步骤失败时抛出
     */
    public static void run(String[] args) {
        CliOptions options = parseArgs(args);
        if (options == null) {
            // 已打印帮助信息
            return;
        }
        if (options.nodes.isEmpty()) {
            printUsage();
            return;
        }
        new ClusterSetupCommand(options.nodes, options.replicas).execute();
    }

    /**
     * 解析后的 CLI 选项
     */
    static final class CliOptions {
        /** 位置参数：节点地址列表 */
        final List<NodeAddress> nodes;
        /** 每主节点的从节点数量 */
        final int replicas;

        CliOptions(List<NodeAddress> nodes, int replicas) {
            this.nodes = nodes;
            this.replicas = replicas;
        }
    }

    /**
     * 解析命令行参数
     *
     * @param args 原始参数
     * @return 解析结果；返回 {@code null} 表示仅需打印帮助（如 --help）
     */
    static CliOptions parseArgs(String[] args) {
        if (args == null || args.length == 0) {
            printUsage();
            return null;
        }

        boolean clusterMode = false;
        String clusterSubcommand = null;
        List<String> rawNodes = new ArrayList<>();
        int replicas = 0;
        boolean replicasSet = false;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-h":
                case "--help":
                    printUsage();
                    return null;

                case "--cluster":
                    if (i + 1 >= args.length) {
                        throw new ClusterSetupException("--cluster 需要指定子命令 (如 create)");
                    }
                    clusterMode = true;
                    clusterSubcommand = args[++i];
                    if (!"create".equalsIgnoreCase(clusterSubcommand)) {
                        throw new ClusterSetupException("暂不支持的 --cluster 子命令: " + clusterSubcommand
                                + "（当前仅支持 create）");
                    }
                    break;

                case "--cluster-replicas":
                    if (i + 1 >= args.length) {
                        throw new ClusterSetupException("--cluster-replicas 需要指定数值");
                    }
                    try {
                        replicas = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        throw new ClusterSetupException("--cluster-replicas 的值必须为整数: " + args[i]);
                    }
                    if (replicas < 0) {
                        throw new ClusterSetupException("--cluster-replicas 不能为负数: " + replicas);
                    }
                    replicasSet = true;
                    break;

                default:
                    if (arg.startsWith("-")) {
                        throw new ClusterSetupException("未知的选项: " + arg);
                    }
                    rawNodes.add(arg);
                    break;
            }
        }

        if (!clusterMode) {
            printUsage();
            return null;
        }

        if (!replicasSet && rawNodes.size() > 3 && rawNodes.size() % 3 == 0) {
            // 与 redis-cli 一致：当未显式指定且节点数为 3 的倍数时，默认 replicas=1
            // 但这里保持显式优先，仅作提示不做隐式赋值，避免误判
            System.out.println("提示: 未指定 --cluster-replicas，默认为 0（无从节点）");
        }

        List<NodeAddress> nodes = new ArrayList<>(rawNodes.size());
        for (String raw : rawNodes) {
            nodes.add(NodeAddress.parse(raw));
        }

        // 校验节点数与 replicas 的关系
        if (!nodes.isEmpty()) {
            ClusterSetupCommand.computeMasterCount(nodes.size(), replicas);
        }

        return new CliOptions(nodes, replicas);
    }

    /**
     * 打印使用说明
     */
    private static void printUsage() {
        System.out.println("LubanRDS 集群管理 CLI");
        System.out.println();
        System.out.println("用法:");
        System.out.println("  java -cp luban-rds-client.jar com.janeluo.luban.rds.client.cli.RedisCliMain \\");
        System.out.println("       --cluster create <host:port> <host:port> ... [--cluster-replicas N]");
        System.out.println();
        System.out.println("选项:");
        System.out.println("  --cluster create             创建集群（必需）");
        System.out.println("  --cluster-replicas <N>       每个主节点的从节点数量（默认 0）");
        System.out.println("  -h, --help                   显示帮助信息");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  # 创建 3 主 3 从集群");
        System.out.println("  java ... RedisCliMain --cluster create \\");
        System.out.println("       192.168.8.161:9736 192.168.8.161:9737 192.168.8.161:9738 \\");
        System.out.println("       192.168.8.161:9739 192.168.8.161:9740 192.168.8.161:9741 \\");
        System.out.println("       --cluster-replicas 1");
        System.out.println();
        System.out.println("  # 创建 3 主 0 从集群");
        System.out.println("  java ... RedisCliMain --cluster create \\");
        System.out.println("       192.168.8.161:9736 192.168.8.161:9737 192.168.8.161:9738");
    }
}
