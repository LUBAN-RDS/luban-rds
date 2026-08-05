---
title: 使用指南
---

# 使用指南

本部分提供 Luban-RDS 的详细使用指南，涵盖从安装到高级功能的完整说明。

## 指南内容

- **[快速开始](./quickstart.md)** — 5 分钟内快速上手 Luban-RDS
- **[安装指南](./installation.md)** — 不同环境的安装和配置方法
- **[基本使用](./basic-usage.md)** — 常用命令和基本操作
- **[高级功能](./advanced.md)** — 持久化、Lua 脚本等高级特性
- **[使用示例](./examples.md)** — 常见场景的代码示例
- **[性能基准测试（v1.0.15+）](./benchmarking.md)** — `LubanBenchmarkMain` / Cluster / Mesh / Redis 对比套件 + HTML/Markdown 报告输出

## 集群与分布式方案

- **[Redis Cluster 集群部署](../deployment/cluster-setup.md)** — 6+ 节点、16384 slot 分片、最终一致
- **[Mesh 集群（v1.0.15+）](../mesh/index.md)** — 3 节点 Raft 强一致、已确认写入不丢：[快速上手](../mesh/setup.md) · [协议设计要点](../mesh/design.md)

## 适合人群

| 角色 | 推荐内容 |
|------|---------|
| **初学者** | 快速开始 → 基本使用 |
| **开发者** | 高级功能 → API 文档 |
| **运维人员** | 安装指南 → 部署运维 |
| **性能工程师** | 性能基准测试 → Mesh / Cluster / Redis 对比 |

## 学习路径

```
快速开始 → 安装指南 → 基本使用 → 高级功能 → 使用示例
                ↓
       集群方案选择（Redis Cluster vs Mesh）→ 性能基准测试
```

1. **第一步**：阅读 [快速开始](./quickstart.md)，了解基本概念和使用方法
2. **第二步**：参考 [安装指南](./installation.md)，在生产环境部署
3. **第三步**：学习 [基本使用](./basic-usage.md)，掌握常用命令
4. **第四步**：探索 [高级功能](./advanced.md)，使用更复杂的特性
5. **第五步**：查看 [使用示例](./examples.md)，学习实际应用场景
6. **第六步（可选）**：根据一致性与规模需求选择 [Redis Cluster](../deployment/cluster-setup.md) 或 [Mesh 集群](../mesh/index.md)，并跑一次 [性能基准测试](./benchmarking.md) 验证

## 相关资源

- **[API 文档](../api/)** — 详细的 API 接口说明
- **[架构设计](../architecture/)** — 系统架构和设计原理
- **[Mesh 集群设计](../mesh/design.md)** — 3 节点 Raft 协议设计要点
- **[部署运维](../deployment/)** — 生产环境部署和维护
- **[常见问题](../resources/faq.md)** — 常见问题解答
