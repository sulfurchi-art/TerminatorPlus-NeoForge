# 4.22 开发期战局日志

本轮按用户在对话中确认的 P1-1～P1-5 实现。反馈资料保存在 `feedback-4.21.md`，其中正式服观察属于用户提供的资料，本轮只操作隔离自测服。

## 使用

管理员执行 `/bot log on` 开启并保存设置，`/bot log off` 结束并异步排空文件，`/bot log status` 查看路径、排队、写入、丢弃及错误，`/bot log mark 文字` 记录人工标记。输出在服务端工作目录的 `logs/terminatorplus/battle-日期-时间-随机标识.jsonl`。默认关闭。支持在配置中预先开启后冷启动。

`config/terminatorplus/settings.snbt` 中的配置：

```snbt
BattleLog: {
  Enabled: 0b,
  SnapshotTicks: 40,
  Categories: {
    life: 1b, damage: 1b, tactics: 1b, weapons: 1b,
    vehicles: 1b, drones: 1b, c4: 1b, missiles: 1b,
    items: 1b, snap: 1b, summary: 1b
  }
}
```

快照间隔为 1～12000 刻。缺省类别开启；未知类别或非法间隔在配置加载前拒绝。修改文件后使用 `/bot settings reload`。关闭 `damage` 只停写逐次伤害，死亡前 100 刻的有限历史及本局伤害计数继续收集。历史最多 128 条，超出时死亡事件带 `recentDamageTruncated=true`，不会将截断记录冒充完整历史。关闭 `snap` 不影响行为事件。

从 NONE 切换到进攻目标记录 `match_start`；切回 NONE 写 `match_end` 与 `summary`。中途开日志会开始一个从开启时刻计算的记录区间，中途关日志也结束区间。无法补齐开启前的事件。

## 事件含义

- 机器人公共字段：tick、time、type、bot、uuid、team、level、pos、dimension；乘车时有载具完整/短 UUID、类型和原生座位。全局标记、汇总和写入丢弃提示没有机器人字段。
- `spawn` 是首次观察到机器人，含当时实际装备和配装标签；`death` 在确认死亡后发出，双方身份与乘车信息在死亡处理前采集；伤害来源和爆头根据真实 DamageSource 判断。
- `damage` 是 NeoForge 的实际正值 Post 伤害，`totem` 在原生图腾事件未取消且实际消耗后记录。`weaponSource=attacker_current_hand` 明确表示攻击者当前手持物，不能把它当成延迟弹丸的发射时武器。
- 卓越前线 `fire` 使用原生 ShootEvent.Post，包含手持枪和座位武器；调用发射函数或进入瞄准状态不计一次开火。`projectile_hit` 是未取消的原生实体命中事件。开火、弹丸命中与正值伤害次数分别统计，不能直接当作同一个命中率；`fire.hit` 为 null。武器字段为注册物品 ID；装备摘要可额外包含数量。
- `board_request/ok/fail` 记录座位请求与等待，`seat_change` 记录空驾驶位补位，`evacuate` 记录弃车原因。`nav` 仅在导航/乘务状态变化时写入。`vehicle_snap` 带实际总血量、部件血量、能量及乘员。
- `drone_launch/target/attack/return/lost/sample` 记录无人机身份、原生投弹/自爆、首次攻击延迟和每 20 刻高度/垂直速度。控制结束保留实体；主人死亡也不删除无人机。
- C4 的 approach、orbit_start、throw、detonate、end、exit 记录阶段时间和累计转向。安全检查仍覆盖所有己方 C4；不安全时不会遥控引爆。
- `missile_warning` 区分 locking/locked 并记录声音坐标；`missile_seen` 记录实体威胁；`shelter_start/done/removed`、`decoy` 与方块事件记录有限防御过程。
- 自动生成团队喷溅药水已取消，`potion_throw` 正常应为 0。伤员护卫和真实消耗型治疗继续工作。

## 文件与性能边界

世界、实体、背包及队伍只在服务器主线程读取并生成 JSON 字符串。写入线程只接收不可变字符串，不运行 AI。队列最多 16384 行，文件使用缓冲输出、每秒刷新；队列满时立即丢弃新记录，并写 `log_gap` 及累计数量，`status/summary` 也显示丢弃数。磁盘异常停止该文件写入并在服务器日志报告。开关不等待磁盘，关服才做有限等待。

日志目前没有自动轮转/删除策略，开发时按需开启，并自行归档历史文件。类别关闭会减少 JSON 构造和文件体积。快照采集与 JSON 序列化仍占用少量主线程时间；“写入异线程”不能代表 100 人机战争已经完成性能验收。

## 分析

```powershell
python tools/analyze_battle.py logs/terminatorplus/battle-文件.jsonl --output battle-analysis.json
```

脚本输出事件数、逐机器人事件、无人机攻击延迟/高度样本、C4/掩体阶段和汇总，检查丢弃提示、超出 100 刻首攻、80 刻盘旋或 4 块掩体。移动/爬升阶段的高度变化不等于悬停抽动；分析悬停需选择固定高度目标的时间段。

具体测试结果与交付哈希另见最终验证说明；本文件说明接口，不把正在运行的测试当作通过。

闲置时取消步兵与乘务的自动扫视；战术任务需要的转向仍正常执行。反导最多 4 块、25 秒冷却，小掩体不保证躲过标枪。最终验证见 [4.22 验证记录](validation-4.22.md)。
