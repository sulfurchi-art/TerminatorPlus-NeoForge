from pathlib import Path
import hashlib, json, re, shutil, zipfile
root=Path(__file__).resolve().parent.parent
repo=root/'outputs/TerminatorPlus-NeoForge'; work=root/'work'; out=root/'outputs'
version='4.19.0'
jar_name=f'TerminatorPlus-NeoForge-1.21.1-{version}-BETA.jar'
zip_name=f'TerminatorPlus-NeoForge-{version}-内测包.zip'
def sha(data): return hashlib.sha256(data).hexdigest().upper()
def results(name,total):
    body=(work/name).read_text(encoding='utf-8-sig',errors='replace')
    assert 'BUILD SUCCESSFUL' in body,name
    passes=body.count('[SelfTest] PASS ');failures=re.findall(r'\[SelfTest\] FAIL ([^\r\n]+)',body)
    assert passes+len(failures)==total,(name,passes,failures)
    assert (f'{len(failures)} of {total} checks FAILED' if failures else f'ALL {total} CHECKS PASSED') in body
    return passes,failures
native_pass,native_failures=results('ai-hardness-4.19-warfare-full.log',102)
base_pass,base_failures=results('ai-hardness-4.19-base-full.log',130)
assert not native_failures,native_failures
assert set(base_failures)<={'equipment border infantry retreats heals and navigates with an inward margin','teamdive same side native takeoffs spread before a common smash with far member first','teamdive same side native takeoffs spread before a common smash with near member first'},base_failures
assert 'BUILD SUCCESSFUL' in (work/'ai-hardness-4.19-build.log').read_text(encoding='utf-8-sig')
frozen=json.loads((work/'4.19-test-source-snapshot.json').read_text());assert len(frozen)==114
for n,h in frozen.items():assert sha((repo/n).read_bytes())==h,n
native_classes=json.loads((work/'4.19-native-class-snapshot.json').read_text())
classes_root=repo/'build/classes/java/main'
classes={p.relative_to(classes_root).as_posix():p.read_bytes() for p in classes_root.rglob('*.class')}
assert len(classes)==len(native_classes)==180
for n,data in classes.items():assert sha(data)==native_classes[n],n
jar_path=repo/'build/libs'/jar_name
with zipfile.ZipFile(jar_path) as z:
    assert z.testzip() is None
    for n,data in classes.items():assert z.read(n)==data,n
    metadata=z.read('META-INF/neoforge.mods.toml').decode('utf-8')
    assert 'version="4.19.0-BETA"' in metadata and 'versionRange="[21.1.1,)"' in metadata
    assert 'accesstransformer.cfg' in metadata and 'META-INF/accesstransformer.cfg' in z.namelist()
    assert not any(n.startswith(('com/atsuishio/','assets/superbwarfare/','data/superbwarfare/')) for n in z.namelist())
old={'TerminatorPlus-NeoForge-1.21.1-4.18.0-BETA.jar':'2D3005E972027752B76A82E8459F903140262025EC8DC67D02466CB2FACC3C43','TerminatorPlus-NeoForge-4.18.0-内测包.zip':'6A912A45D889D45671AFFF4819EB6EEA2714139972901738438389D25640466F','TerminatorPlus-NeoForge-1.21.1-4.17.0-BETA.jar':'CF5BBEC5675E17DCCAD3F0492E35EDB55BF0D92074B1AEF30960FBE1F78A0230','TerminatorPlus-NeoForge-4.17.0-内测包.zip':'CD637EAF52F3AD30A56EFF1191C1D9721DEDADD9D2CCAA5CA31F86C57E54DD48'}
for n,h in old.items():assert sha((out/n).read_bytes())==h,n
assert not (out/jar_name).exists() and not (out/zip_name).exists()
jar_bytes=jar_path.read_bytes();jar_hash=sha(jar_bytes)
fail_list='\n'.join('- '+n for n in base_failures) or '- 无'
notes=f'''# 4.19.0-BETA 陆地载具内测

加入射程内推进、短停换位和预判截击；改进桥下/台阶道路检测、真实车身转向碰撞、轮式行驶弧线与掉头、惯性刹车及倒车脱困。指令抵达后驻留新的目的地。原生运动/伤害保留。

YX-100 旧版两项驾驶基线也通过，正式服不动尚未复现根因；新增 `/bot info <驾驶员>` 的 `terrain=原因@坐标`，方便再次卡住时定位。LAV-AD 对地武器和职业等其余反馈留待对应专项。

将 `{jar_name}` 放入测试服 mods 并替换旧 TerminatorPlus JAR。Minecraft 1.21.1 / Java 21；单独运行最低 NeoForge 21.1.1，卓越前线使用准确 0.8.9.1-final-mc1.21.1 及其依赖。没有捆绑第三方模组。本次没有操作或重启用户服务器。

完整原生专项 {native_pass}/102，基础 {base_pass}/130，最低版本构建通过。114 个源/构建文件冻结核对，180 个原生测试、最低版本编译与 JAR 类逐字节一致。共享 24 节点/tick 是预算，不能视为百车 TPS 保证。

基础失败保留：

{fail_list}

新增十一项驾驶检查涵盖桥下道路、六级台阶、坦克/轮式 L 街道、轮式掉头、动态封路并驻留 40 tick、YX-100 三人驾驶、受损车接近高处目标、不可达高点停车、射程内推进/换位以及移动敌车截击。详细范围和结果见 docs/ground-navigation-4.19.md 与 docs/validation-4.19.md。狭窄/复杂立交、坡面滚转、拥堵车阵仍需人工内测。
'''
(out/'4.19.0-内测说明.md').write_text(notes,encoding='utf-8')
validation=f'''4.19.0-BETA validation
Native: {native_pass} PASS / {len(native_failures)} FAIL / 102
Base: {base_pass} PASS / {len(base_failures)} FAIL / 130
Build: NeoForge 21.1.1, Java 21, BUILD SUCCESSFUL
Frozen files: 114; native/minimum/JAR classes: 180, byte-identical
JAR bytes: {len(jar_bytes)}
JAR SHA256: {jar_hash}
Third-party source: 5b92ebe1da5daad9cc5e8ae28e3e06f1636f5816
No foreign mod content bundled. Sealed 4.17/4.18 artifacts preserved.
Base failures:
{fail_list}
'''
(out/'validation-4.19.0.txt').write_text(validation,encoding='utf-8')
shutil.copyfile(jar_path,out/jar_name)
contents={jar_name:out/jar_name,'内测说明.md':out/'4.19.0-内测说明.md','validation-4.19.0.txt':out/'validation-4.19.0.txt'}
for suffix,source in [('warfare','ai-hardness-4.19-warfare-full.log'),('selftest','ai-hardness-4.19-base-full.log'),('build','ai-hardness-4.19-build.log')]:
    name=f'ai-hardness-4.19.0-{suffix}.log';assert not (out/name).exists();shutil.copyfile(work/source,out/name);contents[name]=out/name
for n in ['README.md','AGENTS.md','AI_HARDNESS.md','AI_HARDNESS_PROGRESS.md','SUPERB_WARFARE.md','docs/ground-navigation-4.19.md','docs/validation-4.19.md','docs/playtest-observations-2026-10-06.md','docs/vehicle-ai-research-4.17.md','docs/missile-defense-4.18.md']:contents[n]=repo/n
for n in ['4.19-test-source-snapshot.json','4.19-native-class-snapshot.json','4.19-source-review.json','4.19-implementation.diff']:contents['verification/'+n]=work/n
for p in work.glob('ai-hardness-4.19-*.log'):
    if p.name not in ['ai-hardness-4.19-warfare-full.log','ai-hardness-4.19-base-full.log','ai-hardness-4.19-build.log']:contents['process/'+p.name]=p
with zipfile.ZipFile(out/zip_name,'x',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for n,p in contents.items():z.write(p,n)
with zipfile.ZipFile(out/zip_name) as z:
    assert z.testzip() is None
    for n,p in contents.items():assert z.read(n)==p.read_bytes(),n
zip_hash=sha((out/zip_name).read_bytes())
(out/'SHA256-4.19.0.txt').write_text(f'{jar_hash}  {jar_name}\n{zip_hash}  {zip_name}\n',encoding='utf-8')
report={'jar':str(out/jar_name),'zip':str(out/zip_name),'jar_bytes':len(jar_bytes),'jar_sha256':jar_hash,'zip_sha256':zip_hash,'archive_files':len(contents),'frozen_files':len(frozen),'compiled_classes':len(classes),'native_pass':native_pass,'native_failures':native_failures,'base_pass':base_pass,'base_failures':base_failures,'native_and_minimum_class_bytes_match':True}
(out/'package-verification-4.19.0.json').write_text(json.dumps(report,indent=2,ensure_ascii=False),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
