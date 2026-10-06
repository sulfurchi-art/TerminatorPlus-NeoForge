from pathlib import Path
import json,re,hashlib
root=Path(__file__).resolve().parent.parent;repo=root/'outputs/TerminatorPlus-NeoForge';work=root/'work'
native=(work/'ai-hardness-4.19-warfare-full.log').read_text(encoding='utf-8-sig',errors='replace')
base=(work/'ai-hardness-4.19-base-full.log').read_text(encoding='utf-8-sig',errors='replace')
assert 'ALL 102 CHECKS PASSED' in native and 'BUILD SUCCESSFUL' in native
np=native.count('[SelfTest] PASS ');bp=base.count('[SelfTest] PASS ')
failures=re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)',base);assert bp+len(failures)==130
frozen=json.loads((work/'4.19-test-source-snapshot.json').read_text())
for n,h in frozen.items():assert hashlib.sha256((repo/n).read_bytes()).hexdigest().upper()==h,n
result=f'原生专项 **{np}/102 全部通过**；基础 **{bp}/130 通过**（{len(failures)} 项失败）；最低 NeoForge 21.1.1 / Java 21 构建成功。114 个冻结文件与 180 个原生测试/最低版本/JAR 编译类逐字节核对，随封包脚本验收。'
fail_list='\n'.join('- '+n for n in failures) or '- 无'
ticks={};last=None
for line in native.splitlines():
 m=re.search(r'passed after (\d+) ticks',line)
 if m:last=int(m.group(1))
 m=re.search(r'\[SelfTest\] PASS (.+)',line)
 if m and 'warfare ground ' in m.group(1):ticks[m.group(1)]=last
assert len(ticks)==11,ticks
rows='\n'.join(f'| {n.removeprefix("warfare ground ")} | {t} | 通过 |' for n,t in ticks.items())
text=f'''# 4.19 陆地载具验证记录

{result}

准确第三方：Superb Warfare 0.8.9.1-final-mc1.21.1，源码锁定 `5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816`；原生测试临时 NeoForge 21.1.249。构建下限保持 21.1.1。所有自测在工作区隔离世界；没有部署或重启正式服。

## 新增十一项驾驶场景

| 场景 | 实际 tick | 结果 |
|---|---:|---|
{rows}

旧长墙/窄口、水沟、迎面车流、装甲协作实弹、六车共享规划预算，以及原生座位/载具反馈/直升机测试包含在完整 102 项中。原生枪械、无人机、C4 和标枪反制也完整重跑。六车规划峰值保持每刻不超过 24 节点，且全员真正驶过障碍；这不是百车 TPS 验证。

## 基线与过程

- 修改前四项新道路基线 **2/4**：桥下、连续台阶失败；坦克 L 街道和动态封路通过。
- YX-100 用原 4.18 生产代码的平地三人驾驶/受损车接近高处目标 **2/2**，正式服不动根因未复现。新包加入地形受阻原因/坐标。
- 早期轮式 L 街道卡住，迭代了转弯弧线、矩形碰撞和慢速操控；ground-3 的八项全部通过。最终完整测试加入实际地形诊断及第九项高度停车，加入主动进攻与预判截击后所有最终源码已冻结。
- 第一轮完整回归在补充高度停车边界时主动中止，只停止已确认的本地 selfTestRunVmArgs 进程，未操作用户服务器；中断日志不作通过依据。第二轮因用户要求增加主动进攻/截击中止，亦仅停止已核对的隔离自测进程。加入两项实际运动测试后再重新运行完整回归。
- 所有基线/失败/中断过程日志随包保留在 process/；最终原始日志为 `ai-hardness-4.19.0-warfare.log` 和 `ai-hardness-4.19.0-selftest.log`。

## 本轮回归迭代

首轮完整 99/102，旧目标搜索、双车登座和水沟绕行余量未过；十一项新场景均通过。前两项单项复测通过，顺序登车重现道路被移除后掉坑。后续增加水边外圈流体余量、登车支撑检查；双车夹具强化为固定三格深坑和无下落断言，并在断言结束后清理测试弹体。失败/诊断日志随包保留。道路被移除的具体来源尚未唯一证明，不能宣称第三方延迟破坏已修复。

## 基础失败保留

{fail_list}

这些历史问题没有在本批修复。其他车型逐辆验证、复杂多层立交/大坡面滚转/拥堵车阵/正式服 TPS 尚未完成。LAV-AD 对地不开炮仍待武器选择专项；附件建议不当作正式服操作授权。实现与限制见 [陆地寻路记录](ground-navigation-4.19.md)。
'''
(repo/'docs/validation-4.19.md').write_text(text,encoding='utf-8')
p=repo/'README.md';s=p.read_text(encoding='utf-8-sig').replace('最终验收待完成，详见 [陆地寻路记录](docs/ground-navigation-4.19.md)。',result+'详见 [验证记录](docs/validation-4.19.md)。');p.write_text(s,encoding='utf-8')
p=repo/'AI_HARDNESS_PROGRESS.md';s=p.read_text(encoding='utf-8-sig').replace('最终验收待完成，详见 [陆地寻路记录](docs/ground-navigation-4.19.md)。',result+'详见 [验证记录](docs/validation-4.19.md)。');p.write_text(s,encoding='utf-8')
p=repo/'AGENTS.md';s=p.read_text(encoding='utf-8-sig').replace('最终验收待完成。',result+'详见 `docs/validation-4.19.md`。');p.write_text(s,encoding='utf-8')
p=repo/'SUPERB_WARFARE.md';s=p.read_text(encoding='utf-8-sig')+'\n4.19 最终验证：'+result+'详见 [验证记录](docs/validation-4.19.md)。\n';p.write_text(s,encoding='utf-8')
print(result)
