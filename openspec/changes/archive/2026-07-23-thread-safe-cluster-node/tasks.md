# Tasks: ClusterNode 线程安全

- [x] 1. 给 `addState`/`removeState`/`hasState` 方法加 `synchronized`
- [x] 2. 给 `addSlot`/`removeSlot`/`clearSlots`/`setSlots`/`hasSlot`/`getSlotCount` 方法加 `synchronized`
- [x] 3. 给 `setMasterNodeId`/`setConfigEpoch`/`setConfigEpochIfGreater`/`updateLastPingTime`/`updateLastPongTime` 方法加 `synchronized`
