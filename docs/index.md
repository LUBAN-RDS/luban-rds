---
title: Luban-RDS 文档
last_updated: 2026-03-06
version: 1.0.0-SNAPSHOT
---

<div align="center">

# ⚡ Luban-RDS

**轻量级 · 高性能 · 嵌入式 Java 内存数据库**

<p style="display: flex; justify-content: center; gap: 8px; flex-wrap: wrap;">
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/Java-17+-green.svg" alt="Java">
  <img src="https://img.shields.io/badge/Maven-3.6+-blue.svg" alt="Maven">
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7.18-green.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Netty-4.2.10.Final-orange.svg" alt="Netty">
  <img src="https://img.shields.io/badge/Redis-Protocol%20Compatible-red.svg" alt="Redis Compatible">
</p>

---

**兼容 Redis 协议** | **嵌入式设计** | **零依赖部署**

</div>

---

## 🎯 项目简介

**Luban-RDS** 是一个轻量级、高性能、兼容 Redis 协议的嵌入式 Java 内存数据库。专为需要嵌入式缓存或内存数据库场景设计，无需独立部署 Redis 服务，即可享受完整的 Redis 功能。

### 核心优势

| 特性 | 描述 |
|:---:|:---|
| 🚀 **高性能** | 基于 Netty NIO 框架，优化的内存管理，纳秒级命令执行延迟 |
| 🔌 **嵌入式** | 无需独立进程，直接嵌入 JVM 应用，零运维成本 |
| 📡 **协议兼容** | 完整支持 RESP 协议，兼容所有 Redis 客户端 |
| 💾 **持久化** | 支持 RDB 快照和 AOF 日志双重持久化机制 |
| 🔒 **安全可控** | Lua 脚本沙箱模式，细粒度权限控制 |
| 📊 **可观测** | 内置 MONITOR、SLOWLOG、MEMORY 监控命令，实时性能追踪 |
| 🌱 **Spring 集成** | 提供 Spring Boot Starter，开箱即用 |

---

## 📚 文档导航

### 🚀 快速入门
**[开始使用 →](./guide/quickstart.md)** — 5 分钟快速上手 Luban-RDS  
环境要求与安装 · 基本配置与启动 · 第一个示例程序

### 🏗️ 架构设计
**[深入了解 →](./architecture/index.md)** — 系统架构与设计原理  
[系统架构](./architecture/system.md) · [核心特性](./architecture/features.md) · [设计文档](./architecture/design.md)

### 📖 使用指南
**[详细教程 →](./guide/index.md)** — 完整的使用说明与最佳实践  
[安装指南](./guide/installation.md) · [基本用法](./guide/basic-usage.md) · [高级功能](./guide/advanced.md) · [代码示例](./guide/examples.md)

### 📡 API 参考
**[查阅文档 →](./api/index.md)** — 完整的 API 文档与命令参考  
[命令列表](./api/commands.md) · [核心接口](./api/core.md) · [协议说明](./api/protocol.md)

### 📜 Lua 脚本
**[脚本编程 →](./lua/index.md)** — Lua 脚本引擎详细说明  
[脚本 API](./lua/api.md) · [使用指南](./lua/usage.md)

### 🛠️ 部署运维
**[运维指南 →](./deployment/index.md)** — 生产环境部署与维护  
[配置指南](./deployment/configuration.md) · [安装部署](./deployment/installation.md) · [监控维护](./deployment/monitoring.md) · [故障排查](./deployment/troubleshooting.md)

---

## ✨ 版本特性

### v1.0.0-SNAPSHOT (当前版本)

#### 🎨 核心功能
- ✅ **数据结构**: 完整支持 String、List、Hash、Set、ZSet 五大核心数据结构
- ✅ **过期机制**: 支持 Key 级别的 TTL 过期策略
- ✅ **事务支持**: MULTI/EXEC/DISCARD/WATCH 事务命令

#### 📡 协议与网络
- ✅ **RESP 协议**: 完整实现 Redis Serialization Protocol，支持 RESP2 和 RESP3
- ✅ **Pipeline**: 支持命令管道，批量执行提升性能
- ✅ **Pub/Sub**: 发布订阅模式，支持频道订阅、模式订阅和流订阅

#### 💾 持久化
- ✅ **RDB 快照**: 内存数据快照持久化，使用 Kryo 序列化
- ✅ **AOF 日志**: 命令追加式持久化

#### 🔧 脚本与扩展
- ✅ **Lua 脚本**: 集成 LuaJ 引擎，支持 EVAL/EVALSHA
- ✅ **沙箱模式**: 可配置的安全脚本执行环境
- ✅ **Redis API**: 完整的 `redis.call()` / `redis.pcall()` / `redis.sha1hex()` 支持

#### 📊 监控与性能
- ✅ **MONITOR**: 实时命令监控（<40ns 开销）
- ✅ **SLOWLOG**: 慢查询日志记录
- ✅ **MEMORY**: 内存使用分析和诊断
- ✅ **INFO**: 服务器状态信息聚合
- ✅ **Benchmark**: 内置性能测试工具

#### 🌱 生态集成
- ✅ **Spring Boot**: 官方 Starter 自动配置支持

---

## 🤝 参与贡献

我们欢迎所有形式的贡献！

**[贡献指南 →](./development/index.md)**

- [环境搭建](./development/setup.md)
- [构建指南](./development/build.md)
- [测试指南](./development/testing.md)
- [代码规范](./development/code-style.md)
- [贡献流程](./development/contributing.md)

---

## 📄 许可证

Luban-RDS 采用 **[Apache License 2.0](./legal/license.md)** 许可证开源。

---

<div align="center">

**[⬆ 返回顶部](#-luban-rds)**

Made with ❤️ by Luban-RDS Team

</div>
