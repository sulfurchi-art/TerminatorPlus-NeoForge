package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.utils.PlayerUtils;
import net.nuggetmc.tplus.bot.Bot;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Team-scoped observations and roles. Every use rechecks actual identity and membership. */
final class TeamBoard {
    private record TeamKey(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, String team) {}
    private record Focus(int target, long until) {}
    private record Signal(UUID target, TeamKey origin, long until) {}
    private record Protection(ServerPlayer ally, LivingEntity threat) {}
    private record Roster(long tick, List<Terminator> members) {}
    private record Roles(UUID target, List<UUID> members, Map<UUID, BotMemory.TeamRole> assignments, long until) {}
    private final BotManager manager;
    private final BotSkills skills;
    private final Map<TeamKey, Focus> focus = new HashMap<>();
    private final TeamDive dives;
    private final Map<UUID, Signal> attacks = new LinkedHashMap<>();
    private final Map<UUID, Signal> injuries = new LinkedHashMap<>();
    private final Map<TeamKey, Roster> rosters = new HashMap<>();
    private final Map<TeamKey, Roles> roles = new HashMap<>();

    TeamBoard(BotManager manager, BotSkills skills) { this.manager = manager; this.skills = skills; this.dives = new TeamDive(skills); }
    private TeamKey key(Terminator bot) { return new TeamKey(bot.getBotLevel().dimension(), bot.getEntity().getTeam().getName()); }
    private boolean cooperative(Terminator bot) {
        return bot.getEntity().getTeam() != null && skills.hardness(bot).tactical() && skills.enabled(bot, "teamwork");
    }
    List<Terminator> members(Terminator bot) {
        if (bot.getEntity().getTeam() == null) return List.of(bot);
        long now = manager.getServer().getTickCount();
        TeamKey key = key(bot);
        Roster roster = rosters.get(key);
        if (roster == null || now - roster.tick() >= 10 || !roster.members().contains(bot)) {
            roster = new Roster(now, manager.fetch().stream().filter(b -> b.isBotAlive() && b.getBotLevel() == bot.getBotLevel()
                    && (b == bot || TacticalSkill.allied(bot.getEntity(), b.getEntity())))
                    .sorted(Comparator.comparing(b -> b.getEntity().getUUID())).toList());
            rosters.put(key, roster);
        }
        return roster.members().stream().filter(b -> b.isBotAlive() && b.getBotLevel() == bot.getBotLevel()
                && (b == bot || TacticalSkill.allied(bot.getEntity(), b.getEntity())) && b.getLocation().distanceTo(bot.getLocation()) < 64).toList();
    }
    private List<ServerPlayer> allies(Terminator bot, double range) {
        return bot.getBotLevel().getEntitiesOfClass(ServerPlayer.class, bot.getEntity().getBoundingBox().inflate(range),
                p -> p.isAlive() && !p.isSpectator() && !p.isCreative() && TacticalSkill.allied(bot.getEntity(), p));
    }
    void attack(ServerPlayer player, LivingEntity target, long now) {
        if (player instanceof Terminator || !player.isAlive() || player.isSpectator() || player.isCreative()
                || player.getTeam() == null || target.level() != player.level() || !target.isAlive() || player.isAlliedTo(target)) return;
        put(attacks, player, target, now + 60);
    }
    void damage(LivingEntity victim, LivingEntity attacker, long now) {
        if (attacker instanceof ServerPlayer player) attack(player, victim, now);
        if (victim instanceof ServerPlayer player && player.getTeam() != null && !player.isAlliedTo(attacker)
                && attacker.level() == player.level()) put(injuries, player, attacker, now + 100);
    }
    private static void put(Map<UUID, Signal> observations, ServerPlayer player, LivingEntity target, long until) {
        observations.remove(player.getUUID());
        if (observations.size() >= 256) observations.remove(observations.keySet().iterator().next());
        observations.put(player.getUUID(), new Signal(target.getUUID(), new TeamKey(player.level().dimension(), player.getTeam().getName()), until));
    }
    @Nullable private LivingEntity observed(Terminator bot, ServerPlayer ally, Map<UUID, Signal> observations, long now) {
        Signal signal = observations.get(ally.getUUID());
        if (signal == null || now > signal.until() || ally.getTeam() == null || !signal.origin().equals(key(bot))
                || !signal.origin().team().equals(ally.getTeam().getName())) return null;
        var target = bot.getBotLevel().getEntity(signal.target());
        return target instanceof LivingEntity living && valid(bot, living) ? living : null;
    }
    private boolean available(Terminator bot) {
        BotMemory mem = skills.memory(bot);
        return cooperative(bot) && bot.getBotHealth() >= bot.getEntity().getMaxHealth() * 0.65
                && (mem.tactic == BotMemory.Tactic.FIGHT || mem.tactic == BotMemory.Tactic.COVER_ALLY)
                && !bot.isGliding() && mem.flight == BotMemory.Flight.NONE && !mem.maceDrop
                && !(bot instanceof Bot b && b.isConsuming());
    }
    @Nullable private Protection protection(Terminator bot, long now) {
        if (!available(bot)) return null;
        List<Terminator> group = members(bot);
        for (ServerPlayer ally : allies(bot, 24).stream().filter(a -> a.getHealth() < a.getMaxHealth() * 0.4)
                .sorted(Comparator.comparingDouble(a -> a.distanceTo(bot.getEntity()))).limit(8).toList()) {
            LivingEntity threat = observed(bot, ally, injuries, now);
            if (threat == null && ally.tickCount - ally.getLastHurtByMobTimestamp() < 100) {
                LivingEntity last = ally.getLastHurtByMob();
                if (last != null && valid(bot, last)) threat = last;
            }
            if (threat == null || threat.distanceTo(ally) > 16) continue;
            LivingEntity enemy = threat;
            Terminator guard = group.stream().filter(this::available)
                    .filter(b -> skills.memory(b).teamRole != BotMemory.TeamRole.GUARD || skills.memory(b).guardedAlly == ally.getId())
                    .filter(b -> b.getLocation().distanceTo(ally.position()) < 24 && valid(b, enemy))
                    .min(Comparator.<Terminator>comparingDouble(b -> b.getLocation().distanceToSqr(ally.position()))
                            .thenComparing(b -> b.getEntity().getUUID())).orElse(null);
            if (guard == bot) return new Protection(ally, threat);
        }
        return null;
    }
    @Nullable LivingEntity select(Terminator bot, @Nullable LivingEntity chosen, long now) {
        BotMemory mem = skills.memory(bot);
        mem.teamRole = BotMemory.TeamRole.NONE; mem.guardedAlly = -1;
        if (!cooperative(bot)) { mem.rolePoint = null; return chosen; }
        Protection protection = protection(bot, now);
        if (protection != null) {
            mem.teamRole = BotMemory.TeamRole.GUARD; mem.guardedAlly = protection.ally().getId();
            mem.roleTarget = protection.threat().getId(); mem.rolePoint = null;
            return protection.threat();
        }
        LivingEntity playerFocus = null;
        long newest = -1;
        for (ServerPlayer ally : allies(bot, 32)) {
            if (ally instanceof Terminator) continue;
            LivingEntity target = observed(bot, ally, attacks, now);
            Signal signal = attacks.get(ally.getUUID());
            if (target != null && signal.until() > newest) { newest = signal.until(); playerFocus = target; }
        }
        TeamKey key = key(bot);
        LivingEntity best = playerFocus;
        boolean reused = false;
        Focus cached = focus.get(key);
        if (best == null && cached != null && now < cached.until()) {
            var target = bot.getBotLevel().getEntity(cached.target());
            if (target instanceof LivingEntity living && valid(bot, living)) { best = living; reused = true; }
        }
        if (best == null) {
            var candidates = new LinkedHashSet<LivingEntity>();
            if (chosen != null && valid(bot, chosen)) candidates.add(chosen);
            for (Terminator member : members(bot)) {
                LivingEntity target = skills.currentTarget(member);
                if (target != null && valid(bot, target)) candidates.add(target);
            }
            double bestScore = Double.MAX_VALUE;
            List<ServerPlayer> nearbyAllies = allies(bot, 24);
            for (LivingEntity target : candidates) {
                double score = target.getHealth() / target.getMaxHealth() * 20 + bot.getLocation().distanceTo(target.position()) * 0.15;
                for (ServerPlayer ally : nearbyAllies) {
                    if (ally.getHealth() < ally.getMaxHealth() * 0.4 && ally.getLastHurtByMob() == target) score -= 30;
                }
                if (score < bestScore) { best = target; bestScore = score; }
            }
        }
        if (best != null) {
            if (!reused && playerFocus == null) focus.put(key, new Focus(best.getId(), now + 10));
            role(bot, mem, best);
        }
        return best != null ? best : chosen;
    }
    private boolean valid(Terminator bot, @Nullable LivingEntity target) {
        double range = skills.settings().targetRange;
        return target != null && target.isAlive() && target.level() == bot.getBotLevel() && target != bot.getEntity()
                && !bot.getEntity().isAlliedTo(target) && !(target instanceof Player p && PlayerUtils.isInvincible(p))
                && (range == 0 || bot.getLocation().distanceToSqr(target.position()) <= range * range) && skills.canSee(bot, target);
    }
    private void role(Terminator bot, BotMemory mem, LivingEntity target) {
        if (mem.roleTarget != target.getId()) { mem.roleTarget = target.getId(); mem.rolePoint = null; mem.nextRolePoint = 0; }
        if (skills.hardness(bot).level() != 10 || !available(bot) || mem.tactic != BotMemory.Tactic.FIGHT) return;
        List<Terminator> group = members(bot).stream().filter(b -> skills.hardness(b).level() == 10 && available(b)
                && skills.memory(b).tactic == BotMemory.Tactic.FIGHT && skills.memory(b).teamRole != BotMemory.TeamRole.GUARD && valid(b, target)).toList();
        if (group.size() < 2) return;
        long now = manager.getServer().getTickCount();
        List<UUID> identifiers = group.stream().map(b -> b.getEntity().getUUID()).toList();
        Roles previous = roles.get(key(bot));
        if (previous != null && now < previous.until() && previous.target().equals(target.getUUID()) && previous.members().equals(identifiers)
                && group.stream().noneMatch(b -> previous.assignments().get(b.getEntity().getUUID()) == BotMemory.TeamRole.ARCHER
                && (!skills.enabled(b, "bow") || !b.canShootBow()))) {
            mem.teamRole = previous.assignments().getOrDefault(bot.getEntity().getUUID(), BotMemory.TeamRole.NONE);
            return;
        }
        Terminator archer = group.size() >= 3 ? group.stream().filter(b -> skills.enabled(b, "bow") && b.canShootBow())
                .max(Comparator.<Terminator>comparingDouble(b -> b.getLocation().distanceToSqr(target.position()))
                        .thenComparing(b -> b.getEntity().getUUID())).orElse(null) : null;
        Terminator bait = group.stream().filter(b -> b != archer)
                .min(Comparator.<Terminator>comparingDouble(b -> b.getLocation().distanceTo(target.position())
                        - (b.getEntity().getOffhandItem().is(Items.SHIELD) || !b.findItem(s -> s.is(Items.SHIELD)).isEmpty() ? 2 : 0))
                        .thenComparing(b -> b.getEntity().getUUID())).orElse(null);
        Map<UUID, BotMemory.TeamRole> assignments = new HashMap<>();
        for (Terminator member : group) assignments.put(member.getEntity().getUUID(), member == archer ? BotMemory.TeamRole.ARCHER
                : member == bait ? BotMemory.TeamRole.BAIT : BotMemory.TeamRole.FLANK);
        roles.put(key(bot), new Roles(target.getUUID(), identifiers, Map.copyOf(assignments), now + 80));
        mem.teamRole = assignments.getOrDefault(bot.getEntity().getUUID(), BotMemory.TeamRole.NONE);
    }
    @Nullable ServerPlayer ward(Terminator bot) {
        var entity = bot.getBotLevel().getEntity(skills.memory(bot).guardedAlly);
        return entity instanceof ServerPlayer player && player.isAlive() && !player.isSpectator()
                && TacticalSkill.allied(bot.getEntity(), player) ? player : null;
    }
    boolean idle(Bot bot, BotMemory mem) {
        if (!cooperative(bot) || ((LegacyAgent) manager.getAgent()).getTargetType() == EnumTargetGoal.NONE || !available(bot)
                || !bot.isBotOnGround() || bot.isBotInWater()) return false;
        ServerPlayer ally = allies(bot, 32).stream().filter(p -> !(p instanceof Terminator) && skills.canSee(bot, p))
                .min(Comparator.comparingDouble(p -> p.distanceTo(bot))).orElse(null);
        if (ally == null) return false;
        mem.teamRole = BotMemory.TeamRole.GUARD; mem.guardedAlly = ally.getId();
        bot.stand(); bot.faceLocation(ally.position()); bot.setItem(bot.getWeapon());
        return bot.distanceTo(ally) <= 5 || step(bot, ally.position());
    }
    boolean position(Bot bot, BotMemory mem, LivingEntity target, long now) {
        if (!cooperative(bot) || skills.hardness(bot).level() != 10 || mem.roleTarget != target.getId()
                || now < mem.groundFallbackUntil || mem.allIn || mem.tactic != BotMemory.Tactic.FIGHT || !available(bot) || bot.isBotInWater() || !bot.isBotOnGround()) return false;
        if ((mem.teamRole == BotMemory.TeamRole.BAIT || mem.teamRole == BotMemory.TeamRole.FLANK) && bot.distanceTo(target) < 4.5) {
            Vec3 point = pursuit(bot, mem, target, target.position(), now);
            if (point.equals(target.position())) return false;
            bot.faceLocation(target.getEyePosition()); bot.stand();
            boolean reachable = SkillUtil.reach(bot, target) < 3 && bot.hasLineOfSight(target);
            if (reachable && skills.meleeReady(bot)) bot.attackTarget(target);
            if (SkillUtil.horizontalDistance(bot.position(), point) > 0.8) {
                if (step(bot, point)) return true;
            } else if (reachable) return true;
            // A failed formation step must release control to digging and the ordinary unstuck navigator.
            mem.groundFallbackUntil = now + 100; mem.rolePoint = null; mem.nextRolePoint = 0;
            return false;
        }
        if (mem.teamRole != BotMemory.TeamRole.ARCHER || bot.distanceTo(target) < 6) return false;
        Vec3 point = archerPoint(bot, mem, target, now);
        if (point == null) return false;
        bot.faceLocation(target.getEyePosition()); bot.setItem(bot.getWeapon()); bot.stand();
        if (skills.bow().tryStart(bot, mem, target, now)) {
            mem.combo = BotMemory.Combo.NONE; mem.comboConfirmed = false;
            return true;
        }
        if (SkillUtil.horizontalDistance(bot.position(), point) <= 1.5 && now < mem.nextArrow && bot.canShootBow() && bot.hasLineOfSight(target)) return true;
        if (SkillUtil.horizontalDistance(bot.position(), point) > 1.5 && step(bot, point)) return true;
        mem.groundFallbackUntil = now + 100; mem.rolePoint = null; mem.nextRolePoint = 0;
        return false;
    }
    Vec3 pursuit(Terminator bot, BotMemory mem, LivingEntity target, Vec3 fallback, long now) {
        if (!cooperative(bot) || skills.hardness(bot).level() != 10 || mem.roleTarget != target.getId()
                || mem.tactic != BotMemory.Tactic.FIGHT || mem.allIn) return fallback;
        if (mem.teamRole == BotMemory.TeamRole.ARCHER && bot instanceof Bot b) {
            Vec3 point = archerPoint(b, mem, target, now); return point == null ? fallback : point;
        }
        if (mem.teamRole == BotMemory.TeamRole.BAIT) {
            Vec3 point = standable(bot, target.position().add(target.getLookAngle().multiply(1, 0, 1).normalize().scale(2.5)));
            return point == null ? fallback : point;
        }
        if (mem.teamRole != BotMemory.TeamRole.FLANK) return fallback;
        List<Terminator> flanks = members(bot).stream().filter(b -> skills.memory(b).teamRole == BotMemory.TeamRole.FLANK).toList();
        int index = Math.max(0, flanks.indexOf(bot));
        Vec3 facing = target.getLookAngle().multiply(1, 0, 1).normalize();
        double angle = Math.atan2(facing.z, facing.x) + Math.PI + (index % 2 == 0 ? 1 : -1) * Math.PI / 3;
        Vec3 predicted = target.position().add(mem.targetVelocity.scale(5));
        double radius = bot.getLocation().distanceTo(target.position()) < 4.5 ? 2.4 : 4;
        Vec3 point = standable(bot, predicted.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
        return point == null ? fallback : point;
    }
    @Nullable private Vec3 archerPoint(Bot bot, BotMemory mem, LivingEntity target, long now) {
        if (now < mem.nextRolePoint && (mem.rolePoint == null || mem.rolePoint.distanceTo(target.position()) > 9
                && mem.rolePoint.distanceTo(target.position()) < 20)) return mem.rolePoint;
        mem.nextRolePoint = now + 10;
        List<Vec3> candidates = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double angle = i * Math.PI / 6;
            Vec3 point = standable(bot, target.position().add(Math.cos(angle) * 14, 0, Math.sin(angle) * 14));
            if (point == null || bot.getBotLevel().clip(new ClipContext(point.add(0, 1.6, 0), target.getEyePosition(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() != HitResult.Type.MISS) continue;
            candidates.add(point);
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distanceToSqr(bot.position())));
        for (Vec3 point : candidates) {
            Vec3 origin = point.add(0, bot.getEyeHeight() - 0.1, 0);
            var aim = ArrowAim.solveFrom(bot.getBotLevel(), bot, origin, target, mem.targetVelocity);
            if (aim == null || !ArrowAim.clearAlliesFrom(bot.getBotLevel(), bot, origin, target, aim, mem.targetVelocity)) continue;
            mem.rolePoint = point; return point;
        }
        mem.rolePoint = null; return null;
    }
    @Nullable private static Vec3 standable(Terminator bot, Vec3 point) {
        BlockPos base = BlockPos.containing(point);
        for (int dy = 1; dy >= -1; dy--) {
            BlockPos feet = base.offset(0, dy, 0);
            if (!bot.getBotLevel().hasChunkAt(feet) || !SkillUtil.passable(bot.getBotLevel(), feet)
                    || !SkillUtil.passable(bot.getBotLevel(), feet.above()) || !bot.getBotLevel().getFluidState(feet).isEmpty()
                    || !bot.getBotLevel().getBlockState(feet.below()).isFaceSturdy(bot.getBotLevel(), feet.below(), Direction.UP)
                    || !PearlAim.isSafeLanding(bot.getBotLevel(), feet.below())) continue;
            return Vec3.atBottomCenterOf(feet);
        }
        return null;
    }
    private static boolean step(Bot bot, Vec3 goal) {
        Vec3 direction = goal.subtract(bot.position()).multiply(1, 0, 1).normalize();
        Vec3 floor = standable(bot, bot.position().add(direction));
        if (floor == null || Math.abs(floor.y - bot.getY()) > 1.1) return false;
        Vec3 move = direction.scale(0.2);
        if (floor.y > bot.getY() + 0.1 || bot.horizontalCollision) bot.jump(move.add(0, 0.42, 0)); else bot.walk(move);
        return true;
    }
    boolean dive(Terminator bot, LivingEntity target, long now) {
        if (skills.hardness(bot).level() != 10 || !skills.enabled(bot, "teamwork") || bot.getEntity().getTeam() == null) return true;
        return dives.release(bot, target, members(bot), now);
    }
    @Nullable Vec3 diveStaging(Terminator bot, LivingEntity target, long now) { return dives.staging(bot, target, now); }
    void updateDive(Terminator bot, @Nullable LivingEntity target, long now) { dives.update(bot, target, now); }
    void remove(Terminator bot, long now) { dives.remove(bot, now); }
    void sweep(long now) {
        attacks.values().removeIf(s -> now > s.until()); injuries.values().removeIf(s -> now > s.until());
        focus.values().removeIf(s -> now > s.until()); dives.sweep(now);
        rosters.values().removeIf(r -> now - r.tick() > 100);
        roles.values().removeIf(r -> now > r.until());
    }
    void clear() { focus.clear(); dives.clear(); attacks.clear(); injuries.clear(); rosters.clear(); roles.clear(); }
}
