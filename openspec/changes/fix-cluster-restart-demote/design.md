## Context

集群故障转移后，原主节点重启必须降级为新主的 slave，否则会以旧 master 身份重新上线，与接管其 slots 的新主形成双主冲突（split-brain）。当前实现依赖 `FailoverResultMessage` 广播来传播降级，但该广播仅在故障转移瞬间发送一次，重启节点错过广播后无任何收敛机制。gossip 的 epoch 仲裁本可作为后备收敛路径（对齐 Redis），但 `processGossipNodes` 显式跳过 MYSELF，`syncSenderRole` 只改对端，导致 MYSELF 永不自降级。

## Goals / Non-Goals

**Goals:**
- 重启的原主节点在收到首个携带更高 configEpoch 的 PONG/PING 后，能通过 epoch 仲裁自降级为新主的 slave，清空 slots、切换角色、切换复制方向。
- 重启节点能通过心跳同步集群级 `currentEpoch`，避免 epoch 滞后导致后续仲裁门控恒为 false。
- PING/PONG 协议扩展向后兼容：旧版本节点能解码新消息（尾部追加字段），新版本节点能解码旧消息（缺字段时按默认处理）。
- 启动恢复不再无条件以本地 MYSELF 角色为准恢复 slots；保留本地文件仅作为节点 ID 与已知节点列表来源。
- 严格 epoch 门控，防止陈旧 gossip 把已提升的 master 回退为 slave（撤销合法故障转移）。

**Non-Goals:**
- 不改变故障转移选举算法、投票机制或 `FailoverResultMessage` 的广播时机。
- 不引入新的集群总线消息类型或新的持久化格式。
- 不改变 `nodes.conf` 文件格式或公开命令语义。
- 不处理手动 `CLUSTER FAILOVER TAKEOVER` 的特殊语义（其 configEpoch 已由现有逻辑覆盖）。

## Decisions

1. **自降级走 gossip 接收侧，不走启动阻塞对齐。** 在 `processGossipNodes` 中移除"跳过 MYSELF"的盲区，但仅对"角色为 SLAVE 且 configEpoch 严格大于本地基线"的 MYSELF 视图应用降级。相比启动时阻塞等待 PONG，此方案不增加启动延迟、不引入死锁风险（启动时若集群不可达仍能以本地配置服务），且与 Redis 的"gossip 驱动收敛"模型一致。
2. **PING/PONG 追加 `currentEpoch` 字段，向后兼容。** 在消息体尾部追加 8 字节 `senderCurrentEpoch`；解码时若剩余字节不足则保留默认值 0（`setEpochIfGreater(0)` 无副作用）。不新增消息类型、不改变现有字段顺序，旧节点解码新消息时尾部多出的字节被忽略。
3. **启动恢复"软对齐"而非"硬重置"。** `restoreClusterFromConfig` 仍从本地恢复 MYSELF 与 slots（保证单节点启动可用），但 `initCurrentNode` 后由 gossip 自然纠正。不在启动路径加阻塞等待，避免集群分区时节点无法启动。
4. **复用 `ReplicationLifecycleListener.demoteToSlave`。** 自降级时调用现有 `demoteToSlave(winner)` 切换复制方向，不新增接口。`ClusterNode` 的角色/slots/masterNodeId 变更与 `onFailoverResult` 降级路径保持一致（clearSlots、MASTER->SLAVE、setMasterNodeId）。
5. **epoch 门控统一为"严格大于"。** 自降级仅在 `gossipEpoch > localEpochBaseline` 时触发，与 `syncSenderRole`/`processGossipNodes` 现有第三方节点角色切换门控一致。相等时不切换，防止陈旧视图回退。

## Risks / Trade-offs

- [Risk] gossip section 随机选择 ≤3 个节点，重启节点可能要等几个心跳周期才收到携带 MYSELF 视图的消息 -> 可接受（秒级收敛），且 PING/PONG 头携带的 `currentEpoch` 不受此限制，能更快同步集群级 epoch。
- [Risk] 移除 MYSELF 跳过后，若 gossip 携带错误视图可能误降级 -> 严格 epoch 门控 + 仅 SLAVE 角色触发 + `configEpoch` 单调递增保证只有更新视图才能覆盖。
- [Risk] PING/PONG 协议扩展可能破坏旧版本节点 -> 追加字段在尾部、解码时长度校验、缺字段默认 0，向后兼容；需在编解码测试中覆盖旧消息解码。
- [Risk] 启动恢复仍以本地 slots 服务，在首个 PONG 到达前可能短暂接受写入 -> 与 Redis 行为一致（重启后短暂窗口），且 slots 写入会因 MOVED 重定向到新主而自然失败；可接受。
- [Trade-off] 不做启动阻塞对齐意味着极端情况下（集群完全分区）重启节点仍以 master 上线 -> 这是预期行为（与 Redis 一致），分区恢复后由 gossip 收敛。

## Migration Plan

无需数据迁移。升级后：
- 重启的原主节点会在首个携带更高 configEpoch 的 PONG 后自降级。
- 滚动升级期间，新版本节点与旧版本节点可互通（协议向后兼容）。
- 回滚：回退应用版本即可；`nodes.conf` 格式不变。

## Open Questions

无。设计决策已通过日志与源码验证。
