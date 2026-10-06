from pathlib import Path
import zipfile,json,difflib
work=Path(__file__).resolve().parent
repo=work.parent/'outputs/TerminatorPlus-NeoForge'
snap=json.loads((work/'4.22-final-source.json').read_text())
with zipfile.ZipFile(work/'TerminatorPlus-NeoForge-4.21-source-checkpoint.zip') as z:
    names=set(z.namelist());changed=[];patch=[];production=[]
    for n in snap:
        data=(repo/n).read_bytes();before=z.read(n) if n in names else b''
        if data != before:
            changed.append(n)
            if n.endswith('.java') and not n.endswith('SelfTest.java'):
                production.append(n)
                patch.extend(difflib.unified_diff(before.decode('utf-8-sig').replace('\r\n','\n').splitlines(True),data.decode('utf-8-sig').replace('\r\n','\n').splitlines(True),fromfile='4.21/'+n,tofile='4.22/'+n))
(work/'4.22-production.patch').write_text(''.join(patch),encoding='utf-8')
(work/'4.22-delta-from-4.21.json').write_text(json.dumps(changed,indent=2),encoding='utf-8')
print(json.dumps({'changedFiles':len(changed),'productionJava':len(production),'javaFiles':[Path(n).name for n in production]}))
