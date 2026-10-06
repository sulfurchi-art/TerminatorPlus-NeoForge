package net.nuggetmc.tplus.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds;
import net.nuggetmc.tplus.compat.WarfareAccess.Vessel;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/** Feedback controls emit native keys and mouse motion, never position, rotation or velocity writes. */
final class VehiclePilot {
    private static final class Flight {
        Vec3 waypoint, home, extend, retreat, lastEnemy, antiAirEntry, separation;
        long nextSeparation;
        int antiAirTarget = -1;
        long seenAt = -1000, hurtUntil, nextDecoy, orbitUntil; float health = -1; boolean antiAir; int pass;
        int threatId = -1; boolean dangerous; long threatAt;
        String phase = "IDLE";
        long attackSince = -1, extendUntil, lastTick;
        float pitch, yaw, roll;
        boolean sampled, aiming; float aimYaw, aimPitch;
    }
    private final WarfareSupport warfare;
    VehiclePilot(WarfareSupport warfare) { this.warfare = warfare; }
    private final Map<Entity, Flight> flights = new HashMap<>();

    void go(Vessel vessel, Vec3 point) {
        Flight f = flights.computeIfAbsent(vessel.entity(), v -> new Flight());
        f.waypoint = point; f.extend = null; f.attackSince = -1;
    }
    void forget(Entity vessel) { flights.remove(vessel); }
    void clear() { flights.clear(); }
    String describe(Entity vessel) { Flight f = flights.get(vessel); return f == null ? "IDLE" : f.phase; }
    boolean hasWaypoint(Entity vessel) { Flight f = flights.get(vessel); return f != null && f.waypoint != null; }

    boolean tick(Bot bot, Vessel vessel, @Nullable LivingEntity enemy, long now, boolean land, boolean waitForCrew) {
        Entity e = vessel.entity();
        Flight f = flights.computeIfAbsent(e, v -> new Flight());
        if (f.home == null) f.home = e.position();
        f.lastTick = now;
        int id = enemy == null ? -1 : enemy.getId();
        if (id != f.threatId || now >= f.threatAt) {
            f.threatId = id; f.threatAt = now + 20;
            f.dangerous = enemy != null && warfare.canThreaten(vessel, enemy);
            f.antiAir = enemy != null && warfare.antiAirThreat(vessel, enemy);
            if (!f.dangerous) f.retreat = null;
        }
        if (enemy != null) { f.lastEnemy = enemy.getRootVehicle().position(); f.seenAt = now; }
        if (f.health > 0 && vessel.health() < f.health) f.hurtUntil = now + 80;
        f.health = vessel.health();
        if (now % 100 == 0) flights.entrySet().removeIf(entry -> entry.getKey().isRemoved() || now - entry.getValue().lastTick > 600);
        if (vessel.engine().equals("HELICOPTER")) return helicopter(bot, vessel, enemy, now, f, land, waitForCrew);
        ground(bot, vessel, enemy, f, land, waitForCrew);
        return e.onGround() || vessel.engine().equals("FIXED");
    }

    private void ground(Bot bot, Vessel vessel, @Nullable LivingEntity enemy, Flight f, boolean exit, boolean wait) {
        Entity e = vessel.entity();
        if (exit || wait || vessel.engine().equals("FIXED")) {
            f.phase = exit ? "STOPPING" : wait ? "ASSEMBLING" : "EMPLACED";
            vessel.input(bot, 16, 0, 0); return;
        }
        Vec3 goal = f.waypoint;
        boolean retreat = f.dangerous && vessel.health() < vessel.maxHealth() * 0.35;
        if (goal == null && enemy != null) {
            Vec3 delta = enemy.position().subtract(e.position()).multiply(1, 0, 1);
            if (retreat) {
                f.phase = "RETREAT";
                if (f.retreat == null) f.retreat = MovementBounds.clamp(e, e.position().subtract(delta.normalize().scale(24)), 8);
                goal = f.retreat;
            } else if (!warfare.isVehicle(enemy.getRootVehicle()) && (f.separation != null || f.lastTick >= f.nextSeparation
                    && delta.lengthSqr() < 36 && (delta.lengthSqr() < 1 || enemy.getY() > e.getY() + e.getBbHeight() * 0.5))) {
                if (f.separation == null) f.separation = MovementBounds.clamp(e, e.position().add(e.getLookAngle().multiply(1, 0, 1).normalize().scale(14)), 8);
                f.phase = "CLOSE_SEPARATION"; goal = f.separation;
            } else if (!warfare.isVehicle(enemy.getRootVehicle()) && delta.lengthSqr() < 12 * 12 && ramSafe(bot, e, enemy)) {
                f.phase = "RAM"; goal = enemy.position().add(delta.normalize().scale(8));
            } else if (delta.lengthSqr() > 48 * 48 || !clearLine(e, e.getBoundingBox().getCenter(), enemy.getBoundingBox().getCenter())) {
                f.phase = "APPROACH"; goal = enemy.position();
            } else { f.phase = "FIRING_POSITION"; vessel.input(bot, 16, 0, 0); return; }
        }
        if (goal == null && enemy == null) {
            if (f.lastEnemy != null && f.lastTick - f.seenAt <= 300 && e.position().distanceToSqr(f.lastEnemy) > 36) { goal = f.lastEnemy; f.phase = "SEARCH_LAST_SEEN"; }
            else if (f.home != null && e.position().distanceToSqr(f.home) > 48 * 48) { goal = f.home; f.phase = "RALLY"; }
        }
        if (goal == null) { f.phase = "IDLE"; vessel.input(bot, 16, 0, 0); return; }
        goal = MovementBounds.clamp(e, goal, 8);
        Vec3 delta = goal.subtract(e.position()).multiply(1, 0, 1);
        Vec3 coasting = e.position().add(e.getDeltaMovement().multiply(20, 0, 20));
        if (!MovementBounds.contains(e, coasting, 5) && e.getDeltaMovement().horizontalDistance() > 0.08) {
            f.phase = "BORDER_BRAKE"; vessel.input(bot, 16, 0, 0); return;
        }
        if (delta.lengthSqr() < 16) {
            f.waypoint = null; if (f.separation != null) { f.separation = null; f.nextSeparation = f.lastTick + 60; }
            f.phase = retreat ? "HOLD_COVER" : "ARRIVED"; vessel.input(bot, 16, 0, 0); return;
        }
        Vec3 route = groundRoute(e, delta);
        if (route == null) { f.phase = "OBSTRUCTED"; vessel.input(bot, 16, 0, 0); return; }
        float yaw = yaw(route), error = Mth.wrapDegrees(yaw - e.getYRot());
        int keys = error > 4 ? 2 : error < -4 ? 1 : 0;
        if (Math.abs(error) < 85) keys |= 4; else keys |= 16;
        if (Math.abs(error) > 50 && e.getDeltaMovement().horizontalDistance() > 0.12) keys |= 16;
        if (f.waypoint != null) f.phase = "DRIVING";
        if (vessel.threatened()) keys |= 64;
        vessel.input(bot, keys, 0, 0);
    }

    private boolean helicopter(Bot bot, Vessel vessel, @Nullable LivingEntity enemy, long now, Flight f, boolean land, boolean wait) {
        Entity e = vessel.entity(); Vec3 pos = e.position(), motion = e.getDeltaMovement();
        if (wait && e.onGround()) { f.phase = "ASSEMBLING"; vessel.input(bot, 0, 0, 0); return true; }
        if (land && e.onGround()) { f.phase = "LANDED"; vessel.input(bot, 32, 0, 0); return true; }
        Vec3 goal;
        boolean incoming = vessel.threatened() || now < f.hurtUntil;
        boolean landing = land || vessel.energy() < Math.min(20000, vessel.maxEnergy() / 100);
        if (landing) {
            // Pick a loaded clear patch below/near the aircraft; avoid an automatic teleport or midair ejection.
            goal = landingPoint(e);
            if (goal == null) { f.phase = "NO_LANDING_SITE"; goal = pos.add(0, 4, 0); landing = false; }
            else f.phase = "LANDING";
        } else if (f.waypoint != null) {
            Vec3 horizontal = f.waypoint.subtract(pos).multiply(1, 0, 1);
            boolean near = horizontal.lengthSqr() < 64;
            goal = f.waypoint;
            if (!near) goal = goal.add(0, 18, 0);
            else { landing = true; f.phase = "LANDING"; }
            if (near && e.onGround()) {
                f.waypoint = null; f.phase = "ARRIVED"; vessel.input(bot, 32, 0, 0); return true;
            }
            if (!near) f.phase = "TRANSIT";
        } else if (enemy != null) {
            Vec3 enemyPos = enemy.position();
            double dist = enemyPos.subtract(pos).horizontalDistance();
            if (f.antiAir && f.antiAirTarget != enemy.getId()) {
                Vec3 away = pos.subtract(enemyPos).multiply(1, 0, 1).normalize();
                f.antiAirEntry = MovementBounds.clamp(e, enemyPos.add(-away.z * 55, 10, away.x * 55).add(away.scale(25)), 15);
                f.antiAirTarget = enemy.getId();
            }
            if (f.antiAirEntry != null && pos.subtract(f.antiAirEntry).horizontalDistance() < 12) f.antiAirEntry = null;
            if (f.extend != null && (now >= f.extendUntil || pos.distanceToSqr(f.extend) <= 64)) {
                f.extend = null;
                boolean benches = vessel.seats().size() > 3 && (vessel.passenger(2) != null || vessel.passenger(3) != null);
                if (benches && !incoming) f.orbitUntil = now + 80;
            }
            if (f.antiAirEntry != null && !incoming && f.extend == null) {
                goal = f.antiAirEntry; f.phase = "FLANK_APPROACH";
            } else if (now < f.orbitUntil && !incoming) {
                Vec3 radial = pos.subtract(enemyPos).multiply(1, 0, 1).normalize(); int sign = f.pass % 2 == 0 ? 1 : -1;
                Vec3 tangent = new Vec3(-radial.z * sign, 0, radial.x * sign);
                goal = pos.add(tangent.scale(25)).add(radial.scale(Mth.clamp(45 - dist, -10, 10)));
                goal = new Vec3(goal.x, enemyPos.y + 16, goal.z); f.phase = "GUNNER_ORBIT";
            } else if (f.extend != null && now < f.extendUntil && pos.distanceToSqr(f.extend) > 64) {
                goal = f.extend; f.phase = incoming ? "EVADE" : "EXTEND";
            } else if (dist < 28 || f.dangerous && vessel.health() < vessel.maxHealth() * 0.4 || incoming
                    || f.attackSince >= 0 && now - f.attackSince >= 100) {
                Vec3 away = pos.subtract(enemyPos).multiply(1, 0, 1).normalize();
                if (away.lengthSqr() < 0.5) away = e.getLookAngle().multiply(1, 0, 1).normalize();
                int sign = ++f.pass % 2 == 0 ? 1 : -1;
                Vec3 lateral = new Vec3(-away.z * sign, 0, away.x * sign);
                f.extend = MovementBounds.clamp(e, pos.add(away.scale(32)).add(lateral.scale(28)).add(0, incoming ? -6 : 5, 0), 15);
                f.extendUntil = now + 120;
                f.attackSince = -1; goal = f.extend; f.phase = incoming ? "EVADE" : "EXTEND";
            } else {
                f.extend = null;
                goal = enemyPos.add(0, f.antiAir ? 10 : 18, 0);
                if (f.antiAir && (e.onGround() || dist > 90)) {
                    Vec3 away = pos.subtract(enemyPos).multiply(1, 0, 1).normalize();
                    Vec3 side = new Vec3(-away.z, 0, away.x);
                    goal = enemyPos.add(side.scale(55)).add(away.scale(25)).add(0, 10, 0);
                }
                if (dist < 110 && !e.onGround() && pos.y > groundHeight(e, pos) + 7) {
                    Vec3 toEnemy = enemy.getRootVehicle().getBoundingBox().getCenter().subtract(vessel.muzzle(bot)).normalize();
                    if (f.attackSince < 0 && vessel.direction(bot).normalize().dot(toEnemy) > Math.cos(Math.toRadians(3))) f.attackSince = now;
                    f.phase = "ATTACK_PASS";
                } else f.phase = f.antiAir ? "FLANK_APPROACH" : "APPROACH";
            }
        } else if (f.lastEnemy != null && now - f.seenAt <= 300 && pos.subtract(f.lastEnemy).horizontalDistance() > 12) {
            goal = f.lastEnemy.add(0, 16, 0); f.phase = "SEARCH_LAST_SEEN";
        } else if (f.home != null && pos.subtract(f.home).horizontalDistance() > 48) {
            goal = f.home.add(0, 18, 0); f.phase = "RALLY";
        } else {
            goal = landingPoint(e); landing = goal != null;
            if (goal == null) goal = f.home.add(0, 18, 0);
            f.phase = landing ? "LANDING" : "HOLD";
        }
        goal = MovementBounds.clamp(e, goal, 12 + motion.horizontalDistance() * 20);
        Vec3 predicted = pos.add(motion.multiply(25, 0, 25));
        if (!MovementBounds.contains(e, predicted, 8)) {
            var b = e.level().getWorldBorder(); goal = new Vec3(b.getCenterX(), goal.y, b.getCenterZ()); f.phase = "BORDER_TURN";
        }
        Vec3 delta = goal.subtract(pos);
        double horizontal = delta.horizontalDistance();
        double ground = groundHeight(e, pos);
        double desiredAltitude = landing ? goal.y + (horizontal > 8 ? 8 : 0.15) : Math.max(goal.y, ground + (incoming || f.antiAir ? 8 : 16));
        desiredAltitude = Math.min(e.level().getMaxBuildHeight() - 10, desiredAltitude);
        if (!landing && !incoming && pos.y < ground + 7) f.phase = "TAKEOFF";
        Vec3 ahead = pos.add(motion.multiply(10, 0, 10)).add(e.getLookAngle().multiply(6, 0, 6));
        boolean obstructed = !flightClear(e, ahead);
        if (obstructed) { desiredAltitude = Math.max(desiredAltitude, pos.y + 7); f.phase = "AVOID"; }
        float desiredYaw = horizontal > 3 ? yaw(delta) : e.getYRot();
        float yawError = Mth.wrapDegrees(desiredYaw - e.getYRot());
        double speed = motion.dot(e.getLookAngle().multiply(1, 0, 1).normalize());
        float desiredPitch = (float) Mth.clamp(horizontal > 10 ? 18 - speed * 20 : -speed * 35, -18, 22);
        if (Math.abs(yawError) > 50 || obstructed || pos.y < ground + 6) desiredPitch = (float) Mth.clamp(-speed * 25, -12, 12);
        boolean aiming = f.phase.equals("ATTACK_PASS") && enemy != null && horizontal > 25 && !vessel.seats().get(0).rotateHead();
        if (aiming) {
            Vec3 origin = vessel.muzzle(bot), fire = vessel.direction(bot).normalize();
            var gun = vessel.weaponGun(bot, vessel.selectedWeapon(bot));
            Vec3 aim = enemy.getRootVehicle().getBoundingBox().getCenter();
            if (gun != null && gun.specs().velocity() > 0) {
                double time = Math.min(25, origin.distanceTo(aim) / gun.specs().velocity());
                aim = aim.add(enemy.getDeltaMovement().scale(time)).add(0, gun.specs().gravity() * time * time / 2, 0);
            }
            Vec3 toTarget = aim.subtract(origin);
            desiredYaw = e.getYRot() + Mth.wrapDegrees(yaw(toTarget) - yaw(fire));
            yawError = Mth.wrapDegrees(desiredYaw - e.getYRot());
            float targetPitch = (float) -Math.toDegrees(Math.atan2(toTarget.y, toTarget.horizontalDistance()));
            float firePitch = (float) -Math.toDegrees(Math.atan2(fire.y, fire.horizontalDistance()));
            desiredPitch = Mth.clamp(e.getXRot() + Mth.wrapDegrees(targetPitch - firePitch), -18, 40);
        }
        float yawRate = f.sampled ? Mth.wrapDegrees(e.getYRot() - f.yaw) : 0;
        float pitchRate = f.sampled ? Mth.wrapDegrees(e.getXRot() - f.pitch) : 0;
        float rollRate = f.sampled ? Mth.wrapDegrees(vessel.roll() - f.roll) : 0;
        f.yaw = e.getYRot(); f.pitch = e.getXRot(); f.roll = vessel.roll(); f.sampled = true;
        double mouseX = Mth.clamp(yawError * 0.3 - yawRate * 2, -20, 20);
        double mouseY = Mth.clamp((desiredPitch - e.getXRot()) * 0.55 - pitchRate * 7, -20, 20);
        if (aiming) {
            double yawLead = f.aiming ? Mth.clamp(Mth.wrapDegrees(desiredYaw - f.aimYaw), -3, 3) / Math.max(0.04, 2 * vessel.rotor()) : 0;
            double pitchLead = f.aiming ? Mth.clamp(desiredPitch - f.aimPitch, -3, 3) / Math.max(0.03, 1.5 * vessel.rotor()) : 0;
            mouseX = Mth.clamp(yawError * 1.2 - yawRate * 0.5 + yawLead, -20, 20);
            mouseY = Mth.clamp((desiredPitch - e.getXRot()) * 1.8 - pitchRate + pitchLead, -20, 20);
        }
        f.aiming = aiming; f.aimYaw = desiredYaw; f.aimPitch = desiredPitch;
        double verticalGoal = Mth.clamp((desiredAltitude - pos.y) * 0.06, landing ? -0.16 : -0.2, 0.35);
        if (landing && pos.y - desiredAltitude < 3) verticalGoal = Math.max(-0.07, verticalGoal);
        double liftError = verticalGoal - motion.y;
        int keys = !vessel.engineStarted() && !landing || liftError > 0.025 ? 4 : liftError < -0.025 ? 32 : 0;
        double rollError = -vessel.roll() - rollRate * 5 - mouseX * 0.015;
        if (rollError > 2) keys |= 2; else if (rollError < -2) keys |= 1;
        boolean hover = landing || f.phase.equals("HOLD");
        if (!e.onGround() && vessel.hovering() != hover) keys |= 16;
        if (incoming && now >= f.nextDecoy && vessel.decoys() > 0) { keys |= 64; f.nextDecoy = now + 40; }
        vessel.input(bot, keys, mouseX, mouseY);
        return landing && e.onGround();
    }

    private static boolean ramSafe(Bot driver, Entity vehicle, LivingEntity enemy) {
        var swept = vehicle.getBoundingBox().minmax(enemy.getBoundingBox()).inflate(1);
        return vehicle.level().getEntities(vehicle, swept).stream().noneMatch(other -> other != driver
                && other.getRootVehicle() != vehicle && other instanceof LivingEntity && driver.isAlliedTo(other));
    }
    private static Vec3 groundRoute(Entity e, Vec3 delta) {
        Vec3 wanted = delta.normalize(), best = null; double score = -Double.MAX_VALUE;
        for (int degrees : new int[]{0, 30, -30, 60, -60, 90, -90}) {
            Vec3 direction = wanted.yRot((float) Math.toRadians(degrees));
            Vec3 end = e.position().add(direction.scale(5));
            if (!MovementBounds.contains(e, end, 8) || !e.level().hasChunkAt(BlockPos.containing(end))) continue;
            Vec3 eye = e.position().add(0, 1.2, 0);
            if (!clearLine(e, eye, eye.add(direction.scale(5)))) continue;
            double floor = groundHeight(e, end);
            if (Math.abs(floor - e.getY()) > Math.max(1.2, e.maxUpStep())) continue;
            double value = direction.dot(wanted) * 10 - Math.abs(degrees) * 0.01;
            if (value > score) { score = value; best = direction; }
        }
        return best;
    }
    private static Vec3 landingPoint(Entity e) {
        for (int radius : new int[]{0, 6, 12}) for (int i = 0; i < (radius == 0 ? 1 : 8); i++) {
            Vec3 p = e.position().add(Math.cos(i * Math.PI / 4) * radius, 0, Math.sin(i * Math.PI / 4) * radius);
            if (!MovementBounds.contains(e, p, 8) || !e.level().hasChunkAt(BlockPos.containing(p))) continue;
            p = new Vec3(p.x, groundHeight(e, p), p.z);
            BlockPos floor = BlockPos.containing(p).below();
            if (!e.level().getBlockState(floor).isSolid() || !e.level().getFluidState(floor).isEmpty()) continue;
            double width = Math.max(2, e.getBbWidth() / 2);
            AABB volume = new AABB(p.x - width, p.y + 0.15, p.z - width, p.x + width, p.y + Math.max(3, e.getBbHeight()), p.z + width);
            if (!e.level().getBlockCollisions(e, volume).iterator().hasNext()) return p;
        }
        return null;
    }
    private static boolean flightClear(Entity e, Vec3 point) {
        if (!MovementBounds.contains(e, point, 8) || !e.level().hasChunkAt(BlockPos.containing(point))) return false;
        double width = Math.max(1.5, e.getBbWidth() / 2);
        AABB volume = new AABB(point.x - width, point.y + 0.3, point.z - width,
                point.x + width, point.y + Math.max(3, e.getBbHeight()), point.z + width);
        return !e.level().getBlockCollisions(e, volume).iterator().hasNext();
    }
    private static double groundHeight(Entity e, Vec3 point) {
        if (!e.level().hasChunkAt(BlockPos.containing(point))) return e.getY();
        return e.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(point.x), Mth.floor(point.z));
    }
    private static boolean clearLine(Entity e, Vec3 start, Vec3 end) {
        return e.level().hasChunkAt(BlockPos.containing(end)) && e.level().clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)).getType() == HitResult.Type.MISS;
    }
    private static float yaw(Vec3 direction) { return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z)); }
}
