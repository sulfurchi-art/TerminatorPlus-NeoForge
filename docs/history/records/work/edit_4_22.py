from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'outputs/TerminatorPlus-NeoForge'
def edit(path, old, new, count=1):
    p = root / path
    text = p.read_text(encoding='utf-8')
    assert old in text, (path, old[:100])
    p.write_text(text.replace(old, new, count), encoding='utf-8', newline='\n')

skill = 'src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/skill/'
bot = 'src/main/java/net/nuggetmc/tplus/bot/'
compat = 'src/main/java/net/nuggetmc/tplus/compat/'
edit('gradle.properties', 'mod_version=4.21.0-BETA', 'mod_version=4.22.0-BETA')
p = root / (skill+'TacticalSkill.java')
s = p.read_text(encoding='utf-8')
a=s.index('        List<ServerPlayer> close = allies(bot, 4);',s.index('private void support('))
b=s.index('        if (target == null',a)
s=s[:a]+s[b:]
p.write_text(s, encoding='utf-8', newline='\n')
for path in ['src/main/resources/chatter/ally.zh_cn.txt','extras/chatter/ally.zh_cn.txt']:
    p=root/path; s=p.read_text(encoding='utf-8'); a=s.index('[ally.buff]'); b=s.index('[',a+1)
    # Remove the retired section, preserving the next section and its contents.
    s=s[:a]+s[b:]; p.write_text(s,encoding='utf-8',newline='\n')
edit(skill+'BotSkills.java','    private final MissileDefense missiles;','    private final MissileDefense missiles;\n    private final BattleLog battleLog;\n    public BattleLog battleLog() { return battleLog; }')
edit(skill+'BotSkills.java','        this.missiles = new MissileDefense(this);','        this.missiles = new MissileDefense(this);\n        this.battleLog = new BattleLog(this);')
edit(skill+'BotSkills.java','        if (bot instanceof Bot b) missiles.forget(b);','        if (bot instanceof Bot b) battleLog.forget(b);\n        if (bot instanceof Bot b) missiles.forget(b);')
edit(bot+'BotSettingsStore.java','        SkillSettings checked = new SkillSettings();','        var logConfig = net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(root.getCompound("BattleLog"));\n        SkillSettings checked = new SkillSettings();')
edit(bot+'BotSettingsStore.java','        agent.setTargetType(goal);','        agent.getSkills().battleLog().configure(logConfig);\n        agent.setTargetType(goal);')
edit(bot+'BotSettingsStore.java','        root.put("Skills", agent.getSkillSettings().save());','        root.put("Skills", agent.getSkillSettings().save());\n        root.put("BattleLog", agent.getSkills().battleLog().config().save());')
legacy='src/main/java/net/nuggetmc/tplus/api/agent/legacyagent/LegacyAgent.java'
edit(legacy,'finally { if (bot.isBotAlive()) skills.finishWeapons(bot); }','finally { if (bot.isBotAlive()) { skills.finishWeapons(bot); if (bot instanceof net.nuggetmc.tplus.bot.Bot b) skills.battleLog().observe(b); } }')
edit(legacy,'        this.goal = goal;','        this.goal = goal;\n        skills.battleLog().goal(goal);')
entry='src/main/java/net/nuggetmc/tplus/TerminatorPlus.java'
edit(entry,'            manager.reset();','            ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills().battleLog().shutdown();\n            manager.reset();')
edit(entry,'        if (manager == null || event.getNewDamage() <= 0 || !(event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker)) return;','        if (manager == null || event.getNewDamage() <= 0) return;\n        ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills().battleLog().damage(event.getEntity(), event.getSource(), event.getNewDamage());\n        if (!(event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker)) return;')
edit(entry,'        if (scheduler != null) {\n            scheduler.tick();','        if (manager != null) ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills().battleLog().tick();\n        if (scheduler != null) {\n            scheduler.tick();')
edit(bot+'Bot.java','    public void prepareEquipmentPreset() {','    private String equipmentLabel = "custom";\n    public String equipmentLabel() { return equipmentLabel; }\n    public void equipmentLabel(String value) { equipmentLabel = value; }\n\n    public void prepareEquipmentPreset() {')
edit(bot+'Bot.java','        stopGliding();\n\n        super.die(damageSource);','        var log = ((LegacyAgent) agent).getSkills().battleLog();\n        var context = log.deathContext(this, damageSource);\n        stopGliding();\n\n        super.die(damageSource);')
edit(bot+'Bot.java','        if (!isAlive()) {\n            this.dieCheck();','        if (!isAlive()) {\n            log.death(this, damageSource, context);\n            this.dieCheck();')
edit(bot+'Bot.java','        ItemStack result = getMainHandItem().finishUsingItem(level(), this);','        ((LegacyAgent) agent).getSkills().battleLog().event("items", "consume", this, "item", net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.item(getMainHandItem()));\n        ItemStack result = getMainHandItem().finishUsingItem(level(), this);')
edit(bot+'Bot.java','        level().addFreshEntity(pearl);','        level().addFreshEntity(pearl);\n        ((LegacyAgent) agent).getSkills().battleLog().event("items", "pearl", this, "entity", pearl.getUUID().toString());')
edit(bot+'DefaultEquipment.java','        bot.prepareEquipmentPreset();','        bot.equipmentLabel("vanilla:" + level);\n        bot.prepareEquipmentPreset();')
p=root/(bot+'DefaultEquipment.java'); s=p.read_text(encoding='utf-8'); a=s.index('private static void applyWarfare('); idx=s.index('        bot.prepareEquipmentPreset();',a); s=s[:idx]+'        bot.equipmentLabel("warfare:" + level);\n'+s[idx:];p.write_text(s,encoding='utf-8',newline='\n')
edit(bot+'EquipmentPresets.java','        public void apply(Bot bot, boolean applyTeam) {','        public void apply(Bot bot, boolean applyTeam) {\n            var store = net.nuggetmc.tplus.TerminatorPlus.getManager().presets();\n            bot.equipmentLabel(store.names().stream().filter(n -> store.get(n) == this).findFirst().orElse("custom_preset"));')
edit(compat+'WarfareAccess.java','        float maxHealth();','        float maxHealth();\n        default java.util.Map<String, Float> partHealth() { return java.util.Map.of(); }')
edit(compat+'superbwarfare/SuperbWarfareAccess.java','        bind("setEnergy", int.class);','        bind("setEnergy", int.class);\n        for (String name : List.of("getTurretHealth", "getLeftWheelHealth", "getRightWheelHealth", "getMainEngineHealth", "getSubEngineHealth")) bind(name);')
edit(compat+'superbwarfare/SuperbWarfareAccess.java','        @Override public int energy()','        @Override public Map<String, Float> partHealth() {\n            Map<String, Float> out = new java.util.LinkedHashMap<>();\n            for (String part : List.of("Turret", "LeftWheel", "RightWheel", "MainEngine", "SubEngine")) out.put(part, ((Number) v("get" + part + "Health")).floatValue());\n            return out;\n        }\n        @Override public int energy()')
edit(compat+'VehiclePilot.java','    boolean hasWaypoint(Entity vessel)','    String telemetry(Entity vessel) { Flight f = flights.get(vessel); return f == null ? "IDLE" : f.phase + "/" + f.route.state; }\n    boolean hasWaypoint(Entity vessel)')
edit(compat+'VehicleCrew.java','    public long shots(Bot bot)','    public String telemetry(Bot bot) { Member m = members.get(bot); return m == null ? "NONE" : (bot.getVehicle() == m.vessel.entity() ? "seat=" + m.vessel.seatIndex(bot) : "boarding=" + m.seat) + ";weapon=" + m.weaponState + ";nav=" + pilot.telemetry(m.vessel.entity()); }\n    public long shots(Bot bot)')
edit(skill+'BattleLog.java','vehicleHealth.put(e.getUUID(), v.health())','vehicleHealth.put(e.getUUID(), (double) v.health())')
edit(skill+'BattleLog.java','    public void goal(EnumTargetGoal next) {\n        if (goal != next && enabled()) {','    public void goal(EnumTargetGoal next) {\n        EnumTargetGoal previous = goal; goal = next;\n        if (previous != next && enabled()) {')
edit(skill+'BattleLog.java','        goal = next;\n    }\n    private void begin()', '    }\n    private void begin()')

# Retire the obsolete auto-generated buff expectation. Count all real thrown entities throughout the fight.
p=root/'src/main/java/net/nuggetmc/tplus/utils/SelfTest.java'; s=p.read_text(encoding='utf-8')
a=s.index('            list.add(new Scenario("team splash buffs at hardness '); b=s.index('\n        Bot[] friendA',a)
s=s[:a]+'''            int[] thrown = {0};
            list.add(new Scenario("team clustered infantry hardness " + value + " never generates splash buffs", () -> {
                run("bot settings hardness " + value); run("bot settings setgoal nearesthostile");
                buffer[0] = spawnBot("Buffer" + value, 1410.5 + (value - 8) * 40, y, 0.5);
                ally[0] = spawnBot("BuffAlly" + value, 1412.5 + (value - 8) * 40, y, 0.5);
                ally[0].setHardnessOverride(value); testTeam("selftest_buff" + value, buffer[0], ally[0]);
                spawnHusk(1415.5 + (value - 8) * 40, y, 5.5);
                java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                    if (event.getEntity() instanceof net.minecraft.world.entity.projectile.ThrownPotion p
                            && (p.getOwner() == buffer[0] || p.getOwner() == ally[0])) thrown[0]++;
                };
                NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            }, () -> buffer[0].getAliveTicks() >= 220 && thrown[0] == 0 &&
                    List.of(MobEffects.DAMAGE_BOOST, MobEffects.MOVEMENT_SPEED, MobEffects.JUMP).stream().noneMatch(ally[0]::hasEffect), 260, true));
        }
''' +s[b:];p.write_text(s,encoding='utf-8',newline='\n')
print('4.22 foundation edits applied')
