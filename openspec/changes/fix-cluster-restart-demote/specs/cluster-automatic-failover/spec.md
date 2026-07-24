## MODIFIED Requirements

### Requirement: 集群启动时持久化节点配置

集群模式首次启动或集群配置发生变化时，系统 MUST 在配置目录中创建或更新 `nodes.conf`，并 MUST 仅在目标文件成功写入/替换后记录保存成功。持久化过程 MUST 确保父目录存在，使用同目录临时文件写入，并在支持时采用原子替换；失败时 MUST 记录包含绝对路径和异常上下文的错误，且不得留下未清理的临时文件。

#### Scenario: 首次集群启动生成 nodes.conf

- **WHEN** 集群模式启动且配置目录中不存在 `nodes.conf`
- **THEN** 系统创建配置目录（如不存在）
- **AND** 写入包含当前 MYSELF 节点和节点 ID 的非空 `nodes.conf`
- **AND** 目标文件存在后才记录“集群配置已保存”成功日志

#### Scenario: 已有 nodes.conf 更新成功

- **WHEN** 集群拓扑变化触发配置保存
- **THEN** 系统将新内容写入同目录临时文件
- **AND** 使用原子移动替换目标文件，或在不支持原子移动时安全降级为普通替换
- **AND** 保存成功后临时文件不存在

#### Scenario: 配置目录无法创建

- **WHEN** 目标配置路径的父目录不存在且文件系统拒绝创建目录或写入文件
- **THEN** 保存操作报告失败并记录绝对目标路径、临时路径及完整异常上下文
- **AND** 不记录保存成功日志
- **AND** 不留下可被误认为有效配置的临时文件

#### Scenario: 重复保存覆盖旧配置

- **WHEN** `nodes.conf` 已存在且再次触发保存
- **THEN** 系统成功替换旧文件
- **AND** 新文件保持兼容 Redis nodes.conf 的格式
- **AND** 节点 ID、节点地址和槽位信息可在随后加载时恢复

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
