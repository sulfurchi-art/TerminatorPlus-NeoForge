from pathlib import Path
import difflib
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parent.parent
repo = root / 'outputs/TerminatorPlus-NeoForge'
frozen = json.loads((root / 'work/4.18-test-source-snapshot.json').read_text(encoding='utf-8-sig'))
differences = [name for name, digest in frozen.items()
               if hashlib.sha256((repo / name).read_bytes()).hexdigest().upper() != digest]
assert not differences, differences
changed = []
patch = []
with zipfile.ZipFile(root / 'work/TerminatorPlus-NeoForge-4.17-source-checkpoint.zip') as archive:
    old_sources = {name for name in archive.namelist() if name.startswith('src/main/java/') and name.endswith('.java')}
    for name in sorted(old_sources):
        old, new = archive.read(name), (repo / name).read_bytes()
        if old == new:
            continue
        changed.append(name)
        patch.extend(difflib.unified_diff(old.decode('utf-8-sig').replace('\r', '').splitlines(keepends=True),
                                        new.decode('utf-8-sig').replace('\r', '').splitlines(keepends=True),
                                        fromfile='4.17/' + name, tofile='4.18/' + name))
    added = sorted(set(str(p.relative_to(repo)).replace('\\', '/') for p in (repo / 'src/main/java').rglob('*.java')) - old_sources)
for name in added:
    patch.extend(difflib.unified_diff([], (repo / name).read_text(encoding='utf-8-sig').splitlines(keepends=True),
                                    fromfile='/dev/null', tofile='4.18/' + name))
(root / 'work/4.18-implementation.diff').write_text(''.join(patch), encoding='utf-8')
result = {'frozen_files': len(frozen), 'modified_java': changed, 'added_java': added}
(root / 'work/4.18-source-review.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
