## 1. PING/PONG 协议扩展（携带 currentEpoch）

- [x] 1.1 `PingMessage`/`PongMessage` 在消息体尾部追加 8 字节 `senderCurrentEpoch` 字段，更新 `encodeBody`/`decodeBody`；解码时剩余字节不足则保留默认值 0（向后兼容）
- [x] 1.2 `GossipProtocol.sendPing`/`sendPong` 填充 `senderCurrentEpoch = clusterConfig.getCurrentEpoch()`
- [x] 1.3 `GossipProtocol.updateNodeFromPingMessage`/`updateNodeFromPongMessage` 调用 `clusterConfig.setEpochIfGreater(senderCurrentEpoch)`，使重启节点能通过心跳同步集群级 currentEpoch
- [x] 1.4 更新 `GossipMessageCodecTest` 覆盖：新消息编解码、旧消息（无 currentEpoch 字段）解码向后兼容

## 2. gossip 接收侧 MYSELF 自降级

- [x] 2.1 改造 `GossipProtocol.processGossipNodes`：移除对 MYSELF 的无条件 `continue`，改为当 gossip entry 的 `nodeId == myNodeId` 且 `gossipEpoch > localEpochBaseline` 且 flags 含 SLAVE 时，执行自降级（清空 MYSELF slots、MASTER->SLAVE、`setMasterNodeId(nodeInfo.getMasterNodeId())`）
- [x] 2.2 自降级时调用 `replicationLifecycleListener.demoteToSlave(newMasterNode)` 切换复制方向（复用现有接口，不新增）
- [x] 2.3 自降级后调用 `notifyTopologyChanged()` 触发 `nodes.conf` 持久化与 SlotManager 同步
- [x] 2.4 严格 epoch 门控：仅 `gossipEpoch > localEpochBaseline` 触发角色切换，相等时不切换（与第三方节点门控一致，防回退）
- [x] 2.5 处理 MYSELF 视图中 slots 同步：自降级时清空本地 slots，并按新主视图同步 slots 归属到 SlotManager/ClusterConfig

## 3. 启动恢复软对齐

- [x] 3.1 `NettyRedisServer.restoreClusterFromConfig`/`initCurrentNode` 保留本地恢复（节点 ID、已知节点列表、slots 作为初始值），不新增阻塞等待；依赖 gossip 自然纠正角色
- [x] 3.2 增加日志：启动恢复时若 MYSELF 为 master 且本地 configEpoch 低于已知集群视图，输出"等待 gossip 对齐"提示，便于诊断
- [x] 3.3 确保 `seedSlotManagerFromConfig` 在 gossip 自降级清空 slots 后能正确同步 SlotManager（mySlots 清空、slotOwners 更新为新主）

## 4. 回归测试

- [x] 4.1 单元测试：`GossipProtocol` 收到携带 MYSELF 降级视图（更高 configEpoch + SLAVE + masterNodeId）的 PONG 时，MYSELF 自降级--角色切换、slots 清空、masterNodeId 设置
- [x] 4.2 单元测试：epoch 门控--`gossipEpoch == localEpoch` 时不自降级（防回退）；`gossipEpoch < localEpoch` 时忽略
- [x] 4.3 单元测试：PING/PONG 携带 `currentEpoch` 后，接收侧 `setEpochIfGreater` 同步集群级 currentEpoch
- [x] 4.4 单元测试：旧版本 PING/PONG 消息（无 currentEpoch 字段）解码向后兼容，不抛异常
- [x] 4.5 集成测试：模拟故障转移后旧主重启--新主已提升（epoch=9），旧主以 epoch=4 重启，经 gossip 交互后旧主降级为新主 slave，slots 转移，`cluster nodes` 一致

## 5. 验证

- [x] 5.1 运行 `luban-rds-cluster` 模块全部单元测试
- [x] 5.2 运行项目构建（Java 17）确认无回归
- [x] 5.3 在 `D:\tmp\luban-rds` 6 节点集群复现场景，验证重启旧主正确降级
