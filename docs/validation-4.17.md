# 4.17.0-BETA 最终验证

日期：2026-10-05。本轮范围为用户直接要求的载具寻路、友军协同、敌载具对抗及网上参考资料；研究记录见 [vehicle-ai-research-4.17.md](vehicle-ai-research-4.17.md)。所有运行均为隔离自测服。

| 验证 | 最终结果 | 环境 / 日志 |
|---|---|---|
| 卓越前线完整专项 | **80 PASS / 0 FAIL** | NeoForge 21.1.249、SBW 0.8.9.1、Kotlin for Forge 5.12.0；`ai-hardness-4.17.0-warfare.log` |
| 无卓越前线基础完整回归 | **127 PASS / 3 FAIL / 130** | NeoForge 21.1.1；`ai-hardness-4.17.0-selftest.log` |
| 最低版本构建 | **BUILD SUCCESSFUL** | JDK 21.0.7、NeoForge 21.1.1；`ai-hardness-4.17.0-build.log` |
| 冻结源码与产物 | **109 个源/构建文件一致；174 个编译类匹配 JAR** | SHA256、元数据、AT、包内容检查随交付记录 |

原生专项最终汇总为 `ALL 80 CHECKS PASSED`，耗时 10m 3s；基础回归最终汇总为 `3 of 130 checks FAILED`，耗时 9m 19s。基础回归的 Gradle 退出成功不表示场景全部通过。

## 本轮新增七项

真实 M1A2 绕长墙并拒绝车身过不去的窄口；水沟支撑检查与绕行；同队迎面两车实际通过且不损血；两辆同队坦克分散并产生原生炮弹伤害；AH-6 预测航段体积检查并飞越高墙；六辆真实坦克全部绕行，搜索共享峰值 **24 节点/tick**；两架 AH-6 分路、保持分离、双方原生武器正值伤害并在关闭 teamwork 后退出协同。七项均包含于最终 80 项完整运行。

持续射击夹具对仍存活的敌方靶标逐 tick 恢复生命，保留真实伤害和弹丸 owner；参战友军载具没有无敌。搜索预算验证不等于服务器耗时或百人 TPS 验证。

## 基础失败

1. `equipment border infantry retreats heals and navigates with an inward margin`
2. `teamdive same side native takeoffs spread before a common smash with far member first`
3. `teamdive same side native takeoffs spread before a common smash with near member first`

修改前 4.16 冻结源码回归为原生 73/73、基础 128/130，其中第一、第三项失败。第二项在本次也失败，基础通过数下降一项；同向俯冲历次运行曾在不同顺序下失败，本轮仍未定位或修复。步兵生产代码未修改，但不能据此排除间接影响。保留完整失败日志，不把定向通过或 Gradle 成功作为修复证明。

## 过程记录

最终完整运行之前，夹具首次编译因调用 `setBlock` 参数不匹配失败；首次五项定向运行 2/5，通过修正规划等待被计入卡死时间、明确启用测试车组 teamwork 后，六项定向 6/6。新增双机后两次七项运行均 6/7：存在实际分离不足与敌靶先死亡的问题。调整提前预测、垂直间距和原生悬停，并用上述持续射击夹具保留双机实际伤害验证，单项 1/1 后完成最终 80/80。

一次旧代码完整专项在 13 PASS / 0 FAIL 时主动停止，用于修正驾驶席身份变化后的状态复核；该日志没有最终汇总，不计为完整通过。失败和中断日志作为过程资料随包放入 `process/`，最终验收仅使用表中三份日志。

## 产物与边界

JAR：`TerminatorPlus-NeoForge-1.21.1-4.17.0-BETA.jar`，731274 字节。

SHA256：`CF5BBEC5675E17DCCAD3F0492E35EDB55BF0D92074B1AEF30960FBE1F78A0230`。

兼容下限仍为 NeoForge `[21.1.1,)`；安装 SBW 时遵守其 NeoForge 21.1.203+ 与依赖要求。第三方源码锁定提交 `5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816`。未捆绑第三方模组/代码/资产，旧 4.16 JAR/ZIP 保留。

驾驶只走原生输入，没有新增载具机动力。固定翼自动驾驶未实现；复杂坡道、洞穴、巨大迷宫、真人多人空战和大规模 TPS 未验证。异线程实验仍暂停。未操作正式服、部署、提交或推送。
