# Tasks: 修复 FAIL 状态被过早清除导致 failover 失败

## 1. ClusterNode 新增 failTime 字段并维护

- [x] 1.1 在 `ClusterNode.java` 新增 `private volatile long failTime` 字段（0 表示未标记 FAIL）
- [x] 1.2 在 `addState(ClusterNodeState.FAIL)` 时设置 `failTime = System.currentTimeMillis()`，在 `removeState(ClusterNodeState.FAIL)` 时清零 `failTime`（集中在 addState/removeState 维护，保证所有路径一致）
- [x] 1.3 新增 `public long getFailTime()` getter 与 `public void setFailTime(long)` setter（对齐 setLastPongTime 模式，供测试与恢复场景操作）

## 2. FailureDetector.clearNodeFailState 增加 FAIL 保护期

- [x] 2.1 修改 `clearNodeFailState(String nodeId)`：PFAIL 清除不受影响；FAIL 清除前判断 `System.currentTimeMillis() - node.getFailTime() < 2L * nodeTimeout`，保护期内拒绝清除 FAIL 并记录 debug 日志
- [x] 2.2 保护期过后清除 FAIL 时，同步清除 `confirmedFailNodes` 与 `pfailVotes` 中该节点的记录
- [x] 2.3 PFAIL 清除时不再无条件清除 `pfailVotes`（FAIL 保护期可能仍需要），仅在 FAIL 清除时一并清除

## 3. 编译与测试

- [x] 3.1 运行 `mvn -pl luban-rds-cluster -am compile` 确认编译通过
- [x] 3.2 运行 `mvn -pl luban-rds-cluster test` 确认现有 failover 测试通过（106 tests pass，排除需运行 Redis 的兼容性测试）
- [x] 3.3 新增/补充单元测试：验证保护期内 PONG 不清除 FAIL、保护期后清除 FAIL、PFAIL 仍可清除、addState/removeState 维护 failTime；更新 ClusterFailoverTest.testClearFailState 适配保护期
