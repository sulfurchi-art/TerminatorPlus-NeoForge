from pathlib import Path
import hashlib, json, shutil, zipfile

root = Path(__file__).resolve().parent.parent
repo, work, out = root/'outputs/TerminatorPlus-NeoForge', root/'work', root/'outputs'
sha = lambda data: hashlib.sha256(data).hexdigest().upper()
logs = ['ai-hardness-4.21-close-road-baseline.log', 'ai-hardness-4.21-close-road-1.log',
        'ai-hardness-4.21-close-allied-baseline.log', 'ai-hardness-4.21-close-road-2.log',
        'ai-hardness-4.21-close-allied-3.log', 'ai-hardness-4.21-close-allied-4.log',
        'ai-hardness-4.21-close-road-5.log', 'ai-hardness-4.21-flank-1.log', 'ai-hardness-4.21-road-regression.log', 'ai-hardness-4.21-road-regression-2.log', 'ai-hardness-4.21-build.log']
for name, count in [('ai-hardness-4.21-close-road-5.log', 8), ('ai-hardness-4.21-flank-1.log', 3), ('ai-hardness-4.21-road-regression-2.log', 28)]:
    body = (work/name).read_text(encoding='utf-8-sig')
    assert f'ALL {count} CHECKS PASSED' in body and body.count('[SelfTest] PASS ') == count, name
    assert '[SelfTest] FAIL ' not in body and 'BUILD SUCCESSFUL' in body, name
body = (work/'ai-hardness-4.21-road-regression-2.log').read_text(encoding='utf-8-sig')
assert body.count('[SelfTest] PASS warfare close road ') == 10
assert body.count('[SelfTest] PASS warfare ground ') == 11
assert body.count('[SelfTest] PASS warfare navigation ') == 7
assert 'BUILD SUCCESSFUL' in (work/'ai-hardness-4.21-build.log').read_text(encoding='utf-8-sig')
assert 'neo_version=21.1.1\n' in (repo/'gradle.properties').read_text(encoding='utf-8-sig')
frozen = json.loads((work/'4.21-test-source-snapshot.json').read_text())
assert len(frozen) == 114
for n, h in frozen.items():
    assert sha((repo/n).read_bytes()) == h, n
native = json.loads((work/'4.21-native-class-snapshot.json').read_text())
croot = repo/'build/classes/java/main'
classes = {p.relative_to(croot).as_posix(): p.read_bytes() for p in croot.rglob('*.class')}
assert len(classes) == len(native) == 181
for n, data in classes.items():
    assert sha(data) == native[n], n
jar_name = 'TerminatorPlus-NeoForge-1.21.1-4.21.0-BETA.jar'
jar = repo/'build/libs'/jar_name
with zipfile.ZipFile(jar) as z:
    assert z.testzip() is None
    assert {n for n in z.namelist() if n.endswith('.class')} == set(classes)
    for n, data in classes.items():
        assert z.read(n) == data, n
    toml = z.read('META-INF/neoforge.mods.toml').decode('utf-8')
    assert 'version="4.21.0-BETA"' in toml and 'versionRange="[21.1.1,)"' in toml
    assert 'accesstransformer.cfg' in toml and 'META-INF/accesstransformer.cfg' in z.namelist()
    assert not any(n.startswith(('com/atsuishio/', 'assets/superbwarfare/', 'data/superbwarfare/')) for n in z.namelist())
old = {
    'TerminatorPlus-NeoForge-1.21.1-4.20.0-BETA.jar': 'D76634BAF1CD1983CEAF18EFC526AF73EACCBB214FAEBA6C9E88FB9300EF1032',
    'TerminatorPlus-NeoForge-1.21.1-4.19.0-BETA.jar': '4408F1E5524D6FE4129664A9C7CA1B3F06856536B90C3FF1D87BB7943CCC8D20',
    'TerminatorPlus-NeoForge-4.19.0-内测包.zip': '6AF85E48BE687E863DF82FD573180F53B2C26E9C8C5045EE807AE204EC641DBF',
}
for n, h in old.items():
    assert sha((out/n).read_bytes()) == h, n
status = '最终定向原生专项 **28/28 全部通过**（10 项近距驾驶/侧翼新增、11 项既有陆地驾驶、7 项既有寻路/车队/武装协同）；最低 NeoForge 21.1.1 / Java 21 构建通过。114 个源/构建文件冻结核对，181 个原生专项编译、最低版本编译与 JAR 类逐字节一致，CRC/元数据/AT 核对通过。完整 116 项原生专项和 130 项基础回归未重跑。'
for name in ['README.md', 'AGENTS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md', 'docs/close-road-combat-4.21.md']:
    p = repo/name
    s = p.read_text(encoding='utf-8-sig')
    assert s.count('最终验证待完成') == 1, name
    p.write_text(s.replace('最终验证待完成', status.rstrip('。')), encoding='utf-8')
target = out/jar_name
with target.open('xb') as f:
    f.write(jar.read_bytes())
jar_hash = sha(target.read_bytes())
report = {
    'version': '4.21.0-BETA', 'jar': str(target), 'bytes': target.stat().st_size, 'sha256': jar_hash,
    'targeted_native_pass': 28, 'targeted_native_fail': 0, 'new_close_road_pass': 10,
    'full_native_116_not_run': True, 'base_130_not_run': True,
    'frozen_files': 114, 'classes': 181, 'native_minimum_jar_class_bytes_match': True,
    'minimum_neoforge': '21.1.1', 'jar_crc_verified': True, 'sealed_4_20_and_4_19_preserved': True,
    'logs': logs,
    'known_prior_issues': ['fixed three-block pit second crew boarding timeout', 'formal YX100 NO_ROUTE not reproduced', 'LAV-AD ground weapon behavior', 'historical infantry border and TeamDive failures'],
}
(out/'package-verification-4.21.0.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
(out/'SHA256-4.21.0.txt').write_text(f'{jar_hash}  {jar_name}\n', encoding='utf-8')
(out/'validation-4.21.0.txt').write_text(status+'\nJAR SHA256: '+jar_hash+'\n详见 4.21.0-近距载具修正说明.md\n', encoding='utf-8')
shutil.copyfile(repo/'docs/close-road-combat-4.21.md', out/'4.21.0-近距载具修正说明.md')
for name in logs:
    assert not (out/name).exists()
    shutil.copyfile(work/name, out/name)
checkpoint = work/'TerminatorPlus-NeoForge-4.21-source-checkpoint.zip'
with zipfile.ZipFile(checkpoint, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for n, h in frozen.items():
        data = (repo/n).read_bytes(); assert sha(data) == h, n; z.writestr(n, data)
    for n in ['README.md', 'AGENTS.md', 'AI_HARDNESS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md', 'docs/close-road-combat-4.21.md']:
        z.write(repo/n, n)
with zipfile.ZipFile(checkpoint) as z:
    assert z.testzip() is None
print(json.dumps(report, ensure_ascii=True, indent=2))
