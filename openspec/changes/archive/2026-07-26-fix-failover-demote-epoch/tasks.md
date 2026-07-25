## 1. 修复 onFailoverResult 旧主 configEpoch

- [x] 1.1 `FailoverManager.onFailoverResult()` 中旧 master 降级为 SLAVE 时，设置 `node.setConfigEpoch(msg.getNewConfigEpoch())`

## 2. 修复 nodes.conf header 一致性

- [x] 2.1 `ClusterConfigPersister.save()` 中 `# My Config Epoch` header 改用 MYSELF 节点的实际 configEpoch 写入

## 3. 验证

- [x] 3.1 运行 `luban-rds-cluster` 模块全部测试确认无回归
- [x] 3.2 运行完整项目构建（Java 17）确认无回归
