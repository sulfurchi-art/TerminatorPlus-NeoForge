package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.WarfareAccess;
import net.nuggetmc.tplus.compat.WarfareItems;

import javax.annotation.Nullable;
import java.util.*;

/** Bounded native ordnance actions. Remote operators relinquish control on pressure or lost equipment. */
public final class WarfareTactics {
    private enum Mode { IDLE, DRONE, C4_APPROACH, C4_EGRESS }
    private static final class State {
        Mode mode = Mode.IDLE;
        WarfareAccess.Drone drone;
        Hand hand;
        UUID target, charge;
        Vec3 observed, velocity = Vec3.ZERO, home;
        Vec3 passDirection = Vec3.ZERO;
        AABB targetBounds;
        double closest;
        long progressAt, placedAt;
        String c4Result = "";
        long started, seen, nextAttempt, nextFire;
        int lastVertical, verticalTicks, drops, detonations;
        boolean returning;
    }
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private boolean failed;
    private final BotSkills skills;
    private final Map<Bot, State> states = new HashMap<>();
    WarfareTactics(BotSkills skills) { this.skills = skills; }
    public String describe(Bot bot) {
        State s = states.get(bot);
        return s == null ? "" : "; ordnance=" + s.mode + "; droneDrops=" + s.drops + "; c4Detonations=" + s.detonations
                + (s.c4Result.isEmpty() ? "" : "; c4Result=" + s.c4Result);
    }
    public Entity drone(Bot bot) { State s = states.get(bot); return s == null || s.drone == null ? null : s.drone.entity(); }
    public int drops(Bot bot) { State s = states.get(bot); return s == null ? 0 : s.drops; }
    public int detonations(Bot bot) { State s = states.get(bot); return s == null ? 0 : s.detonations; }
    public void forget(Bot bot) { cancel(bot); states.remove(bot); }
    public void clear() { for (Bot bot : List.copyOf(states.keySet())) forget(bot); }
    public void cancel(Bot bot) {
        State s = states.get(bot);
        if (s == null || s.mode == Mode.IDLE) return;
        if (s.drone != null && s.hand != null) s.drone.stop(bot, s.hand.stack);
        if (s.hand != null) s.hand.close();
        BotMemory mem = skills.memory(bot);
        if (mem.plan == BotMemory.FlightPlan.C4) {
            // Finish in the current pass direction; returning to the launch point causes another U-turn.
            Vec3 forward = s.passDirection.lengthSqr() > 0.1 ? s.passDirection : bot.getVelocity().multiply(1, 0, 1).normalize();
            Vec3 landing = MovementBounds.clamp(bot, bot.position().add(forward.scale(24)), 8);
            double ground = bot.getBotLevel().hasChunkAt(BlockPos.containing(landing))
                    ? SkillUtil.surfaceY(bot.getBotLevel(), landing) : bot.getY() - 2;
            mem.plan = BotMemory.FlightPlan.TRAVEL;
            mem.flightGoal = new Vec3(landing.x, ground, landing.z);
            if (s.c4Result.equals("APPROACH") || s.c4Result.equals("PLACED")) s.c4Result = "CANCELLED";
        }
        s.hand = null; s.drone = null; s.target = null; s.charge = null;
        s.mode = Mode.IDLE;
        s.nextAttempt = Math.max(s.nextAttempt, bot.getServer().getTickCount() + 300);
    }
    public boolean tick(Bot bot, @Nullable LivingEntity enemy, long now) {
        if (failed) return false;
        try { return control(bot, enemy, now); }
        catch (RuntimeException | LinkageError error) {
            failed = true;
            LOGGER.error("Native ordnance disabled after API failure; ordinary weapon AI continues", error);
            for (Bot owned : List.copyOf(states.keySet())) {
                try { cancel(owned); } catch (RuntimeException | LinkageError cleanupError) {
                    State state = states.get(owned);
                    if (state != null && state.hand != null) state.hand.close();
                    if (state != null) { state.hand = null; state.drone = null; state.mode = Mode.IDLE; }
                }
            }
            return false;
        }
    }
    private boolean control(Bot bot, @Nullable LivingEntity enemy, long now) {
        WarfareItems.tickBatons(bot);
        var access = skills.warfare().access();
        if (access == null) { cancel(bot); return false; }
        State s = states.get(bot);
        if (s != null && s.mode != Mode.IDLE) {
            boolean enabled = skills.enabled(bot, s.mode == Mode.DRONE ? "drones" : "c4");
            BotMemory mem = skills.memory(bot);
            if (!enabled || !bot.isAlive() || bot.isPassenger() || bot.isConsuming()
                    || bot.getHealth() / bot.getMaxHealth() < 0.55
                    || now - mem.lastDamage < 12 || now - s.started > (s.mode == Mode.DRONE ? 600 : 420)) {
                cancel(bot); return false;
            }
            return s.mode == Mode.DRONE ? tickDrone(bot, s, access.ordnance(), now) : tickC4(bot, s, enemy, access.ordnance(), now);
        }
        if (enemy == null || !enemy.isAlive() || enemy.level() != bot.level() || bot.isAlliedTo(enemy)
                || bot.isPassenger() || bot.isGliding() || !bot.isBotOnGround() || bot.isConsuming()
                || bot.getHealth() / bot.getMaxHealth() < 0.7 || !skills.canSee(bot, enemy)
                || !bot.hasLineOfSight(enemy) || now - skills.memory(bot).lastDamage < 40
                || s != null && now < s.nextAttempt) return false;
        if (s == null) { s = new State(); states.put(bot, s); }
        var ordnance = access.ordnance();
        double distance = bot.distanceTo(enemy);
        // A low pass is an anti-armour opening, not a routine travel/flight decision.
        if (skills.enabled(bot, "c4") && access.isVehicle(enemy.getRootVehicle()) && distance >= 12 && distance <= 72
                && has(bot, "c4_bomb") && has(bot, "detonator") && skills.pilot().canFly(bot)
                && ordnance.charges(bot).isEmpty()) {
            BotMemory mem = skills.memory(bot);
            if (skills.pilot().tryStart(bot, mem, BotMemory.FlightPlan.C4, now)) {
                start(s, enemy.getRootVehicle(), bot.position(), now); s.mode = Mode.C4_APPROACH;
                s.passDirection = s.observed.subtract(bot.position()).multiply(1, 0, 1).normalize();
                s.targetBounds = enemy.getRootVehicle().getBoundingBox();
                s.closest = SkillUtil.horizontalDistance(bot.position(), s.observed); s.progressAt = now; s.placedAt = 0;
                s.c4Result = "APPROACH";
                mem.flightGoal = s.observed.add(s.passDirection.scale(40)); skills.warfare().release(bot); return true;
            }
        }
        if (skills.enabled(bot, "drones") && states.values().stream().filter(st -> st.mode == Mode.DRONE).count() < 8 && distance >= 30 && distance <= 100 && has(bot, "monitor")) {
            s.nextAttempt = now + 300;
            if (startDrone(bot, enemy, s, ordnance, now)) return true;
        }
        return false;
    }
    private static void start(State s, Entity target, Vec3 home, long now) {
        s.target = target.getUUID(); s.observed = target.getBoundingBox().getCenter(); s.velocity = target.getDeltaMovement();
        s.home = home; s.returning = false; s.started = s.seen = now; s.nextFire = now; s.verticalTicks = s.lastVertical = 0;
    }
    private boolean startDrone(Bot bot, LivingEntity enemy, State s, WarfareAccess.Ordnance ordnance, long now) {
        skills.warfare().release(bot);
        ItemStack monitor = item(bot, "monitor");
        CompoundTag tag = monitor.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        WarfareAccess.Drone remote = null;
        if (tag.getBoolean("Linked")) {
            try {
                Entity existing = bot.getBotLevel().getEntity(UUID.fromString(tag.getString("LinkedDrone")));
                if (existing != null && ordnance.isDrone(existing)) {
                    var owned = ordnance.drone(existing);
                    if (owned.owned(bot) && existing.distanceTo(bot) < 128) remote = owned;
                }
            } catch (IllegalArgumentException ignored) { }
            if (remote == null) return false; // Never steal/unbind another operator's monitor.
        } else {
            ItemStack deploy = item(bot, "drone"), payload = selectPayload(bot, enemy, ordnance);
            if (deploy.isEmpty() || payload.isEmpty()) return false;
            Vec3 facing = enemy.position().subtract(bot.position()).multiply(1, 0, 1).normalize();
            BlockPos ground = BlockPos.containing(bot.position().add(facing.scale(1.5))).below();
            Vec3 hit = Vec3.atBottomCenterOf(ground.above());
            var world = bot.getBotLevel();
            if (!world.hasChunkAt(ground) || !MovementBounds.contains(bot, hit, 3) || hit.distanceTo(bot.getEyePosition()) > bot.blockInteractionRange()
                    || world.getBlockState(ground).getCollisionShape(world, ground).isEmpty()
                    || !world.noCollision(new AABB(ground.above()).inflate(0.15, 0, 0.15)) || !line(bot, bot.getEyePosition(), hit.add(0, 0.01, 0))) return false;
            Set<UUID> before = new HashSet<>();
            world.getEntities(bot, bot.getBoundingBox().inflate(5), ordnance::isDrone).forEach(e -> before.add(e.getUUID()));
            try (Hand hand = Hand.take(bot, deploy)) {
                if (hand == null) return false;
                deploy.getItem().useOn(new UseOnContext(bot, InteractionHand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, ground, false)));
            }
            Entity created = world.getEntities(bot, bot.getBoundingBox().inflate(5), ordnance::isDrone).stream()
                    .filter(e -> !before.contains(e.getUUID()) && !ordnance.drone(e).linked()).findFirst().orElse(null);
            if (created == null) return false;
            remote = ordnance.drone(created);
            WarfareAccess.Payload data = ordnance.payload(payload);
            try (Hand hand = Hand.take(bot, payload)) {
                if (hand == null) return false;
                boolean shift = bot.isShiftKeyDown(); bot.setShiftKeyDown(false);
                try {
                    for (int i = 0; i < data.capacity() && !payload.isEmpty() && remote.ammo() < data.capacity(); i++)
                        created.interact(bot, InteractionHand.MAIN_HAND);
                } finally { bot.setShiftKeyDown(shift); }
            }
            if (remote.ammo() == 0) return false;
        }
        if (remote.ammo() == 0) {
            if (remote.entity().position().distanceTo(bot.getEyePosition()) > bot.entityInteractionRange()) return false;
            ItemStack payload = selectPayload(bot, enemy, ordnance);
            var data = ordnance.payload(payload);
            if (data == null) return false;
            try (Hand loading = Hand.take(bot, payload)) {
                if (loading == null) return false;
                bot.stand();
                for (int i = 0; i < data.capacity() && !payload.isEmpty() && remote.ammo() < data.capacity(); i++)
                    remote.entity().interact(bot, InteractionHand.MAIN_HAND);
            }
            if (remote.ammo() == 0) return false;
        }
        Hand hand = Hand.take(bot, monitor);
        if (hand == null) return false;
        if (remote.owned(bot)) {
            if (!monitor.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean("Using"))
                monitor.getItem().use(bot.level(), bot, InteractionHand.MAIN_HAND);
        } else if (!remote.link(bot)) { hand.close(); return false; }
        start(s, enemy.getRootVehicle(), bot.position(), now);
        s.drone = remote; s.hand = hand; s.mode = Mode.DRONE;
        return true;
    }
    private ItemStack selectPayload(Bot bot, LivingEntity enemy, WarfareAccess.Ordnance ordnance) {
        boolean armor = skills.warfare().isVehicle(enemy.getRootVehicle());
        ItemStack preferred = item(bot, armor ? "c4_bomb" : "grenade_40mm");
        var preferredData = ordnance.payload(preferred);
        if (preferredData != null && preferredData.radius() > 0 && preferredData.radius() <= 16 && preferredData.damage() > 0 && preferredData.kamikaze() == armor) return preferred;
        ItemStack fallback = ItemStack.EMPTY;
        for (int i = 1; i < bot.getInventory().items.size(); i++) {
            ItemStack stack = bot.getInventory().items.get(i);
            var data = ordnance.payload(stack);
            if (data == null || data.radius() <= 0 || data.damage() <= 0 || data.radius() > 16) continue;
            if (!data.kamikaze() && !WarfareItems.is(stack, "grenade_40mm") && !WarfareItems.is(stack, "hand_grenade")) continue;
            if (armor == data.kamikaze()) return stack;
            if (fallback.isEmpty()) fallback = stack;
        }
        return fallback;
    }
    private boolean tickDrone(Bot bot, State s, WarfareAccess.Ordnance ordnance, long now) {
        Entity drone = s.drone.entity();
        if (drone.isRemoved() || drone.level() != bot.level() || !s.drone.owned(bot) || !s.hand.active()
                || drone.distanceTo(bot) > 128 || !MovementBounds.contains(bot, drone.position(), 4)) { cancel(bot); return false; }
        Entity target = bot.getBotLevel().getEntity(s.target);
        boolean observed = target != null && target.isAlive() && !bot.isAlliedTo(target)
                && target.getPassengers().stream().noneMatch(bot::isAlliedTo)
                && line(bot, drone.getEyePosition(), target.getBoundingBox().getCenter());
        if (observed) { s.observed = target.getBoundingBox().getCenter(); s.velocity = target.getDeltaMovement(); s.seen = now; }
        var payload = s.drone.payload();
        if (payload == null || s.drone.ammo() == 0 || !observed && now - s.seen > 60) s.returning = true;
        if (payload != null && payload.kamikaze() && !safeBlast(bot, s.observed, payload.radius(), target, false)) s.returning = true;
        if (s.returning) {
            if (drone.position().distanceTo(bot.getEyePosition()) < bot.entityInteractionRange()) { cancel(bot); return false; }
            Vec3 home = MovementBounds.clamp(bot, s.home.add(0, 1.5, 0), 4);
            if (!line(bot, drone.position(), home)) home = new Vec3(home.x, Math.max(drone.getY(), SkillUtil.surfaceY(bot.getBotLevel(), drone.position()) + 8), home.z);
            if (payload != null && payload.kamikaze() && drone.position().distanceTo(s.observed) < 18)
                home = drone.position().add(0, 8, 0); // Pull up before a dangerous native collision.
            pilotDrone(bot, s, home); return true;
        }
        Vec3 lead = s.velocity.scale(Math.min(10, drone.position().distanceTo(s.observed) / 0.7));
        Vec3 goal = s.observed.add(lead);
        double horizontal = SkillUtil.horizontalDistance(drone.position(), goal);
        double ground = SkillUtil.surfaceY(bot.getBotLevel(), drone.position());
        double desiredY = payload.kamikaze() && horizontal < 12 ? goal.y : Math.max(ground + 8, goal.y + 7);
        goal = MovementBounds.clamp(bot, new Vec3(goal.x, desiredY, goal.z), 4);
        if (!bot.getBotLevel().hasChunkAt(BlockPos.containing(goal)) || !line(bot, drone.position(), goal)) {
            // Go above a local obstruction, with a finite mission deadline instead of loading new terrain.
            goal = MovementBounds.clamp(bot, drone.position().add(0, 6, 0), 4);
            if (!line(bot, drone.position(), goal)) { cancel(bot); return false; }
        }
        if (observed && now >= s.nextFire) {
            DropForecast forecast = payload.kamikaze() ? new DropForecast(drone.position(), 0) : dropImpact(drone, s.observed.y, payload.dropPosition());
            Vec3 impact = forecast.position();
            Vec3 predictedTarget = s.observed.add(s.velocity.scale(Math.min(25, forecast.ticks())));
            double error = SkillUtil.horizontalDistance(impact, predictedTarget);
            boolean ready = payload.kamikaze() ? drone.position().distanceTo(s.observed) < 6.5 : error < 3.2 && drone.getY() > s.observed.y + 3;
            if (ready && safeBlast(bot, impact, payload.radius(), target, false)
                    && safeBlast(bot, s.observed, payload.radius(), target, false)) {
                s.drone.fire(bot); s.drops++; s.nextFire = now + (skills.hardness(bot).level() == 10 ? 20 : skills.hardness(bot).level() == 9 ? 30 : 40);
            }
        }
        pilotDrone(bot, s, goal);
        return true;
    }
    private void pilotDrone(Bot bot, State s, Vec3 goal) {
        Entity entity = s.drone.entity(); Vec3 velocity = entity.getDeltaMovement();
        Vec3 delta = goal.subtract(entity.position());
        float yaw = SkillUtil.yawTo(entity.position(), goal);
        double yawError = Mth.wrapDegrees(yaw - entity.getYRot());
        double forwardError = delta.multiply(1, 0, 1).dot(entity.getLookAngle().multiply(1, 0, 1).normalize());
        double forwardSpeed = velocity.dot(entity.getLookAngle().multiply(1, 0, 1).normalize());
        int keys = 0;
        if (Math.abs(yawError) < 45) {
            double desired = Math.min(0.75, Math.max(-0.3, forwardError * 0.14));
            if (forwardSpeed < desired - 0.07) keys |= 4;
            else if (forwardSpeed > desired + 0.07) keys |= 8;
        }
        int vertical = delta.y - velocity.y * 5 > 0.5 ? 16 : delta.y - velocity.y * 5 < -0.5 ? 32 : 0;
        if (vertical != s.lastVertical) s.verticalTicks = 0;
        if (vertical != 0 && ++s.verticalTicks >= 6) { vertical = 0; s.verticalTicks = 0; }
        s.lastVertical = vertical;
        keys |= vertical;
        s.drone.input(bot, keys, Mth.clamp(yawError * 0.65, -20, 20), Mth.clamp(-entity.getXRot(), -8, 8));
    }
    private record DropForecast(Vec3 position, int ticks) {}
    private static DropForecast dropImpact(Entity drone, double y, Vec3 offset) {
        // Native droneDrop inherits exactly 20% of velocity; supported FastThrowable bombs
        // have no air drag and apply 0.05 gravity after moving, not a player throw trajectory.
        Vec3 p = drone.position().add(offset), v = drone.getDeltaMovement().scale(0.2);
        int ticks = 0;
        while (ticks < 60 && p.y > y) { p = p.add(v); v = v.add(0, -0.05, 0); ticks++; }
        return new DropForecast(p, ticks);
    }
    private boolean tickC4(Bot bot, State s, @Nullable LivingEntity enemy, WarfareAccess.Ordnance ordnance, long now) {
        BotMemory mem = skills.memory(bot);
        Entity target = bot.getBotLevel().getEntity(s.target);
        // Remembered motion is bounded; hidden opponents never supply fresh coordinates.
        if (target != null && enemy != null && enemy.getRootVehicle() == target && skills.canSee(bot, enemy) && bot.hasLineOfSight(enemy)) {
            s.observed = target.getBoundingBox().getCenter(); s.targetBounds = target.getBoundingBox();
            s.velocity = target.getDeltaMovement(); s.seen = now;
        }
        Vec3 predicted = s.observed.add(s.velocity.scale(Math.min(25, now - s.seen)));
        if (s.mode == Mode.C4_APPROACH) {
            if (!has(bot, "c4_bomb") || !has(bot, "detonator") || !skills.pilot().canFly(bot) || target == null || !target.isAlive()
                    || bot.isAlliedTo(target) || target.getPassengers().stream().anyMatch(bot::isAlliedTo)) { cancel(bot); return false; }
            if (now - s.seen > 80) { cancel(bot); return false; }
            double distance = SkillUtil.horizontalDistance(bot.position(), predicted);
            if (distance < s.closest - 0.5) { s.closest = distance; s.progressAt = now; }
            // A missed opportunity is one failed pass, never an orbit around the same centre.
            if (bot.position().subtract(predicted).dot(s.passDirection) > 8) return finishC4(bot, s, "MISSED_PASS");
            if (now - s.started > 120 || now - s.progressAt > 40) return finishC4(bot, s, "NO_PROGRESS");
            mem.flightGoal = predicted.add(s.velocity.scale(8)).add(s.passDirection.scale(40));
            skills.pilot().tick(bot, mem, enemy, now);
            if (mem.flight == BotMemory.Flight.NONE) { cancel(bot); return false; }
            double height = bot.getEyeY() - predicted.y;
            if (bot.isGliding() && height >= 2 && height <= 14 && distance < 12 && now - s.seen <= 30) {
                if (!safeBlast(bot, predicted, ordnance.chargeRadius(), target, true)) return finishC4(bot, s, "UNSAFE_PASS");
                ItemStack stack = item(bot, "c4_bomb");
                if (bot.getCooldowns().isOnCooldown(stack.getItem())) return true;
                C4Throw aim = c4Throw(bot, s, now, ordnance.chargeRadius());
                if (aim == null) return true;
                if (!safeBlast(bot, aim.impact(), ordnance.chargeRadius(), target, true)) return finishC4(bot, s, "UNSAFE_PASS");
                float flightYaw = bot.getYRot(), flightPitch = bot.getXRot();
                Set<UUID> before = new HashSet<>(); ordnance.charges(bot).forEach(c -> before.add(c.getUUID()));
                try (Hand hand = Hand.take(bot, stack)) {
                    if (hand == null) { cancel(bot); return false; }
                    bot.setLook(aim.yaw(), aim.pitch());
                    CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean("Control", true));
                    stack.getItem().use(bot.level(), bot, InteractionHand.MAIN_HAND);
                } finally {
                    // Throw aim is temporary; the next physics tick keeps the existing flight heading.
                    bot.setLook(flightYaw, flightPitch);
                }
                Entity charge = ordnance.charges(bot).stream().filter(c -> !before.contains(c.getUUID())).findFirst().orElse(null);
                if (charge != null) {
                    s.charge = charge.getUUID(); s.mode = Mode.C4_EGRESS; s.placedAt = now; s.c4Result = "PLACED";
                    Vec3 travel = bot.getVelocity().multiply(1, 0, 1).normalize();
                    if (travel.lengthSqr() > 0.1) s.passDirection = travel;
                }
            }
        } else {
            Entity charge = s.charge == null ? null : bot.getBotLevel().getEntity(s.charge);
            if (charge == null || !has(bot, "detonator")) { cancel(bot); return false; }
            if (now - s.placedAt > 100) return finishC4(bot, s, "EGRESS_TIMEOUT");
            // Keep the exit ahead while checking all charges. A blocked detonation never makes us circle.
            Vec3 exit = bot.position().add(s.passDirection.scale(ordnance.chargeRadius() * 2 + 30));
            mem.flightGoal = MovementBounds.clamp(bot, new Vec3(exit.x, s.observed.y, exit.z), 8);
            if (mem.flight != BotMemory.Flight.NONE) skills.pilot().tick(bot, mem, null, now);
            List<Entity> all = ordnance.charges(bot);
            boolean nearEnemy = target != null && target.isAlive() && (now - s.seen < 100)
                    && charge.position().distanceTo(predicted) < ordnance.chargeRadius() * 1.4;
            boolean safe = !all.isEmpty() && all.stream().allMatch(c -> safeBlast(bot, c.position(), ordnance.chargeRadius(), target, false));
            if (nearEnemy && safe && now - s.placedAt >= 8) {
                ItemStack detonator = item(bot, "detonator");
                if (!bot.getCooldowns().isOnCooldown(detonator.getItem())) {
                    try (Hand hand = Hand.take(bot, detonator)) {
                        if (hand == null) { cancel(bot); return false; }
                        detonator.getItem().use(bot.level(), bot, InteractionHand.MAIN_HAND); s.detonations++;
                    }
                    finishC4(bot, s, "DETONATED"); return true;
                }
            }
            if (!bot.isGliding() && mem.flight == BotMemory.Flight.NONE) { cancel(bot); return false; }
        }
        return true;
    }
    private boolean finishC4(Bot bot, State s, String reason) {
        s.c4Result = reason; cancel(bot); return false;
    }
    private record C4Throw(float yaw, float pitch, Vec3 impact) {}
    @Nullable private static C4Throw c4Throw(Bot bot, State s, long now, double radius) {
        // Match the native spawn offset, 0.5 throw speed, float drag and move-then-gravity order.
        int age = (int) Math.min(25, now - s.seen);
        Vec3 lead = s.observed.add(s.velocity.scale(Math.min(25, age + 12)));
        float yaw = SkillUtil.yawTo(bot.position(), lead);
        C4Throw best = null; double bestScore = Double.MAX_VALUE;
        for (float pitch : new float[]{25, 40, 55, 70, 85}) {
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            Vec3 direction = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
            Vec3 p = bot.getEyePosition().add(0, -0.2F, 0).add(direction.scale(0.25)), v = direction.scale(0.5);
            for (int tick = 1; tick <= 30; tick++) {
                Vec3 end = p.add(v);
                if (!MovementBounds.contains(bot, end, 2) || end.y < bot.level().getMinBuildHeight()
                        || end.y >= bot.level().getMaxBuildHeight() || !bot.getBotLevel().hasChunkAt(BlockPos.containing(p))
                        || !bot.getBotLevel().hasChunkAt(BlockPos.containing(end))) break;
                var ground = bot.getBotLevel().clip(new ClipContext(p, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
                Vec3 motion = s.velocity.scale(Math.min(25, age + tick));
                var vehicle = s.targetBounds.inflate(0.2).move(motion).clip(p, end);
                boolean hitsVehicle = vehicle.isPresent() && (ground.getType() == HitResult.Type.MISS
                        || vehicle.get().distanceToSqr(p) <= ground.getLocation().distanceToSqr(p));
                if (hitsVehicle || ground.getType() != HitResult.Type.MISS) {
                    Vec3 impact = hitsVehicle ? vehicle.get() : ground.getLocation();
                    double error = hitsVehicle ? 0 : SkillUtil.horizontalDistance(impact, s.observed.add(motion));
                    double score = error + tick * 0.02;
                    if (error <= Math.min(4, radius * 0.35) && score < bestScore) {
                        best = new C4Throw(yaw, pitch, impact); bestScore = score;
                    }
                    break;
                }
                p = end; v = v.scale((double) 0.99F).add(0, -0.05, 0);
            }
        }
        return best;
    }
    private static boolean line(Bot bot, Vec3 from, Vec3 to) {
        double distance = from.distanceTo(to);
        if (!Double.isFinite(distance) || distance > 256) return false;
        int steps = Math.max(1, (int) Math.ceil(distance / 4));
        for (int i = 0; i <= steps; i++) {
            Vec3 point = from.lerp(to, (double) i / steps);
            if (!MovementBounds.contains(bot, point, 2) || point.y < bot.level().getMinBuildHeight()
                    || point.y >= bot.level().getMaxBuildHeight() || !bot.getBotLevel().hasChunkAt(BlockPos.containing(point))) return false;
        }
        return bot.getBotLevel().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.MISS;
    }
    private static boolean safeBlast(Bot bot, Vec3 point, double radius, @Nullable Entity target, boolean excludeSelf) {
        double margin = radius * 2 + 3;
        if (!excludeSelf && bot.getBoundingBox().getCenter().distanceTo(point) < margin) return false;
        for (Entity e : bot.getBotLevel().getEntities(bot, new AABB(point, point).inflate(margin))) {
            if (!e.isAlive() || e == target) continue;
            boolean ally = e instanceof LivingEntity && bot.isAlliedTo(e) || e.getPassengers().stream().anyMatch(bot::isAlliedTo);
            if (ally && e.getBoundingBox().getCenter().distanceTo(point) < margin) return false;
        }
        return true;
    }
    private static boolean has(Bot bot, String name) { return !item(bot, name).isEmpty(); }
    private static ItemStack item(Bot bot, String name) {
        for (int i = 1; i < bot.getInventory().items.size(); i++) if (WarfareItems.is(bot.getInventory().items.get(i), name)) return bot.getInventory().items.get(i);
        return ItemStack.EMPTY;
    }

    /** Own a real stack, restore by identity, and never overwrite an administrative slot replacement. */
    private static final class Hand implements AutoCloseable {
        private final Bot bot; private final int source; private final ItemStack stack, display;
        private boolean closed;
        private Hand(Bot bot, int source, ItemStack stack) {
            this.bot = bot; this.source = source; this.stack = stack;
            display = bot.getMainHandItem(); bot.getInventory().items.set(0, stack); bot.getInventory().items.set(source, ItemStack.EMPTY);
        }
        static Hand take(Bot bot, ItemStack stack) {
            if (stack.isEmpty() || bot.isConsuming()) return null;
            for (int i = 1; i < bot.getInventory().items.size(); i++) if (bot.getInventory().items.get(i) == stack) return new Hand(bot, i, stack);
            return null;
        }
        boolean active() { return !closed && bot.getMainHandItem() == stack; }
        @Override public void close() {
            if (closed) return; closed = true;
            var items = bot.getInventory().items;
            if (items.get(0) == stack) items.set(0, display);
            if (stack.isEmpty() || items.stream().anyMatch(s -> s == stack)) return;
            int slot = items.get(source).isEmpty() ? source : -1;
            if (slot < 0) for (int i = 1; i < items.size(); i++) if (items.get(i).isEmpty()) { slot = i; break; }
            if (slot >= 0) items.set(slot, stack); else bot.drop(stack, false);
        }
    }
}
