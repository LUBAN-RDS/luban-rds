# Tasks: fix-cluster-eval-slot-redirect

- [x] T1: 从 `NO_KEY_COMMANDS` 移除 `EVAL`/`EVALSHA`，使集群重定向检查对脚本命令生效
- [x] T2: 在集群重定向检查块中为 EVAL/EVALSHA 增加 CROSSSLOT 校验（numkeys>1 时所有 KEYS 必须同 slot，否则返回 `-CROSSSLOT`）
- [x] T3: 补充单元测试覆盖：单 key 正常执行、多 key 同 slot 正常执行、多 key 跨 slot 返回 CROSSSLOT、key 不在本节点返回 MOVED、numkeys=0 不重定向、单机模式不受影响
- [x] T4: 运行模块测试与构建确认通过
