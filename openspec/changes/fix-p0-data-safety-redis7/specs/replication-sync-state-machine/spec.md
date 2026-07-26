## ADDED Requirements

### Requirement: Slave 复制握手的 PSYNC 响应路由

Slave 发送 `PSYNC` 命令后，MUST 进入 `HANDSHAKE_PSYNC` 状态，并将 `+FULLRESYNC`/`+CONTINUE` 响应路由到 PSYNC 专用处理逻辑，而非 `REPLCONF` 通用响应处理器。收到 `+FULLRESYNC <replid> <offset>` 时，MUST 解析 replid 和 offset，触发 `onFullSync` 回调并进入 `FULL_SYNC` 状态。收到 `+CONTINUE [replid]` 时，MUST 解析可选 replid，触发 `onPartialSync` 回调并进入 `PARTIAL_SYNC` 状态。`REPLCONF` 三连发（PORT/IP/CAPA）MUST 改为逐条发送并等待 `+OK` 响应后再发下一条，避免 Netty 异步下发导致响应错位。

#### Scenario: Full sync 握手完整走通

- **WHEN** Slave 连接 master，完成 PING/AUTH/REPLCONF 三连发后发送 `PSYNC ? -1`
- **AND** master 返回 `+FULLRESYNC <replid> <offset>` 并开始传输 RDB
- **THEN** Slave 解析 replid 和 offset
- **AND** 触发 `onFullSync(replid, offset)` 回调
- **AND** Slave 进入 `FULL_SYNC` 状态等待 RDB 数据

#### Scenario: Partial sync 握手完整走通

- **WHEN** Slave 发送 `PSYNC <replid> <offset>`，master 返回 `+CONTINUE`
- **THEN** Slave 触发 `onPartialSync(replid)` 回调
- **AND** Slave 进入 `PARTIAL_SYNC` 状态接收 backlog 增量数据

#### Scenario: REPLCONF 逐条等待响应

- **WHEN** Slave 发送 `REPLCONF listening-port <port>`
- **THEN** Slave 等待 master 返回 `+OK` 后才发送下一条 `REPLCONF ip-address <ip>`
- **AND** 三条 REPLCONF 全部 `+OK` 后才发送 PSYNC

#### Scenario: Slave 进入 ONLINE 状态

- **WHEN** Full sync 的 RDB 加载完成（或 partial sync 的 backlog 重放完成）
- **THEN** Slave 调用 `callback.onOnline()`
- **AND** Slave 进入 `ONLINE` 状态
- **AND** 开始周期性发送 `REPLCONF ACK <offset>` 心跳

### Requirement: Full sync 窗口期命令缓冲与重放

Master 在执行 `performFullSync` 期间，MUST 记录 RDB 快照生成时刻的 `snapshotBaseOffset`（即当时的 `backlog.getMasterReplOffset()`）。RDB 传输完成后、slave 进入 ONLINE 之前，MUST 从 backlog 重放 `snapshotBaseOffset` 到当前 master offset 之间的窗口期命令。重放期间 slave MUST 保持 `SLAVE_FLAG_SYNCING` 状态，避免 `propagateCommand` 并发直发导致命令乱序。重放完成后 slave 才进入 ONLINE，后续命令走正常 `propagateCommand` 路径。

#### Scenario: 窗口期写入不丢失

- **WHEN** Master 在 RDB 传输期间收到 `SET k1 v1`、`SET k2 v2` 两个写命令
- **AND** 这些命令被写入 backlog 但 slave 当时处于 SYNCING 未收到
- **AND** RDB 传输完成
- **THEN** Master 从 backlog 重放这两个命令给该 slave
- **AND** slave 最终包含 k1=v1、k2=v2

#### Scenario: 重放期间不并发直发

- **WHEN** Master 正在重放窗口期命令给 slave（slave 仍 SYNCING）
- **AND** 此时又有新命令 `SET k3 v3` 到达 master
- **THEN** `SET k3 v3` 被写入 backlog 但不直接发给该 slave（因 SYNCING）
- **AND** 该命令在重放循环中被一并重放（重放读到当前 offset）

#### Scenario: 重放完成后转 ONLINE 接收增量

- **WHEN** 窗口期命令重放完成
- **THEN** slave 进入 ONLINE 状态
- **AND** 后续 master 的写命令通过 `propagateCommand` 直接发送

### Requirement: 运行时 SLAVEOF/REPLICAOF 启动复制

运行时执行 `SLAVEOF host port` 或 `REPLICAOF host port` 命令时，MUST 解析 `host` 和 `port` 参数，调用复制协调器的 `startSlave(address)` 真正发起复制连接，而非仅设置只读标志。`SLAVEOF NO ONE` / `REPLICAOF NO ONE` MUST 调用 `stopSlave()` 断开与 master 的复制连接并恢复可写状态。集群模式下（`cluster-enabled yes`）执行 `SLAVEOF host port` 仍 MUST 返回 `-ERR can't set master in cluster mode, use CLUSTER REPLICATE instead`。

#### Scenario: SLAVEOF 启动复制连接

- **WHEN** 客户端发送 `SLAVEOF 192.168.1.10 6379`
- **THEN** 系统调用 `startSlave("192.168.1.10:6379")`
- **AND** 返回 `+OK`
- **AND** 后台开始与 192.168.1.10:6379 建立复制连接

#### Scenario: REPLICAOF 等价于 SLAVEOF

- **WHEN** 客户端发送 `REPLICAOF 192.168.1.10 6379`
- **THEN** 行为与 `SLAVEOF 192.168.1.10 6379` 完全一致

#### Scenario: SLAVEOF NO ONE 断开复制

- **WHEN** 客户端发送 `SLAVEOF NO ONE`，且当前节点是 slave
- **THEN** 系统调用 `stopSlave()` 断开与 master 的连接
- **AND** 节点恢复可写状态（清除只读标志）
- **AND** 返回 `+OK`

#### Scenario: 集群模式拒绝 SLAVEOF

- **WHEN** `cluster-enabled yes` 时客户端发送 `SLAVEOF host port`
- **THEN** 返回 `-ERR can't set master in cluster mode, use CLUSTER REPLICATE instead`

### Requirement: Slave 复制偏移量正确上报与统计

Slave 进入 ONLINE 状态后，MUST 周期性向 master 发送 `REPLCONF ACK <offset>` 上报自身复制偏移量。Master 端的 `SlaveInfo.offset` MUST 根据 slave 上报的 ACK 值更新，而非固定为 0。`WAIT numreplicas timeout` 命令 MUST 基于各 slave 的真实 offset 统计已同步副本数（`slave.getOffset() >= currentOffset`）。`INFO replication` 的 `slave0:...,offset=<n>` MUST 反映真实偏移量。

#### Scenario: Slave 上报真实偏移量

- **WHEN** Slave 进入 ONLINE 并收到 master 发来的命令字节
- **THEN** Slave 累计接收字节数作为 offset
- **AND** 周期性发送 `REPLCONF ACK <offset>` 给 master
- **AND** master 端 `SlaveInfo.offset` 更新为该值

#### Scenario: WAIT 命令统计已同步副本

- **WHEN** 客户端发送 `WAIT 1 5000`，当前 master offset = 1000
- **AND** 有一个 slave 的 offset 已达到 1000
- **THEN** 返回 `:1`（1 个副本已同步）

#### Scenario: 未同步的 slave 不计入 WAIT

- **WHEN** 客户端发送 `WAIT 1 5000`，当前 master offset = 1000
- **AND** slave 的 offset 仍为 500（落后）
- **THEN** 在 timeout 内若 slave 未追平，返回 `:0`
