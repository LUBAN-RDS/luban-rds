## Why

集群模式启动日志显示初始化流程完成且报告已保存 `nodes.conf`，但实际文件不存在，导致重启时节点 ID 和集群拓扑无法恢复。根因是持久化路径的父目录/临时文件替换流程在真实启动环境中没有得到可靠验证，且保存异常仅记录后继续启动，掩盖了启动持久化失败。

## What Changes

- 修复集群首次启动时 `nodes.conf` 的创建与原子替换流程，确保目标目录和临时文件处理可靠。
- 增强保存失败日志，包含目标路径及异常上下文，便于诊断启动问题。
- 增加回归测试，验证首次集群启动能够实际生成非空 `nodes.conf`，并覆盖 Windows/不支持原子移动时的降级行为。

## Capabilities

### New Capabilities

### Modified Capabilities
- `cluster-automatic-failover`: 集群启动时必须持久化节点配置，保证节点身份和拓扑可恢复。

## Impact

- 影响 `NettyRedisServer` 集群初始化和 `ClusterConfigPersister` 文件持久化实现。
- 不改变 Redis 协议或对外 API；仅修复启动时配置文件落盘和错误诊断。
- 仅涉及本地文件系统，不引入新依赖。
