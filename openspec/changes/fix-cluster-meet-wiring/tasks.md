# Tasks: fix-cluster-meet-wiring

## 修复任务

- [x] 1. 修改 `NettyRedisServer.initChannel`：用 7 参数构造方法创建 `RedisServerHandler`，并在 `clusterEnabled` 时调用 `setClusterCommandHandler` 注入集群命令处理器
- [x] 2. 编译 `luban-rds-server` 模块确认无编译错误，并运行集群相关测试确认无回归
