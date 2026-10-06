# AI hardness 交接设计与当前实现差异审查

审查日期：2026-10-04。对象：本地 4.6.0-BETA 实现、用户提供的 `ai-hardness-handoff.md` 及 `extras/chatter/` 台词库。

## 结论

当前实现已经完成难度框架和一批高难战术基础，但 **10 级目前仍是参数更积极的高难机器人，没有交接文档定义的整套专属决策体系**。读人、抓窗口、假动作、多步连招、跨局针对性学习、团队同步和说话系统均需新增。

文档中最需要先落实的差异是：默认装备、10 级资源与回血例外、8～9 级感知限制、混合玩家与机器人的敌方索敌模式。直接在现有固定技能优先级后面继续堆条件，会让撤退、护卫、连招和换手互相争抢控制权。

本轮只审查并生成报告，没有修改源码、构建产物或运行中的服务器。交接文档中的“用户决定”作为它描述的目标进行比对，不扩展为本轮实现、部署、删除数据包或执行游戏聊天指令的授权。无限图腾在文档中仍是未定项。

## 1. 两份目录的实际状态

- 本轮审查的实现位于本聊天 `outputs/TerminatorPlus-NeoForge`，版本为 4.6.0-BETA，基于 `c1fcf77`，功能改动尚未提交或推送。
- `E:\codex\claude code\TerminatorPlus-NeoForge` 中有新交接文档和台词库，但没有本轮审查的这批源码改动。因此不能用那份旧源码判断最新实现是否已经修复。
- 已有验证记录显示：Minecraft 1.21.1、NeoForge 21.1.1、JDK 21，构建成功，69 项自测通过。本轮没有重复运行测试；该记录不能代替新设计的验收。

验证记录：[validation.txt](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/validation.txt)。现有用法：[AI_HARDNESS.md](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/AI_HARDNESS.md)。

## 2. 逐项差异

| 设计目标 | 当前实现 | 审查判断 |
|---|---|---|
| 1～10 分级，7 为原战斗基线 | 有全局设置、单机器人覆盖；低档降低反应、出手频率、瞄准和可用技能 | 框架已完成，强度递增尚未实测校准 |
| 8～10 撤退、补状态、追击期间空中恢复 | 有撤退、恢复、空中追踪状态；实际使用苹果、食物、治疗药水、紫颂果 | 基础已完成，安全判断仍主要围绕一个威胁 |
| 正面持续受压时脱离 | 根据近期受伤次数触发，使用已有珍珠、飞行、掩体 | 部分完成；没有判断攻击方向、击退锁定程度或多个火力来源 |
| 临时掩体、破墙或紫颂果脱离 | 有正面墙、紫颂果；墙到期清理 | 部分完成；没有主动挖出口动作，卸载区块的清理记录可能丢失 |
| 8、9、10 分别提供 1、2、3 种队伍增益 | 有真实喷溅药水；按 UUID 随机确定组合；不要求携带 | 已完成基础行为；缺实测移动/起跳变化的验收 |
| 保护伤员、包抄、协同追击 | 有伤员附近建墙、挡在伤员和敌人之间的追击点，以及三个追击偏移 | 部分完成；没有共享目标、角色、集火与同步时机 |
| 剑斧跳劈 | 8～10 使用真实武器、原版攻击、攻击冷却和下落暴击 | 已完成基础；没有盾斧切换、疾跑击退与暴击的打法选择 |
| 鞘翅重锤预判 | 8～10 预测目标速度和下落时间，修正落点 | 已完成基础；8、9、10 尚无分别校准的命中率目标 |
| 装备预设 | 可保存完整背包、护甲、副手及组件，异步生成后套用 | 已完成核心；命令为 `preset`，保存为世界 NBT |
| 每级默认装备 | 只有选定装备预设时自动套用，无预设时没有按级别配装 | 未完成 |
| 10 保留旧自动回血，物资无限、装备不坏 | 8～10 一起切换为饥饿恢复；消耗品照扣，真实装备照掉耐久 | 与新文档目标直接不同 |
| 8～9 仅依据可见、可听信息 | 索敌不要求视线或视野；目标位置与速度持续读取 | 未完成。攻击时的视线检查不能替代感知限制 |
| 同时对付不同队的真人和机器人 | 已过滤友军，但真人与机器人仍是分开的目标模式 | 缺统一的“最近敌人”模式 |
| 设置、难度档案、预设元数据持久化 | 装备和默认预设持久化；全局设置不持久化，预设不存难度/队伍/人设/能力 | 部分完成 |
| 10 专属读人、抓窗口、假动作、学习、团队同步 | 仍按固定顺序启动重锤→珍珠→弓→远行 | 未完成；现有 `/ai` 训练不等于针对玩家的跨局记忆 |
| 10 说话及队友指挥系统 | 台词素材在另一份目录；当前模组没有加载、事件触发、路由和限频 | 未接入 |

依据：[Hardness.java](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/skill/Hardness.java:9)、[BotSkills.java](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/skill/BotSkills.java:168)、[TacticalSkill.java](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/skill/TacticalSkill.java:44)、[EquipmentPresets.java](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/EquipmentPresets.java:24)。

## 3. 文档中已经过时的审查意见

1. **“速度、跳跃药水无效”已经不适用于最新高难实现。** 8～10 的 `walk/jump` 已接入迅捷、缓慢和跳跃提升。1～7 保留旧运动分支；现有药水测试只检查效果存在，没有测位移和起跳高度，因此还不能把“收到效果”当成全部验收通过。
2. **“不能完成进食”已解决基础路径。** 真实物品换进主手，按原版时长计时，再调用 `finishUsingItem`；取消、清空背包和套用预设也处理了物品返回。盾牌的原版使用进度仍未补齐。
3. **“自测还没改”已过时。** 原有 44 项加新场景，共 69 项通过。不过新设计的大部分 10 级能力尚无对应测试。
4. **“不看队伍”只剩部分问题。** 当前候选目标、反击目标、事件返回目标和普通攻击均过滤队友；缺的是同时识别真人与机器人的敌方候选集合，以及队伍统一决策。
5. **`/bot give` 共用物品已修复。** 每个机器人收到独立副本。不能进一步把所有真实武器换手都改成 `copy()`：预设武器需要保持与该机器人实际背包中物品的关系，才能正确同步耐久。应防止不同机器人共享实例，同时保留单机器人真实物品的所有权。

依据：[Bot.java：移动效果](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/Bot.java:1186)、[进食](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/Bot.java:156)、[友军过滤](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java:1446)、[give 副本](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/command/commands/BotCommand.java:196)。

## 4. 需要优先处理的实现缺口

### 默认装备与资源规则

`Bot.tick` 只在非 tactical 档恢复旧自动回血，所以 10 级目前也失去了它。烟花、珍珠、风弹直接 `shrink(1)`，没有 10 级例外。无预设时也没有默认补给，长期对局中 8～9 可能因资源耗尽而弱于 7。

应把“战术档”“资源规则”“感知规则”分开表示。否则一个 `level > 7` 会继续同时决定三件性质不同的事。按文档目标，8～9 用有限补给，10 恢复旧回血并有无限补给；图腾规则保持独立未定。

无限物资和默认装备都会改变战斗条件。后续应同时记录“相同装备下的 AI 强度”和“完整默认规则下的实战强度”，避免把资源优势全算成 AI 更聪明。每级自动配装也意味着新 7 与历史空装 7 的对战条件不同，需要以相同装备解释“基线”。

依据：[回血分支](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/Bot.java:410)、[烟花消耗](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/Bot.java:845)、[异步套用装备](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/BotManagerImpl.java:193)。

### 动作互斥与真正格挡

文档对 `smash/shootBow` 的意见仍有效：这两个公开方法直接换第 0 格，没有 `isConsuming()` 入口保护。现有协调器通常避免重叠，因此不能据此断言当前普通对战已经发生复制，但新增连招或调用这些 API 时会有风险。盾牌也只开始使用副手，没有推进格挡所需的使用进度。

建议先统一主手动作的占用、取消和恢复：进食、举盾、拉弓、切武器、坠击都明确优先级；危险逃生可打断进食，普通攻击不能偷偷覆盖真实食物。这样才适合新增多步连招。

依据：[smash](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/Bot.java:910)、[shootBow](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/Bot.java:1002)、[block](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/bot/Bot.java:579)。

### 掩体与安全恢复

当前安全判定主要是离单个威胁足够远或两者被方块遮挡，不会综合评估第二名敌人、飞行中的投射物或枪械持续火力。更高撤退血线也不自动等于更强：可能反复退出优势对局，给对手恢复时间。

当前清理只移除已加载且方块状态相同的位置，随后清空全部记录。若区块未加载，临时墙可能留下；相同状态也不能区分“机器人原墙”和“玩家后来重放的同材质墙”。没有主动破墙逃出的计划。

建议区分“当前可恢复”“正在寻求脱离”“无补给只能重新交战”，按实际威胁与资源决定结束条件。临时掩体需要可追踪的生命周期和出口检查；跨区块清理应延后处理而非丢掉记录。

依据：[安全判断](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/skill/TacticalSkill.java:102)、[掩体清理](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/skill/TacticalSkill.java:222)。

### 团队合作

包抄当前采用 `index % 3`，四名以上成员必然重复路线；成员集合没有要求正在攻击同一个目标，也没有共享角色和发起时间。伤员掩护主要影响地面追击点，进攻技能仍可取得控制权，不能据此认定护卫会持续拦住追兵。

需要按队伍、维度及当前交战分组共享目标；围绕目标预测位置分配角度，按敌情调整距离；伤员撤退时分配护卫，其他成员继续施压。同步俯冲应有准备、发起、取消三个阶段，队员状态变化时能取消。

依据：[护卫与追击点](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/skill/TacticalSkill.java:234)、[三路线分配](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/skill/TacticalSkill.java:281)。

### 预设和持久化

当前 NBT 预设已经保留组件并能重启读取，不必重做装备快照本身。文档要求的可编辑文本、难度/人设/能力等元数据，以及全局设置持久化是新增范围。可增加 `loadout` 命令别名，并保留现有 `preset` 用法。

将世界级 NBT 改为服务端 config 文件，会改变预设的作用范围。应提供旧数据读取与迁移，明确配置、世界数据和单机器人覆盖之间的优先级。异步生成仍需在服务端回调中一次完成装备、难度和队伍初始化。

## 5. PvP 机制：已核对与需修正文案

以下核对依据是本地构建使用的 **Minecraft 1.21.1 / NeoForge 21.1.1 对应源码**，位于 `build/moddev/artifacts/neoforge-21.1.1-sources.jar`，不是将其他版本的技巧数值直接套入。

| 文档表述 | 核对结果与实现要求 |
|---|---|
| 冷却 ≥ 0.848 能暴击 | `Player.attack` 使用 `getAttackStrengthScale(0.5F) > 0.9F`，还要求下落、非疾跑等条件。源码中的基础伤害倍率为 `0.2 + 0.8 × 蓄力比例²`，0.9 对应 0.848；不能把伤害倍率当成蓄力比例的阈值。现有实现 ≥0.95 较保守 |
| 重锤命中时必须卸下鞘翅 | `MaceItem.canSmashAttack` 要求 `fallDistance > 1.5` 且 `!isFallFlying()`。必要条件是结束滑翔，不是移除胸甲槽里的鞘翅 |
| 盾举起 5 tick 生效 | 对应源码确有此条件；当前机器人物品使用进度未为盾推进 |
| 斧破盾 5 秒 | 对应源码有斧的破盾能力和 100 tick 冷却；必须实际经过盾牌阻挡/攻击逻辑，不能直接替代成任意伤害或无条件禁盾 |
| `instabuild` 让物品无限、不坏 | 原版相关路径成立：`Player.hasInfiniteMaterials()` 返回该开关，耐久、食物消耗、药水和弹药路径读取它。无需设置创造模式，但自定义 `shrink` 仍需单独处理。模组物品是否遵守同一接口还需实测 |
| 图腾也自动无限 | 不成立。图腾保护路径直接 `shrink(1)`；文档也明确把该规则留待决定 |
| 同 tick 先斧后锤可直接模拟 | 应复现对应版本真实的换手、攻击、冷却和坠击条件；原版攻击会重置攻击冷却。不能据“技巧存在”就额外调用伤害或虚构武器属性 |

“落地硬直”“珍珠无敌帧”“击退减少 40%”“每秒跳 1.6 次”等，应转换成可测的具体时序和状态条件，不能作为所有战况下成立的常量。文档关于未来版本属性切换补丁的叙述本轮未核实，也不作为 1.21.1 实现依据。

W-tap、跳跃重置、风爆再起势涉及原版运动与击退，不能仅移植按键名称。机器人的自定义速度与 `deltaMovement` 有区别；新增适配应限定为原版机制原本产生的效果，并验证幅度，避免借适配增加额外机动力。

枪械、无人机和物理子世界也不能只靠“手上是不是弓”识别。读准星可先使用服务端朝向射线，但它不等于已经准确理解所有模组的瞄准、弹道和攻击窗口。

## 6. 10 级应做成相互配合的决策体系

建议让这组能力形成完整链条，而不是给每个触发条件单独加一个固定动作：

1. **感知与读人：** 8～9 只用可见信息、声音事件和有时效的最后已知位置；10 可使用加载范围内的额外信息。把准星、盾、冷却、补给、投射物等整理为当前战况。
2. **抓窗口并选择打法：** 敌人正在吃、盾被破、起飞、落地或扔珍珠时，根据距离、地形、自己的装备和队友位置选动作。读到对方没图腾也不能无条件忽略自身逃生。
3. **短计划与假动作：** 从数个有效选项中按价值加权选择，允许假撤退、假俯冲。每步重新检查范围、视线、资源和风险；紧急撤退可中断连招。
4. **地形与团队：** 选择攻击方向、切入路线和队伍角色；由共享战况决定谁压制、谁侧翼、谁护卫，何时一起进场。
5. **学习：** 按玩家和招式记录有意义的尝试与结果，保留样本量、时间衰减和装备/场景上下文；不要把一次失败就判成永久弱点。现有训练系统不能直接充当这份记忆。
6. **说话：** 从真实战况、已经选中的计划和已采集统计触发台词，让语言对应行动。真假预告可以共用，真实统计不能编造。

这样才能体现文档描述的整套 10 级能力，同时保留“不增加额外代码级机动力”的边界。帧级反应可以精确，但未来走位、网络时序和模组交互存在不确定性，不能将“机器人每次都能做对”作为验收承诺。

## 7. 台词库审查

本轮实际解析两份文本，检查了类别头、参数范围、重复类别、人设标记、花括号和已声明槽位：

| 文件 | 类别 | 台词 | 带 `!` 的台词 | 格式问题 |
|---|---:|---:|---:|---|
| enemy.zh_cn.txt | 44 | 431 | 41 | 未发现 |
| ally.zh_cn.txt | 30 | 206 | 0 | 未发现 |
| 合计 | 74 | 637 | 41 | — |

这是素材格式验收，不代表事件触发或文案中的战斗判断已经得到代码支持。

接入时应先实现目标/队伍路由、每机器人与每听众限频、mild 过滤和重载，再接入已经有可靠事件的类别。`habit/prediction` 依赖新读人系统；命中率需要明确“尝试次数”的来源，尤其是真人和枪械模组的攻击。无法获得的数据应跳过句子。

聊天限频需先确定实际接收者再计算，多人同时接收的 `team_callout` 也要逐人限流。洗牌袋应在筛选人设、强度和可用数据之后选句，避免因大量不可用槽位反复失败。

游戏内“跟我”“集火”等属于未来模组的受限战术指令。它与 AI 助手读取服务器聊天并执行外部操作是两件事；本轮没有执行任何游戏聊天。若实现战术指令，应限定同队及明确的指挥权限，只允许列出的游戏行为，不能转成任意控制台或系统命令。

## 8. 建议顺序与验收

### 实现顺序

1. **补齐规则与资源：** 可配置难度档案、默认装备、10 级回血/资源规则、设置持久化和旧预设迁移。保留无限图腾未定项。
2. **统一动作与机制：** 主手互斥、真正举盾、武器选择与切换；补上掩体出口及延后清理。
3. **感知与团队基础：** 混合敌方索敌、8～9 感知边界、10 战况读取、团队共享目标和角色。
4. **先做最有体感的 10 级能力：** 读人、抓窗口、加权随机与假动作，再把可靠事件接到台词库。
5. **再做多步规划与学习：** 连招取消/重规划、同步进场、跨局记忆，并用对战数据调整参数。

### 验收不能只看 69 项通过

- **回归：** 保留原有检查，并在相同装备、目标与随机种子下对照 7 级。当前全局友军过滤和物品隔离也影响低档，不能把“主战斗分支保留”表述成所有行为逐字不变。
- **物品：** 进食与换手交叉时不丢失、不复制；8～9 有限消耗；10 对规定物资不消耗、装备不损坏；难度切换后规则恢复正确。
- **感知：** 8～9 看不到的人不能持续获得新位置或背包信息；声音和最后已知位置可支持搜索；10 在约定信息范围内触发正确战术。
- **药水与盾：** 测实际位移、起跳高度和伤害阻挡，不能只检查效果图标或举盾动画。
- **重锤：** 现有移动靶测试是在 1600 tick 内出现一次大于 15 的命中，不是命中率测试。需要记录发起、有效坠击、命中和空砸，在直线、绕圈、突变方向等场景按等级报告结果。
- **团队：** 真人与机器人混队不互打；四名以上成员路线不重复挤在一起；伤员存活/脱离率和同步攻击实际发生，而不只是某个状态枚举被设定。
- **天梯：** N 对 N−1 多局、多种地图、交换出生点和种子；分别测等装备及默认规则，报告局数、胜率与不确定范围。一次胜利或单一平地靶场不能证明难度递增。
- **10 与说话：** 每个读人/窗口事件有正例与不应触发的反例；假动作可取消；敌友台词路由、听众限流及未知槽位跳过可验证；学习在重启后保留且可清除。

当前测试局限依据：[SelfTest.java：药水、护卫、暴击和移动靶](C:/Users/13703/Documents/Codex/2026-10-04/http-200-https-github-com-sulfurchi/outputs/TerminatorPlus-NeoForge/src/main/java/net/nuggetmc/tplus/utils/SelfTest.java:857)。
