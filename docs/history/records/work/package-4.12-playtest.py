from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import tomllib
import zipfile

root = Path(__file__).resolve().parent.parent
repo = root / 'outputs/TerminatorPlus-NeoForge'
out = root / 'outputs'
build = root / 'work/ai-hardness-4.12-build.log'
full = root / 'work/ai-hardness-4.12-full.log'
warfare = root / 'work/ai-hardness-4.12-warfare-validated.log'
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
assert len(re.findall(r'\[SelfTest\] PASS warfare ', native)) == 14
assert '[SelfTest] ALL 14 CHECKS PASSED' in native
for text in [checks, native]:
    assert '[SelfTest] ---- summary ----' in text
    assert not re.search(r'\[SelfTest\] ERROR ', text)
    assert 'BUILD SUCCESSFUL' in text
assert 'BUILD SUCCESSFUL' in build.read_text(encoding='utf-8', errors='replace')
assert re.search(r'^neo_version=21\.1\.1$', (repo / 'gradle.properties').read_text(), re.M)

jar = repo / 'build/libs/TerminatorPlus-NeoForge-1.21.1-4.12.0-BETA.jar'
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    meta = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
    assert meta['mods'][0]['version'] == '4.12.0-BETA'
    assert meta['mods'][0]['modId'] == 'terminatorplus'
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
        name = class_file.relative_to(class_root).as_posix()
        assert archive.read(name) == class_file.read_bytes(), name
    for name in ['WarfareSupport', 'WarfareAccess', 'superbwarfare/SuperbWarfareAccess']:
        assert archive.read('net/nuggetmc/tplus/compat/' + name + '.class')

# Restore only the self-test server's generated timestamp; preserve unexpected settings for review.
head = subprocess.check_output(['git', 'show', 'HEAD:run-selftest/server.properties'], cwd=repo)
properties = repo / 'run-selftest/server.properties'
current_lines, head_lines = properties.read_bytes().splitlines(), head.splitlines()
assert current_lines[0] == head_lines[0] and current_lines[2:] == head_lines[2:]
properties.write_bytes(b'\r\n'.join(head_lines) + b'\r\n')

full_summary = f'{passes} PASS / {len(failures)} FAIL / 128 total'
full_zh = f'{passes} 项通过、{len(failures)} 项失败，共 128 项'
failure_text = ', '.join(failures) if failures else '无；历史偶发问题不视为已经修复'
shutil.copy2(jar, out / jar.name)
for source, name in [(build, 'ai-hardness-4.12.0-build.log'), (full, 'ai-hardness-4.12.0-selftest.log'),
                     (warfare, 'ai-hardness-4.12.0-warfare.log')]:
    shutil.copy2(source, out / name)
digest = hashlib.sha256(jar.read_bytes()).hexdigest().upper()
validation = f'''TerminatorPlus-NeoForge 4.12.0-BETA manual playtest
Validated: 2026-10-05
Minecraft: 1.21.1
Java: Microsoft OpenJDK 21.0.7
Final build and vanilla regression: NeoForge 21.1.1 (minimum retained)
Native warfare integration: NeoForge 21.1.249, Superb Warfare 0.8.9.1 and Kotlin for Forge 5.12.0
SBW installed JAR/source commit: 5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816
Build: SUCCESSFUL
Vanilla SelfTest: {full_summary}
Current vanilla failure names: {failures or 'none'}
Native warfare SelfTest: ALL 14 CHECKS PASSED
Historical same-side team dive synchronization remains pending; this batch does not claim it is fixed.
Jar: {jar.name}
Jar bytes: {jar.stat().st_size}
SHA256: {digest}
Jar inspection: archive integrity, exact version, minimum NeoForge, optional SBW ordering, explicit AT, chatter resources and every compiled main class byte confirmed.
No third-party mod classes or assets are bundled.
Source base: c1fcf77; local changes remain uncommitted and unpushed.
Full functional log: ai-hardness-4.12.0-selftest.log
Native integration log: ai-hardness-4.12.0-warfare.log
Build log: ai-hardness-4.12.0-build.log

Validated native scenarios: exact-version API loading; finite AK-47 ammo/reload and real attributed damage; six loaded rounds stop at six; difficulty 10 replenishes native reserve but preserves a real reload gap; ability disable returns the same gun and resumes native sword damage; AWM bolt cadence and real damage; AA-12/AWM distance switching; firing stops behind an opaque wall and last-seen pursuit continues; allied obstruction blocks fire; real golden-apple use restores the same owned gun; RPG selected over an ineffective rifle damages a real occupied M1A2; live GunProp overrides control RPM/magazine/reload; twelve AA-12 bots stay within a global 64-projectile-per-tick budget with all owners served; low-level bounded turning still allows actual shots.

This release adds gun operation at difficulties 1-10 and anti-vehicle weapon selection for existing hostile riders. It does not implement 10EX, vehicle driving/crew, empty-vehicle acquisition, drones, throwables or military chat. Guided-launcher constraints are implemented but Javelin/IGLA native guidance and countermeasures are not validated. Ordinary supported guns share the native API, but only the listed guns were tested. Safety checks occur before firing and cannot prevent every later collision with a moving teammate.
The twelve-bot projectile test does not establish 100-bot TPS or asynchronous behavior. All AI/world access remains on the server thread. The broader checklist and async experiments remain paused. All tests used isolated self-test servers; the user's live server was not modified or operated.
'''
(out / 'validation-4.12.0.txt').write_text(validation, encoding='utf-8')

progress_file = repo / 'AI_HARDNESS_PROGRESS.md'
progress = progress_file.read_text(encoding='utf-8')
progress = progress.replace('更新：2026-10-05，4.11.0-BETA。', '更新：2026-10-05，4.12.0-BETA。', 1)
marker = '## 当前修正批次\n\n'
assert marker in progress
before, body = progress.split(marker, 1)
history_marker = '### 4.11 历史反馈批次\n\n'
if history_marker in body:
    body = body.split(history_marker, 1)[1]
    progress = before + marker + body
new = f'''4.12 已完成卓越前线 **0.8.9.1** 的第一批可选兼容并封装内测包。最低版本 NeoForge 21.1.1 构建成功，未安装卓越前线的完整回归为 **{full_zh}**；真实卓越前线 JAR / NeoForge 21.1.249 的专项测试 **14 项全部通过**。基础回归失败场景：{failure_text}。4.10 的同向团队俯冲历史问题仍独立待修。

本批实现 1–10 级真实枪械开火、换弹、拉栓、开镜、误差与受限转身、按距离选武器、有限库存消耗、10 级备弹补充、换弹时附近掩体、友军发射前检查，以及针对敌方乘员所在载具的已有武器比较。已实测 AK-47、AWM、AA-12、RPG 和真实 M1A2；12 名 AA-12 机器人验证每 tick 64 颗弹丸的总预算与轮换，但没有百人性能结论。进食和关闭能力后的真实枪械引用/组件恢复也已验证。

标枪/IGLA 实际制导、听枪声、对方换弹窗口、特殊蓄力武器、投掷物、空载具自动索敌、驾驶/乘员组、无人机、军事台词及 10EX 仍未完成。此批不是设计笔记全部能力的完成版。原大清单和异线程实验继续暂缓；没有部署正式服或提交 GitHub。用法见 `SUPERB_WARFARE.md`，结果与日志见交付目录 `validation-4.12.0.txt`、`ai-hardness-4.12.0-selftest.log`、`ai-hardness-4.12.0-warfare.log`。

### 4.11 历史反馈批次

'''
progress = progress.replace(marker, marker + new, 1)
progress = progress.replace('- 原大清单继续暂缓；当前按 2026-10-05 的人工内测反馈修正飞行频率和行动停滞，并交付新的内测包。',
    '- 原大清单继续暂缓；4.11 已处理人工反馈，4.12 当前只交付卓越前线第一批枪械兼容内测包。')
progress_file.write_text(progress, encoding='utf-8')

guide = f'''# 4.12.0-BETA 卓越前线兼容内测包

对应你的 **Superb Warfare 0.8.9.1-final-mc1.21.1**，适用 Minecraft 1.21.1 / Java 21。卓越前线要求 NeoForge 至少 21.1.203，你的 21.1.249 可用；TerminatorPlus 不安装卓越前线时仍兼容 21.1.1。

关服后保留旧 JAR 的备份，替换 `mods` 中的 TerminatorPlus，避免同时存在两版，然后启动。卓越前线和它已有的依赖不用更换；包中没有捆绑这些第三方模组。

## 配装与开始对战

推荐在自己的真实背包中准备好枪械、配件、弹匣和备弹，空出一格，再保存预设并生成：

```mcfunction
/bot preset save warfare
/bot createpreset warfare RifleBot 7 none
/bot settings ability guns true
/bot settings setgoal nearestvulnerableplayer
/bot info RifleBot
```

生成需要等待皮肤请求完成，可用 `/bot count` 确认。想测其他等级，将 `7` 改成 `1` 到 `10`。同队免被索敌使用原版 scoreboard 队伍；队伍对战可改为 `nearestenemy`。

给已经生成的全部机器人发步枪和有限备弹：

```mcfunction
/bot inventory give superbwarfare:ak_47 1
/bot inventory give superbwarfare:rifle_ammo 64
```

枪必须放在真实背包中，不要用 `/bot give` 配枪。`/bot info <名字>` 的 Warfare 行显示兼容状态、开火计数、弹匣、换弹和开镜状态。用 `/bot settings ability guns false` 关闭枪械动作。

## 本批效果

1–3 身体瞄准、误差大且转身慢；4–6 按距离选已有武器、开镜点射，换弹尝试附近掩体；7–9 尝试瞄头、提前量、重力补偿和安全提前换弹。10 提高控制精度并补充备弹，但仍需要真实换弹和拉栓，枪械伤害、射速和机器人运动上限不变。

敌对玩家/生物乘坐载具时，4–10 比较已有武器的实际减伤结果；有 RPG 时可对坦克使用 RPG。开火前检查友军射线和爆炸附近的友军。全体每 tick 最多发射 64 颗弹丸，避免多人霰弹枪无限堆积；运动中的友军仍可能进入已经发出的弹丸路径。

可重点人工复测：相同配装的 1/7/10 枪法差异；1–9 有限备弹是否用尽；10 换弹时是否存在空窗；近身与远距离切换；遮挡后停火搜索；金苹果与枪械切换；敌方乘坐坦克时的 RPG 选择。

## 验证与边界

真实卓越前线专项 **14 项全部通过**；未安装该模组的完整回归 **{full_zh}**。本批新增无模组兼容检查和原 4.11 六项反馈场景均通过。基础回归失败场景：{failure_text}。历史同向团队俯冲失步仍待单独修复。

已实测 AK-47、AWM、AA-12、RPG 与 M1A2，其余普通枪械共享 API，但尚未逐枪验证。标枪/IGLA 的实际制导与诱饵反制没有专门实测。尚未实现特殊蓄力武器、驾驶/乘员组、无人机、投掷物、军事台词或 10EX；当前仍为 1–10 难度。

12 人霰弹枪测试验证弹丸预算和轮换，不代表百人 TPS 已达标；没有异线程 AI。本次没有操作你的正式服。详细行为见 `SUPERB_WARFARE.md`，完整测试日志与产物 SHA256 见 `validation-4.12.0.txt`。
'''
(out / '4.12.0-内测说明.md').write_text(guide, encoding='utf-8')
package = out / 'TerminatorPlus-NeoForge-4.12.0-内测包.zip'
contents = [(out / jar.name, jar.name), (out / '4.12.0-内测说明.md', '内测说明.md')]
contents += [(repo / n, n) for n in ['README.md', 'AGENTS.md', 'AI_HARDNESS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md', 'docs/ai-hardness-handoff.md', 'docs/superbwarfare-compat-notes.md']]
contents += [(out / n, n) for n in ['validation-4.12.0.txt', 'ai-hardness-4.12.0-build.log', 'ai-hardness-4.12.0-selftest.log', 'ai-hardness-4.12.0-warfare.log']]
with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
    for source, name in contents:
        archive.write(source, name)
with zipfile.ZipFile(package) as archive:
    assert archive.testzip() is None
    assert archive.read(jar.name) == jar.read_bytes()
assert (out / jar.name).read_bytes() == jar.read_bytes()
print(json.dumps({'jar': jar.name, 'jar_bytes': jar.stat().st_size, 'sha256': digest,
                  'package_bytes': package.stat().st_size, 'zip_sha256': hashlib.sha256(package.read_bytes()).hexdigest().upper(),
                  'vanilla': full_summary, 'native': 'ALL 14 CHECKS PASSED', 'classes_verified': len(classes)}, ensure_ascii=True))
