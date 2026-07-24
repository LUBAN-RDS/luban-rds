# Tasks: 修复集群故障转移后槽位归属不一致

- [x] 1. 在 `FailoverManager.performFailover()` 槽位转移循环中增加 `clusterConfig.setSlotOwner(i, slaveNode.getNodeId())` 调用
- [x] 2. 在 `FailoverManager.onFailoverResult()` 槽位转移循环中增加 `clusterConfig.setSlotOwner(i, winner.getNodeId())` 调用
- [x] 3. 在 `ClusterCommandHandler.performFailoverLocally()` 槽位转移循环中增加 `clusterConfig.setSlotOwner(i, slaveNode.getNodeId())` 调用（降级路径一致性修复）
- [x] 4. 运行 `mvn test -pl luban-rds-cluster -Dtest=ClusterFailoverTest` 确认现有测试通过 (16/16 pass)
- [x] 5. 运行 `mvn test -pl luban-rds-cluster -Dtest=FailoverManagerTest` 确认现有测试通过 (14/14 pass)
- [x] 6. 运行 `mvn test -pl luban-rds-server -Dtest=ClusterModeIntegrationTest` 确认集成测试通过 (8/8 pass)
- [x] 7. 运行全量 `mvn test -pl luban-rds-cluster` 确认 337 测试全通过
