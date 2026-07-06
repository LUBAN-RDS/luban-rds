# Tasks: fix-cluster-nodes-crlf

## 1. 修复 CLUSTER NODES 行尾符

- [x] 1.1 将 `ClusterCommandHandler.clusterNodes()` 中每行行尾 `\r\n` 改为 `\n`
- [x] 1.2 新增/补充单元测试断言 `CLUSTER NODES` 行尾为 `\n` 且 slot 字段无残留 `\r`，覆盖连续区间与非连续多段 slot
- [x] 1.3 运行 `luban-rds-cluster` 模块测试确认通过

## 2. 验证

- [x] 2.1 运行 `luban-rds-cluster` 模块测试与相关 cluster 集成测试
- [x] 2.2 确认未引入对 `formatNodeInfo` / `ClusterConfigPersister` 的改动（范围保持）
