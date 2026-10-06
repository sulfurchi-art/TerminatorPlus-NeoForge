from pathlib import Path
import re,json
work=Path(__file__).resolve().parent
text=(work/'4.22-minimum-cold-start-12.log').read_text(encoding='utf-8-sig')
name=re.search(r'battle-\d{4}-\d\d-\d\d-\d\d-\d\d-\d\d-\d{3}-[a-f0-9]{8}\.jsonl',text).group()
p=work.parent/'outputs/TerminatorPlus-NeoForge/run-selftest/logs/terminatorplus'/name
rows=[json.loads(line) for line in p.read_text(encoding='utf-8').splitlines()]
assert rows[0]['type']=='log_start' and rows[0]['tick']==0 and rows[1]['type']=='mark'
(work/'4.22-minimum-cold-start-evidence.json').write_text(json.dumps({'path':str(p),'first12Events':[(r['tick'],r['type']) for r in rows[:12]],'startupEvent':rows[0],'startupGoalNoneNoMatchBeforeTestMark':True},indent=2),encoding='utf-8')
print('Verified enabled-at-startup log opens at tick 0 before the test starts the match')
