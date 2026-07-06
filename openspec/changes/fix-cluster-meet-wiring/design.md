# Design: fix-cluster-meet-wiring

## 修复方案

在 `NettyRedisServer` 构建管道时，使用 7 参数（完整）构造方法创建 `RedisServerHandler`，并显式注入集群与复制相关依赖，使其与测试装配保持一致。

### 改动点

**文件：`luban-rds-server/src/main/java/com/janeluo/luban/rds/server/NettyRedisServer.java`**

`initChannel`（约 488-494 行）当前：

```java
pipeline.addLast(businessGroup, "handler",
        new RedisServerHandler(memoryStore, commandHandler, protocolParser, config.getTimeout()));
```

改为：

```java
RedisServerHandler handler = new RedisServerHandler(
        memoryStore, commandHandler, protocolParser, config.getTimeout(),
        clusterEnabled, clusterConfig, slotManager);
// 集群模式：注入已初始化的集群命令处理器
if (clusterEnabled && clusterCommandHandler != null) {
    handler.setClusterCommandHandler(clusterCommandHandler);
}
pipeline.addLast(businessGroup, "handler", handler);
```

### 为什么这样改

1. **单一根因**：生产装配遗漏了 setter 调用，导致 `clusterCommandHandler` 为 `null`。直接在装配处补齐注入即可，无需改动 `RedisServerHandler` / `ClusterCommandHandler` / `GossipProtocol` 的任何逻辑。
2. **与测试一致**：测试代码正是「构造 + setter 注入」模式（见 `TestNode.java:85-89`），本修复让生产走相同路径，消除装配差异。
3. **顺带修正 `clusterEnabled`/`clusterConfig`/`slotManager`**：4 参数构造方法把这些字段设为 `false`/`null`/`null`，即使补了 `clusterCommandHandler`，集群重定向（`MOVED`/`ASK`）等依赖这些字段的逻辑仍会失效。改用 7 参数构造方法一次性修正。
4. **非集群模式不受影响**：`clusterEnabled=false` 时 7 参数构造方法行为与 4 参数完全等价（`clusterConfig`/`slotManager` 为 `null`），且 `if (clusterEnabled ...)` 守卫确保不会误注入。

### 不改动的部分

- `RedisServerHandler`：字段、构造方法、`setClusterCommandHandler` 均已存在，无需修改。
- `ClusterCommandHandler.clusterMeet`、`GossipProtocol.sendMeet`：实现正确，问题不在处理逻辑。
- 不新增接口、不调整架构、不变更数据库 schema。

### 影响范围

- 改动文件数：1（`NettyRedisServer.java`）
- 改动函数：1（`initChannel` 内联匿名 `ChannelInitializer`）
- 不构成 hotfix 升级条件（≤2 文件、单模块、无架构/接口/schema 变更）。

### 验证方式

1. 集群模式启动后，对节点执行 `CLUSTER MEET <ip> <port>`，应返回 `+OK`，并出现 `CLUSTER MEET: ip=..., port=...` 与 `发送 MEET 消息: target=...:...` 日志。
2. `CLUSTER INFO` / `CLUSTER NODES` 应返回集群信息而非 `-ERR cluster command not configured`。
3. 运行既有集群测试套件确认无回归。
