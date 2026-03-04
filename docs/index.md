---
title: Luban-RDS 文档
last_updated: 2026-03-04
version: 1.0.0-SNAPSHOT
---

# Luban-RDS 文档

欢迎阅读 Luban-RDS 文档。Luban-RDS 是一个轻量级、高性能、兼容 Redis 协议的嵌入式 Java 内存数据库。

## 文档目录

### [快速入门](./guide/quickstart.md)
了解如何安装、启动和使用 Luban-RDS。

### [架构设计](./architecture/index.md)
深入了解 Luban-RDS 的核心架构、设计原则和实现细节。
- [系统架构](./architecture/system.md)
- [核心特性](./architecture/features.md)
- [设计文档](./architecture/design.md)

### [使用指南](./guide/index.md)
详细的使用说明和最佳实践。
- [安装指南](./guide/installation.md)
- [基本用法](./guide/basic-usage.md)
- [高级功能](./guide/advanced.md)
- [代码示例](./guide/examples.md)

### [API 参考](./api/index.md)
完整的 API 文档和命令参考。
- [命令列表](./api/commands.md)
- [核心接口](./api/core.md)
- [协议说明](./api/protocol.md)

### [Lua 脚本](./lua/index.md)
Lua 脚本引擎的详细说明和 API 参考。
- [脚本 API](./lua/api.md)
- [使用指南](./lua/usage.md)

### [部署运维](./deployment/index.md)
生产环境部署、配置和监控指南。
- [配置指南](./deployment/configuration.md)
- [安装部署](./deployment/installation.md)
- [监控维护](./deployment/monitoring.md)
- [故障排查](./deployment/troubleshooting.md)

### [开发贡献](./development/index.md)
参与 Luban-RDS 开发的指南。
- [环境搭建](./development/setup.md)
- [构建指南](./development/build.md)
- [测试指南](./development/testing.md)
- [代码规范](./development/code-style.md)
- [贡献流程](./development/contributing.md)

## 版本历史

### v1.0.0-SNAPSHOT (当前版本)
- **核心功能**: 支持 String, List, Hash, Set, ZSet 数据结构。
- **协议兼容**: 完整支持 RESP 协议。
- **Lua 脚本**: 集成 LuaJ 引擎，支持沙箱模式和丰富的 Redis API。
- **持久化**: 支持 RDB 和 AOF 持久化机制。
- **监控**: 新增 `MONITOR` 命令和 `SLOWLOG` 支持。
- **性能**: 引入 `luban-rds-benchmark` 模块，优化内存管理。
- **Spring 集成**: 提供 Spring Boot Starter。

## 许可证

Luban-RDS 采用 [Apache License 2.0](./legal/license.md) 许可证开源。
