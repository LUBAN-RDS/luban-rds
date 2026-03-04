---
title: 构建和测试
---

# 构建和测试

本指南详细说明了 Luban-RDS 项目的构建和测试流程，包括环境准备、构建步骤、测试方法和常见问题解决。

## 1. 环境准备

### 1.1 系统要求

- **操作系统**：Windows 7+, Linux, macOS
- **Java 版本**：Java 17+（推荐 Java 17）
- **Maven**：Maven 3.8+（构建工具）
- **Git**：Git 2.20+（版本控制）
- **IDE**：推荐 IntelliJ IDEA 或 Eclipse（可选）

### 1.2 安装 Java

#### Windows

1. 下载 JDK 安装包：[https://www.oracle.com/java/technologies/downloads/](https://www.oracle.com/java/technologies/downloads/)
2. 运行安装程序，按照提示完成安装
3. 配置环境变量：
   - 新建 `JAVA_HOME` 环境变量，值为 JDK 安装目录
   - 将 `%JAVA_HOME%\bin` 添加到 `PATH` 环境变量
4. 验证安装：
   ```bash
   java -version
   javac -version
   ```

#### Linux

1. 使用包管理器安装 JDK：
   ```bash
   # Ubuntu/Debian
   sudo apt update
   sudo apt install openjdk-17-jdk
   
   # CentOS/RHEL
   sudo yum install java-11-openjdk-devel
   ```
2. 验证安装：
   ```bash
   java -version
   javac -version
   ```

#### macOS

1. 使用 Homebrew 安装 JDK：
   ```bash
   brew install openjdk@17
   ```
2. 验证安装：
   ```bash
   java -version
   javac -version
   ```

### 1.3 安装 Maven

#### Windows

1. 下载 Maven：[https://maven.apache.org/download.cgi](https://maven.apache.org/download.cgi)
2. 解压到本地目录，例如 `C:\apache-maven-3.8.8`
3. 配置环境变量：
   - 新建 `MAVEN_HOME` 环境变量，值为 Maven 安装目录
   - 将 `%MAVEN_HOME%\bin` 添加到 `PATH` 环境变量
4. 验证安装：
   ```bash
   mvn -version
   ```

#### Linux

1. 使用包管理器安装 Maven：
   ```bash
   # Ubuntu/Debian
   sudo apt update
   sudo apt install maven
   
   # CentOS/RHEL
   sudo yum install maven
   ```
2. 验证安装：
   ```bash
   mvn -version
   ```

#### macOS

1. 使用 Homebrew 安装 Maven：
   ```bash
   brew install maven
   ```
2. 验证安装：
   ```bash
   mvn -version
   ```

### 1.4 克隆代码

```bash
# 克隆代码仓库
git clone https://github.com/your-org/luban-rds.git

# 进入项目目录
cd luban-rds

# 查看分支
git branch -a
```

## 2. 构建流程

### 2.1 构建选项

| 命令 | 描述 |
|------|------|
| `mvn clean package` | 清理并构建项目，运行测试 |
| `mvn clean package -DskipTests` | 清理并构建项目，跳过测试 |
| `mvn clean install` | 清理、构建并安装到本地 Maven 仓库 |
| `mvn clean install -DskipTests` | 清理、构建并安装到本地 Maven 仓库，跳过测试 |
| `mvn clean verify` | 清理并构建项目，运行集成测试 |
| `mvn clean deploy` | 清理、构建并部署到远程 Maven 仓库 |

### 2.2 构建步骤

1. **清理项目**：
   ```bash
   mvn clean
   ```

2. **构建项目（运行测试）**：
   ```bash
   mvn package
   ```

3. **构建项目（跳过测试）**：
   ```bash
   mvn package -DskipTests
   ```

4. **安装到本地 Maven 仓库**：
   ```bash
   mvn install -DskipTests
   ```

5. **构建特定模块**：
   ```bash
   # 构建核心模块
   mvn package -pl luban-rds-core
   
   # 构建服务器模块
   mvn package -pl luban-rds-server
   ```

6. **构建带依赖的可执行 JAR**：
   ```bash
   mvn package assembly:single -pl luban-rds-bin
   ```

### 2.3 构建输出

构建完成后，各模块的输出文件位于：

- **luban-rds-core**：`luban-rds-core/target/luban-rds-core-1.0.0.jar`
- **luban-rds-protocol**：`luban-rds-protocol/target/luban-rds-protocol-1.0.0.jar`
- **luban-rds-server**：`luban-rds-server/target/luban-rds-server-1.0.0.jar`
- **luban-rds-persistence**：`luban-rds-persistence/target/luban-rds-persistence-1.0.0.jar`
- **luban-rds-client**：`luban-rds-client/target/luban-rds-client-1.0.0.jar`
- **luban-rds-common**：`luban-rds-common/target/luban-rds-common-1.0.0.jar`
- **luban-rds-bin**：`luban-rds-bin/target/luban-rds-bin-1.0.0.jar`
- **luban-rds-spring-boot-starter**：`luban-rds-spring-boot-starter/target/luban-rds-spring-boot-starter-1.0.0.jar`

## 3. 测试流程

### 3.1 测试类型

- **单元测试**：测试单个类或方法的功能
- **集成测试**：测试多个组件之间的交互
- **端到端测试**：测试整个系统的功能

### 3.2 运行测试

1. **运行所有测试**：
   ```bash
   mvn test
   ```

2. **运行特定模块的测试**：
   ```bash
   # 运行核心模块的测试
   mvn test -pl luban-rds-core
   
   # 运行服务器模块的测试
   mvn test -pl luban-rds-server
   ```

3. **运行特定测试类的测试**：
   ```bash
   mvn test -Dtest=LuaCommandHandlerTest
   ```

4. **运行特定测试方法的测试**：
   ```bash
   mvn test -Dtest=LuaCommandHandlerTest#testEvalScript
   ```

5. **运行集成测试**：
   ```bash
   mvn verify
   ```

### 3.3 测试报告

测试完成后，测试报告位于各模块的 `target/surefire-reports` 目录下：

- **HTML 报告**：`target/surefire-reports/index.html`
- **XML 报告**：`target/surefire-reports/*.xml`
- **文本报告**：`target/surefire-reports/*.txt`

## 4. 常见问题解决

### 4.1 构建失败

#### 问题：缺少依赖

**症状**：构建过程中出现 `Could not find artifact` 错误。

**解决方案**：
- 检查 Maven 仓库配置是否正确
- 运行 `mvn clean install -U` 强制更新依赖
- 检查网络连接是否正常

#### 问题：编译错误

**症状**：构建过程中出现编译错误，如 `error: cannot find symbol`。

**解决方案**：
- 检查代码是否存在语法错误
- 检查依赖版本是否兼容
- 检查 Java 版本是否符合要求

#### 问题：测试失败

**症状**：构建过程中测试失败，如 `Tests failed: 1`。

**解决方案**：
- 查看测试日志，定位失败原因
- 修复测试代码或被测试代码
- 运行 `mvn test -Dtest=失败的测试类` 单独测试失败的测试

### 4.2 测试失败

#### 问题：超时

**症状**：测试过程中出现 `TimeoutException`。

**解决方案**：
- 检查测试代码是否存在无限循环
- 检查测试环境是否资源不足
- 增加测试超时时间：`@Test(timeout = 10000)`

#### 问题：断言失败

**症状**：测试过程中出现 `AssertionError`。

**解决方案**：
- 检查断言条件是否正确
- 检查被测试代码的行为是否符合预期
- 调整测试数据或测试逻辑

#### 问题：空指针异常

**症状**：测试过程中出现 `NullPointerException`。

**解决方案**：
- 检查测试代码是否正确初始化对象
- 检查被测试代码是否正确处理 null 值
- 添加适当的 null 检查

### 4.3 性能问题

#### 问题：构建速度慢

**症状**：Maven 构建过程非常缓慢。

**解决方案**：
- 配置 Maven 镜像加速：在 `settings.xml` 中配置国内镜像
- 清理本地 Maven 仓库：删除 `~/.m2/repository` 目录
- 增加 Maven 内存：设置 `MAVEN_OPTS=-Xmx2G -XX:MaxPermSize=512m`

#### 问题：测试执行慢

**症状**：测试执行过程非常缓慢。

**解决方案**：
- 优化测试代码，减少测试执行时间
- 并行运行测试：`mvn test -T 4`（使用 4 个线程）
- 跳过不需要的测试：`mvn test -DskipTests=false -Dtest=需要的测试类`

## 5. 持续集成

### 5.1 CI/CD 配置

Luban-RDS 项目使用 GitHub Actions 进行持续集成，配置文件位于 `.github/workflows/ci.yml`。

**主要流程**：
1. 当代码推送到 GitHub 时，触发 CI 流程
2. 安装 Java 和 Maven
3. 构建项目并运行测试
4. 生成测试报告和代码覆盖率报告
5. 部署构建产物到 Maven 仓库（仅对主分支）

### 5.2 本地 CI 测试

可以使用 Docker 本地模拟 CI 环境：

```bash
# 构建 Docker 镜像
docker build -t luban-rds-ci .

# 运行 CI 流程
docker run -v $(pwd):/app luban-rds-ci mvn clean package
```

## 6. 开发工具集成

### 6.1 IntelliJ IDEA

1. **导入项目**：
   - 打开 IntelliJ IDEA
   - 选择 `File > Open`，选择项目根目录
   - 等待 Maven 依赖下载完成

2. **构建项目**：
   - 点击 `Build > Build Project`
   - 或使用快捷键 `Ctrl+F9`

3. **运行测试**：
   - 右键点击测试类，选择 `Run 'TestClassName'`
   - 或使用快捷键 `Shift+F10`

4. **调试测试**：
   - 右键点击测试类，选择 `Debug 'TestClassName'`
   - 或使用快捷键 `Shift+F9`

### 6.2 Eclipse

1. **导入项目**：
   - 打开 Eclipse
   - 选择 `File > Import > Maven > Existing Maven Projects`
   - 选择项目根目录，点击 `Finish`

2. **构建项目**：
   - 右键点击项目，选择 `Run As > Maven build...`
   - 输入 `clean package`，点击 `Run`

3. **运行测试**：
   - 右键点击测试类，选择 `Run As > JUnit Test`

4. **调试测试**：
   - 右键点击测试类，选择 `Debug As > JUnit Test`

## 7. 依赖管理

### 7.1 依赖版本管理

Luban-RDS 项目使用 Maven 的 `dependencyManagement` 管理依赖版本，集中在 `pom.xml` 文件中：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-all</artifactId>
            <version>4.2.10.Final</version>
        </dependency>
        <dependency>
            <groupId>org.luaj</groupId>
            <artifactId>luaj-jse</artifactId>
            <version>3.0.1</version>
        </dependency>
        <!-- 其他依赖 -->
    </dependencies>
</dependencyManagement>
```

### 7.2 依赖分析

使用 Maven 依赖分析工具检查依赖：

```bash
# 分析依赖树
mvn dependency:tree

# 分析依赖冲突
mvn dependency:analyze

# 查找未使用的依赖
mvn dependency:analyze-unused-dependencies
```

### 7.3 依赖更新

使用 Maven 依赖更新插件检查依赖更新：

```bash
# 检查依赖更新
mvn versions:display-dependency-updates

# 更新依赖版本
mvn versions:update-properties
```

## 8. 构建优化

### 8.1 并行构建

使用 Maven 的并行构建功能加速构建过程：

```bash
# 使用 4 个线程并行构建
mvn clean package -T 4

# 使用 CPU 核心数的线程并行构建
mvn clean package -T 1C
```

### 8.2 增量构建

Maven 默认支持增量构建，只重新构建修改过的模块：

```bash
# 增量构建
mvn package
```

### 8.3 缓存优化

配置 Maven 本地仓库缓存，加速依赖下载：

**Windows**：`%USERPROFILE%\.m2\repository`
**Linux/macOS**：`~/.m2/repository`

### 8.4 构建配置

在 `pom.xml` 中配置构建参数：

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.8.1</version>
            <configuration>
                <source>17</source>
                <target>17</target>
                <encoding>UTF-8</encoding>
                <compilerArgs>
                    <arg>-Xlint:all</arg>
                    <arg>-Werror</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
```

## 9. 测试优化

### 9.1 测试覆盖率

使用 JaCoCo 插件生成测试覆盖率报告：

```bash
# 运行测试并生成覆盖率报告
mvn test jacoco:report
```

覆盖率报告位于 `target/site/jacoco` 目录下。

### 9.2 测试并行化

使用 Maven Surefire 插件配置测试并行运行：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.0.0-M5</version>
    <configuration>
        <parallel>methods</parallel>
        <threadCount>4</threadCount>
    </configuration>
</plugin>
```

### 9.3 测试数据管理

使用测试数据工厂或测试数据生成器管理测试数据：

```java
public class TestDataFactory {
    public static Command createSetCommand() {
        return new Command("SET", Arrays.asList("key", "value"));
    }
    
    public static Command createGetCommand() {
        return new Command("GET", Collections.singletonList("key"));
    }
}
```

### 9.4 测试工具

使用以下工具辅助测试：

- **Mockito**：模拟对象
- **JUnit 5**：测试框架
- **Hamcrest**：断言库
- **AssertJ**：流畅的断言库
- **TestContainers**：容器化测试

## 10. 部署构建产物

### 10.1 本地部署

将构建产物部署到本地 Maven 仓库：

```bash
mvn clean install -DskipTests
```

### 10.2 远程部署

将构建产物部署到远程 Maven 仓库：

```bash
mvn clean deploy -DskipTests
```

需要在 `settings.xml` 中配置远程仓库认证信息：

```xml
<servers>
    <server>
        <id>maven-repository</id>
        <username>your-username</username>
        <password>your-password</password>
    </server>
</servers>
```

### 10.3 构建 Docker 镜像

使用 Docker 构建镜像：

```bash
# 构建 Docker 镜像
docker build -t luban-rds:1.0.0 .

# 运行 Docker 容器
docker run -p 9736:9736 luban-rds:1.0.0
```

## 11. 常见构建命令

| 命令 | 描述 |
|------|------|
| `mvn clean` | 清理构建产物 |
| `mvn compile` | 编译源代码 |
| `mvn test` | 运行测试 |
| `mvn package` | 构建项目 |
| `mvn install` | 安装到本地仓库 |
| `mvn deploy` | 部署到远程仓库 |
| `mvn dependency:tree` | 查看依赖树 |
| `mvn dependency:analyze` | 分析依赖 |
| `mvn site` | 生成项目站点 |
| `mvn help:describe -Dplugin=compiler` | 查看插件帮助 |

## 12. 总结

本指南提供了 Luban-RDS 项目的构建和测试流程，包括：

- **环境准备**：Java、Maven 的安装和配置
- **构建流程**：各种构建命令和选项
- **测试流程**：单元测试、集成测试的运行方法
- **问题解决**：常见构建和测试问题的解决方案
- **持续集成**：CI/CD 配置和本地模拟
- **开发工具集成**：IntelliJ IDEA 和 Eclipse 的使用
- **依赖管理**：依赖版本管理和分析
- **构建优化**：并行构建、增量构建等优化方法
- **测试优化**：测试覆盖率、并行化等优化方法
- **部署构建产物**：本地和远程部署方法

遵循本指南可以高效地构建和测试 Luban-RDS 项目，确保代码质量和功能正确性。

## 13. 参考资料

- **Maven 官方文档**：[https://maven.apache.org/guides/index.html](https://maven.apache.org/guides/index.html)
- **JUnit 官方文档**：[https://junit.org/junit5/docs/current/user-guide/](https://junit.org/junit5/docs/current/user-guide/)
- **Mockito 官方文档**：[https://site.mockito.org/](https://site.mockito.org/)
- **JaCoCo 官方文档**：[https://www.jacoco.org/jacoco/trunk/doc/](https://www.jacoco.org/jacoco/trunk/doc/)
- **GitHub Actions 文档**：[https://docs.github.com/en/actions](https://docs.github.com/en/actions)
