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
log = root / 'work/ai-hardness-4.10-final-full.log'
build = root / 'work/ai-hardness-4.10-build.log'
checks = log.read_text(encoding='utf-8', errors='replace')
assert '[SelfTest] ALL 121 CHECKS PASSED' in checks
assert len(re.findall(r'\[SelfTest\] PASS ', checks)) == 121
assert not re.search(r'\[SelfTest\] (FAIL|ERROR)', checks)
assert 'BUILD SUCCESSFUL' in checks and 'BUILD SUCCESSFUL' in build.read_text(encoding='utf-8', errors='replace')
jar = repo / 'build/libs/TerminatorPlus-NeoForge-1.21.1-4.10.0-BETA.jar'
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
    assert metadata['mods'][0]['version'] == '4.10.0-BETA'
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
shutil.copy2(log, out / 'ai-hardness-4.10.0-selftest.log')
shutil.copy2(build, out / 'ai-hardness-4.10.0-build.log')
digest = hashlib.sha256(jar.read_bytes()).hexdigest().upper()
validation = f'''TerminatorPlus-NeoForge 4.10.0-BETA
Validated: 2026-10-04
Minecraft: 1.21.1
Compiled and tested against NeoForge: 21.1.1 (minimum retained)
Java: Microsoft OpenJDK 21.0.7
Build: SUCCESSFUL
SelfTest: ALL 121 CHECKS PASSED (44 original + 25 hardness + 20 elite/configuration + 11 strategy + 11 cooperation + 10 team-dive)
SelfTest failure markers: none; 121 individual PASS records confirmed.
Jar: {jar.name}
Jar bytes: {jar.stat().st_size}
SHA256: {digest}
Jar inspection: archive integrity, 4.10.0 metadata, NeoForge minimum, explicit AT, chatter resources, forecast, assembly, scheduling and flight classes and all compiled class bytes confirmed.
Source base: c1fcf77; local changes have not been committed or pushed.
Full functional log: ai-hardness-4.10.0-selftest.log
Build log: ai-hardness-4.10.0-build.log
Progress: TerminatorPlus-NeoForge/AI_HARDNESS_PROGRESS.md
Guide: TerminatorPlus-NeoForge/AI_HARDNESS.md

This batch: native contact-time forecasts, same-side airborne assembly into separated approaches, and per-member release scheduling toward one shared impact window at hardness 10. Wounded members, switched targets, changed membership/equipment, timing misses, and blocked descent cancel the shared plan. Flight and falling limits are unchanged. Hardness 8-9 now use elapsed skill attempt intervals and reacquire lost flight targets from recent last-seen memory through actual perception.
The 10 new scenarios cover forecast-to-native first smash timing; different-height attacks from opposite directions against stationary and actually moving targets; actual same-side takeoffs in both formation orders followed by separated approaches; wounded-member cancellation and healthy continuation; a newly placed obstruction that cancels the plan and removal that restores attacks; distinct actual enemies that never share a plan; fully automatic 8/9/10-with-teamwork-disabled takeoff and native smash; and collision, dangerous ground and unloaded-column rejection without forced chunk loading.
Native AttackEntityEvent confirms descending mace attacks and positive LivingDamageEvent.Post confirms actual target damage. Simultaneous native attempts do not imply two damaging hits during vanilla hurt protection; tests preserve that protection. Opposite-side scenarios require attack-time spread at most two ticks and differing release times/heights; same-side assembly checks actual spread before native attacks.
Once committed, release consults the swept contact forecast rather than being postponed again by the coarse landing estimate. Both formation orders have dedicated native scenarios.
Candidate forecast snapshots last one tick and check input positions/velocities; release always recomputes. Groups contain at most eight members; assembly, readiness and scheduled pre-release waits are bounded. Functional scenarios establish these tested routes, not success rates for arbitrary larger teams, sudden target turns or complex terrain, adjacent-level win-rate ordering, or top-player equivalence.
Remaining requirements are recorded in AI_HARDNESS_PROGRESS.md. The persistent development goal remains active.
The asynchronous AI performance experiment remains paused at the user's request; its scratch prototype is excluded from this jar.
Testing used an isolated self-test server. The user's live game server was not modified or started/stopped.
'''
(out / 'validation-4.10.0.txt').write_text(validation, encoding='utf-8')
progress_file = repo / 'AI_HARDNESS_PROGRESS.md'
progress = progress_file.read_text(encoding='utf-8')
old = '4.9 完整功能回归已通过 111 项。4.10 新增 10 项，最终完整回归目标为 `ALL 121 CHECKS PASSED`，包含原有 44 项、4.6 的 25 项、4.7 的 20 项、4.8 和 4.9 各 11 项及本轮 10 项；本轮最终完整回归尚未完成。'
new = '4.10 构建成功，完整功能回归已通过 `ALL 121 CHECKS PASSED`，包含原有 44 项、4.6 的 25 项、4.7 的 20 项、4.8 和 4.9 各 11 项及本轮 10 项。'
assert old in progress
progress_file.write_text(progress.replace(old, new), encoding='utf-8')
assert (out / jar.name).read_bytes() == jar.read_bytes()
print(f'VALIDATED: 121 checks; {jar.stat().st_size} bytes; SHA256={digest}')
