from pathlib import Path
import hashlib,json,zipfile,shutil,re,importlib.util
root=Path(__file__).resolve().parent.parent
repo,work,out=root/'outputs/TerminatorPlus-NeoForge',root/'work',root/'outputs'
sha=lambda data:hashlib.sha256(data).hexdigest().upper()
tests={ '4.22-minimum-cold-start-12.log':1,'4.22-final-native-11.log':2,'4.22-minimum-13.log':25 }
for name,count in tests.items():
    text=(work/name).read_text(encoding='utf-8-sig')
    assert f'ALL {count} CHECKS PASSED' in text and text.count('[SelfTest] PASS ')==count and '[SelfTest] FAIL ' not in text and 'BUILD SUCCESSFUL' in text,name
assert 'BUILD SUCCESSFUL' in (work/'4.22-build-14.log').read_text(encoding='utf-8-sig')
nativeRun=(work/'4.22-final-native-10.log').read_text(encoding='utf-8-sig')
assert nativeRun.count('[SelfTest] PASS ')==41 and nativeRun.count('[SelfTest] FAIL ')==1
assert '1 of 42 checks FAILED: [warfare idle 4.22 mortar' in nativeRun and 'BUILD SUCCESSFUL' in nativeRun
passNames=set(re.findall(r'\[SelfTest\] PASS (.+)',nativeRun))
passNames.update(re.findall(r'\[SelfTest\] PASS (.+)',(work/'4.22-final-native-11.log').read_text(encoding='utf-8-sig')))
assert len(passNames)==42,len(passNames)
frozen=json.loads((work/'4.22-final-source.json').read_text())
for name,h in frozen.items(): assert sha((repo/name).read_bytes())==h,name
previous=json.loads((work/'4.22-native10-source.json').read_text())
assert [n for n,h in frozen.items() if previous[n]!=h]==['src/main/java/net/nuggetmc/tplus/utils/SelfTest.java']
native=json.loads((work/'4.22-final-native-classes.json').read_text())
base=repo/'build/classes/java/main'
classes={p.relative_to(base).as_posix():p.read_bytes() for p in base.rglob('*.class')}
assert len(classes)==len(native)==188
for name,data in classes.items():assert sha(data)==native[name],name
assert 'neo_version=21.1.1\n' in (repo/'gradle.properties').read_text(encoding='utf-8')
jarName='TerminatorPlus-NeoForge-1.21.1-4.22.0-BETA.jar'
jar=repo/'build/libs'/jarName
with zipfile.ZipFile(jar) as z:
    assert z.testzip() is None
    assert {n for n in z.namelist() if n.endswith('.class')}==set(classes)
    for name,data in classes.items(): assert z.read(name)==data,name
    meta=z.read('META-INF/neoforge.mods.toml').decode()
    assert 'version="4.22.0-BETA"' in meta and 'versionRange="[21.1.1,)"' in meta
    assert 'accesstransformer.cfg' in meta and 'META-INF/accesstransformer.cfg' in z.namelist()
    assert not any(n.startswith(('com/atsuishio/','assets/superbwarfare/','data/superbwarfare/')) for n in z.namelist())
old={'TerminatorPlus-NeoForge-1.21.1-4.21.0-BETA.jar':'9A491FE1BA42C1933B271B213F7E19305386107A5C145DEC708CAA1B0DDACD57',
     'TerminatorPlus-NeoForge-1.21.1-4.20.0-BETA.jar':'D76634BAF1CD1983CEAF18EFC526AF73EACCBB214FAEBA6C9E88FB9300EF1032',
     'TerminatorPlus-NeoForge-1.21.1-4.19.0-BETA.jar':'4408F1E5524D6FE4129664A9C7CA1B3F06856536B90C3FF1D87BB7943CCC8D20'}
for name,h in old.items(): assert sha((out/name).read_bytes())==h,name
spec=importlib.util.spec_from_file_location('battle_analysis',repo/'tools/analyze_battle.py'); analyzer=importlib.util.module_from_spec(spec);spec.loader.exec_module(analyzer)
data=[]
for p in sorted((work/'warfare-selftest/logs/terminatorplus').glob('battle-2026-10-07-01-*.jsonl')):
    if p.name < 'battle-2026-10-07-01-16':continue
    result=analyzer.analyze(p);data.append(result)
(work/'4.22-final-battle-analysis.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
attacks=[a for d in data for a in d['drone_attacks'] if a.get('bot')=='FastSuppressor']
stages=[s for d in data for seq in d['stages'].values() for s in seq]
assert len(attacks)>=4 and all(a['firstAttackLatencyTicks']<=100 for a in attacks)
assert not any(d['warnings'] for d in data),[d['warnings'] for d in data]
assert all(s.get('orbitTicks',0)<=80 for s in stages if s['type']=='c4_end')
assert all(s.get('blocks',0)<=4 for s in stages if s['type']=='shelter_done')
assert all(d['potion_throw_count']==0 for d in data)
target=out/jarName
with target.open('xb') as f:f.write(jar.read_bytes())
h=sha(target.read_bytes())
report={'version':'4.22.0-BETA','jar':str(target),'bytes':target.stat().st_size,'sha256':h,
        'with_sbw_distinct_passing_scenarios':42,'native_run_10':{'pass':41,'fail':1,'fixture_failure':'mortar has no native crew seat'},'native_fixture_retest_11':{'pass':2,'fail':0},'production_source_and_classes_match_run_10':True,
        'final_without_sbw_25_pass':True,'persisted_enabled_cold_start_1_pass':True,
        'earlier_source_regression':{'pass':72,'fail':1,'total':73,'failed':'old early direct Javelin guaranteed survival assertion superseded by P1-3 limited cover'},
        'full_base_139_not_run':True,'full_native_124_not_run':True,'frozen_files':len(frozen),'classes':188,
        'native_minimum_jar_class_bytes_match':True,'minimum_neoforge':'21.1.1','jar_crc_verified':True,'old_jars_preserved':True,
        'suppression_attacks':len(attacks),'first_attack_latencies':sorted(set(a['firstAttackLatencyTicks'] for a in attacks)),
        'logs':list(tests)+['4.22-final-native-10.log','4.22-regression-4.log','4.22-final-native-6.log','4.22-final-native-9.log','4.22-cold-start-5.log','4.22-build-14.log'],'ai_workers_not_enabled':True,
        'known_prior_issues':['fixed three-block pit second crew boarding timeout','formal YX100 NO_ROUTE not reproduced','LAV-AD ground weapon behavior','historical infantry border and TeamDive failures']}
(out/'package-verification-4.22.0.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
(out/'SHA256-4.22.0.txt').write_text(f'{h}  {jarName}\n',encoding='utf-8')
status='本轮定向验证：安装卓越前线 0.8.9.1 / NeoForge 21.1.249 覆盖 **42 种通过场景**（整轮 41/42，唯一失败为无乘员座位的迫击炮夹具；更正为 MK42 后专项 2/2 通过，生产源码与字节码保持一致）；不装卓越前线 / 最低 NeoForge 21.1.1 **25/25 通过**；配置预先开启日志的独立冷启动 **1/1 通过**。188 个类的最终原生专项、最低版本、JAR 字节核对通过。完整 139 项基础及 124 项原生专项未重跑。'
for name in ['README.md','AGENTS.md','AI_HARDNESS_PROGRESS.md','SUPERB_WARFARE.md']:
    p=repo/name;s=p.read_text(encoding='utf-8');assert s.count('4.22 最终验证待完成。')==1,name
    p.write_text(s.replace('4.22 最终验证待完成。',status+' 详细记录见 '+('[docs/validation-4.22.md](docs/validation-4.22.md)' if name!='AGENTS.md' else '`docs/validation-4.22.md`')+'。'),encoding='utf-8')
validation=f'''# 4.22 人工内测交付验证

日期：2026-10-07。按用户明确确认实现 P1-1～P1-5，并纳入“所有机器人单位闲置时不再转动”。只构建并运行隔离自测服。

{status}

## 行为

- 无人机用原生按键进行带死区的高度控制，中性刻重置原生升降累计，最多连续 2 刻修正。首攻最多 100 刻，否则返航；保持友军/自伤安全，缩短后续投弹间隔。
- C4 单次接近最多 100 刻，近距阶段最多 80 刻；投放或放弃后沿原方向下降离场，最多 60 刻。遥控仍检查全部己方炸药。
- 仅为实际可见、正在接近、96 格内的导弹造小掩体。优先现有地形/建筑，最多 4 块、25 秒冷却，有限构建窗口且不修复。结束清理匹配所有权的方块。
- 取消凭空生成团队喷溅药水和对应台词，保留伤员护卫。
- BattleLog 默认关闭、可持久化，主线程采集不可变 JSON；有界队列后台写文件。配置、原生开火、实际伤害、死亡前有限历史、乘務/导航、载具/无人机/C4/反导阶段、快照及汇总均有测试。死亡前历史最多 128 条，截断明确标注。使用方法见 [战局日志](battle-log-4.22.md)。
- 步兵和乘员不再闲置扫描；近身护卫不转头追踪队友。追击、跟随、登车、驾驶、主动飞行、返航、威胁应对仍正常转向。验证 1–10 级的 yaw/pitch/head/body，以及原生 M1A2、AH-6、MK42 的乘员和本体朝向。

## 实测与范围

- 固定高度目标稳定后，原生无人机连续悬停 100 刻，高度范围约 0.00344 格，未写无人机位置/速度。30 秒压制实测 {len(attacks)} 次原生攻击，日志中的首攻延迟为 {sorted(set(a['firstAttackLatencyTicks'] for a in attacks))} 刻。
- 四项真实 C4 场景检查投放、扣弹、原生伤害、安全拒绝、累计转向、阶段时间和完成后落地，均通过。
- 两种原生标枪实际来袭检查有限搭建与清理；无实际导弹零搭建；六人实际威胁共享每刻 24 块预算；连续威胁的冷却和禁止修复、天然掩体优先均通过。
- 100 个静止机器人的日志测试丢弃为 0。最终一次平均服务器刻时：不开日志约 2.73 ms、开日志约 2.72 ms；重复运行和热身会影响该差值。这是隔离静止场景，不能当作 50v50 与 24 台载具战争性能验收。
- 有界队列 50000 行压力、显式丢失报告和磁盘错误隔离通过。原异线程 AI 实验仍暂停，只有日志文件 I/O 在后台。
- 较早冻结源码的 73 项回归为 **72 PASS / 1 FAIL**。其中 12 个原有军械、7 个载具导航、11 个陆地驾驶、10 个近距战斗均通过。这些记录不冒充最终源码完整回归。
- 该失败为旧“提前降落后一定躲过直攻标枪”断言。新 P1-3 明确取消全包防御，小掩体不保证存活；最终用真实来袭验证下降、活着落地、原生位移、最多 4 块和后续清理。近距大机动测试仍保留存活断言。旧失败日志保留。
- 中间验证 `4.22-final-native-6.log` 的汇总为 3/41 失败（共 42 个场景，其中一项夹具设置失败不计入检查数但计入失败列表）：两项误把累计搭建计数当作残留方块，固定武器夹具用了未注册的 MK19 载具名。状态显示和测试已修正，世界中真实清理断言保留。`4.22-final-native-9.log` 的测试编译缺少 Block 类型引用，已改为局部类型推导。
- `4.22-final-native-10.log` 为 **41 PASS / 1 FAIL / 共 42 项**，失败为替换的迫击炮没有乘员座位。对照原生数据更正为有座位的 MK42，并加入空座位夹具的提前失败保护；`4.22-final-native-11.log` 的固定火炮闲置及真实座位开火日志 **2/2 通过**。日志测试只解析完成的 JSONL 行，避免与后台缓冲刷新竞争时误报半行 JSON。最终源码相对整轮仅 SelfTest 夹具/读取助手变化，生产源文件及生产类逐字节一致；最终完整 42 项没有再次重复运行。
- 历史深坑双车乘务、正式服 YX100 根因、LAV-AD 地面武器、基础边界步兵和同侧 TeamDive 问题未在本轮宣称修复。

## 包与审计

`{jarName}`：{target.stat().st_size} 字节。

SHA256：`{h}`

118 个源/构建/分析文件冻结核对，188 个类在带卓越前线测试、最低 NeoForge 测试和 JAR 中逐字节相同；ZIP CRC、4.22 版本、`[21.1.1,)` 范围、AT 声明及没有内嵌卓越前线代码均核对。4.21、4.20、4.19 原封包哈希保持不变。

最终日志：`4.22-final-native-11.log`、`4.22-minimum-13.log`、`4.22-minimum-cold-start-12.log`、`4.22-build-14.log`；整轮和较早失败日志一并保留。机器审计见上级 outputs 的 `package-verification-4.22.0.json`。战局分析在 work/4.22-final-battle-analysis.json。源码快照在 work/TerminatorPlus-NeoForge-4.22-source-checkpoint.zip。
'''
(repo/'docs/validation-4.22.md').write_text(validation,encoding='utf-8')
shutil.copyfile(repo/'docs/validation-4.22.md',out/'4.22.0-内测说明.md')
for name in report['logs']: shutil.copyfile(work/name,out/name)
with zipfile.ZipFile(work/'TerminatorPlus-NeoForge-4.22-source-checkpoint.zip','x',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for name,h0 in frozen.items(): data=(repo/name).read_bytes();assert sha(data)==h0;z.writestr(name,data)
    for name in ['README.md','AGENTS.md','AI_HARDNESS.md','AI_HARDNESS_PROGRESS.md','SUPERB_WARFARE.md','docs/battle-log-4.22.md','docs/validation-4.22.md','docs/feedback-4.21.md']:
        z.write(repo/name,name)
print(json.dumps(report,ensure_ascii=True,indent=2))
