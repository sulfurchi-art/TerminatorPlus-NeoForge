from pathlib import Path
repo=Path(__file__).parent.parent/'outputs/TerminatorPlus-NeoForge'
for name in ['README.md','AI_HARDNESS_PROGRESS.md','SUPERB_WARFARE.md','AGENTS.md']:
    p=repo/name;s=p.read_text(encoding='utf-8')
    marker='4.22 最终验证待完成。'
    assert s.count(marker)==1,name
    s=s.replace(marker,'2026-10-07 补充：所有机器人在真正闲置时保留当前朝向，取消步兵和载具乘员的定时扫视；已站在队友旁的护卫不再持续转头跟踪。跟随、追击、导航、飞行返航和实际防御仍按任务转向。新增 3 项基础和 3 项原生载具朝向场景。\n\n'+marker)
    if name=='AGENTS.md':
        s=s.replace('最后更新：2026-10-06（4.22.0-BETA 实测反馈与战局日志）','最后更新：2026-10-07（4.22.0-BETA 实测反馈、战局日志与闲置朝向）')
        s=s.replace('ALL 130 CHECKS PASSED','ALL 139 CHECKS PASSED',1)
        s=s.replace('当前原生场景使用到 x≈26800','当前原生场景使用到 x≈31800',1)
    p.write_text(s,encoding='utf-8')
p=repo/'docs/battle-log-4.22.md';s=p.read_text(encoding='utf-8')
s=s.replace('死亡前 100 刻的有限历史及本局伤害计数继续收集。','死亡前 100 刻的有限历史及本局伤害计数继续收集。历史最多 128 条，超出时死亡事件带 `recentDamageTruncated=true`，不会将截断记录冒充完整历史。')
s=s.replace('`fire.hit` 为 null。','`fire.hit` 为 null。武器字段为注册物品 ID；装备摘要可额外包含数量。')
s=s.replace('默认关闭。','默认关闭。支持在配置中预先开启后冷启动。',1)
s += '\n闲置时取消步兵与乘务的自动扫视；战术任务需要的转向仍正常执行。反导最多 4 块、25 秒冷却，小掩体不保证躲过标枪。最终验证见 [4.22 验证记录](validation-4.22.md)。\n'
p.write_text(s,encoding='utf-8')
