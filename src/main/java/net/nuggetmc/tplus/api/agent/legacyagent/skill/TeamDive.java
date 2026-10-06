package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.bot.Bot;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Schedule individual releases toward a common contact tick, without changing flight physics. */
final class TeamDive {
    private record Key(ResourceKey<Level> dimension, String team, UUID target) {}
    private record Member(Terminator bot, int contactTicks, double angle) {}
    private record Snapshot(UUID target, Vec3 position, Vec3 velocity, Vec3 targetPosition, Vec3 targetVelocity, @Nullable DiveForecast.Contact contact) {}
    private record Assembly(long created, Map<UUID, Member> members) {}
    private static final class Plan {
        final long created, impact;
        final Vec3 origin, velocity;
        final Map<UUID, Member> members;
        final java.util.Set<UUID> released = new java.util.HashSet<>();
        Plan(long now, long impact, Vec3 origin, Vec3 velocity, Map<UUID, Member> members) {
            this.created = now; this.impact = impact; this.origin = origin; this.velocity = velocity; this.members = members;
        }
    }
    private final BotSkills skills;
    private final Map<Key, Plan> plans = new HashMap<>();
    private final Map<Key, Assembly> assemblies = new HashMap<>();
    private final Map<UUID, Snapshot> forecasts = new HashMap<>();
    private long forecastTick = -1;
    TeamDive(BotSkills skills) { this.skills = skills; }

    private boolean enabled(Terminator bot) {
        return bot.getEntity().getTeam() != null && skills.hardness(bot).level() == 10 && skills.enabled(bot, "teamwork");
    }
    private boolean available(Terminator bot, LivingEntity target, boolean released) {
        BotMemory mem = skills.memory(bot);
        return enabled(bot) && bot.isBotAlive() && skills.enabled(bot, "mace") && bot.hasMace()
                && bot.getBotLevel() == target.level() && !bot.getEntity().isAlliedTo(target) && skills.currentTarget(bot) == target
                && bot.getBotHealth() >= bot.getEntity().getMaxHealth() * 0.65 && mem.tactic == BotMemory.Tactic.FIGHT
                && !(bot instanceof Bot b && b.isConsuming()) && !bot.isBotInWater()
                && (released ? mem.maceDrop : skills.enabled(bot, "elytra") && bot.isGliding() && bot.hasUsableElytra()
                && mem.plan == BotMemory.FlightPlan.MACE && mem.flight == BotMemory.Flight.CRUISE);
    }
    private static Key key(Terminator bot, LivingEntity target) { return new Key(bot.getBotLevel().dimension(), bot.getEntity().getTeam().getName(), target.getUUID()); }
    @Nullable private DiveForecast.Contact forecast(Terminator bot, LivingEntity target, long now, boolean fresh) {
        if (forecastTick != now) { forecasts.clear(); forecastTick = now; }
        Vec3 position = bot.getLocation(), velocity = bot.getVelocity(), movement = skills.memory(bot).targetVelocity;
        Snapshot previous = forecasts.get(bot.getEntity().getUUID());
        if (!fresh && previous != null && previous.target().equals(target.getUUID()) && previous.position().equals(position)
                && previous.velocity().equals(velocity) && previous.targetPosition().equals(target.position()) && previous.targetVelocity().equals(movement)) return previous.contact();
        var contact = DiveForecast.contact(bot, target, movement);
        forecasts.put(bot.getEntity().getUUID(), new Snapshot(target.getUUID(), position, velocity, target.position(), movement, contact));
        return contact;
    }
    private boolean separated(List<Terminator> group, LivingEntity target) {
        double threshold = Math.cos(Math.PI * 1.2 / group.size());
        for (int i = 0; i < group.size(); i++) {
            Vec3 first = group.get(i).getLocation().subtract(target.position()).multiply(1, 0, 1);
            if (first.length() < 2) return false;
            for (int j = i + 1; j < group.size(); j++) {
                Vec3 second = group.get(j).getLocation().subtract(target.position()).multiply(1, 0, 1);
                if (second.length() < 2 || first.normalize().dot(second.normalize()) > threshold) return false;
            }
        }
        return true;
    }
    private boolean assemble(Key key, List<Terminator> nearby, Terminator bot, LivingEntity target, long now) {
        List<Terminator> group = nearby.stream().filter(b -> available(b, target, false) && now >= skills.memory(b).nextTeamDive).limit(8).toList();
        if (group.size() < 2) { assemblies.remove(key); return true; }
        Assembly assembly = assemblies.get(key);
        if (assembly != null && now - assembly.created() >= 80) {
            assemblies.remove(key); cancelAssembly(assembly, now); return true;
        }
        if (separated(group, target)) {
            if (assembly != null) {
                assemblies.remove(key);
                group.forEach(b -> skills.memory(b).teamDiveWaitingSince = -1);
            }
            return true;
        }
        if (assembly == null || !assembly.members().keySet().equals(group.stream().map(b -> b.getEntity().getUUID()).collect(java.util.stream.Collectors.toSet()))) {
            Vec3 average = Vec3.ZERO;
            for (Terminator member : group) average = average.add(member.getLocation().subtract(target.position()).multiply(1, 0, 1).normalize());
            double angle = Math.atan2(average.z, average.x);
            Map<UUID, Member> slots = new LinkedHashMap<>();
            for (int i = 0; i < group.size(); i++) {
                Terminator member = group.get(i);
                slots.put(member.getEntity().getUUID(), new Member(member, 0, angle + i * Math.PI * 2 / group.size()));
                BotMemory memory = skills.memory(member); memory.teamDiveTarget = target.getId(); memory.teamDiveWaitingSince = now;
            }
            assembly = new Assembly(now, slots); assemblies.put(key, assembly);
        }
        return !assembly.members().containsKey(bot.getEntity().getUUID());
    }

    boolean release(Terminator bot, LivingEntity target, List<Terminator> nearby, long now) {
        BotMemory mem = skills.memory(bot);
        if (!available(bot, target, false)) { clear(mem); return true; }
        Key key = key(bot, target);
        Plan plan = plans.get(key);
        if (plan == null && now >= mem.nextTeamDive && !assemble(key, nearby, bot, target, now)) return false;
        var forecast = forecast(bot, target, now, true);
        if (forecast == null) { if (plan != null) cancel(key, plan, now); clear(mem); return false; }
        if (plan != null && invalid(plan, target, now)) { cancel(key, plan, now); plan = null; }
        if (now < mem.nextTeamDive || plan == null && nearby.stream().filter(b -> available(b, target, false)).count() < 2) {
            clear(mem); return true;
        }
        if (plan == null) {
            Map<UUID, Member> members = new LinkedHashMap<>();
            for (Terminator member : nearby) {
                if (members.size() >= 8) break;
                if (!available(member, target, false) || now < skills.memory(member).nextTeamDive) continue;
                Vec3 pos = member.getLocation();
                double height = pos.y - target.getY();
                if (height <= 6 || height >= 40 || !member.getEntity().hasLineOfSight(target)) continue;
                if (MaceSkill.landingError(pos, member.getVelocity(), target.position(), skills.memory(member).targetVelocity) >= 1.2) continue;
                var contact = member == bot ? forecast : forecast(member, target, now, false);
                if (contact != null) members.put(member.getEntity().getUUID(), new Member(member, contact.ticks(), Math.atan2(pos.z - target.getZ(), pos.x - target.getX())));
            }
            if (members.size() < 2) {
                if (mem.teamDiveWaitingSince < 0) mem.teamDiveWaitingSince = now;
                mem.teamDiveTarget = target.getId();
                if (now - mem.teamDiveWaitingSince < 20) return false;
                clear(mem); mem.nextTeamDive = now + 40; return true;
            }
            int slowest = members.values().stream().mapToInt(Member::contactTicks).max().orElse(forecast.ticks());
            plan = new Plan(now, now + slowest, target.position(), mem.targetVelocity, members);
            plans.put(key, plan);
            if (Boolean.getBoolean("terminatorplus.selftest")) org.slf4j.LoggerFactory.getLogger(TeamDive.class).info("[SelfTest] Team dive plan: {} at {}, impact {}, members {}", target.getUUID(), now, plan.impact,
                    members.values().stream().map(m -> m.bot().getBotName() + ":" + m.contactTicks()).toList());
            for (Member member : members.values()) {
                BotMemory memory = skills.memory(member.bot());
                memory.teamDiveTarget = target.getId(); memory.teamImpactTick = plan.impact; memory.teamDiveReleased = -1; memory.teamDiveWaitingSince = -1;
            }
        }
        if (!plan.members.containsKey(bot.getEntity().getUUID())) { clear(mem); return true; }
        long estimatedImpact = now + forecast.ticks();
        if (estimatedImpact > plan.impact + 2 || now - plan.created > 30) {
            if (Boolean.getBoolean("terminatorplus.selftest")) org.slf4j.LoggerFactory.getLogger(TeamDive.class).info("[SelfTest] Team dive missed: {} at {}, estimate {}, impact {}", bot.getBotName(), now, estimatedImpact, plan.impact);
            cancel(key, plan, now); return true;
        }
        mem.teamContactTicks = forecast.ticks(); mem.teamImpactTick = plan.impact; mem.teamDiveTarget = target.getId();
        if (estimatedImpact < plan.impact - 1) return false;
        plan.released.add(bot.getEntity().getUUID()); mem.teamDiveReleased = now;
        return true;
    }
    private boolean invalid(Plan plan, LivingEntity target, long now) {
        if (!target.isAlive() || now > plan.impact + 3
                || target.position().distanceToSqr(plan.origin.add(plan.velocity.scale(Math.min(25, now - plan.created)))) > 36) return true;
        return plan.members.entrySet().stream().anyMatch(e -> {
            boolean released = plan.released.contains(e.getKey());
            Terminator member = e.getValue().bot();
            return !available(member, target, released) || !released
                    && (!member.getEntity().hasLineOfSight(target) || forecast(member, target, now, false) == null);
        });
    }
    @Nullable Vec3 staging(Terminator bot, LivingEntity target, long now) {
        BotMemory mem = skills.memory(bot);
        if (!enabled(bot) || mem.teamDiveTarget != target.getId() || mem.teamDiveReleased >= 0 || now < mem.nextTeamDive) return null;
        Assembly assembly = assemblies.get(key(bot, target));
        if (assembly != null && now - assembly.created() < 80) {
            Member member = assembly.members().get(bot.getEntity().getUUID());
            if (member != null && available(bot, target, false)) return target.position().add(mem.targetVelocity.scale(10))
                    .add(Math.cos(member.angle()) * 7, 0, Math.sin(member.angle()) * 7);
        }
        if (mem.teamImpactTick <= now) return null;
        Plan plan = plans.get(key(bot, target));
        Member member = plan == null ? null : plan.members.get(bot.getEntity().getUUID());
        if (member == null || invalid(plan, target, now)) return null;
        return target.position().add(mem.targetVelocity.scale(Math.min(25, mem.teamContactTicks)))
                .add(Math.cos(member.angle()) * 4, 0, Math.sin(member.angle()) * 4);
    }
    void update(Terminator bot, @Nullable LivingEntity target, long now) {
        BotMemory mem = skills.memory(bot);
        if (mem.teamDiveTarget < 0) return;
        if (target != null && enabled(bot) && mem.teamDiveTarget == target.getId() && mem.teamImpactTick > 0) {
            Key key = key(bot, target); Plan plan = plans.get(key);
            if (plan != null && invalid(plan, target, now)) { cancel(key, plan, now); return; }
        }
        if (target == null || mem.teamDiveTarget != target.getId() || !available(bot, target, mem.teamDiveReleased >= 0)) {
            plans.values().removeIf(p -> { if (!p.members.containsKey(bot.getEntity().getUUID())) return false; cancelMembers(p, now); return true; });
            assemblies.values().removeIf(a -> { if (!a.members().containsKey(bot.getEntity().getUUID())) return false; cancelAssembly(a, now); return true; });
            clear(mem);
        }
    }
    private void cancel(Key key, Plan plan, long now) { plans.remove(key); assemblies.remove(key); cancelMembers(plan, now); }
    private void cancelAssembly(Assembly assembly, long now) {
        for (Member member : assembly.members().values()) { BotMemory mem = skills.memory(member.bot()); clear(mem); mem.nextTeamDive = now + 40; }
    }
    private void cancelMembers(Plan plan, long now) {
        for (Member member : plan.members.values()) {
            BotMemory mem = skills.memory(member.bot());
            clear(mem); mem.nextTeamDive = now + 40;
        }
    }
    private static void clear(BotMemory mem) { mem.teamDiveTarget = -1; mem.teamImpactTick = -1; mem.teamContactTicks = -1; mem.teamDiveReleased = -1; mem.teamDiveWaitingSince = -1; }
    void remove(Terminator bot, long now) {
        plans.values().removeIf(p -> { if (!p.members.containsKey(bot.getEntity().getUUID())) return false; cancelMembers(p, now); return true; });
        assemblies.values().removeIf(a -> { if (!a.members().containsKey(bot.getEntity().getUUID())) return false; cancelAssembly(a, now); return true; });
        forecasts.remove(bot.getEntity().getUUID());
    }
    void sweep(long now) {
        plans.values().removeIf(p -> { if (now <= p.impact + 3) return false; cancelMembers(p, now); return true; });
        assemblies.values().removeIf(a -> { if (now - a.created() < 80) return false; cancelAssembly(a, now); return true; });
        if (now - forecastTick > 20) forecasts.clear();
    }
    void clear() {
        plans.values().forEach(p -> p.members.values().forEach(m -> clear(skills.memory(m.bot()))));
        assemblies.values().forEach(a -> a.members().values().forEach(m -> clear(skills.memory(m.bot()))));
        plans.clear(); assemblies.clear(); forecasts.clear();
    }
}
