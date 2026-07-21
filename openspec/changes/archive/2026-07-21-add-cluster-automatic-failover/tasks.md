## 1. 基础设施

- [x] 1.1 新增 `GossipMessageType.FAILOVER_RESULT (0x08)`（0x07 已被 UPDATE 占用），并在 `GossipMessage.createMessage` 工厂注册。
- [x] 1.2 新增 `gossip/FailoverResultMessage.java`（含 winnerNodeId、newConfigEpoch、inheritedSlots BitSet 字段 + 编解码）。
- [x] 1.3 `ClusterConfig` 新增 `getSlavesOfMaster(String masterNodeId)` 辅助方法（返回该 master 的所有 slave 列表）。

## 2. FailoverManager 选举状态机

- [x] 2.1 新增 `gossip/FailoverManager.java` + `gossip/FailoverState.java`：定义 `FailoverState` 枚举（IDLE/REQUESTING/ELECTED）、字段（state、electionEpoch、authVotes、requestDeadline、timeoutDeadline、votesCast）。
- [x] 2.2 实现 `tick()`：检测本节点是否 slave 且其 master 处于 FAIL，满足则进入 REQUESTING 并计算退避截止时间（`gracePeriod + nodeIdHash % 500ms`）。
- [x] 2.3 实现退避到期后广播 AUTH_REQUEST：自增 currentEpoch、构造 `FailoverAuthRequestMessage`、`busClient.broadcast`。
- [x] 2.4 实现 `onAuthAck(FailoverAuthAckMessage)`：累计去重的不同 master 授权票数，达 `masterCount/2+1` 后调用 `performFailoverAndBroadcast`。
- [x] 2.5 实现选举超时回退：`REQUESTING` 态超过 `2 * cluster-node-timeout` 则回 IDLE 并清票数。
- [x] 2.6 实现 `performFailoverAndBroadcast`：复用 `performFailover`（抽取到 FailoverManager），自增 currentEpoch/configEpoch，广播 `FailoverResultMessage`。

## 3. 投票授权（master 侧）

- [x] 3.1 FailoverManager 维护 `votesCast: Map<String, Long>`（被投 slaveId → currentEpoch）。
- [x] 3.2 实现 `onAuthRequest(FailoverAuthRequestMessage)`：仅健康 master（非 FAIL）处理，校验纪元、每纪元每 slave 一票、择优规则，授权则广播 `FailoverAuthAckMessage`。
- [x] 3.3 幂等处理：同 currentEpoch 重复请求重发 ACK，不重复自增 currentEpoch。

## 4. 消息分发接入

- [x] 4.1 `GossipProtocol` 新增 `handleFailoverAuthRequest` / `handleFailoverAuthAck` / `handleFailoverResult`，委托给 `FailoverManager`。
- [x] 4.2 `GossipProtocol` 新增 `failoverManager` 字段 + `setFailoverManager`/`getFailoverManager` 访问器。
- [x] 4.3 `ClusterBusHandler.handleMessage` 增加 `FAILOVER_AUTH_REQUEST` / `FAILOVER_AUTH_ACK` / `FAILOVER_RESULT` 三个 case 分支 + 三个委托方法。
- [x] 4.4 `GossipTask.run` 增加 `failoverManager.tick()` 调用（在 `checkAndBroadcastFail` 之后，确保 FAIL 状态已更新）。

## 5. 结果收敛

- [x] 5.1 `FailoverManager.onFailoverResult`：纪元裁决、winner 标记 MASTER、槽位重分配、原 master 降级 SLAVE（清 FAIL/PFAIL）、自增本地 currentEpoch。
- [x] 5.2 触发 `onTopologyChanged` 回调，持久化 nodes.conf。

## 6. 配置项

- [x] 6.1 在 `RdsConfig` 新增 `clusterFailoverGracePeriod`（默认 0）字段 + getter/setter，在 `ConfigLoader` 增加 `cluster-failover-grace-period` 解析，在 `NettyRedisServer` 初始化 FailoverManager 时传入。
- [x] 6.2 （可选，未做）在 `CLUSTER INFO` 输出补 `cluster_failover_grace_period` 字段 —— 当前仅在启动日志打印 gracePeriod，INFO 命令增强留作后续。

## 7. 测试

- [x] 7.1 `FailoverManagerTest`：单元测试 IDLE→REQUESTING→ELECTED 状态流转、退避抖动、超时回退、master 未 FAIL 不触发、退避到期广播。
- [x] 7.2 `FailoverManagerTest`：投票授权场景（首投、重复幂等、本纪元已投他 slave 拒绝、过期纪元拒绝）。
- [x] 7.3 `FailoverManagerTest`：胜选后 performFailover 调用、currentEpoch/configEpoch 自增、FailoverResult 广播、未过半不提升。
- [x] 7.4 `FailoverManagerTest`：FailoverResult 收敛（winner 提权、原 master 降级、槽位转移、旧纪元忽略）+ 手动 performManualFailover 不广播 RESULT。
- [x] 7.5 扩展 `integration/ClusterFailoverTest`：单 master FAIL 后 slave 自动提升的端到端场景（TestCluster 多节点模拟器）。
- [x] 7.6 扩展 `integration/ClusterFailoverTest`：多 slave 场景至少一个接管（唯一性由单元测试 testRejectOtherSlaveInSameEpoch 覆盖）。
- [x] 7.7 扩展 `integration/ClusterFailoverTest`：手动 `performManualFailover` 不触发选举状态机、不广播 RESULT。
- [x] 7.8 运行 `mvn test -pl luban-rds-cluster` 全量通过（333 tests, 0 failures）。

## 8. 文档

- [x] 8.1 更新 `AGENTS.md` 第 10 节 Cluster 章节，补充自动故障转移流程与新增配置项。
- [x] 8.2 更新 `luban-rds-bin/src/main/resources/luban-rds.conf` 模板，加入 `cluster-failover-grace-period` 注释。
