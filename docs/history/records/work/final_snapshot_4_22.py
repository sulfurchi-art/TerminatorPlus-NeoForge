from pathlib import Path
import hashlib,json
work=Path(__file__).resolve().parent
repo=work.parent/'outputs/TerminatorPlus-NeoForge'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest().upper()
files=[p for p in (repo/'src').rglob('*') if p.is_file()]+[repo/n for n in ['build.gradle','gradle.properties','settings.gradle','gradlew','gradlew.bat','gradle/wrapper/gradle-wrapper.properties','tools/analyze_battle.py']]
frozen={p.relative_to(repo).as_posix():sha(p) for p in files}
base=repo/'build/classes/java/main'
compiled={p.relative_to(base).as_posix():sha(p) for p in base.rglob('*.class')}
old=json.loads((work/'4.22-native10-source.json').read_text())
changes=[n for n,h in frozen.items() if old[n]!=h]
assert changes==['src/main/java/net/nuggetmc/tplus/utils/SelfTest.java'],changes
oldCompiled=json.loads((work/'4.22-native10-classes.json').read_text())
assert set(oldCompiled)==set(compiled)
for n,h in compiled.items():
    if '/SelfTest' not in n:assert oldCompiled[n]==h,n
(work/'4.22-final-source.json').write_text(json.dumps(frozen,indent=2),encoding='utf-8')
(work/'4.22-final-native-classes.json').write_text(json.dumps(compiled,indent=2),encoding='utf-8')
print(f'Frozen {len(frozen)} files and {len(compiled)} classes; production source and bytecode match the 41 passing cases in native run 10')
