## Context

Luban-RDS 已有 `MasterReplicationManager`、`SlaveReplicationService`、PSYNC、复制 backlog 和集群故障转移状态机，但这些组件尚未形成生产可用的端到端链路。集群层只负责角色和槽位，服务层没有完整启动从节点复制服务，写命令没有进入 backlog，从节点收到传播数据后也没有执行命令。因此，拓扑可以完成故障转移，而数据面没有随之收敛。

该修复跨越 cluster、server、replication 三个模块。模块依赖边界必须保持单向，集群模块不应直接依赖具体复制实现。

## Goals / Non-Goals

**Goals:**

- 在节点被配置为 slave 时启动全量或部分同步，并持续应用增量命令。
- 主节点成功执行写命令后，将原始 RESP 命令追加到 backlog 并传播给在线 slave。
- slave 提升为 master 时停止上游复制；旧 master 恢复并降级后能够跟随新 master。
- 故障转移完成后，新 master 能读取故障前已确认并复制的数据。
- 保持现有集群选举、纪元裁决和 RESP 接口兼容。

**Non-Goals:**

- 不保证尚未传播到 slave 的异步写入零丢失；本次遵循现有异步复制模型。
- 不修改 PSYNC 协议格式或 Cluster Bus 消息格式。
- 不增加 `WAIT`、磁盘同步复制或新的复制一致性级别。
- 不重写现有选举状态机。

## Decisions

### 1. 通过中立回调接口连接集群角色变化与复制生命周期

在 cluster 模块定义不依赖 replication 模块的生命周期回调接口，由 server 模块在启动装配时提供实现。回调至少覆盖 `replicateTo(masterNode)`、`promoteToMaster()` 和 `demoteToSlave(masterNode)`。

选择回调接口而不是让 cluster 直接依赖 replication，是为了保持 Maven 模块依赖方向，避免循环依赖，并使既有集群单元测试可使用 no-op 实现。

### 2. 复制组件在服务启动时按节点角色装配

`NettyRedisServer` 负责创建或取得单例的 `MasterReplicationManager`、`SlaveReplicationService` 和复制命令处理器，并注入同一个 `MemoryStore`、配置及持久化服务。启动时如果配置了 `replicaof` 或集群节点角色为 slave，则异步启动 slave 服务；作为 master 时保持 PSYNC 接收能力。

选择服务层集中装配，而不是在命令处理器内临时创建复制服务，是为了确保生命周期、资源关闭和共享存储一致。

### 3. 在命令执行成功后传播原始 RESP 帧

`RedisServerHandler` 在确认写命令成功后，使用请求的原始 RESP 编码调用 `MasterReplicationManager.propagateCommand`。事务在 `EXEC` 成功后传播可重放的事务命令序列，失败或排队阶段不传播。只读命令、重定向、语法错误及执行失败不得进入 backlog。

使用原始 RESP 帧可避免复制侧重新构造参数时产生二进制安全问题，并与 Redis 命令流复制方式一致。

### 4. 从节点复用协议解析和命令执行能力应用传播数据

`SlaveReplicationService` 对传播字节进行累积和 RESP 帧边界解析，将完整命令交给一个专用的复制命令执行器。执行器直接操作从节点共享的 `MemoryStore`，但必须标记请求来源为 replication，禁止再次传播、集群重定向、客户端响应及监控副作用。

选择复用现有 parser/handler 能力并增加执行上下文，而不是手写命令分派表，以减少命令覆盖缺失及主从语义漂移。

### 5. 角色切换显式停止或重连复制

slave 提升为 master 时停止其上游连接和心跳，保留已经复制到本地的 `MemoryStore`，并使本地 master 复制管理器继续接收新的 slave。节点降级或执行 `CLUSTER REPLICATE` 时，按目标节点的 Redis 服务地址停止旧连接并重新发起 PSYNC。相同目标的重复通知必须幂等。

不在提升时清空本地数据或 backlog；复制 ID 和 offset 的具体切换遵循现有 PSYNC 能力，无法继续部分同步时回退全量同步。

### 6. 以端到端数据断言作为主要回归标准

测试必须先证明 slave 在提升前已获得主节点数据，再触发主节点故障和提升，最后从新 master 读取故障前数据并验证后续写入可复制到降级节点。仅断言角色、槽位或回调被调用不足以证明修复完成。

## Risks / Trade-offs

- **异步复制仍存在最后一小段未确认写入丢失窗口** → 测试仅对已观察到复制完成的数据做强保证，并在文档中明确一致性边界。
- **复制流拆包/粘包可能产生半条命令** → 使用累积缓冲区和现有 RESP parser，仅在完整帧到达后执行。
- **复制命令再次传播形成环路** → 为复制执行路径增加明确来源标记，跳过 backlog 传播和集群重定向。
- **故障转移与重连并发导致重复连接** → 生命周期切换使用同步或原子状态，按目标地址实现幂等。
- **事务传播不完整导致主从偏差** → 对 `MULTI`/`EXEC` 使用可重放的完整命令序列，并增加事务回归测试。
- **跨模块改动可能影响 standalone replication** → 保留非集群 `replicaof` 启动方式并运行 replication、server、cluster 全套回归。

## Migration Plan

1. 先补充失败测试，覆盖传播命令执行和故障转移数据保留。
2. 完成复制服务装配与命令传播/执行链路。
3. 接入集群角色生命周期回调。
4. 运行相关模块测试及完整 Maven 构建。
5. 部署时滚动升级；旧节点仍可通过现有 Cluster Bus 交互，但只有升级节点具备完整数据面切换。

回滚只需还原代码，无配置或存储格式迁移。

## Open Questions

- 现有命令处理器是否已经保留完整原始 RESP 请求；实现阶段需确认最小的二进制安全传播接入点。
- 现有 RDB 全量同步载入是否直接更新共享 `MemoryStore`；若不是，需要在当前修复内补齐该连接。
