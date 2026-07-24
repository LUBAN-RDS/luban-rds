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
