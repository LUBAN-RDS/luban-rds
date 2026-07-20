## 1. 基础设施

- [ ] 1.1 新增 `GossipMessageType.FAILOVER_RESULT (0x07)`，并在 `GossipMessage.createMessage` 工厂注册。
- [ ] 1.2 新增 `gossip/FailoverResultMessage.java`（含 winnerNodeId、newConfigEpoch、inheritedSlots BitSet 字段 + 编解码）。
- [ ] 1.3 `ClusterConfig` 新增 `getSlavesOfMaster(String masterNodeId)` 辅助方法（返回该 master 的所有 slave 列表）。

## 2. FailoverManager 选举状态机

- [ ] 2.1 新增 `gossip/FailoverManager.java`：定义 `FailoverState` 枚举（IDLE/REQUESTING/ELECTED/FAILED）、字段（state、electionEpoch、authVotes、requestDeadline、timeoutDeadline）。
- [ ] 2.2 实现 `tick(ClusterConfig, FailureDetector)`：检测本节点是否 slave 且其 master 处于 FAIL，满足则进入 REQUESTING 并计算退避截止时间（`gracePeriod + nodeIdHash % 500ms`）。
- [ ] 2.3 实现退避到期后广播 AUTH_REQUEST：自增 currentEpoch、构造 `FailoverAuthRequestMessage`、`busClient.broadcast`。
- [ ] 2.4 实现 `onAuthAck(FailoverAuthAckMessage)`：累计去重的不同 master 授权票数，达 `masterCount/2+1` 后调用 `performFailoverAndBroadcast`。
- [ ] 2.5 实现选举超时回退：`REQUESTING` 态超过 `2 * cluster-node-timeout` 则回 IDLE 并清票数。
- [ ] 2.6 实现 `performFailoverAndBroadcast`：复用 `ClusterCommandHandler.performFailover` 逻辑（抽取为 `ClusterConfig` 或工具方法），自增 currentEpoch/configEpoch，广播 `FailoverResultMessage`。

## 3. 投票授权（master 侧）

- [ ] 3.1 FailoverManager（或新 `VoteRegistry`）维护 `votesCast: Map<String, Long>`（被投 slaveId → currentEpoch）。
- [ ] 3.2 实现 `onAuthRequest(FailoverAuthRequestMessage)`：仅 master 节点处理，校验纪元、每纪元每 slave 一票、择优规则（configEpoch > replicationOffset > nodeId），授权则广播 `FailoverAuthAckMessage`。
- [ ] 3.3 幂等处理：同 currentEpoch 重复请求重发 ACK，不重复自增 currentEpoch。

## 4. 消息分发接入

- [ ] 4.1 `GossipProtocol` 新增 `handleFailoverAuthRequest` / `handleFailoverAuthAck` / `handleFailoverResult`，委托给 `FailoverManager`。
- [ ] 4.2 `GossipProtocol` 构造时创建 `FailoverManager`，提供 `getFailoverManager()` 访问器。
- [ ] 4.3 `ClusterBusHandler.handleMessage` 增加 `FAILOVER_AUTH_REQUEST` / `FAILOVER_AUTH_ACK` / `FAILOVER_RESULT` 三个 case 分支。
- [ ] 4.4 `GossipTask.run` 增加 `failoverManager.tick()` 调用（在 `checkAndBroadcastFail` 之后，确保 FAIL 状态已更新）。

## 5. 结果收敛

- [ ] 5.1 `FailoverManager.handleFailoverResult`：纪元裁决、winner 标记 MASTER、槽位重分配、原 master 降级 SLAVE、清 FAIL/PFAIL、自增本地 currentEpoch。
- [ ] 5.2 触发 `onTopologyChanged` 回调，持久化 nodes.conf。

## 6. 配置项

- [ ] 6.1 在集群配置解析处新增 `cluster-failover-grace-period`（默认 0），传入 `FailoverManager`。
- [ ] 6.2 在 `CLUSTER INFO` 输出补 `cluster_failover_grace_period` 字段（可选，便于排查）。

## 7. 测试

- [ ] 7.1 `FailoverManagerTest`：单元测试 IDLE→REQUESTING→ELECTED 状态流转、退避抖动、超时回退。
- [ ] 7.2 `FailoverManagerTest`：投票授权场景（首投、重复幂等、本纪元已投他 slave 拒绝、过期纪元拒绝）。
- [ ] 7.3 `FailoverManagerTest`：胜选后 performFailover 调用、currentEpoch/configEpoch 自增、FailoverResult 广播。
- [ ] 7.4 `FailoverManagerTest`：FailoverResult 收敛（winner 提权、原 master 降级、槽位转移、旧纪元忽略）。
- [ ] 7.5 扩展 `integration/ClusterFailoverTest`：单 master FAIL 后 slave 自动提升的端到端场景。
- [ ] 7.6 扩展 `integration/ClusterFailoverTest`：多 slave 竞争唯一胜选（构造两 slave 同时进入 REQUESTING）。
- [ ] 7.7 扩展 `integration/ClusterFailoverTest`：手动 `CLUSTER FAILOVER TAKEOVER` 与自动选举共存、互不干扰。
- [ ] 7.8 运行 `mvn test -pl luban-rds-cluster` 全量通过。

## 8. 文档

- [ ] 8.1 更新 `AGENTS.md` 第 10 节 Cluster 章节，补充自动故障转移流程与新增配置项。
- [ ] 8.2 更新 `luban-rds-bin/src/main/resources/luban-rds.conf` 模板，加入 `cluster-failover-grace-period` 注释。
