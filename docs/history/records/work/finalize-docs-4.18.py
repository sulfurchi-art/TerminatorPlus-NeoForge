from pathlib import Path
import hashlib
import json
import re
import zipfile

root = Path(__file__).resolve().parent.parent
repo = root / 'outputs/TerminatorPlus-NeoForge'
work = root / 'work'
logs = {kind: (work / name).read_text(encoding='utf-8-sig', errors='replace') for kind, name in {
    'native': 'ai-hardness-4.18-warfare-full-final.log',
    'base': 'ai-hardness-4.18-base-full.log',
    'build': 'ai-hardness-4.18-build.log',
}.items()}
assert all('BUILD SUCCESSFUL' in text for text in logs.values())
assert logs['native'].count('[SelfTest] PASS ') == 91 and 'ALL 91 CHECKS PASSED' in logs['native']
base_pass = logs['base'].count('[SelfTest] PASS ')
base_failures = re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)', logs['base'])
assert base_pass + len(base_failures) == 130
allowed = {
    'equipment border infantry retreats heals and navigates with an inward margin',
    'teamdive same side native takeoffs spread before a common smash with far member first',
    'teamdive same side native takeoffs spread before a common smash with near member first',
}
assert set(base_failures) <= allowed, base_failures
frozen = json.loads((work / '4.18-test-source-snapshot.json').read_text(encoding='utf-8'))
assert len(frozen) == 114
for name, digest in frozen.items():
    assert hashlib.sha256((repo / name).read_bytes()).hexdigest().upper() == digest, name
classes_root = repo / 'build/classes/java/main'
native_classes = json.loads((work / '4.18-native-class-snapshot.json').read_text(encoding='utf-8'))
classes = list(classes_root.rglob('*.class'))
assert len(classes) == len(native_classes) == 178
jar = repo / 'build/libs/TerminatorPlus-NeoForge-1.21.1-4.18.0-BETA.jar'
with zipfile.ZipFile(jar) as archive:
    assert archive.testzip() is None
    for path in classes:
        name = path.relative_to(classes_root).as_posix()
        content = path.read_bytes()
        assert hashlib.sha256(content).hexdigest().upper() == native_classes[name], name
        assert archive.read(name) == content, name
    metadata = archive.read('META-INF/neoforge.mods.toml').decode('utf-8')
    assert 'version="4.18.0-BETA"' in metadata and 'versionRange="[21.1.1,)"' in metadata
    assert 'accesstransformer.cfg' in metadata and 'META-INF/accesstransformer.cfg' in archive.namelist()
    assert not any(name.startswith(('com/atsuishio/', 'assets/superbwarfare/', 'data/superbwarfare/')) for name in archive.namelist())

properties = repo / 'run-selftest/server.properties'
before = (work / '4.18-base-server-properties-before.bin').read_bytes()
def meaningful(data):
    return sorted(line.strip() for line in data.decode('utf-8-sig').splitlines() if line.strip() and not line.lstrip().startswith('#'))
assert meaningful(before) == meaningful(properties.read_bytes()), 'Preserve changed server properties'
properties.write_bytes(before)

fail_text = '\n'.join('- `' + name + '`' for name in base_failures) or '- 本轮没有失败。'
status = f'原生专项 **91/91 全部通过**；基础回归 **{base_pass}/130 通过**（{len(base_failures)} 项失败）；最低 NeoForge 21.1.1 / Java 21 构建成功。'
p = repo / 'docs/validation-4.18.md'
text = p.read_text(encoding='utf-8')
start = text.index('## 当前验收')
end = text.index('## 新增场景', start)
text = text[:start] + f'''## 最终验收

{status}

| 检查 | 最终结果 | 原始日志 |
| --- | --- | --- |
| 实际 SBW 0.8.9.1 / NeoForge 21.1.249 专项 | 91 PASS / 0 FAIL，包含全部 11 项标枪检查 | ai-hardness-4.18-warfare-full-final.log |
| 无 SBW / NeoForge 21.1.1 基础回归 | {base_pass} PASS / {len(base_failures)} FAIL / 130 | ai-hardness-4.18-base-full.log |
| 最低版本构建 | BUILD SUCCESSFUL | ai-hardness-4.18-build.log |

114 个源/构建文件与测试冻结快照一致。178 个原生专项所用编译类、最低版本编译类和最终 JAR 中的类逐字节一致；CRC、4.18.0-BETA 版本、最低 NeoForge 范围和 AT 均核对通过，没有捆绑第三方模组。交付校验值见包内 SHA256 文件；4.16/4.17 原 JAR 和 ZIP 保留。

本轮基础失败：

{fail_text}

这些场景属于既有边界步兵/同向 TeamDive 问题，仍待定位；本轮某次通过也不认定历史不稳定问题已修复。六人反导搭建峰值为 24 块/tick，验证预算和实际扣材，不代表 TPS 或异线程性能。

''' + text[end:]
p.write_text(text, encoding='utf-8', newline='\n')
p = repo / 'AI_HARDNESS_PROGRESS.md'
text = p.read_text(encoding='utf-8').replace(
    '最终冻结源码的原生专项 **91/91 全部通过**，包含全部 11 项标枪检查；130 项基础回归与最低版本构建正在进行，完成后回填',
    status + '包含全部 11 项标枪检查；114 个冻结文件与 178 个原生测试/最低版本/JAR 类逐字节核对通过，已准备本地内测包')
p.write_text(text, encoding='utf-8', newline='\n')
for filename, paragraph in {
    'README.md': f'4.18 新增 11 项真实标枪反制检查。{status}基础边界步兵及同向团队俯冲历史问题仍待修；详细验收、失败迭代和适用范围见 [验证报告](docs/validation-4.18.md)。',
    'AGENTS.md': f'- 最终验收：{status}114 个源/构建文件冻结；178 个原生测试类、最低版本编译类及 JAR 类逐字节一致，CRC/元数据/AT 核对通过。基础失败类别与原始日志见 `docs/validation-4.18.md`，历史失败不认定修复。',
    'SUPERB_WARFARE.md': f'本轮最终验证：{status}完整结果和失败边界见上面的验证报告，不能以有限样本存活推出必定反制所有来弹。',
}.items():
    path = repo / filename
    text = path.read_text(encoding='utf-8').rstrip() + '\n\n' + paragraph + '\n'
    path.write_text(text, encoding='utf-8', newline='\n')
print(json.dumps({'native_pass': 91, 'base_pass': base_pass, 'base_failures': base_failures,
                  'verified_class_bytes': 178, 'source_files': 114,
                  'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest().upper()}, ensure_ascii=False))
