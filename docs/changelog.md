---
title: 更新日志
last_updated: 2026-03-08
version: 1.0.0-SNAPSHOT
---
# Changelog

一个轻量、高性能、兼容 RESP 的 Java 内存键值库，易嵌入与扩展。


## [Unreleased]

### Added

- 完整 RESP3 协议支持，包括新数据类型（Map、Set、Null、Boolean、Double、Big Number）
- 协议版本自动检测和切换，支持 RESP2 和 RESP3 客户端
- 优化 Lua 脚本处理器的字符串编码处理，符合 Redis 规范
- 模式订阅支持（PSUBSCRIBE/PUNSUBSCRIBE）
- 流订阅支持（SSUBSCRIBE/SUNSUBSCRIBE）
- 慢查询日志功能（SLOWLOG GET/LEN/RESET）
- 扩展字符串命令：SETNX, GETSET, SETRANGE, GETRANGE, PSETEX
- 扩展集合命令：SPOP, SRANDMEMBER, SMOVE, SINTER, SUNION, SDIFF
- 扩展有序集合命令：ZREVRANGE, ZRANGEBYSCORE, ZRANK, ZREVRANK, ZCOUNT, ZINCRBY
- 扩展列表命令：LINDEX, LSET, LREM
- 扩展哈希命令：HSETNX, HINCRBY, HSCAN
- 客户端管理命令：CLIENT LIST, CLIENT KILL, CLIENT SETNAME, CLIENT GETNAME
- 键版本控制机制，支持 WATCH 乐观锁

### Changed

- 升级 Netty 版本至 4.2.10.Final
- 升级 Spring Boot 版本至 2.7.18
- 升级 Caffeine 版本至 3.2.3
- 升级 Guava 版本至 33.5.0-jre
- 升级 Kryo 版本至 5.6.0
- RDB 持久化改用 Kryo 序列化框架
- MONITOR 命令支持 DB 和 MATCH 过滤参数

### Deprecated

- 无

### Removed

- 无

### Fixed

- 修复事务执行时的响应序列化问题
- 修复 WATCH 机制在多数据库场景下的键版本检查
- 修复 String.intern() 导致的内存泄漏问题，改用分段锁机制
- 修复过期键竞态条件问题，使用双重检查锁定机制
- 修复 STRLEN 命令返回字符长度而非字节长度的问题
- 修复 MSET 命令缺少原子性保证的问题
- 修复 LRU 淘汰策略性能问题，优化采样算法
- 修复 AOF 持久化命令解析不完整问题，支持 20+ 种命令类型
- 修复 RDB 持久化 ZSet 分数丢失问题，完整保存和恢复分数
- 添加过期键主动清理机制，避免过期键长期占用内存

### Security

- 增强 Lua 脚本沙箱安全性

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
- 批量命令支持：MSET, MGET, HMSET, HMGET, DEL (多键)
- 多元素推入：LPUSH/RPUSH/SADD/ZADD 支持多元素

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
