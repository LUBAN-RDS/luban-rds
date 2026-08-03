## Why

集群自动故障转移（failover）在生产环境无法成功：当 master 真正宕机时，slave 进入选举后被立即取消，导致槽位无法迁移、客户端持续收到 MOVED 到已死 master 的错误，业务不可用。

根因：`GossipProtocol.handlePing()/handlePong()` 收到来自已 FAIL 节点的任何 PING/PONG 时，无条件调用 `failureDetector.clearNodeFailState()` 清除 FAIL 状态。这导致 master 宕机后其 FAIL 状态被一次短暂的 PONG 恢复清除，`FailoverManager.handleRequestingState()` 随即检测到 `!master.isFail()` 取消选举。

生产日志实证（`0803/11服停了之后系统错误/12/app_ig.log`，14:00:40）：
1. `14:00:40.684` 节点 `bd7a7e...`(11服) 被标记 FAIL
2. `14:00:40.883` slave `53e4d6...`(12服) 进入选举，453ms 退避
3. `14:00:41.670` `FailureDetector:196` 收到一个 PONG，**清除 FAIL 状态**
4. `14:00:41.883` `FailoverManager:209` "原 master 已恢复，取消选举"

该模式在 13:43、14:00、14:05 反复出现，master 实际一直宕机但 failover 始终无法完成。

## What Changes

- 节点被标记 FAIL 后，MUST 保持 FAIL 状态至少 `NODE_TIMEOUT * 2`（对齐 Redis Cluster `cluster.c` 中 `CLUSTER_TODO` 的 FAIL 保护期语义），保护期内收到的 PING/PONG 不清除 FAIL。
- `FailureDetector.clearNodeFailState()` MUST 在节点处于 FAIL 且未过保护期时拒绝清除 FAIL（仅清除 PFAIL）。
- FAIL 节点在保护期结束后若确实恢复（连续 PONG），方可清除 FAIL 状态。

## Capabilities

### New Capabilities
<!-- 无新增 capability -->

### Modified Capabilities
- `cluster-automatic-failover`: 新增"FAIL 状态保护期"要求--节点被标记 FAIL 后必须在 `NODE_TIMEOUT * 2` 内保持 FAIL 状态，保护期内 PING/PONG 不清除 FAIL，确保 slave 选举窗口不被短暂的 PONG 恢复打断。

## Impact

- **受影响代码**：`luban-rds-cluster` 模块
  - `FailureDetector.java`：`clearNodeFailState` 增加 FAIL 保护期判断，需记录 FAIL 标记时间
  - `ClusterNode.java`：新增 `failTime` 字段记录 FAIL 标记时刻
  - `GossipProtocol.java`：无需改动（调用方语义不变，由 `clearNodeFailState` 内部判断）
- **API**：无对外接口变更
- **依赖**：无新增依赖
- **回归风险**：低。保护期仅影响 FAIL→可达 的转换时序，PFAIL 检测路径不变；保护期过后行为与原实现一致
