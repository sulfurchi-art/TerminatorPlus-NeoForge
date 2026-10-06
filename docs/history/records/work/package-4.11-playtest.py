from pathlib import Path
import hashlib
import re
import shutil
import subprocess
import tomllib
import zipfile

root = Path(__file__).resolve().parent.parent
repo = root / 'outputs/TerminatorPlus-NeoForge'
out = root / 'outputs'
log = root / 'work/ai-hardness-4.11-full.log'
build = root / 'work/ai-hardness-4.11-build.log'
checks = log.read_text(encoding='utf-8', errors='replace')
passes = len(re.findall(r'\[SelfTest\] PASS ', checks))
failures = re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)', checks)
assert passes + len(failures) == 127, (passes, failures)
known = {'teamdive same side native takeoffs spread before a common smash with near member first', 'teamdive same side native takeoffs spread before a common smash with far member first'}
assert set(failures).issubset(known), 'Unexpected full regression failure: ' + str(failures)
assert len(re.findall(r'\[SelfTest\] PASS feedback ', checks)) == 6
assert not re.search(r'\[SelfTest\] ERROR ', checks)
assert '[SelfTest] ---- summary ----' in checks
summary = f'{passes} PASS / {len(failures)} FAIL / 127 total'
assert 'BUILD SUCCESSFUL' in checks and 'BUILD SUCCESSFUL' in build.read_text(encoding='utf-8', errors='replace')
jar = repo / 'build/libs/TerminatorPlus-NeoForge-1.21.1-4.11.0-BETA.jar'
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
    assert metadata['mods'][0]['version'] == '4.11.0-BETA'
    assert metadata['mods'][0]['modId'] == 'terminatorplus'
    assert any(d['modId'] == 'neoforge' and d['versionRange'] == '[21.1.1,)' for d in metadata['dependencies']['terminatorplus'])
    assert any(a['file'] == 'META-INF/accesstransformer.cfg' for a in metadata['accessTransformers'])
    assert archive.read('META-INF/accesstransformer.cfg')
    chatter = [n for n in archive.namelist() if n.startswith('chatter/') and not n.endswith('/')]
    assert len(chatter) >= 2
    package = 'net/nuggetmc/tplus/api/agent/legacyagent/skill/'
    for name in ['DiveForecast', 'DiveForecast$Contact', 'TeamDive', 'TeamDive$Plan', 'TeamDive$Assembly', 'TeamDive$Snapshot', 'TeamDive$Member', 'TeamBoard', 'BotMemory', 'BotSkills', 'ElytraPilot']:
        path = package + name + '.class'
        assert archive.read(path) == (repo / 'build/classes/java/main' / path).read_bytes()
    classes = list((repo / 'build/classes/java/main').rglob('*.class'))
    assert classes, 'No compiled classes found'
    for class_file in classes:
        class_path = class_file.relative_to(repo / 'build/classes/java/main').as_posix()
        assert archive.read(class_path) == class_file.read_bytes(), class_path

head_properties = subprocess.check_output(['git', 'show', 'HEAD:run-selftest/server.properties'], cwd=repo)
properties = repo / 'run-selftest/server.properties'
current_lines = properties.read_bytes().splitlines()
head_lines = head_properties.splitlines()
assert current_lines[2:] == head_lines[2:], 'Unexpected test-server settings changes; preserve for review'
assert current_lines[0] == head_lines[0]
properties.write_bytes(b'\r\n'.join(head_lines) + b'\r\n')

shutil.copy2(jar, out / jar.name)
shutil.copy2(log, out / 'ai-hardness-4.11.0-selftest.log')
shutil.copy2(build, out / 'ai-hardness-4.11.0-build.log')
digest = hashlib.sha256(jar.read_bytes()).hexdigest().upper()
validation = f'''TerminatorPlus-NeoForge 4.11.0-BETA manual playtest
Validated: 2026-10-05
Minecraft: 1.21.1
Compiled and tested against NeoForge: 21.1.1 (minimum retained)
Java: Microsoft OpenJDK 21.0.7
Build: SUCCESSFUL
SelfTest: {summary}
All six feedback scenarios passed within the full run.
Current failure names: {failures or 'none'}
The intermittent same-side team dive timing issue from 4.10 remains a separate pending task; this batch does not claim it is fixed even if this run passes.
Jar: {jar.name}
Jar bytes: {jar.stat().st_size}
SHA256: {digest}
Jar inspection: archive integrity, 4.11.0 metadata, NeoForge minimum, explicit AT, chatter resources and every compiled main class byte confirmed.
Source base: c1fcf77; local changes remain uncommitted and unpushed.
Full functional log: ai-hardness-4.11.0-selftest.log
Build log: ai-hardness-4.11.0-build.log

This feedback batch limits elective elytra use at hardness 8-10: ordinary close combat stays on the ground, flights need offensive distance or an actual follow-up/elevated/flying enemy, and elective flights rest 200 ticks at 8-9 or 300 ticks at 10. Emergency recovery flights use a separate 100-tick limit and can bypass the elective rest during genuinely low health or recent sustained accepted damage. Flight speed, turning, jumps and gravity are unchanged. Distance alone does not mark recently damaged positions safe.
Failed formation movement now yields to ordinary navigation and digging for 100 ticks, including formation points from which the enemy cannot be reached. Near enemies behind a wall no longer continually reset navigation progress. Empty ground recovery releases control after 60 ticks if no item use or regeneration effect can proceed.
The six new scenarios verify actual native damage from all three high levels with no flight at close range, native golden-apple effects without an unnecessary launch at moderate health, actual solo smash followed by 200 ticks of ground pursuit, two accepted native damage events after spawn protection followed by real emergency glide during elective rest, actual ground movement and native damage beyond a blocked close formation, and real pursuit after empty-supply recovery.
These tests establish the reproduced failure paths and conditions; they do not prove that every intermittent in-game stall, terrain configuration or third-party item interaction is fixed. The user's live game server was not modified or operated. Asynchronous AI experiments and the larger development checklist remain paused pending manual feedback.
'''
(out / 'validation-4.11.0.txt').write_text(validation, encoding='utf-8')
progress_file = repo / 'AI_HARDNESS_PROGRESS.md'
progress = progress_file.read_text(encoding='utf-8')
old = '4.11 按最新人工内测反馈降低 8–10 的主动飞行频率；近战站位不可达时让普通导航接管，并限制空补给的地面等待。新增 6 项功能检查，最终完整回归目标 127 项；本轮最终结果尚待验证。4.10 的同向团队俯冲失步仍独立待修。'
new = f'4.11 已封装为人工内测修正版，构建成功。完整回归为 **{passes} 项通过、{len(failures)} 项失败，共 127 项**，新增的 6 项反馈场景全部通过。已验证近距离实际地面近战、轻度受伤真实补给、砸击后的地面追击、连续原版伤害后的紧急飞行、受阻团队站位后的实际接敌，以及空补给后恢复追击。4.10 的同向团队俯冲失步仍独立待修；本次失败场景为：{", ".join(failures) if failures else "无，但历史偶发问题不视为已修复"}。原大清单继续暂缓，等待人工反馈。构建与完整回归日志见本次交付目录 `validation-4.11.0.txt` 与 `ai-hardness-4.11.0-selftest.log`。'
assert old in progress
progress_file.write_text(progress.replace(old, new), encoding='utf-8')
assert (out / jar.name).read_bytes() == jar.read_bytes()
print(f'PLAYTEST PACKAGED: {summary}; {jar.stat().st_size} bytes; SHA256={digest}')
