## ADDED Requirements

### Requirement: CLUSTER NODES 线协议行尾符

`CLUSTER NODES` 响应的 RESP bulk string payload 中，每个节点行 MUST 以裸 `\n`（LF）结尾，不得包含 `\r`（CR），以对齐真实 Redis 的 `clusterGenNodesDescription` 行为。RESP bulk string 的框架（`$<len>\r\n` 头与尾部 `\r\n`）仍按 RESP 规范封装，但 payload 内部行分隔符 MUST 为 `\n`。

#### Scenario: 集群客户端可解析 CLUSTER NODES 的 slot 字段

- **WHEN** 集群拓扑收敛且存在持有 slot 区间的 master 节点
- **AND** 客户端（如 Redisson）发送 `CLUSTER NODES` 并用 `response.split("\n")` 切行
- **THEN** 每行末尾不得残留 `\r`
- **AND** 末尾 slot 字段（如 `0-5460`）可被 `Integer.parseInt` 成功解析
- **AND** 不抛出 `NumberFormatException`

#### Scenario: 持有非连续 slot 的节点行可被正确解析

- **WHEN** 某 master 节点持有非连续 slot（如 slot 0 与 slot 100）
- **AND** `CLUSTER NODES` 该行末尾字段为 `0 100`（空格分隔多段 slot 区间）
- **THEN** 该行以 `\n` 结尾，每段 slot token（`0`、`100`）不含 `\r`
- **AND** 客户端可逐段 `Integer.parseInt` 解析成功
