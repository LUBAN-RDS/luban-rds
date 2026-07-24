# Design: ClusterNode 线程安全

## 策略
给 `ClusterNode` 的状态变更方法加 `synchronized`，使用对象内置锁（`this`）。

## 需要同步的方法

### 状态字段 (`EnumSet<ClusterNodeState>`)
- `addState(ClusterNodeState)` → `synchronized`
- `removeState(ClusterNodeState)` → `synchronized`
- `hasState(ClusterNodeState)` → `synchronized`（读也加锁，保证可见性）

### 槽位字段 (`BitSet slots`)
- `addSlot(int)` → `synchronized`
- `removeSlot(int)` → `synchronized`
- `clearSlots()` → `synchronized`
- `setSlots(BitSet)` → `synchronized`
- `hasSlot(int)` → `synchronized`

### 其他可变字段
- `setMasterNodeId(String)` → `synchronized`
- `setConfigEpoch(long)` → `synchronized`
- `setConfigEpochIfGreater(long)` → `synchronized`
- `updateLastPingTime()` → `synchronized`
- `updateLastPongTime()` → `synchronized`

### 不需要同步
- 构造方法（单线程初始化）
- getter 方法（`getNodeId`, `getIp`, `getPort` 等）— 读陈旧值可接受
- `getTimeSinceLastPong()` — 读陈旧值可接受

## 风险
- 锁粒度粗（对象级），但 ClusterNode 方法调用频率低（Gossip 每秒一次），不会成为瓶颈
- 只影响集群模块，不影响数据面命令处理
