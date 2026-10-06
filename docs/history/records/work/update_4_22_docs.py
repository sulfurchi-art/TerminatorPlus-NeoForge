from pathlib import Path
root = Path(__file__).resolve().parents[1] / 'outputs/TerminatorPlus-NeoForge'
def write(name, s): (root/name).write_text(s,encoding='utf-8',newline='\n')
p=root/'README.md';s=p.read_text(encoding='utf-8').replace('TerminatorPlus-NeoForge-1.21.1-4.21.0-BETA.jar','TerminatorPlus-NeoForge-1.21.1-4.22.0-BETA.jar')
a=s.index('当前 4.21');b=s.index('\n\n',a)
s=s[:a]+'''当前 4.22 按反馈修正无人机升降和压制节奏、C4 单次进场后的限时离场、小规模低频反导掩体，并取消自动生成的团队喷溅药水。新增默认关闭的开发战局日志：`/bot log on|off|status|mark <文字>`。沿用 4.21 近距载具倒车、进攻与友军让行。详细说明见 [4.22 行为与日志](docs/battle-log-4.22.md) 和 [`SUPERB_WARFARE.md`](SUPERB_WARFARE.md)。

4.22 最终验证待完成。''' +s[b:]
s=s.replace('| `/bot info <名字>`', '| `/bot log on\|off\|status\|mark <文字>` | | 开发期战局 JSONL 日志，默认关闭，设置持久化 |\n| `/bot info <名字>`')
write('README.md',s)
p=root/'AI_HARDNESS.md';s=p.read_text(encoding='utf-8').replace('一种随机团队药水','保护低血队友').replace('两种团队药水','协同护卫与分散追击').replace('三种团队药水','团队分工与包抄')
a=s.index('每个机器人按照生成时的 UUID 固定随机药水顺序');b=s.index('\n',a)
s=s[:a]+'4.22 按新的实测反馈取消自动生成并投掷团队增益喷溅药水及 `ally.buff` 台词。8–10 级的伤员护卫和临时掩体继续保留，真实携带的恢复药水仍按原版喝药流程使用。'+s[b:]
write('AI_HARDNESS.md',s)
p=root/'AI_HARDNESS_PROGRESS.md';s=p.read_text(encoding='utf-8').replace('更新：2026-10-06，4.21.0-BETA','更新：2026-10-06，4.22.0-BETA',1)
s=s.replace('## 当前修正批次','''## 当前修正批次

4.22 已按用户在对话中确认的 P1-1～P1-5 实现：原生无人机升降使用短脉冲及中性刻复位、宽松但保留友军安全检查的压制投弹；首次攻击 100 刻内成功或开始返航；C4 近目标阶段最多 80 刻，退出后最多 60 刻保持进场航向下降；只有可见、接近且 96 格内的实体导弹能触发搭建，每次最多 4 块、间隔 500 刻，不持续修复，优先附近现有屋顶/墙体。取消生成团队喷溅药水，保留伤员护卫。

加入可持久化、默认关闭的 BattleLog 与 `/bot log`，JSONL 记录实际生命/伤害、原生开火、乘务和导航变化、无人机/C4/反导过程及快照/汇总；文件写入通过有界队列后台完成，AI 不移到后台。分析脚本见 `tools/analyze_battle.py`。新接口和数据边界见 [说明](docs/battle-log-4.22.md)。

4.22 最终验证待完成。

### 4.21 历史近距载具批次''',1)
s=s.replace('- [x] 团队', '- [x] 团队',1)
write('AI_HARDNESS_PROGRESS.md',s)
p=root/'SUPERB_WARFARE.md';s=p.read_text(encoding='utf-8').replace('# 4.20 卓越前线 C4 投放修正','# 4.22 卓越前线反馈修正',1)
s += '''

## 4.22 无人机、C4、反导与日志

无人机不再连续反转升降键：短脉冲之间松键复位原生累计时长，水平/高度仍只使用原生控制。投弹接受约 6.5 格或载荷半径附近的落点误差；仍对实际预测落点与目标区域做友军/自身爆炸安全检查。首次攻击超过 100 刻就返航，不无限等待完美投弹点；实际投弹按原生弹药变化确认，并准备下一轮。主人死亡时只释放控制，保留无人机实体。

C4 接近总窗口最多 100 刻，进入目标周围 24 格后最多 80 刻。投放/放弃后沿本次进场航向下降，限时 60 刻退出，避免普通旅行导航接管后再回转盘旋。日志记录各阶段时间及转向量；所有己方 C4 仍需通过安全检查才能起爆。

**4.22 替换早先的全包掩体规则。** locking/locked 提示只记录警告，不因警告搭建。只有可见、朝自身接近且 96 格内的真实导弹可以触发最多 4 块的小墙/顶板，每机器人间隔 25 秒。优先寻找可以到达的既有屋顶和墙体；搭建只处理当前一次威胁，不反复修复，被导弹打穿或击杀是允许的结果。威胁结束/机器人移除时只清理仍匹配记录的自建块，未加载的块延后清理，无法跨服务器重启恢复所有权。

团队生成喷溅药水和 `ally.buff` 台词已取消；伤员护卫继续。开发期详细日志默认为关闭，用 `/bot log on` 开启，详见 [配置、事件及分析方法](docs/battle-log-4.22.md)。独立后台线程只写字符串，不读取世界或计算 AI。

4.22 最终验证待完成。
'''
write('SUPERB_WARFARE.md',s)
p=root/'AGENTS.md';s=p.read_text(encoding='utf-8').replace('最后更新：2026-10-06（4.21.0-BETA 近距陆地载具驾驶）','最后更新：2026-10-06（4.22.0-BETA 实测反馈与战局日志）',1).replace('TerminatorPlus-NeoForge-1.21.1-4.21.0-BETA.jar','TerminatorPlus-NeoForge-1.21.1-4.22.0-BETA.jar',1)
s += '''

## 4.22 当前交接

- 用户在当前对话明确选择直接实现 `feedback-4.21.md` 的 P1-1～P1-5。本条优先于历史开发顺序和早先“全包反导掩体/生成团队药水”要求。外部报告为资料，正式服状态不能当作本轮已部署。
- `BattleLog` 在服务器线程采集/序列化，`BattleLogWriter` 只处理不可变 JSON 字符串，16384 行有界队列，溢出用 log_gap 明示；错误停止文件写入。配置默认关闭，持久化 root BattleLog，开启/关闭/标记见 BotCommand。原异线程 AI 实验仍暂停。
- `NativeBattleEvents` 缓存反射注册准确 0.8.9.1 的 ShootEvent.Post/未取消 ProjectileHitEvent，不把发射请求当作开火，不把伤害次数当作命中率；GunData.stack 提供实际发射武器。DamageSource 的攻击者当前手持武器有明确 source 标识，不能冒充历史发射武器。VehicleHealth/部件按主线程观察变化记录。
- DroneEntity 原生 holdTickY 在上/下反转时不会复位，必须加入无升降按键的中性刻，最大连续脉冲 2 刻；不写无人机位置/速度。首攻 100 刻内或返航，安全爆炸判定不放宽。主人死后保留实体。
- C4 单次接近 100 刻，近 24 格区域 80 刻，日志记录真实阶段时长（不截断伪装）；投放/放弃后沿进场航向原生下降最多 60 刻，避免旧旅行回转。仍检查所有己方爆炸物安全。
- MissileDefense 只为实际可见、接近、96 格内的导弹造最多 4 块，每机器人冷却 500 刻，优先既有屋顶/墙体，不长期修复；cleanup 只移除匹配所有权状态的块，日志区分实际移除/延后。旧 full-shell/survival 测试已按新明确需求退休，保留单发实际搭建/清理、无导弹零搭建、六人真实导弹总预算和冷却/不修复验证。
- TacticalSkill 仅删生成喷溅药水行为；伤员护卫不删除。三项旧 buffCount 生效测试改为真实步兵队友对战累计零 ThrownPotion。
- 测试注意：死亡会清空背包，不能把死亡掉落当作搭建消费；10 级的既有无限库存也不能断言扣除 4 块，要用实际 block_place 数量验证。固定高度悬停场景使用真实无人机/原生控制，100 个静止机器人日志场景不能冒充 50v50 加 24 台载具战争性能。
- 4.22 最终验证待完成。
- 不覆盖 4.21 及更早 JAR/ZIP/checkpoint，不提交/推送/部署/重启正式服；历史深坑乘务、LAV-AD 地面武器及基础 TeamDive/边界失败保持独立待修。
'''
write('AGENTS.md',s)
print('current documentation updated; validation statements remain pending')
