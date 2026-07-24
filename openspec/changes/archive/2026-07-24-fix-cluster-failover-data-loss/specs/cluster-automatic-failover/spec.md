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
- **THEN** 系统允许该写入在故障转移后不可用
- **AND** 集群仍按现有选举和槽位规则恢复服务
