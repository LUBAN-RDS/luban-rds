# Proposal: fix-cluster-meet-wiring

## 问题描述

集群模式下执行 `CLUSTER MEET <ip> <port>` 命令时报错，客户端连接后立即断开，MEET 操作未生效。

复现日志：

```
11:11:34.816 DEBUG c.j.l.rds.server.RedisServerHandler - Command: CLUSTER Args: [CLUSTER, MEET, 192.10.0.128, 9737]
11:11:34.816 DEBUG c.j.l.rds.server.RedisServerHandler - Processing command: CLUSTER In Pub/Sub mode: false
11:11:34.816 DEBUG c.j.l.rds.server.RedisServerHandler - Handling CLUSTER command
11:11:34.817 INFO  c.j.l.rds.server.RedisServerHandler - Client disconnected: /192.10.0.128:58640
```

日志只打印到 `Handling CLUSTER command`，之后客户端立即断开；`ClusterCommandHandler.clusterMeet` 与 `GossipProtocol.sendMeet` 中的任何 INFO 日志均未出现，说明命令未真正进入 MEET 处理路径。

## 根因分析

`NettyRedisServer.initChannel`（`luban-rds-server/.../NettyRedisServer.java:488-494`）在构建 Netty pipeline 时，使用 4 参数构造方法创建 `RedisServerHandler`：

```java
pipeline.addLast(businessGroup, "handler",
        new RedisServerHandler(memoryStore, commandHandler, protocolParser, config.getTimeout()));
```

该 4 参数构造方法（`RedisServerHandler.java:196-198`）委托给 7 参数构造方法，传入 `clusterEnabled=false`、`clusterConfig=null`、`slotManager=null`，且后续**从未调用** `setClusterCommandHandler(...)` / `setReplicationCommandHandler(...)`。

因此运行时每个 `RedisServerHandler` 实例中：

- `clusterCommandHandler == null`
- `clusterEnabled == false`、`clusterConfig == null`、`slotManager == null`

`CLUSTER` 命令进入 `RedisServerHandler.java:548-570` 分支后，因 `clusterCommandHandler == null`，走到 562-568 行返回 `-ERR cluster command not configured\r\n`。客户端收到错误后断开连接，与日志现象一致。

对比：测试代码（`testinfra/TestNode.java:89`、`AbstractClusterHandlerTest.java:59`、`ClusterCommandIntegrationTest.java:178`、`ClusterRedirectIntegrationTest.java:288`）均在构造 handler 后显式调用 `handler.setClusterCommandHandler(clusterCommandHandler)`，所以测试通过、生产失败——这是典型的「测试与生产装配不一致」缺陷。

附带影响：除 `CLUSTER MEET` 外，所有 `CLUSTER` 子命令（INFO/NODES/ADDSLOTS/...）、集群 `MOVED`/`ASK` 重定向、复制命令处理器在生产装配下同样失效，根因相同。

## 修复目标

让生产 Netty pipeline 与测试装配一致：集群模式启动时，把已初始化好的 `clusterEnabled` / `clusterConfig` / `slotManager` / `clusterCommandHandler` / `replicationCommandHandler` 注入到每个新建连接的 `RedisServerHandler`，使 `CLUSTER MEET` 能正常进入 `clusterMeet` → `GossipProtocol.sendMeet` 路径并返回 `+OK`。
