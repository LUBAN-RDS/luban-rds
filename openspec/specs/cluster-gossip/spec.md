# cluster-gossip

## MODIFIED Requirements

### Requirement: Gossip 节点发现推动握手完成

当节点通过 Gossip 携带信息（PING/PONG/MEET 的 gossip section）发现一个本地未知的节点时，必须主动向该节点发起总线连接并发送 MEET 消息，推动其完成握手；不应仅以 HANDSHAKE 状态加入本地配置后不再处理。

#### Scenario: 通过 Gossip 发现新节点后发起 MEET
- **WHEN** 节点 A 收到节点 B 的 PONG，其 gossip section 包含节点 C 的信息（nodeId/ip/port/busPort），且 A 本地不存在 nodeId 为 C 的节点
- **THEN** A 应将 C 以 HANDSHAKE 状态加入本地配置，并向 C 的地址发起总线连接、发送 MEET 消息
- **AND** 若 A 已与 C 建立连接，则不再重复发起

#### Scenario: 心跳覆盖 HANDSHAKE 节点
- **WHEN** GossipTask 周期性发送心跳，本地存在处于 HANDSHAKE 状态的节点
- **THEN** 应对该节点发送 MEET（而非 PING）以推动握手完成
- **AND** 对非 HANDSHAKE 节点维持现有随机 PING 心跳逻辑

### Requirement: Gossip 消息携带并同步槽位所有权

Gossip 消息必须携带节点拥有的槽位信息，使各节点对全局槽位分配达成一致，否则 `cluster_state` 无法达到 `ok`。

#### Scenario: PING/PONG/MEET 携带发送方槽位
- **WHEN** 节点发送 PING/PONG/MEET 消息
- **THEN** 消息应包含发送方（myNode）拥有的 slots 集合（BitSet）

#### Scenario: Gossip section 携带第三方节点槽位
- **WHEN** 节点构造 gossip section 中的 GossipNodeInfo
- **THEN** 每个 GossipNodeInfo 应携带对应节点拥有的 slots 集合

#### Scenario: 接收方同步槽位归属
- **WHEN** 节点收到 PING/PONG/MEET，其消息头 senderSlots 非空
- **THEN** 接收方应按"本地该 slot 无 owner 直接设；异 owner 且发送方配置纪元严格更大才覆盖；相等不覆盖"策略，将 senderSlots 中的每个 slot 归属设为发送方
- **AND** 当 gossip section 中某节点的 slots 非空时，按同样策略同步该节点槽位

#### Scenario: 槽位同步后集群状态收敛
- **WHEN** 集群中所有 master 的槽位已通过 Gossip 在各节点间同步
- **THEN** 每个节点的 `cluster_slots_assigned` 应为 16384
- **AND** `cluster_state` 应为 `ok`（在多数 master 可用的前提下）
