# 环境搭建

本文档介绍如何搭建 Luban-RDS 项目的开发环境。

## 系统要求

- **操作系统**：Windows、macOS 或 Linux
- **Java**：JDK 17 或更高版本
- **Maven**：Maven 3.8 或更高版本
- **Git**：Git 2.0 或更高版本
- **IDE**：推荐 IntelliJ IDEA 或 Eclipse

## 安装步骤

### 1. 安装 Java

1. 下载并安装 JDK 17 或更高版本：
   - [Oracle JDK](https://www.oracle.com/java/technologies/downloads/)
   - 或 [Temurin JDK](https://adoptium.net/)

2. 配置环境变量：
   - **Windows**：设置 `JAVA_HOME` 环境变量指向 JDK 安装目录
   - **macOS/Linux**：在 `.bashrc` 或 `.zshrc` 中添加 `export JAVA_HOME=/path/to/jdk`

3. 验证安装：
   ```bash
   java -version
   ```

### 2. 安装 Maven

1. 下载并安装 Maven 3.6 或更高版本：
   - [Maven 下载](https://maven.apache.org/download.cgi)

2. 配置环境变量：
   - **Windows**：将 Maven 的 `bin` 目录添加到 `PATH` 环境变量
   - **macOS/Linux**：在 `.bashrc` 或 `.zshrc` 中添加 `export PATH=$PATH:/path/to/maven/bin`

3. 验证安装：
   ```bash
   mvn -version
   ```

### 3. 安装 Git

1. 下载并安装 Git：
   - [Git 下载](https://git-scm.com/downloads)

2. 验证安装：
   ```bash
   git --version
   ```

### 4. 克隆代码库

```bash
git clone https://github.com/your-repo/luban-rds.git
cd luban-rds
```

### 5. 配置 IDE

#### IntelliJ IDEA

1. 打开 IntelliJ IDEA
2. 选择 `File > Open`，导航到克隆的代码库目录
3. 等待 IDE 自动导入 Maven 项目
4. 配置 JDK：
   - 选择 `File > Project Structure > Project`
   - 确保 `Project SDK` 选择了正确的 JDK 版本

#### Eclipse

1. 打开 Eclipse
2. 选择 `File > Import > Maven > Existing Maven Projects`
3. 导航到克隆的代码库目录，点击 `Finish`
4. 配置 JDK：
   - 选择 `Window > Preferences > Java > Installed JREs`
   - 确保添加了正确的 JDK 版本

## 构建项目

在项目根目录执行以下命令：

```bash
mvn clean install
```

这将编译项目并运行所有测试。

## 运行测试

执行以下命令运行测试：

```bash
mvn test
```

## 常见问题

### Maven 依赖下载缓慢

可以配置 Maven 镜像加速依赖下载，编辑 `~/.m2/settings.xml` 文件：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyun</id>
      <name>Aliyun Maven Mirror</name>
      <url>https://maven.aliyun.com/repository/central</url>
      <mirrorOf>central</mirrorOf>
    </mirror>
  </mirrors>
</settings>
```

### Java 版本不匹配

确保项目使用的 Java 版本与本地安装的 JDK 版本一致。可以在 `pom.xml` 文件中查看和修改 Java 版本：

```xml
<properties>
  <maven.compiler.source>17</maven.compiler.source>
  <maven.compiler.target>17</maven.compiler.target>
</properties>
```
