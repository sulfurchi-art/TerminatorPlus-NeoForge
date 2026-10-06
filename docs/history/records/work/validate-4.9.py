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
log = root / 'work/ai-hardness-4.9-final-full.log'
build = root / 'work/ai-hardness-4.9-build.log'
checks = log.read_text(encoding='utf-8', errors='replace')
assert '[SelfTest] ALL 111 CHECKS PASSED' in checks
assert len(re.findall(r'\[SelfTest\] PASS ', checks)) == 111
assert not re.search(r'\[SelfTest\] (FAIL|ERROR)', checks)
assert 'BUILD SUCCESSFUL' in checks and 'BUILD SUCCESSFUL' in build.read_text(encoding='utf-8', errors='replace')
jar = repo / 'build/libs/TerminatorPlus-NeoForge-1.21.1-4.9.0-BETA.jar'
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    metadata = tomllib.loads(archive.read('META-INF/neoforge.mods.toml').decode('utf-8'))
    assert metadata['mods'][0]['version'] == '4.9.0-BETA'
    assert metadata['mods'][0]['modId'] == 'terminatorplus'
    assert any(d['modId'] == 'neoforge' and d['versionRange'] == '[21.1.1,)' for d in metadata['dependencies']['terminatorplus'])
    assert any(a['file'] == 'META-INF/accesstransformer.cfg' for a in metadata['accessTransformers'])
    assert archive.read('META-INF/accesstransformer.cfg')
    chatter = [n for n in archive.namelist() if n.startswith('chatter/') and not n.endswith('/')]
    assert len(chatter) >= 2
    package = 'net/nuggetmc/tplus/api/agent/legacyagent/skill/'
    for name in ['TeamBoard', 'TeamBoard$Roles', 'TeamBoard$Signal', 'BotMemory$TeamRole', 'ArrowAim', 'BowSkill', 'TacticalSkill', 'EliteCombat', 'ShieldDefense']:
        path = package + name + '.class'
        assert archive.read(path) == (repo / 'build/classes/java/main' / path).read_bytes()

head_properties = subprocess.check_output(['git', 'show', 'HEAD:run-selftest/server.properties'], cwd=repo)
properties = repo / 'run-selftest/server.properties'
current_lines = properties.read_bytes().splitlines()
head_lines = head_properties.splitlines()
assert current_lines[2:] == head_lines[2:], 'Unexpected test-server settings changes; preserve for review'
assert current_lines[0] == head_lines[0]
properties.write_bytes(b'\r\n'.join(head_lines) + b'\r\n')

shutil.copy2(jar, out / jar.name)
shutil.copy2(log, out / 'ai-hardness-4.9.0-selftest.log')
shutil.copy2(build, out / 'ai-hardness-4.9.0-build.log')
digest = hashlib.sha256(jar.read_bytes()).hexdigest().upper()
validation = f'''TerminatorPlus-NeoForge 4.9.0-BETA
Validated: 2026-10-04
Minecraft: 1.21.1
Compiled and tested against NeoForge: 21.1.1 (minimum retained)
Java: Microsoft OpenJDK 21.0.7
Build: SUCCESSFUL
SelfTest: ALL 111 CHECKS PASSED (44 original + 25 hardness + 20 elite/configuration + 11 strategy + 11 cooperation)
SelfTest failure markers: none; 111 individual PASS records confirmed.
Jar: {jar.name}
Jar bytes: {jar.stat().st_size}
SHA256: {digest}
Jar inspection: archive integrity, 4.9.0 metadata, NeoForge minimum, explicit AT, chatter resources, team role/signal classes and exact built class bytes confirmed.
Source base: c1fcf77; local changes have not been committed or pushed.
Full functional log: ai-hardness-4.9.0-selftest.log
Build log: ai-hardness-4.9.0-build.log
Progress: TerminatorPlus-NeoForge/AI_HARDNESS_PROGRESS.md
Guide: TerminatorPlus-NeoForge/AI_HARDNESS.md

This batch: actual human teammate attack focus, recent-injury guard assignment, idle following at hardness 8-10, stable bait/flank/archer roles at 10, wounded-front withdrawal and healthy replacement, and actual friendly-lane checks at draw start and native arrow release.
The 11 new scenarios cover native melee attack focus; perception, identity, membership, toggles and observation expiry; real guard damage against a pursuer; native follow movement at 8/9/10 within existing limits; all three roles and two real arrow UUIDs; actual movement to safety under an elevated threat with native regeneration and front replacement; canceled attack events; two distinct guards and departure cleanup; and withholding an active draw for an entering teammate before later native arrow damage.
Roles apply during FIGHT and preserve COVER_ALLY interception as well as retreat/recovery priority. Candidate firing positions cache for 10 ticks; role plans hold up to 80 ticks and invalidate when members or equipment change.
Functional scenarios use real ServerPlayer fixtures, native items and damage/projectile events. They do not establish adjacent-level win-rate ordering, zero friendly fire under every dynamic shot, complete complex-terrain guard routing, precise simultaneous team dive impacts, or top-player equivalence.
Remaining requirements are recorded in AI_HARDNESS_PROGRESS.md. The persistent development goal remains active.
The asynchronous AI performance experiment remains paused at the user's request; its scratch prototype is excluded from this jar.
Testing used an isolated self-test server. The user's live game server was not modified or started/stopped.
'''
(out / 'validation-4.9.0.txt').write_text(validation, encoding='utf-8')
progress_file = repo / 'AI_HARDNESS_PROGRESS.md'
progress = progress_file.read_text(encoding='utf-8')
old = '4.8 完整功能回归已通过 100 项。4.9 完整功能回归目标为 `ALL 111 CHECKS PASSED`，包含原有 44 项、4.6 的 25 项、4.7 的 20 项、4.8 的 11 项与本轮新增 11 项；本轮最终完整回归正在运行。'
new = '4.9 构建成功，完整功能回归已通过 `ALL 111 CHECKS PASSED`，包含原有 44 项、4.6 的 25 项、4.7 的 20 项、4.8 的 11 项与本轮新增 11 项。'
assert old in progress
progress_file.write_text(progress.replace(old, new), encoding='utf-8')
assert (out / jar.name).read_bytes() == jar.read_bytes()
print(f'VALIDATED: 111 checks; {jar.stat().st_size} bytes; SHA256={digest}')
