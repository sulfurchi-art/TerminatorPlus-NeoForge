exec((__import__('pathlib').Path(__file__).parent/'edit_4_22.py').read_text(encoding='utf-8').split("edit('gradle.properties'")[0])
# Ten has the existing unlimited-stock rule. Count actual placements, not invented stock consumption.
edit('src/main/java/net/nuggetmc/tplus/utils/SelfTest.java','noRepair[0] &= survivor[0].countItem(warfareItem("steel_block", 1).getItem()) == 60;','noRepair[0] &= survivor[0].countItem(warfareItem("steel_block", 1).getItem()) == 64 && skills.missiles().placed(survivor[0]) <= 4;')
# Remove retired full-shell movement/build helpers. The runtime only makes the four-cell barrier.
p=root/(skill+'MissileDefense.java');s=p.read_text(encoding='utf-8');a=s.index('    @Nullable private BlockPos findShelter(');b=s.index('    private void build(',a)
s=s[:a]+'    private static BlockPos feet(Bot bot) { return BlockPos.containing(bot.getX(), bot.getY() + 0.05, bot.getZ()); }\n'+s[b:]
s=s.replace('Vec3 origin, landing, coverDirection;', 'Vec3 origin, landing;').replace('        Entity coverMissile;\n','')
s=s.replace('s.path = null; s.coverMissile = null; s.coverDirection = null;', 's.path = null;')
a=s.index('    private void release(');b=s.index('    public void sweep()',a)
s=s[:a]+'''    private void release(Bot bot, State s) {
        if (s.engaged) {
            skills.memory(bot).nextTakeoff = Math.max(skills.memory(bot).nextTakeoff, bot.getServer().getTickCount() + 100);
            cleanup(bot, s, "threat_passed");
        }
        s.engaged = false; s.secured = false; s.ceilingEscape = false;
        s.buildStarted = -1; s.phase = "IDLE"; s.shelter = null; s.landing = null; s.path = null;
    }
    private void cleanup(Bot bot, State s, String reason) {
        int removed = 0, deferred = 0, owned = s.blocks.size();
        for (var entry : s.blocks.entrySet()) {
            var level = bot.getServer().getLevel(entry.getKey().dimension()); BlockPos pos = entry.getKey().pos();
            if (level == null || !level.hasChunkAt(pos)) { pending.put(entry.getKey(), entry.getValue()); deferred++; }
            else if (level.getBlockState(pos).equals(entry.getValue()) && level.removeBlock(pos, false)) removed++;
        }
        s.blocks.clear();
        if (s.buildStarted >= 0) skills.battleLog().event("missiles", "shelter_removed", bot, "blocks", removed, "ownedBlocks", owned, "pending", deferred,
                "durationTicks", bot.getServer().getTickCount() - s.buildStarted, "reason", reason, "removal", "matching_owned_blocks_only");
    }
''' +s[b:]
s=s.replace('if (s != null) cleanup(s);','if (s != null) cleanup(bot, s, "owner_or_ability_removed");')
p.write_text(s,encoding='utf-8',newline='\n')
p=root/'src/main/java/net/nuggetmc/tplus/utils/SelfTest.java';s=p.read_text(encoding='utf-8')
s=s.replace('!defense.enclosed(observer[0], observer[0].blockPosition())', 'defense.placed(observer[0]) == 0')
s=s.replace('baselineP95Ms={} loggingP95Ms={} dropped={}', 'baselineP95Ms={} loggingP95Ms={} baselineMaxMs={} loggingMaxMs={} dropped={}')
s=s.replace('onTicks.stream().sorted().skip(95).findFirst().orElse(-1.0), log.dropped()', 'onTicks.stream().sorted().skip(95).findFirst().orElse(-1.0), offTicks.stream().mapToDouble(Double::doubleValue).max().orElse(-1), onTicks.stream().mapToDouble(Double::doubleValue).max().orElse(-1), log.dropped()')
s=s.replace('            disabled[0] &= net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(config.save()).equals(config);','''            disabled[0] &= net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(config.save()).equals(config);
            var persisted = config.save(); persisted.putInt("SnapshotTicks", 60); persisted.getCompound("Categories").putBoolean("damage", false);
            var alternate = net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(persisted); log.configure(alternate);
            try { TerminatorPlus.getManager().saveSettings(); TerminatorPlus.getManager().reloadSettings(); disabled[0] &= log.config().equals(alternate); }
            catch (java.io.IOException e) { throw new IllegalStateException(e); }
            log.configure(config); TerminatorPlus.getManager().saveSettings();''')
# Existing real C4 scenarios now also keep running after release to validate bounded landing and stage logs.
a=s.index('private List<Scenario> warfareC4PassScenarios');b=s.index('private List<Scenario> warfareOrdnanceScenarios',a);part=s[a:b]
part=part.replace('double[] turn = {0}; boolean[] alive = {true};','double[] turn = {0}; boolean[] alive = {true}; java.nio.file.Path[] stageFile = {null}; boolean[] logClosed = {false};')
part=part.replace('attacker[0] = ordnanceBot("C4Pass" + trial,', 'run("bot log on"); stageFile[0] = skills.battleLog().path(); scenarioHooks.add(() -> skills.battleLog().toggle(false));\n                attacker[0] = ordnanceBot("C4Pass" + trial,')
part=part.replace('                if (trial == 2) return ended[0]', '''                if (attacker[0].getAliveTicks() < ended[0] + 65) return false;
                if (attacker[0].isGliding() || skills.memory(attacker[0]).getFlight() != net.nuggetmc.tplus.api.agent.legacyagent.skill.BotMemory.Flight.NONE) return false;
                if (!logClosed[0]) { skills.battleLog().toggle(false); logClosed[0] = true; }
                var stageRows = battleRows(stageFile[0]);
                if (stageRows.stream().noneMatch(o -> o.get("type").getAsString().equals("c4_end"))) return false;
                if (stageRows.stream().filter(o -> o.get("type").getAsString().equals("c4_end")).anyMatch(o -> o.get("orbitTicks").getAsLong() > 80)) return false;
                if (trial != 2 && !stageRows.stream().map(o -> o.get("type").getAsString()).collect(java.util.stream.Collectors.toSet()).containsAll(List.of("c4_approach", "c4_orbit_start", "c4_throw", "c4_detonate", "c4_exit"))) return false;
                if (trial == 2) return ended[0]''')
s=s[:a]+part+s[b:];p.write_text(s,encoding='utf-8',newline='\n')
p=root/(skill+'WarfareTactics.java');s=p.read_text(encoding='utf-8')
s=s.replace('if (s == null || s.mode == Mode.IDLE) return;', 'if (s == null) return;\n        if (s.mode == Mode.IDLE) { s.exiting = false; return; }')
a=s.index('    private boolean tickC4(');b=s.index('    private boolean finishC4(',a);part=s[a:b]
part=part.replace('{ cancel(bot); return false; }','{ return finishC4(bot, s, "EQUIPMENT_TARGET_OR_FLIGHT_LOST"); }')
s=s[:a]+part+s[b:];p.write_text(s,encoding='utf-8',newline='\n')
p=root/(skill+'TacticalSkill.java');s=p.read_text(encoding='utf-8')
for name in ['net.minecraft.world.entity.projectile.ThrownPotion','net.minecraft.world.item.alchemy.PotionContents','net.minecraft.world.item.alchemy.Potions','java.util.Collections','java.util.Random']:
    s=s.replace('import '+name+';\n','')
p.write_text(s,encoding='utf-8',newline='\n')
print('final C4 landing/log checks and actual shelter removal counts applied')
