from pathlib import Path
import difflib, hashlib, json, zipfile

root = Path(__file__).resolve().parent.parent
repo = root / 'outputs/TerminatorPlus-NeoForge'
work = root / 'work'
sha = lambda data: hashlib.sha256(data).hexdigest().upper()
snapshot = work / '4.19-test-source-snapshot.json'
previous = json.loads(snapshot.read_text())
assert len(previous) == 114
actual = {p.relative_to(repo).as_posix() for directory in ['src/main/java', 'src/main/resources', 'src/main/templates'] for p in (repo / directory).rglob('*') if p.is_file()}
assert actual == {n for n in previous if n.startswith('src/')}
snapshot.write_text(json.dumps({n: sha((repo/n).read_bytes()) for n in sorted(previous)}, indent=2), encoding='utf-8')
classes_root = repo / 'build/classes/java/main'
classes = {p.relative_to(classes_root).as_posix(): sha(p.read_bytes()) for p in classes_root.rglob('*.class')}
assert len(classes) == 180
(work/'4.19-native-class-snapshot.json').write_text(json.dumps(classes, indent=2, sort_keys=True), encoding='utf-8')
changed, added, diff = [], [], []
with zipfile.ZipFile(work/'TerminatorPlus-NeoForge-4.18-source-checkpoint.zip') as z:
    names = set(z.namelist())
    for n in sorted(previous):
        if not n.endswith('.java'): continue
        current = (repo/n).read_bytes()
        if n not in names:
            added.append(n)
            old = b''
        else:
            old = z.read(n)
        if old != current:
            changed.append(n)
            diff.extend(difflib.unified_diff(old.decode('utf-8-sig').splitlines(True), current.decode('utf-8-sig').splitlines(True), fromfile='4.18/'+n, tofile='4.19/'+n))
assert set(changed) == {'src/main/java/net/nuggetmc/tplus/compat/'+n+'.java' for n in ['VehicleNavigation','VehiclePilot','VehicleCrew']} | {'src/main/java/net/nuggetmc/tplus/utils/SelfTest.java'}
assert not added
(work/'4.19-implementation.diff').write_text(''.join(diff), encoding='utf-8')
review = {'changedJava': changed, 'addedJava': added, 'frozenFiles': len(previous), 'nativeClasses': len(classes), 'snapshotTestLog': 'ai-hardness-4.19-boarding-pit-2.log', 'snapshotTestResult': '3 PASS / 1 FAIL / 4', 'fullRegressionPending': True}
(work/'4.19-source-review.json').write_text(json.dumps(review, indent=2), encoding='utf-8')
print(json.dumps(review))
