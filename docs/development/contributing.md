---
title: 贡献指南
---

# 贡献指南

本指南详细说明了如何为 Luban-RDS 项目做出贡献，包括代码提交、文档改进、问题反馈等方面的流程和规范。

## 1. 贡献方式

您可以通过以下方式为 Luban-RDS 项目做出贡献：

- **代码贡献**：实现新功能、修复 bug、优化性能
- **文档贡献**：改进和完善项目文档
- **测试贡献**：编写和运行测试用例
- **问题反馈**：报告 bug、提出功能建议
- **社区支持**：回答社区问题、参与讨论

## 2. 开发环境准备

### 2.1 系统要求

- **操作系统**：Windows 7+, Linux, macOS
- **Java 版本**：Java 8+（推荐 Java 11+）
- **Maven**：Maven 3.8+（构建工具）
- **Git**：Git 2.20+（版本控制）
- **IDE**：推荐 IntelliJ IDEA 或 Eclipse

### 2.2 克隆代码

您可以选择从 GitHub 或 Gitee 克隆代码仓库：

```bash
# 从 GitHub 克隆
git clone https://github.com/LUBAN-RDS/luban-rds.git

# 或者从 Gitee 克隆（国内推荐）
git clone https://gitee.com/luban-rds/luban-rds.git

# 进入项目目录
cd luban-rds

# 查看分支
git branch -a
```

### 2.3 构建项目

```bash
# 构建项目（跳过测试）
mvn clean package -DskipTests

# 构建项目（运行测试）
mvn clean package

# 安装到本地 Maven 仓库
mvn clean install -DskipTests
```

### 2.4 运行测试

```bash
# 运行所有测试
mvn test

# 运行特定模块的测试
mvn test -pl luban-rds-core

# 运行特定测试类的测试
mvn test -Dtest=LuaCommandHandlerTest
```

### 2.5 启动开发服务器

```bash
# 启动嵌入式服务器
java -cp luban-rds-server/target/luban-rds-server-1.0.0.jar com.janeluoluo.luban.rds.server.EmbeddedRedisServer

# 启动独立服务器
java -cp luban-rds-bin/target/luban-rds-bin-1.0.0.jar com.janeluoluo.luban.rds.bin.RedisServerMain
```

## 3. 开发流程

### 3.1 分支管理

- **main**：主分支，包含稳定的代码
- **develop**：开发分支，集成新功能
- **feature/**：功能分支，开发新功能
- **fix/**：修复分支，修复 bug
- **release/**：发布分支，准备发布版本
- **hotfix/**：热修复分支，紧急修复生产问题

### 3.2 开发步骤

1. **Fork 仓库**：在 GitHub/Gitee 上 Fork 代码仓库
2. **克隆代码**：克隆到本地开发环境
3. **创建分支**：从 develop 分支创建功能分支或修复分支
4. **开发代码**：实现功能或修复 bug
5. **运行测试**：确保测试通过
6. **提交代码**：提交代码并推送到远程仓库
7. **创建 PR**：在 GitHub/Gitee 上创建 Pull Request
8. **代码审查**：等待维护者审查代码
9. **合并代码**：代码通过审查后合并到主分支

### 3.3 提交规范

#### 提交消息格式

```
<type>: <description>

<body>

<footer>
```

#### 类型说明

- **feat**：新功能（feature）
- **fix**：修复 bug
- **docs**：文档更新
- **style**：代码风格调整（不影响功能）
- **refactor**：代码重构（不添加新功能或修复 bug）
- **test**：测试相关
- **chore**：构建、依赖等其他变更

#### 示例

```
feat: Add Lua script sandbox mode

Add sandbox mode for Lua scripts with configurable module access control.

Closes #123
```

## 4. 代码规范

### 4.1 编码规范

- **Java 版本**：使用 Java 8+ 语法
- **代码风格**：遵循 Google Java 风格指南
- **命名规范**：
  - 类名：大驼峰命名法（PascalCase）
  - 方法名：小驼峰命名法（camelCase）
  - 变量名：小驼峰命名法（camelCase）
  - 常量名：全大写，下划线分隔（SNAKE_CASE）
  - 包名：小写，点分隔
- **缩进**：4 个空格（不使用制表符）
- **行宽**：每行不超过 100 个字符
- **括号**：使用大括号包裹所有代码块
- **导入**：按包名排序，使用静态导入时需谨慎

### 4.2 文档规范

- **Javadoc**：为公共类和方法添加 Javadoc 注释
- **方法注释**：描述方法功能、参数、返回值和异常
- **类注释**：描述类的用途、功能和设计思路
- **实现注释**：为复杂的实现逻辑添加行注释
- **中文注释**：对于团队内部理解的内容，可使用中文注释

### 4.3 测试规范

- **测试覆盖**：为新功能和修复添加单元测试
- **测试命名**：测试类名使用 `*Test` 后缀，测试方法使用 `test*` 前缀
- **测试隔离**：确保测试用例之间相互隔离
- **测试断言**：使用明确的断言消息
- **测试数据**：使用有意义的测试数据

## 5. 代码提交和 Pull Request

### 5.1 代码提交

```bash
# 查看修改状态
git status

# 添加修改的文件
git add .

# 提交代码
git commit -m "feat: Add new feature"

# 推送到远程仓库
git push origin feature/new-feature
```

### 5.2 创建 Pull Request

1. **登录 GitHub/Gitee**：打开 GitHub/Gitee 网站，登录账号
2. **进入仓库**：进入您 Fork 的代码仓库
3. **切换分支**：选择您的功能分支或修复分支
4. **创建 PR**：点击 "Pull request" 按钮
5. **填写信息**：
   - 标题：清晰描述变更内容
   - 描述：详细说明变更的原因、实现细节和影响
   - 关联 Issues：使用 `Closes #123` 关联相关的 Issues
   - 检查项：确保代码符合规范，测试通过
6. **提交 PR**：点击 "Create pull request" 按钮

### 5.3 代码审查

- **审查流程**：维护者会审查您的代码，提出修改建议
- **回应反馈**：根据审查意见修改代码，再次提交
- **测试验证**：确保修改后的代码通过所有测试
- **合并条件**：代码通过审查，测试通过，符合项目规范

### 5.4 合并代码

- **自动合并**：如果配置了自动合并，当所有检查通过时会自动合并
- **手动合并**：维护者手动合并代码到目标分支
- **删除分支**：代码合并后，您可以删除本地和远程的功能分支

## 6. 文档贡献

### 6.1 文档结构

- **指南**：使用指南、快速开始、高级功能
- **API**：API 参考、命令列表、协议说明
- **Lua**：Lua 脚本使用指南、API 参考
- **架构**：系统架构、功能架构、设计决策
- **部署**：安装部署、配置指南、监控维护、故障排查
- **开发**：贡献指南、代码风格、构建和测试
- **资源**：常见问题、更新日志、社区资源
- **法律**：许可证、隐私政策

### 6.2 文档格式

- **Markdown**：使用 Markdown 格式编写文档
- **标题层级**：使用 #、##、### 等标记标题层级
- **代码块**：使用 ``` 包裹代码块，指定语言
- **链接**：使用 `[文本](链接)` 格式添加链接
- **图片**：使用 `![alt](path/to/image.png)` 格式添加图片
- **表格**：使用 Markdown 表格语法

### 6.3 文档构建

```bash
# 进入文档目录
cd docs

# 安装依赖
npm install

# 本地预览
npm run docs:dev

# 构建文档
npm run docs:build

# 预览构建结果
npm run docs:preview
```

## 7. 问题反馈

### 7.1 报告 Bug

1. **搜索 Issues**：先搜索现有的 Issues，确认是否已有人报告
2. **创建 Issue**：点击 "New issue" 按钮
3. **选择模板**：选择 "Bug report" 模板
4. **填写信息**：
   - 标题：简洁描述 bug
   - 环境：操作系统、Java 版本、Luban-RDS 版本
   - 复现步骤：详细的复现步骤
   - 预期行为：描述期望的行为
   - 实际行为：描述实际发生的行为
   - 日志：提供相关的日志信息
   - 代码：提供最小复现示例
5. **提交 Issue**：点击 "Submit new issue" 按钮

### 7.2 提出功能建议

1. **搜索 Issues**：先搜索现有的 Issues，确认是否已有人提出
2. **创建 Issue**：点击 "New issue" 按钮
3. **选择模板**：选择 "Feature request" 模板
4. **填写信息**：
   - 标题：简洁描述功能
   - 描述：详细说明功能需求、使用场景和预期效果
   - 实现思路：提供可能的实现思路
   - 优先级：评估功能的优先级
5. **提交 Issue**：点击 "Submit new issue" 按钮

## 8. 社区参与

### 8.1 讨论区

- **GitHub Discussions**：[https://github.com/LUBAN-RDS/luban-rds/discussions](https://github.com/LUBAN-RDS/luban-rds/discussions)
- **Gitter 聊天室**：[https://gitter.im/luban-rds/community](https://gitter.im/luban-rds/community)

### 8.2 回答问题

- **Stack Overflow**：回答标签为 `luban-rds` 的问题
- **GitHub Issues**：回答和讨论 Issues 中的问题
- **Gitee Issues**：回答和讨论 Issues 中的问题
- **社区群组**：参与社区群组的讨论，回答问题

### 8.3 分享经验

- **技术博客**：撰写关于 Luban-RDS 的技术博客
- **演讲分享**：在技术会议上分享 Luban-RDS 的使用经验
- **教程示例**：创建教程和示例代码，帮助其他开发者

## 9. 贡献者行为准则

### 9.1 尊重他人

- 尊重社区成员，避免冒犯性言论
- 保持专业的沟通态度
- 接受不同的观点和意见
- 帮助新成员融入社区

### 9.2 诚信透明

- 诚实地报告问题和缺陷
- 透明地交流开发进展
- 尊重知识产权，遵守许可证
- 承认他人的贡献

### 9.3 质量优先

- 追求代码质量和可维护性
- 编写清晰、简洁的代码
- 为代码添加适当的注释和文档
- 确保测试覆盖和代码质量

### 9.4 团队合作

- 积极参与团队讨论和决策
- 支持和帮助其他贡献者
- 尊重项目的代码审查流程
- 共同维护项目的健康发展

## 10. 版权和许可证

### 10.1 贡献授权

通过提交代码或其他贡献，您同意：

- 您有权提交这些贡献，并且这些贡献是您的原创作品
- 您授权 Luban-RDS Contributors 在 Apache License 2.0 许可证下使用和分发您的贡献
- 您的贡献将被包含在项目的版权声明中

### 10.2 许可证信息

Luban-RDS 项目采用 Apache License 2.0 许可证开源，详细信息请参考 [许可证](../legal/license.md) 文档。

## 11. 常见问题

### 11.1 如何解决构建失败的问题？

- **检查 Java 版本**：确保使用 Java 8+ 版本
- **检查 Maven 配置**：确保 Maven 配置正确，网络连接正常
- **清理缓存**：运行 `mvn clean` 清理构建缓存
- **查看日志**：查看详细的构建日志，定位错误原因

### 11.2 如何解决测试失败的问题？

- **查看测试日志**：查看详细的测试日志，定位失败原因
- **检查环境**：确保测试环境配置正确
- **隔离测试**：单独运行失败的测试用例
- **修复代码**：根据测试失败的原因修复代码

### 11.3 如何处理代码冲突？

- **拉取最新代码**：运行 `git pull origin develop` 拉取最新代码
- **解决冲突**：手动编辑冲突文件，解决冲突
- **标记冲突**：使用 `git add` 标记冲突已解决
- **提交代码**：提交解决冲突后的代码

### 11.4 如何获得更多帮助？

- **查看文档**：参考项目文档和相关指南
- **搜索 Issues**：搜索和阅读相关的 Issues
- **社区支持**：在社区讨论区提问
- **联系维护者**：通过 GitHub/Gitee 消息联系维护者

## 12. 致谢

感谢所有为 Luban-RDS 项目做出贡献的开发者！您的贡献是项目成功的关键。

- **核心贡献者**：项目的核心开发者
- **文档贡献者**：改进和完善文档的贡献者
- **测试贡献者**：编写和运行测试用例的贡献者
- **问题报告者**：报告 bug 和提出建议的用户
- **社区支持者**：回答问题、参与讨论的社区成员

## 13. 联系方式

如果您有任何问题或建议，请通过以下方式联系我们：

- **GitHub Issues**：[https://github.com/LUBAN-RDS/luban-rds/issues](https://github.com/LUBAN-RDS/luban-rds/issues)
- **Gitee Issues**：[https://gitee.com/luban-rds/luban-rds/issues](https://gitee.com/luban-rds/luban-rds/issues)
- **GitHub Discussions**：[https://github.com/LUBAN-RDS/luban-rds/discussions](https://github.com/LUBAN-RDS/luban-rds/discussions)

---

**欢迎加入 Luban-RDS 社区，一起构建更好的开源项目！**
