exec((__import__('pathlib').Path(__file__).parent/'edit_4_22.py').read_text(encoding='utf-8').split("edit('gradle.properties'")[0])
p=root/(skill+'WarfareTactics.java'); s=p.read_text(encoding='utf-8')
s=s.replace('long progressAt, placedAt;', 'long progressAt, placedAt, nearSince = -1, firstAttack = -1, exitStarted;\n        double turnDegrees; float lastYaw; boolean exiting, pendingFire; int fireAmmo;\n        String fireKind; double fireDistance;')
s=s.replace('if (s.drone != null && s.hand != null) s.drone.stop(bot, s.hand.stack);','''if (s.drone != null && s.hand != null) {
            skills.battleLog().event("drones", "drone_lost", bot, "drone", skills.battleLog().identity(s.drone.entity()), "reason", s.returning ? "mission_returned" : !bot.isAlive() ? "operator_dead_entity_retained" : "control_released", "durationTicks", bot.getServer().getTickCount() - s.started);
            s.drone.stop(bot, s.hand.stack);
        }''')
s=s.replace('s.mode = Mode.IDLE;\n        s.nextAttempt', 's.mode = Mode.IDLE; s.exiting = false;\n        s.nextAttempt')
s=s.replace('        if (s != null && s.mode != Mode.IDLE) {','''        if (s != null && s.exiting) {
            if (!skills.enabled(bot, "c4") || bot.isPassenger() || !bot.isAlive() || bot.isConsuming()) { s.exiting = false; return false; }
            BotMemory mem = skills.memory(bot);
            double floor = SkillUtil.surfaceY(bot.getBotLevel(), bot.position());
            if (!bot.isGliding() || bot.getY() - floor < 2 || now - s.exitStarted >= 60) {
                s.exiting = false; mem.flight = BotMemory.Flight.NONE; mem.maceDrop = false; bot.stopGliding();
                skills.battleLog().event("c4", "c4_exit", bot, "durationTicks", now - s.exitStarted, "reason", "bounded_descent_finished");
                return false;
            }
            // Keep the same pass heading and descend with native controls. Never re-target the flight centre.
            bot.setLook(Mth.approachDegrees(bot.getYRot(), SkillUtil.yawTo(Vec3.ZERO, s.passDirection), 12), Mth.approach(bot.getXRot(), 65, 12));
            return true;
        }
        if (s != null && s.mode != Mode.IDLE) {''')
s=s.replace('s.c4Result = "APPROACH";','''s.c4Result = "APPROACH"; s.nearSince = -1; s.turnDegrees = 0; s.lastYaw = bot.getYRot();
                skills.battleLog().event("c4", "c4_approach", bot, "target", skills.battleLog().identity(enemy.getRootVehicle()), "mode", "single_air_pass", "orbitLimitTicks", 80);''')
s=s.replace('s.home = home; s.returning = false;', 's.home = home; s.returning = false; s.firstAttack = -1; s.pendingFire = false;')
s=s.replace('s.drone = remote; s.hand = hand; s.mode = Mode.DRONE;', '''s.drone = remote; s.hand = hand; s.mode = Mode.DRONE;
        skills.battleLog().event("drones", "drone_launch", bot, "drone", skills.battleLog().identity(remote.entity()), "target", skills.battleLog().identity(enemy.getRootVehicle()), "ammo", remote.ammo(), "firstAttackLimitTicks", 100);
        skills.battleLog().event("drones", "drone_target", bot, "drone", skills.battleLog().identity(remote.entity()), "target", skills.battleLog().identity(enemy.getRootVehicle()));''')
s=s.replace('        Entity drone = s.drone.entity();\n        if (drone.isRemoved()', '''        Entity drone = s.drone.entity();
        if (s.pendingFire && (s.drone.ammo() < s.fireAmmo || s.fireKind.equals("self_blast") && drone.isRemoved())) {
            s.pendingFire = false; s.drops++;
            if (s.firstAttack < 0) s.firstAttack = now;
            skills.battleLog().event("drones", "drone_attack", bot, "drone", skills.battleLog().identity(drone), "kind", s.fireKind,
                    "horizontalDistance", s.fireDistance, "firstAttackLatencyTicks", s.firstAttack - s.started, "durationTicks", now - s.started, "round", s.drops, "confirmedBy", s.fireKind.equals("drop") ? "native_ammo_consumed" : "native_fire_and_entity_removed");
        }
        if (now % 20 == 0) skills.battleLog().event("drones", "drone_sample", bot, "drone", skills.battleLog().identity(drone), "height", drone.getY(), "vy", drone.getDeltaMovement().y, "mode", s.returning ? "return" : "attack");
        if (drone.isRemoved()''')
s=s.replace('        if (payload == null || s.drone.ammo() == 0 || !observed && now - s.seen > 60) s.returning = true;', '''        boolean wasReturning = s.returning;
        if (payload == null || s.drone.ammo() == 0 || !observed && now - s.seen > 60 || s.firstAttack < 0 && now - s.started >= 100) s.returning = true;''')
s=s.replace('        if (s.returning) {\n            if (drone.position()', '''        if (s.returning && !wasReturning) skills.battleLog().event("drones", "drone_return", bot, "drone", skills.battleLog().identity(drone), "durationTicks", now - s.started, "reason", s.firstAttack < 0 ? "first_attack_deadline_or_unsafe" : "ammo_or_target_lost");
        if (s.returning) {
            if (drone.position()''')
s=s.replace('error < 3.2 && drone.getY() > s.observed.y + 3', 'error < Math.max(6.5, payload.radius() + 1) && drone.getY() > s.observed.y + 3')
s=s.replace('s.drone.fire(bot); s.drops++; s.nextFire = now + (skills.hardness(bot).level() == 10 ? 20 : skills.hardness(bot).level() == 9 ? 30 : 40);', '''s.fireAmmo = s.drone.ammo(); s.pendingFire = true; s.fireKind = payload.kamikaze() ? "self_blast" : "drop";
                s.fireDistance = horizontal; s.drone.fire(bot);
                s.nextFire = now + (skills.hardness(bot).level() == 10 ? 16 : skills.hardness(bot).level() == 9 ? 20 : 30);''')
a=s.index('        int vertical = delta.y - velocity.y'); b=s.index('        keys |= vertical;',a)
s=s[:a]+'''        double heightError = delta.y - velocity.y * 6;
        int vertical = heightError > 0.5 ? 16 : heightError < -0.5 ? 32 : 0;
        // SBW holdTickY survives an up/down reversal. A neutral tick resets it, preventing accelerating oscillation.
        if (vertical != 0 && s.lastVertical != 0 && (vertical != s.lastVertical || s.verticalTicks >= 2)) vertical = 0;
        s.verticalTicks = vertical == 0 ? 0 : vertical == s.lastVertical ? s.verticalTicks + 1 : 1;
        s.lastVertical = vertical;
''' +s[b:]
s=s.replace('            double distance = SkillUtil.horizontalDistance(bot.position(), predicted);','''            double distance = SkillUtil.horizontalDistance(bot.position(), predicted);
            if (distance < 24 && s.nearSince < 0) {
                s.nearSince = now; skills.battleLog().event("c4", "c4_orbit_start", bot, "approachTicks", now - s.started);
            }
            s.turnDegrees += Math.abs(Mth.wrapDegrees(bot.getYRot() - s.lastYaw)); s.lastYaw = bot.getYRot();
            if (s.nearSince >= 0 && now - s.nearSince >= 80) return finishC4(bot, s, "ORBIT_LIMIT");''')
s=s.replace('now - s.started > 120', 'now - s.started >= 100')
s=s.replace('                    s.charge = charge.getUUID(); s.mode', '''                    skills.battleLog().event("c4", "c4_throw", bot, "charge", skills.battleLog().identity(charge), "durationTicks", now - s.started, "approachTicks", s.nearSince < 0 ? now - s.started : s.nearSince - s.started, "orbitTicks", s.nearSince < 0 ? 0 : now - s.nearSince, "orbits", s.turnDegrees / 360);
                    s.charge = charge.getUUID(); s.mode''')
s=s.replace('detonator.getItem().use(bot.level(), bot, InteractionHand.MAIN_HAND); s.detonations++;','''detonator.getItem().use(bot.level(), bot, InteractionHand.MAIN_HAND); s.detonations++;
                        skills.battleLog().event("c4", "c4_detonate", bot, "durationTicks", now - s.placedAt, "totalTicks", now - s.started, "ownedCharges", all.size());''')
s=s.replace('        s.c4Result = reason; cancel(bot); return false;', '''        long now = bot.getServer().getTickCount();
        skills.battleLog().event("c4", "c4_end", bot, "reason", reason, "aborted", !reason.equals("DETONATED"), "durationTicks", now - s.started,
                "orbitTicks", s.nearSince < 0 ? 0 : Math.min(80, (s.placedAt > 0 ? s.placedAt : now) - s.nearSince), "orbits", s.turnDegrees / 360);
        s.c4Result = reason; cancel(bot);
        s.exiting = bot.isGliding(); s.exitStarted = now;
        return s.exiting;''')
p.write_text(s,encoding='utf-8',newline='\n')
print('bounded ordnance and neutral drone controls applied')
