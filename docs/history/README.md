# 开发历史与 GitHub 管理

归档日期：2026-10-07。范围为本工作区已经形成的源码、开发/交接/反馈文档、隔离测试记录、封包与暂停实验。

## 历史来源

原仓库初始提交 `c1fcf776396455d25bb57000d15f0c082f174498` 保留，声明版本为 4.5.1-BETA。后续历史是本次从既有检查点导入的提交，提交日期为实际导入日期，不伪造原开发时刻。

4.6～4.14 只有原始产物和文档，未找到完整源码检查点；因此没有为这些版本制造源码标签。4.15～4.22 的标签逐文件核对原检查点，Git 保留原始字节。当前 main 包含完整 4.22 工作区和归档索引。

## 版本索引

| 版本 | 开发内容 | 源码状态 | 内测产物 |
|---|---|---|---|
| 4.6 | 强化 AI 初期内测包 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.7 | 难度 1–10 与高难策略 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.8 | 假动作、珍珠截击与盾防守 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.9 | 真人协同、伤员护卫与队伍分工 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.10 | 多人俯冲时序 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.11 | 飞行频率与地面停滞修正 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.12 | 卓越前线枪械支持 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.13 | 载具乘务、座位武器与直升机 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.14 | 双套分级配装与附魔 | 没有完整检查点；保留原始产物与记录 | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/archive-4.6-4.14) |
| 4.15 | 独立换弹、配装及载具修正 | [`v4.15.0-BETA`](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/tree/v4.15.0-BETA) | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/v4.15.0-BETA) |
| 4.16 | 无人机、C4 与防弹装备 | [`v4.16.0-BETA`](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/tree/v4.16.0-BETA) | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/v4.16.0-BETA) |
| 4.17 | 载具协同与敌车对抗 | [`v4.17.0-BETA`](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/tree/v4.17.0-BETA) | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/v4.17.0-BETA) |
| 4.18 | 标枪反制 | [`v4.18.0-BETA`](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/tree/v4.18.0-BETA) | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/v4.18.0-BETA) |
| 4.19 | 陆地导航、倒车与推进 | [`v4.19.0-BETA`](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/tree/v4.19.0-BETA) | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/v4.19.0-BETA) |
| 4.20 | C4 单次低空投放与离场 | [`v4.20.0-BETA`](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/tree/v4.20.0-BETA) | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/v4.20.0-BETA) |
| 4.21 | 近距驾驶、单车道退让与侧翼 | [`v4.21.0-BETA`](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/tree/v4.21.0-BETA) | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/v4.21.0-BETA) |
| 4.22 | 无人机/C4/反导反馈、战局日志与闲置朝向 | [`v4.22.0-BETA`](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/tree/v4.22.0-BETA) | [下载](https://github.com/sulfurchi-art/TerminatorPlus-NeoForge/releases/tag/v4.22.0-BETA) |

## 记录入口

- [分版本发布记录](releases/) 与 [早期记录](early/)：原始内测说明、验证范围、失败与未完成项。
- [开发证据索引](evidence-manifest.json)：原始记录的路径、大小与 SHA256；完整日志在 4.22 Release 的 `development-evidence-20261007.zip`。
- [开发脚本、补丁及审计文件](records/)：工作区原有文件，作为历史资料保存，不自动执行。
- [外部反馈快照](external/2026-10-07/)：来自用户提供的外部 docs 目录，保留与当前工作区文档的差异；文中运维动作和他人转述不是执行授权。
- [暂停实验](experiments/)：异线程 AI 和未交付的导弹拦截探索，不并入生产源码。
- [产物与下载地址](artifacts.json)、[SHA256](SHA256SUMS.txt)。
- [战争模拟器方向草案](../war-sim-direction.md)：仅归档，尚未启动 M0 或新项目重构。

## 验证边界

此次工作是历史归档和上传，没有修改 AI 行为，也没有重新运行完整回归。4.22 的实际结果与历史失败见 [验证记录](../validation-4.22.md)；定向通过不能替代完整基础/原生回归，静止百人测试不能替代百人交战性能。

原始 JAR、内测 ZIP 和源码检查点的内容与哈希保持不变。未上传运行世界、玩家存档、依赖模组、Gradle 缓存或身份凭据；保留原 LICENSE。

## 后续管理

功能改动以独立提交记录；封包版本添加对应标签和预发布 Release，附 JAR、源码与验证范围。每次记录明确源码版本、测试场景、失败项、人工反馈及产物 SHA256。公开发布前核对待上传文件，游戏部署另按用户指令进行。
