## ADDED Requirements

### Requirement: 集群模式下多键命令的 CROSSSLOT 校验

集群模式（`cluster-enabled yes`）下，所有涉及多个键的命令 MUST 在执行前校验全部键落在同一 hash slot，否则 MUST 返回 `-CROSSSLOT Keys in request don't hash to the same slot`。校验覆盖的命令包括但不限于：`MGET`、`MSET`、`MSETNX`、`DEL`、`EXISTS`、`UNLINK`、`TOUCH`、`SUNION`、`SINTER`、`SDIFF`、`SMOVE`、`SDIFFSTORE`、`SINTERSTORE`、`SUNIONSTORE`、`ZUNIONSTORE`、`ZINTERSTORE`、`BITOP`、`SORT ... STORE`。对于源+目标型命令（`RENAME`、`RENAMENX`、`COPY`），MUST 同时校验源键和目标键落在同一 slot。`EVAL`/`EVALSHA` 的 CROSSSLOT 校验（按 `numkeys` 遍历 KEYS）保持现有行为不变。

#### Scenario: MGET 跨槽被拒绝

- **WHEN** 集群模式下客户端发送 `MGET key1 key2`，且 `key1` 与 `key2` 落在不同 hash slot
- **THEN** 系统返回 `-CROSSSLOT Keys in request don't hash to the same slot`
- **AND** 不执行任何键的读取

#### Scenario: MSET 同槽正常执行

- **WHEN** 集群模式下客户端发送 `MSET {tag}k1 v1 {tag}k2 v2`，两键通过 hash tag 落在同一 slot
- **THEN** 命令正常执行并返回 `+OK`

#### Scenario: DEL 多键跨槽被拒绝

- **WHEN** 集群模式下客户端发送 `DEL key1 key2 key3`，其中 `key2` 落在不同 slot
- **THEN** 系统返回 `-CROSSSLOT`
- **AND** 不删除任何键

#### Scenario: RENAME 源目标不同槽被拒绝

- **WHEN** 集群模式下客户端发送 `RENAME srcKey dstKey`，两键落在不同 slot
- **THEN** 系统返回 `-CROSSSLOT`
- **AND** 不执行重命名

#### Scenario: EVAL 的 CROSSSLOT 校验保持不变

- **WHEN** 集群模式下客户端发送 `EVAL script 2 key1 key2`，两键不同 slot
- **THEN** 系统返回 `-CROSSSLOT`（沿用现有 `checkCrossSlotForScript` 逻辑）

#### Scenario: 非集群模式不校验 CROSSSLOT

- **WHEN** 非集群模式（`cluster-enabled no`）下客户端发送 `MGET key1 key2`，两键不同 slot 也无所谓
- **THEN** 命令正常执行，不返回 CROSSSLOT 错误

### Requirement: MIGRATE 多键迁移的原子性

`MIGRATE host port "" dest-db timeout [COPY] [REPLACE] KEYS k1 k2 ...` 多键迁移 MUST 保证原子性：要么全部键成功迁移到目标节点，要么全部不迁移（源端不删除）。源端 DEL 操作 MUST 在目标端全部键 ACK 成功后统一执行。任一阶段失败时，源端 MUST NOT 删除已 dump 的键，由调用方决定重试。单条批量迁移消息大小 MUST 限制在 64MB 以内，超限时返回 `-ERR command keys batch too large` 并提示分批。

#### Scenario: 全部成功迁移并删除源

- **WHEN** 客户端发送 `MIGRATE host port "" 0 5000 KEYS k1 k2 k3`，目标端全部 RESTORE 成功
- **THEN** 返回 `+OK`
- **AND** 源端 k1、k2、k3 被统一删除（在全部 ACK 后）

#### Scenario: 部分失败时源端不删除

- **WHEN** 客户端发送 `MIGRATE host port "" 0 5000 KEYS k1 k2 k3`，目标端 k2 RESTORE 失败
- **THEN** 返回 `-ERR partial migration: 2 succeeded, 1 failed` 或类似错误
- **AND** 源端 k1、k2、k3 均不被删除（即使 k1、k3 在目标端已落地）
- **AND** 调用方可重试整个 MIGRATE（目标端 RESTORE 幂等，REPLACE 模式覆盖）

#### Scenario: COPY 模式不删除源

- **WHEN** 客户端发送 `MIGRATE host port "" 0 5000 COPY KEYS k1 k2`，目标端全部成功
- **THEN** 返回 `+OK`
- **AND** 源端 k1、k2 保留不删除

#### Scenario: 批量消息超限被拒绝

- **WHEN** 客户端发送的 KEYS 列表 dump 总大小超过 64MB
- **THEN** 返回 `-ERR command keys batch too large`
- **AND** 不发起任何网络传输，源端不删除
