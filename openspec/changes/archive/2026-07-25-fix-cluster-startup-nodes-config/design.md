## Context

集群首次启动时 `NettyRedisServer.initClusterMode()` 会创建当前节点并调用 `saveClusterConfig()`。日志显示保存方法进入并记录成功，但真实运行目录中没有 `nodes.conf`，因此必须保证目录创建、临时文件写入、替换目标和异常日志形成可验证闭环。现有代码使用字符串拼接路径和 `FileWriter`，对父目录、临时文件清理和 Windows 文件替换异常的处理不足。

## Goals / Non-Goals

**Goals:**
- 首次集群启动可靠创建目标 `nodes.conf`。
- 在目标目录缺失时创建目录，并在目录创建失败时明确报告错误。
- 使用同目录临时文件完成写入，替换失败时保留可诊断信息并清理临时文件。
- 保持现有 nodes.conf 格式和公开行为不变，并增加回归测试。

**Non-Goals:**
- 不改变集群协议、节点 ID 算法或拓扑选举逻辑。
- 不新增外部依赖或修改配置项语义。

## Decisions

1. **在持久化器内部确保父目录存在。** `ClusterConfigPersister.save` 将以 `Files.createDirectories(target.toAbsolutePath().getParent())` 作为写入前置条件，而不依赖调用方提前创建目录。这样所有调用路径都具备一致行为。
2. **临时文件与目标文件使用同一规范化父目录。** 通过 `target.resolveSibling(target.getFileName() + ".tmp")` 创建临时文件，避免相对路径、工作目录和跨盘移动造成不一致。
3. **保留原子移动并对 Windows 降级。** 先尝试 `ATOMIC_MOVE + REPLACE_EXISTING`，遇到不支持时使用普通替换；在任意异常中删除临时文件并重新抛出 IOException。相比直接删除目标文件，优先移动可避免短暂无文件窗口。
4. **增强启动错误可见性。** `NettyRedisServer.saveClusterConfig` 记录绝对路径、目录和异常堆栈；不伪造“保存成功”日志。持久化失败仍不阻止服务器启动，但日志必须明确失败。
5. **回归测试优先覆盖真实文件系统。** 使用临时目录验证首次保存生成非空目标文件、临时文件不残留及重复保存可覆盖；不依赖特定操作系统是否支持原子移动。

## Risks / Trade-offs

- [Risk] 普通移动在极端崩溃时可能留下旧文件或临时文件 → 使用同目录临时文件、异常清理并保留原子移动优先路径。
- [Risk] 目录无写权限时启动仍可继续但节点身份未持久化 → 输出包含绝对路径和完整异常的 ERROR 日志，便于运维发现。
- [Risk] 修改持久化器可能影响现有测试中的相对路径 → 保持文件格式、方法签名和覆盖语义不变。

## Migration Plan

无需数据迁移。升级后首次启动会自动创建缺失目录和 `nodes.conf`；已有文件按原格式加载。若部署出现问题，可回滚应用版本，保留生成的 nodes.conf。

## Open Questions

无。
