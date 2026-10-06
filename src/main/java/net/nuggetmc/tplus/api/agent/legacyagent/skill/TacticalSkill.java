package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.bot.Bot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** High difficulty tactics use existing walking, jumping, items and vanilla elytra steering. */
final class TacticalSkill {
    private final BotSkills skills;
    private final BotManager manager;
    private final Map<GlobalPos, net.minecraft.world.level.block.state.BlockState> pendingCover = new LinkedHashMap<>();

    TacticalSkill(BotSkills skills, BotManager manager) { this.skills = skills; this.manager = manager; }

    static boolean allied(LivingEntity a, LivingEntity b) {
        return a != b && a.getTeam() != null && a.isAlliedTo(b);
    }

    private List<ServerPlayer> allies(Bot bot, double range) {
        return bot.getBotLevel().getEntitiesOfClass(ServerPlayer.class, bot.getBoundingBox().inflate(range),
                p -> p.isAlive() && !p.isSpectator() && allied(bot, p));
    }

    boolean tick(Terminator terminator, BotMemory mem, @Nullable LivingEntity target, long now) {
        if (!(terminator instanceof Bot bot)) return false;
        Hardness difficulty = skills.hardness(bot);
        if (!difficulty.tactical()) {
            bot.cancelRecoveryItem();
            mem.tactic = BotMemory.Tactic.FIGHT;
            mem.flightGoal = null;
            if (mem.plan == BotMemory.FlightPlan.RECOVER || mem.plan == BotMemory.FlightPlan.HUNT) {
                mem.plan = BotMemory.FlightPlan.TRAVEL;
            }
            cleanup(bot, mem);
            return false;
        }

        if (!mem.cover.isEmpty() && now >= mem.coverExpires) cleanup(bot, mem);
        if (skills.enabled(bot, "teamwork")) support(bot, mem, target, now);
        else if (mem.tactic == BotMemory.Tactic.COVER_ALLY) { mem.tactic = BotMemory.Tactic.FIGHT; mem.protectedAlly = null; }
        boolean recovery = skills.enabled(bot, "recovery");
        if (!recovery) {
            bot.cancelRecoveryItem();
        }

        double health = bot.getHealth() / bot.getMaxHealth();
        boolean gunFight = target != null && bot.position().distanceToSqr(target.position()) > 25
                && (skills.warfare().readyToEngage(bot, target) || skills.warfare().canEngage(bot, target));
        boolean armedTotem = bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || bot.countItem(Items.TOTEM_OF_UNDYING) > 0;
        double retreatHealth = gunFight && difficulty.level() == 10 && armedTotem ? Math.min(0.28, difficulty.retreatHealth()) : difficulty.retreatHealth();
        double resumeHealth = gunFight && difficulty.level() == 10 ? Math.min(0.65, difficulty.resumeHealth()) : difficulty.resumeHealth();
        boolean pressured = now - mem.lastDamage < 30 && mem.pressureHits >= difficulty.pressureHits()
                && (!gunFight || health < 0.5);
        if (mem.tactic == BotMemory.Tactic.FIGHT || mem.tactic == BotMemory.Tactic.COVER_ALLY) {
            if (now >= mem.nextRetreat && (pressured || recovery && health <= retreatHealth)) {
                beginRetreat(bot, mem, target, now);
            } else if (!gunFight && recovery && target != null && target.getHealth() / target.getMaxHealth() < 0.3
                    && !(mem.allIn && health > difficulty.retreatHealth() + 0.1)
                    && health < difficulty.resumeHealth() && skills.pilot().canFly(bot) && now >= mem.nextOffensiveFlight
                    && skills.pilot().attackOpportunity(bot, mem, target)) {
                mem.tactic = BotMemory.Tactic.HUNT;
                mem.tacticSince = now;
                mem.clearMovement();
                bot.lowerBow();
                mem.bowTicks = 0;
            }
        }

        if (mem.tactic == BotMemory.Tactic.HUNT) {
            if (mem.allIn && health > difficulty.retreatHealth() + 0.1) { finish(bot, mem, now); return false; }
            boolean searching = target == null && mem.lastSeen != null && now - mem.lastSeenTick < 100;
            if (!searching && (target == null || !target.isAlive()) || now - mem.tacticSince > 200 || health >= difficulty.resumeHealth()) {
                finish(bot, mem, now);
                return false;
            }
            Vec3 predicted = searching ? mem.lastSeen : target.position().add(mem.targetVelocity.scale(12));
            double angle = now * 0.04 + bot.getUUID().hashCode();
            mem.flightGoal = predicted.add(Math.cos(angle) * 12, 14, Math.sin(angle) * 12);
            if (mem.flight == BotMemory.Flight.NONE && !bot.isGliding()) {
                if (!skills.pilot().tryStart(bot, mem, BotMemory.FlightPlan.HUNT, now)) {
                    finish(bot, mem, now);
                    return false;
                }
            }
            if (now - mem.lastDamage > 20 && !bot.isConsuming()) bot.beginRecoveryItem(false);
            return false; // the flight controller continues steering during item use
        }

        if (mem.tactic != BotMemory.Tactic.RETREAT && mem.tactic != BotMemory.Tactic.RECOVER) {
            // Use a long reload window for recovery without surrendering a ready ranged weapon.
            if (recovery && gunFight && health < 0.7 && !bot.isConsuming() && !skills.warfare().readyToEngage(bot, target)
                    && now - mem.lastDamage > 30 && (bot.position().distanceToSqr(target.position()) > 196 || !bot.hasLineOfSight(target))
                    && skills.warfare().longestReload(bot) >= 40) bot.beginRecoveryItem(false);
            return bot.isConsuming();
        }
        Vec3 threat = target != null ? target.position() : mem.threat;
        boolean safe = threat == null || bot.position().distanceTo(threat) >= 14 && now - mem.lastDamage > 20 || !visible(bot, threat.add(0, 1.5, 0), bot.getEyePosition());
        if (now - mem.tacticSince > 300 || health >= resumeHealth
                && now - mem.tacticSince >= 40 && now - mem.lastDamage >= 30 && safe) {
            finish(bot, mem, now);
            return false;
        }
        if (safe) mem.tactic = BotMemory.Tactic.RECOVER;
        // Empty supplies must not reserve ground movement for the full fifteen-second retreat window.
        if (safe && now - mem.tacticSince >= 60 && !bot.isConsuming() && !bot.hasRecoveryItem() && !bot.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)) {
            finish(bot, mem, now); return false;
        }
        if (bot.isBotOnGround() && !safe && now >= mem.nextCover && threat != null) {
            buildCover(bot, mem, bot.position(), threat, difficulty.level());
            mem.nextCover = now + 60;
        }

        if (mem.safePoint == null || (now - mem.tacticSince) % 40 == 0) mem.safePoint = findSafePoint(bot, threat, difficulty.level());
        if (mem.safePoint != null) {
            mem.flightGoal = mem.safePoint.add(0, 6, 0);
            if (mem.flight == BotMemory.Flight.NONE && !bot.isGliding() && !safe) {
                skills.pilot().tryStart(bot, mem, BotMemory.FlightPlan.RECOVER, now);
            }
            if (!safe && mem.flight == BotMemory.Flight.NONE && !bot.isGliding() && now >= mem.nextPearl && bot.getHealth() > 7
                    && skills.enabled(bot, "pearls") && skills.pearls().throwTowards(bot, mem, mem.safePoint, 3, now)) return true;
        }

        if (recovery && !bot.isConsuming() && (safe || bot.isGliding() && now - mem.lastDamage > 20)) bot.beginRecoveryItem(false);
        if (recovery && difficulty.level() >= 9 && !safe && !bot.isConsuming() && now - mem.tacticSince > 60 && now >= mem.nextChorus) {
            if (bot.beginRecoveryItem(true)) mem.nextChorus = now + 100;
        }
        if (bot.isGliding() || mem.flight != BotMemory.Flight.NONE) return false;
        if (bot.isConsuming()) return true;
        if (!safe && mem.safePoint != null) walk(bot, mem.safePoint);
        else { bot.stand(); bot.setItem(null); }
        return true;
    }

    private void beginRetreat(Bot bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        mem.recoverySearchUntil = 0;
        mem.tactic = BotMemory.Tactic.RETREAT;
        mem.tacticSince = now;
        mem.threat = target == null ? null : target.position();
        mem.safePoint = findSafePoint(bot, mem.threat, skills.hardness(bot).level());
        mem.clearMovement();
        mem.maceDrop = false;
        mem.bowTicks = 0;
        bot.lowerBow();
        if (mem.flight != BotMemory.Flight.NONE || bot.isGliding()) mem.plan = BotMemory.FlightPlan.RECOVER;
    }

    private void finish(Bot bot, BotMemory mem, long now) {
        bot.cancelRecoveryItem();
        // A lengthy item recovery can outlast ordinary sight memory while our own
        // cover hides the enemy. Search the observed point briefly without reading
        // a hidden entity's new position or pretending the observation is fresh.
        if (mem.lastSeen != null && mem.lastSeenTick >= mem.tacticSince - 100 && now - mem.lastSeenTick < 400)
            mem.recoverySearchUntil = now + 100;
        mem.tactic = BotMemory.Tactic.FIGHT;
        mem.safePoint = null;
        mem.flightGoal = null;
        mem.pressureHits = 0;
        mem.nextRetreat = now + 60;
        if (mem.plan == BotMemory.FlightPlan.RECOVER || mem.plan == BotMemory.FlightPlan.HUNT) mem.plan = BotMemory.FlightPlan.TRAVEL;
        // Covers expire later; walking or a chorus fruit provides the actual escape.
    }

    @Nullable private Vec3 findSafePoint(Bot bot, @Nullable Vec3 threat, int level) {
        Vec3 best = null;
        double bestScore = -Double.MAX_VALUE;
        List<ServerPlayer> nearby = level >= 10 ? allies(bot, 40) : List.of();
        for (int i = 0; i < level * 3; i++) {
            double angle = i * Math.PI * 2 / (level * 3);
            double radius = level >= 9 ? 20 : 12;
            Vec3 raw = bot.position().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            if (!bot.getBotLevel().hasChunkAt(BlockPos.containing(raw))) continue;
            int y = SkillUtil.surfaceY(bot.getBotLevel(), raw);
            BlockPos feet = BlockPos.containing(raw.x, y, raw.z);
            if (!SkillUtil.passable(bot.getBotLevel(), feet) || !SkillUtil.passable(bot.getBotLevel(), feet.above())
                    || !PearlAim.isSafeLanding(bot.getBotLevel(), feet.below())
                    || !bot.getBotLevel().getBlockState(feet.below()).isFaceSturdy(bot.getBotLevel(), feet.below(), Direction.UP)
                    || !bot.getBotLevel().getFluidState(feet).isEmpty()) continue;
            Vec3 candidate = Vec3.atBottomCenterOf(feet);
            double score = threat == null ? 0 : candidate.distanceTo(threat);
            if (threat != null && !visible(bot, threat.add(0, 1.5, 0), candidate.add(0, 1.5, 0))) score += 20;
            score -= Math.abs(candidate.y - bot.getY()) * 2;
            for (ServerPlayer ally : nearby) score += Math.max(0, 8 - candidate.distanceTo(ally.position()));
            if (score > bestScore) { bestScore = score; best = candidate; }
        }
        return best;
    }

    private static boolean visible(Bot bot, Vec3 from, Vec3 to) {
        return bot.getBotLevel().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.MISS;
    }

    private static void walk(Bot bot, Vec3 goal) {
        goal = MovementBounds.clamp(bot, goal, 3);
        Vec3 delta = goal.subtract(bot.position()).multiply(1, 0, 1);
        if (delta.lengthSqr() < 0.1) return;
        bot.faceLocation(goal);
        bot.stand();
        if (bot.isBotOnGround()) {
            Vec3 step = delta.normalize().scale(0.2);
            if (bot.horizontalCollision) bot.jump(step.add(0, 0.42, 0));
            else bot.walk(step);
        }
    }

    private void buildCover(Bot bot, BotMemory mem, Vec3 protectedPos, Vec3 threat, int level) {
        Vec3 direction = threat.subtract(protectedPos).multiply(1, 0, 1);
        if (direction.lengthSqr() < 0.01 || mem.cover.size() >= 12) return;
        direction = direction.normalize();
        Vec3 side = new Vec3(-direction.z, 0, direction.x);
        for (int dx = level >= 9 ? -1 : 0; dx <= (level >= 9 ? 1 : 0); dx++) {
            BlockPos base = BlockPos.containing(protectedPos.add(direction.scale(1.8)).add(side.scale(dx)));
            for (int dy = 0; dy < 2; dy++) {
                BlockPos pos = base.above(dy);
                if (bot.position().distanceTo(Vec3.atCenterOf(pos)) > 4.5 || !bot.getBotLevel().getBlockState(pos).canBeReplaced()
                        || !bot.getBotLevel().getFluidState(pos).isEmpty()
                        || !bot.getBotLevel().getEntities(bot, new AABB(pos)).isEmpty() || new AABB(pos).intersects(bot.getBoundingBox())) continue;
                var state = skills.settings().buildBlock.defaultBlockState();
                bot.attemptBlockPlace(pos, skills.settings().buildBlock, false, "tactical_cover");
                if (bot.getBotLevel().getBlockState(pos).equals(state)) {
                    mem.cover.put(GlobalPos.of(bot.getBotLevel().dimension(), pos), state);
                    mem.coverExpires = manager.getServer().getTickCount() + 600;
                }
            }
        }
    }

    void cleanup(Terminator bot, @Nullable BotMemory mem) {
        if (mem == null) return;
        mem.cover.forEach((location, state) -> {
            var world = manager.getServer().getLevel(location.dimension());
            BlockPos pos = location.pos();
            if (world != null && world.hasChunkAt(pos) && world.getBlockState(pos).equals(state)) {
                world.removeBlock(pos, false);
            } else if (world == null || !world.hasChunkAt(pos)) pendingCover.put(location, state);
        });
        mem.cover.clear();
    }

    void sweep() {
        if (manager.getServer().getTickCount() % 20 != 0) return;
        pendingCover.entrySet().removeIf(entry -> {
            var world = manager.getServer().getLevel(entry.getKey().dimension());
            if (world == null || !world.hasChunkAt(entry.getKey().pos())) return false;
            if (world.getBlockState(entry.getKey().pos()).equals(entry.getValue())) world.removeBlock(entry.getKey().pos(), false);
            return true;
        });
    }

    void invalidate(GlobalPos position, Iterable<BotMemory> memories) {
        pendingCover.remove(position);
        for (BotMemory mem : memories) mem.cover.remove(position);
    }

    private void support(Bot bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        if (mem.tactic == BotMemory.Tactic.COVER_ALLY) mem.tactic = BotMemory.Tactic.FIGHT;
        mem.protectedAlly = null;
        if (target == null || mem.tactic != BotMemory.Tactic.FIGHT || bot.getHealth() < bot.getMaxHealth() * 0.65) return;
        ServerPlayer assigned = skills.guardedAlly(bot);
        List<ServerPlayer> wounded = assigned != null ? List.of(assigned) : allies(bot, 8);
        for (ServerPlayer ally : wounded) {
            if (ally.getHealth() >= ally.getMaxHealth() * 0.4) continue;
            if (now >= mem.nextCover && bot.position().distanceTo(ally.position()) < 4) {
                buildCover(bot, mem, ally.position(), target.position(), skills.hardness(bot).level());
                mem.nextCover = now + 60;
            }
            mem.tactic = BotMemory.Tactic.COVER_ALLY;
            mem.protectedAlly = ally.position();
            break;
        }
    }

    Vec3 pursuitPoint(Terminator bot, BotMemory mem, LivingEntity target, Vec3 fallback) {
        if (!skills.hardness(bot).tactical() || !skills.enabled(bot, "teamwork") || bot.getEntity().getTeam() == null) return fallback;
        if (mem.protectedAlly != null) {
            Vec3 facing = target.position().subtract(mem.protectedAlly).multiply(1, 0, 1).normalize();
            double distance = mem.teamRole == BotMemory.TeamRole.GUARD
                    ? Math.max(2, Math.min(6, target.position().distanceTo(mem.protectedAlly) - 2.5)) : 2;
            return mem.protectedAlly.add(facing.scale(distance));
        }
        List<Terminator> group = manager.fetch().stream().filter(b -> b.isBotAlive() && b.getBotLevel() == bot.getBotLevel()
                && (b == bot || allied(bot.getEntity(), b.getEntity())) && b.getLocation().distanceTo(target.position()) < 40)
                .sorted(java.util.Comparator.comparing(b -> b.getEntity().getUUID())).toList();
        if (group.size() < 2) return fallback;
        int index = group.indexOf(bot);
        double distance = bot.getLocation().distanceTo(target.position());
        if (distance < 4) return target.position();
        Vec3 predicted = target.position().add(mem.targetVelocity.scale(Math.min(20, distance / 0.4)));
        Vec3 forward = mem.targetVelocity.multiply(1, 0, 1);
        if (forward.lengthSqr() < 0.001) forward = target.position().subtract(bot.getLocation()).multiply(1, 0, 1);
        forward = forward.normalize();
        Vec3 side = new Vec3(-forward.z, 0, forward.x);
        double angle = index * Math.PI * 2 / group.size();
        double radius = Math.min(5, distance / 3);
        Vec3 waypoint = predicted.add(side.scale(Math.cos(angle) * radius)).add(forward.scale(Math.sin(angle) * radius));
        return SkillUtil.passable(bot.getBotLevel(), BlockPos.containing(waypoint)) ? waypoint : fallback;
    }
}
