## ADDED Requirements

### Requirement: 故障转移后原主重启经 gossip 自降级

当 master 节点被故障转移替换后重启上线，系统 MUST 通过 gossip 心跳的 epoch 仲裁让其自降级为新主的 slave，而不得盲信本地 `nodes.conf` 维持旧 master 身份。重启节点从本地恢复的 `configEpoch` 低于集群已演进的 `configEpoch` 时，首个携带 MYSELF 降级视图（更高 `configEpoch` + SLAVE 角色 + 指向新主的 `masterNodeId`）的 PING/PONG 必须触发自降级。

#### Scenario: 重启旧主通过 gossip 自降级

- **WHEN** 原 master M 重启后从本地 `nodes.conf` 恢复为 MASTER，且本地 `configEpoch` 低于集群当前值
- **AND** M 收到 PONG/PING，其 gossip section 携带 MYSELF 的视图：`configEpoch > M 本地基线` 且 flags 含 SLAVE 且 `masterNodeId` 指向新主 S
- **THEN** M 清空自身 slots
- **AND** M 切换角色 MASTER->SLAVE 并设置 `masterNodeId = S`
- **AND** M 通过 `ReplicationLifecycleListener.demoteToSlave(S)` 切换复制方向，向 S 发起同步
- **AND** M 持久化 `nodes.conf` 并同步 SlotManager

#### Scenario: 严格 epoch 门控防止回退已提升 master

- **WHEN** 节点收到 gossip 携带 MYSELF 视图，但其 `configEpoch` 小于或等于本地 MYSELF 的 `configEpoch` 基线
- **THEN** 不触发自降级
- **AND** 不改变 MYSELF 的角色、slots 或 masterNodeId
- **AND** 防止陈旧 gossip 把已合法提升的 master 回退为 slave

#### Scenario: currentEpoch 经 PING/PONG 心跳同步

- **WHEN** 重启节点收到 PING/PONG，其消息头 `senderCurrentEpoch` 大于本地 `currentEpoch`
- **THEN** 节点通过 `setEpochIfGreater` 将本地 `currentEpoch` 提升至 `senderCurrentEpoch`
- **AND** 使后续 epoch 仲裁门控能正常工作（避免本地 epoch 滞后导致自降级恒不触发）

#### Scenario: PING/PONG 协议向后兼容

- **WHEN** 新版本节点收到旧版本节点发送的 PING/PONG（消息体尾部无 `senderCurrentEpoch` 字段）
- **THEN** 解码不抛异常
- **AND** `senderCurrentEpoch` 按默认值 0 处理，`setEpochIfGreater(0)` 无副作用
- **AND** 旧版本节点收到新版本消息时忽略尾部多余字节

#### Scenario: 启动恢复软对齐不阻塞

- **WHEN** 节点重启并从本地 `nodes.conf` 恢复，本地视图显示 MYSELF 为 master
- **THEN** 节点以本地配置启动服务（不阻塞等待集群对齐）
- **AND** 启动后由 gossip 心跳自然纠正角色与 slots
- **AND** 在首个携带更高 configEpoch 的 PONG 到达前，写入请求因 slots 已属于新主而返回 MOVED 重定向
