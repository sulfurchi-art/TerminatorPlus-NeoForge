from pathlib import Path
import hashlib,json,re,shutil,subprocess,tomllib,zipfile
root=Path.cwd();repo=root/'outputs/TerminatorPlus-NeoForge';out=root/'outputs'
checks=root/'work/ai-hardness-4.16-ordnance-release-final.log';build=root/'work/ai-hardness-4.16-build.log';partial=root/'work/ai-hardness-4.16-warfare-full.log'
c=checks.read_text(encoding='utf-8',errors='replace');b=build.read_text(encoding='utf-8',errors='replace');p=partial.read_text(encoding='utf-8',errors='replace')
assert '[SelfTest] ALL 12 CHECKS PASSED' in c and len(re.findall(r'\[SelfTest\] PASS warfare ordnance ',c))==12
assert 'BUILD SUCCESSFUL' in c and 'BUILD SUCCESSFUL' in b
assert '[SelfTest] FAIL ' not in c and 'scenario check threw' not in c and 'scenario setup failed' not in c and 'Native ordnance disabled' not in c
partial_passes=len(re.findall(r'\[SelfTest\] PASS ',p));partial_failures=re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)',p)
props=(repo/'gradle.properties').read_text();assert re.search(r'^neo_version=21\.1\.1$',props,re.M) and re.search(r'^mod_version=4\.16\.0-BETA$',props,re.M)
jar=repo/'build/libs/TerminatorPlus-NeoForge-1.21.1-4.16.0-BETA.jar'
with zipfile.ZipFile(jar) as z:
 assert z.testzip() is None
 meta=tomllib.loads(z.read('META-INF/neoforge.mods.toml').decode('utf-8'))
 assert meta['mods'][0]['version']=='4.16.0-BETA'
 deps=meta['dependencies']['terminatorplus'];assert any(d['modId']=='neoforge' and d['versionRange']=='[21.1.1,)' for d in deps)
 assert any(d['modId']=='superbwarfare' and d['type']=='optional' and d['ordering']=='AFTER' for d in deps)
 assert any(a['file']=='META-INF/accesstransformer.cfg' for a in meta['accessTransformers']) and z.read('META-INF/accesstransformer.cfg')
 assert not any(n.startswith(('com/atsuishio/','assets/superbwarfare/','data/superbwarfare/')) for n in z.namelist())
 assert len([n for n in z.namelist() if n.startswith('chatter/') and not n.endswith('/')])>=2
 classroot=repo/'build/classes/java/main';classes=list(classroot.rglob('*.class'));assert classes
 for cls in classes: assert z.read(cls.relative_to(classroot).as_posix())==cls.read_bytes(),cls
 for name in ('api/agent/legacyagent/skill/WarfareTactics','compat/WarfareItems','compat/superbwarfare/NativeOrdnance'):
  assert z.read('net/nuggetmc/tplus/'+name+'.class')
# Preserve known previous artifacts and tracked isolated-server properties.
for name,digest in [('TerminatorPlus-NeoForge-1.21.1-4.14.0-BETA.jar','2E942044B824EB8CBA3F8A134A60A0AFF998A05432DE1687B2DB8CB1ADA849AB'),('TerminatorPlus-NeoForge-1.21.1-4.15.0-BETA.jar','813E565136A4E836B89840A529AA398106106CBF715D846B31D4A27B110DB80F')]:
 assert hashlib.sha256((out/name).read_bytes()).hexdigest().upper()==digest
assert hashlib.sha256((out/'TerminatorPlus-NeoForge-4.15.0-内测包.zip').read_bytes()).hexdigest().upper()=='54CC2FD581ED8A059F07F0F0A2B317CA4328A2BFADBE79318443103F256949EA'
head=subprocess.check_output(['git','show','HEAD:run-selftest/server.properties'],cwd=repo);propfile=repo/'run-selftest/server.properties'
if propfile.read_bytes()!=head:
 a,h=propfile.read_bytes().splitlines(),head.splitlines();assert a[0]==h[0] and a[2:]==h[2:];propfile.write_bytes(head)
progress=repo/'AI_HARDNESS_PROGRESS.md';s=progress.read_text();assert 'FINAL_416_VALIDATION' in s
s=s.replace('FINAL_416_VALIDATION','最低 NeoForge 21.1.1 构建成功；最终 0.8.9.1 原生快速自测 **12/12 全部通过**。JAR CRC、元数据和全部编译类逐字节核对通过。已封装本地 4.16.0-BETA 内测包。');progress.write_text(s,encoding='utf-8')
jarhash=hashlib.sha256(jar.read_bytes()).hexdigest().upper();shutil.copy2(jar,out/jar.name)
for source,name in [(checks,'ai-hardness-4.16.0-ordnance-quick.log'),(build,'ai-hardness-4.16.0-build.log'),(partial,'ai-hardness-4.16.0-warfare-preseal-partial.log')]:shutil.copy2(source,out/name)
validation=f'''TerminatorPlus-NeoForge 4.16.0-BETA manual playtest
Validated: 2026-10-05
Minecraft 1.21.1 / Microsoft OpenJDK 21.0.7
Build: SUCCESSFUL; minimum NeoForge 21.1.1
Final native quick test: ALL 12 CHECKS PASSED
Native runtime: NeoForge 21.1.249 / Superb Warfare 0.8.9.1 / Kotlin for Forge 5.12.0
SBW locked source commit: 5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816
Base full regression (130 checks): NOT RERUN for this package.
Native full regression (now 73 checks): NOT COMPLETED for this package.
A pre-final-forecast-adjustment native run was stopped at the user's packaging request: {partial_passes} partial PASS, {len(partial_failures)} partial FAIL ({partial_failures}). It is a process log, not final-package full validation.
An attempted quick invocation before the earlier JVM exited failed to obtain the isolated-world lock; no summary was emitted. Final quick run started after that test JVM stopped and completed all 12 checks.
Previous 4.15 full results (129/130 base, 61/61 native) are historical only and do not certify 4.16.
Known prior issues: same-side TeamDive synchronization; intermittent archer timeout not declared fixed.
No production server operated, no deployment, commit or push. YSM not implemented.
No extra native drone/vehicle movement, health or weapon damage stats; public native controls/items/explosions only.
Native drone loading debits inventory even for ten; deployed payload capacity and per-drop debit remain native.
New abilities: drones levels 8-10, c4 levels 9-10. Only default warfare ten has electric baton and infinite native FE.
Jar: {jar.name}
Jar bytes: {jar.stat().st_size}
SHA256: {jarhash}
Verified: CRC, metadata, minimum NeoForge, explicit AT, optional SBW AFTER, chatter resources, every compiled class byte ({len(classes)} classes), no third-party classes/assets.
Previous 4.14 and 4.15 JARs and 4.15 ZIP preserved with exact known SHA256.
''';(out/'validation-4.16.0.txt').write_text(validation,encoding='utf-8')
guide=f'''# 4.16.0-BETA 无人机 / C4 / 电棍内测包

Minecraft 1.21.1、Java 21；Superb Warfare 0.8.9.1-final-mc1.21.1。安装卓越前线时按其要求使用 NeoForge 21.1.203+，本次兼容快测为 21.1.249；TerminatorPlus 单独运行最低仍为 21.1.1。

关服后备份旧 JAR，将 mods 中 TerminatorPlus 替换为包内 JAR，避免两版并存。卓越前线与依赖保留原安装。本包未捆绑第三方模组，没有部署或操作你的正式服。

## 本轮内容

- 8–10 无人机：真实部署、挂载、显示器链接、原生飞行、自爆/投弹；步兵优先 40mm 投弹，载具优先 C4 自爆。使用真实库存，原生装载仍扣物品；无人机最多同时 8 名操作者，任务有时限，受伤/关闭能力/降级/换装会退出。
- 9–10 C4：实际鞘翅低空通过，投放遥控 C4，继续拉离至安全位置，再用真实起爆器引爆。起爆器会引爆该机器人所有遥控 C4，因此逐颗检查自己与队友距离，危险时延迟或放弃本轮。
- 卓越前线配装：按级枪械；原生防弹头盔/胸甲；默认 1–9 不再带刀剑，10 级近战为永远满电、开启电击的原生电棍。原版剑、弓、重锤不加入 warfare 套装。原版独立套装及明确保存的自定义预设保留。
- 4.15 的每枪独立装弹冷却、真实备弹、载具乘员和直升机修正继续保留。

## 启用

```mcfunction
/bot preset use none
/bot settings defaultgear true
/bot settings gear warfare
/bot settings hardness 10
/bot create Elite
```

给已有机器人换装与设置等级：

```mcfunction
/bot inventory default warfare 10 Elite
/bot settings hardness 10 Elite
/bot info Elite
```

无人机能力是 `drones`，C4 能力是 `c4`；可用 `/bot settings ability drones false` 或 `/bot settings ability c4 false` 关闭。8 级配装已有投弹无人机；9 起配 C4/起爆器。库存预设请保留真实无人机/显示器/挂载，C4 飞行还需鞘翅/烟花。原生爆炸会破坏地形，内测可先选隔离场地。

## 验证范围

最低版本构建成功；本轮最终原生快速测试 **12/12 通过**，包括真正的飞行/投弹/爆炸/伤害/消耗、移动目标提前量、友军安全、降级/清背包恢复、真实防弹减伤及连续满电电击。已核对 JAR 元数据、CRC 和全部 {len(classes)} 个编译类。

按你“先封包”的要求暂停长回归。**本包未重跑 130 项基础完整回归，也未完成现在的 73 项原生完整专项**。封包前阶段代码长回归日志已保留（{partial_passes} 项部分通过、{len(partial_failures)} 项部分失败），不当作当前包完整通过。4.15 的历史结果也不代替本包验证。

历史同向团队俯冲及偶发弓手超时未修复；复杂地形、快速移动载具、多人空战和多无人机协同需人工内测。无人机挂载仍遵守原生真实库存扣除，包括 10 级；队友突然冲进爆炸范围或外部引爆仍可能造成原生伤害。YSM 尚未加入，手持烟雾/手雷/TM62/装甲板其他自动使用、低级乘员、补给指令和整个 10EX 不标完成。

日志、validation 和 SHA256 与本说明随包提供，详细用法见 SUPERB_WARFARE.md。旧 4.14 / 4.15 产物已保留。

JAR SHA256：`{jarhash}`
''';(out/'4.16.0-内测说明.md').write_text(guide,encoding='utf-8')
package=out/'TerminatorPlus-NeoForge-4.16.0-内测包.zip'
docs=['README.md','AGENTS.md','AI_HARDNESS.md','AI_HARDNESS_PROGRESS.md','SUPERB_WARFARE.md','docs/ysm-compat-review.md','docs/playtest-report-status-4.15.md']
contents=[(out/jar.name,jar.name),(out/'4.16.0-内测说明.md','内测说明.md')]+[(repo/name,name) for name in docs]
contents+=[(out/name,name) for name in ['validation-4.16.0.txt','ai-hardness-4.16.0-build.log','ai-hardness-4.16.0-ordnance-quick.log','ai-hardness-4.16.0-warfare-preseal-partial.log']]
contents+=[(root/'work/ai-hardness-4.16-ordnance-release.log','process/aborted-world-lock.log')]
with zipfile.ZipFile(package,'w',zipfile.ZIP_DEFLATED) as z:
 for source,name in contents:z.write(source,name)
with zipfile.ZipFile(package) as z:assert z.testzip() is None and z.read(jar.name)==jar.read_bytes()
assert (out/jar.name).read_bytes()==jar.read_bytes()
zh=hashlib.sha256(package.read_bytes()).hexdigest().upper();(out/'SHA256-4.16.0.txt').write_text(f'{jarhash}  {jar.name}\n{zh}  {package.name}\n',encoding='utf-8')
print(json.dumps({'jar':str(out/jar.name),'zip':str(package),'jar_bytes':jar.stat().st_size,'zip_bytes':package.stat().st_size,'jar_sha256':jarhash,'zip_sha256':zh,'classes_verified':len(classes),'quick':'12/12 passed','preseal_partial_passes':partial_passes,'preseal_partial_failures':partial_failures},ensure_ascii=False))
