from pathlib import Path
import hashlib, json, shutil, zipfile

root = Path(__file__).resolve().parent.parent
repo = root/'outputs/TerminatorPlus-NeoForge'
work = root/'work'
out = root/'outputs'
sha = lambda data: hashlib.sha256(data).hexdigest().upper()
body = (work/'ai-hardness-4.20-ordnance-missile.log').read_text(encoding='utf-8-sig')
assert 'ALL 27 CHECKS PASSED' in body and body.count('[SelfTest] PASS ') == 27
assert '[SelfTest] FAIL ' not in body and 'BUILD SUCCESSFUL' in body
assert 'BUILD SUCCESSFUL' in (work/'ai-hardness-4.20-build.log').read_text(encoding='utf-8-sig')
assert 'neo_version=21.1.1\n' in (repo/'gradle.properties').read_text(encoding='utf-8-sig')
frozen = json.loads((work/'4.20-test-source-snapshot.json').read_text())
assert len(frozen) == 114
for n, h in frozen.items():
    assert sha((repo/n).read_bytes()) == h, n
native = json.loads((work/'4.20-native-class-snapshot.json').read_text())
croot = repo/'build/classes/java/main'
classes = {p.relative_to(croot).as_posix(): p.read_bytes() for p in croot.rglob('*.class')}
assert len(classes) == len(native) == 181
for n, data in classes.items():
    assert sha(data) == native[n], n
jar_name = 'TerminatorPlus-NeoForge-1.21.1-4.20.0-BETA.jar'
jar = repo/'build/libs'/jar_name
with zipfile.ZipFile(jar) as z:
    assert z.testzip() is None
    assert {n for n in z.namelist() if n.endswith('.class')} == set(classes)
    for n, data in classes.items():
        assert z.read(n) == data, n
    toml = z.read('META-INF/neoforge.mods.toml').decode('utf-8')
    assert 'version="4.20.0-BETA"' in toml and 'versionRange="[21.1.1,)"' in toml
    assert 'accesstransformer.cfg' in toml and 'META-INF/accesstransformer.cfg' in z.namelist()
    assert not any(n.startswith(('com/atsuishio/', 'assets/superbwarfare/', 'data/superbwarfare/')) for n in z.namelist())
old = {
    'TerminatorPlus-NeoForge-1.21.1-4.19.0-BETA.jar': '4408F1E5524D6FE4129664A9C7CA1B3F06856536B90C3FF1D87BB7943CCC8D20',
    'TerminatorPlus-NeoForge-4.19.0-内测包.zip': '6AF85E48BE687E863DF82FD573180F53B2C26E9C8C5045EE807AE204EC641DBF',
    'TerminatorPlus-NeoForge-1.21.1-4.18.0-BETA.jar': '2D3005E972027752B76A82E8459F903140262025EC8DC67D02466CB2FACC3C43',
    'TerminatorPlus-NeoForge-4.18.0-内测包.zip': '6A912A45D889D45671AFFF4819EB6EEA2714139972901738438389D25640466F',
}
for n, h in old.items():
    assert sha((out/n).read_bytes()) == h, n

status = '最终定向原生专项 **27/27 全部通过**（12 项原有 ordnance、4 项新增 C4、11 项标枪反制）；最低 NeoForge 21.1.1 / Java 21 构建通过。114 个源/构建文件冻结核对，181 个定向原生编译、最低版本编译与 JAR 类逐字节一致，CRC/元数据/AT 核对通过。完整 106 项原生专项和 130 项基础回归未重跑。'
p = repo/'docs/c4-low-pass-4.20.md'
s = p.read_text(encoding='utf-8-sig')
assert '最终原生专项和最低版本构建待完成。' in s
s = s.replace('最终原生专项和最低版本构建待完成。', status)
s += '''

最终新增场景实测（自进入 C4 战术算起）：

| 场景 | 投放耗时 | 完成本轮 | 累计转向 |
|---|---:|---:|---:|
| 9 级、18 格 | 11 tick | 29 tick（1.45 秒） | 1.90° |
| 10 级、68 格 | 42 tick | 62 tick（3.1 秒） | 0.50° |
| 友军贴近敌车 | 不投放 | 23 tick（1.15 秒） | 0.85° |
| 另一颗炸药持续威胁友军 | 一次投放 | 投放后 101 tick（5.05 秒）退出 | 约 0.0003° |

秒数按每秒 20 tick 换算。最后一项因严格大于 100 tick 才触发退出，实际为 101 tick；未放宽原生伤害、本人/友军安全或炸药保留断言。原始最终日志 `ai-hardness-4.20-ordnance-missile.log`，最低版本构建日志 `ai-hardness-4.20-build.log`。高速急转车辆、水下弹道、复杂障碍与大规模 TPS 未验证；本轮不保证所有情况都能命中，不给额外机动力。
'''
p.write_text(s, encoding='utf-8')
updates = {
    'README.md': ('本批最终验证待完成', status),
    'AI_HARDNESS_PROGRESS.md': ('验证状态待最终专项完成', status),
    'AGENTS.md': ('最终验证待完成', status),
}
for name, (needle, replacement) in updates.items():
    p = repo/name; s = p.read_text(encoding='utf-8-sig')
    assert s.count(needle) == 1, name
    p.write_text(s.replace(needle, replacement), encoding='utf-8')
p = repo/'SUPERB_WARFARE.md'
p.write_text(p.read_text(encoding='utf-8-sig')+'\n4.20 验证：'+status+'详见 [C4 修正记录](docs/c4-low-pass-4.20.md)。\n', encoding='utf-8')

target = out/jar_name
with target.open('xb') as f:
    f.write(jar.read_bytes())
jar_hash = sha(target.read_bytes())
report = {
    'version': '4.20.0-BETA', 'jar': str(target), 'bytes': target.stat().st_size, 'sha256': jar_hash,
    'targeted_native_pass': 27, 'targeted_native_fail': 0, 'full_native_106_not_run': True, 'base_130_not_run': True,
    'frozen_files': 114, 'classes': 181, 'native_minimum_jar_class_bytes_match': True,
    'minimum_neoforge': '21.1.1', 'jar_crc_verified': True, 'sealed_4_19_and_4_18_preserved': True,
    'known_prior_issues': ['fixed three-block pit second crew boarding timeout', 'formal YX100 NO_ROUTE not reproduced', 'historical infantry border and TeamDive failures'],
}
(out/'package-verification-4.20.0.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
(out/'SHA256-4.20.0.txt').write_text(f'{jar_hash}  {jar_name}\n', encoding='utf-8')
(out/'validation-4.20.0.txt').write_text(status+'\nJAR SHA256: '+jar_hash+'\n详见 4.20.0-C4修正说明.md\n', encoding='utf-8')
shutil.copyfile(repo/'docs/c4-low-pass-4.20.md', out/'4.20.0-C4修正说明.md')
for name in ['ai-hardness-4.20-c4-baseline.log', 'ai-hardness-4.20-c4-1.log', 'ai-hardness-4.20-ordnance-missile.log', 'ai-hardness-4.20-build.log']:
    assert not (out/name).exists()
    shutil.copyfile(work/name, out/name)
checkpoint = work/'TerminatorPlus-NeoForge-4.20-source-checkpoint.zip'
with zipfile.ZipFile(checkpoint, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for n, h in frozen.items():
        data = (repo/n).read_bytes(); assert sha(data) == h, n; z.writestr(n, data)
    for n in ['README.md', 'AGENTS.md', 'AI_HARDNESS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md', 'docs/c4-low-pass-4.20.md']:
        z.write(repo/n, n)
with zipfile.ZipFile(checkpoint) as z:
    assert z.testzip() is None
print(json.dumps(report, ensure_ascii=True))
