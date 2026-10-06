# TerminatorPlus — NeoForge 1.21.1 移植版

把 [HorseNuggets/TerminatorPlus](https://github.com/HorseNuggets/TerminatorPlus)（Paper 1.21.1 插件）重写为 **NeoForge 1.21.1** 模组。
机器人是服务端的"假玩家"：会追击目标、搭路、垫高、挖穿障碍、空中落水自救、过岩浆放船，还能用一个小型神经网络做种群强化训练。

- Minecraft **1.21.1**，NeoForge **21.1.1 及以上**的任意 21.1.x 版本，Java **21**
- 纯服务端模组：放在专用服务器的 `mods/` 即可，玩家客户端**不需要**安装（原版/NeoForge 客户端都能进）；单人游戏装在客户端里也能用
- 许可证：EPL-2.0（与原项目一致），原作者 HorseNuggets 及贡献者

## 安装

```bash
./gradlew build
```

产物在 `build/libs/TerminatorPlus-NeoForge-1.21.1-4.5.1-BETA.jar`，放进服务器（或客户端）的 `mods/` 文件夹。

## 命令

与原插件一致（需要权限 `terminatorplus.manage`，默认 OP 等级 2 及以上；装了权限模组时可单独授予）。

| 命令 | 别名 | 说明 |
|---|---|---|
| `/terminatorplus` | `/tplus` | 模组信息；`debuginfo` 把调试信息上传到 mclo.gs |
| `/bot create <名字> [皮肤] [位置]` | `/npc` | 生成一个机器人 |
| `/bot multi <数量> <名字> [皮肤] [位置]` | | 批量生成，名字里的 `%` 会被替换成序号 |
| `/bot give <物品>` | | 设置所有机器人的默认武器（决定攻击伤害） |
| `/bot armor <none\|leather\|chain\|gold\|iron\|diamond\|netherite>` | | 给所有机器人穿盔甲 |
| `/bot info <名字>` · `/bot count` · `/bot reset` | | 查看 / 计数 / 全部移除 |
| `/bot settings setgoal <目标模式>` | | `nearestvulnerableplayer`、`nearestplayer`、`nearesthostile`、`nearestraider`、`nearestmob`、`nearestbot`、`nearestbotdiffer`、`nearestbotdifferalpha`、`customlist`、`player`、`none` |
| `/bot settings mobtarget <true\|false>` | | 是否允许怪物以机器人为目标 |
| `/bot settings playertarget <玩家>` | | 配合 `player` 模式锁定一名玩家 |
| `/bot settings addplayerlist <true\|false>` | | 新机器人加入玩家列表（Tab 栏可见、会被 `@a`/`@p` 选中） |
| `/bot settings region <x1 y1 z1 x2 y2 z2> <wX wY wZ>\|strict\|clear` | | 设置优先/限定目标区域 |
| `/bot debug <方法(参数)>` | | 调试方法，例如 `/bot debug confuse(10)`、`sit()`、`toggleAgent()` |
| `/ai random <数量> <名字> [皮肤] [位置]` | | 生成带随机神经网络（会举盾）的机器人 |
| `/ai reinforcement <种群大小> <名字> [皮肤]` | | 开始种群强化训练（需玩家执行，机器人生成在其上方） |
| `/ai stop` · `/ai info <名字>` | | 结束训练 / 查看机器人的网络权重 |
| `/botenvironment addSolid\|removeSolid <方块>` 或 `<x y z>` | `/botenv` | 手动把方块视为实心 |
| `/botenvironment addCustomMob\|removeCustomMob <实体>` · `mobListType` | | 自定义目标生物列表 |
| `/bot inventory give <物品> [数量]` | `/bot inv` | 往所有机器人的背包里放物品 |
| `/bot inventory kit <elytra\|mace\|pearl\|windcharge\|bow\|full>` | | 一键发套装（见下文"强化 AI"） |
| `/bot inventory clear` · `/bot inventory show <名字>` | | 清空背包 / 查看某个机器人的物品 |
| `/bot settings range <格数\|unlimited>` | | 索敌范围，默认 `unlimited` |
| `/bot settings ability <能力> <true\|false>` | | 单独开关各项强化能力 |
| `/bot settings buildblock <方块>` | | 机器人搭柱、搭桥、垫脚用的方块，默认圆石，比如 `minecraft:obsidian`（必须是完整方块，用不完） |

`[位置]` 可以是在线玩家名，或 `x y z [维度]`，坐标支持 `~` 相对坐标；维度用 `minecraft:the_nether` 这类 id，也兼容原插件的 `world` / `world_nether` / `world_the_end`。

## 强化 AI

在原插件"直线追击 + 挖穿一切"的基础上增加了下面这些能力。物品类能力**只有机器人背包里有对应物品时才会使用**（`/bot inventory` 发放）；每项都能用 `/bot settings ability` 关掉。

| 能力 | 名字 | 做什么 |
|---|---|---|
| 防卡死 | `pathfinding` | 4 秒没有接近目标就判定为卡住，按顺序轮换脱困手段：用原版寻路绕路 → 在墙边搭方块翻过去 → 去附近的梯子/藤蔓爬上去 → 扔末影珍珠 → 鞘翅飞过去 → 侧跳。挖到基岩这类挖不动的方块会立即触发，不再傻挖 |
| 翻越障碍 | `climbing` | 搭柱翻墙（最高 10 格）、爬梯子/藤蔓/脚手架、从高出水面一格的岸边跳出水、被方块卡在身体里时自动挤出来、路上的木门和栅栏门会打开 |
| 鞘翅 + 烟花 | `elytra` | 目标水平距离 ≥ 30 格（或在 10 格以上的高处）时起飞：自动从背包换上鞘翅、烟花助推、贴地/撞墙前拉升，到目标附近降落后接着近战，落地后换回胸甲。飞行物理与原版鞘翅一致，鞘翅照常掉耐久 |
| 重锤 | `mace` | 有重锤时：远处目标用鞘翅爬升到目标上方约 14 格，拉起减速后松开鞘翅下落，空中修正方向砸下去；近处（≤ 5.5 格）有风弹就往脚下扔风弹弹起来砸。伤害、附魔、范围击退、音效、耐久全部走原版重锤逻辑（测试中一击 47 点）。任何带重锤的下落攻击都会自动变成重锤冲击 |
| 末影珍珠 | `pearls` | 目标在 20–60 格外时扔珍珠追近，比鞘翅快，所以有鞘翅也会先扔（血量 ≥ 10 才扔，珍珠本身扣 5 血）；卡住时用来脱困；掉进虚空时扔回最后站过的地面或目标身边。落点由模拟原版弹道算出，不会往岩浆、仙人掌上扔 |
| 风弹 | `windcharges` | 配合重锤的弹跳起手 |
| 弓箭 | `bow` | 目标在 8–60 格外、有射击线路时站定拉满弓（1 秒），按原版箭矢弹道算抛物线并预判目标走位后松手。走的是原版弓的逻辑：力量、冲击、火矢、无限、耐久都生效，没有无限就消耗箭。16 格内射得没那么勤，留时间给近战和重锤 |
| 图腾 | `totems` | 副手的不死图腾一爆，立刻从背包里补一个上去 |
| 反击 | `retaliate` | 5 秒内谁打了它，就优先打谁（不管当前目标模式），创造/旁观模式玩家除外 |

**关于索敌范围**：原插件的索敌在同一维度内其实没有距离上限（遍历整个世界已加载的玩家/实体），实际限制来自"实体所在区块必须被加载"。所以这里做的是：`nearesthostile` 从只认 `Monster` 子类扩展到所有敌对生物（史莱姆、岩浆怪、幻翼、恶魂、潜影贝、疣猪兽……）；加入反击；`/bot settings range` 可以按需限制范围（同时减少大服务器上的遍历开销）；再加上鞘翅和珍珠，远处的目标能真正追得上。

快速体验（默认目标模式会追击生存/冒险模式的玩家）：

```
/bot create Steve
/bot inventory kit full
/gamemode survival
```

然后跑远一点，或者站到高处。

## 和 Paper 版的差异

行为尽量保持一致，以下是移植时必须改动或顺手修掉的地方：

- **名字格式**：方块、物品、实体统一用注册名（`minecraft:stone`、`zombie`），不再是 Bukkit 的 `STONE` 枚举名（大小写不敏感）。
- **皮肤异步获取**：原插件在主线程请求 Mojang API，生成机器人时服务器会卡住；现在先异步拉皮肤再在主线程生成。离线环境可加 JVM 参数 `-Dterminatorplus.fetchSkins=false`，机器人使用默认皮肤。
- **区块加载跟随机器人**：机器人像真实玩家一样加载周围区块，走远后不会因区块不再 tick 而"冻住"。
- **不留垃圾数据**：加入玩家列表的机器人不会写入 `playerdata/`，所有机器人都不获得进度（不会刷进度公告）、不残留统计数据。
- **`player` 目标模式**：按原插件说明实现了"找不到指定玩家时退回最近的可攻击玩家"（原代码没实现）。
- **小修复**：实体推挤的坐标笔误（X/Z 写反）、空中预判垫方块后仍重复判断、排序比较器写错、负 Y 坐标取整错误、`/kick` 机器人、跨维度传送后机器人永久无敌、不可破坏方块判定（现在所有硬度 < 0 的方块都不会被挖）。
- **装备同步**：只在装备变化时广播，不再每 tick 给全服发装备包。
- 举盾仍和原版插件一样只有动作效果（原插件里盾牌格挡实际上从未生效，这里保持一致，不改变训练平衡）。

## API（给其他模组）

```java
BotManager manager = TerminatorPlusAPI.getBotManager(); // 服务器未运行时为 null
Terminator bot = manager.createBot(new Location(level, x, y, z), "BotName", skinValue, skinSignature);
```

接口与原版相同，只是 Bukkit 类型换成了原版类型（`Location` → `net.nuggetmc.tplus.api.utils.Location` / `Vec3`，`Block` → `BlockPos`，`Material` → `Block`/`Item`）。改名的几个方法：`getBukkitEntity()` → `getEntity()`，`attack()` → `attackTarget()`，`isFalling()` → `isBotFalling()`。`TerminatorLocateTargetEvent` 发布在 `NeoForge.EVENT_BUS` 上，可取消、可替换目标。

新增的接口方法：背包（`giveItem`、`countItem`、`findItem`、`consumeItem`、`clearInventory`、`getWeapon`）、鞘翅（`startGliding`、`stopGliding`、`fireRocket`、`isGliding`）、投掷物（`throwEnderPearl`、`throwWindCharge`）、重锤（`hasMace`、`canSmash`、`smash`）、弓和图腾（`canShootBow`、`drawBow`、`lowerBow`、`shootBow`、`equipTotem`）、移动（`launch`、`climb`、`setLook`、`getFallHeight`）。机器人背包约定：快捷栏第 0 格是 AI 换工具/方块用的"手"，其余 35 格才是真正的背包。

## 开发

- 接手开发（包括 AI 助手）先看 [`AGENTS.md`](AGENTS.md)：代码结构、机器人的关键机制、测试方法、最近的改动
- 需要 JDK 21；`./gradlew runServer` / `runClient` 启动开发环境
- `gradle.properties` 里的 `neo_version` 是编译用的 NeoForge 版本，要保持为 `neo_version_range` 的下限（目前 21.1.1），这样不会误用新版本才有的 API。想在别的 NeoForge 版本上测试，临时改 `neo_version` 再跑 `runSelfTest`
- `./gradlew runSelfTest` 会启动一个超平坦世界的专用服务器，自动执行一整套命令和场景（生成、对打、挖穿堵住的通道、200 格高空落水自救、AI 机器人、玩家列表模式、`/tp`、`/reload`、存档、`/kill`、Tab 补全；翻出基岩围栏、绕过基岩墙、跳出深水池、爬梯子、鞘翅远程飞行、鞘翅重锤俯冲、风弹重锤、珍珠追击、虚空珍珠自救、反击、索敌范围、被方块卡住后脱出、攻击史莱姆、背包命令、远程弓箭命中、用指定方块（黑曜石）搭柱、图腾爆了自动补；最后跑完一整代强化训练），共 44 项检查，结束后自动关服；日志里搜索 `[SelfTest]`
