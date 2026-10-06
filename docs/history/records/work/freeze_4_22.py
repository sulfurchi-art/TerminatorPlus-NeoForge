from pathlib import Path
import hashlib, json
base=Path(__file__).resolve().parents[1]
repo=base/'outputs/TerminatorPlus-NeoForge'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest().upper()
files=list((repo/'src').rglob('*'))+[repo/n for n in ('build.gradle','gradle.properties','settings.gradle','gradlew','gradlew.bat')]
snapshot={p.relative_to(repo).as_posix():sha(p) for p in files if p.is_file()}
classes=repo/'build/classes/java/main'
compiled={p.relative_to(classes).as_posix():sha(p) for p in classes.rglob('*.class')}
for name,data in [('4.22-regression4-source.json',snapshot),('4.22-regression4-native-classes.json',compiled)]:
    p=base/'work'/name
    with p.open('x',encoding='utf-8') as h: json.dump(data,h,indent=2)
print(json.dumps({'sourceFiles':len(snapshot),'classes':len(compiled)}))
