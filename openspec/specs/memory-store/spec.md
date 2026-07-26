## ADDED Requirements

### Requirement: ZSet 同分成员按字典序排序

ZSet 中分数相同的成员，MUST 按成员名的字典序（lexicographic，按 unsigned byte 比较）排序。此顺序适用于所有按 score 范围或排名返回成员的命令，包括：`ZRANGE`、`ZRANK`、`ZREVRANGE`、`ZREVRANK`、`ZRANGEBYSCORE`、`ZREVRANGEBYSCORE`、`ZPOPMIN`、`ZPOPMAX`、`ZSCAN`、`ZREMRANGEBYRANK`。对于正向命令（`ZRANGE`、`ZRANK`、`ZPOPMIN`），同分成员按字典序**升序**；对于反向命令（`ZREVRANGE`、`ZREVRANK`、`ZPOPMAX`），同分成员按字典序**降序**。`ZPOPMIN` 在同分时 MUST 弹出字典序最小的成员；`ZPOPMAX` 在同分时 MUST 弹出字典序最大的成员。ZSet 内部存储同分成员集合的数据结构 MUST 使用并发安全的字典序结构（如 `ConcurrentSkipListSet<String>`），保证多线程读写下的排序正确性。

#### Scenario: ZRANGE 同分成员字典序

- **WHEN** ZSet `myzset` 有成员 `a=1.0, c=1.0, b=1.0`（同分）
- **AND** 客户端执行 `ZRANGE myzset 0 -1`
- **THEN** 返回 `a, b, c`（字典序升序）

#### Scenario: ZREVRANGE 同分成员反向字典序

- **WHEN** ZSet `myzset` 有成员 `a=1.0, c=1.0, b=1.0`（同分）
- **AND** 客户端执行 `ZREVRANGE myzset 0 -1`
- **THEN** 返回 `c, b, a`（字典序降序）

#### Scenario: ZPOPMIN 同分弹字典序最小

- **WHEN** ZSet `myzset` 有成员 `banana=2.0, apple=2.0, cherry=2.0`（同分）
- **AND** 客户端执行 `ZPOPMIN myzset 1`
- **THEN** 弹出 `apple`（同分中字典序最小）

#### Scenario: ZPOPMAX 同分弹字典序最大

- **WHEN** ZSet `myzset` 有成员 `banana=2.0, apple=2.0, cherry=2.0`（同分）
- **AND** 客户端执行 `ZPOPMAX myzset 1`
- **THEN** 弹出 `cherry`（同分中字典序最大）

#### Scenario: ZRANK 同分成员排名正确

- **WHEN** ZSet `myzset` 有成员 `a=1.0, c=1.0, b=1.0, d=2.0`
- **AND** 客户端执行 `ZRANK myzset b`
- **THEN** 返回 `:1`（b 在同分组 a,b,c 中排第 2，rank=1）

#### Scenario: ZINCRBY 改分后同分组重新排序

- **WHEN** ZSet `myzset` 有成员 `a=1.0, b=1.0`
- **AND** 客户端执行 `ZINCRBY myzset 0 c`（新增 c=1.0）
- **THEN** 同分组变为 `a, b, c`（字典序）
- **AND** `ZRANGE myzset 0 -1` 返回 `a, b, c`

#### Scenario: 多线程并发 ZADD 排序正确

- **WHEN** 多个线程并发对同一 ZSet 执行 `ZADD` 添加同分成员
- **THEN** 最终 `ZRANGE` 返回的同分成员仍按字典序
- **AND** 不抛 `ConcurrentModificationException`
