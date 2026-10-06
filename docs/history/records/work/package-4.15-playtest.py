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
build = root / 'work/ai-hardness-4.15-build.log'
full = root / 'work/ai-hardness-4.15-full.log'
warfare = root / 'work/ai-hardness-4.15-warfare-release-final.log'
checks = full.read_text(encoding='utf-8', errors='replace')
native = warfare.read_text(encoding='utf-8', errors='replace')
first_base = (root / 'work/ai-hardness-4.15-full-first.log').read_text(encoding='utf-8', errors='replace')
first_failures = re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)', first_base)
assert first_failures == ['cooperation ten assigns bait flank and archer and fires repeated native arrows from range']
for focus in ['target'] + [f'repeat-{i}' for i in range(1, 4)]:
    focused = (root / f'work/ai-hardness-4.15-archer-{focus}.log').read_text(encoding='utf-8', errors='replace')
    assert '[SelfTest] ALL 1 CHECKS PASSED' in focused and 'BUILD SUCCESSFUL' in focused
passes = len(re.findall(r'\[SelfTest\] PASS ', checks))
failures = re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)', checks)
known = {
    'teamdive same side native takeoffs spread before a common smash with near member first',
    'teamdive same side native takeoffs spread before a common smash with far member first',
}
assert passes + len(failures) == 130, (passes, failures)
assert set(failures).issubset(known), 'Unexpected base failure: ' + str(failures)
assert (f'[SelfTest] {len(failures)} of 130 checks FAILED:' in checks) if failures else ('[SelfTest] ALL 130 CHECKS PASSED' in checks)
assert len(re.findall(r'\[SelfTest\] PASS feedback ', checks)) == 6
assert '[SelfTest] PASS equipment vanilla levels progressively enchant and level ten has a legal maximum OP kit' in checks
assert '[SelfTest] PASS equipment border infantry retreats heals and navigates with an inward margin' in checks
assert len(re.findall(r'\[SelfTest\] PASS warfare ', native)) == 61
assert len(re.findall(r'\[SelfTest\] PASS warfare cooldown ', native)) == 4
assert len(re.findall(r'\[SelfTest\] PASS warfare flight feedback ', native)) == 11
assert '[SelfTest] ALL 61 CHECKS PASSED' in native
assert not re.search(r'\[SelfTest\] FAIL ', native)
assert native.count('native delay=4 projectiles=16') == 2
budget = re.search(r'Mixed budget actual projectile maximum=(\d+) total=(\d+) owners=(\d+)', native)
assert budget and int(budget[1]) <= 64 and int(budget[2]) > 240 and int(budget[3]) == 10
for log in (checks, native):
    assert '[SelfTest] ---- summary ----' in log and 'BUILD SUCCESSFUL' in log
    assert not re.search(r'\[SelfTest\] ERROR ', log)
    assert '[SelfTest] scenario check threw' not in log and '[SelfTest] scenario setup failed' not in log
assert 'BUILD SUCCESSFUL' in build.read_text(encoding='utf-8', errors='replace')
props = (repo / 'gradle.properties').read_text()
assert re.search(r'^neo_version=21\.1\.1$', props, re.M)
assert re.search(r'^mod_version=4\.15\.0-BETA$', props, re.M)

jar = repo / 'build/libs/TerminatorPlus-NeoForge-1.21.1-4.15.0-BETA.jar'
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    meta = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
    assert meta['mods'][0]['version'] == '4.15.0-BETA' and meta['mods'][0]['modId'] == 'terminatorplus'
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
    for f in classes:
        assert archive.read(f.relative_to(class_root).as_posix()) == f.read_bytes(), f
    for name in ('WarfareSupport', 'WarfareAccess', 'VehicleCrew', 'VehiclePilot', 'superbwarfare/SuperbWarfareAccess'):
        assert archive.read('net/nuggetmc/tplus/compat/' + name + '.class')

# Preserve the tracked isolated-server properties, after checking only its generated timestamp changed.
head = subprocess.check_output(['git', 'show', 'HEAD:run-selftest/server.properties'], cwd=repo)
properties = repo / 'run-selftest/server.properties'
current_lines, head_lines = properties.read_bytes().splitlines(), head.splitlines()
assert current_lines[0] == head_lines[0] and current_lines[2:] == head_lines[2:]
properties.write_bytes(head)

full_summary = f'{passes} PASS / {len(failures)} FAIL / 130 total'
full_zh = f'{passes} 项通过、{len(failures)} 项失败，共 130 项'
failure_text = '；'.join(failures) if failures else '本次无；历史同向团队俯冲偶发失步仍待单独修复'
digest = hashlib.sha256(jar.read_bytes()).hexdigest().upper()
shutil.copy2(root / 'work/ai-hardness-4.15-full-first.log', out / 'ai-hardness-4.15.0-selftest-first.log')
for i, focus in enumerate(['target'] + [f'repeat-{i}' for i in range(1, 4)], 1):
    shutil.copy2(root / f'work/ai-hardness-4.15-archer-{focus}.log', out / f'ai-hardness-4.15.0-archer-focus-{i}.log')
shutil.copy2(jar, out / jar.name)
for source, name in ((build, 'ai-hardness-4.15.0-build.log'), (full, 'ai-hardness-4.15.0-selftest.log'),
                     (warfare, 'ai-hardness-4.15.0-warfare.log')):
    shutil.copy2(source, out / name)

validation = f'''TerminatorPlus-NeoForge 4.15.0-BETA manual playtest
Validated: 2026-10-05
Minecraft 1.21.1; Microsoft OpenJDK 21.0.7
Final build and base regression: minimum NeoForge 21.1.1
Native integration: NeoForge 21.1.249, Superb Warfare 0.8.9.1, Kotlin for Forge 5.12.0
SBW locked source commit: 5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816
Build: SUCCESSFUL
Base SelfTest: {full_summary}
Current base failure names: {failures or 'none'}
First base run: 129 PASS / 1 FAIL, {first_failures}. Retained as ai-hardness-4.15.0-selftest-first.log.
Archer focused reruns: four independent ALL 1 CHECKS PASSED, retained as archer-focus-1..4.log.
The initial archer timeout is not declared fixed; diagnostic logging only was added. Final complete result is reported separately above.
Native SelfTest: ALL 61 CHECKS PASSED (46 existing and 15 new)
Mixed native projectiles: max {budget[1]} per tick, {budget[2]} total, all {budget[3]} owners served.
Both mounted budget fixtures: native delay 4, 16 projectiles, actual owned bursts.
Jar: {jar.name}
Jar bytes: {jar.stat().st_size}
SHA256: {digest}
Jar validation: CRC, version, minimum NeoForge, optional SBW ordering, explicit AT, chatter resources, every compiled main class byte ({len(classes)} classes).
No third-party classes or assets bundled. Changes are uncommitted and unpushed.

New checks: independent real duplicate-gun reload deadlines; native normal/empty runtime override; finite ammo debit only at completion; real apple/pearl/elytra during reload; ready backup rifle actual damage before first gun reload ends; pure-gun level ten actual continued damage under mild hits; AH6/M1A2/BMP tail boarding; AH6 nose-cannon actual projectile/damage then extension; real native non-hover transit; observed last-position return; actual helicopter border containment; unique second-vehicle crew reservations; native anti-air flank approach and locked missile finite flare consumption; actual both side-bench rifle damage; roof-target separation and native damage.
Existing 5v5 starts with two empty guns: all ten owners emit native projectiles, move, and preserve offensive pearls/fireworks inside effective rifle range.
Production reload/bolt clocks are keyed by actual ItemStack identity. Other hand actions do not interrupt or reset them. Reload completion calls SBW native handlers for finite inventory debit and legal chamber rules. Vehicle reload remains native. Sounds use native SoundInfo events broadcast to nearby players.
Low-pressure gun combat yields less often; emergency recovery remains. Effective range selection avoids a ready short-range shotgun overriding a cooling rifle at sixty blocks. Level-ten gifted reserve is revoked before due reload on downgrade/disable.
Vehicle physics remain native keys/mouse. No production writes to vehicle position/velocity/rotation. Flares are finite even at difficulty ten. Native blast safety and heat/delay/projectile budget remain enforced.

Not included: YSM bridge, lower-level passenger admission, convenient finite vehicle supply command, spectator chatter, autonomous tactical grenades/mines/plates, fixed-wing/ship/drone driving, indirect artillery, radar, 10EX. YSM attachment was reviewed and the implementation gap is documented; no real YSM client test was run.
Historical same-side TeamDive synchronization remains pending even if this run passes. Tests do not establish 100-bot TPS or asynchronous AI. All world/AI work remains on the server thread.
All tests used isolated servers; no live-server installation or operations. Full combat, complex terrain, guided weapon effectiveness and late-join YSM visuals require future user/client validation.
'''
(out / 'validation-4.15.0.txt').write_text(validation, encoding='utf-8')

progress_file = repo / 'AI_HARDNESS_PROGRESS.md'
progress = progress_file.read_text(encoding='utf-8')
old = '4.15 已实现用户确认的独立装弹冷却与本轮载具反馈修正，最终全套验证进行中。实际结果在封包时更新；不能把定向通过写成完整通过。'
assert old in progress
new = f'''4.15 已封装本地人工内测包。用户确认的每枪独立装弹/拉栓冷却、持枪交火优先级及本轮载具反馈修正完成。最低 NeoForge 21.1.1 构建成功；基础完整回归 **{full_zh}**；真实 0.8.9.1 原生专项 **61 项全部通过**。本次基础失败：{failure_text}。历史同向团队俯冲仍独立待修。

新增 15 项验证覆盖：不同真枪独立截止时间与有限备弹扣除、实际进食/珍珠/鞘翅不中断冷却、冷却时切到可用枪实际造成伤害、10 级纯枪械轻伤持续攻击、AH-6/M1A2/BMP 尾部登车、AH-6 机头实际伤害及拉离、关闭悬停后的原生前进、丢失目标后回最后观察点搜索、直升机边界、第二车不抢第一车预留、真实锁定导弹触发有限诱饵、左右乘员随身枪实际伤害、车顶目标拉开后实际伤害。原有 46 项继续通过。混合预算最多 {budget[1]} 颗/tick、累计 {budget[2]} 颗、十名 owner 均有机会。

首轮完整回归曾有 5v5、掩体 RPG、第二车登车失败。步枪冷却误选射程不足霰弹枪已修正；掩体与第二车测试复原上轮爆炸破坏的隔离场地，保留真实伤害/座位验收。最终结果来自修正后的新完整日志，未将定向通过替代完整回归。

基础首轮 129 项通过、1 项弓手连续射箭超时；随后独立单项连续四次通过。本次完整重跑汇总如上。首轮及四份复测日志随包保留，只有诊断日志改动，尚未确认偶发超时的根因，不标记为已修复。

YSM 附件与报告新范围已审查，记录在 `docs/ysm-compat-review.md`。本包尚未接入 YSM、4–6 级乘员、便捷载具补给命令或观战台词，未把整份报告标成完成。复杂地形、实际多人空战与用户正式服目测仍待人工内测；没有操作正式服、提交或推送。结果见交付目录 `validation-4.15.0.txt` 和 `ai-hardness-4.15.0-*.log`。'''
progress_file.write_text(progress.replace(old, new, 1), encoding='utf-8')

guide = f'''# 4.15.0-BETA 独立装弹与载具反馈内测

适配 Minecraft 1.21.1、Java 21、Superb Warfare 0.8.9.1-final-mc1.21.1；原生兼容测试使用 NeoForge 21.1.249。TerminatorPlus 单独运行仍支持 21.1.1。

关服后备份旧 JAR，将 mods 内 TerminatorPlus 替换为包内版本，避免两版同时存在；卓越前线及其依赖保持原安装。包内含说明与测试记录，未捆绑第三方模组。本次没有安装或操作正式服。

## 本轮行为

- 每把真实枪拥有独立装弹冷却；空膛与战术换弹分别读取运行时 EmptyReloadTime/NormalReloadTime，配件/覆盖生效。切枪、移动、吃东西、珍珠和鞘翅不会重置冷却；到期通过卓越前线扣真实备弹，有限库存不足时只补可用数量。10 级沿用原生无限备弹。冷却和拉栓期间该枪不能射击，开始装弹播放附近可听的原生音效。车载换弹保持原生。
- 冷却时可切另一把有效射程内的枪开火。射程内减少主动珍珠/鞘翅突脸，10 级轻伤持枪更倾向持续攻击，紧急撤离仍保留。
- AH-6 以真实炮口和弹道对准机头，原生输入扫射后拉离；左右侧座有人时交替进攻方向，并提供侧飞窗口。前往目的地关闭悬停，提高原生前进效率。
- 遇已感知对空威胁先绕侧；实际锁定导弹或载具掉血时规避并请求原生诱饵。需要真实 flying_flare_ammo，10 级也有限；不能保证躲过全部导弹。
- 登车先绕机尾/车身至原生侧点，必要时普通寻路；第二辆车不抢已有乘员预留。目标丢失后限时回最后观察点搜索，再回集结点。车顶目标打不到时有限拉开射界。

## 基本用法

```mcfunction
/bot preset use none
/bot settings defaultgear true
/bot settings gear warfare
/bot settings hardness 7
/bot create RifleBot
```

显式装备预设优先；切回原版用 `/bot settings gear vanilla`。已有机器人可用 `/bot inventory default warfare 7 RifleBot` 换装备，AI 等级保持原值。两套 1–10 配装沿用 4.14：1 无附魔，逐级增加，原版 10 为合法 OP 套。卓越前线只提供其已注册头盔/胸甲，不补原版腿/鞋；枪械原生不接受附魔。

载具指令：`/bot vehicle crew <队长> nearest`、`board <机器人> <UUID> <座位>`、`go <驾驶员> <x> <y> <z>`、`leave <机器人>`；`status` 与 `/bot info` 显示真实座位、vehiclePilot 阶段、装弹等待和剩余诱饵。地面驾驶和移动载具登车仍需 7，直升机驾驶需 9；9 级以下燃料/弹药由管理员或玩家准备。

## 建议复测

1. 5 级 AK+AA-12 双空枪 5v5，相距 60 格：所有人能开火，冷却不会反复卡死，步枪射程内不主动突脸。
2. 10 级纯卓越武器对真人：清空默认显示武器，观察轻伤持续进攻、低血撤离、冷却间补给和切枪。
3. AH-6 四人乘务组：观察机头伤害、左右长椅随身枪、拉离后返回；另测 go 飞行速度和边界。
4. 给 AH-6 有限 flying_flare_ammo 并用对空武器攻击：观察绕侧、规避、真实诱饵消耗及耗尽后行为。
5. 同队两辆载具从尾部登车：确认两车均入座且不反复抢人；先复原上局爆炸坑洞。
6. M1A2 双人对战、车顶步兵与掩体 RPG：确认座位武器、原生碾压/拉开及丢失目标后搜索。

## 结果与后续

真实卓越前线专项 **61/61 通过**，新增 15 项全部通过；基础完整回归 **{full_zh}**。基础失败：{failure_text}。历史同向团队俯冲问题仍独立待修。最低版本构建、JAR CRC、元数据和 {len(classes)} 个编译类逐字节检查通过。完整日志、validation 和 SHA256 随包提供。

基础首轮曾有弓手连续射箭场景超时（129/130），随后单项连续四次通过。最终完整重跑结果如上；首轮失败及四份复测日志保留，偶发超时未标记为已修复。

YSM 说明已审查，本包尚未加入 YSM 模型命令或模型预设；加入服务端玩家列表后的同步、隐藏 Tab、后进服/重连与中文模型需要后续实现及真实客户端验证。低级乘员、便捷补给和观战台词也保留下一轮。手雷/烟雾/C4/TM-62/装甲板仍只配给，自动使用未完成。

这轮自测验证实际弹丸、伤害、位移和消耗；复杂山地、建筑群、高速空战和真人实战仍需内测。没有百人 TPS 或异线程 AI 结论。实现细节见 SUPERB_WARFARE.md、AI_HARDNESS_PROGRESS.md 和 AGENTS.md。
'''
(out / '4.15.0-内测说明.md').write_text(guide, encoding='utf-8')

package = out / 'TerminatorPlus-NeoForge-4.15.0-内测包.zip'
docs = ['README.md', 'AGENTS.md', 'AI_HARDNESS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md',
        'docs/ai-hardness-handoff.md', 'docs/superbwarfare-compat-notes.md', 'docs/feedback-4.12-guns.md',
        'docs/feedback-4.13.md', 'docs/playtest-observations.md', 'docs/playtest-report-4.13.md',
        'docs/ysm-compat-notes.md', 'docs/ysm-compat-review.md', 'docs/playtest-report-status-4.15.md']
contents = [(out / jar.name, jar.name), (out / '4.15.0-内测说明.md', '内测说明.md')]
contents += [(repo / n, n) for n in docs]
contents += [(out / n, n) for n in ('validation-4.15.0.txt', 'ai-hardness-4.15.0-build.log',
                                  'ai-hardness-4.15.0-selftest.log', 'ai-hardness-4.15.0-warfare.log', 'ai-hardness-4.15.0-selftest-first.log')]
contents += [(out / f'ai-hardness-4.15.0-archer-focus-{i}.log', f'ai-hardness-4.15.0-archer-focus-{i}.log') for i in range(1, 5)]
with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
    for source, name in contents:
        archive.write(source, name)
with zipfile.ZipFile(package) as archive:
    assert archive.testzip() is None and archive.read(jar.name) == jar.read_bytes()
assert (out / jar.name).read_bytes() == jar.read_bytes()
zip_digest = hashlib.sha256(package.read_bytes()).hexdigest().upper()
(out / 'SHA256-4.15.0.txt').write_text(f'{digest}  {jar.name}\n{zip_digest}  {package.name}\n', encoding='utf-8')
print(json.dumps({'jar': jar.name, 'jar_bytes': jar.stat().st_size, 'sha256': digest,
                  'package_bytes': package.stat().st_size, 'zip_sha256': zip_digest,
                  'base': full_summary, 'native': 'ALL 61 CHECKS PASSED', 'classes_verified': len(classes)}, ensure_ascii=False))
