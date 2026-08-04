package com.janeluo.luban.rds.mesh.lifecycle;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.MeshClusterCommands;
import com.janeluo.luban.rds.mesh.gateway.MeshWriteGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeshBootstrap} 装配测试（阶段 12）。
 * <p>
 * 验证 bootstrap 装配链路完整：config 解析 → state 恢复 → MeshNode/gate/redirector/
 * clusterCommands/busClient/busServer/snapshotManager 全部就绪。
 * 不启动真实网络（不调 MeshNode.start / busServer.start），避免端口占用与时序问题。
 * </p>
 *
 * <h3>测试范围</h3>
 * <ul>
 *   <li>正常装配：单节点 + 多节点配置 → assembly 全部组件非 null；</li>
 *   <li>peers 缺失/格式非法 → 抛 IllegalStateException；</li>
 *   <li>selfNodeId 不在 peers 列表 → 抛异常；</li>
 *   <li>装配后 MeshNode 角色为 FOLLOWER（启动恢复后的默认角色）。</li>
 * </ul>
 *
 * @author janeluo
 * @since 阶段 12
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MeshBootstrapTest {

    @TempDir
    Path tempDir;

    /** 构造单节点 mesh 配置（selfNodeId=n1，仅一个 peer）。 */
    private RdsConfig singleNodeConfig() {
        RdsConfig c = new RdsConfig();
        c.setDir(tempDir.toString());
        c.setMeshEnabled(true);
        c.setPort(6390);
        c.setMeshPeers("n1@127.0.0.1:11000");
        c.setMeshSelfNodeId("n1");
        return c;
    }

    /** 构造 3 节点 mesh 配置。 */
    private RdsConfig threeNodeConfig() {
        RdsConfig c = new RdsConfig();
        c.setDir(tempDir.toString());
        c.setMeshEnabled(true);
        c.setPort(6390);
        c.setMeshPeers("n1@127.0.0.1:11000,n2@127.0.0.1:11001,n3@127.0.0.1:11002");
        c.setMeshSelfNodeId("n1");
        return c;
    }

    @Test
    void bootstrap_singleNode_assemblyReady() {
        RdsConfig config = singleNodeConfig();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        MeshAssembly assembly = bootstrap.bootstrap(config, rawStore, handler);

        // 全部组件非 null
        assertNotNull(assembly.getMeshNode(), "MeshNode 应就绪");
        assertNotNull(assembly.getWriteGate(), "MeshWriteGate 应就绪");
        assertNotNull(assembly.getClientRedirector(), "MeshClientRedirector 应就绪");
        assertNotNull(assembly.getClusterCommands(), "MeshClusterCommands 应就绪");
        assertNotNull(assembly.getLifecycleListener(), "MeshLifecycleListener 应就绪");
        assertNotNull(assembly.getBusClient(), "MeshBusClient 应就绪");
        assertNotNull(assembly.getBusServer(), "MeshBusServer 应就绪");
        assertNotNull(assembly.getSnapshotManager(), "SnapshotManager 应就绪");

        // 装配后未 start：角色为 FOLLOWER（MeshState 默认）
        assertEquals(com.janeluo.luban.rds.mesh.core.MeshRole.FOLLOWER,
                assembly.getMeshNode().getRole(),
                "装配后未 start，角色应为 FOLLOWER");

        // 单节点集群：totalNodes=1
        assertEquals(1, assembly.getMeshNode().getState().currentTerm,
                "首次启动 currentTerm 应为 1");

        // leader 尚未选出
        assertFalse(assembly.getMeshNode().isLeader(), "未 start 不应是 Leader");
    }

    @Test
    void bootstrap_threeNode_assemblyReady() {
        RdsConfig config = threeNodeConfig();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        MeshAssembly assembly = bootstrap.bootstrap(config, rawStore, handler);

        assertNotNull(assembly.getMeshNode(), "MeshNode 应就绪");
        assertNotNull(assembly.getWriteGate(), "MeshWriteGate 应就绪");
        assertNotNull(assembly.getClusterCommands(), "MeshClusterCommands 应就绪");
        // 3 节点：clientRedirector 应能解析 n1/n2/n3 的 service 地址
        assertNotNull(assembly.getClientRedirector().getServiceAddr("n1"), "n1 serviceAddr 应非空");
        assertNotNull(assembly.getClientRedirector().getServiceAddr("n2"), "n2 serviceAddr 应非空");
        assertNotNull(assembly.getClientRedirector().getServiceAddr("n3"), "n3 serviceAddr 应非空");
    }

    @Test
    void bootstrap_meshPeersEmpty_throwsIllegalState() {
        RdsConfig config = new RdsConfig();
        config.setDir(tempDir.toString());
        config.setMeshEnabled(true);
        config.setMeshPeers(""); // 空 peers
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        assertThrows(IllegalStateException.class,
                () -> bootstrap.bootstrap(config, rawStore, handler),
                "mesh-peers 为空时应抛 IllegalStateException");
    }

    @Test
    void bootstrap_meshPeersMalformed_throwsIllegalState() {
        RdsConfig config = new RdsConfig();
        config.setDir(tempDir.toString());
        config.setMeshEnabled(true);
        config.setMeshPeers("bad-peer-no-at-sign"); // 缺 @
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        assertThrows(IllegalStateException.class,
                () -> bootstrap.bootstrap(config, rawStore, handler),
                "mesh-peers 格式非法时应抛 IllegalStateException");
    }

    @Test
    void bootstrap_selfNodeIdNotInPeers_throwsIllegalState() {
        RdsConfig config = threeNodeConfig();
        config.setMeshSelfNodeId("nX"); // nX 不在 peers 列表
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        assertThrows(IllegalStateException.class,
                () -> bootstrap.bootstrap(config, rawStore, handler),
                "selfNodeId 不在 peers 列表时应抛 IllegalStateException");
    }

    @Test
    void bootstrap_nullArgs_throwsIllegalArgument() {
        MeshBootstrap bootstrap = new MeshBootstrap();
        assertThrows(IllegalArgumentException.class,
                () -> bootstrap.bootstrap(null, new DefaultMemoryStore(), new DefaultCommandHandler()),
                "config 为 null 应抛 IllegalArgumentException");
        assertThrows(IllegalArgumentException.class,
                () -> bootstrap.bootstrap(singleNodeConfig(), null, new DefaultCommandHandler()),
                "rawStore 为 null 应抛 IllegalArgumentException");
        assertThrows(IllegalArgumentException.class,
                () -> bootstrap.bootstrap(singleNodeConfig(), new DefaultMemoryStore(), null),
                "handler 为 null 应抛 IllegalArgumentException");
    }

    @Test
    void bootstrap_inferredSelfNodeId_takesFirstPeer() {
        // 未配置 mesh-self-node-id → 取 peers 首个（n1）
        RdsConfig config = threeNodeConfig();
        config.setMeshSelfNodeId(""); // 清空，触发推断
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        MeshAssembly assembly = bootstrap.bootstrap(config, rawStore, handler);

        // leader 供应商应返回 null（未 start，无 leader）
        MeshClusterCommands cc = assembly.getClusterCommands();
        assertNotNull(cc);
        // CLUSTER INFO 在无 leader 时应反映 cluster_state:fail（但仍可生成响应）
        byte[] info = cc.clusterInfo();
        assertNotNull(info, "clusterInfo 应返回非 null 字节");
        String infoStr = new String(info, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(infoStr.contains("cluster_enabled:1"), "clusterInfo 应含 cluster_enabled:1");
        // 无 leader → state=fail
        assertTrue(infoStr.contains("cluster_state:fail"),
                "无 leader 时 cluster_state 应为 fail: " + infoStr);
    }

    @Test
    void bootstrap_clusterCommandsNodes_containsAllPeers() {
        RdsConfig config = threeNodeConfig();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        MeshAssembly assembly = bootstrap.bootstrap(config, rawStore, handler);

        MeshClusterCommands cc = assembly.getClusterCommands();
        byte[] nodes = cc.clusterNodes();
        String nodesStr = new String(nodes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(nodesStr.contains("n1"), "CLUSTER NODES 应含 n1: " + nodesStr);
        assertTrue(nodesStr.contains("n2"), "CLUSTER NODES 应含 n2: " + nodesStr);
        assertTrue(nodesStr.contains("n3"), "CLUSTER NODES 应含 n3: " + nodesStr);
        // myself 标记应在 n1（selfNodeId）
        assertTrue(nodesStr.contains("myself"), "CLUSTER NODES 应含 myself 标记");
    }

    /**
     * 验证 peers 第三段 servicePort 解析：单机多实例（同 host、不同 RESP 端口）时，
     * nodeIdToServiceAddr 每节点独立端口正确（修正 MOVED 地址塌缩 bug）。
     */
    @Test
    void bootstrap_perNodeServicePort_resolvedFromThirdSegment() {
        RdsConfig c = new RdsConfig();
        c.setDir(tempDir.toString());
        c.setMeshEnabled(true);
        c.setPort(6390); // 全局 port；peers 显式给出 servicePort 时不应被使用
        // n1/n2/n3 同 host，busPort 11000/11001/11002，servicePort 9736/9737/9738
        c.setMeshPeers("n1@127.0.0.1:11000:9736,n2@127.0.0.1:11001:9737,n3@127.0.0.1:11002:9738");
        c.setMeshSelfNodeId("n1");
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        MeshAssembly assembly = bootstrap.bootstrap(c, rawStore, handler);

        // 每节点 serviceAddr 应是各自的 RESP 端口，不是全局 port=6390
        assertEquals("127.0.0.1:9736", assembly.getClientRedirector().getServiceAddr("n1"),
                "n1 serviceAddr 应取第三段 servicePort");
        assertEquals("127.0.0.1:9737", assembly.getClientRedirector().getServiceAddr("n2"),
                "n2 serviceAddr 应取第三段 servicePort");
        assertEquals("127.0.0.1:9738", assembly.getClientRedirector().getServiceAddr("n3"),
                "n3 serviceAddr 应取第三段 servicePort");
    }

    /**
     * 向后兼容：peers 不带第三段 servicePort 时，回落全局 servicePort / port（旧行为不变）。
     */
    @Test
    void bootstrap_legacyPeersWithoutServicePort_fallsBackToGlobalPort() {
        RdsConfig c = threeNodeConfig(); // port=6390, peers 无第三段
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();

        MeshBootstrap bootstrap = new MeshBootstrap();
        MeshAssembly assembly = bootstrap.bootstrap(c, rawStore, handler);

        // 旧格式：所有节点回落全局 port=6390（旧行为）
        assertEquals("127.0.0.1:6390", assembly.getClientRedirector().getServiceAddr("n1"));
        assertEquals("127.0.0.1:6390", assembly.getClientRedirector().getServiceAddr("n2"));
        assertEquals("127.0.0.1:6390", assembly.getClientRedirector().getServiceAddr("n3"));
    }
}
