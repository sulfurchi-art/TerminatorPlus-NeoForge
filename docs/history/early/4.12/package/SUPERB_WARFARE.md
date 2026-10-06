# 4.12 卓越前线枪械内测

适配 **Superb Warfare 0.8.9.1-final-mc1.21.1**，对应源码提交 `5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816`。本轮优先实现设计笔记中的单兵枪械和反载具武器判断。没有安装卓越前线时，TerminatorPlus 仍可独立运行；其他卓越前线版本会禁用此兼容层，可在 `/bot info <名字>` 查看状态。

卓越前线这个版本自身要求 NeoForge **21.1.203 及以上**，用户的 21.1.249 符合要求。TerminatorPlus 单独运行时仍兼容 21.1.1，下限没有改变。卓越前线与它的依赖按自身要求安装；TerminatorPlus 仍只需放在服务端。

## 开始测试

先准备自己的枪械、配件和弹药，并空出一个背包格，保存成预设：

```mcfunction
/bot preset save warfare
/bot createpreset warfare RifleBot 7 none
/bot settings ability guns true
/bot settings setgoal nearestvulnerableplayer
/bot info RifleBot
```

预设沿用玩家真实枪械组件、已装弹匣和备弹物品。生成要等皮肤请求完成；可通过 `/bot count` 确认生成。准备多个枪种时，机器人会根据距离选择已有武器。

也可在机器人已经生成后发放基础步枪和有限弹药。以下库存指令影响所有现有机器人：

```mcfunction
/bot inventory give superbwarfare:ak_47 1
/bot inventory give superbwarfare:rifle_ammo 64
```

不要使用 `/bot give` 配枪，它设置的是默认近战显示武器；枪必须在真实背包中。也不要用普通 `/give` 将物品塞进会被 AI 覆盖的第 0 格。单独配装可以使用 `/item replace entity RifleBot hotbar.1 with superbwarfare:ak_47` 等原有指令。

关闭兼容动作：`/bot settings ability guns false`。它会归还真实枪械，恢复原有近战和其他技能。预设也可用 `/bot loadout profile warfare ability guns false` 保存单个套装的覆盖。

## 当前行为

| 等级 | 枪械控制 |
|---|---|
| 1–3 | 身体瞄准、较大误差、较慢转身、不主动开镜；打空再换弹 |
| 4–6 | 按距离选择已有枪种，中远距离开镜和短点射；换弹时尝试附近可达掩体 |
| 7–9 | 尝试瞄头、弹速提前量和重力补偿；有安全掩体时提前换弹，近距离优先可用武器 |
| 10 | 在相同枪械参数下提高操作精度；生成备弹，弹匣仍会打空，换弹和拉栓时间仍真实存在 |

- 枪械使用卓越前线原生开火、弹药、热量、拉栓和换弹流程，生成真实弹丸、音效和伤害；不直接扣目标血量，不修改枪械数据，不提高机器人运动速度。
- 真实枪械在使用期间交换进主手，结束后归还背包；显示副本不会参与开火或背包 tick。吃药、切预设和关闭能力都能中断用枪并保留实际物品。
- 持枪的 1–9 级受视野和视线限制，失去视线后只保留限时最后已知位置。所有等级开火都要求目标实际可见，并限制每 tick 的转身角度。低难度瞄偏可以打到地面，失误不会被过滤成自动精准命中。
- 近距离优先已有霰弹枪或冲锋枪，中距离偏向步枪，远距离偏向狙击枪。数据读取运行时 GunProp，包含配件、弹药和数据覆盖的结果；射击范围当前最多 160 格。
- 当原有敌对目标乘坐载具时，4–10 调用载具本身的减伤计算比较已有武器。对子弹免疫的坦克不会继续使用步枪；有适合的 RPG 时可改用。友军乘员所在载具不射击。
- 开火前检查友军所在射线；爆炸武器还检查目标附近的友军和自身距离。这是发射前的安全判断，不能保证运动中的友军永远不进入已经发射的弹丸或爆炸范围。
- 全体机器人共享每 tick **64 颗弹丸**的开火预算，轮换等待，霰弹枪按实际弹丸数计费；每个机器人每 tick 最多一次开火。高射速或人多时实际射速可能降低；没有进行百人性能校准，也没有启用后台 AI 线程。

## 已验证与后续范围

功能自测使用真实 0.8.9.1 JAR：AK-47 真实伤害与有限库存弹药、AWM 拉栓和命中、AA-12 与 AWM 按距离切换、RPG 对真实 M1A2 的伤害、原生换弹空窗、物品组件/引用恢复、运行时数据覆盖，以及 12 人霰弹枪弹丸预算和公平轮换。最新实际结果见 `AI_HARDNESS_PROGRESS.md` 与交付包中的验证日志。

其他普通枪械按相同 API 和运行时数据接入，但尚未逐枪实测。特殊枪种、蓄力/能量等特殊开火流程暂不接管。导弹锁定已设置持续瞄准时间、角度、距离和目标高度条件，但标枪/IGLA 的实际制导及各类诱饵反制尚未完成专门实测，不作为本批已验证能力。低速抛物线武器的提前量为有限近似，复杂移动目标仍需人工校准。

本轮没有加入空载具自动索敌、驾驶、登车、多座乘员组、无人机、投掷物、军事喊话或 10EX 指挥系统；相关设计留在 `docs/superbwarfare-compat-notes.md`。现有硬度仍为 1–10。

## 开发者验证

基础回归不安装卓越前线，仍用最小 NeoForge：

```powershell
$env:JAVA_HOME='C:/Program Files/Microsoft/jdk-21.0.7.6-hotspot'
.\gradlew.bat build --console=plain
.\gradlew.bat runSelfTest --console=plain
```

集成验证使用隔离目录中的卓越前线和 Kotlin for Forge JAR。另建超平坦自测目录，接受 EULA 并设置与正式服不同的端口；本工作区使用 25600。以下两个目录参数相对于仓库根目录：

```powershell
.\gradlew.bat runSelfTest '-PselftestFocus=warfare' '-PwarfareModsDir=../../work/warfare-test-mods' '-PselftestDir=../../work/warfare-selftest' '-Pneo_version=21.1.249' --console=plain
```

集成测试不把第三方 JAR 打进产物。最后在默认 21.1.1 再构建；不能把一次临时较新版本编译的 JAR 当作最低版本兼容产物。功能汇总必须查看 `[SelfTest]`，Gradle 成功不等于场景通过。
