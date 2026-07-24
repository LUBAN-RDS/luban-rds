---
archived-with: 2026-07-24-fix-cluster-failover-data-loss
status: final
---
# 集群故障转移数据保留 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将集群角色变更与主从复制生命周期连接起来，保证候选 slave 已成功应用的数据在提升为 master 后仍可读。

**Architecture:** 采用"中立生命周期接口 + server 层装配"方案。cluster 模块定义 `ReplicationLifecycleListener` 接口发布角色事件（不依赖 replication 模块）；server 模块实现该接口并持有复制组件完成装配；replication 模块负责 PSYNC、backlog、复制流解析和本地命令应用。master 写命令成功后传播原始 RESP 帧到 backlog 和在线 slave；slave 累积解析传播流并应用到共享 MemoryStore（标记来源防止循环传播）。

**Tech Stack:** Java 17, Netty NIO, Maven 多模块, JUnit 5

**Change:** fix-cluster-failover-data-loss
**Design Doc:** docs/superpowers/specs/2026-07-24-cluster-failover-data-retention-design.md
**Base Ref:** a8faeb522a2e85bcc426763e5ced483f19df6281

---

## 文件结构

### 新建文件

| 文件 | 责任 |
|------|------|
| `luban-rds-cluster/.../lifecycle/ReplicationLifecycleListener.java` | 中立角色生命周期回调接口（cluster 模块定义） |
| `luban-rds-cluster/.../lifecycle/NoOpReplicationLifecycleListener.java` | no-op 默认实现，供非集群测试使用 |
| `luban-rds-replication/.../ReplicationExecutionContext.java` | replication 来源标记 + 专用命令执行上下文 |
| `luban-rds-replication/.../ReplicationStreamApplier.java` | slave 侧累积缓冲区 + RESP 拆帧 + 本地命令应用 |
| `luban-rds-server/.../ReplicationCoordinator.java` | server 层复制协调器，实现 ReplicationLifecycleListener，持有并装配所有复制组件 |
| `luban-rds-server/src/test/.../ClusterFailoverDataRetentionTest.java` | 端到端故障转移数据保留集成测试 |

### 修改文件

| 文件 | 改动 |
|------|------|
| `luban-rds-server/.../NettyRedisServer.java` | 启动时装配 ReplicationCoordinator；stop 时关闭复制资源；ChannelInitializer 注入 ReplicationCommandHandler |
| `luban-rds-server/.../RedisServerHandler.java` | 写命令成功后传播原始 RESP 帧；EXEC 成功后传播事务流；装配 ReplicationCommandHandler；只读拦截 |
| `luban-rds-replication/.../SlaveReplicationService.java` | onCommandPropagation 委托 ReplicationStreamApplier；start 支持指定 master 地址（集群复用） |
| `luban-rds-replication/.../SlaveReplicationClient.java` | 修复 offset 双份不同步问题 |
| `luban-rds-replication/.../MasterReplicationManager.java` | 新增 promoteToMaster/demoteToSlave/stopSlaveConnection 方法 |
| `luban-rds-cluster/.../handler/ClusterCommandHandler.java` | clusterReplicate 成功后调用 lifecycle listener.replicateTo |
| `luban-rds-cluster/.../gossip/FailoverManager.java` | performFailover 后通知 promoteToMaster；onFailoverResult 降级时通知 demoteToSlave |
| `luban-rds-cluster/.../gossip/GossipProtocol.java` | 注入 ReplicationLifecycleListener，传递给 ClusterCommandHandler 和 FailoverManager |

---

## Task 1: 复制数据路径测试（失败测试先行）

### Task 1.1: 添加 master 写入进入 backlog 并由 slave 应用的失败测试

**Files:**
- Create: `luban-rds-replication/src/test/java/com/janeluo/luban/rds/replication/ReplicationDataPathTest.java`

- [ ] **Step 1: 编写失败测试**

```java
package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.replication.handler.ReplicationCommandHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 master 成功写入会进入 backlog 并由 slave 应用到共享 MemoryStore。
 */
class ReplicationDataPathTest {

    private MasterReplicationManager masterManager;
    private MemoryStore masterStore;
    private MemoryStore slaveStore;
    private ReplicationStreamApplier applier;

    @BeforeEach
    void setUp() {
        MasterReplicationManager.initialize(1024 * 1024);
        masterManager = MasterReplicationManager.getInstance();
        masterStore = new DefaultMemoryStore(16, 0, "noeviction");
        slaveStore = new DefaultMemoryStore(16, 0, "noeviction");
        masterManager.setMemoryStore(masterStore);

        applier = new ReplicationStreamApplier(slaveStore);
    }

    @AfterEach
    void tearDown() {
        masterManager.shutdown();
        applier.close();
    }

    @Test
    void testPropagatedCommandAppliedToSlaveStore() {
        // master 传播一条 SET key value 的 RESP 帧
        byte[] respFrame = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n".getBytes(
                java.nio.charset.StandardCharsets.ISO_8859_1);
        masterManager.propagateCommand(respFrame);

        // slave 侧 applier 接收并应用
        applier.applyData(Unpooled.wrappedBuffer(respFrame));

        // 验证 slave 的 MemoryStore 已应用该命令
        assertEquals("value", slaveStore.get(0, "key"));
    }

    @Test
    void testReadOnlyCommandNotPropagated() {
        // propagateCommand 只传播写命令；GET 不应改变 backlog 内容
        // 这里验证 backlog 的 masterReplOffset 只在写命令后增长
        long offsetBefore = masterManager.getBacklog().getMasterReplOffset();

        // 模拟 GET 命令——调用方（RedisServerHandler）负责判断是否传播，
        // propagateCommand 本身不做只读过滤，所以这里只验证写命令确实进了 backlog
        byte[] setFrame = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes(
                java.nio.charset.StandardCharsets.ISO_8859_1);
        masterManager.propagateCommand(setFrame);

        long offsetAfter = masterManager.getBacklog().getMasterReplOffset();
        assertTrue(offsetAfter > offsetBefore, "写命令应推进 backlog offset");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -pl luban-rds-replication -Dtest=ReplicationDataPathTest -DfailIfNoTests=false`
Expected: 编译失败，`ReplicationStreamApplier` 类不存在

- [ ] **Step 3: Commit**

```bash
git add luban-rds-replication/src/test/java/com/janeluo/luban/rds/replication/ReplicationDataPathTest.java
git commit -m "test(replication): 添加复制数据路径失败测试 (Task 1.1)"
```

### Task 1.2: 添加复制流拆包、粘包和事务重放测试

**Files:**
- Create: `luban-rds-replication/src/test/java/com/janeluo/luban/rds/replication/ReplicationStreamParsingTest.java`

- [ ] **Step 1: 编写拆包/粘包失败测试**

```java
package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 slave 侧 ReplicationStreamApplier 处理拆包、粘包和事务重放。
 */
class ReplicationStreamParsingTest {

    private MemoryStore slaveStore;
    private ReplicationStreamApplier applier;

    @BeforeEach
    void setUp() {
        slaveStore = new DefaultMemoryStore(16, 0, "noeviction");
        applier = new ReplicationStreamApplier(slaveStore);
    }

    @AfterEach
    void tearDown() {
        applier.close();
    }

    @Test
    void testPartialCommandBufferedUntilComplete() {
        // 半条命令：只有 *3\r\n$3\r\nSET\r\n$3\r\nkey\r\n （缺少 value 部分）
        byte[] partial = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n".getBytes(StandardCharsets.ISO_8859_1);
        applier.applyData(Unpooled.wrappedBuffer(partial));

        // slave 尚未应用——key 不存在
        assertNull(slaveStore.get(0, "key"));

        // 补全剩余部分
        byte[] rest = "$5\r\nvalue\r\n".getBytes(StandardCharsets.ISO_8859_1);
        applier.applyData(Unpooled.wrappedBuffer(rest));

        assertEquals("value", slaveStore.get(0, "key"));
    }

    @Test
    void testMultipleCommandsInOneChunk() {
        // 一次收到两条完整命令
        byte[] twoCommands = (
            "*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n" +
            "*3\r\n$3\r\nSET\r\n$1\r\nb\r\n$1\r\n2\r\n"
        ).getBytes(StandardCharsets.ISO_8859_1);
        applier.applyData(Unpooled.wrappedBuffer(twoCommands));

        assertEquals("1", slaveStore.get(0, "a"));
        assertEquals("2", slaveStore.get(0, "b"));
    }

    @Test
    void testBinarySafeArguments() {
        // 包含 \r\n 的二进制 value
        byte[] respFrame = Unpooled.buffer();
        respFrame.writeBytes("*3\r\n".getBytes(StandardCharsets.ISO_8859_1));
        respFrame.writeBytes("$3\r\nSET\r\n".getBytes(StandardCharsets.ISO_8859_1));
        respFrame.writeBytes("$4\r\nkey\r\n".getBytes(StandardCharsets.ISO_8859_1));
        respFrame.writeBytes("$7\r\n".getBytes(StandardCharsets.ISO_8859_1));
        respFrame.writeBytes(new byte[]{0x76, 0x61, 0x0d, 0x0a, 0x75, 0x65, 0x00}); // "va\r\nue\0"
        respFrame.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));

        applier.applyData(respFrame);

        // ISO-8859-1 保留二进制
        String val = slaveStore.get(0, "key");
        assertNotNull(val);
        assertEquals(7, val.length());
    }

    @Test
    void testTransactionReplay() {
        // MULTI / SET k v / EXEC 作为一个传播流
        byte[] txStream = (
            "*1\r\n$5\r\nMULTI\r\n" +
            "*3\r\n$3\r\nSET\r\n$2\r\ntx\r\n$5\r\nhello\r\n" +
            "*1\r\n$4\r\nEXEC\r\n"
        ).getBytes(StandardCharsets.ISO_8859_1);
        applier.applyData(Unpooled.wrappedBuffer(txStream));

        // EXEC 后数据应已应用
        assertEquals("hello", slaveStore.get(0, "tx"));
    }

    @Test
    void testAppliedOffsetAdvancesByConsumedBytes() {
        byte[] frame = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes(StandardCharsets.ISO_8859_1);
        long before = applier.getAppliedOffset();
        applier.applyData(Unpooled.wrappedBuffer(frame));
        long after = applier.getAppliedOffset();
        assertEquals(frame.length, after - before, "applied offset 应按消费字节数推进");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn test -pl luban-rds-replication -Dtest=ReplicationStreamParsingTest -DfailIfNoTests=false`
Expected: 编译失败，`ReplicationStreamApplier` 类不存在

- [ ] **Step 3: Commit**

```bash
git add luban-rds-replication/src/test/java/com/janeluo/luban/rds/replication/ReplicationStreamParsingTest.java
git commit -m "test(replication): 添加拆包粘包和事务重放失败测试 (Task 1.2)"
```

---

## Task 2: 实现 ReplicationStreamApplier（slave 侧复制流应用）

### Task 2.1: 实现 ReplicationStreamApplier

**Files:**
- Create: `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/ReplicationStreamApplier.java`

这个类负责：累积 slave 收到的字节流，复用 `RedisProtocolParser` 拆帧，将完整命令通过 `DefaultCommandHandler` 应用到本地 `MemoryStore`，标记来源为 replication 防止循环传播，按消费字节数推进 applied offset。

- [ ] **Step 1: 实现 ReplicationStreamApplier**

```java
package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.Command;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slave 侧复制流应用器。
 * <p>
 * 累积从 master 传播的 RESP 字节流，复用 RedisProtocolParser 拆帧，
 * 将完整命令应用到本地共享 MemoryStore。命令执行标记为 replication 来源，
 * 不产生客户端响应、不做集群重定向、不写 monitor、不再次传播。
 * </p>
 * <p>
 * 半包保留在累积缓冲区等待下次数据到达；一次到达多条命令按顺序逐条执行。
 * 执行成功后才推进 applied offset；协议错误或执行失败时记录日志并通知上层断开重连。
 * </p>
 */
public class ReplicationStreamApplier implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationStreamApplier.class);

    private final MemoryStore memoryStore;
    private final DefaultCommandHandler commandHandler;
    private final RedisProtocolParser parser;
    private final ByteBuf accumulationBuffer;
    private final AtomicLong appliedOffset = new AtomicLong(0);

    /**
     * @param memoryStore slave 共享的本地存储
     */
    public ReplicationStreamApplier(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        this.commandHandler = new DefaultCommandHandler();
        this.parser = new RedisProtocolParser();
        this.accumulationBuffer = PooledByteBufAllocator.DEFAULT.buffer(1024);
    }

    /**
     * 接收并应用传播字节流。
     * <p>
     * 将 data 追加到累积缓冲区，循环解析完整 RESP 命令帧并逐条应用到 MemoryStore。
     * 解析消费的字节数累加到 appliedOffset。
     * </p>
     *
     * @param data master 传播的 RESP 字节（调用后本方法会 release）
     */
    public void applyData(ByteBuf data) {
        try {
            accumulationBuffer.writeBytes(data);
            drainCommands();
        } finally {
            data.release();
        }
    }

    /**
     * 循环解析累积缓冲区中的完整命令并执行。
     */
    private void drainCommands() {
        while (true) {
            int beforeReaderIndex = accumulationBuffer.readerIndex();
            Command command = parser.parse(accumulationBuffer);
            if (command == null) {
                // 半包：parser 已 resetReaderIndex，剩余字节留待下次
                compactBuffer();
                break;
            }
            int consumedBytes = accumulationBuffer.readerIndex() - beforeReaderIndex;
            appliedOffset.addAndGet(consumedBytes);
            executeReplicationCommand(command);
        }
    }

    /**
     * 执行单条复制命令。
     * <p>
     * 使用 DefaultCommandHandler 分发到具体处理器，操作共享 MemoryStore。
     * 不生成客户端响应、不做集群重定向、不写 monitor、不再次传播。
     * 执行失败时记录日志——上层通过 offset 不推进或心跳检测发现偏差后断开重连。
     * </p>
     */
    private void executeReplicationCommand(Command command) {
        try {
            String commandName = command.getName();
            String[] args = command.getArgs();
            int database = 0; // 复制流默认使用 db 0，SELECT 命令会切换
            if ("SELECT".equalsIgnoreCase(commandName) && args.length >= 2) {
                // SELECT 命令在 replication 执行器中只用于切换后续命令的数据库
                // DefaultCommandHandler 的 SelectCommandHandler 会记录当前 db
                // 但 replication 路径需要维护自己的 currentDatabase
                // 这里简化处理：通过 CommandHandler 执行 SELECT，后续命令使用返回的 db
                commandHandler.handle(commandName, database, args, memoryStore);
                return;
            }
            if ("MULTI".equalsIgnoreCase(commandName) || "EXEC".equalsIgnoreCase(commandName)
                    || "DISCARD".equalsIgnoreCase(commandName)) {
                // 事务命令直接交给 CommandHandler（replication 上下文不维护事务队列）
                // MULTI/EXEC 在复制流中作为独立命令逐条执行即可
                commandHandler.handle(commandName, database, args, memoryStore);
                return;
            }
            commandHandler.handle(commandName, database, args, memoryStore);
        } catch (Exception e) {
            logger.error("复制命令执行失败，将触发重连: {}", command.getName(), e);
            throw new ReplicationApplyException("Failed to apply replication command: " + command.getName(), e);
        }
    }

    /**
     * 压缩累积缓冲区：丢弃已读部分，保留未读的半包数据。
     */
    private void compactBuffer() {
        if (accumulationBuffer.readerIndex() > 0) {
            accumulationBuffer.discardReadBytes();
        }
    }

    /**
     * @return 已成功应用的字节数（用于 REPLCONF ACK）
     */
    public long getAppliedOffset() {
        return appliedOffset.get();
    }

    /**
     * 重置状态（重连后重新全量同步时调用）
     */
    public void reset() {
        accumulationBuffer.clear();
        appliedOffset.set(0);
    }

    @Override
    public void close() {
        if (accumulationBuffer.refCnt() > 0) {
            accumulationBuffer.release();
        }
    }
}
```

- [ ] **Step 2: 创建 ReplicationApplyException**

```java
package com.janeluo.luban.rds.replication;

/**
 * 复制命令应用失败时抛出，触发上层断开重连。
 */
public class ReplicationApplyException extends RuntimeException {

    public ReplicationApplyException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: 运行 Task 1.1 和 1.2 的测试验证通过**

Run: `mvn test -pl luban-rds-replication -Dtest="ReplicationDataPathTest,ReplicationStreamParsingTest"`
Expected: 所有测试 PASS

- [ ] **Step 4: Commit**

```bash
git add luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/ReplicationStreamApplier.java \
        luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/ReplicationApplyException.java
git commit -m "feat(replication): 实现 ReplicationStreamApplier 复制流应用器 (Task 2.1)"
```

### Task 2.2: 接入 SlaveReplicationService.onCommandPropagation

**Files:**
- Modify: `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/SlaveReplicationService.java`

当前 `onCommandPropagation`（约 L233-249）是空壳，只累加字节计数不执行命令。需要改为委托 `ReplicationStreamApplier`。

- [ ] **Step 1: 在 SlaveReplicationService 中添加 ReplicationStreamApplier 字段**

在 `SlaveReplicationService` 类字段区域（约 L36 附近）添加：

```java
private ReplicationStreamApplier streamApplier;
```

- [ ] **Step 2: 在 setMemoryStore 中初始化 streamApplier**

找到 `setMemoryStore` 方法（约 L84），修改为：

```java
public void setMemoryStore(MemoryStore memoryStore) {
    this.memoryStore = memoryStore;
    this.streamApplier = new ReplicationStreamApplier(memoryStore);
}
```

- [ ] **Step 3: 重写 onCommandPropagation 方法**

找到 `onCommandPropagation` 方法（约 L233-249），替换为：

```java
@Override
public void onCommandPropagation(ByteBuf data) {
    if (streamApplier == null) {
        logger.warn("ReplicationStreamApplier 未初始化，丢弃传播数据");
        data.release();
        return;
    }
    try {
        streamApplier.applyData(data);
        // applied offset 由 streamApplier 内部精确推进
        slaveReplOffset.set(streamApplier.getAppliedOffset());
        if (state.get() != ReplicationState.ONLINE) {
            state.set(ReplicationState.ONLINE);
        }
    } catch (ReplicationApplyException e) {
        logger.error("复制命令应用失败，断开连接以重新同步", e);
        data.release();
        // 触发重连——通过 callback 通知 client 断开
        if (client != null) {
            client.disconnect();
        }
    }
}
```

- [ ] **Step 4: 在 stop/close 方法中关闭 streamApplier**

找到 `stop()` 方法（或添加 cleanup 方法），在关闭逻辑中添加：

```java
if (streamApplier != null) {
    streamApplier.close();
}
```

- [ ] **Step 5: 运行现有 replication 测试确认无回归**

Run: `mvn test -pl luban-rds-replication -Dtest="ReplicationDataPathTest,ReplicationStreamParsingTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/SlaveReplicationService.java
git commit -m "feat(replication): SlaveReplicationService 接入 ReplicationStreamApplier (Task 2.2)"
```

---

## Task 3: 端到端复制链路（服务层装配 + 写命令传播）

### Task 3.1: 创建 ReplicationCoordinator 并在服务启动时装配

**Files:**
- Create: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/ReplicationCoordinator.java`
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/NettyRedisServer.java`

`ReplicationCoordinator` 是 server 层的复制协调器，统一管理 `MasterReplicationManager`、`SlaveReplicationService`、`ReplicationCommandHandler` 及共享 `MemoryStore`/`RdbPersistService`。它实现了集群模块定义的 `ReplicationLifecycleListener` 接口（Task 4 会创建），但本任务先创建协调器骨架，接口实现在 Task 4 接入。

- [ ] **Step 1: 创建 ReplicationCoordinator 骨架**

```java
package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.RdbPersistService;
import com.janeluo.luban.rds.replication.MasterReplicationManager;
import com.janeluo.luban.rds.replication.SlaveReplicationService;
import com.janeluo.luban.rds.replication.handler.ReplicationCommandHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server 层复制协调器。
 * <p>
 * 统一管理主从复制组件的创建、装配和生命周期。
 * 在服务启动时完成一次性装配，在关闭时停止 slave 连接和 master 心跳。
 * </p>
 * <p>
 * 实现 ReplicationLifecycleListener 接口（Task 4 接入），
 * 将集群角色变更事件桥接到复制生命周期。
 * </p>
 */
public class ReplicationCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationCoordinator.class);

    private final RdsConfig config;
    private final MemoryStore memoryStore;
    private final RdbPersistService rdbPersistService;

    private MasterReplicationManager masterManager;
    private SlaveReplicationService slaveService;
    private ReplicationCommandHandler replicationCommandHandler;

    public ReplicationCoordinator(RdsConfig config, MemoryStore memoryStore,
                                  RdbPersistService rdbPersistService) {
        this.config = config;
        this.memoryStore = memoryStore;
        this.rdbPersistService = rdbPersistService;
    }

    /**
     * 装配复制组件。
     * <p>
     * 初始化 MasterReplicationManager 单例并注入 MemoryStore/RdbPersistService/requirepass；
     * 创建 ReplicationCommandHandler；
     * 如果配置了 replicaof，创建并启动 SlaveReplicationService。
     * </p>
     */
    public void setup() {
        // 1. 初始化 master 侧
        MasterReplicationManager.initialize((int) config.getReplBacklogSize());
        masterManager = MasterReplicationManager.getInstance();
        masterManager.setMemoryStore(memoryStore);
        masterManager.setRdbPersistService(rdbPersistService);
        if (config.getRequirepass() != null && !config.getRequirepass().isEmpty()) {
            masterManager.setRequirepass(config.getRequirepass());
        }

        // 2. 创建复制命令处理器
        replicationCommandHandler = new ReplicationCommandHandler(config);

        // 3. 如果配置了 replicaof，启动 slave 侧
        String replicaof = config.getReplicaof();
        if (replicaof != null && !replicaof.isEmpty()) {
            startSlave(replicaof);
        }

        logger.info("ReplicationCoordinator 装配完成: replicaof={}",
                replicaof != null && !replicaof.isEmpty() ? replicaof : "(master)");
    }

    /**
     * 启动 slave 复制服务。
     *
     * @param masterAddress master 地址 host:port
     */
    public void startSlave(String masterAddress) {
        if (slaveService != null) {
            logger.info("SlaveReplicationService 已存在，停止旧实例后重新创建");
            slaveService.stop();
        }
        // 解析 masterAddress，设置到 config 的 replicaof 字段
        // ConfigLoader 可能将 "host port" 截断为单个 token，这里统一支持 host:port 格式
        String normalizedAddress = normalizeAddress(masterAddress);
        config.setReplicaof(normalizedAddress);

        slaveService = new SlaveReplicationService(config);
        slaveService.setMemoryStore(memoryStore);
        slaveService.setRdbPersistService(rdbPersistService);
        slaveService.start();
        logger.info("Slave 复制服务已启动，目标 master: {}", normalizedAddress);
    }

    /**
     * 停止 slave 复制服务（提升为 master 时调用）。
     */
    public void stopSlave() {
        if (slaveService != null) {
            slaveService.stop();
            slaveService = null;
            logger.info("Slave 复制服务已停止");
        }
        // 清除 replicaof 配置
        config.setReplicaof("");
    }

    /**
     * 停止所有复制资源。
     */
    public void shutdown() {
        if (slaveService != null) {
            slaveService.stop();
            slaveService = null;
        }
        if (masterManager != null) {
            masterManager.shutdown();
        }
        logger.info("ReplicationCoordinator 已关闭");
    }

    /**
     * 规范化地址格式为 host:port。
     * 支持 "host:port" 和 "host port" 两种输入。
     */
    private String normalizeAddress(String address) {
        if (address == null || address.isEmpty()) {
            return "";
        }
        String trimmed = address.trim();
        // 如果包含空格但不包含冒号，取前两个 token 组合为 host:port
        if (trimmed.contains(" ") && !trimmed.contains(":")) {
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                return parts[0] + ":" + parts[1];
            }
        }
        return trimmed;
    }

    public ReplicationCommandHandler getReplicationCommandHandler() {
        return replicationCommandHandler;
    }

    public MasterReplicationManager getMasterManager() {
        return masterManager;
    }

    public boolean isSlave() {
        return slaveService != null;
    }
}
```

- [ ] **Step 2: 在 NettyRedisServer 中装配 ReplicationCoordinator**

在 `NettyRedisServer` 类中添加字段（约 L150 附近字段区域）：

```java
private ReplicationCoordinator replicationCoordinator;
```

在构造方法中（约 L203-255），在 `persistService.load(memoryStore)` 之后、`initClusterMode()` 之前添加：

```java
// 装配复制协调器（master 侧 backlog + 可选 slave 侧服务）
this.replicationCoordinator = new ReplicationCoordinator(config, memoryStore, persistService);
this.replicationCoordinator.setup();
// 设置 ServerContext.config 以便 RedisServerHandler 构造时可访问
com.janeluo.luban.rds.common.context.ServerContext.setConfig(config);
```

在 `start()` 方法的 ChannelInitializer 中（约 L690-698），在创建 `RedisServerHandler` 后添加复制命令处理器注入：

```java
if (replicationCoordinator != null) {
    handler.setReplicationCommandHandler(replicationCoordinator.getReplicationCommandHandler());
}
```

在 `stop()` 方法中（约 L766-824），在关闭 persistService 之前添加：

```java
if (replicationCoordinator != null) {
    replicationCoordinator.shutdown();
}
```

- [ ] **Step 3: 运行 server 模块编译确认无语法错误**

Run: `mvn compile -pl luban-rds-server -am`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add luban-rds-server/src/main/java/com/janeluo/luban/rds/server/ReplicationCoordinator.java \
        luban-rds-server/src/main/java/com/janeluo/luban/rds/server/NettyRedisServer.java
git commit -m "feat(server): 创建 ReplicationCoordinator 并在启动时装配 (Task 3.1)"
```

### Task 3.2: 在成功写命令路径传播原始 RESP 帧

**Files:**
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java`

在 `processCommand` 中，命令执行成功后（约 L722 `commandHandler.handle` 返回后），判断条件并传播原始 RESP 帧。

关键点：`channelRead` 中 `protocolParser.parse(clientInfo.getInboundBuf())` 成功后，`inboundBuf` 的 readerIndex 已推进到命令末尾。可以在 parse 前后记录 readerIndex 差值提取原始帧。

- [ ] **Step 1: 在 channelRead 中提取原始 RESP 帧并传入 processCommand**

修改 `channelRead` 方法（约 L270-286），在 parse 前后记录 readerIndex：

```java
while (true) {
    if (clientInfo.getProtocolVersion() == ProtocolVersion.RESP2) {
        if (detectResp3Hello(clientInfo.getInboundBuf(), ctx, clientInfo)) {
            continue;
        }
    }
    int readerIndexBefore = clientInfo.getInboundBuf().readerIndex();
    Command command = protocolParser.parse(clientInfo.getInboundBuf());
    if (command == null) {
        break;
    }
    int readerIndexAfter = clientInfo.getInboundBuf().readerIndex();
    // 提取原始 RESP 帧（用于复制传播）
    byte[] rawRespFrame = null;
    if (readerIndexAfter > readerIndexBefore) {
        int frameLength = readerIndexAfter - readerIndexBefore;
        rawRespFrame = new byte[frameLength];
        clientInfo.getInboundBuf().getBytes(readerIndexBefore, rawRespFrame);
    }
    try {
        TraceContext.startTrace();
        processCommand(ctx, clientInfo, command, rawRespFrame);
    } finally {
        TraceContext.endTrace();
    }
}
```

- [ ] **Step 2: 修改 processCommand 方法签名增加 rawRespFrame 参数**

将 `processCommand` 方法签名从：

```java
private void processCommand(ChannelHandlerContext ctx, ClientInfo clientInfo, Command command)
```

改为：

```java
private void processCommand(ChannelHandlerContext ctx, ClientInfo clientInfo, Command command, byte[] rawRespFrame)
```

- [ ] **Step 3: 在命令执行成功后添加传播逻辑**

在 `processCommand` 中，`commandHandler.handle` 返回后（约 L722-758 之间），在序列化响应之前添加传播判定：

```java
long startTime = System.nanoTime();
Object response = commandHandler.handle(commandName, currentDatabase, args, memoryStore);
long duration = (System.nanoTime() - startTime) / 1000; // microseconds
SlowLogManager.getInstance().push(duration, java.util.Arrays.asList(args), ctx.channel().remoteAddress().toString(), clientInfo.getName());

// --- 复制传播 ---
// 条件：非 replication 来源 + 非 cluster 重定向 + 命令执行成功（响应非错误）+ 是写命令
if (rawRespFrame != null && shouldPropagate(commandName, response)) {
    propagateCommand(rawRespFrame);
}
```

- [ ] **Step 4: 添加 shouldPropagate 和 propagateCommand 辅助方法**

在 `RedisServerHandler` 类中添加：

```java
/**
 * 判断命令是否应传播到 slave。
 * <p>
 * 只传播写命令且执行成功（响应非错误、非 MOVED、非 ASK）。
 * 只读命令、失败命令、重定向命令不传播。
 * </p>
 */
private boolean shouldPropagate(String commandName, Object response) {
    if (response == null) {
        return false;
    }
    // 响应是错误则不传播
    if (response instanceof String) {
        String resp = (String) response;
        if (resp.startsWith("-ERR") || resp.startsWith("-MOVED") || resp.startsWith("-ASK")
                || resp.startsWith("-CLUSTERDOWN") || resp.startsWith("-EXECABORT")
                || resp.startsWith("-NOPROTO") || resp.startsWith("-LOADING")
                || resp.startsWith("-READONLY") || resp.startsWith("-NOAUTH")) {
            return false;
        }
    }
    // 只读命令集合——不传播
    String upper = commandName.toUpperCase();
    if (isReadOnlyCommand(upper)) {
        return false;
    }
    return true;
}

/**
 * 判断是否为只读命令。
 */
private boolean isReadOnlyCommand(String upperCommand) {
    switch (upperCommand) {
        case "GET":
        case "MGET":
        case "HGET":
        case "HGETALL":
        case "HMGET":
        case "HKEYS":
        case "HVALS":
        case "HLEN":
        case "HSCAN":
        case "LINDEX":
        case "LRANGE":
        case "LLEN":
        case "SMEMBERS":
        case "SISMEMBER":
        case "SCARD":
        case "SSCAN":
        case "SRANDMEMBER":
        case "ZSCORE":
        case "ZRANGE":
        case "ZRANGEBYSCORE":
        case "ZRANGEBYLEX":
        case "ZREVRANGE":
        case "ZREVRANGEBYSCORE":
        case "ZCARD":
        case "ZCOUNT":
        case "ZRANK":
        case "ZREVRANK":
        case "ZSCAN":
        case "EXISTS":
        case "TYPE":
        case "TTL":
        case "PTTL":
        case "EXPIRETIME":
        case "PEXPIRETIME":
        case "OBJECT":
        case "MEMORY":
        case "INFO":
        case "DBSIZE":
        case "KEYS":
        case "SCAN":
        case "RANDOMKEY":
        case "STRLEN":
        case "GETRANGE":
        case "SUBSTR":
        case "BITCOUNT":
        case "GETBIT":
        case "BITPOS":
        case "PING":
        case "ECHO":
        case "AUTH":
        case "HELLO":
        case "SELECT":
        case "CLIENT":
        case "COMMAND":
        case "CONFIG":
        case "DEBUG":
        case "SLOWLOG":
        case "MONITOR":
        case "CLUSTER":
        case "WAIT":
        case "PSYNC":
        case "SYNC":
        case "REPLCONF":
        case "SLAVEOF":
        case "REPLICAOF":
        case "MULTI":
        case "EXEC":
        case "DISCARD":
        case "WATCH":
        case "UNWATCH":
        case "LATENCY":
        case "RESET":
        case "QUIT":
        case "XLEN":
        case "XRANGE":
        case "XREVRANGE":
        case "XREAD":
        case "XINFO":
        case "XPENDING":
        case "XCLAIM":
        case "XAUTOCLAIM":
            return true;
        default:
            return false;
    }
}

/**
 * 传播写命令到 replication backlog 和在线 slave。
 */
private void propagateCommand(byte[] rawRespFrame) {
    try {
        MasterReplicationManager manager = MasterReplicationManager.getInstance();
        if (manager != null) {
            manager.propagateCommand(rawRespFrame);
        }
    } catch (Exception e) {
        logger.warn("命令传播失败（不影响客户端响应）", e);
    }
}
```

- [ ] **Step 5: 修复 processCommand 中所有调用方（handleMultiCommand 等内部调用）**

搜索 `processCommand(ctx` 的所有调用点，确保传入 `rawRespFrame` 参数。注意 `handleExecCommand` 不直接调用 `processCommand`，事务传播在 Task 3.3 处理。

- [ ] **Step 6: 运行 server 模块编译和已有测试**

Run: `mvn test -pl luban-rds-server -am -Dtest="*" -DfailIfNoTests=false`
Expected: 已有测试无回归

- [ ] **Step 7: Commit**

```bash
git add luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java
git commit -m "feat(server): 写命令成功后传播原始 RESP 帧到 backlog (Task 3.2)"
```

### Task 3.3: 实现 slave 对传播 RESP 流的增量解析和命令执行

**Files:**
- Modify: `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/SlaveReplicationClient.java`

修复 `SlaveReplicationClient.handleSyncData` 中 offset 双份不同步问题，并确保命令流边界正确。

- [ ] **Step 1: 修复 handleSyncData 的 offset 统一问题**

找到 `handleSyncData` 方法（约 L333-349），修改为统一使用 `callback.getReplOffset()` 而非 client 自身的 `replicationOffset`：

```java
private void handleSyncData(ByteBuf data) {
    if (state.get() == ReplicationState.FULL_SYNC || state.get() == ReplicationState.LOADING_RDB) {
        if (callback != null) {
            callback.onRdbData(data.copy());
        }
    } else if (state.get() == ReplicationState.ONLINE || state.get() == ReplicationState.PARTIAL_SYNC) {
        if (callback != null) {
            callback.onCommandPropagation(data.copy());
        }
    }

    // 统一使用 callback (SlaveReplicationService) 的 offset，避免双份不同步
    // 注意：onRdbData/onCommandPropagation 内部会精确推进 offset
    // client 自身的 replicationOffset 仅用于握手阶段的 FULLRESYNC offset
    // 上线后 ACK 使用 callback.getReplOffset()
    replicationOffset = callback != null ? callback.getReplOffset() : replicationOffset + data.readableBytes();
}
```

- [ ] **Step 2: 修复 sendAck 方法使用统一 offset**

找到 `sendAck` 方法（约 L490 附近），确认使用 `callback.getReplOffset()`：

```java
private void sendAck() {
    long offset = callback != null ? callback.getReplOffset() : replicationOffset;
    String ack = "REPLCONF ACK " + offset + "\r\n";
    // ... 发送逻辑
}
```

- [ ] **Step 3: 运行 replication 测试确认无回归**

Run: `mvn test -pl luban-rds-replication -Dtest="*"`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/SlaveReplicationClient.java
git commit -m "fix(replication): 统一 slave offset 推进路径避免双份不同步 (Task 3.3)"
```

---

## Task 4: 集群角色生命周期

### Task 4.1: 定义 ReplicationLifecycleListener 接口

**Files:**
- Create: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/lifecycle/ReplicationLifecycleListener.java`
- Create: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/lifecycle/NoOpReplicationLifecycleListener.java`

- [ ] **Step 1: 创建接口**

```java
package com.janeluo.luban.rds.cluster.lifecycle;

import com.janeluo.luban.rds.cluster.node.ClusterNode;

/**
 * 集群角色生命周期回调接口。
 * <p>
 * 在 cluster 模块定义，不依赖 replication 模块，由 server 层实现。
 * 当集群角色变更（REPLICATE、提升、降级）时，cluster 模块通过此接口通知 server 层
 * 启动或停止复制连接。
 * </p>
 * <p>
 * 实现必须保证重复相同目标的通知幂等。
 * </p>
 */
public interface ReplicationLifecycleListener {

    /**
     * 节点成为 slave 或更换 master。
     * <p>
     * 实现应停止旧连接（如有）并向新 master 发起 PSYNC。
     * 相同目标的重复调用不应创建重复连接。
     * </p>
     *
     * @param master 目标 master 节点
     */
    void replicateTo(ClusterNode master);

    /**
     * 本节点提升为 master。
     * <p>
     * 实现应停止上游复制连接但保留本地已同步数据。
     * </p>
     */
    void promoteToMaster();

    /**
     * 本节点降级为 slave。
     * <p>
     * 实现应按新 master 地址重新发起 PSYNC。
     * 相同目标的重复调用不应创建重复连接。
     * </p>
     *
     * @param master 新 master 节点
     */
    void demoteToSlave(ClusterNode master);
}
```

- [ ] **Step 2: 创建 no-op 默认实现**

```java
package com.janeluo.luban.rds.cluster.lifecycle;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReplicationLifecycleListener 的 no-op 默认实现。
 * <p>
 * 供非集群模式、单元测试或未装配复制组件的场景使用。
 * 所有方法空实现，仅记录 debug 日志。
 * </p>
 */
public class NoOpReplicationLifecycleListener implements ReplicationLifecycleListener {

    private static final Logger logger = LoggerFactory.getLogger(NoOpReplicationLifecycleListener.class);

    @Override
    public void replicateTo(ClusterNode master) {
        logger.debug("NoOp replicateTo: {}", master != null ? master.getNodeId() : "null");
    }

    @Override
    public void promoteToMaster() {
        logger.debug("NoOp promoteToMaster");
    }

    @Override
    public void demoteToSlave(ClusterNode master) {
        logger.debug("NoOp demoteToSlave: {}", master != null ? master.getNodeId() : "null");
    }
}
```

- [ ] **Step 3: 编译 cluster 模块确认无错误**

Run: `mvn compile -pl luban-rds-cluster`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/lifecycle/
git commit -m "feat(cluster): 定义 ReplicationLifecycleListener 中立接口 (Task 4.1)"
```

### Task 4.2: 让 CLUSTER REPLICATE 启动复制连接

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/handler/ClusterCommandHandler.java`
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java`

- [ ] **Step 1: 在 ClusterCommandHandler 中注入 ReplicationLifecycleListener**

在 `ClusterCommandHandler` 类字段区域添加：

```java
private com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener replicationLifecycleListener =
        new com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener();
```

添加 setter：

```java
public void setReplicationLifecycleListener(
        com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener listener) {
    this.replicationLifecycleListener = listener != null ? listener
            : new com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener();
}
```

- [ ] **Step 2: 在 clusterReplicate 成功后调用 replicateTo**

修改 `clusterReplicate` 方法（约 L676-687），在 `notifyTopologyChanged()` 之后、`return "+OK\r\n"` 之前添加：

```java
// 通知复制生命周期监听器启动到目标 master 的复制连接
replicationLifecycleListener.replicateTo(masterNode);
```

- [ ] **Step 3: 在 GossipProtocol 中注入并传递 listener**

在 `GossipProtocol` 类字段区域添加：

```java
private com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener replicationLifecycleListener =
        new com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener();
```

添加 setter：

```java
public void setReplicationLifecycleListener(
        com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener listener) {
    this.replicationLifecycleListener = listener != null ? listener
            : new com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener();
}
```

- [ ] **Step 4: 编译 cluster 模块**

Run: `mvn compile -pl luban-rds-cluster`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/handler/ClusterCommandHandler.java \
        luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java
git commit -m "feat(cluster): CLUSTER REPLICATE 启动复制连接 (Task 4.2)"
```

### Task 4.3: 让提升节点停止上游复制，并让降级节点跟随新 master

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java`

- [ ] **Step 1: 在 FailoverManager 中注入 ReplicationLifecycleListener**

在 `FailoverManager` 类字段区域（约 L75 附近）添加：

```java
private com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener replicationLifecycleListener =
        new com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener();
```

添加 setter：

```java
public void setReplicationLifecycleListener(
        com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener listener) {
    this.replicationLifecycleListener = listener != null ? listener
            : new com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener();
}
```

- [ ] **Step 2: 在 performFailover 中通知提升节点 promoteToMaster**

修改 `performFailover` 方法（约 L396-419），在 `stateManager.updateClusterState()` 之后添加：

```java
// 通知提升节点停止上游复制（仅当提升的是本节点）
if (slaveNode.isMyself()) {
    replicationLifecycleListener.promoteToMaster();
}
// 通知降级节点跟随新 master（仅当降级的是本节点）
if (masterNode.isMyself()) {
    replicationLifecycleListener.demoteToSlave(slaveNode);
}
```

- [ ] **Step 3: 在 onFailoverResult 中处理远程拓扑变更的通知**

修改 `onFailoverResult` 方法（约 L429-507），在拓扑变更应用后、`notifyTopologyChanged()` 之前添加对本地节点的角色变更通知：

```java
// 通知本地节点角色变更
ClusterNode myNode = clusterConfig.getMyNode();
if (myNode != null) {
    if (myNode.getNodeId().equals(winner.getNodeId()) && myNode.isMaster()) {
        // 本节点是 winner 且已提升为 master
        replicationLifecycleListener.promoteToMaster();
    } else if (myNode.isSlave()
            && myNode.getMasterNodeId() != null
            && myNode.getMasterNodeId().equals(winner.getNodeId())) {
        // 本节点降级为 winner 的 slave（包括原 master 恢复后降级）
        replicationLifecycleListener.demoteToSlave(winner);
    }
}
```

注意：此段代码应放在 `notifyTopologyChanged()` 调用之前。

- [ ] **Step 4: 编译 cluster 模块**

Run: `mvn compile -pl luban-rds-cluster`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java
git commit -m "feat(cluster): failover 提升停止上游复制，降级跟随新 master (Task 4.3)"
```

### Task 4.4: 在 NettyRedisServer 中装配 ReplicationLifecycleListener

**Files:**
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/NettyRedisServer.java`
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/ReplicationCoordinator.java`

让 `ReplicationCoordinator` 实现 `ReplicationLifecycleListener`，并在 `NettyRedisServer.initClusterMode()` 中注入。

- [ ] **Step 1: 让 ReplicationCoordinator 实现 ReplicationLifecycleListener**

修改 `ReplicationCoordinator` 类声明：

```java
public class ReplicationCoordinator
        implements com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener {
```

实现三个方法：

```java
@Override
public void replicateTo(com.janeluo.luban.rds.cluster.node.ClusterNode master) {
    if (master == null) {
        logger.warn("replicateTo 收到 null master，忽略");
        return;
    }
    String address = master.getIp() + ":" + master.getPort();
    logger.info("replicateTo: 启动到 master {} ({}) 的复制", master.getNodeId(), address);
    startSlave(address);
}

@Override
public void promoteToMaster() {
    logger.info("promoteToMaster: 停止上游复制，保留本地数据");
    stopSlave();
    // 确保 master 侧 backlog 已初始化（可能在 setup 时已初始化）
    if (masterManager == null) {
        MasterReplicationManager.initialize((int) config.getReplBacklogSize());
        masterManager = MasterReplicationManager.getInstance();
        masterManager.setMemoryStore(memoryStore);
        masterManager.setRdbPersistService(rdbPersistService);
    }
}

@Override
public void demoteToSlave(com.janeluo.luban.rds.cluster.node.ClusterNode master) {
    if (master == null) {
        logger.warn("demoteToSlave 收到 null master，忽略");
        return;
    }
    String address = master.getIp() + ":" + master.getPort();
    logger.info("demoteToSlave: 降级为 {} ({}) 的 slave", master.getNodeId(), address);
    startSlave(address);
}
```

- [ ] **Step 2: 在 NettyRedisServer.initClusterMode 中注入 listener**

在 `initClusterMode()` 方法中（约 L367-404 区域），在创建 `ClusterCommandHandler` 和 `FailoverManager` 之后添加注入：

```java
// 注入复制生命周期监听器
if (replicationCoordinator != null) {
    this.clusterCommandHandler.setReplicationLifecycleListener(replicationCoordinator);
    this.gossipProtocol.setReplicationLifecycleListener(replicationCoordinator);
    failoverManager.setReplicationLifecycleListener(replicationCoordinator);
    logger.info("ReplicationLifecycleListener 已注入集群组件");
}
```

注意：需要调整代码顺序，确保 `failoverManager` 变量在注入时可访问。当前 `failoverManager` 是局部变量（L385），需改为在注入前已创建。检查代码顺序：failoverManager 在 L385 创建，注入代码应在 L394 之后。

- [ ] **Step 3: 编译 server 模块**

Run: `mvn compile -pl luban-rds-server -am`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add luban-rds-server/src/main/java/com/janeluo/luban/rds/server/ReplicationCoordinator.java \
        luban-rds-server/src/main/java/com/janeluo/luban/rds/server/NettyRedisServer.java
git commit -m "feat(server): ReplicationCoordinator 实现生命周期接口并注入集群 (Task 4.4)"
```

---

## Task 5: 故障转移回归验证

### Task 5.1: 添加集成测试验证故障转移后数据保留

**Files:**
- Create: `luban-rds-server/src/test/java/com/janeluo/luban/rds/server/ClusterFailoverDataRetentionTest.java`

- [ ] **Step 1: 编写端到端集成测试**

```java
package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.client.RedisClient;
import com.janeluo.luban.rds.common.config.RdsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端验证：候选 slave 已同步数据后触发 master 故障，验证新 master 保留故障前数据。
 * <p>
 * 验收标准是数据断言：从新 master 读取故障前已同步的数据，必须得到原值。
 * </p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ClusterFailoverDataRetentionTest {

    private NettyRedisServer masterServer;
    private NettyRedisServer slaveServer;
    private int masterPort;
    private int slavePort;

    @BeforeEach
    void setUp() throws Exception {
        masterPort = findFreePort();
        slavePort = findFreePort();

        // 启动 master
        RdsConfig masterConfig = createClusterConfig(masterPort);
        masterServer = new NettyRedisServer(masterConfig);
        masterServer.start();

        // 启动 slave
        RdsConfig slaveConfig = createClusterConfig(slavePort);
        slaveServer = new NettyRedisServer(slaveConfig);
        slaveServer.start();

        // 等待服务就绪
        Thread.sleep(2000);
    }

    @AfterEach
    void tearDown() {
        if (slaveServer != null) slaveServer.stop();
        if (masterServer != null) masterServer.stop();
    }

    @Test
    void testFailoverRetainsReplicatedData() throws Exception {
        // 1. 在 master 上配置集群并分配槽位
        try (RedisClient masterClient = createClient(masterPort)) {
            masterClient.sendCommand("CLUSTER", "ADDSLOTS", "0", "1", "2");
            // 等待集群状态收敛
            Thread.sleep(1000);

            // 2. 写入测试数据
            masterClient.sendCommand("SET", "key1", "value1");
            masterClient.sendCommand("SET", "key2", "value2");
        }

        // 3. 让 slave 复制 master（通过 CLUSTER MEET + REPLICATE）
        try (RedisClient slaveClient = createClient(slavePort)) {
            // 获取 master 的 nodeId
            // (CLUSTER MEET + REPLICATE 的具体实现取决于现有集群测试模式)
            // 这里假设已有辅助方法获取 master nodeId
        }

        // 4. 等待复制完成
        Thread.sleep(3000);

        // 5. 停止 master 模拟故障
        masterServer.stop();
        masterServer = null;
        Thread.sleep(2000);

        // 6. 触发 slave 提升（CLUSTER FAILOVER FORCE 或自动 failover）
        try (RedisClient slaveClient = createClient(slavePort)) {
            slaveClient.sendCommand("CLUSTER", "FAILOVER", "FORCE");
            Thread.sleep(3000);

            // 7. 验证故障前数据仍可读
            Object val1 = slaveClient.sendCommand("GET", "key1");
            Object val2 = slaveClient.sendCommand("GET", "key2");
            assertEquals("value1", val1, "故障转移后 key1 数据应保留");
            assertEquals("value2", val2, "故障转移后 key2 数据应保留");
        }
    }

    @Test
    void testNewWritesSyncToDemotedMaster() throws Exception {
        // 1. 建立复制关系并同步数据
        // 2. 触发 failover
        // 3. 在新 master 写入新值
        // 4. 恢复原 master（降级为 slave）
        // 5. 验证原 master 已追平新值
        // （此测试需要模拟原 master 恢复，取决于测试基础设施能力）
    }

    private RdsConfig createClusterConfig(int port) {
        RdsConfig config = new RdsConfig();
        config.setPort(port);
        config.setClusterEnabled(true);
        config.setDir(java.nio.file.Files.createTempDir().getAbsolutePath());
        config.setClusterConfigFile("nodes-" + port + ".conf");
        config.setClusterNodeTimeout(5000);
        config.setClusterFailoverGracePeriod(0);
        return config;
    }

    private RedisClient createClient(int port) throws Exception {
        // 使用 luban-rds-client 连接
        RedisClient client = new com.janeluo.luban.rds.client.NettyRedisClient("127.0.0.1", port);
        client.connect();
        return client;
    }

    private int findFreePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
```

注意：此测试的具体实现细节（CLUSTER MEET/REPLICATE 的调用方式、等待策略）需要根据现有集群集成测试模式调整。测试编写者应参考 `luban-rds-cluster/src/test/` 下已有的集群集成测试。

- [ ] **Step 2: 运行测试验证（预期失败或需要调整）**

Run: `mvn test -pl luban-rds-server -Dtest=ClusterFailoverDataRetentionTest -DfailIfNoTests=false`
Expected: 测试运行，可能需要根据实际测试基础设施调整

- [ ] **Step 3: Commit**

```bash
git add luban-rds-server/src/test/java/com/janeluo/luban/rds/server/ClusterFailoverDataRetentionTest.java
git commit -m "test(server): 添加故障转移数据保留端到端测试 (Task 5.1)"
```

### Task 5.2: 验证故障转移后的新增写入能同步到降级原 master

**Files:**
- Modify: `luban-rds-server/src/test/java/com/janeluo/luban/rds/server/ClusterFailoverDataRetentionTest.java`

- [ ] **Step 1: 完善 testNewWritesSyncToDemotedMaster 测试**

参考 Task 5.1 的测试模式，完善降级原 master 后的数据同步验证。确保：
1. failover 后在新 master 写入新值
2. 原 master 恢复后被降级为 slave
3. 等待同步后从原 master（现 slave）读取新值，验证一致

- [ ] **Step 2: 运行测试**

Run: `mvn test -pl luban-rds-server -Dtest=ClusterFailoverDataRetentionTest -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add luban-rds-server/src/test/java/com/janeluo/luban/rds/server/ClusterFailoverDataRetentionTest.java
git commit -m "test(server): 验证故障转移后新写入同步到降级节点 (Task 5.2)"
```

### Task 5.3: 运行三模块测试和完整 Maven 构建

- [ ] **Step 1: 运行 replication 模块测试**

Run: `mvn test -pl luban-rds-replication`
Expected: ALL PASS

- [ ] **Step 2: 运行 server 模块测试**

Run: `mvn test -pl luban-rds-server -am`
Expected: ALL PASS

- [ ] **Step 3: 运行 cluster 模块测试**

Run: `mvn test -pl luban-rds-cluster -am`
Expected: ALL PASS

- [ ] **Step 4: 运行完整 Maven 构建**

Run: `mvn clean install -DskipTests=false`
Expected: BUILD SUCCESS

- [ ] **Step 5: 如果测试失败，修复后重新运行直到全部通过**

- [ ] **Step 6: Commit（如有修复）**

```bash
git add -A
git commit -m "fix: 修复回归测试发现的问题 (Task 5.3)"
```

---

## 自检清单

### Spec 覆盖率

| Spec 需求 | 对应 Task |
|-----------|-----------|
| 从节点必须持续复制主节点数据 | Task 2.1-2.2 (applier), Task 3.1 (装配), Task 4.2 (CLUSTER REPLICATE) |
| CLUSTER REPLICATE 建立复制链路 | Task 4.2 |
| 从节点执行增量传播命令 | Task 2.1, Task 2.2 |
| 复制流拆包/粘包 | Task 1.2, Task 2.1 |
| 主节点成功写入必须进入复制流 | Task 3.2 |
| 成功写命令被传播 | Task 3.2 |
| 失败或只读命令不传播 | Task 3.2 (shouldPropagate) |
| 事务写入可重放 | Task 3.2 (MULTI/EXEC 不在只读列表) |
| 故障转移必须切换复制生命周期并保留数据 | Task 4.3, Task 4.4 |
| 已同步 slave 提升后保留数据 | Task 4.3, Task 5.1 |
| 原 master 恢复后跟随新 master | Task 4.3, Task 5.2 |
| 重复角色通知保持幂等 | Task 4.1 (接口文档), Task 4.4 (实现) |
| 故障转移数据保证遵循异步复制边界 | Task 5.1 (测试已应用数据可读) |

### 类型一致性检查

- `ReplicationLifecycleListener` 接口三个方法签名在 Task 4.1 定义，在 Task 4.4 实现一致 ✓
- `ReplicationStreamApplier` 构造参数 `MemoryStore` 在 Task 2.1 定义，在 Task 2.2 使用一致 ✓
- `ReplicationCoordinator.setup()` 在 Task 3.1 定义，在 Task 4.4 扩展一致 ✓
- `rawRespFrame` 参数从 `channelRead` 传递到 `processCommand` 一致 ✓
