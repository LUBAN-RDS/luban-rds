package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterLink;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * 集群配置持久化器
 * <p>
 * 负责集群配置的保存和加载，格式兼容Redis nodes.conf：
 * <pre>
 * 格式说明：
 * &lt;nodeid&gt; &lt;ip:port@cport&gt; &lt;flags&gt; &lt;master&gt; &lt;ping-sent&gt; &lt;pong-recv&gt; &lt;config-epoch&gt; &lt;link-state&gt; &lt;slot&gt;
 * 
 * 字段说明：
 * - nodeid: 40字符的节点ID（十六进制）
 * - ip:port@cport: IP地址:端口@集群总线端口
 * - flags: 节点标志，多个用逗号分隔（如 master,myself）
 * - master: 主节点ID（从节点使用），"-"表示无主节点
 * - ping-sent: 最后发送PING的时间戳
 * - pong-recv: 最后收到PONG的时间戳
 * - config-epoch: 配置纪元
 * - link-state: 连接状态（connected/disconnected）
 * - slot: 槽位范围（如 0-5460），多个范围用空格分隔
 * </pre>
 * </p>
 */
public class ClusterConfigPersister {

    private static final Logger logger = LoggerFactory.getLogger(ClusterConfigPersister.class);

    /**
     * 节点ID长度
     */
    private static final int NODE_ID_LENGTH = 40;

    /**
     * 保存集群配置到文件
     *
     * @param config   集群配置
     * @param filePath 文件路径
     * @throws IOException 如果写入文件失败
     */
    public void save(ClusterConfig config, String filePath) throws IOException {
        if (config == null || filePath == null) {
            throw new IllegalArgumentException("配置和文件路径不能为空");
        }

        logger.info("保存集群配置到文件: {}", filePath);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            // 写入文件头注释
            writer.write("# Luban-RDS Cluster Configuration");
            writer.newLine();
            writer.write("# Generated at: " + System.currentTimeMillis());
            writer.newLine();
            writer.write("# Format: <nodeid> <ip:port@cport> <flags> <master> <ping-sent> <pong-recv> <config-epoch> <link-state> <slot>");
            writer.newLine();
            writer.newLine();

            // 写入当前配置纪元
            writer.write("# Current Epoch: " + config.getCurrentEpoch());
            writer.newLine();
            writer.write("# My Config Epoch: " + config.getConfigEpoch());
            writer.newLine();
            writer.newLine();

            // 写入每个节点（跳过 HANDSHAKE 和 NOADDR 状态的临时节点）
            int savedCount = 0;
            for (ClusterNode node : config.getAllNodes()) {
                if (node.hasState(ClusterNodeState.HANDSHAKE) || node.hasState(ClusterNodeState.NOADDR)) {
                    continue;
                }
                String line = formatNodeLine(node, config.getMyNodeId());
                writer.write(line);
                writer.newLine();
                savedCount++;
            }

            logger.info("集群配置保存成功，节点数: {}", savedCount);
        }
    }

    /**
     * 从文件加载集群配置
     *
     * @param filePath 文件路径
     * @return 集群配置对象
     * @throws IOException 如果读取文件失败
     */
    public ClusterConfig load(String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("文件路径不能为空");
        }

        logger.info("从文件加载集群配置: {}", filePath);

        ClusterConfig config = new ClusterConfig();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                
                // 跳过空行和注释
                if (line.isEmpty() || line.startsWith("#")) {
                    // 解析配置纪元注释
                    if (line.startsWith("# Current Epoch:")) {
                        try {
                            long epoch = Long.parseLong(line.substring("# Current Epoch:".length()).trim());
                            config.setCurrentEpoch(epoch);
                        } catch (NumberFormatException e) {
                            logger.warn("解析当前配置纪元失败: {}", line);
                        }
                    } else if (line.startsWith("# My Config Epoch:")) {
                        try {
                            long epoch = Long.parseLong(line.substring("# My Config Epoch:".length()).trim());
                            config.setConfigEpoch(epoch);
                        } catch (NumberFormatException e) {
                            logger.warn("解析我的配置纪元失败: {}", line);
                        }
                    }
                    continue;
                }

                // 解析节点行
                try {
                    ClusterNode node = parseNodeLine(line);
                    if (node != null) {
                        if (node.hasState(ClusterNodeState.HANDSHAKE)
                                || node.hasState(ClusterNodeState.NOADDR)) {
                            logger.debug("跳过加载临时节点: {}", node.getNodeId());
                            continue;
                        }

                        config.addNode(node);
                        
                        // 如果是 myself 节点，设置 myNodeId
                        if (node.isMyself()) {
                            config.setMyNodeId(node.getNodeId());
                        }
                        
                        // 设置槽位分配
                        BitSet slots = node.getSlots();
                        for (int i = slots.nextSetBit(0); i >= 0; i = slots.nextSetBit(i + 1)) {
                            config.setSlotOwner(i, node.getNodeId());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("解析节点行失败: {}, 错误: {}", line, e.getMessage());
                }
            }
        }

        logger.info("集群配置加载成功，节点数: {}", config.getNodeCount());
        return config;
    }

    /**
     * 生成节点ID（40字符十六进制）
     * <p>
     * 基于当前时间戳、随机数和主机名生成SHA1哈希
     * </p>
     *
     * @return 40字符的十六进制节点ID
     */
    public static String generateNodeId() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            
            // 使用多种信息生成唯一ID
            StringBuilder sb = new StringBuilder();
            sb.append(System.currentTimeMillis());
            sb.append("-");
            sb.append(ThreadLocalRandom.current().nextLong());
            sb.append("-");
            sb.append(System.getProperty("user.name", "unknown"));
            sb.append("-");
            sb.append(System.getProperty("os.name", "unknown"));
            
            byte[] hash = md.digest(sb.toString().getBytes());
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 应该总是可用，如果不可用则使用随机方法
            logger.warn("SHA-1算法不可用，使用随机方法生成节点ID");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < NODE_ID_LENGTH; i++) {
                sb.append(Integer.toHexString(ThreadLocalRandom.current().nextInt(16)));
            }
            return sb.toString();
        }
    }

    /**
     * 格式化节点行为字符串
     *
     * @param node     节点对象
     * @param myNodeId 当前节点ID
     * @return 格式化的节点行
     */
    private String formatNodeLine(ClusterNode node, String myNodeId) {
        StringBuilder sb = new StringBuilder();

        // 节点ID
        sb.append(node.getNodeId());

        // IP:端口@集群总线端口
        sb.append(" ");
        sb.append(node.getFullAddress());

        // 标志
        sb.append(" ");
        sb.append(formatFlags(node, myNodeId));

        // 主节点ID
        sb.append(" ");
        sb.append(node.getMasterNodeId() != null ? node.getMasterNodeId() : "-");

        // 最后发送PING时间
        sb.append(" ");
        sb.append(node.getLastPingTime());

        // 最后收到PONG时间
        sb.append(" ");
        sb.append(node.getLastPongTime());

        // 配置纪元
        sb.append(" ");
        sb.append(node.getConfigEpoch());

        // 连接状态
        sb.append(" ");
        ClusterLink link = node.getLink();
        sb.append(link != null && link.isConnected() ? "connected" : "disconnected");

        // 槽位范围
        String slots = formatSlots(node.getSlots());
        if (!slots.isEmpty()) {
            sb.append(" ");
            sb.append(slots);
        }

        return sb.toString();
    }

    /**
     * 格式化节点标志
     *
     * @param node     节点对象
     * @param myNodeId 当前节点ID
     * @return 标志字符串
     */
    private String formatFlags(ClusterNode node, String myNodeId) {
        Set<String> flags = new HashSet<>();

        // 检查是否为当前节点
        if (node.getNodeId().equals(myNodeId)) {
            flags.add("myself");
        }

        // 添加状态标志
        // 注意：FAIL/PFAIL 是运行时瞬时状态，不持久化到 nodes.conf。
        // 对齐 Redis 行为（clusterSaveConfig 不写 fail/fail?），重启后由故障检测器重新判定，
        // 否则全集群停止后重启，对端节点会以 FAIL 状态加载且永远无法恢复（无人发 PING 触发清除）。
        for (ClusterNodeState state : node.getState()) {
            switch (state) {
                case MASTER:
                    flags.add("master");
                    break;
                case SLAVE:
                    flags.add("slave");
                    break;
                case HANDSHAKE:
                    flags.add("handshake");
                    break;
                case NOADDR:
                    flags.add("noaddr");
                    break;
                case NOFLAGS:
                    flags.add("noflags");
                    break;
                // FAIL / PFAIL 故意不写入：运行时瞬时状态，不落盘
                default:
                    break;
            }
        }

        // 如果没有标志，添加 noflags
        if (flags.isEmpty()) {
            flags.add("noflags");
        }

        return String.join(",", flags);
    }

    /**
     * 格式化槽位范围为字符串
     *
     * @param slots 槽位位集合
     * @return 槽位范围字符串
     */
    private String formatSlots(BitSet slots) {
        if (slots.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int start = -1;
        int end = -1;

        for (int i = slots.nextSetBit(0); i >= 0; i = slots.nextSetBit(i + 1)) {
            if (start == -1) {
                start = i;
                end = i;
            } else if (i == end + 1) {
                end = i;
            } else {
                // 输出当前范围
                appendSlotRange(sb, start, end);
                start = i;
                end = i;
            }
        }

        // 输出最后一个范围
        if (start != -1) {
            appendSlotRange(sb, start, end);
        }

        return sb.toString().trim();
    }

    /**
     * 追加槽位范围到字符串构建器
     *
     * @param sb    字符串构建器
     * @param start 起始槽位
     * @param end   结束槽位
     */
    private void appendSlotRange(StringBuilder sb, int start, int end) {
        if (sb.length() > 0) {
            sb.append(" ");
        }
        if (start == end) {
            sb.append(start);
        } else {
            sb.append(start).append("-").append(end);
        }
    }

    /**
     * 解析节点行
     *
     * @param line 节点行字符串
     * @return 节点对象
     */
    private ClusterNode parseNodeLine(String line) {
        StringTokenizer st = new StringTokenizer(line);
        
        // 至少需要8个字段
        if (st.countTokens() < 8) {
            logger.warn("节点行字段不足: {}", line);
            return null;
        }

        // 解析节点ID
        String nodeId = st.nextToken();
        if (nodeId.length() != NODE_ID_LENGTH) {
            logger.warn("节点ID长度不正确: {}", nodeId);
            return null;
        }

        ClusterNode node = new ClusterNode(nodeId);

        // 解析地址 ip:port@cport
        String address = st.nextToken();
        parseAddress(node, address);

        // 解析标志
        String flags = st.nextToken();
        parseFlags(node, flags);

        // 解析主节点ID
        String masterId = st.nextToken();
        if (!"-".equals(masterId)) {
            node.setMasterNodeId(masterId);
        }

        // 解析时间戳
        try {
            node.setLastPingTime(Long.parseLong(st.nextToken()));
            node.setLastPongTime(Long.parseLong(st.nextToken()));
        } catch (NumberFormatException e) {
            logger.warn("解析时间戳失败: {}", line);
        }

        // 解析配置纪元
        try {
            node.setConfigEpoch(Long.parseLong(st.nextToken()));
        } catch (NumberFormatException e) {
            logger.warn("解析配置纪元失败: {}", line);
        }

        // 解析连接状态
        String linkState = st.nextToken();
        ClusterLink link = node.getLink();
        if (link != null) {
            link.setConnected("connected".equalsIgnoreCase(linkState));
        }

        // 解析槽位
        while (st.hasMoreTokens()) {
            String slotStr = st.nextToken();
            parseSlotRange(node, slotStr);
        }

        return node;
    }

    /**
     * 解析地址字符串
     *
     * @param node    节点对象
     * @param address 地址字符串（ip:port@cport）
     */
    private void parseAddress(ClusterNode node, String address) {
        try {
            // 格式: ip:port@cport
            int atIndex = address.indexOf('@');
            int colonIndex = address.indexOf(':');

            if (colonIndex > 0) {
                String ip = address.substring(0, colonIndex);
                node.setIp(ip);

                if (atIndex > 0) {
                    int port = Integer.parseInt(address.substring(colonIndex + 1, atIndex));
                    int busPort = Integer.parseInt(address.substring(atIndex + 1));
                    node.setPort(port);
                    node.setBusPort(busPort);
                } else {
                    int port = Integer.parseInt(address.substring(colonIndex + 1));
                    node.setPort(port);
                    node.setBusPort(port + 10000); // 默认集群总线端口
                }
            }
        } catch (Exception e) {
            logger.warn("解析地址失败: {}", address);
        }
    }

    /**
     * 解析标志字符串
     *
     * @param node  节点对象
     * @param flags 标志字符串
     */
    private void parseFlags(ClusterNode node, String flags) {
        String[] flagArray = flags.split(",");
        for (String flag : flagArray) {
            switch (flag.toLowerCase()) {
                case "myself":
                    node.addState(ClusterNodeState.MYSELF);
                    break;
                case "master":
                    node.addState(ClusterNodeState.MASTER);
                    break;
                case "slave":
                    node.addState(ClusterNodeState.SLAVE);
                    break;
                // fail / fail? 是运行时瞬时状态，不应从 nodes.conf 恢复。
                // 兼容修复前落盘的旧文件：忽略这两个标志，让故障检测器在运行时重新判定，
                // 避免全集群重启后对端节点以 FAIL 状态加载导致死锁。
                case "fail":
                case "fail?":
                    logger.debug("忽略历史 nodes.conf 中的瞬时状态标志: {}, nodeId={}", flag, node.getNodeId());
                    break;
                case "handshake":
                    node.addState(ClusterNodeState.HANDSHAKE);
                    break;
                case "noaddr":
                    node.addState(ClusterNodeState.NOADDR);
                    break;
                case "noflags":
                    node.addState(ClusterNodeState.NOFLAGS);
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 解析槽位范围
     *
     * @param node    节点对象
     * @param slotStr 槽位字符串（如 "0-5460" 或 "5461"）
     */
    private void parseSlotRange(ClusterNode node, String slotStr) {
        try {
            int dashIndex = slotStr.indexOf('-');
            if (dashIndex > 0) {
                // 范围格式
                int start = Integer.parseInt(slotStr.substring(0, dashIndex));
                int end = Integer.parseInt(slotStr.substring(dashIndex + 1));
                node.addSlotRange(start, end);
            } else {
                // 单个槽位
                int slot = Integer.parseInt(slotStr);
                node.addSlot(slot);
            }
        } catch (NumberFormatException e) {
            logger.warn("解析槽位失败: {}", slotStr);
        }
    }

    /**
     * 字节数组转十六进制字符串
     *
     * @param bytes 字节数组
     * @return 十六进制字符串
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 线程本地随机数生成器
     */
    private static class ThreadLocalRandom {
        private static final java.util.Random random = new java.util.Random();

        public static java.util.Random current() {
            return random;
        }
    }
}
