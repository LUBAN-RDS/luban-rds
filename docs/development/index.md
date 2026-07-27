---
title: 开发指南
---

# 开发指南

欢迎参与 Luban-RDS 项目的开发！本部分提供开发环境搭建、开发流程、代码规范等相关指南，帮助您快速上手并贡献代码。

## 目录

- **[环境搭建](setup.md)** — 开发环境配置与项目构建
- **[构建和测试](build.md)** — 项目构建与测试流程
- **[开发流程](process.md)** — 规范化的开发流程说明
- **[代码规范](standards.md)** — 编码规范与最佳实践
- **[代码风格指南](code-style.md)** — 详细的代码风格规范
- **[测试指南](testing.md)** — 单元测试与集成测试指南
- **[贡献指南](contributing.md)** — 如何为项目贡献代码
- **[开发路线图](roadmap.md)** — 版本规划与功能路线图

## 开发环境要求

| 工具 | 版本要求 | 说明 |
|------|---------|------|
| **JDK** | 17+ | 推荐 OpenJDK 17 或 Oracle JDK 17 |
| **Maven** | 3.6.3+ | 项目构建管理工具；仓库根目录提供 `mvn-java17.bat` 自动指向本地 JDK 17 + Maven 3.6.3 |
| **Git** | 任意版本 | 版本控制工具 |
| **IDE** | - | 推荐 IntelliJ IDEA 或 Eclipse |

## 快速开始

```bash
# 1. 克隆代码库
git clone https://github.com/janeluo/luban-rds.git
cd luban-rds

# 2. 构建项目（生成 bin fat JAR：luban-rds-bin/target/luban-rds-jar-with-dependencies.jar）
mvn clean install -DskipTests

# 3. 运行测试
mvn test

# 4. 启动服务器（推荐使用 bin 模块 fat JAR）
java -jar luban-rds-bin/target/luban-rds-jar-with-dependencies.jar
```

## 开发流程

```
克隆代码 → 环境搭建 → 编写代码 → 运行测试 → 提交 PR → 代码审查 → 合并代码
```

详细步骤请参考各个子章节的内容。
