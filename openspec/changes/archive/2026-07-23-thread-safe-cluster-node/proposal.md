# Proposal: ClusterNode 线程安全

## 动机
`ClusterNode` 是集群拓扑的核心数据模型，被 3 类线程并发读写：
1. `gossip-protocol` 调度线程（GossipProtocol, FailureDetector）
2. Netty I/O 线程（ClusterBusHandler → FailoverManager）
3. 业务线程（RedisServerHandler → ClusterCommandHandler）

`EnumSet<ClusterNodeState>` 和 `BitSet slots` 均非线程安全，并发修改可能导致状态位损坏、槽位数据错乱。

## 目标
给 `ClusterNode` 的状态变更方法添加 `synchronized` 保护，消除竞态条件。

## 范围
仅修改 `ClusterNode.java`，1 个文件。
