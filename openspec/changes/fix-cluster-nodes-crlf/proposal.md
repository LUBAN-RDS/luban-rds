## Why

使用 Redisson 客户端连接 Luban-RDS 集群时，`CLUSTER NODES` 响应导致 `org.redisson.client.protocol.decoder.ClusterNodesDecoder.decode` 抛出 `NumberFormatException`，集群连接初始化失败。

根因：`ClusterCommandHandler.clusterNodes()` 用 `\r\n` 拼接每个节点行，响应经 RESP bulk-string 封装后 `\r\n` 残留在 payload 中。Redisson 的 `ClusterNodesDecoder.decode`（line 49）用 `response.split("\n")` 切行，每行末尾残留一个 `\r`，使末尾 slot 字段解析为 `"0-5460\r"`，`Integer.valueOf("5460\r")` 抛 `NumberFormatException`。

真实 Redis 的 `CLUSTER NODES` bulk payload 每行以裸 `\n` 结尾（`clusterGenNodesDescription` 中 `sdscatlen(ni,"\n",1)`），客户端据此解析。Luban-RDS 当前的 `\r\n` 偏离了真实 Redis 的线协议契约。

## What Changes

- `ClusterCommandHandler.clusterNodes()` 中每行行尾由 `\r\n` 改为裸 `\n`，与真实 Redis `CLUSTER NODES` bulk payload 行尾一致，确保 Redisson / Jedis / Lettuce 等集群客户端正确解析 slot 字段。

## Capabilities

### New Capabilities
<!-- None -->

### Modified Capabilities
- `cluster-commands`: `CLUSTER NODES` bulk payload 行尾符由 `\r\n` 改为 `\n`，对齐真实 Redis 线协议，保证集群客户端可解析。

## Impact

- `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/handler/ClusterCommandHandler.java`: `clusterNodes()` 行尾符修正。
- 客户端兼容性：修复 Redisson 集群连接初始化失败的 `NumberFormatException`；Jedis / Lettuce 对行尾 `\r` 容错较强但同样受益于协议对齐。
- 无 API、无配置、无持久化格式变更。
