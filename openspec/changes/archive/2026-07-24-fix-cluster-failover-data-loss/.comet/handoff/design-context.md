# Comet Design Handoff

- Change: fix-cluster-failover-data-loss
- Phase: design
- Mode: compact
- Context hash: b2ccac1290093165980ac3db1d2cbf99c8ea9059acaf6fcde56e731315fe1ce7

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/fix-cluster-failover-data-loss/proposal.md

- Source: openspec/changes/fix-cluster-failover-data-loss/proposal.md
- Lines: 1-28
- SHA256: 16709829b2f926f5a50870b5a5ed84d1ae16cbe906a2975b8ba63984655e1dd3

```md
## Why

集群故障转移目前只更新节点角色与槽位归属，没有建立和切换实际的数据复制链路。主节点宕机后，从节点虽然能够提升并继续服务，但其本地数据可能为空或落后，导致原主节点数据不可用。

## What Changes

- 将集群角色变更与主从复制生命周期连接起来：`CLUSTER REPLICATE` 启动到目标主节点的同步，提升时停止从节点复制状态，降级节点开始跟随新主节点。
- 将主节点成功执行的写命令写入复制 backlog，并传播给在线从节点。
- 让从节点解析并执行主节点传播的 RESP 命令，使本地 `MemoryStore` 持续同步。
- 在服务启动时完整装配主、从复制组件及其所需的存储和持久化依赖。
- 增加覆盖初始同步、增量传播和故障转移后数据保留的回归测试。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `cluster-automatic-failover`：故障转移除拓扑收敛外，还必须保证提升节点拥有已复制的数据，并正确切换主从复制生命周期。

## Impact

- 影响 `luban-rds-server`、`luban-rds-replication` 和 `luban-rds-cluster` 的启动装配、命令处理、复制执行和故障转移回调。
- 不改变 RESP 命令格式和现有 Cluster 命令返回格式。
- 不引入新的外部依赖。
- 需要跨模块集成测试验证主节点宕机后数据仍可从新主节点读取。
```

## openspec/changes/fix-cluster-failover-data-loss/design.md

- Source: openspec/changes/fix-cluster-failover-data-loss/design.md
- Lines: 1-82
- SHA256: 349eb6068292c8d4d28dc4f2accd926a4d62640e81dfcb9d9300f112273cc887

[TRUNCATED]

```md
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

```

Full source: openspec/changes/fix-cluster-failover-data-loss/design.md

## openspec/changes/fix-cluster-failover-data-loss/tasks.md

- Source: openspec/changes/fix-cluster-failover-data-loss/tasks.md
- Lines: 1-22
- SHA256: eac358a0c962300cbc4e1cfbb069bcde0e31053091e577ec1a5d28b0da72800b

```md
## 1. 复制数据路径测试

- [ ] 1.1 添加失败测试，证明 master 成功写入会进入 backlog 并由 slave 应用到共享 `MemoryStore`
- [ ] 1.2 添加复制流拆包、粘包和事务重放测试

## 2. 端到端复制链路

- [ ] 2.1 在服务启动时完整装配主从复制管理器、命令处理器、存储及持久化依赖
- [ ] 2.2 在成功写命令路径传播原始 RESP 帧，并排除只读、失败、重定向和复制来源命令
- [ ] 2.3 实现 slave 对传播 RESP 流的增量解析和命令执行

## 3. 集群角色生命周期

- [ ] 3.1 定义集群模块可依赖的复制生命周期回调，并在服务层提供实现
- [ ] 3.2 让 `CLUSTER REPLICATE` 启动或切换到目标 master 的复制连接
- [ ] 3.3 让提升节点停止上游复制，并让降级节点跟随新 master，保证重复通知幂等

## 4. 故障转移回归验证

- [ ] 4.1 添加集成测试：确认候选 slave 已同步数据后触发 master 故障，验证新 master 保留故障前数据
- [ ] 4.2 验证故障转移后的新增写入能够同步到恢复并降级的原 master
- [ ] 4.3 运行 replication、server、cluster 模块测试和完整 Maven 构建
```

## openspec/changes/fix-cluster-failover-data-loss/specs/cluster-automatic-failover/spec.md

- Source: openspec/changes/fix-cluster-failover-data-loss/specs/cluster-automatic-failover/spec.md
- Lines: 1-82
- SHA256: 74b7e469f6bb1238ff5376b9d74a5d15072ee06a172ab4075deecf5772e45179

[TRUNCATED]

```md
## ADDED Requirements

### Requirement: 从节点必须持续复制主节点数据

当节点被配置为另一个集群节点的 slave 时，系统 MUST 向目标 master 发起 PSYNC，将全量或部分同步数据加载到该节点共享的 `MemoryStore`，并持续执行后续传播的写命令。

#### Scenario: CLUSTER REPLICATE 建立复制链路

- **WHEN** 节点 S 成功执行 `CLUSTER REPLICATE <masterId>`
- **THEN** S 在后台连接到目标 master 的 Redis 服务端口并发起 PSYNC
- **AND** 同步完成后 S 的已复制键值与 master 一致

#### Scenario: 从节点执行增量传播命令

- **WHEN** master 成功执行写命令并向 ONLINE slave 传播完整 RESP 命令帧
- **THEN** slave 在共享 `MemoryStore` 上执行该命令
- **AND** slave 不把该复制命令再次传播给其它节点

#### Scenario: 复制流发生拆包或粘包

- **WHEN** slave 一次收到半条命令或多条连续 RESP 命令
- **THEN** slave 缓存不完整数据，仅执行已经完整解析的命令帧
- **AND** 每条完整命令恰好按流顺序执行一次

### Requirement: 主节点成功写入必须进入复制流

处于 master 角色的节点 MUST 在写命令成功执行后，将可重放的原始 RESP 命令追加到 replication backlog，并传播给所有 ONLINE slave。只读、失败、重定向或仅排队的命令 MUST NOT 被传播。

#### Scenario: 成功写命令被传播

- **WHEN** 客户端在 master 上成功执行 `SET key value`
- **THEN** 对应 RESP 命令被追加到 backlog
- **AND** 所有 ONLINE slave 收到并执行该命令

#### Scenario: 失败或只读命令不传播

- **WHEN** 命令为只读命令、执行失败或返回集群重定向
- **THEN** replication backlog 和 slave 数据均不因该命令发生变化

#### Scenario: 事务写入可重放

- **WHEN** master 成功执行包含写命令的 `MULTI`/`EXEC` 事务
- **THEN** slave 收到能够保持事务边界与命令顺序的复制流
- **AND** slave 执行后的数据与 master 一致

### Requirement: 故障转移必须切换复制生命周期并保留数据

slave 提升为 master 时，系统 MUST 停止其上游复制连接并保留已同步的本地数据；原 master 或其它被重新指派的 slave MUST 开始跟随新 master。角色切换不得清空提升节点的 `MemoryStore`。

#### Scenario: 已同步 slave 提升后保留数据

- **WHEN** master M 的写入已经同步到 slave S
- **AND** M 被判定 FAIL，S 经选举提升为新 master
- **THEN** 客户端从 S 读取故障前已同步的数据得到与 M 故障前相同的值
- **AND** S 不再保持指向 M 的上游复制连接

#### Scenario: 原 master 恢复后跟随新 master

- **WHEN** 原 master M 恢复并根据较新纪元拓扑被降级为 S 的 slave
- **THEN** M 向 S 发起同步
- **AND** 同步完成后 M 的数据与 S 一致

#### Scenario: 重复角色通知保持幂等

- **WHEN** 节点重复收到相同 master 目标或相同提升结果的通知
- **THEN** 系统不创建重复复制连接、不清空数据且不抛出异常

### Requirement: 故障转移数据保证遵循异步复制边界

系统 MUST 保证故障转移后保留已经在候选 slave 上成功应用的数据，但不承诺保留 master 宕机前尚未传播或尚未应用的异步写入。

#### Scenario: 已应用数据在提升后可读

- **WHEN** 测试在故障前已确认候选 slave 成功应用指定写入
- **AND** 候选 slave 随后提升为 master
- **THEN** 该写入在新 master 上保持可读

#### Scenario: 未复制写入不作为零丢失保证

- **WHEN** master 在写入传播到任何 slave 前立即宕机
```

Full source: openspec/changes/fix-cluster-failover-data-loss/specs/cluster-automatic-failover/spec.md

