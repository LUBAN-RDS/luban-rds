---
comet_change: fix-cluster-failover-data-loss
role: technical-design
canonical_spec: openspec
archived-with: 2026-07-24-fix-cluster-failover-data-loss
status: final
---

# 集群故障转移数据保留技术设计

## 1. 目标与一致性边界

当前故障转移只完成角色和槽位切换，数据复制链路没有接入生产路径。本设计补齐从写入传播、slave 应用到角色切换的端到端流程，保证候选 slave 已成功应用的数据在提升为 master 后仍可读。

复制仍采用异步语义：尚未传播或尚未应用到候选 slave 的写入不承诺零丢失。本次不引入同步复制、`WAIT` 或新的协议格式。

## 2. 方案选择

采用“中立生命周期接口 + server 层装配”的方案：

- cluster 模块只发布 `replicateTo`、`promoteToMaster`、`demoteToSlave` 等角色事件，不依赖具体复制类。
- server 模块实现该接口并持有复制组件，维持现有 Maven 依赖方向。
- replication 模块负责 PSYNC、backlog、复制流解析和本地命令应用。

未采用故障发生时临时拷贝数据，因为原 master 宕机时通常不可访问，无法可靠恢复。也未让 cluster 直接依赖 replication，以避免模块循环依赖和集群状态机与网络复制实现耦合。

## 3. 组件设计

### 3.1 ReplicationLifecycleListener

在 cluster 模块定义中立接口，参数使用 `ClusterNode` 或普通 host/port，不暴露 replication 模块类型：

- `replicateTo(ClusterNode master)`：节点成为 slave 或更换 master。
- `promoteToMaster()`：本节点提升，停止上游复制并保留本地数据。
- `demoteToSlave(ClusterNode master)`：本节点降级并连接新 master。

提供 no-op 默认实现或允许空监听器，使现有集群单元测试不必装配真实网络服务。重复相同目标的通知必须幂等。

### 3.2 服务层复制协调器

server 模块实现生命周期接口，并统一管理：

- `MasterReplicationManager`
- `SlaveReplicationService`
- `ReplicationCommandHandler`
- 共享 `MemoryStore`
- RDB/AOF 相关服务与 `RdsConfig`

`NettyRedisServer` 在启动阶段完成一次性装配，在关闭阶段停止 slave 连接、心跳和相关资源。standalone `replicaof` 与 cluster slave 共用同一套启动逻辑。

### 3.3 主节点写入传播

`RedisServerHandler` 在命令执行完成后判断：

1. 当前请求来自客户端而非 replication；
2. 当前节点承担 master 写入职责；
3. 命令确实修改数据且执行成功；
4. 响应不是错误、MOVED 或 ASK。

满足条件时，把请求的原始 RESP 帧交给 `MasterReplicationManager.propagateCommand`。使用原始帧可保留二进制参数并避免重新编码差异。

事务在 `EXEC` 成功后传播可重放的完整事务流，排队阶段不传播。复制来源命令必须跳过传播，防止循环。

### 3.4 slave 复制流应用

`SlaveReplicationService` 保留跨网络读取的累积缓冲区，通过现有 RESP parser 识别完整命令帧：

- 半条命令留在缓冲区等待下一次读取；
- 一次读取包含多条命令时按顺序逐条执行；
- 完整命令通过专用 replication 执行上下文操作共享 `MemoryStore`；
- 不生成客户端响应、不做集群重定向、不写 monitor，也不再次传播。

执行成功后才推进 applied offset。协议错误或执行失败时记录明确日志并断开连接，让既有重连流程重新同步，避免静默产生数据偏差。

### 3.5 故障转移生命周期

- `CLUSTER REPLICATE <masterId>`：控制面校验和角色更新成功后，异步调用 `replicateTo`。目标改变时停止旧连接并向新 master 发起 PSYNC。
- slave 提升：先完成既有角色、槽位与 epoch 更新，再调用 `promoteToMaster`，停止上游连接但不清空 `MemoryStore`。
- 原 master 恢复并被较新拓扑降级：调用 `demoteToSlave`，按新 master 地址重新同步。
- 相同 epoch 或相同目标的重复 gossip/failover 通知不重复建立连接。

如果 partial sync 条件不满足，沿用现有 PSYNC 行为回退 full sync。

## 4. 数据流

正常复制：

```text
客户端 RESP
  -> RedisServerHandler
  -> CommandHandler 修改 master MemoryStore
  -> 成功判定
  -> MasterReplicationManager backlog + broadcast
  -> SlaveReplicationService 累积解析
  -> replication 执行上下文
  -> slave 共享 MemoryStore
```

故障转移：

```text
master FAIL
  -> slave 已持有已复制数据
  -> FailoverManager 完成选举和槽位切换
  -> promoteToMaster 停止上游复制
  -> 新 master 直接以原 MemoryStore 对外服务
  -> 原 master 恢复后 demoteToSlave
  -> PSYNC 新 master 并追平
```

## 5. 错误处理与并发

- 生命周期切换串行化，连接目标使用原子状态保存。
- 相同 master 地址重复请求直接返回，不制造连接抖动。
- 目标节点不存在时保留现有 RESP 错误，不改变角色。
- slave 应用命令失败时不得继续盲目推进 offset；断链后重新 PSYNC。
- 提升与传播并发时，以角色状态为传播门禁；提升节点停止消费旧 master 流后才作为新 master 接受后续写入。
- 所有 ByteBuf 必须遵守 Netty 引用计数，解析完成或异常时可靠释放。

## 6. 测试策略

### 单元测试

- 写命令成功、失败、只读、重定向和 replication 来源的传播判定。
- RESP 拆包、粘包、多命令、二进制参数及事务重放。
- 生命周期监听器的相同目标幂等、切换目标、提升停止上游和降级重连。
- slave 执行失败时不推进 offset 并触发重连。

### 集成测试

1. 启动 master/slave，共享真实服务器命令路径。
2. 在 master 写入数据，等待并确认候选 slave 已应用。
3. 停止 master 或触发既有故障检测，等待 slave 提升。
4. 从新 master 读取故障前数据，必须得到原值。
5. 在新 master 写入新值；原 master 恢复并降级后，验证其追平新值。
6. 回归 standalone replication、手动/自动 failover、gossip 和 slot 路由测试。

主要验收标准是数据断言，而不是仅断言角色或回调发生。

## 7. 实施顺序

1. 先添加复制流应用和故障转移数据断言的失败测试。
2. 补齐服务启动装配和 slave 命令执行。
3. 接入成功写命令传播与事务处理。
4. 添加 cluster 生命周期接口并接入 `CLUSTER REPLICATE`、自动和手动 failover。
5. 运行三个相关模块测试，再运行完整 Maven 构建。

## 8. 风险控制

- 异步复制的丢失窗口通过明确验收前置条件界定，不错误承诺零丢失。
- parser 复用和二进制帧测试降低协议实现偏差。
- replication 来源标记防止传播环路。
- no-op 生命周期实现降低对既有集群测试和非集群模式的回归影响。
- 不改变外部命令和 Cluster Bus 格式，支持滚动升级与代码回滚。
