# 4.16 封包后完整回归

日期：2026-10-05。测试在隔离专用服运行，正式服未操作。

封包 JAR SHA256：`0896467EE0C3D1D0248B1A35AC8BA8FDC2EA71B2519C72DB2DACCC40B45A1032`。108 个源文件/构建文件与封包时快照一致；测试前 168 个编译类与交付 JAR 逐字节一致。

- `ai-hardness-4.16-warfare-full-verified.log`：73 项通过，0 项失败；Gradle 正常退出。
- `ai-hardness-4.16-base-full-verified.log`：128 项通过，2 项失败；Gradle 正常退出。
  - 失败：`equipment border infantry retreats heals and navigates with an inward margin`。
  - 失败：`teamdive same side native takeoffs spread before a common smash with near member first`。

基础回归 128/130，不能称为全过。TeamDive 为历史未解决问题；边界步兵场景为本次新增观察到的失败，均在载具优化修改前出现。本轮未降低断言、未给予参战机器人无敌、未覆盖原 4.16 JAR/ZIP。
