from pathlib import Path
import re
work=Path(__file__).resolve().parent
p=work.parent/'outputs/TerminatorPlus-NeoForge/run-selftest/config/terminatorplus/settings.snbt'
s=p.read_text(encoding='utf-8-sig')
with (work/'4.22-minimum-cold-start-settings-before.snbt').open('x',encoding='utf-8') as h:h.write(s)
if 'BattleLog:' in s:
    s,n=re.subn(r'(BattleLog:\s*\{.*?\n\s*Enabled:\s*)0b',r'\g<1>1b',s,count=1,flags=re.S)
    assert n==1
else:
    assert s.lstrip().startswith('{')
    s=s.replace('{','{\n    BattleLog: {Enabled: 1b, SnapshotTicks: 40},',1)
p.write_text(s,encoding='utf-8')
print('Prepared isolated minimum-version world with logging enabled before startup')
