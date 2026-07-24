## Why

集群模式启动后，Redisson 客户端连接报错 `CLUSTERDOWN Hash slot not served`。根因是 Gossip 协议在传播 master-slave 关系时，`masterNodeId` 在 MASTER→SLAVE 角色转换场景下未被正确设置，导致节点状态不一致。此外 `GossipNodeInfo.decode()` 在解码无 masterNodeId 的消息时未显式清除旧值，存在潜在的脏数据风险。

## What Changes

- **修复 `GossipProtocol.processGossipNodes()`**：在检测到 MASTER→SLAVE 角色切换时，同步设置 `masterNodeId`
- **修复 `GossipProtocol.syncSenderRole()`**：同上，确保发送方角色切换时 `masterNodeId` 被正确设置
- **修复 `GossipNodeInfo.decode()`**：当 `masterNodeId` 标志为 0 时显式置 null，消除对象复用场景下的脏数据风险

## Capabilities

### New Capabilities
<!-- 无新增 capability -->

### Modified Capabilities
<!-- 无 spec 变更，仅修复内部行为 -->

## Impact

- 受影响文件：
  - `luban-rds-cluster/.../gossip/GossipProtocol.java` — `processGossipNodes()` 和 `syncSenderRole()` 方法
  - `luban-rds-cluster/.../gossip/GossipNodeInfo.java` — `decode()` 方法
- 不涉及接口变更
- 不涉及架构调整
