package com.janeluo.luban.rds.mesh.client;

import com.janeluo.luban.rds.common.util.SlotUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MOVED / MESHDOWN 响应生成器（DESIGN §5.3 场景 3 / §11 决策 12）。
 * <p>
 * 把 {@link MovedToLeaderException} 转换成符合 Redis Cluster 协议的 RESP 错误响应，
 * 供 {@code RedisServerHandler} 的专用 catch 写回客户端。集群感知客户端（JedisCluster /
 * lettuce cluster）收到 MOVED 后会自动刷新拓扑并重连 Leader，实现「集群感知客户端零侵入」。
 * </p>
 *
 * <h3>响应格式（对齐 Redis Cluster）</h3>
 * <ul>
 *   <li><b>已知 Leader</b>：{@code "-MOVED <slot> <leaderServiceAddr>\r\n"}
 *     <ul>
 *       <li>{@code slot} = key 的真实 CRC16（{@link SlotUtils#getSlot}，0–16383）；
 *           <b>不用固定占位值</b>——部分客户端依赖 MOVED 中的 slot 更新本地路由缓存。</li>
 *       <li>{@code leaderServiceAddr} = {@code "ip:port"}（<b>service 端口</b>，6379/6380/6381；
 *           非 bus 端口 11000）。</li>
 *     </ul>
 *   </li>
 *   <li><b>未知/无 Leader</b>：{@code "-MESHDOWN The mesh cluster has no leader\r\n"}。
 *       建议客户端指数退避重试（默认 200ms 起步、上限 2s，DESIGN §5.3）。</li>
 * </ul>
 *
 * <h3>nodeId → serviceAddr 映射</h3>
 * <p>
 * 阶段 6 提供 {@code nodeIdToServiceAddr} 映射（{@code nodeId → "ip:port"}）。当
 * {@link MovedToLeaderException#getLeaderServiceAddr()} 已是完整 {@code "ip:port"} 时直接用之；
 * 若异常只携带 {@code leaderNodeId}（serviceAddr 为空），可经该映射补全。
 * 映射在 {@link MeshNode} 装配时（阶段 12 MeshBootstrap）注入。
 * </p>
 *
 * <h3>线程安全</h3>
 * 无状态（{@code nodeIdToServiceAddr} 在构造后只读），可在多线程 Netty handler 间共享。
 *
 * @author janeluo
 * @since 阶段 6
 */
public class MeshClientRedirector {

    /** 无 Leader 时返回的 MESHDOWN 响应（DESIGN §5.3）。 */
    public static final String MESHDOWN_RESPONSE = "-MESHDOWN The mesh cluster has no leader\r\n";

    /** nodeId → service 地址（{@code "ip:port"}，service 端口）映射；只读。 */
    private final Map<String, String> nodeIdToServiceAddr;

    /**
     * 默认构造器：无 nodeId→serviceAddr 映射，仅依据异常自身携带的 serviceAddr 生成响应。
     * <p>适用于 {@link MovedToLeaderException#getLeaderServiceAddr()} 已是完整 {@code "ip:port"} 的场景。</p>
     */
    public MeshClientRedirector() {
        this.nodeIdToServiceAddr = Collections.emptyMap();
    }

    /**
     * @param nodeIdToServiceAddr nodeId → service 地址（{@code "ip:port"}）映射；用于异常只携带
     *                            leaderNodeId 时补全 serviceAddr。null 等同空映射。
     */
    public MeshClientRedirector(Map<String, String> nodeIdToServiceAddr) {
        this.nodeIdToServiceAddr = nodeIdToServiceAddr == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(nodeIdToServiceAddr));
    }

    /**
     * 生成 MOVED 或 MESHDOWN 响应（DESIGN §5.3）。
     * <p>
     * 判定顺序：
     * <ol>
     *   <li>异常携带的 {@code leaderServiceAddr} 非空 → 直接用；</li>
     *   <li>否则用 {@code leaderNodeId} 查 {@link #nodeIdToServiceAddr} 映射；</li>
     *   <li>仍为空 → 无 Leader，返回 {@link #MESHDOWN_RESPONSE}。</li>
     * </ol>
     * slot 一律用 {@code key} 的真实 CRC16（{@link SlotUtils#getSlot}，0–16383）。
     * </p>
     *
     * @param e {@link MovedToLeaderException}
     * @return 完整 RESP 错误响应字符串（含 {@code \r\n}）
     */
    public String formatResponse(MovedToLeaderException e) {
        String leaderAddr = e.getLeaderServiceAddr();
        if (leaderAddr == null || leaderAddr.isEmpty()) {
            // 尝试用 leaderNodeId 补全 serviceAddr
            String leaderNodeId = e.getLeaderNodeId();
            if (leaderNodeId != null) {
                leaderAddr = nodeIdToServiceAddr.get(leaderNodeId);
            }
        }
        if (leaderAddr == null || leaderAddr.isEmpty()) {
            // 无 Leader → MESHDOWN
            return MESHDOWN_RESPONSE;
        }
        // 有 Leader：slot 用 key 的真实 CRC16（0-16383）
        int slot = SlotUtils.getSlot(e.getKey());
        return "-MOVED " + slot + " " + leaderAddr + "\r\n";
    }

    /**
     * 便捷重载：按 leaderServiceAddr + key 直接生成响应。
     *
     * @param leaderServiceAddr Leader 的 service 地址（{@code "ip:port"}）；无 Leader 时传 {@code null}
     * @param key               触发重定向的 key（用于算 slot）；未知时传 {@code null}（slot=0）
     * @return 完整 RESP 错误响应字符串
     */
    public String formatResponse(String leaderServiceAddr, String key) {
        return formatResponse(new MovedToLeaderException(null, leaderServiceAddr, key));
    }

    /**
     * 查询 nodeId 对应的 service 地址（{@code "ip:port"}）；不存在返回 {@code null}。
     * <p>供阶段 12 装配层 / 测试使用。</p>
     */
    public String getServiceAddr(String nodeId) {
        if (nodeId == null) {
            return null;
        }
        return nodeIdToServiceAddr.get(nodeId);
    }
}
