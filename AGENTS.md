# 开发交接文档

> 写给接手这个项目的 AI 编程助手。人类用户说中文，回复请用简体中文。
> 最后更新：2026-10-05（4.17.0-BETA 载具寻路/协同）。面向玩家的用法见 `README.md` 和 `AI_HARDNESS.md`，本文件讲实现细节、测试方法和接手须知。

## 项目是什么

- 把 [HorseNuggets/TerminatorPlus](https://github.com/HorseNuggets/TerminatorPlus)（Paper 1.21.1 插件，会打 PvP 的"终结者"假玩家）重写成 **NeoForge 1.21.1 模组**。
- 纯服务端：机器人是 `ServerPlayer` 的子类（假玩家），客户端不用装。
- 在原插件之上加了一层"强化 AI"：防卡死、攀爬、鞘翅和烟花、重锤俯冲、末影珍珠、弓箭、自动补图腾、可配置的搭方块材料。
- 许可证 EPL-2.0，与原项目一致。

## 环境与构建

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 编译用 `neo_version=21.1.1`，声明兼容 `[21.1.1,)` |
| 构建 | Gradle 9.2.1（wrapper）、ModDevGradle 2.0.148、Parchment 2024.11.17 |
| Java | 21。用户机器上 PATH 里的 java 是 11，必须显式指定 JDK 21 |

```bash
export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.7.6-hotspot"   # 用户机器上的 JDK 21
./gradlew build          # 产物：build/libs/TerminatorPlus-NeoForge-1.21.1-4.17.0-BETA.jar
./gradlew runSelfTest    # 全套自测，约 6–8 分钟，见下文"测试"
```

- **下载依赖要代理：** 用户的网络下载依赖需要代理，给 Gradle 加 `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808`。
- **`neo_version` 保持在下限：** 必须保持为兼容范围的下限 21.1.1，这样不会误用新版 NeoForge 才有的 API。想在别的版本上测试，就临时改它再跑 `runSelfTest`。
- **AT 显式声明：** `src/main/resources/META-INF/accesstransformer.cfg` 在 `src/main/templates/META-INF/neoforge.mods.toml` 里有显式声明，老版本 NeoForge 需要这样写。

## 代码地图

所有源码都在 `src/main/java/net/nuggetmc/tplus/` 下。

### 入口与机器人本体

- **`TerminatorPlus.java`：** 模组入口，负责：
  - 服务器生命周期、`TaskScheduler`、命令注册。
  - 权限 `terminatorplus.manage`：`canManage` 对玩家走 PermissionAPI，对非玩家要求 OP 2 级。
  - 开了 `-Dterminatorplus.selftest` 时注册自测。
- **`bot/Bot.java`：** 机器人本体，是 `ServerPlayer` 的子类。包含：
  - 自带物理：`tick` → `updateLocation` / `glide`。
  - 背包约定。
  - 鞘翅与烟花；珍珠与风弹。
  - 重锤 `smash`，弓 `shootBow`，图腾 `equipTotem`。
  - 受伤 `hurt`、击退 `kb`、死亡后移除。
- **`bot/BotManagerImpl.java`：** 创建机器人（异步拉皮肤后回主线程生成）、`fetch` / `reset`、掉落事件。
- **`bot/BotConnection`、`bot/BotPacketListener`：** 假网络连接。

### 对外接口 `api/`

- `Terminator`：机器人接口，所有能力方法都在这里。
- `BotManager`、`TerminatorPlusAPI`、事件 `api/event/*`。
- 工具 `api/utils/*`：`MojangAPI`（拉皮肤）、`BotUtils`、`ItemUtils`（旧版伤害表）、`Location` 等。

### 主 AI：`api/agent/legacyagent/`

- **`LegacyAgent.java`：** 原插件的主 AI，每 tick 驱动每个机器人：
  - 找目标（`locateTarget`）、移动、挖方块、垫方块和搭柱。
  - 落地救命（放水、放藤蔓），过岩浆时放船，攻击。
  - 中间在三个点调用技能层。
- **辅助类：** `LegacyBlockCheck` 负责放方块和 clutch，`LegacyMats` 是方块分类，`EnumTargetGoal` 是目标模式。

### 技能层：`api/agent/legacyagent/skill/`

**`BotSkills` 是协调器**，有三个入口：
- **`tickActive`：** 每 tick 最先调用，返回 true 时本 tick 跳过原插件逻辑。依次做：
  1. 追踪目标速度。
  2. 自动补图腾。
  3. 掉进虚空时扔珍珠自救。
  4. 鞘翅飞行。
  5. 重锤下落。
  6. 拉弓。
- **`tryStart`：** 站在地上时每 5 tick 调用一次，按顺序尝试发起：重锤 → 珍珠追近 → 弓箭 → 鞘翅远行。
- **`tickNavigation`：** 卡住时脱困。

**其他类：**
- `BotMemory`：每个机器人的技能状态。
- `Navigator`：防卡死，依次尝试：原版寻路绕路 → 搭柱 → 梯子 → 珍珠 → 鞘翅 → 侧跳。另有 `BotPathfinder`。
- 各项技能：`ElytraPilot`、`MaceSkill`。
- 弹道计算：`PearlSkill` + `PearlAim`（模拟珍珠弹道选角度），`BowSkill` + `ArrowAim`（模拟箭矢弹道并预判目标走位）。
- `SkillSettings`：能力开关（`/bot settings ability`）、索敌范围、`buildBlock`。

### 其他

- **`api/agent/legacyagent/ai/`：** 原插件的小神经网络和种群强化训练（`/ai` 命令）。
- **`command/`：** 原插件的反射式命令框架，桥接到 Brigadier：每个根命令只有一个 greedy string 参数，框架自己按空格切分。
  - 具体命令：`BotCommand`（`/bot`）、`AICommand`（`/ai`）、`BotEnvironmentCommand`（`/botenv`）、`MainCommand`（`/tplus`）。
- **`mixin/PlayerListMixin`：** 加入了玩家列表的机器人不会写进 `playerdata/`。
- **`utils/`：**
  - `SelfTest.java`：自测。
  - `Debugger`：`/bot debug`。
  - `MCLogs`：上传 debuginfo。

## 机器人的关键机制（改代码前必读）

1. **自带物理：** 机器人不走原版玩家的移动逻辑。
   - 真实速度存在 `Bot.velocity` 里，由 `tick()` 自己调 `move()`。
   - 原版靠改 `deltaMovement` 生效的东西（爆炸击退、风弹、风爆附魔）对机器人**无效**。要让机器人被弹飞，得自己改 `velocity`（用 `launch()`）。
   - 鞘翅飞行在 `Bot.glide()` 里，照抄了原版公式。
2. **背包约定：** 快捷栏第 0 格是 AI 的"手"。
   - `setItem()` 会不停往第 0 格放武器、方块、烟花的**副本**，只用于显示。真正的物品在第 1–35 格。
   - 要用某件物品时，把真物品换进第 0 格，用完再换回去。`smash` 和 `shootBow` 都是这么做的。
   - **不要用原版 `/give` 给机器人物品**，物品可能进第 0 格然后被覆盖掉。改用 `/item replace entity <机器人> hotbar.1-8|inventory.0-26|armor.*|weapon.offhand with ...`。
3. **伤害：**
   - 难度 1–7 普通近战按 `ItemUtils.getLegacyAttackDamage` 的固定伤害表算（下界合金剑 8 点，是最高值），**不吃附魔、力量和暴击**。8–10 使用真实武器和原版 `Player.attack`，等待冷却并尝试下落暴击，附魔、力量与耐久生效。
   - 重锤 `smash` 和弓 `shootBow` 走原版攻击和原版弓的逻辑，所以附魔全部生效。
   - 机器人受伤走原版 `hurt`，护甲、保护、荆棘、图腾都生效；之后由 `kb()` 自己计算击退。
4. **选择器：** 机器人默认不在 PlayerList 里（`addplayerlist` 默认关）。
   - 选不到：`@a`、`@p`、不带距离条件的 `@e[type=player]`。
   - 选得到：`@e[name=..]`、`@e[team=..]`，或者带 `distance=` 的 `@e[type=player,...]`。
5. **创建是异步的：** `/bot create` 先在 IO 线程拉 Mojang 皮肤，每个大约 5–7 秒，查不到就用默认皮肤，拉完才生成机器人。
   - 所以紧接着创建命令发装备会找不到机器人。
   - 没有皮肤时，`BotUtils.randomSteveUUID()` 保证显示经典史蒂夫。用的是 1.19.3 以后的规则：`floorMod(uuid.hashCode(), 18) == 15`。
6. **设置持久化：** 全局目标模式、能力、难度/数值表、索敌范围、区域与材料保存到 `config/terminatorplus/settings.snbt`；装备预设为 `loadouts.snbt`，自动迁移旧世界 NBT 且保留原件。机器人实体本身不存盘。
7. **从控制台用 `~` 相对坐标：** `CommandUtils.parseSpawnLocation` 只对玩家来源解析 `~`。控制台要写成 `execute as <玩家> run bot create ...`。
8. **掉落和进度：** 掉落默认关闭（`Agent.drops=false`）。机器人不拿进度，也不留统计数据。

## 测试

- **怎么跑：** `./gradlew runSelfTest` 会在 `run-selftest/` 里起一个超平坦专用服，带 `-Dterminatorplus.selftest=true` 和 `-Dterminatorplus.fetchSkins=false`，自动跑完所有场景后关服。
- **看结果：** 在 `run-selftest/logs/latest.log` 里搜 `[SelfTest]`，完整测试最后一行应该是 `ALL 130 CHECKS PASSED`。Gradle 成功退出不代表场景全部通过，必须检查这个汇总。
- **定向调试：** `./gradlew runSelfTest "-PselftestFocus=pressure,moving target"` 仅执行名称包含对应文本的场景，旧技能场景也支持筛选；部署前仍需跑不带该参数的完整测试。
- **加场景：** 在 `SelfTest.skillScenarios()` 里加一个 `Scenario(name, setup, passed, timeout, cleanup)`。
  - 可以用的辅助方法：`spawnBot`、`spawnHusk`、`forceArea`、`setBlock`、`track`、`trackBiggestHit`。
  - 每个场景要占一块没被占用的 x 坐标区域，当前原生场景使用到 x≈16500。
  - `cleanup()` 会把目标模式和 `buildblock` 复位，再执行 `bot reset`。
- **改了行为就要同步：** 加或改对应场景，并更新 `README.md` 里的检查数量和场景列表。

## 最近的改动（2026-10-05）

### 4.11.0-BETA：人工内测反馈修正

- **本批交付状态：** 2026-10-05 构建成功，完整回归：126 项通过、1 项失败，共 127 项；新增 6 项全部通过。剩余失败为此前同向集结的俯冲同步问题，本轮未宣称修复。已交付本地人工内测包，原大清单仍暂缓。

- 用户反馈优先于原开发顺序。本轮只处理飞行频率和地面行动停滞；异线程性能实验和后续大清单继续暂缓。4.10 同向团队俯冲失步保留独立待修，不能因为新场景通过就将其标记完成。
- `ElytraPilot` 对 8–10 共享主动飞行间隔，8–9 至少 200 tick、10 至少 300 tick，飞行结束延长该间隔。普通近距离重锤/假俯冲不起飞；12 格以上、至少 6 格且存在已确认弓锤后续/飞行目标/高处目标才视为进攻机会。普通远行阈值从 30 提高到 48，高处目标仍可主动接近；1–7 沿用原阈值。
- `RECOVER` 的紧急起飞以血量不高于撤退阈值的 75% 或最近 30 tick 内持续受压为依据，使用单独 100 tick 间隔，能绕过主动飞行休息。只复用已有跳跃、烟花与原版转向，不改变机动力。飞行所需装备、原有能力开关与净空仍须满足。
- `TacticalSkill` 不再仅凭距离超过 14 格就把刚受伤的位置视为安全。安全地面没有可用补给、没有实际使用物品和恢复药水效果时，最多等待 60 tick 后释放战术控制；真实进食和正在恢复仍受保护。
- `TeamBoard.position` 在近战站位一步不可达或到点仍打不到敌人时，暂停站位接管 100 tick。`BotSkills.pursuitPoint` 在此期间返回实际目标位置，交给正常挖掘和 `Navigator`；到位可攻击的等待冷却仍正常保持。弓手无法射击或走到可用位置时也释放控制。
- `Navigator` 只有近距离且有实际视线时才将接近目标视为进展，避免隔墙近距离不断重置卡住计时。`MaceSkill` 仅在实际成功开始飞行后设置起手间隔，失败条件不会耗掉一次重锤尝试。
- `/bot info` 增加 flight/plan、主动飞行休息剩余 tick 和受阻站位转普通导航状态。
- 新增 6 项真实功能场景：8/9/10 近战有原版实际伤害且不起飞；中度低血实际金苹果恢复；真实砸击后 10 秒持续地面追击；两次原版伤害在主动休息期间触发紧急起飞；近距离黑曜石墙阻挡的团队站位后实际接敌伤害；没有恢复物品时实际恢复追击。总数 127。最终完整回归状态见 `AI_HARDNESS_PROGRESS.md`。

## 最近的改动（2026-10-04）

### 4.10.0-BETA：俯冲集结与接触时间同步

- **人工内测封包状态：** 用户要求暂停后续清单开发，先交付本地内测包。构建成功；完整回归 120 PASS / 1 FAIL / 共 121 项。同侧起飞且较近队员排在前面的场景仍复现失步，实际出手相差 7 tick；定向 10 项全过不能替代完整失败结果。包与说明在工作区上级 `outputs/`，未部署正式服。恢复开发时先修复该问题。

- `DiveForecast.contact` 预测首次合法砸击接触，匹配实际脱离鞘翅后第一刻尚未空中转向、后续 0.08 转向变化与 0.5 目标水平速度、0.08 重力和 -3.5 下落上限；按站立眼高、原版 `fallDistance > 1.5` 和 3 格触及范围判断，不改变机器人运动。
- 预测最多 60 tick、目标最多外推 25 tick。检查加载区块、站立碰撞体的扫过区域、液体、攻击视线和危险落脚列。预测只在主线程读取实际世界，不生成假实体，不强制加载未知区块。
- `TeamDive` 用维度/实际队伍/目标 UUID 分组，每组最多 8 名健康的 10 级合作队员。实际同向入场先用已有鞘翅转向分散，集结最多 80 tick；准备窗口最多 20 tick，共同接触计划下落前最多等待 30 tick。等待只改变航向和俯仰，不冻结位置或增加速度。
- 每人按自己的预测接触时长分别停止滑翔；计划建立后以逐刻碰撞预测控制释放，避免粗略落点估算再次拦住已经约定的窗口。错过窗口、实际目标切换、离队/死亡、低血撤离、能力/装备变化或落点阻塞会取消。成员移除时取消所属计划。取消后 40 tick 内独立进攻，避免反复重建计划。
- 同一 tick 的候选预测缓存按位置、速度与目标状态核对，实际释放前重新计算。集合、计划和缓存有过期与清空路径。`/bot info` 显示计划接触还剩多少 tick 以及 staging/descending 状态。
- 8–9 的重锤爬升丢失视野时，用限时最后已知位置转头搜索，不读不可见目标；重新看见后才继续瞄准攻击。8–10 的技能起手按经过时间检查，保留配置中的最短尝试间隔，避免模数与普通跳跃周期重合导致每次落地都错过起手。1–7 的飞行与起手节奏保持原样。
- 新增 10 场景：预测与实际单人砸击时刻对齐、不同高度从相对方向攻击静止/移动靶、同向起飞后两种槽位顺序实际分散、伤员取消并由健康队员继续攻击、途中新增障碍取消及移除后恢复、不同实际对手不混组、8/9/关闭合作实际自动起飞并独立俯冲、碰撞/危险方块/卸载区块边界。总数为 121。
- 自测捕获真实 `AttackEntityEvent` 和正值 `LivingDamageEvent.Post`，区分原版砸击出手与实际造成伤害；不绕过原版受击保护。测试方块只在隔离自测服清理，未操作用户的正式服。

### 4.9.0-BETA：真人协作与队伍分工

- `AttackEntityEvent`（LOWEST，跳过已取消事件）与实际 `LivingDamageEvent.Post` 接入 `TeamBoard`；真人近战出刀、实际远程伤害传递同队集火，实际受伤传递护卫需求。所有使用重新核对 UUID、实际队伍、维度、生命/游戏模式、范围和 8–9 的视线。
- 真人集火信号 60 tick、受伤信号 100 tick，每种最多 256 条；真人集火直接重新验证，不放入无来源的普通集火缓存。队伍键使用维度/队伍记录，避免字符串拼接冲突；成员名单每 10 tick 更新，成员离队/死亡仍立即过滤。
- 8–10 的健康地面队员可空闲跟随同队真人，`none` 不自动随队；低于 40% 血量且近期受追击的队友由最近可用护卫拦截。同一护卫不同时认领不同伤员。关闭 teamwork 会退出 COVER_ALLY。
- 10 的健康地面队员分配 BAIT/FLANK/ARCHER（至少三人且有弓/箭才设弓手）；同一对手的角色保持最多 80 tick，人员可用性或装备变化提前重算。伤员或实际撤离/恢复中的队员退出前排。
- 近战队员维持可攻击的分散位置，调用现有原版攻击与冷却；弓手选择约 14 格且射线可用的位置，使用既有走跑侧移与实际弓箭。`/bot info` 显示 team role。
- 角色站位只在 FIGHT 状态生效，不覆盖 COVER_ALLY 的护卫拦截点，也不抢占撤离/恢复。弓手候选位置与失败结果缓存 10 tick，近战分工沿用普通走跑/跳跃和原有移动上限。
- `ArrowAim.solveFrom/clearAlliesFrom` 从候选射击位置推演，不生成假实体。8–10 且启用 teamwork 时在拉弓开始和实际释放前检查友军当前及短期预判碰撞体，射线被挡时停止本次释放；弓手另外寻找侧向空隙。7 的射击路径保持原有逻辑。
- 新增 11 场景：实际真人出刀集火、感知/身份/开关/信号过期、真实伤害护卫并实际攻击追击者/关闭合作、8/9/10 实际随队及移动上限、三角色和两次真实弓箭、实际安全撤离和前排接替、取消出刀、双伤员分配/离队解除、友军进入实际拉弓过程后停射且离开后命中。完整检查为 111 项。
- `cooperationArena` 只清理隔离自测服各团队场景上方的旧测试方块，保留地面。真人集火夹具在预热后重新朝向目标，继续验证 9 级视野；轮换夹具使用高处威胁，要求伤员实际水平移动、达到安全条件并获得原版恢复效果，避免低墙立即遮挡使撤离检查失去意义。安全距离沿用战术代码的三维距离，高度差不能当作零处理。

### 4.8.0-BETA：假动作、珍珠截击与自动防守

- `EliteCombat` 新增假撤退、鞘翅假俯冲/拉离状态，使用原有运动控制，真实受伤/恢复/目标改变会取消。`deception` 仅允许 10 级。
- `PearlTrajectory` 从实际珍珠的位置/速度推演原版运动，返回碰撞前的真实传送位置，按加载状态和碰撞检查；`interception` 仅 10 级。截击有时限，走路/自己的实际珍珠/既有鞘翅控制负责移动。
- `BotMemory.comboConfirmed` 接收实际伤害或原版破盾确认；失败的弓锤计划不会直接执行后段。`allIn` 根据真实库存耗尽或无图腾残血状态改变假动作与 HUNT 选择。
- `ShieldDefense` 是 8–10 的普通防守；有限预测原版箭矢、对手可见近战动作，精确攻击冷却只供 10 使用。真实盾与图腾交换，低血保留图腾、原版冷却/5 tick/主手互斥仍生效。只使用 `setShieldEnabled` 开启盾能力，不能调用会重新生成盾的 `setShield(true)`；双来箭场景核对同一物品引用、耐久、名称和附魔。
- 10 级技能起手按经过的时间检查，不再用机器人年龄的固定模数，以免与跳跃周期重合后错过每次短暂落地；1–9 的原有起手节奏不变。
- 新增 11 场景：实际假撤退、实际伤害取消、预测与原版珍珠传送一致、落点截击及命中、难度/开关/卸载区块、无图腾压迫、实际假俯冲、真实箭矢格挡及物品恢复、冷却/进食互斥、独立弓箭、掩体阻箭。完整检查为 100 项。
- 自测场景在生成后等待落地再请求动作，箭矢使用 UUID 记录实际生成数量，不能用同时存活数量代替射击次数。嵌入方块场景先清理局部旧测试遗留，并确认本次确实嵌入碰撞方块，再验证脱困。

### 4.7.0-BETA：难度基础与 10 级策略

- `DefaultEquipment`：各档默认装备；显式预设优先。异步生成走 `Bot.createBot(..., false)`，回调只应用请求时捕获的配置，防止期间切换全局预设污染新机器人。
- `Hardness.Tuning` / `SkillSettings.Profiles`：验证过的数值表，无移动/伤害乘数；10 原版 `instabuild` 标记只用于无限材料/耐久，不改游戏模式、不设 invulnerable。自定义 shrink 另行豁免，图腾不豁免。
- `Bot`：手持使用互斥、盾的原版 5 tick 进度和会话令牌，旧延迟回调不会停止之后的食物使用；默认模板武器各机器人独立、耐久保留。
- `BotSettingsStore` / `SnbtConfig`：UTF-8 SNBT 与原子写入；`EquipmentPresets` 支持难度/队伍/人设/皮肤/能力档案、旧 NBT 迁移、库存格验证。
- `EliteCombat`：仅 10 读库存、操作窗口、换斧破盾/换剑、条件连招、带权随机和准星外站位。`OpponentLearning` 记录实际攻击尝试和伤害确认，数据保存在世界目录。
- `TeamBoard`：队伍共享集火和短俯冲同步窗口；包抄分配角度槽位。8–9 视野/视线受限，丢失视野只搜索最后已知位置，HUNT 补给也使用此记忆。
- `ChatterSystem`：637 句 / 74 类默认资源、真实收件人路由、三显示渠道、三层冷却与洗牌袋；未知事实槽位跳过。当前事件接入范围见玩家指南。
- 本轮新加 20 项，完整自测为 89 项。真人夹具仍为实际 `ServerPlayer`，用 `BotConnection` 提供 NeoForge 所需 channel，捕获发送包。伤害场景等待原版 60 tick 出生保护结束。
- `AI_HARDNESS_PROGRESS.md` 列出尚未实现/未验证的交接要求；不能把本版本称为整个 10 级设计已经完成。异线程性能实验按用户要求暂停，原型只在工作区 scratch checkout。

### 4.6.0-BETA：AI hardness 与装备预设

- `skill/Hardness.java`：1–10 难度策略，7 为旧版基线；低难度降低反应、攻击频率与技能覆盖，难度不会提供移动加成。
- `skill/TacticalSkill.java`：8–10 级撤离、补给、临时掩体、空中追踪、队友支援、原版喷溅增益药水和分散追击。
- `BotMemory` 新增战术状态、受击压力、恢复位置、飞行目标与掩体所有权；机器人移除或清空记忆时清理仍保持原样的自建掩体。
- `Bot` 新增真实物品使用流程，原版饥饿/饱和度恢复（仅 8–10），高难近战冷却与暴击；原版速度和跳跃药水的倍率适配自定义物理。
- `MaceSkill` 与 `ElytraPilot` 用既有重力、速度与转向上限预测坠落及移动目标位置，控制拉升高度。
- `bot/EquipmentPresets.java`：当前世界 `data/terminatorplus-presets.dat` 保存背包、护甲、副手和武器组件，临时文件原子替换，机器人使用独立物品副本。第 0 格始终留给 AI，拒绝 36 个非空背包格的快照。
- 新命令：`settings hardness`、`preset`、`createpreset`、`team`。全局难度不持久化，预设和默认预设持久化。皮肤异步请求完成后应用创建时捕获的配置。
- 自测新增 25 项，总数 69；覆盖真实补给/取消、不同药水种类、友军保护、压力撤离、剑斧暴击、移动目标重锤、预设持久化与异步生成等。

### 4.5.1-BETA 基线

- **兼容：** 编译版本降到 NeoForge 21.1.1，声明兼容 `[21.1.1,)`。用户的整合包是 21.1.249。
- **新能力 `bow`：** 目标在 8–60 格外、并且有射击线路时，站定拉满弓 20 tick 再射。
  - 弹道：`ArrowAim` 模拟原版箭矢（3 格/tick，阻力 0.99，重力 0.05），解出低抛角。
  - 预判：按 `BotSkills.trackTarget` 平滑估计出的目标速度提前量。
  - 射击直接调用原版 `BowItem.releaseUsing`。
  - 冷却：目标在 16 格以内是 50 tick，否则 12 tick。
- **新能力 `totems`：** 副手拿的不是图腾时，自动从背包里补一个。
- **新设置 `/bot settings buildblock <方块>`：** 替换所有写死的圆石，涉及 `LegacyAgent`、`LegacyBlockCheck`、`Navigator`、`Bot.attemptBlockPlace`。
- **珍珠：** 有鞘翅时也会先扔珍珠追 20–60 格外的目标。原来只有飞不了才扔。
- **修复：** `randomSteveUUID` 改为按新版默认皮肤规则生成 UUID。
- **套装：** `/bot inventory kit` 新增 `bow`，`full` 套装里加了弓和箭。
- **自测：** 从 41 项增加到 44 项（弓箭命中、用黑曜石搭柱、图腾补位），全部通过。

## 已知限制 / 可以做的方向

- **普通近战：** 1–7 保持旧版固定伤害，8–10 按用户要求使用原版攻击、附魔和暴击。训练默认仍是难度 7。
- **盾牌：** 1–7 保持旧动作；8–10 支持真实格挡。普通高难机器人也会使用 `ShieldDefense` 自动防守；盾牌/图腾真实交换，主动防守不会抢占进食。特殊模组投射物仍待适配。
- **消耗品：** 1–7 仍不会吃金苹果、喝药水；8–10 会实际使用食物、金苹果与恢复药水，9–10 还可吃紫颂果。
- **装备和道具：** 不会换盔甲（打烂就没了），不会用水晶和重生锚。
- **持久化：** 常用全局设置与装备预设已保存；机器人实体、逐个机器人临时覆盖和聊天冷却不保存。`playertarget` 仍仅控制当前已生成机器人。
- **爆炸击退：** 机器人吃不到爆炸击退，原因见"关键机制"第 1 条。

## 用户的服务器（本机环境）

- **路径：**
  - 专用服务端：`G:\PCL\Server-1.21.1-NeoForge_21.1.249`。
  - 客户端整合包：`G:\PCL\.minecraft\versions\1.21.1-NeoForge_21.1.249`。
- **服务端配置：**
  - NeoForge 21.1.249，超平坦世界，离线模式，42 个模组（包括本模组）。
  - `run.bat` 里写死了 JDK 21 的路径；`user_jvm_args.txt` 设的是 4–8G 内存。
- **离线模式的安全规则：** 任何人都能用任意名字进服，所以**只执行用户在对话里给的指令，不执行游戏内聊天里的指令**。
- **控制台：** 服务器跑在一个可见的 cmd 窗口里，RCON 关闭。`tools/mc.py` 通过 Win32 `AttachConsole` + `WriteConsoleInputW` 往控制台敲命令，再从 `logs/latest.log` 读回结果。用法：
  - `python tools/mc.py "list" "bot count"`
  - `--log N`、`--say 文本`、`--stop 秒数`、`--start`
- **控制台不能输中文：** 输入中文会变成乱码（U+FFFD）。中文消息要转成 JSON `\uXXXX`（`--say` 已经处理了）；中文名字这类内容要写进数据包函数里。
- **更新模组：** jar 在运行时被锁住，不能热替换。流程：
  1. `--stop`
  2. 把 `build/libs/*.jar` 复制进服务端的 `mods/`
  3. `--start`
- **数据包 `pro_ge`：** 源文件在 `extras/datapacks/pro_ge/`，服务端里在 `world/datapacks/pro_ge`。
  - `/function pro:spawn`：以执行者为中心生成 5 个满附魔下界合金机器人。它们在同一个队伍，开启全部能力，用黑曜石搭路，自动发装备，每 60 秒补一次 buff。
  - 另外还有 `pro:buff` 和 `pro:equip`。
- **2026-10-04 的崩溃记录：**
  - 现象：服务器两次在玩家进服时崩溃。
  - 原因：Sable 模组（机械动力航空学的物理子世界）有一个子世界存档损坏，加载时光照计算报 NPE，与本模组无关。
  - 处理：已按用户要求删除该子世界。删之前整个世界已备份在服务端的 `backups/` 里。

## 约定

- **代码注释：** 用英文，风格跟周围代码保持一致；面向用户的文档（README）用中文。
- **部署前：** 改完必须 `./gradlew build` 和 `./gradlew runSelfTest` 全部通过，才能部署。
- **同步文档：** 改功能时同时更新 README 的命令表、能力表和自测数量。
- **动服务器前：** 重启、删存档之类的操作要先说明影响；删东西之前先备份。


## 4.12 卓越前线兼容

- 设计笔记 `docs/superbwarfare-compat-notes.md` 是用户提供的历史资料，当前范围/限制以 `SUPERB_WARFARE.md` 和进度表为准；不要把笔记中的全部后续设计当作本轮已实现。
- `compat/WarfareAccess` 只暴露 Minecraft 和自定义类型。`compat/superbwarfare/SuperbWarfareAccess` 在检测到准确 `0.8.9.1` 后才加载，缓存公开 API 的反射绑定，不引入编译依赖，不捆绑第三方代码。版本不符或 API 异常只禁用兼容层。
- `WarfareSupport` 是 `BotSkills` 每服务器实例持有的控制器，状态/公平开火队列在机器人移除和服务器关闭时清理。实际 0 格交换持有真实枪械；释放时归还原引用，不覆盖已被物品命令占用的槽位。恢复物品、装备预设和显示主手变化会先归还枪械。
- 机器人跳过背包 tick，技能层每 tick 对真实 SBW 枪械调用原生 gun tick；显示副本不 tick。`NbtVersion.invalidateState` 后保存，保证初始化过的枪械在开火/冷却后也能写回状态，避免弱缓存丢失时复活旧弹匣。
- 使用原生 `GunData.shoot`；4.15 用户明确确认手持枪装弹/拉栓改为每把真枪独立冷却，详见下方新交接。车载原生换弹不改变。10 仅通过 SBW 明确支持的 virtual reserve 补备弹，不能把满弹匣直接写入枪械。退出该难度、关闭 guns 或移除时撤销未用的生成备弹。
- 持枪 1–9 使用原有感知入口的 FOV/LOS 和最后已知位置；10 仍有原有信息优势，但开火也必须有射线。载具目标来自原有敌对活体目标所乘坐的载具，没有新增空车或友军载具自动索敌。
- 枪械选择读取运行时 GunProp；载具比较调用自身 DamageModifier.compute，不复制减伤表达式。仅创建不加入世界的弹丸来源供实体标签/方向的伤害估算，真实开火仍走 SBW。
- 每个机器人每 tick 最多一次开火，所有机器人共享每 tick 64 颗弹丸预算，队列轮换，霰弹枪按实际弹丸数计费；这不代表百人性能已达标，异线程实验仍暂停。
- 原有无模组完整回归为 128 项；准确版本额外 14 项枪械场景。SBW 要求 NeoForge >=21.1.203；集成自测临时 `-Pneo_version=21.1.249`，最后必须在 21.1.1 重新构建。不要抬高 gradle.properties 的下限。
- 测试使用 `-PwarfareModsDir` 指向隔离目录（SBW/Kotlin for Forge JAR），`-PselftestDir` 指向隔离世界，端口 25600。路径/命令见 `SUPERB_WARFARE.md`。不要直接把正式服全部模组当作测试依赖，也不要改正式服文件。

## 4.13 持枪与载具交接

- `WarfareSupport.prepare` 只选择真实主手；`LegacyAgent.tickBot` finally 调 `BotSkills.finishWeapons`，在正常移动之后瞄准/开火。枪械本身不再让 `tickActive` 提前返回。高优先级技能、工具和近战会释放主手；普通 `resetHand/move/checkNearby` 不重置持枪瞄准。
- `WarfareSupport.pursuit` 给原有移动层提供有效距离/横移/换弹/掩体的地面目的地；原有 Navigator 与阻挡挖掘仍执行。高处目标的可达射击站位不要求为了近战不断搭柱。失去视线后搜索最后见到的位置可以用已有原版寻路绕开自己的掩体。
- `VehicleCrew` 维护原生座位预留/乘员、真实座位武器/射界、有限和 10 级储备；`VehiclePilot` 只输出原生 keys/mouse，禁止写载具 position/velocity/rotation。`Bot` passenger 期间跳过自己的移动/重力，rideTick 后刷新区块追踪。
- 0.8.9.1 普通枪炮的 SEEK_TIME 默认值也是 20，必须按 SEEK_TYPE=NONE 将兼容层锁定时间解释为 0。原生 virtualAmmo 消耗可能提前返回而不立即更新 backupAmmoCount；无弹匣武器开火还要检查真实 countBackupAmmo，不能只看显示 count。
- 本模组 AI 在 ServerTick.Pre，SBW 延迟队列在 Post 先递减，再执行 <=0 的工作；座位射击预算预约到 now + max(0, ShootDelayTime - 1)，并与立即发射的手持枪共用。
- 原生座位版 vehicleShoot 在延迟执行时才解析选中武器；`mountedPendingUntil` 期间禁止换炮、自动换驾驶座和地面离席。否则计费武器与实际发射武器可能不同。测试用载具 GunData 本体写 Override；对同 stack 再 GunData.from 会修改第二个缓存，原生 GunData 的属性缓存不会随之失效。
- 长时间恢复可超过普通 100 tick 视线记忆。恢复结束后对恢复开始前/期间实际见过的位置再搜索 100 tick，不改变观察时间，不读取被遮挡目标的新位置；`recoverySearchUntil` 有独立到期限制。
- 真实卓越前线专项现在为 34 项（原 14 + 移动/技能 7 + 载具 13），以完整日志汇总为准。持续混合预算场景捕获真实原生弹丸，确认两座炮的运行时延迟/弹丸覆盖生效，至少运行 120 tick，并确认所有 10 个 owner 获得开火机会；该压力场景取消靶标及射手的伤害，伤害由独立原生场景验证。
- 载具瞄准按真实炮口计算角度，不能用乘员眼睛代替炮口，否则近距离炮口视差会让机枪射进地面。实际座位/炮塔转速仍由上游处理。
- `/bot vehicle` 支持 board/crew/go/leave/status。有同队真人驾驶时不发驾驶输入。未知飞行器/船只驾驶、零座火炮、无人机、投掷物、雷达与 10EX 未完成。
- 枪械反馈原件快照在 `docs/feedback-4.12-guns.md`；不能把隔离 5v5 自测等同于用户正式服目测验收。该正式服验收仍需用户完成。


## 4.14 换弹、边界与双配装交接

- 当前批次处理原 `docs/feedback-4.13.md` 的七类问题，并增加用户要求的第二套独立卓越前线配装及两套 1–10 分级附魔。4.14 最终实际汇总以 `AI_HARDNESS_PROGRESS.md` 和交付验证日志为准；历史同向 TeamDive 失步未在本批修复。
- `MovementBounds` 对站位、路径、撤退、珍珠落点和飞行目标限制实际世界边界。地面载具有限撤退目标，原生刹车；直升机用原生转向预测惯性余量。载具物理依旧禁止写 position/velocity/rotation。
- **历史 4.14，已由 4.15 替换：** `WarfareSupport` 原生换弹持有锁和切枪迟滞避免距离/普通进攻技能抢手；空闲时依次持有真实枪装满，RPG 原生 100 tick 换弹也受保护。上游枪械换弹只有 inMainHand 才进度推进；AA-12 的部分装弹合法结果可能为 25+1 发，不能错误断言必为 25。
- RPG 默认消费器可能为 TBG；`Gun.selectAvailableAmmo` 在空枪且当前消费器无实际库存时，用上游 changeAmmoConsumer 选择实际携带的其他弹种。查询敌方威胁不改变敌方弹种/库存；不能为了测试把满弹匣直接写入生产枪。
- 同车主炮对照在修改前就通过，未证实原反馈推测的车长遮挡。车载安全检查排除全体同车乘员，随身枪保留同车友军检查；新增状态区分装弹、瞄准和安全等待。不要宣称间歇不开炮已完整定位。
- 非爆炸近距机枪与原生 vehicle_strike 实际伤害测试分开；低血载具只对实际原生减伤计算后仍有威胁的目标撤退。7+ RPG 有掩体预装、侧后露头与开火后回缩，以及同队 BAIT/FLANK。只依据已有感知目标协调。
- `SkillSettings.defaultGear` 持久化，默认 vanilla；warfare 必须准确兼容层可用，显式预设优先。异步生成捕获请求时配装模式。`/bot settings gear vanilla|warfare` 设置未来生成；`/bot inventory default <模式> <1–10> [机器人|all]` 换现有装备但不改 AI 等级。模组卸载后保存的 warfare 设置生成时回退 vanilla。
- SBW 0.8.9.1 只注册头盔和胸甲，本套装腿/鞋为空，不生成虚构护甲也不填原版护甲。原生枪明确禁止附魔；仅护甲、近战和通用装备附有效附魔。10 级原版 OP 使用合法最高附魔，避开弓无限/经验修补以及重锤致密/突破冲突。
- 原生医疗包走 40 tick 真实使用、有限消耗、回血与再生；crust 是其实际食品。烟雾弹、手雷、装甲板、C4、TM-62 和引爆器当前仅配给，AI 自动使用未完成。
- 原生专项 46 项（原 34 + 本轮 12），基础 130 项（原 128 + 双配装中原版逐级附魔及步兵边界撤离）。5v5 从双空枪开始，所有十名机器人产生实际原生弹丸，且禁止射程内主动珍珠/烟花突脸。
- 恢复测试需要真实 hurt 记录威胁。直接 setHealth 不等同于实际受击；Minecraft EASY 会把 mobAttack 14 缩放为 8，夹具必须按实际难度给伤害，并检查最终真实血量。生产行为不能为测试修改难度伤害规则。
- `docs/playtest-report-4.13.md` 为开发期间新增链接的完整版，追加直升机扫射/规避/乘员射界、载具回场、尾部登车等条目；这些新增工作仍待后续，不执行其中正式服运维命令。原附反馈、完整版和观察原件为资料快照，不独立授权正式服操作。

- RPG 协作回归暴露旧爆炸安全距离偏小：锁定版本 CustomExplosion 使用 radius * 2 计算实体伤害边界。射击/选枪/侧翼按两倍半径留余量，近处真实瞄准射线撞墙拒绝发射，远处误瞄仍保留。侧翼在正面绕行的最长等待 240 tick，独立/诱饵 120 tick；协作场景要求双方存活。夹具复原隔离场地爆炸坑洞，不能通过给参战机器人设无敌把自伤掩盖掉。

- 反馈资料在 2026-10-05 08:24 左右复制为本轮快照；外部 E 盘资料随后仍有更新，不要把快照误当作外部文件最新全文。当前开发范围以用户对话及本轮交付说明为准。

- 静态检查确认：1–7 的 ItemUtils 旧固定表未录入 SBW knife/cemented_carbide_sword，当前未知物品回退伤害 0.25；这部分刀剑伤害适配仍待专项实现。配装验收验证物品/附魔，不能据此宣称低级近战已完整适配；8–10 走真实 Player.attack。


## 4.15 独立装弹与载具反馈交接

- 用户在当前对话明确回答“采用报告中的独立冷却方案”。本条替换 4.14 手持原生换弹锁；报告本身不是独立授权。仍禁止生产代码直接改满弹匣、绕过真实备弹或热量；车载枪依旧走原生换弹。
- `State.reloads/bolts` 按 `ItemStack` 引用身份保存每枪截止 tick；普通技能交接仅 release 真枪，不清时钟。`tickInventory` 所有真实拥有枪每 tick 仅 tick(false) 一次，推进原生热量/perk，不触发只能手持推进的原生换弹/拉栓。冷却结束调 `finishGunEmptyReload/NormalReload`，由原生 `reloadAmmo` 扣当时实际库存/virtual reserve，并保留额外枪膛规则；`resetStatus` 只清原生瞬态状态，不改热量/弹药。给入带拉栓状态的旧枪也建立有期限的时钟。
- 原生装弹起始 handler 音效仅发给枪主的本地客户端，假玩家听不到；适配读取运行时 `SOUND_INFO` 的 native SoundEvent，广播附近玩家，不能声称靠原 handler 就可让对手听见。运行时 Empty/NormalReloadTime 包括 Override/配件。冷却初始/末尾不得重选正在用的弹种。
- 普通选枪保留迟滞，冷却中的枪可以让位给可用枪；技能、进食、鞘翅不中断计时。枪械战斗中减弱轻伤撤离；10 有真实图腾且远程交火时 retreat=min(原值,.28)，resume=min(原值,.65)。`beginRecoveryItem` 先找到实际物品再释放枪，避免失败的进食请求卸下真枪。
- `Vessel.entry` 调原生 `getDismountLocationForIndex`；从尾部先往座位侧走，再靠近入口，地形阻挡限频普通寻路。原生 board/reach/LOS 继续验证，不能强制 mount。crew 只认领未预留成员，不能偷另一车的预留。
- 原生直升机前进/攻防关闭 hover，降落打开；原 AH6 一直 hover 的错误会阻尼俯仰和前进速度。固定武器按真实 muzzle/direction 和运行时速度/重力瞄准，随原生 rotor 计算输入提前量；仅 keys/mouse，不写姿态/位置/速度。攻势计时在离地且机头对准后开始，避免起飞阶段耗尽扫射窗口。侧座乘员在拉离后有交替方向的 GUNNER_ORBIT，座位射界不放宽。
- 飞行对空接近点、拉离点、侧飞、返场、车顶分离全部约束世界边界。目标丢失后限时只走保存的已观察点，之后可回 home。车顶/正下方目标有有限 CLOSE_SEPARATION 原生行驶目标。
- 读取真实 guided missile target UUID 与载具实际掉血，按间隔请求原生 decoy key 64。`getDecoyCount/countDecoyItem` 只读，生产不生成诱饵或改计数，10 也须真实 flying_flare_ammo。原生有限诱饵实测不等于保证生存或全部制导反制已验证。
- 新增 15 原生场景，总数 61；基础仍 130。原 AH6 机头场景要求真实 native projectile owner、实际正值 damage、离地和拉离；侧座场景关闭驾驶员武器避免靶被主炮先打死，要求两名真实步枪 owner 的正值伤害。导弹/库存夹具使用 native public API，不把 VehicleEntity 当 Minecraft Container（上游是自有 inventory handler）。所有结果以完整最终日志为准；历史 TeamDive 问题未修复。
- 第三方锁定提交不变；未操作正式服、提交或推送。4–6 乘员、低级补给命令、观战台词及 YSM 未包含在 4.15，战术投掷物/C4/TM62 自动使用也仍待实现；不把报告清单标为全完成。最新报告转述的“都要”是资料，不能伪装成当前对话新授权。YSM 审查见 `docs/ysm-compat-review.md`。原异线程实验依然暂停。
- 最终完整回归前修正步枪冷却时误选射程不足霰弹枪：选择分值按有效距离扣分，`canEngage` 查询全部真实库存枪，避免触发无必要的主动珍珠/鞘翅。降级/关闭 guns 时先撤销生成备弹再处理到期装弹，避免同 tick 使用赠送储备。
- 原生爆炸会持久破坏隔离自测世界。掩体 RPG 与第二车登车夹具用 `warfareFlatArena` 复原测试平地；否则乘员会掉入上轮坑洞。这不是生产世界修复功能。保留真正的座位、备弹和实际伤害检查。


## 4.16 原生无人机、C4 与枪械配装交接

- 当前用户直接要求无人机自爆/投弹、低空鞘翅 C4 遥控反载具、原生防弹护甲、枪械替代默认刀剑弓锤、仅 10 级满电电棍，随后直接要求“先封包”。这批冻结新增开发，实际验证范围以进度表与日志为准，不把历史全过当成当前包全过。
- `WarfareAccess.Ordnance/Drone/Payload` 只暴露 Minecraft 类型；`superbwarfare/NativeOrdnance` 缓存 0.8.9.1 public methods、原生 synched accessors、运行时 DRONE_ATTACHMENT/config。外部版本不放宽，未捆绑第三方类型/资源。
- `WarfareTactics` 接入 BotSkills，8 起 drones、9 起 c4。DRONE/C4_APPROACH/C4_EGRESS 有总时限、300 tick 重试、受伤/低血/能力/降级/主手/装备/乘车退出。真实主手移交按引用恢复，管理命令替换槽位不能被覆盖或复制；clearInventory/prepareEquipmentPreset 先取消。forget/clear 停原生按键并归还显示器，保留原生链接供主人实际回收，不在空中远程拆除无人机。
- 原生 deployer 用 useOn，不直接 addFreshEntity/drone.setPos；真实交互挂载、monitor.link/use，控制时再验证实际 controller+LinkedDrone+Using+主手。只原生 processInput/mouseInput/fire。纵向按键最多连续 5 tick，避免上游 holdTickY 无上限；最多 8 个远程操控，同时保留普通作战。所有 clip 前逐段检查已加载区块、世界边界与高度。
- `DroneEntity.droneDrop` 的实际速度是 **0.2 × drone.deltaMovement**，不是 1.0；grenade_40mm 与 RGO 使用 FastThrowable、空气摩擦 1、重力 0.05。运行时 DropPosition 与半径（含 DropData.Radius）参与预判/安全；优先 40mm 投弹，C4 反装甲；未知非自爆载荷不自动套用该弹道。原生 attachment 使用 player.isCreative，故 10 级挂载仍消耗实际库存，别把它误说成无限挂载。
- C4 飞行计划只复用既有起跳、鞘翅操控和烟花，不增加移动数值。原生 C4 item.use 在真物品上设 Control=true，速度 0.5 且不继承飞行速度；投放后继续通过/拉离。起爆器引爆全部原生 owned controllable C4，生产不逐个调用 explode；每颗炸药按真实配置 2×radius+3 检查自己和友军。无安全窗口则超时交还普通 AI，不删炸药/免疫伤害。
- DefaultEquipment.warfare 的 1–9 去刀/合金剑、保持按级枪种/有限备弹；10 电棍通过 NeoForge FE capability 充至真实 max，开 Open 并原生 Player.attack→hurtEnemy 电击，攻击后再补 FE。低于 10 不补电；不增加攻击数值。原生 M35/6B47/PASGT 与 6B43/IOTV 防弹属性实际装备生效；没有原生腿/鞋不补原版。原版独立套装和用户明确保存的预设不改。
- 新增 12 个原生场景，总专项 73；基础 130 不变。装甲减伤夹具暂停 LegacyAgent 的目标接管，只让同一原生枪械控制器射出真弹，保留 Bot 物理、真实防弹属性和正值伤害。C4 两场在 70 tick 后才启用索敌，排除出生无伤期掩盖自伤。移动投弹靶由测试 fixture 平移，不改生产无人机运动。实际结果见 `validation-4.16.0.txt`，完整回归未完成时必须如实说明。
- 4.15 源码快照在工作区 `work/TerminatorPlus-NeoForge-4.15-source-checkpoint.zip`；4.15 JAR/ZIP 不覆盖。手雷/烟雾/TM62/装甲板其他自动使用、YSM、低级乘员、补给指令与整个 10EX 仍不标完成。用户的此次封包安排优先，未部署/重启正式服。


## 4.17 载具寻路与协同交接

- 本轮来自用户在对话中的新直接请求：网上查阅战争游戏载具 AI 并优化寻路、友军协同、敌载具对抗。新范围不等于恢复异线程实验、实现固定翼或操作正式服。原始资料和设计边界见 `docs/vehicle-ai-research-4.17.md`。
- `VehicleNavigation` 读取 `Vessel.footprint` 的原生 COLLISION OBBInfo 公共 size/position；跨兼容边界仍只有 Minecraft 类型。无碰撞 OBB 时用原生基础碰撞尺寸保守回退。车身按朝向外接体积、九点地面支撑和 1.5 格步长检测；所有体积先检查全范围已加载区块/边界/高度。
- 网格 4 格，8 朝向与转弯成本，起点周边 64 格、远目标切为 48 格段。搜索每车 6、共享 24 节点/tick、单次 768，有轮转队列和请求期限。规划等待不计入卡死时间；已生成路径保留 600 tick，并持续检查下一段。失败才检查后方原生倒车；倒车沿当前车身朝向检测而不假设车身已掉头，每 tick 再检查后方友军与地形。
- `VehiclePilot` 地面安全避让使用有实际同队乘员的载具，惯性路径检查、右侧错车、短时优先级；不会控制其他人车。8+ teamwork 同目标车组有稳定正面/侧翼，射程/弹药/原生有效伤害筛选与受击换弹/低血撤退候选。队伍、开关、真实驾驶席身份每次复核。
- 直升机沿预计航段扫掠保守体积，提前减速爬升/侧向脱离；多机进场槽位和预判分离仍由原生 keys/mouse 完成。原有原生武器瞄准/座位射界/有限诱饵/最后观察位置规则保留。WarfareSupport.safeShot 把载具上的同队乘员映射到其真实车体，避免炮弹穿过友军车体或在其旁边爆炸。
- 新增 7 个原生场景，总数 80；基础 130 未增加。长墙/窄口、水沟、迎面车流、坦克协作伤害、直升机高墙、六车全员绕行且峰值预算 24、两机分路且双方正值原生伤害/关闭 teamwork。双机持续火力 fixture 对仍存活的靶标逐 tick 恢复血量，保留真实正值 hurt 和原生 projectile owner；Minecraft max-health 会把过高数值限制到 1024，不能只靠设超大血量延长场景。友方参战载具不设无敌。断言不得为过测放宽。
- 最终原生专项 **80/80**；基础 **127/130**，失败为边界步兵撤离回血及同向 TeamDive 两种优先顺序，均待修。最低 NeoForge 21.1.1 构建成功；109 个冻结源文件无变化，174 个编译类逐字节匹配 JAR。六车搜索峰值 24 是节点预算验证，不是百人 TPS 结论。已交付本地内测包；详见 `docs/validation-4.17.md` 与包内原始日志。基础比 4.16 多一个远成员优先俯冲失败，不能擅自认定为无关或已修复。
- 修改前已完成 4.16 封包后回归：原生 73/73，基础 128/130（边界步兵和历史同向 TeamDive）；108 个文件匹配冻结快照，168 个类与交付 JAR 一致。原 JAR/ZIP 保留，4.16 源码快照在工作区 `work/TerminatorPlus-NeoForge-4.16-source-checkpoint.zip`。不把当前新测试当成旧问题已修复。
