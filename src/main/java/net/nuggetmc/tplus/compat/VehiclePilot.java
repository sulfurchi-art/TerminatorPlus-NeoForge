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
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.BotSkills;

/** Feedback controls emit native keys and mouse motion, never position, rotation or velocity writes. */
final class VehiclePilot {
    private static final class Flight {
        final Entity vehicle;
        Flight(Entity vehicle) { this.vehicle = vehicle; }
        Vec3 waypoint, home, extend, retreat, lastEnemy, antiAirEntry, separation, trafficGoal, airEntry;
        final VehicleNavigation.Route route = new VehicleNavigation.Route();
        Bot driver; String engine; boolean cooperative, weaponReady;
        double combatRange = 48, minimumRange = 18;
        Vec3 groundAttack, groundEnemy, groundVelocity, closeGoal, closeEnemy;
        int groundTarget = -1, groundLeg, groundRank = -1, groundSide;
        boolean groundMoving;
        long groundUntil, groundAt, fireUntil;
        String groundPhase = "ATTACK_APPROACH";
        long nextTrafficScan, trafficUntil, yieldUntil;
        Entity passing;
        Vec3 passingAxis;
        boolean parking, parked;
        long passingUntil;
        int airTarget = -1;
        List<Entity> allies = List.of();
        List<Entity> nearbyVehicles = List.of();
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
    private final BotSkills skills;
    private final VehicleNavigation navigation = new VehicleNavigation();
    VehiclePilot(WarfareSupport warfare, BotSkills skills) { this.warfare = warfare; this.skills = skills; }
    int peakExpansions() { return navigation.peakExpansions(); }
    private final Map<Entity, Flight> flights = new HashMap<>();

    void go(Vessel vessel, Vec3 point) {
        Flight f = flights.computeIfAbsent(vessel.entity(), Flight::new);
        f.waypoint = point; f.extend = null; f.attackSince = -1; f.route.reset(); f.trafficGoal = null; f.passing = null;
    }
    void forget(Entity vessel) { flights.remove(vessel); navigation.forget(vessel); }
    void clear() { flights.clear(); navigation.clear(); }
    String describe(Entity vessel) { Flight f = flights.get(vessel); return f == null ? "IDLE" : f.phase + " nav=" + f.route.state + "/" + f.route.expanded
                + (f.route.problem == null ? "" : " terrain=" + f.route.problem.reason() + "@" + f.route.problem.position().toShortString())
                + (f.trafficGoal == null ? "" : " traffic=" + BlockPos.containing(f.trafficGoal).toShortString()); }
    boolean hasWaypoint(Entity vessel) { Flight f = flights.get(vessel); return f != null && f.waypoint != null; }

    boolean tick(Bot bot, Vessel vessel, @Nullable LivingEntity enemy, long now, boolean land, boolean waitForCrew) {
        Entity e = vessel.entity();
        Flight f = flights.computeIfAbsent(e, Flight::new);
        if (f.home == null) f.home = e.position();
        f.lastTick = now; f.driver = bot; f.engine = vessel.engine();
        f.cooperative = skills.enabled(bot, "teamwork") && bot.getTeam() != null;
        int id = enemy == null ? -1 : enemy.getId();
        if (id != f.threatId || now >= f.threatAt) {
            if (f.threatId != id) { f.retreat = null; f.groundAttack = null; f.groundTarget = -1; f.fireUntil = 0; f.closeGoal = null; }
            f.threatId = id; f.threatAt = now + 20;
            f.dangerous = enemy != null && warfare.canThreaten(vessel, enemy);
            f.antiAir = enemy != null && warfare.antiAirThreat(vessel, enemy);
            if (!f.dangerous) f.retreat = null;
            if (enemy != null && warfare.isVehicle(enemy.getRootVehicle())) weaponEnvelope(bot, vessel, enemy.getRootVehicle(), f);
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
                if (f.retreat == null) f.retreat = cover(vessel, enemy, delta);
                goal = f.retreat;
            } else if (warfare.isVehicle(enemy.getRootVehicle()) && bot.hardness().level() >= 8) {
                if (f.closeGoal != null && (e.position().subtract(f.closeGoal).horizontalDistance() < 4
                        || enemy.getRootVehicle().position().subtract(f.closeEnemy).horizontalDistance() > 6)) f.closeGoal = null;
                if (delta.horizontalDistance() < f.minimumRange || f.closeGoal != null) {
                    if (f.closeGoal == null) {
                        f.closeEnemy = enemy.getRootVehicle().position();
                        f.closeGoal = new Vec3(f.closeEnemy.x, e.getY(), f.closeEnemy.z).subtract(delta.normalize().scale(f.minimumRange + 10));
                    }
                    f.phase = "STANDOFF"; goal = f.closeGoal;
                } else if (!f.weaponReady && f.dangerous && f.hurtUntil > f.lastTick) {
                    if (f.retreat == null) f.retreat = cover(vessel, enemy, delta);
                    f.phase = "RELOAD_COVER"; goal = f.retreat;
                } else {
                    goal = groundAttack(bot, vessel, enemy, f);
                    if (goal == null) { vessel.input(bot, 16, 0, 0); return; }
                }
            } else if (!warfare.isVehicle(enemy.getRootVehicle()) && (f.separation != null || f.lastTick >= f.nextSeparation
                    && delta.lengthSqr() < 36 && (delta.lengthSqr() < 1 || enemy.getY() > e.getY() + e.getBbHeight() * 0.5))) {
                if (f.separation == null) f.separation = MovementBounds.clamp(e, e.position().add(e.getLookAngle().multiply(1, 0, 1).normalize().scale(14)), 8);
                f.phase = "CLOSE_SEPARATION"; goal = f.separation;
            } else if (!warfare.isVehicle(enemy.getRootVehicle()) && delta.lengthSqr() < 12 * 12 && ramSafe(bot, e, enemy)) {
                f.phase = "RAM"; goal = enemy.position().add(delta.normalize().scale(8));
            } else {
                goal = groundAttack(bot, vessel, enemy, f);
                if (goal == null) { vessel.input(bot, 16, 0, 0); return; }
            }
        }
        if (goal == null && enemy == null) {
            if (f.lastEnemy != null && f.lastTick - f.seenAt <= 300 && e.position().distanceToSqr(f.lastEnemy) > 36) { goal = f.lastEnemy; f.phase = "SEARCH_LAST_SEEN"; }
            else if (f.home != null && e.position().distanceToSqr(f.home) > 48 * 48) { goal = f.home; f.phase = "RALLY"; }
        }
        if (goal == null) { f.phase = "IDLE"; vessel.input(bot, 16, 0, 0); return; }
        boolean attackLeg = f.waypoint == null && enemy != null && goal == f.groundAttack;
        goal = MovementBounds.clamp(e, goal, 8);
        Vec3 delta = goal.subtract(e.position()).multiply(1, 0, 1);
        Vec3 coasting = e.position().add(e.getDeltaMovement().multiply(20, 0, 20));
        if (!MovementBounds.contains(e, coasting, 5) && e.getDeltaMovement().horizontalDistance() > 0.08) {
            f.phase = "BORDER_BRAKE"; vessel.input(bot, 16, 0, 0); return;
        }
        if (delta.lengthSqr() < 16) {
            if (Math.abs(e.getY() - goal.y) >= Math.max(2, e.maxUpStep())) {
                f.phase = "GOAL_HEIGHT_BLOCKED"; f.route.state = "NO_ROUTE";
                f.route.problem = new VehicleNavigation.Rejection("GOAL_HEIGHT", BlockPos.containing(goal));
                vessel.input(bot, 16, 0, 0); return;
            }
            if (f.waypoint != null) f.home = goal;
            if (attackLeg) {
                f.groundAttack = null; f.groundLeg++;
                f.fireUntil = f.lastTick + (enemy.getRootVehicle().getDeltaMovement().horizontalDistance() > 0.08 ? 8 : bot.hardness().level() >= 8 ? 24 : 40);
                f.phase = "ATTACK_FIRE_PAUSE"; vessel.input(bot, 16, 0, 0); return;
            }
            f.waypoint = null; if (f.separation != null) { f.separation = null; f.nextSeparation = f.lastTick + 60; }
            f.phase = retreat ? "HOLD_COVER" : "ARRIVED"; vessel.input(bot, 16, 0, 0); return;
        }
        Vec3 original = goal;
        goal = traffic(vessel, f, goal, f.lastTick);
        if (f.lastTick < f.yieldUntil) { f.phase = "YIELD_TEAM"; vessel.input(bot, 16, 0, 0); return; }
        // A short rearward combat leg should use native reverse, not search for room to turn the chassis around.
        if ((enemy != null && f.waypoint == null || goal != original) && reverseForGoal(bot, vessel, f, goal)) return;
        if (f.lastTick < f.route.reverseUntil) {
            Vec3 rear = e.position().subtract(e.getLookAngle().multiply(1, 0, 1).normalize()
                    .scale(Mth.clamp(4 + e.getDeltaMovement().horizontalDistance() * 20, 4, 12)));
            if (VehicleNavigation.reverseClear(vessel, e.position(), rear, e.getYRot()) && clearTraffic(e, f, rear)) {
                f.phase = "REVERSE_RECOVERY"; vessel.input(bot, 8, 0, 0); return;
            }
            f.route.reverseUntil = 0; f.phase = "REVERSE_BLOCKED"; vessel.input(bot, 16, 0, 0); return;
        }
        Vec3 route = navigation.guide(vessel, f.route, goal, f.lastTick);
        Vec3 progressTarget = f.route.index < f.route.points.size() ? f.route.points.get(f.route.index) : goal;
        double remaining = e.position().subtract(progressTarget).horizontalDistance();
        float turn = route == null ? 180 : Math.abs(Mth.wrapDegrees(yaw(route) - e.getYRot()));
        if (f.route.progress == null || f.route.progress.distanceToSqr(progressTarget) > 1) {
            f.route.progress = progressTarget; f.route.bestDistance = remaining; f.route.bestTurn = turn; f.route.progressAt = f.lastTick;
        } else if (remaining < f.route.bestDistance - 0.75 || turn < f.route.bestTurn - 10) {
            f.route.bestDistance = Math.min(f.route.bestDistance, remaining); f.route.bestTurn = Math.min(f.route.bestTurn, turn);
            f.route.progressAt = f.lastTick;
        }
        // Oscillating near a wall is not progress. Planning and deliberate crew yielding are separate waits.
        if (route == null && f.route.state.equals("PLANNING")) f.route.progressAt = f.lastTick;
        if (f.lastTick >= f.route.nextReverse && f.lastTick - f.route.progressAt > 100 && !f.route.state.equals("PLANNING")) {
            Vec3 rear = e.position().subtract(e.getLookAngle().multiply(1, 0, 1).normalize().scale(5));
            if (VehicleNavigation.reverseClear(vessel, e.position(), rear, e.getYRot()) && clearTraffic(e, f, rear)) {
                f.route.recoveries = Math.min(3, f.route.recoveries + 1);
                f.route.reverseUntil = f.lastTick + 25 + f.route.recoveries * 10; f.route.nextReverse = f.lastTick + 120; f.route.reset();
            }
            f.route.progressAt = f.lastTick;
        }
        if (f.lastTick < f.route.reverseUntil) { f.phase = "REVERSE_RECOVERY"; vessel.input(bot, 8, 0, 0); return; }
        if (route == null) { f.phase = f.route.state.equals("PLANNING") ? "ROUTE_PLANNING" : "OBSTRUCTED"; vessel.input(bot, 16, 0, 0); return; }
        float yaw = yaw(route), error = Mth.wrapDegrees(yaw - e.getYRot());
        int keys = error > 4 ? 2 : error < -4 ? 1 : 0;
        double speed = e.getDeltaMovement().horizontalDistance();
        boolean pivot = vessel.engine().equals("TRACK") && Math.abs(error) > 18;
        if (!pivot && Math.abs(error) < 65) keys |= 4; else keys |= 16;
        if (Math.abs(error) > 35 && speed > 0.12 || !vessel.engine().equals("TRACK") && Math.abs(error) > 12 && speed > 0.14)
            keys = (keys & ~4) | 16;
        if (f.waypoint != null) f.phase = pivot ? "TURNING_ROUTE" : f.route.state.equals("ROUTE") ? "DRIVING_ROUTE" : "DRIVING";
        if (goal != original) f.phase = "PASS_TEAM";
        // Brake for the current momentum as well as for the planned direction; the native engine retains inertia.
        Vec3 stopping = e.position().add(e.getDeltaMovement().multiply(8, 0, 8));
        if (speed > 0.08 && !VehicleNavigation.sweepFacing(vessel, e.position(), stopping, e.getYRot())) {
            keys = (keys & ~4) | 16; if (f.waypoint != null) f.phase = "OBSTACLE_BRAKE";
        }
        if (remaining < Math.max(4, speed * 10) && speed > 0.18) keys = (keys & ~4) | 16;
        if (goal != original && Math.abs(error) > 30 || !clearTraffic(e, f, coasting)) keys = (keys & ~4) | 16;
        if (vessel.threatened()) keys |= 64;
        vessel.input(bot, keys, 0, 0);
    }

    /** Stable attack legs give the bounded path search time to finish; only native input moves the chassis. */
    @Nullable private Vec3 groundAttack(Bot bot, Vessel vessel, LivingEntity enemy, Flight f) {
        Entity e = vessel.entity(), target = enemy.getRootVehicle();
        Vec3 targetPos = new Vec3(target.getX(), e.getY(), target.getZ());
        Vec3 delta = targetPos.subtract(e.position()).multiply(1, 0, 1);
        Vec3 velocity = target.getDeltaMovement().multiply(1, 0, 1);
        boolean moving = velocity.horizontalDistance() > 0.08;
        boolean armor = warfare.isVehicle(target);
        double radius = armor ? Math.max(f.minimumRange + 6, Math.min(36, f.combatRange * 0.48)) : 24;
        boolean visible = clearLine(e, e.getBoundingBox().getCenter(), target.getBoundingBox().getCenter());
        if (f.lastTick < f.fireUntil && !moving && visible && delta.length() < f.combatRange) {
            f.phase = "ATTACK_FIRE_PAUSE"; return null;
        }
        List<Flight> group = bot.hardness().level() >= 8 ? formation(f) : List.of(f);
        int rank = Math.max(0, group.indexOf(f));
        // An expiry does not repeatedly cancel an unfinished detour. Significant target movement still replans it.
        boolean planning = f.route.state.equals("PLANNING") && f.lastTick - f.route.plannedAt < 1200;
        if (f.groundAttack == null || f.groundTarget != enemy.getId()
                || moving != f.groundMoving || rank != f.groundRank
                || f.groundEnemy.add(f.groundVelocity.scale(f.lastTick - f.groundAt)).subtract(targetPos).horizontalDistance() > 16
                || f.lastTick >= f.groundUntil && !planning) {
            Vec3 away = delta.normalize().scale(-1);
            if (away.lengthSqr() < 0.1) away = e.getLookAngle().multiply(1, 0, 1).normalize().scale(-1);
            double speed = Mth.clamp(e.getDeltaMovement().horizontalDistance(), 0.55, 0.9);
            double leadTicks = bot.hardness().level() >= 8 ? interceptTime(delta, velocity, speed, radius) : 8;
            Vec3 lead = velocity.scale(leadTicks);
            if (lead.length() > 80) lead = lead.normalize().scale(80);
            Vec3 predicted = targetPos.add(lead);
            if (moving) away = e.position().subtract(predicted).multiply(1, 0, 1).normalize();
            Vec3 candidate;
            if (rank > 0) {
                Vec3 anchorAway = group.getFirst().vehicle.position().subtract(targetPos).multiply(1, 0, 1).normalize();
                if (anchorAway.lengthSqr() > 0.1) away = anchorAway;
                Vec3 lateral = new Vec3(-away.z, 0, away.x);
                if (f.groundSide == 0 || f.groundTarget != enemy.getId() || f.groundRank != rank) {
                    double offset = e.position().subtract(group.getFirst().vehicle.position()).dot(lateral);
                    // Preserve the side already occupied instead of crossing through the leading vehicle.
                    f.groundSide = Math.abs(offset) > 4 ? offset > 0 ? 1 : -1 : rank % 2 == 0 ? 1 : -1;
                }
                int side = f.groundSide;
                double flank = Math.min(40, 22 + rank * 6);
                // Alternate the supporting position after each firing pause while retaining the assigned side.
                candidate = predicted.add(away.scale(radius + (f.groundLeg % 2) * 8))
                        .add(-away.z * flank * side, 0, away.x * flank * side);
                f.groundPhase = moving ? "TEAM_FLANK_INTERCEPT" : "TEAM_FLANK";
            } else if (moving || delta.length() > radius + 8 || !visible) {
                candidate = predicted.add(away.scale(radius));
                f.groundPhase = moving ? "ATTACK_INTERCEPT" : "ATTACK_APPROACH";
            } else {
                int side = (f.groundLeg % 2 == 0 ? 1 : -1) * (e.getUUID().hashCode() % 2 == 0 ? 1 : -1);
                if (group.size() > 1) {
                    double offset = e.position().subtract(group.get(1).vehicle.position()).dot(new Vec3(-away.z, 0, away.x));
                    if (Math.abs(offset) > 4) side = offset > 0 ? -1 : 1;
                }
                candidate = targetPos.add(away.yRot((float) Math.toRadians(side * 30)).scale(radius));
                f.groundPhase = "ATTACK_REPOSITION";
            }
            if (armor && !moving && delta.length() <= radius + 16 && !localFacingFits(vessel, candidate)) {
                Vec3 local = laneCombatGoal(vessel, f, targetPos);
                if (local != null) {
                    candidate = local;
                    f.groundPhase = candidate.subtract(e.position()).dot(e.getLookAngle()) < 0 ? "ATTACK_BACKSTEP" : "ATTACK_LANE_ADVANCE";
                }
            }
            f.groundAttack = MovementBounds.clamp(e, candidate, 8); f.groundEnemy = targetPos;
            f.groundVelocity = velocity; f.groundAt = f.lastTick;
            f.groundTarget = enemy.getId(); f.groundMoving = moving; f.groundRank = rank;
            f.groundUntil = f.lastTick + (moving ? 60 : 120);
        }
        // Ground targets follow the current support layer, even when the opponent is flying overhead.
        f.groundAttack = new Vec3(f.groundAttack.x, e.getY(), f.groundAttack.z);
        f.phase = f.groundPhase;
        return f.groundAttack;
    }

    private static boolean localFacingFits(Vessel vessel, Vec3 goal) {
        Entity e = vessel.entity(); Vec3 delta = goal.subtract(e.position()).multiply(1, 0, 1);
        float facing = delta.lengthSqr() < 1 ? e.getYRot() : yaw(delta);
        return VehicleNavigation.site(vessel, goal, facing) != null && VehicleNavigation.turnClear(vessel, e.position(), e.getYRot(), facing);
    }
    @Nullable private Vec3 laneCombatGoal(Vessel vessel, Flight f, Vec3 enemy) {
        Entity e = vessel.entity(); scanTraffic(vessel, f, f.lastTick);
        Vec3 forward = e.getLookAngle().multiply(1, 0, 1).normalize();
        // Two bounded, straight alternatives. Keep normal flanking when its chassis turn and destination fit.
        int first = e.position().subtract(enemy).horizontalDistance() > f.minimumRange + 16 ? 1 : -1;
        for (int sign : new int[]{first, -first}) {
            Vec3 point = e.position().add(forward.scale(sign * 10));
            if (point.subtract(enemy).horizontalDistance() < f.minimumRange + 3
                    || !VehicleNavigation.reverseClear(vessel, e.position(), point, e.getYRot()) || !clearVehicles(vessel, f, point)) continue;
            return point;
        }
        return null;
    }
    private boolean reverseForGoal(Bot bot, Vessel vessel, Flight f, Vec3 goal) {
        Entity e = vessel.entity(); Vec3 delta = goal.subtract(e.position()).multiply(1, 0, 1);
        Vec3 forward = e.getLookAngle().multiply(1, 0, 1).normalize();
        double backward = -delta.dot(forward);
        if (backward < 4 || delta.length() > 64 || delta.normalize().dot(forward) > -0.96) return false;
        double distance = Math.min(backward, Mth.clamp(4 + e.getDeltaMovement().horizontalDistance() * 20, 4, 12));
        Vec3 rear = e.position().subtract(forward.scale(distance));
        if (!VehicleNavigation.reverseClear(vessel, e.position(), rear, e.getYRot()) || !clearVehicles(vessel, f, rear)) return false;
        if (!f.route.state.equals("LOCAL_REVERSE")) { f.route.reset(); navigation.forget(e); }
        f.route.state = "LOCAL_REVERSE"; f.route.problem = null;
        f.phase = f.phase.equals("STANDOFF") ? "CLOSE_REVERSE" : goal == f.trafficGoal ? "PASS_TEAM_REVERSE"
                : f.phase.equals("RETREAT") || f.phase.equals("RELOAD_COVER") ? f.phase + "_REVERSE" : "ATTACK_BACKSTEP";
        // Brake existing forward momentum before selecting reverse; steering and all acceleration stay native.
        int keys = e.getDeltaMovement().dot(forward) > 0.12 ? 16 : 8;
        if (vessel.threatened()) keys |= 64;
        vessel.input(bot, keys, 0, 0); return true;
    }
    private boolean clearVehicles(Vessel vessel, Flight f, Vec3 point) {
        Entity e = vessel.entity(); scanTraffic(vessel, f, f.lastTick);
        AABB volume = VehicleNavigation.body(vessel, e.position(), e.getYRot())
                .minmax(VehicleNavigation.body(vessel, point, e.getYRot())).inflate(0.2);
        return f.nearbyVehicles.stream().filter(other -> !other.isRemoved() && other.level() == e.level())
                .noneMatch(other -> volume.intersects(VehicleNavigation.body(warfare.access().vessel(other), other.position(), other.getYRot())));
    }

    private static double interceptTime(Vec3 delta, Vec3 velocity, double speed, double range) {
        // Solve |delta + velocity*t| = range + speed*t. No reachable root means aim at the nearest pass.
        double a = velocity.lengthSqr() - speed * speed;
        double b = 2 * (delta.dot(velocity) - range * speed);
        double c = delta.lengthSqr() - range * range;
        double time = Double.POSITIVE_INFINITY;
        if (Math.abs(a) < 0.0001) {
            if (Math.abs(b) > 0.0001 && -c / b > 0) time = -c / b;
        } else {
            double discriminant = b * b - 4 * a * c;
            if (discriminant >= 0) {
                double root = Math.sqrt(discriminant), first = (-b - root) / (2 * a), second = (-b + root) / (2 * a);
                if (first > 0) time = first;
                if (second > 0) time = Math.min(time, second);
            }
        }
        if (!Double.isFinite(time)) time = velocity.lengthSqr() > 0.001 ? -delta.dot(velocity) / velocity.lengthSqr() : 10;
        return Mth.clamp(time, 10, 120);
    }

    private boolean helicopter(Bot bot, Vessel vessel, @Nullable LivingEntity enemy, long now, Flight f, boolean land, boolean wait) {
        Entity e = vessel.entity(); Vec3 pos = e.position(), motion = e.getDeltaMovement();
        if (wait && e.onGround()) { f.phase = "ASSEMBLING"; vessel.input(bot, 0, 0, 0); return true; }
        if (land && e.onGround()) { f.phase = "LANDED"; vessel.input(bot, 32, 0, 0); return true; }
        Vec3 goal;
        boolean incoming = vessel.threatened() || now < f.hurtUntil;
        var missile = skills.missiles().threat(bot, now);
        boolean emergency = missile != null;
        boolean emergencyBreak = emergency && missile.missile() != null
                && missile.impactTicks() < Math.max(0, pos.y - groundHeight(e, pos)) / 0.2 + 20;
        incoming |= emergency;
        boolean landing = land || vessel.energy() < Math.min(20000, vessel.maxEnergy() / 100);
        if (emergency && !emergencyBreak) landing = true;
        if (landing) {
            // Pick a loaded clear patch below/near the aircraft; avoid an automatic teleport or midair ejection.
            goal = landingPoint(e);
            if (goal == null) { f.phase = "NO_LANDING_SITE"; goal = pos.add(0, 4, 0); landing = false; }
            else f.phase = emergency ? "MISSILE_LANDING" : "LANDING";
        } else if (emergencyBreak) {
            Vec3 approach = missile.missile().entity().getDeltaMovement().multiply(1, 0, 1).normalize();
            if (approach.lengthSqr() < 0.1) approach = pos.subtract(missile.origin()).multiply(1, 0, 1).normalize();
            int side = e.getUUID().hashCode() % 2 == 0 ? 1 : -1;
            goal = pos.add(-approach.z * side * 55, -12, approach.x * side * 55);
            f.attackSince = -1; f.phase = "EVADE_BREAK";
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
        List<Flight> group = formation(f);
        int rank = group.indexOf(f);
        if (!landing && f.waypoint == null && enemy != null && rank > 0 && !incoming && f.extend == null && now >= f.orbitUntil) {
            if (f.airTarget != enemy.getId()) {
                Vec3 away = group.getFirst().driver.getVehicle().position().subtract(enemy.position()).multiply(1, 0, 1).normalize();
                int side = rank % 2 == 0 ? 1 : -1;
                f.airEntry = MovementBounds.clamp(e, enemy.position().add(away.scale(60))
                        .add(-away.z * side * (25 + rank * 5), 18 + rank * 12, away.x * side * (25 + rank * 5)), 15);
                f.airTarget = enemy.getId();
            }
            if (f.airEntry != null && pos.subtract(f.airEntry).horizontalDistance() < 12) f.airEntry = null;
            if (f.airEntry != null) { goal = f.airEntry; f.phase = "TEAM_AIR_APPROACH"; }
        }
        if (!landing && !incoming && rank > 0 && enemy != null && !f.phase.equals("TEAM_AIR_APPROACH")) goal = goal.add(0, Math.min(24, rank * 10), 0);
        if (!emergency) goal = airSpacing(vessel, f, goal, now, landing);
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
        boolean obstructed = !flightSweep(vessel, pos, ahead);
        if (obstructed) {
            desiredAltitude = Math.max(desiredAltitude, pos.y + 7); f.phase = "AVOID";
            Vec3 raised = ahead.add(0, 8, 0);
            if (!flightSweep(vessel, pos.add(0, 3, 0), raised)) {
                Vec3 right = e.getLookAngle().multiply(1, 0, 1).normalize().yRot((float) -Math.PI / 2);
                Vec3 escape = pos.add(right.scale(12)).add(0, 7, 0);
                if (!flightSweep(vessel, pos, escape)) escape = pos.subtract(right.scale(12)).add(0, 7, 0);
                if (flightSweep(vessel, pos, escape)) { delta = escape.subtract(pos); horizontal = delta.horizontalDistance(); }
            }
        }
        float desiredYaw = horizontal > 3 ? yaw(delta) : e.getYRot();
        float yawError = Mth.wrapDegrees(desiredYaw - e.getYRot());
        double speed = motion.dot(e.getLookAngle().multiply(1, 0, 1).normalize());
        float desiredPitch = (float) Mth.clamp(horizontal > 10 ? 18 - speed * 20 : -speed * 35, -18, 22);
        if (Math.abs(yawError) > 50 || obstructed || pos.y < ground + 6) desiredPitch = (float) Mth.clamp(-speed * 25, -12, 12);
        if (emergencyBreak && !obstructed && pos.y > ground + 6) desiredPitch = 20;
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
        if (emergencyBreak && pos.y > ground + 6) verticalGoal = -0.45;
        if (landing && pos.y - desiredAltitude < 3) verticalGoal = Math.max(-0.07, verticalGoal);
        double liftError = verticalGoal - motion.y;
        int keys = !vessel.engineStarted() && !landing || liftError > 0.025 ? 4 : liftError < -0.025 ? 32 : 0;
        double rollError = -vessel.roll() - rollRate * 5 - mouseX * 0.015;
        if (emergencyBreak) rollError += e.getUUID().hashCode() % 2 == 0 ? 40 : -40;
        if (rollError > 2) keys |= 2; else if (rollError < -2) keys |= 1;
        boolean hover = landing || f.phase.equals("HOLD") || f.phase.equals("AIR_SEPARATION");
        if (!e.onGround() && vessel.hovering() != hover) keys |= 16;
        if (incoming && now >= f.nextDecoy && vessel.decoys() > 0) { keys |= 64; f.nextDecoy = now + (emergencyBreak ? 8 : 40); }
        vessel.input(bot, keys, mouseX, mouseY);
        return landing && e.onGround();
    }

    private List<Flight> formation(Flight self) {
        if (!self.cooperative || self.threatId < 0) return List.of(self);
        return flights.values().stream().filter(f -> f.driver != null && f.cooperative && f.threatId == self.threatId
                && skills.enabled(f.driver, "teamwork") && self.lastTick - f.lastTick <= 20 && self.driver.level() == f.driver.level() && f.driver.isAlive()
                && f.driver.getVehicle() == f.vehicle && warfare.isVehicle(f.vehicle) && warfare.vessel(f.vehicle).passenger(0) == f.driver && self.driver.isAlliedTo(f.driver) && self.engine.equals(f.engine)
                && self.driver.position().distanceToSqr(f.driver.position()) < 160 * 160)
                .sorted(Comparator.comparing(f -> f.driver.getVehicle().getUUID())).toList();
    }
    private void weaponEnvelope(Bot bot, Vessel vessel, Entity target, Flight f) {
        f.weaponReady = false; f.combatRange = 48; f.minimumRange = 18;
        for (var seat : vessel.seats()) if (vessel.passenger(seat.index()) instanceof Bot gunner && skills.enabled(gunner, "vehicleweapons")) {
            for (var weapon : vessel.weapons(gunner)) {
                var gun = vessel.weaponGun(gunner, weapon.index());
                if (gun == null || warfare.access().vehicleDamage(target, gunner, gun) <= 0) continue;
                f.weaponReady |= weapon.ready();
                f.combatRange = Math.max(f.combatRange, Math.min(80, weapon.specs().range() * 0.6));
                f.minimumRange = Math.max(f.minimumRange, weapon.specs().explosionRadius() * 2 + 6);
            }
        }
    }
    private static Vec3 cover(Vessel vessel, LivingEntity enemy, Vec3 delta) {
        Entity e = vessel.entity(); Vec3 away = delta.multiply(1, 0, 1).normalize();
        Vec3 fallback = MovementBounds.clamp(e, e.position().subtract(away.scale(20)), 8);
        for (int degrees : new int[]{0, 45, -45, 90, -90}) {
            Vec3 point = e.position().subtract(away.yRot((float) Math.toRadians(degrees)).scale(18));
            if (VehicleNavigation.site(vessel, point, e.getYRot()) != null
                    && !clearLine(e, point.add(0, 1.5, 0), enemy.getRootVehicle().getBoundingBox().getCenter())) return point;
        }
        return fallback;
    }
    private void scanTraffic(Vessel vessel, Flight f, long now) {
        if (now < f.nextTrafficScan) return;
        f.nextTrafficScan = now + 10;
        f.nearbyVehicles = vessel.entity().level().getEntities(vessel.entity(), vessel.entity().getBoundingBox().inflate(vessel.engine().equals("HELICOPTER") ? 80 : 40), warfare::isVehicle);
        f.allies = f.nearbyVehicles.stream().filter(other -> other.getPassengers().stream().anyMatch(f.driver::isAlliedTo)).toList();
    }
    private Vec3 traffic(Vessel vessel, Flight f, Vec3 goal, long now) {
        Entity e = vessel.entity(); scanTraffic(vessel, f, now);
        if (f.passing != null) {
            Entity other = f.passing;
            if (other.isRemoved() || other.level() != e.level() || now >= f.passingUntil
                    || other.getPassengers().stream().noneMatch(f.driver::isAlliedTo)
                    || other.position().subtract(e.position()).dot(f.passingAxis) < -14) {
                f.passing = null; f.trafficGoal = null; f.parking = f.parked = false;
            } else {
                if (f.parking) {
                    if (e.position().subtract(f.trafficGoal).horizontalDistance() < 3) f.parked = true;
                    if (f.parked) { f.yieldUntil = now + 1; return goal; }
                    return f.trafficGoal;
                }
                if (f.trafficGoal != null && e.position().subtract(f.trafficGoal).horizontalDistance() > 4) return f.trafficGoal;
                Vec3 side = e.position().add(new Vec3(-f.passingAxis.z, 0, f.passingAxis.x).scale(14));
                if (localFacingFits(vessel, side) && clearVehicles(vessel, f, side)) {
                    f.trafficGoal = side; f.parking = true; f.yieldUntil = now; return side;
                }
                Vec3 rear = e.position().subtract(e.getLookAngle().multiply(1, 0, 1).normalize().scale(10));
                if (VehicleNavigation.reverseClear(vessel, e.position(), rear, e.getYRot()) && clearVehicles(vessel, f, rear)) {
                    f.trafficGoal = rear; f.yieldUntil = now; return rear;
                }
                f.yieldUntil = now + 6; return goal;
            }
        }
        if (f.trafficGoal != null && now < f.trafficUntil && e.position().distanceToSqr(f.trafficGoal) > 25) return f.trafficGoal;
        f.trafficGoal = null;
        Vec3 forward = goal.subtract(e.position()).multiply(1, 0, 1).normalize();
        for (Entity other : f.allies) {
            if (other.isRemoved() || other.level() != e.level() || Math.abs(other.getY() - e.getY()) > 6
                    || other.getPassengers().stream().noneMatch(f.driver::isAlliedTo)) continue;
            Flight yielding = flights.get(other);
            if (yielding != null && yielding.passing == e && yielding.parked) continue;
            Vec3 relative = other.position().subtract(e.position()).multiply(1, 0, 1);
            if (relative.dot(forward) < 0) continue;
            Vec3 motion = other.getDeltaMovement().subtract(e.getDeltaMovement()).multiply(1, 0, 1);
            double time = motion.lengthSqr() > 0.001 ? Mth.clamp(-relative.dot(motion) / motion.lengthSqr(), 0, 30) : 0;
            double gap = Math.max(5, Math.max(e.getBoundingBox().getXsize(), e.getBoundingBox().getZsize()) / 2
                    + Math.max(other.getBoundingBox().getXsize(), other.getBoundingBox().getZsize()) / 2 + 2);
            if (relative.add(motion.scale(time)).length() > gap || relative.length() > gap + 18) continue;
            boolean following = e.getLookAngle().dot(other.getLookAngle()) > 0.7;
            if (following && other.getDeltaMovement().horizontalDistance() > 0.05) { f.yieldUntil = now + 5; return goal; }
            // Everyone passes on their own right; an opposed convoy therefore separates to different sides.
            Vec3 right = new Vec3(-forward.z, 0, forward.x);
            f.trafficGoal = MovementBounds.clamp(e, e.position().add(forward.scale(10)).add(right.scale(gap + 4)), 8);
            if (!localFacingFits(vessel, f.trafficGoal)) {
                // A single lane cannot fit two imaginary side paths. Exactly one native AI backs toward open space.
                boolean giveWay = !(other.getFirstPassenger() instanceof Bot) || e.getUUID().compareTo(other.getUUID()) > 0;
                Vec3 rear = e.position().subtract(e.getLookAngle().multiply(1, 0, 1).normalize().scale(10));
                if (giveWay && VehicleNavigation.reverseClear(vessel, e.position(), rear, e.getYRot()) && clearVehicles(vessel, f, rear)) {
                    f.passing = other; f.passingAxis = forward; f.parking = f.parked = false; f.passingUntil = now + 600;
                    f.trafficGoal = rear; f.trafficUntil = now + 60; f.yieldUntil = now; return rear;
                }
                f.trafficGoal = null; f.yieldUntil = now + 6; return goal;
            }
            f.trafficUntil = now + 100;
            if (!(other.getFirstPassenger() instanceof Bot) || e.getUUID().compareTo(other.getUUID()) > 0) f.yieldUntil = now + 12;
            return f.trafficGoal;
        }
        return goal;
    }
    private boolean clearTraffic(Entity e, Flight f, Vec3 point) {
        Vessel vessel = warfare.access().vessel(e);
        AABB volume = VehicleNavigation.body(vessel, e.position(), e.getYRot())
                .minmax(VehicleNavigation.body(vessel, point, e.getYRot())).inflate(0.5);
        return f.allies.stream().filter(other -> !other.isRemoved() && other.level() == e.level()
                && other.getPassengers().stream().anyMatch(f.driver::isAlliedTo))
                .noneMatch(other -> volume.intersects(VehicleNavigation.body(warfare.access().vessel(other), other.position(), other.getYRot())));
    }
    private Vec3 airSpacing(Vessel vessel, Flight f, Vec3 goal, long now, boolean landing) {
        Entity e = vessel.entity(); scanTraffic(vessel, f, now);
        if (e.onGround()) return goal;
        for (Entity other : f.allies) {
            if (other.isRemoved() || other.getPassengers().stream().noneMatch(f.driver::isAlliedTo)) continue;
            Vec3 relative = other.position().subtract(e.position()), motion = other.getDeltaMovement().subtract(e.getDeltaMovement());
            double time = motion.lengthSqr() > 0.001 ? Mth.clamp(-relative.dot(motion) / motion.lengthSqr(), 0, 60) : 0;
            double gap = Math.max(24, (e.getBbWidth() + other.getBbWidth()) / 2 + 12);
            if (relative.add(motion.scale(time)).length() >= gap) continue;
            Vec3 away = relative.scale(-1).normalize();
            if (away.lengthSqr() < 0.1) away = new Vec3(e.getUUID().compareTo(other.getUUID()) > 0 ? 1 : -1, 0, 0);
            boolean climb = e.getUUID().compareTo(other.getUUID()) > 0;
            Vec3 safe = e.position().add(away.multiply(1, 0, 1).normalize().scale(25));
            safe = new Vec3(safe.x, landing ? e.getY() : climb ? Math.max(e.getY() + 10, other.getY() + 14)
                    : Math.max(groundHeight(e, safe) + 16, e.getY()), safe.z);
            if (flightSweep(vessel, e.position(), safe)) { f.phase = "AIR_SEPARATION"; return safe; }
        }
        return goal;
    }
    private static boolean flightSweep(Vessel v, Vec3 from, Vec3 to) {
        Entity e = v.entity(); var shape = v.footprint();
        double radius = Math.max(1.5, shape.halfSize().horizontalDistance());
        int steps = Math.max(1, Mth.ceil(from.distanceTo(to) / 2));
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.lerp(to, (double) i / steps);
            AABB box = new AABB(p.x - radius, p.y + 0.3, p.z - radius,
                    p.x + radius, p.y + Math.max(3, shape.center().y + shape.halfSize().y), p.z + radius);
            if (!VehicleNavigation.loaded(e, box) || e.level().getBlockCollisions(e, box).iterator().hasNext()) return false;
        }
        return true;
    }

    private static boolean ramSafe(Bot driver, Entity vehicle, LivingEntity enemy) {
        var swept = vehicle.getBoundingBox().minmax(enemy.getBoundingBox()).inflate(1);
        return vehicle.level().getEntities(vehicle, swept).stream().noneMatch(other -> other != driver
                && other.getRootVehicle() != vehicle && other instanceof LivingEntity && driver.isAlliedTo(other));
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
            if (VehicleNavigation.loaded(e, volume) && !e.level().getBlockCollisions(e, volume).iterator().hasNext()) return p;
        }
        return null;
    }
    private static double groundHeight(Entity e, Vec3 point) {
        if (!e.level().hasChunkAt(BlockPos.containing(point))) return e.getY();
        return e.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(point.x), Mth.floor(point.z));
    }
    private static boolean clearLine(Entity e, Vec3 start, Vec3 end) {
        return VehicleNavigation.loaded(e, new AABB(start, end).inflate(0.01)) && e.level().clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, e)).getType() == HitResult.Type.MISS;
    }
    private static float yaw(Vec3 direction) { return (float) Math.toDegrees(Math.atan2(-direction.x, direction.z)); }
}
