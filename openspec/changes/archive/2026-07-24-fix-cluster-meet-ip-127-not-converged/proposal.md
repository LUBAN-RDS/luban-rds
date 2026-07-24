## Why

集群模式启动后 Redisson 连接报 CLUSTERDOWN。追踪发现根因是 `GossipProtocol.handleMeet()` 在收到已存在节点的 MEET 消息时从不更新对方地址。当用户通过 `CLUSTER MEET 127.0.0.1 <port>` 建连后，对端节点的 IP 被记录为 127.0.0.1 且永不收敛为真实 IP（如 192.10.0.125），导致 CLUSTER NODES 输出包含不一致地址，Redisson 解析后产生混乱的拓扑视图。

## What Changes

- **修复 `GossipProtocol.handleMeet()`**：收到已存在节点的 MEET 消息时，检查并更新该节点的 IP/Port/BusPort 为 MEET 消息中通告的真实地址

## Impact

- 受影响文件：`luban-rds-cluster/.../gossip/GossipProtocol.java` — `handleMeet()` 方法
