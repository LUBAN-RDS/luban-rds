## Context

`CLUSTER NODES` 返回集群拓扑文本，经 `RedisServerHandler` 调用 `protocolParser.serialize(response)` 封装为 RESP bulk string。bulk payload 的内容由 `ClusterCommandHandler.clusterNodes()` 直接生成。

当前实现（`ClusterCommandHandler.java:264`）每行以 `sb.append("\r\n")` 结尾。RESP bulk string 仅在首尾添加 `$<len>\r\n` / `\r\n` 框架，payload 内部的 `\r\n` 原样保留。

真实 Redis 在 `clusterGenNodesDescription` 中对每行使用 `sdscatlen(ni,"\n",1)` —— 即裸 `\n`，payload 内部不含 `\r`。

约束：
- Luban-RDS 需对齐真实 Redis 线协议，保证 Redisson / Jedis / Lettuce 等集群客户端解析 `CLUSTER NODES`。
- 不改变字段顺序、不改变 RESP 封装方式，仅修正 payload 行尾符。

## Goals / Non-Goals

**Goals:**
- `CLUSTER NODES` bulk payload 每行以裸 `\n` 结尾，与真实 Redis 一致。
- 消除 Redisson `ClusterNodesDecoder.decode` 解析 slot 字段时的 `NumberFormatException`。

**Non-Goals:**
- 不修改 RESP bulk string 的框架封装（`$len\r\n...\r\n`）——框架层 `\r\n` 由 `RedisProtocolParser.serializeBulkString` 负责，符合 RESP 规范。
- 不调整 `formatNodeInfo`（`CLUSTER SLAVES`/`REPLICAS`）与 `ClusterConfigPersister.formatNodeLine`（`nodes.conf` 持久化）——前者每条经独立 bulk string 封装且 RESP 框架已提供分隔，后者为本地文件格式，非客户端线协议。
- 不重构 `clusterNodes()` 的字段拼接逻辑。

## Decisions

**决策 1：仅修改 `clusterNodes()` 行尾为 `\n`。**

- 选项 A（采纳）：将 `sb.append("\r\n")` 改为 `sb.append("\n")`。
  - 理由：真实 Redis 即此行为；改动最小；直接消除 Redisson 解析失败根因。
- 选项 B（否决）：在 `RedisProtocolParser.serializeBulkString` 中对 bulk payload 做 `\r\n` → `\n` 归一化。
  - 否决理由：影响所有 bulk string 命令（如 `CLUSTER INFO`、`INFO` 等按行返回的命令本就依赖 `\r\n` 分行），副作用面过大，且 `CLUSTER INFO` 的 `\r\n` 是真实 Redis 行为（其 payload 每行 `\r\n`）。归一化会破坏其他命令的兼容性。

**决策 2：不动 `formatNodeInfo` 与 `ClusterConfigPersister`。**

- `formatNodeInfo` 被 `clusterSlaves()` 以 RESP array of bulk strings 形式返回，每条节点信息是独立 bulk string，RESP 框架 `\r\n` 已分隔条目，行尾符不影响客户端解析。
- `ClusterConfigPersister.formatNodeLine` 写入 `nodes.conf` 本地文件，真实 Redis 的 `nodes.conf` 行尾为 `\n`，但这是文件格式而非线协议，超出本次 hotfix 范围（独立单点，且无客户端报错佐证）。本次不修改以保持 hotfix 范围最小。

## Risks / Trade-offs

- [风险] 修改后 `CLUSTER NODES` 行尾 `\n` 与某些假设 `\r\n` 的内部测试断言冲突 → 缓解：现有 `ClusterCommandHandlerTest.testClusterNodes` 仅用 `contains` 断言（node id、地址、`myself,master`），不校验行尾；运行测试确认。
- [风险] `formatNodeInfo` / `ClusterConfigPersister` 仍用 `\r\n`，未来若 Redisson 解析 `CLUSTER SLAVES` 出现同类问题需再修 → 缓解：记录于 Non-Goals，作为独立 issue 跟踪，本次不扩大范围。
- [权衡] payload 行尾由 `\r\n` 改 `\n` 会使 `CLUSTER NODES` 响应字节数减少，但 RESP bulk 框架的 `$len` 由 `serializeBulkString` 按实际字节数计算，长度自动正确，无需额外处理。
