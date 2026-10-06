from pathlib import Path
import hashlib, json, re, shutil, zipfile

root = Path(__file__).resolve().parent.parent
repo = root/'outputs/TerminatorPlus-NeoForge'
work = root/'work'
out = root/'outputs'
jar_name = 'TerminatorPlus-NeoForge-1.21.1-4.19.0-BETA.jar'
zip_name = 'TerminatorPlus-NeoForge-4.19.0-内测包.zip'

def sha(data):
    return hashlib.sha256(data).hexdigest().upper()

def results(name, total):
    body = (work/name).read_text(encoding='utf-8-sig', errors='replace')
    assert 'BUILD SUCCESSFUL' in body, name
    passed = body.count('[SelfTest] PASS ')
    failed = re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)', body)
    assert passed+len(failed) == total, (name, passed, failed)
    summary = f'{len(failed)} of {total} checks FAILED' if failed else f'ALL {total} CHECKS PASSED'
    assert summary in body, name
    return {'log': name, 'passed': passed, 'failed': failed, 'total': total}

# Results are tied to their source stages, never merged into a fictitious full pass.
earlier = results('ai-hardness-4.19-warfare-regression-1.log', 102)
intermediate = results('ai-hardness-4.19-regression-fixed.log', 15)
final = results('ai-hardness-4.19-boarding-pit-2.log', 4)
assert earlier['passed'] == 99 and intermediate['passed'] == 14 and final['passed'] == 3
assert final['failed'] == ['warfare flight feedback filling a second vehicle preserves the first crew reservations']
assert 'BUILD SUCCESSFUL' in (work/'ai-hardness-4.19-build.log').read_text(encoding='utf-8-sig')
properties = (repo/'gradle.properties').read_text(encoding='utf-8-sig')
assert re.search(r'^neo_version=21\.1\.1$', properties, re.M)
assert re.search(r'^mod_version=4\.19\.0-BETA$', properties, re.M)
frozen = json.loads((work/'4.19-test-source-snapshot.json').read_text())
assert len(frozen) == 114
for n, h in frozen.items():
    assert sha((repo/n).read_bytes()) == h, n
native_classes = json.loads((work/'4.19-native-class-snapshot.json').read_text())
classes_root = repo/'build/classes/java/main'
classes = {p.relative_to(classes_root).as_posix(): p.read_bytes() for p in classes_root.rglob('*.class')}
assert len(classes) == len(native_classes) == 180
for n, data in classes.items():
    assert sha(data) == native_classes[n], n
jar_path = repo/'build/libs'/jar_name
with zipfile.ZipFile(jar_path) as z:
    assert z.testzip() is None
    assert {n for n in z.namelist() if n.endswith('.class')} == set(classes)
    for n, data in classes.items():
        assert z.read(n) == data, n
    metadata = z.read('META-INF/neoforge.mods.toml').decode('utf-8')
    assert 'version="4.19.0-BETA"' in metadata and 'versionRange="[21.1.1,)"' in metadata
    assert 'accesstransformer.cfg' in metadata and 'META-INF/accesstransformer.cfg' in z.namelist()
    assert not any(n.startswith(('com/atsuishio/', 'assets/superbwarfare/', 'data/superbwarfare/')) for n in z.namelist())
old = {
    'TerminatorPlus-NeoForge-1.21.1-4.18.0-BETA.jar': '2D3005E972027752B76A82E8459F903140262025EC8DC67D02466CB2FACC3C43',
    'TerminatorPlus-NeoForge-4.18.0-内测包.zip': '6A912A45D889D45671AFFF4819EB6EEA2714139972901738438389D25640466F',
    'TerminatorPlus-NeoForge-1.21.1-4.17.0-BETA.jar': 'CF5BBEC5675E17DCCAD3F0492E35EDB55BF0D92074B1AEF30960FBE1F78A0230',
    'TerminatorPlus-NeoForge-4.17.0-内测包.zip': 'CD637EAF52F3AD30A56EFF1191C1D9721DEDADD9D2CCAA5CA31F86C57E54DD48',
}
for n, h in old.items():
    assert sha((out/n).read_bytes()) == h, n
assert not (out/jar_name).exists() and not (out/zip_name).exists()
jar_bytes = jar_path.read_bytes()
jar_hash = sha(jar_bytes)

status = '按用户“先封包”安排交付本地 4.19 内测包。最低 NeoForge 21.1.1 / Java 21 构建通过；最终源码登车专项 **3/4**，三种车尾接近通过，双车组绕固定三格深坑登座仍超时。最终源码的完整 102 项原生专项和 130 项基础回归均未重跑。'
evidence = '''| 阶段 | 结果 | 适用范围 |
|---|---|---|
| 较早源码完整原生专项 | 99/102 | 水边余量、登车防坑与最终路径修改之前；不是最终包结果 |
| 水边修正后的定向回归 | 14/15 | 新增十一项陆地驾驶、旧目标搜索、直升机边界、水沟通过；双车登座失败；此后又修改登车路径 |
| 最终源码登车专项 | 3/4 | AH-6、M1A2、BMP2 车尾登座通过；固定三格深坑前的第二组乘员登座超时 |
| 最终完整原生 102 项 | 未重跑 | 待封包后回归 |
| 本版本基础 130 项 | 未运行 | 历史边界步兵与同向团队俯冲失败未认定修复 |
| 最低 NeoForge 21.1.1 构建 | 通过 | 与最终定向原生编译、JAR 的 180 个类逐字节一致 |
'''
earlier_failures = '\n'.join('- '+n for n in earlier['failed'])
validation_doc = f'''# 4.19 封包验证记录

{status}

{evidence}

114 个源/构建文件在最后一次登车专项后冻结；180 个原生定向测试编译类、最低版本编译类和 JAR 类逐字节核对。JAR CRC、4.19.0-BETA 元数据、兼容下限 `[21.1.1,)` 和 AT 声明核对通过。Gradle 成功退出不等于功能场景全过，本包没有宣称完整验收通过。

原生定向自测使用 Superb Warfare 0.8.9.1-final-mc1.21.1、Kotlin for Forge 5.12.0、临时 NeoForge 21.1.249；第三方源码锁定 `5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816`。所有场景在工作区隔离世界运行；没有部署、重启或改动正式服。

## 已知登车限制

身体空间和前方脚下支撑检查可阻止直接走入坑洞，保留步行绕行路径以减少反复丢弃路线。最终固定三格深坑场景仍未在 350 tick 内完成第二车组登座，乘员停止在坑边；第一车组四个座位仍被原乘员占据。该场景保留原生真实登座、两组名额和不下落断言，没有为封包弱化检查。恢复开发后先修复此项并重跑完整回归。

此前顺序测试发现道路从草块变为空气，来源尚未唯一证明，不能宣称第三方延迟地形破坏根因已经修复。自测仅在场景断言结束后清理残留弹体，不改变生产行为。

## 较早完整回归的三项失败

{earlier_failures}

后续目标搜索和水沟余量定向复测通过，登车仍有上述限制；这些结果不能替代最终源码的完整回归。

## 其他边界

YX-100 原 4.18 平地三人驾驶/受损车接近高处目标基线 2/2，但正式服 `NO_ROUTE/1` 根因未复现。LAV-AD 对地武器、职业清单、YSM 等尚未完成。原异线程实验仍暂停。共享 24 节点/tick 只代表规划预算，不代表百车 TPS。复杂立交、坡面滚转和拥堵车阵尚需人工内测。

原始日志：`ai-hardness-4.19.0-warfare-prefinal.log`、`ai-hardness-4.19.0-ground-targeted.log`、`ai-hardness-4.19.0-boarding-final.log`、`ai-hardness-4.19.0-build.log`；其余失败、中断与诊断日志在包内 `process/`。实现见 [陆地寻路记录](ground-navigation-4.19.md)。
'''
(repo/'docs/validation-4.19.md').write_text(validation_doc, encoding='utf-8')
for name in ['README.md', 'AI_HARDNESS_PROGRESS.md']:
    p = repo/name
    s = p.read_text(encoding='utf-8-sig')
    needle = '最终验收待完成，详见 [陆地寻路记录](docs/ground-navigation-4.19.md)。'
    assert needle in s, name
    p.write_text(s.replace(needle, status+'详见 [封包验证记录](docs/validation-4.19.md)。'), encoding='utf-8')
p = repo/'AGENTS.md'
s = p.read_text(encoding='utf-8-sig')
assert '最终验收待完成。' in s
p.write_text(s.replace('最终验收待完成。', status+'详见 `docs/validation-4.19.md`。')+'\n4.19 封包后续优先项：第二车组绕固定深坑登座仍超时；先修复登车，再跑最终 102 项原生专项与 130 项基础回归。封包前 99/102、14/15 属于较早源码，不能代替最终源码；封包保存已知失败。\n', encoding='utf-8')
p = repo/'SUPERB_WARFARE.md'
p.write_text(p.read_text(encoding='utf-8-sig')+'\n## 4.19 封包状态\n\n'+status+'详见 [验证记录](docs/validation-4.19.md)。\n', encoding='utf-8')
p = repo/'docs/ground-navigation-4.19.md'
s = p.read_text(encoding='utf-8-sig')
s = s.replace('最终结果仍以最后的完整冻结回归为准。', '按用户安排先封包；最终源码登车专项为 3/4，固定三格深坑前的第二组乘员仍卡住。完整冻结回归尚未重跑，详见 [封包验证记录](validation-4.19.md)。')
p.write_text(s, encoding='utf-8')

notes = f'''# 4.19.0-BETA 内测包

本包增加陆地载具射程内推进、短停后侧向换位与移动敌车预判截击，改进桥下道路、连续台阶、真实车身转向碰撞、轮式行驶弧线与掉头、惯性刹车、倒车脱困及水沟绕行。抵达指令目的地后驻留。只使用原生驾驶输入，不提高车辆速度、机动力或耐久。

## 安装

将 `{jar_name}` 放入测试服 `mods`，替换旧 TerminatorPlus JAR，避免同时放入两个版本。Minecraft 1.21.1 / Java 21；本模组最低 NeoForge 21.1.1。使用卓越前线时，需另装准确的 0.8.9.1-final-mc1.21.1 及其依赖，NeoForge 满足卓越前线自身要求（本次原生测试为 21.1.249）。本包没有捆绑第三方模组。

## 验证与已知问题

{status}

{evidence}

主要进攻/截击和十一项新增地形驾驶检查在较早及水边修正后的定向测试中通过，最终又修改过登车路径，不能视为最终完整回归通过。三格深坑旁登车仍可能卡住，是下轮优先修复项。YX-100 正式服不动尚未复现根因，可提供 `/bot info <驾驶员>` 中的 `vehiclePilot` 与 `terrain=原因@坐标` 帮助定位。LAV-AD 对地不开炮、其他清单及历史边界步兵/同向团队俯冲问题保留待修。

114 个源/构建文件冻结；180 个原生定向编译、最低版本编译与 JAR 类逐字节一致，CRC/元数据/AT 核对通过。没有操作用户服务器，旧版封包保留。详细报告见 `docs/validation-4.19.md`，实现范围见 `docs/ground-navigation-4.19.md`；过程失败与中断日志一并保留。

JAR SHA256：`{jar_hash}`
'''
(out/'4.19.0-内测说明.md').write_text(notes, encoding='utf-8')
validation = f'''4.19.0-BETA preseal validation
Final full native regression: NOT RERUN (102 cases)
This version base regression: NOT RUN (130 cases)
Earlier source full native: 99 PASS / 3 FAIL / 102
Intermediate source targeted: 14 PASS / 1 FAIL / 15
Final source targeted boarding: 3 PASS / 1 FAIL / 4
Known failure: {final['failed'][0]} (fixed three-block pit; second crew boarding timeout)
Minimum build: NeoForge 21.1.1 / Java 21 / BUILD SUCCESSFUL
Frozen source/build files: 114
Final targeted native / minimum / JAR classes: 180, byte-identical
JAR bytes: {len(jar_bytes)}
JAR SHA256: {jar_hash}
No foreign mod content bundled. Earlier sealed artifacts preserved.
'''
(out/'validation-4.19.0.txt').write_text(validation, encoding='utf-8')
shutil.copyfile(jar_path, out/jar_name)
contents = {jar_name: out/jar_name, '内测说明.md': out/'4.19.0-内测说明.md', 'validation-4.19.0.txt': out/'validation-4.19.0.txt'}
logs = {
    'warfare-prefinal': earlier['log'],
    'ground-targeted': intermediate['log'],
    'boarding-final': final['log'],
    'build': 'ai-hardness-4.19-build.log',
}
for suffix, source in logs.items():
    name = f'ai-hardness-4.19.0-{suffix}.log'
    assert not (out/name).exists()
    shutil.copyfile(work/source, out/name)
    contents[name] = out/name
for n in ['README.md', 'AGENTS.md', 'AI_HARDNESS.md', 'AI_HARDNESS_PROGRESS.md', 'SUPERB_WARFARE.md', 'docs/ground-navigation-4.19.md', 'docs/validation-4.19.md', 'docs/playtest-observations-2026-10-06.md', 'docs/vehicle-ai-research-4.17.md', 'docs/missile-defense-4.18.md']:
    contents[n] = repo/n
for n in ['4.19-test-source-snapshot.json', '4.19-native-class-snapshot.json', '4.19-source-review.json', '4.19-implementation.diff', 'freeze-4.19-preseal.py', 'package-4.19-preseal.py']:
    contents['verification/'+n] = work/n
for p in work.glob('ai-hardness-4.19-*.log'):
    if p.name not in logs.values():
        contents['process/'+p.name] = p
with zipfile.ZipFile(out/zip_name, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for n, p in contents.items():
        z.write(p, n)
with zipfile.ZipFile(out/zip_name) as z:
    assert z.testzip() is None
    for n, p in contents.items():
        assert z.read(n) == p.read_bytes(), n
zip_hash = sha((out/zip_name).read_bytes())
(out/'SHA256-4.19.0.txt').write_text(f'{jar_hash}  {jar_name}\n{zip_hash}  {zip_name}\n', encoding='utf-8')
report = {
    'jar': str(out/jar_name), 'zip': str(out/zip_name), 'jar_bytes': len(jar_bytes),
    'jar_sha256': jar_hash, 'zip_sha256': zip_hash, 'archive_files': len(contents),
    'frozen_files': len(frozen), 'compiled_classes': len(classes),
    'earlier_source_native_full': earlier, 'intermediate_source_targeted': intermediate,
    'final_source_targeted': final, 'final_full_native_regression_pending': True,
    'base_regression_not_run': True, 'native_targeted_and_minimum_class_bytes_match': True,
    'jar_classes_byte_identical': True, 'archive_crc_verified': True, 'old_artifacts_preserved': list(old),
}
(out/'package-verification-4.19.0.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False))
