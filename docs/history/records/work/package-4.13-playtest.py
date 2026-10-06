from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import tomllib
import zipfile

root = Path(__file__).resolve().parent.parent
repo, out = root / 'outputs/TerminatorPlus-NeoForge', root / 'outputs'
build = root / 'work/ai-hardness-4.13-build.log'
full = root / 'work/ai-hardness-4.13-full.log'
warfare = root / 'work/ai-hardness-4.13-warfare-release-final.log'
checks = full.read_text(encoding='utf-8', errors='replace')
native = warfare.read_text(encoding='utf-8', errors='replace')
passes = len(re.findall(r'\[SelfTest\] PASS ', checks))
failures = re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)', checks)
known = {
    'teamdive same side native takeoffs spread before a common smash with near member first',
    'teamdive same side native takeoffs spread before a common smash with far member first',
}
assert passes + len(failures) == 128, (passes, failures)
assert set(failures).issubset(known), 'Unexpected functional failure: ' + str(failures)
assert len(re.findall(r'\[SelfTest\] PASS feedback ', checks)) == 6
assert len(re.findall(r'\[SelfTest\] PASS warfare ', native)) == 34
assert '[SelfTest] ALL 34 CHECKS PASSED' in native
assert not re.search(r'\[SelfTest\] FAIL ', native)
assert native.count('native delay=4 projectiles=16') == 2
budget_proof = re.search(r'Mixed budget actual projectile maximum=(\d+) total=(\d+) owners=(\d+)', native)
assert budget_proof and int(budget_proof[1]) <= 64 and int(budget_proof[2]) > 240 and int(budget_proof[3]) == 10
for log in [checks, native]:
    assert '[SelfTest] ---- summary ----' in log
    assert not re.search(r'\[SelfTest\] ERROR ', log)
    assert '[SelfTest] scenario check threw' not in log and '[SelfTest] scenario setup failed' not in log
    assert 'BUILD SUCCESSFUL' in log
assert 'BUILD SUCCESSFUL' in build.read_text(encoding='utf-8', errors='replace')
assert re.search(r'^neo_version=21\.1\.1$', (repo / 'gradle.properties').read_text(), re.M)

jar = repo / 'build/libs/TerminatorPlus-NeoForge-1.21.1-4.13.0-BETA.jar'
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    meta = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
    assert meta['mods'][0]['version'] == '4.13.0-BETA' and meta['mods'][0]['modId'] == 'terminatorplus'
    deps = meta['dependencies']['terminatorplus']
    assert any(d['modId'] == 'neoforge' and d['versionRange'] == '[21.1.1,)' for d in deps)
    assert any(d['modId'] == 'superbwarfare' and d['type'] == 'optional' and d['ordering'] == 'AFTER' for d in deps)
    assert any(a['file'] == 'META-INF/accesstransformer.cfg' for a in meta['accessTransformers'])
    assert archive.read('META-INF/accesstransformer.cfg')
    assert len([n for n in archive.namelist() if n.startswith('chatter/') and not n.endswith('/')]) >= 2
    assert not any(n.startswith(('com/atsuishio/', 'assets/superbwarfare/', 'data/superbwarfare/')) for n in archive.namelist())
    class_root = repo / 'build/classes/java/main'
    classes = list(class_root.rglob('*.class'))
    assert classes
    for class_file in classes:
        assert archive.read(class_file.relative_to(class_root).as_posix()) == class_file.read_bytes(), class_file
    for name in ['WarfareSupport', 'WarfareAccess', 'VehicleCrew', 'VehiclePilot', 'superbwarfare/SuperbWarfareAccess']:
        assert archive.read('net/nuggetmc/tplus/compat/' + name + '.class')

# Restore only the generated date in the isolated base server's properties.
head = subprocess.check_output(['git', 'show', 'HEAD:run-selftest/server.properties'], cwd=repo)
properties = repo / 'run-selftest/server.properties'
current_lines, head_lines = properties.read_bytes().splitlines(), head.splitlines()
assert current_lines[0] == head_lines[0] and current_lines[2:] == head_lines[2:]
properties.write_bytes(b'\r\n'.join(head_lines) + b'\r\n')

full_summary = f'{passes} PASS / {len(failures)} FAIL / 128 total'
full_zh = f'{passes} 项通过、{len(failures)} 项失败，共 128 项'
failure_text = '；'.join(failures) if failures else '无；历史偶发问题不视为已经修复'
shutil.copy2(jar, out / jar.name)
for source, name in [(build, 'ai-hardness-4.13.0-build.log'), (full, 'ai-hardness-4.13.0-selftest.log'),
                     (warfare, 'ai-hardness-4.13.0-warfare.log')]:
    shutil.copy2(source, out / name)
digest = hashlib.sha256(jar.read_bytes()).hexdigest().upper()
validation = f'''TerminatorPlus-NeoForge 4.13.0-BETA manual playtest
Validated: 2026-10-05
Minecraft: 1.21.1; Java: Microsoft OpenJDK 21.0.7
Final build and base regression: NeoForge 21.1.1 (minimum retained)
Native integration: NeoForge 21.1.249, Superb Warfare 0.8.9.1, Kotlin for Forge 5.12.0
SBW installed JAR/source commit: 5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816
Build: SUCCESSFUL
Base SelfTest: {full_summary}
Current base failure names: {failures or 'none'}
Native SelfTest: ALL 34 CHECKS PASSED (14 original gun, 7 movement/skills, 13 vehicle checks)
Mixed actual native projectile proof: max {budget_proof[1]} per tick, {budget_proof[2]} total, all {budget_proof[3]} owners served over at least 120 ticks.
Both mounted budget fixtures read native delay 4 and 16 projectiles; each emitted an actual 16-projectile burst.
Historical same-side team dive synchronization remains independently pending.
Jar: {jar.name}
Jar bytes: {jar.stat().st_size}
SHA256: {digest}
Jar inspection: CRC, version, minimum NeoForge, optional SBW ordering, explicit AT, chatter resources and every compiled main class byte confirmed.
No third-party classes or assets bundled. Source base: c1fcf77; changes uncommitted and unpushed.
Logs: ai-hardness-4.13.0-selftest.log, ai-hardness-4.13.0-warfare.log, ai-hardness-4.13.0-build.log

Gun feedback: real sideways movement at difficulties 4-10, closing shotgun distance, real owned pearl with gun restoration, movement during native reload, actual retreat/apple/regeneration and return to real firing, native elytra/fireworks, original level-five five-versus-five kits sixty blocks apart. A long recovery now briefly searches the prior observed location without reading hidden positions.
Vehicle checks: unique native crew seats and real M1A2 driving; both M1A2 weapon seats produce owned projectiles and damage; BMP-2 firing-port weapon with human driver protected; AH-6 native takeoff/travel/landing; AH-6 personal rifle in ground and actual airborne open seats; Mi-28 seat-one native cannon during actual flight; occupied/enemy seats and invalid commands rejected; airborne leave waits for landing; level-nine mounted finite six-round reserve; native BanHand seat cannot consume/fire a personal gun; mixed delayed mounted and immediate handheld budget; pending native volley preserves its selected gun when target boards armor, then switches after emission.
Vehicle physics and movement limits are native. Difficulty ten supplies native energy/reserve, retains native reload/heat, and does not repair health. Other vehicles using matching schemas are unverified. Complex terrain, high-speed aerial combat and countermeasure effectiveness require manual testing.
Guided-launcher native guidance, special fire procedures, fixed-wing/ship/airship/drone driving, zero-seat artillery, throwables, radar and 10EX remain unimplemented or unvalidated as described in SUPERB_WARFARE.md.
The projectile tests do not establish 100-bot TPS or asynchronous AI. All world/AI work remains on the server thread.
All tests used isolated self-test servers. The live server was not operated or modified. User observation of the original live five-versus-five scene remains pending; automated tests do not replace that acceptance.
'''
(out / 'validation-4.13.0.txt').write_text(validation, encoding='utf-8')

progress_file = repo / 'AI_HARDNESS_PROGRESS.md'
progress = progress_file.read_text(encoding='utf-8')
progress = progress.replace('更新：2026-10-05，4.12.0-BETA。', '更新：2026-10-05，4.13.0-BETA。', 1)
marker = '## 当前修正批次\n\n'
before, body = progress.split(marker, 1)
history = '### 4.12 历史枪械批次\n\n'
if history in body:
    body = body.split(history, 1)[1]
new = f'''4.13 已完成持枪实战反馈修正和本轮指定的载具乘员组、座位武器、地面驾驶、直升机起降及机上随身枪械，并封装内测包。最低 NeoForge 21.1.1 构建成功；无卓越前线完整回归为 **{full_zh}**，真实 0.8.9.1 / NeoForge 21.1.249 专项为 **34 项全部通过**。基础回归失败场景：{failure_text}。同向团队俯冲历史问题仍独立待修。

枪械只接管主手/瞄准/扳机，原有移动、导航和技能继续运行。新增 7 项移动/技能场景包含 4–10 横移、有效距离接近、珍珠、空地换弹移动、8 级真实撤退与金苹果恢复后重新开火、鞘翅，以及用户原配置的 5 级 5v5。较长恢复结束后短暂搜索实际观察过的位置，避免自己的掩体遮挡加上视线记忆到期造成停滞；不读取隐藏目标的新位置。原 4.12 的 14 项通过只证明当时覆盖的枪械流程，未覆盖持枪移动与技能，不能作为实战验收。

13 项载具场景实测 M1A2、BMP-2、AH-6 和 Mi-28：真实唯一座位、真人驾驶位保护、地面驾驶、各自座位原生弹丸与伤害、直升机起飞/前往/降落、空中开放座位步枪、请求离席后先落地、9 级有限弹药和禁止手持座位。混合延迟/立即射击至少持续 120 tick，实际每 tick 最多 **{budget_proof[1]}** 颗弹丸、累计 {budget_proof[2]} 颗、全部 10 个 owner 获得开火机会；延迟期间保持武器和座位，验证换目标后先完成原发射，再换反装甲炮。压力场景不检查伤害，伤害由其他真实原生场景独立验证。

其他普通枪械/载具通过相同运行时数据接入，但未逐项验证；复杂地形、高速空战及诱饵效果仍需人工内测。无人机、投掷物、间接火炮、雷达、军事喊话、10EX 与异线程实验仍待后续。没有部署正式服、提交或推送 GitHub。用户正式服原配置 5v5 目测验收仍待用户完成。用法见 `SUPERB_WARFARE.md`，实际结果见交付目录 `validation-4.13.0.txt`、`ai-hardness-4.13.0-selftest.log` 和 `ai-hardness-4.13.0-warfare.log`。

'''
progress = before + marker + new + history + body
progress = progress.replace('4.12 当前只交付卓越前线第一批枪械兼容内测包。', '4.13 当前交付持枪修正与载具乘员内测包。')
progress_file.write_text(progress, encoding='utf-8')

guide = f'''# 4.13.0-BETA 持枪与载具内测

适配 Minecraft 1.21.1、Java 21、Superb Warfare 0.8.9.1-final-mc1.21.1。你的 NeoForge 21.1.249 可继续使用；卓越前线自身要求至少 21.1.203。TerminatorPlus 单独运行仍支持 21.1.1。

关服后备份旧 JAR，替换 mods 内的 TerminatorPlus，避免同时放入两版。卓越前线及已有依赖保持原安装。此包没有捆绑第三方模组。本次没有操作你的正式服。

## 本轮改变

持枪继续移动和使用原有技能。4 级以上横移、换弹走动；按已有枪械的有效距离接近或后撤。珍珠、鞘翅、盾、重锤与进食按条件交接主手。8 级以上恢复后重新搜索被自己的掩体遮住的旧观察位置，找到目标后恢复开火。

同队机器人可组成乘员组，各自操作实际座位武器；可尝试地面驾驶和直升机起降。AH-6 等开放座位能使用真实背包中的随身枪，Mi-28 等有独立座位炮的乘员操作自己的炮。封闭/禁止手持/不能转头的座位禁止随身射击。驾驶仅使用原生输入，没有额外写位置、速度或升力。

## 配装与乘员

先在自己的真实背包里准备枪、备弹和其他装备，空出一格并保存。替换后的预设系统沿用原用法：

```mcfunction
/team add assault
/bot preset save warfare
/bot createpreset warfare Pilot 9 assault
/bot createpreset warfare Gunner 9 assault
/bot settings ability guns true
/bot settings ability vehicles true
/bot settings ability vehicleweapons true
/bot settings ability helicopters true
```

生成要等待皮肤请求完成，可用 `/bot count` 确认。将同乘真人也加入 assault 队伍。7–10 允许命令登车与地面驾驶，9–10 允许直升机驾驶；4–10 能操作固定火力的座位武器。9 级及以下须给载具实际燃料、武器弹药；10 级驾驶员补能源，10 级炮手补各自座位备弹，仍保留原生换弹、热量和射速，退出/降级清理赠送储备。

用卓越前线原有方法放出载具，让乘员站在附近：

```mcfunction
/bot vehicle crew Pilot nearest
/bot vehicle status Pilot
/bot vehicle status Gunner
```

单独指定座位：`/bot vehicle board Gunner nearest 1`；座位从 0 开始，以原生载具定义为准。`nearest` 找 32 格内可用载具，也可换成同维度 48 格内的实际载具 UUID。不会抢真人座位或隔墙强制登车；普通走路接近失败约 20 秒取消。

驾驶与离席示例，坐标请改成自己世界内已加载、适合停车或降落的位置：

```mcfunction
/bot vehicle go Pilot 120 -60 80
/bot vehicle leave Gunner
/bot info Pilot
```

直升机离席请求先尝试降落；真人驾驶时等待真人落地。选择敌人用原有目标模式，例如 `/bot settings setgoal nearestenemy`（同队对战）或 `nearestvulnerableplayer`（攻击可受伤玩家）。

## 建议人工复测

1. 复现反馈中的 5 级 5v5：两队相距 60 格，各带 AK-47、AA-12、128 发步枪弹、64 发霰弹及 5 级默认装备。观察横移、追近、珍珠/鞘翅和换弹移动。
2. 8–10 持枪时受伤，观察真实撤退、进食和重新投入战斗，确认没有长时间原地卡住。
3. M1A2 和 BMP-2 同队分座，观察各炮手是否只操作自己的武器，真人驾驶位是否得到保留。
4. AH-6 的 9–10 级驾驶员起飞、前往平坦落点并降落；开放侧座放实际步枪，观察机上开火。Mi-28 检查 1 号位机炮。
5. 空中请求乘员离席，确认先降落；9 级载具有限弹药用尽后停火。先用空旷平坦地点，再试自己的地形。

## 测试结果与范围

真实卓越前线专项 **34 项全部通过**；无该模组的完整基础回归 **{full_zh}**。基础失败场景：{failure_text}。同向团队俯冲历史问题仍待单独修复。实际日志和 JAR SHA256 见 `validation-4.13.0.txt`。

已实测 AK-47、AWM、AA-12、RPG、M1A2、BMP-2、AH-6 与 Mi-28。其他同 API 装备尚未逐项验证；复杂地形起降、高速空战、实际制导/诱饵效果尚未完成专门验收。发射前友军检查不能阻止队友后来走进已经发出的弹丸路径。弹丸预算测试不代表百人 TPS；没有异线程 AI。

无人机、投掷物、间接火炮、雷达、固定翼/船只/飞艇驾驶与 10EX 尚未完成。本轮指定的乘员与直升机功能已可内测。隔离 5v5 已通过自动检查，正式服的目测验收仍需你实际体验。完整机制与能力开关见 `SUPERB_WARFARE.md`。
'''
(out / '4.13.0-内测说明.md').write_text(guide, encoding='utf-8')
package = out / 'TerminatorPlus-NeoForge-4.13.0-内测包.zip'
contents = [(out / jar.name, jar.name), (out / '4.13.0-内测说明.md', '内测说明.md')]
contents += [(repo / n, n) for n in ['README.md', 'AGENTS.md', 'AI_HARDNESS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md', 'docs/ai-hardness-handoff.md', 'docs/superbwarfare-compat-notes.md', 'docs/feedback-4.12-guns.md']]
contents += [(out / n, n) for n in ['validation-4.13.0.txt', 'ai-hardness-4.13.0-build.log', 'ai-hardness-4.13.0-selftest.log', 'ai-hardness-4.13.0-warfare.log']]
with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
    for source, name in contents: archive.write(source, name)
with zipfile.ZipFile(package) as archive:
    assert archive.testzip() is None and archive.read(jar.name) == jar.read_bytes()
assert (out / jar.name).read_bytes() == jar.read_bytes()
print(json.dumps({'jar': jar.name, 'jar_bytes': jar.stat().st_size, 'sha256': digest,
                  'package_bytes': package.stat().st_size, 'zip_sha256': hashlib.sha256(package.read_bytes()).hexdigest().upper(),
                  'base': full_summary, 'native': 'ALL 34 CHECKS PASSED', 'classes_verified': len(classes)}, ensure_ascii=True))
