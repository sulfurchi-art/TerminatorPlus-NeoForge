exec((__import__('pathlib').Path(__file__).parent/'edit_4_22.py').read_text(encoding='utf-8').split("edit('gradle.properties'")[0])
edit('src/main/java/net/nuggetmc/tplus/utils/SelfTest.java','spent[0] = Math.max(spent[0], 64 - protectedBot[0].countItem(warfareItem("steel_block", 1).getItem()));','if (protectedBot[0].isAlive()) spent[0] = Math.max(spent[0], 64 - protectedBot[0].countItem(warfareItem("steel_block", 1).getItem()));\n                    if (begun[0] >= 0 && (server.getTickCount() - begun[0]) % 20 == 0) LOGGER.info("[SelfTest] Small barrier top={} peak={} spentWhileAlive={} alive={} warned={}", top, peak[0], spent[0], protectedBot[0].isAlive(), warned[0]);')
# General block logging shares one placement implementation and supplies explicit skill reasons.
edit(bot+'Bot.java','    public void attemptBlockPlace(BlockPos loc, Block type, boolean down) {','    public void attemptBlockPlace(BlockPos loc, Block type, boolean down) { attemptBlockPlace(loc, type, down, down ? "build_route" : "legacy_build"); }\n    public void attemptBlockPlace(BlockPos loc, Block type, boolean down, String reason) {')
edit(bot+'Bot.java','            world.setBlockAndUpdate(loc, type.defaultBlockState());','            world.setBlockAndUpdate(loc, type.defaultBlockState());\n            ((LegacyAgent) agent).getSkills().battleLog().event("items", "block_place", this, "reason", reason, "block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(type).toString(), "blockPos", java.util.List.of(loc.getX(), loc.getY(), loc.getZ()));')
edit(skill+'TacticalSkill.java','bot.attemptBlockPlace(pos, skills.settings().buildBlock, false);','bot.attemptBlockPlace(pos, skills.settings().buildBlock, false, "tactical_cover");')
edit(skill+'MissileDefense.java','bot.attemptBlockPlace(pos, block, false);','bot.attemptBlockPlace(pos, block, false, "missile_shelter");')
p=root/(skill+'MissileDefense.java'); s=p.read_text(encoding='utf-8'); a=s.index('                skills.battleLog().event("items", "block_place",'); b=s.index('\n',a); s=s[:a]+s[b:];p.write_text(s,encoding='utf-8',newline='\n')
edit(bot+'Bot.java','        startFallFlying();\n        return true;','        startFallFlying();\n        ((LegacyAgent) agent).getSkills().battleLog().event("items", "elytra_takeoff", this, "reason", ((LegacyAgent) agent).getSkills().memory(this).getFlightPlan().name());\n        return true;')
edit(skill+'BattleLog.java','event("items", bot.isGliding() ? "elytra_takeoff" : "flight", bot, "state", flight);','event("items", "flight", bot, "state", flight);')
edit(compat+'VehicleCrew.java','    public void board(Bot bot, Entity entity, int requestedSeat) {','''    public void board(Bot bot, Entity entity, int requestedSeat) {
        skills.battleLog().event("vehicles", "board_request", bot, "requestedVehicle", skills.battleLog().identity(entity), "seat", requestedSeat, "distance", entity == null ? null : bot.distanceTo(entity));
        try { requestBoard(bot, entity, requestedSeat); }
        catch (IllegalArgumentException failure) { skills.battleLog().event("vehicles", "board_fail", bot, "reason", failure.getMessage(), "waitTicks", 0); throw failure; }
    }
    private void requestBoard(Bot bot, Entity entity, int requestedSeat) {''')
p=root/(compat+'VehicleCrew.java'); s=p.read_text(encoding='utf-8'); a=s.index('        skills.battleLog().event("vehicles", "board_request", bot, "requestedVehicle", skills.battleLog().identity(entity), "seat", seat,'); b=s.index('\n',a);s=s[:a]+s[b:];p.write_text(s,encoding='utf-8',newline='\n')
edit(compat+'VehicleCrew.java','                if (delta.lengthSqr() > 48 * 48) { forget(bot); return false; }','                if (delta.lengthSqr() > 48 * 48) { skills.battleLog().event("vehicles", "board_fail", bot, "reason", "vehicle_moved_out_of_range", "waitTicks", now - m.requestedAt, "distance", delta.length()); forget(bot); return false; }')
edit(compat+'VehicleCrew.java','                bot.stopRiding(); forget(bot); bot.startGliding();','                skills.battleLog().event("vehicles", "evacuate", bot, "reason", "airborne_wreck", "healthRatio", v.health() / v.maxHealth());\n                bot.stopRiding(); forget(bot); bot.startGliding();')
edit(compat+'VehicleCrew.java','        MountedWeapon best = null; double score = -Double.MAX_VALUE;','        java.util.List<String> rejected = skills.battleLog().enabled() ? new ArrayList<>() : null;\n        MountedWeapon best = null; double score = -Double.MAX_VALUE;')
edit(compat+'VehicleCrew.java','|| spec.shootDelay() > 200 || distance > Math.min(256, spec.range()) || spec.explosionRadius() > 0 && distance < spec.explosionRadius() + 5) continue;','|| spec.shootDelay() > 200 || distance > Math.min(256, spec.range()) || spec.explosionRadius() > 0 && distance < spec.explosionRadius() + 5) { if (rejected != null) rejected.add(w.name() + ":ammo_or_range_or_unsafe_blast"); continue; }')
edit(compat+'VehicleCrew.java','if (spec.seekTime() > 0 && !warfare.access().isVehicle(target) && !target.isPassenger()) continue;','if (spec.seekTime() > 0 && !warfare.access().isVehicle(target) && !target.isPassenger()) { if (rejected != null) rejected.add(w.name() + ":requires_vehicle_lock"); continue; }')
edit(compat+'VehicleCrew.java','if (effective <= 0) continue;','if (effective <= 0) { if (rejected != null) rejected.add(w.name() + ":no_effective_vehicle_damage"); continue; }')
edit(compat+'VehicleCrew.java','weapons.stream().map(w -> w.name() + ":ammo_range_lock_or_effective_damage").toList()','rejected')
edit(compat+'VehicleCrew.java','"reason", "seat_range_ammo_effective_damage_score");','"reason", "seat_range_ammo_effective_damage_score", "rejected", rejected);')

p=root/(skill+'WarfareTactics.java');s=p.read_text(encoding='utf-8')
s=s.replace('        if (s.drone != null && s.hand != null) {','''        if (s.mode == Mode.C4_APPROACH || s.mode == Mode.C4_EGRESS) {
            long now = bot.getServer().getTickCount();
            String reason = s.c4Result.equals("APPROACH") || s.c4Result.equals("PLACED") ? "CANCELLED" : s.c4Result;
            skills.battleLog().event("c4", "c4_end", bot, "reason", reason, "aborted", !reason.equals("DETONATED"), "durationTicks", now - s.started,
                    "orbitTicks", s.nearSince < 0 ? 0 : (s.placedAt > 0 ? s.placedAt : now) - s.nearSince, "orbits", s.turnDegrees / 360);
        }
        if (s.drone != null && s.hand != null) {''')
a=s.index('        skills.battleLog().event("c4", "c4_end", bot, "reason", reason,',s.index('private boolean finishC4(')); b=s.index('        s.c4Result = reason;',a);s=s[:a]+s[b:]
a=s.index('        if (now % 20 == 0) skills.battleLog().event("drones", "drone_sample",');b=s.index('\n',a);sample=s[a:b];s=s[:a]+s[b:]
idx=s.index('        Vec3 delta = goal.subtract(entity.position());',s.index('private void pilotDrone'))
s=s[:idx]+sample.replace('now % 20', 'bot.getServer().getTickCount() % 20').replace('identity(drone)', 'identity(entity)').replace('drone.getY()', 'entity.getY()').replace('drone.getDeltaMovement()', 'entity.getDeltaMovement()')+'\n'+s[idx:]
p.write_text(s,encoding='utf-8',newline='\n')

p=root/(skill+'BattleLog.java');s=p.read_text(encoding='utf-8')
s=s.replace('if (s != null && Set.of("drone_launch", "drone_attack", "c4_approach", "c4_throw", "c4_detonate", "shelter_done").contains(type))', 'if (s != null)')
s=s.replace('Watch w = watches.get(bot.getUUID()); BotMemory mem', 'stat(bot); Watch w = watches.get(bot.getUUID()); BotMemory mem')
s=s.replace('        if (config.enabled && !next.enabled)', '        retiring.removeIf(BattleLogWriter::closed);\n        if (config.enabled && !next.enabled)')
s=s.replace('    private final Map<UUID, Integer> decoys', '    private final Map<UUID, Entity> trackedVehicles = new LinkedHashMap<>();\n    private final Map<UUID, Bot> vehicleObservers = new HashMap<>();\n    private final Map<UUID, Map<String, Float>> vehicleParts = new HashMap<>();\n    private final Set<UUID> destroyed = new HashSet<>();\n    private final Map<UUID, Integer> decoys')
a=s.index('        Set<UUID> seen = new HashSet<>();',s.index('public void tick()'));b=s.index('\n    public static String item(',a)
s=s[:a]+'''        for (var terminator : skills.bots()) if (terminator instanceof Bot bot && bot.getVehicle() != null && skills.warfare().isVehicle(bot.getVehicle())) {
            Entity e = bot.getVehicle();
            if (trackedVehicles.size() >= 1024 && !trackedVehicles.containsKey(e.getUUID())) removeVehicle(trackedVehicles.keySet().iterator().next());
            trackedVehicles.put(e.getUUID(), e); vehicleObservers.put(e.getUUID(), bot);
        }
        for (UUID id : List.copyOf(trackedVehicles.keySet())) {
            Entity e = trackedVehicles.get(id); Bot bot = vehicleObservers.get(id);
            if (!skills.warfare().isVehicle(e)) { removeVehicle(id); continue; }
            var v = skills.warfare().access().vessel(e);
            Double previous = vehicleHealth.put(id, (double) v.health());
            Map<String, Float> parts = v.partHealth(), oldParts = vehicleParts.put(id, parts);
            if (previous != null && (previous > v.health() || oldParts != null && parts.entrySet().stream().anyMatch(p -> p.getValue() < oldParts.getOrDefault(p.getKey(), p.getValue()))))
                event("vehicles", "vehicle_damage", bot, "vehicle", vehicle(e, bot), "health", v.health(), "maxHealth", v.maxHealth(), "damage", previous - v.health(), "parts", parts);
            if (v.wreck() && destroyed.add(id)) event("vehicles", "vehicle_destroyed", bot, "vehicle", vehicle(e, bot), "health", v.health());
            Integer old = decoys.put(id, v.decoys());
            if (old != null && old > v.decoys()) event("missiles", "decoy", bot, "vehicle", vehicle(e, bot), "count", old - v.decoys(), "remaining", v.decoys());
            if (now() % config.snapshotTicks == 0 && !e.getPassengers().isEmpty()) event("snap", "vehicle_snap", bot, "vehicle", vehicle(e, bot), "pos", pos(e.position()), "health", v.health(), "maxHealth", v.maxHealth(), "parts", parts, "energy", v.energy(), "maxEnergy", v.maxEnergy(), "crew", e.getPassengers().stream().map(this::identity).toList());
            if (e.isRemoved() || e.getPassengers().isEmpty()) removeVehicle(id);
        }
    }
    private void removeVehicle(UUID id) {
        trackedVehicles.remove(id); vehicleObservers.remove(id); vehicleHealth.remove(id); vehicleParts.remove(id); decoys.remove(id); destroyed.remove(id);
    }
''' +s[b:]
s=s.replace('watches.clear(); stats.clear(); vehicleHealth.clear(); decoys.clear();','watches.clear(); stats.clear(); vehicleHealth.clear(); decoys.clear(); trackedVehicles.clear(); vehicleObservers.clear(); vehicleParts.clear(); destroyed.clear();')
s=s.replace('        for (var sink : retiring) try { sink.awaitClosed(2000); }','        long deadline = System.nanoTime() + 2_000_000_000L;\n        for (var sink : retiring) try { sink.awaitClosed(Math.max(1, (deadline - System.nanoTime()) / 1_000_000L)); }')
p.write_text(s,encoding='utf-8',newline='\n')
print('telemetry refinements and death-inventory fixture correction applied')
