exec((__import__('pathlib').Path(__file__).parent/'edit_4_22.py').read_text(encoding='utf-8').split("edit('gradle.properties'")[0])

edit(compat+'WarfareSupport.java','            status = "SBW 0.8.9.1 ready";','            net.nuggetmc.tplus.compat.superbwarfare.NativeBattleEvents.register();\n            status = "SBW 0.8.9.1 ready";')
edit(compat+'WarfareSupport.java','                if (!hold(bot, s, chosen, now)) return false;','                if (!hold(bot, s, chosen, now)) return false;\n                skills.battleLog().event("weapons", "weapon_select", bot, "weapon", net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.item(chosen.stack()), "reason", "available_ammo_range_damage_score");')
edit(compat+'VehicleCrew.java','        long expires, shots, aimSince','        long requestedAt;\n        long expires, shots, aimSince')
edit(compat+'VehicleCrew.java','        members.put(bot, new Member(vessel, seat, bot.server.getTickCount() + 400L));','        Member request = new Member(vessel, seat, bot.server.getTickCount() + 400L); request.requestedAt = bot.server.getTickCount();\n        members.put(bot, request);\n        skills.battleLog().event("vehicles", "board_request", bot, "requestedVehicle", skills.battleLog().identity(entity), "seat", seat, "distance", bot.distanceTo(entity));')
edit(compat+'VehicleCrew.java','                forget(bot); boardingRest.put(bot, now + 100); return false;','                skills.battleLog().event("vehicles", "board_fail", bot, "requestedVehicle", skills.battleLog().identity(entity), "reason", m.leaving ? "cancelled" : now > m.expires ? "timeout" : v.locked() ? "locked" : v.wreck() ? "wreck" : "ability_disabled", "waitTicks", now - m.requestedAt, "distance", bot.distanceTo(entity));\n                forget(bot); boardingRest.put(bot, now + 100); return false;')
edit(compat+'VehicleCrew.java','                m.expires = Long.MAX_VALUE; bot.walk(Vec3.ZERO);','                skills.battleLog().event("vehicles", "board_ok", bot, "waitTicks", now - m.requestedAt, "distance", bot.distanceTo(entity));\n                m.expires = Long.MAX_VALUE; bot.walk(Vec3.ZERO);')
edit(compat+'VehicleCrew.java','        if (m.leaving || evacuate || requestedLanding && v.engine().equals("HELICOPTER")) {','        if (m.leaving || evacuate || requestedLanding && v.engine().equals("HELICOPTER")) {')
edit(compat+'VehicleCrew.java','                bot.stopRiding(); forget(bot); boardingRest.put(bot, now + 200); return true;','                skills.battleLog().event("vehicles", "evacuate", bot, "reason", v.wreck() ? "wreck" : evacuate ? "low_vehicle_health" : "requested", "healthRatio", v.health() / v.maxHealth());\n                bot.stopRiding(); forget(bot); boardingRest.put(bot, now + 200); return true;')
edit(compat+'VehicleCrew.java','            if (v.changeSeat(bot, 0)) m.seat = 0;','            int from = m.seat;\n            if (v.changeSeat(bot, 0)) { m.seat = 0; skills.battleLog().event("vehicles", "seat_change", bot, "from", from, "to", 0, "reason", "vacant_driver"); }')
edit(compat+'VehicleCrew.java','        if (best == null) { m.weaponState = "no usable weapon"; return false; }','        if (best == null) {\n            if (!m.weaponState.equals("no usable weapon")) skills.battleLog().event("weapons", "weapon_select", bot, "weapon", null, "reason", "no usable weapon", "rejected", weapons.stream().map(w -> w.name() + ":ammo_range_lock_or_effective_damage").toList());\n            m.weaponState = "no usable weapon"; return false;\n        }')
edit(compat+'VehicleCrew.java','        if (v.selectedWeapon(bot) != best.index()) { v.selectWeapon(bot, best.index()); m.aimSince = now; m.lockSince = -1; }','        if (!best.name().equals(m.weaponState)) skills.battleLog().event("weapons", "weapon_select", bot, "weapon", best.name(), "reason", "seat_range_ammo_effective_damage_score");\n        if (v.selectedWeapon(bot) != best.index()) { v.selectWeapon(bot, best.index()); m.aimSince = now; m.lockSince = -1; }')

# Missile alerts still reach aircraft pilots. Construction requires a visible approaching real missile.
p=root/(skill+'MissileDefense.java'); s=p.read_text(encoding='utf-8')
s=s.replace('long scanAt, warnedUntil,','long nextShelter, buildStarted = -1;\n        String warningType = "LOCK_WARNING";\n        long scanAt, warnedUntil,')
s=s.replace('new Threat("LOCK_WARNING", null,','new Threat(s.warningType, null,')
s=s.replace('public void warning(ServerLevel level, Vec3 position, long now) {','public void warning(ServerLevel level, Vec3 position, long now) { warning(level, position, now, "locked_warning"); }\n    public void warning(ServerLevel level, Vec3 position, long now, String sound) {')
s=s.replace('s.warnedUntil = now + 12; s.scanAt = 0;','s.warnedUntil = now + 12; s.scanAt = 0; s.warningType = sound.equals("locking_warning") ? "LOCKING_WARNING" : "LOCKED_WARNING";\n            skills.battleLog().event("missiles", "missile_warning", bot, "warning", sound, "source", BattleLog.pos(position));')
a=s.index('        if (bot.isPassenger()) return false;'); b=s.index('        if (alert != null && !s.engaged)',a)
s=s[:a]+'''        if (bot.isPassenger()) return false; // The native vehicle pilot consumes the same threat.
        boolean airborne = bot.isGliding() || !bot.isBotOnGround() && bot.position().y - surface(bot, bot.position()) > 3;
        boolean inbound = alert != null && alert.missile() != null;
        if (!airborne && !inbound) { release(bot, s); return false; }
        if (alert == null && (!s.engaged || now - s.lastThreat > 35)) { release(bot, s); return false; }
''' +s[b:]
a=s.index('        if (s.shelter != null && (s.secured'); b=s.index('\n    private void fly(',a)
s=s[:a]+'''        if (!constructionThreat(bot, alert)) { s.phase = "TRACK_MISSILE"; return false; }
        if (now % 10 == 0) skills.battleLog().event("missiles", "missile_seen", bot, "missile", skills.battleLog().identity(alert.missile().entity()),
                "distance", bot.distanceTo(alert.missile().entity()), "approaching", true);
        if (s.shelter == null && now >= s.searchAt) {
            s.searchAt = now + 40; s.shelter = findNaturalCover(bot, alert);
            if (s.shelter != null) { s.path = skills.pathfinder().find(bot.getBotLevel(), bot, s.shelter, 12); s.pathIndex = 1; if (s.path == null) s.shelter = null; }
        }
        if (s.shelter != null && s.buildStarted < 0) {
            s.phase = "SEEK_SHELTER"; Vec3 goal = Vec3.atBottomCenterOf(s.shelter);
            if (s.path != null) {
                while (s.pathIndex < s.path.size() && bot.position().distanceTo(Vec3.atBottomCenterOf(s.path.get(s.pathIndex))) < 0.7) s.pathIndex++;
                if (s.pathIndex < s.path.size()) goal = Vec3.atBottomCenterOf(s.path.get(s.pathIndex));
            }
            if (bot.position().distanceTo(Vec3.atBottomCenterOf(s.shelter)) > 0.5) walk(bot, goal); else { bot.stand(); s.phase = "NATURAL_COVER"; }
            return true;
        }
        if (s.buildStarted < 0) {
            if (now < s.nextShelter) { s.phase = "SHELTER_COOLDOWN"; return false; }
            s.shelter = feet(bot); s.buildStarted = now; s.nextShelter = now + 500; s.placed = 0;
            skills.battleLog().event("missiles", "shelter_start", bot, "missile", skills.battleLog().identity(alert.missile().entity()), "cooldownTicks", 500, "limit", 4);
        }
        // One finite barrier, never repair blocks broken by an attacker.
        if (!s.secured && now - s.buildStarted <= 10 && s.placed < 4) build(bot, s, now);
        if (!s.secured && (s.placed >= 4 || now - s.buildStarted > 10)) {
            s.secured = true; skills.battleLog().event("missiles", "shelter_done", bot, "blocks", s.placed, "durationTicks", now - s.buildStarted);
        }
        s.phase = s.secured ? "SMALL_COVER" : "BUILD_SHELTER";
        return false; // Infantry can still move and fight; a barrier is not an invulnerability state.
    }
    private static boolean constructionThreat(Bot bot, @Nullable Threat alert) {
        if (alert == null || alert.missile() == null) return false;
        Entity missile = alert.missile().entity(); Vec3 to = bot.getBoundingBox().getCenter().subtract(missile.position());
        return to.lengthSqr() <= 96 * 96 && missile.getDeltaMovement().dot(to) > 0.05 && clear(bot, bot.getEyePosition(), missile.position());
    }
    @Nullable private BlockPos findNaturalCover(Bot bot, Threat alert) {
        BlockPos here = feet(bot); Vec3 incoming = alert.missile().entity().position();
        for (int r : new int[]{2, 4, 6, 8}) for (int i = 0; i < 8; i++) {
            BlockPos p = here.offset(Mth.floor(Math.cos(i * Math.PI / 4) * r), 0, Mth.floor(Math.sin(i * Math.PI / 4) * r));
            if (!loaded(bot, new AABB(p).inflate(1)) || !solid(bot, p.below()) || !SkillUtil.passable(bot.getBotLevel(), p) || !SkillUtil.passable(bot.getBotLevel(), p.above())) continue;
            Vec3 body = Vec3.atBottomCenterOf(p);
            if (!clear(bot, incoming, body.add(0, 0.5, 0)) && !clear(bot, incoming, body.add(0, 1.6, 0)) && !clear(bot, body.add(0, 5, 0), body.add(0, 1.6, 0))) return p;
        }
        return null;
    }
    private static List<BlockPos> barrier(State s) {
        Threat alert = s.threat;
        if (alert != null && (alert.missile().topAttack() || alert.origin().y > s.shelter.getY() + 6))
            return List.of(s.shelter.above(2), s.shelter.above(2).east(), s.shelter.above(2).south(), s.shelter.above(2).east().south());
        Vec3 from = alert.origin().subtract(Vec3.atCenterOf(s.shelter));
        net.minecraft.core.Direction toward = net.minecraft.core.Direction.getNearest(from.x, 0, from.z);
        BlockPos wall = s.shelter.relative(toward); var side = toward.getClockWise();
        return List.of(wall, wall.above(), wall.relative(side), wall.relative(side).above());
    }
''' +s[b:]
s=s.replace('for (BlockPos pos : shell(s.shelter)) {','for (BlockPos pos : barrier(s)) {\n            if (s.placed >= 4) break;')
s=s.replace('s.placed++; limit--; placementBudget--;','s.placed++; limit--; placementBudget--;\n                skills.battleLog().event("items", "block_place", bot, "reason", "missile_shelter", "block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString(), "blockPos", List.of(pos.getX(), pos.getY(), pos.getZ()));')
s=s.replace('if (s.engaged) { skills.memory(bot).nextTakeoff', 'if (s.engaged) {\n            if (s.buildStarted >= 0) skills.battleLog().event("missiles", "shelter_removed", bot, "blocks", s.blocks.size(), "durationTicks", bot.getServer().getTickCount() - s.buildStarted, "reason", "threat_passed", "removal", "matching_owned_blocks_only");\n            skills.memory(bot).nextTakeoff')
s=s.replace('s.phase = "IDLE"; s.shelter = null;', 's.buildStarted = -1; s.phase = "IDLE"; s.shelter = null;')
p.write_text(s,encoding='utf-8',newline='\n')
edit('src/main/java/net/nuggetmc/tplus/TerminatorPlus.java','level.getServer().getTickCount());','level.getServer().getTickCount(), id.getPath());')
print('native telemetry and finite missile barriers applied')
