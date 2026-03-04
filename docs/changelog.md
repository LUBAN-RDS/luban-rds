---
title: 更新日志
last_updated: 2026-03-04
version: 1.0.0-SNAPSHOT
---
# Changelog

一个轻量、高性能、兼容 RESP 的 Java 内存键值库，易嵌入与扩展。


## [Unreleased]

### Added

- 无

### Changed

- 无

### Deprecated

- 无

### Removed

- 无

### Fixed

- 无

### Security

- 无

## [1.0.0] - 2026-03-04

### Added

- 完整 RESP 协议解析与编码
- 内存数据结构与过期支持（String/List/Set/Hash/ZSet）
- Lua 脚本执行（EVAL/EVALSHA/redis.call），沙箱与执行统计
- RDB 与 AOF 持久化机制
- 基于 Netty 的高并发 NIO 服务器
- 发布/订阅：频道订阅与消息广播
- 事务支持：MULTI/EXEC/DISCARD/WATCH
- Spring Boot Starter 自动配置集成
- 内存统计与 MEMORY 命令族
- 高性能 MONITOR 命令与事件管线

### Changed

- 无

### Deprecated

- 无

### Removed

- 无

### Fixed

- 无

### Security

- 无
