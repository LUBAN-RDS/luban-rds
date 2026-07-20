# AGENTS.MD - AI Agent Context & Guidelines

## 1. Project Overview
**Luban-RDS** is a lightweight, high-performance, in-memory key-value store in Java, fully compatible with Redis protocol (RESP).

**Key Features**: RESP protocol, in-memory data structures, Lua scripting, RDB/AOF persistence, Netty NIO server, Pub/Sub, Spring Boot integration, distributed tracing.

## 2. Project Structure (Maven Modules)

| Module | Key Classes |
| :--- | :--- |
| **luban-rds-core** | `CommandHandler`, `MemoryStore`, `LuaCommandHandler` |
| **luban-rds-protocol** | `RedisProtocolParser`, `Command`, `RespType` |
| **luban-rds-server** | `NettyRedisServer`, `RedisServerHandler`, `PubSubManager` |
| **luban-rds-persistence** | `PersistService`, `RdbPersistService`, `AofPersistService` |
| **luban-rds-replication** | `MasterReplicationManager`, `SlaveReplicationService`, `ReplicationBacklog` |
| **luban-rds-client** | `RedisClient`, `NettyRedisClient` |
| **luban-rds-common** | `Constants`, `Utils`, `TraceContext` |

## 3. Core Architecture

### Command Handling Flow
Network → Protocol Parsing → Dispatch → Execution → Response

### Key Components
- **Pub/Sub**: `PubSubManager` with bidirectional mappings
- **Transactions**: `MULTI`/`EXEC`/`DISCARD`/`WATCH` support
- **Lua Scripting**: `LuaCommandHandler` with sandbox mode
- **MONITOR**: Real-time command monitoring via `MonitorManager`

## 4. Development Guidelines

### Environment Requirements
- **Java**: JDK 17+
- **Maven**: 3.6+

### Essential Commands
```bash
mvn clean install          # Build all, run tests
mvn test -Dtest=ClassName  # Run single test class
mvn test -pl luban-rds-replication -Dtest=ClassName  # Test in module
mvn jacoco:report          # Generate coverage report
```

### Code Style
- **Indentation**: 4 spaces (NO tabs)
- **Line Length**: Max 120 characters
- **Imports**: ALWAYS use explicit imports, NO inline fully-qualified names
- **Naming**: PascalCase for classes, camelCase for methods/variables
- **Generics**: NEVER use raw types
- **Error Handling**: Return RESP error format `-ERR message\r\n`
- **Logging**: SLF4J, never log sensitive data

### Testing
- **Framework**: JUnit 5 (Jupiter)
- **Naming**: `ClassNameTest.java`, `testFeature()` methods
- **Coverage**: Aim high on core modules

## 5. Common Pitfalls
1. ❌ Inline package names → Always use imports
2. ❌ Raw types → Always specify generics
3. ❌ Exposing exceptions → Return RESP errors
4. ❌ Blocking I/O → Use async operations

## 6. Thread Model

```
Boss Group (1 thread) → Worker Group (N threads) → Business Group (M threads)
```

- `io-threads`: Boss threads (default: 1)
- `worker-threads`: Worker threads (default: CPU cores * 2)
- `business-threads`: Business threads (default: CPU cores)

## 7. Memory Management
- **Pool**: `PooledByteBufAllocator`, config: `use-pool yes/no`
- **Defragmentation**: Auto-triggered at 30% fragmentation
- **Commands**: `MEMORY PURGE`, `INFO memory`

## 8. Distributed Tracing
- **TraceId Format**: `{timestamp}-{machineId}-{processId}-{sequence}`
- **Key Classes**: `TraceContext`, `TraceableRunnable`, `TraceableCallable`
- **Usage**: Auto-generated at request entry via `TraceContext.startTrace()`

## 9. Master-Slave Replication

### Core Components
| Component | Description |
|-----------|-------------|
| MasterReplicationManager | Master node manager |
| SlaveReplicationService | Slave node service |
| ReplicationBacklog | Backlog buffer for partial sync |
| ReplicationCommandHandler | SLAVEOF, PSYNC, REPLCONF handler |

### State Machine
```
DISCONNECTED → CONNECTING → HANDSHAKE_* → FULL_SYNC/PARTIAL_SYNC → ONLINE
```

### Sync Flow
- **Full Sync**: `PSYNC ? -1` → `+FULLRESYNC` → RDB data
- **Partial Sync**: `PSYNC <replid> <offset>` → `+CONTINUE` → backlog data

### Configuration
| Config | Default | Description |
|--------|---------|-------------|
| replicaof | "" | Master address (host:port) |
| masterauth | "" | Master auth password |
| repl-timeout | 60 | Timeout (seconds) |
| repl-backlog-size | 1MB | Backlog buffer size |

### Test Coverage
| Class | Coverage |
|-------|----------|
| ReplicationBacklog | 100% |
| ReplicationState | 100% |
| SlaveInfo | 100% |
| ReplicationCommandHandler | 95.5% |
| MasterReplicationManager | 84.2% |
| SlaveReplicationService | 78.5% |

## 10. Redis Cluster

### Components
| Component | Description |
|-----------|-------------|
| ClusterNode | Node model (ID, address, state, slots) |
| SlotManager | 16384 slot management |
| GossipProtocol | Heartbeat and failure detection |
| FailoverManager | Automatic failover election state machine (IDLE/REQUESTING/ELECTED) |
| ClusterBusServer | Cluster bus (port + 10000) |

### Key Commands
| Command | Description |
|---------|---------|
| CLUSTER INFO | Cluster status |
| CLUSTER NODES | Node list |
| CLUSTER ADDSLOTS | Assign slots |
| CLUSTER KEYSLOT | Calculate key slot |
| CLUSTER FAILOVER [FORCE\|TAKEOVER] | Manual failover (delegates to FailoverManager.performManualFailover) |

### Redirects
- **MOVED**: `-MOVED slot ip:port` (slot belongs to other node)
- **ASK**: `-ASK slot ip:port` (slot migrating)

### Automatic Failover
When a master is marked FAIL by majority consensus, its slave automatically initiates election:
1. slave detects master FAIL → enters REQUESTING, waits `cluster-failover-grace-period` + jitter (0-500ms)
2. slave broadcasts `FailoverAuthRequestMessage` (currentEpoch/configEpoch)
3. Each healthy master votes once per epoch (`votesCast` dedup) → `FailoverAuthAckMessage`
4. Candidate collects majority (masterCount/2+1) → `performFailover` promote + broadcast `FailoverResultMessage`
5. All nodes apply FailoverResult (epoch arbitration): winner → MASTER, old master → SLAVE

| Message | Code | Purpose |
|---------|------|---------|
| FailoverAuthRequestMessage | 0x05 | slave requests vote |
| FailoverAuthAckMessage | 0x06 | master votes |
| FailoverResultMessage | 0x08 | winner broadcasts topology change |

### Configuration
| Config | Default |
|--------|---------|
| cluster-enabled | false |
| cluster-node-timeout | 15000ms |
| cluster-failover-grace-period | 0ms (only random jitter) |

## 11. Code References

### Replication
- [MasterReplicationManager.java](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/MasterReplicationManager.java)
- [ReplicationBacklog.java](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/ReplicationBacklog.java)
- [ReplicationCommandHandler.java](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/handler/ReplicationCommandHandler.java)

### Server
- [RedisServerHandler.java](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java)
- [MonitorManager.java](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/janeluo/luban/rds/server/MonitorManager.java)

### Cluster
- [ClusterConfig.java](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/config/ClusterConfig.java)
- [SlotManager.java](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/slot/SlotManager.java)

---
*Last updated: 2026-03-18*