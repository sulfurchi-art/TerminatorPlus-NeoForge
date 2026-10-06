from pathlib import Path
import hashlib
import json
import re
import shutil
import zipfile

root = Path(__file__).resolve().parent.parent
repo = root / 'outputs/TerminatorPlus-NeoForge'
out = root / 'outputs'
work = root / 'work'
version = '4.18.0'
jar_name = f'TerminatorPlus-NeoForge-1.21.1-{version}-BETA.jar'
zip_name = f'TerminatorPlus-NeoForge-{version}-内测包.zip'

def sha(data):
    return hashlib.sha256(data).hexdigest().upper()

def read_log(name):
    body = (work / name).read_text(encoding='utf-8-sig', errors='replace')
    assert 'BUILD SUCCESSFUL' in body, name
    return body

native = read_log('ai-hardness-4.18-warfare-full-final.log')
base = read_log('ai-hardness-4.18-base-full.log')
build = read_log('ai-hardness-4.18-build.log')
native_pass = native.count('[SelfTest] PASS ')
native_fail = native.count('[SelfTest] FAIL ')
assert native_pass + native_fail == 91
native_failure_names = re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)', native)
assert set(native_failure_names) <= {'warfare missile elytra imminent top attack uses a large native maneuver and survives'}, native_failure_names
assert (f'{native_fail} of 91 checks FAILED' if native_fail else 'ALL 91 CHECKS PASSED') in native
base_pass = base.count('[SelfTest] PASS ')
base_fail = base.count('[SelfTest] FAIL ')
assert base_pass + base_fail == 130
failure_names = re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)', base)
known_failures = {
    'equipment border infantry retreats heals and navigates with an inward margin',
    'teamdive same side native takeoffs spread before a common smash with far member first',
    'teamdive same side native takeoffs spread before a common smash with near member first',
}
assert set(failure_names) <= known_failures, failure_names
assert (f'{base_fail} of 130 checks FAILED' if base_fail else 'ALL 130 CHECKS PASSED') in base
frozen_path = work / '4.18-test-source-snapshot.json'
frozen = json.loads(frozen_path.read_text(encoding='utf-8-sig'))
assert len(frozen) == 114
for name, digest in frozen.items():
    assert sha((repo / name).read_bytes()) == digest, name

old_hashes = {
    'TerminatorPlus-NeoForge-1.21.1-4.17.0-BETA.jar': 'CF5BBEC5675E17DCCAD3F0492E35EDB55BF0D92074B1AEF30960FBE1F78A0230',
    'TerminatorPlus-NeoForge-4.17.0-内测包.zip': 'CD637EAF52F3AD30A56EFF1191C1D9721DEDADD9D2CCAA5CA31F86C57E54DD48',
    'TerminatorPlus-NeoForge-1.21.1-4.16.0-BETA.jar': '0896467EE0C3D1D0248B1A35AC8BA8FDC2EA71B2519C72DB2DACCC40B45A1032',
    'TerminatorPlus-NeoForge-4.16.0-内测包.zip': '4F8B9E1F5982774D06FD80D2631456FB6508685B7430561487D321671ADB9056',
}
for name, digest in old_hashes.items():
    assert sha((out / name).read_bytes()) == digest, name
assert not (out / jar_name).exists() and not (out / zip_name).exists(), 'Preserve sealed artifacts'
jar_path = repo / 'build/libs' / jar_name
jar_bytes = jar_path.read_bytes()
jar_hash = sha(jar_bytes)
classes_root = repo / 'build/classes/java/main'
classes = list(classes_root.rglob('*.class'))
assert len(classes) == 178
native_classes = json.loads((work / '4.18-native-class-snapshot.json').read_text(encoding='utf-8'))
assert len(native_classes) == len(classes)
for path in classes:
    assert sha(path.read_bytes()) == native_classes[path.relative_to(classes_root).as_posix()], path
with zipfile.ZipFile(jar_path) as jar:
    assert jar.testzip() is None
    for path in classes:
        name = path.relative_to(classes_root).as_posix()
        assert jar.read(name) == path.read_bytes(), name
    metadata = jar.read('META-INF/neoforge.mods.toml').decode('utf-8')
    assert 'version="4.18.0-BETA"' in metadata
    assert 'versionRange="[21.1.1,)"' in metadata
    assert 'accesstransformer.cfg' in metadata and 'META-INF/accesstransformer.cfg' in jar.namelist()
    assert not any(n.startswith(('com/atsuishio/', 'assets/superbwarfare/', 'data/superbwarfare/')) for n in jar.namelist())
shutil.copyfile(jar_path, out / jar_name)
log_copies = {
    'ai-hardness-4.18.0-warfare.log': work / 'ai-hardness-4.18-warfare-full-final.log',
    'ai-hardness-4.18.0-selftest.log': work / 'ai-hardness-4.18-base-full.log',
    'ai-hardness-4.18.0-build.log': work / 'ai-hardness-4.18-build.log',
}
for name, path in log_copies.items():
    assert not (out / name).exists(), name
    shutil.copyfile(path, out / name)
hash_file = out / 'SHA256-4.18.0-jar.txt'
hash_file.write_text(f'{jar_hash}  {jar_name}\n', encoding='utf-8')
failures = '\n'.join(f'- {name}' for name in failure_names) or '- 无'
native_failures = '\n'.join(f'- {name}' for name in native_failure_names) or '- 无'
notes = f'''# 4.18.0-BETA 标枪反制内测

已加入实际来弹/原生警报/可见开镜预警，鞘翅优先下降与紧急横移，地面封闭掩体、补墙顶和内部规避，直升机原生紧急脱离与有限诱饵。原生运动、伤害和碰撞规则保留；掩体仍可能受到爆炸伤害。职业系统未在本轮实现。

枪弹击毁导弹的探索试验尚不稳定，本版没有启用；其失败日志和试验源码保留在 process/。

将 `{jar_name}` 放入测试服 mods，替换旧 TerminatorPlus JAR。旧版本保留在工作区，本次未操作或重启用户服务器。

Minecraft 1.21.1、Java 21；TerminatorPlus 单独运行最低 NeoForge 21.1.1。安装 Superb Warfare 时使用准确 0.8.9.1-final-mc1.21.1 及其依赖/NeoForge 要求；不包含第三方模组。

`/bot settings ability missiledefense true` 默认开启，`false` 可关闭。`/bot info <名字>` 查看警报与行动阶段。默认 warfare 7–10 增加两组钢块；自定义预设自行配材，可用 `/bot inventory give superbwarfare:steel_block 128` 给现有机器人加材。实际库存 1–9 扣除、10 沿用原有无限规则；没有适当库存的回退沿用原有 buildBlock。

实际验证：完整原生专项 {native_pass}/91（{native_fail} 项失败）；无卓越前线基础 {base_pass}/130（{base_fail} 项失败）；最低 NeoForge 21.1.1 构建成功；114 个源/构建文件冻结核对及 178 个编译类与 JAR 逐字节核对通过。六人搭建峰值 24 块/tick 是预算验证，不是 TPS 结论。

原生专项失败：\n\n{native_failures}\n\n基础失败保留，未认定已修复：

{failures}

平地/有限样本存活不能保证贴脸、连续多弹、材料耗尽、复杂建筑或其他防空武器的成功率。精确预发射锁定在原生客户端，AIM_WARNING 只是可见瞄准预警。详细迭代与真实伤害记录见 docs/missile-defense-4.18.md 和 docs/validation-4.18.md；所有失败/中断过程日志保留在 process/。
'''
(out / '4.18.0-内测说明.md').write_text(notes, encoding='utf-8')
validation = f'''4.18.0-BETA validation
Native: {native_pass} PASS / {native_fail} FAIL / 91
Base: {base_pass} PASS / {base_fail} FAIL / 130
Build: NeoForge 21.1.1, Java 21, BUILD SUCCESSFUL
Frozen source/build files: {len(frozen)}
Verified compiled classes: {len(classes)}
JAR bytes: {len(jar_bytes)}
JAR SHA256: {jar_hash}
Third-party source: 5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816
No foreign mod content bundled; old 4.16/4.17 artifacts preserved.
Native failures:
{native_failures}
Base failures:
{failures}
'''
(out / 'validation-4.18.0.txt').write_text(validation, encoding='utf-8')
contents = {
    jar_name: out / jar_name,
    '内测说明.md': out / '4.18.0-内测说明.md',
    'validation-4.18.0.txt': out / 'validation-4.18.0.txt',
    hash_file.name: hash_file,
    'source-snapshot-4.18.json': frozen_path,
    'native-class-snapshot-4.18.json': work / '4.18-native-class-snapshot.json',
    'source-review-4.18.json': work / '4.18-source-review.json',
    'implementation-4.18.diff': work / '4.18-implementation.diff',
}
for name in ('README.md', 'AGENTS.md', 'AI_HARDNESS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md',
             'docs/missile-defense-4.18.md', 'docs/validation-4.18.md', 'docs/warfare-roles-design.md'):
    contents[name] = repo / name
for name in log_copies:
    contents[name] = out / name
contents['process/4.18-missile-intercept-experiment.zip'] = work / '4.18-missile-intercept-experiment.zip'
excluded = {path.name for path in log_copies.values()}
for path in sorted(work.glob('ai-hardness-4.18-*.log')):
    if path.name not in excluded:
        contents['process/' + path.name] = path
with zipfile.ZipFile(out / zip_name, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for name, path in contents.items():
        archive.write(path, name)
with zipfile.ZipFile(out / zip_name) as archive:
    assert archive.testzip() is None
    assert len(archive.namelist()) == len(contents)
    for name, path in contents.items():
        assert archive.read(name) == path.read_bytes(), name
zip_hash = sha((out / zip_name).read_bytes())
(out / 'SHA256-4.18.0.txt').write_text(f'{jar_hash}  {jar_name}\n{zip_hash}  {zip_name}\n', encoding='utf-8')
report = {'jar': str(out / jar_name), 'zip': str(out / zip_name), 'jar_bytes': len(jar_bytes),
          'jar_sha256': jar_hash, 'zip_sha256': zip_hash, 'archive_files': len(contents),
          'frozen_files': len(frozen), 'compiled_classes': len(classes), 'native_and_minimum_class_bytes_match': True,
          'native_pass': native_pass, 'native_fail': native_fail, 'native_failures': native_failure_names, 'base_pass': base_pass, 'base_fail': base_fail, 'failures': failure_names}
(out / 'package-verification-4.18.0.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False))
