package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.WarfareAccess;

import javax.annotation.Nullable;
import java.util.*;

/** Threats come from native warnings, visible scoped launchers, or actual missile target UUIDs. */
public final class MissileDefense {
    public record Threat(String alert, @Nullable WarfareAccess.Missile missile, Vec3 origin, double impactTicks) {}
    private static final class State {
        Threat threat;
        String phase = "IDLE";
        long scanAt, warnedUntil, lastThreat = -1000, firstThreat = -1, started, searchAt, rocketAt, maneuverAt;
        Vec3 origin, landing, coverDirection;
        Entity coverMissile;
        BlockPos shelter;
        List<BlockPos> path;
        int pathIndex, placed;
        float yaw, pitch;
        boolean engaged, secured, ceilingEscape;
        final Map<GlobalPos, BlockState> blocks = new LinkedHashMap<>();
    }
    private final BotSkills skills;
    private final Map<Bot, State> states = new HashMap<>();
    private final Map<GlobalPos, BlockState> pending = new LinkedHashMap<>();
    private long placementTick = -1;
    private int placementBudget, placementShare;
    private Set<Bot> placementTurn = Set.of();
    MissileDefense(BotSkills skills) { this.skills = skills; }
    public String describe(Bot bot) {
        State s = states.get(bot);
        return s == null ? "" : "; missileDefense=" + s.phase + "; missileAlert=" + (s.threat == null ? "NONE" : s.threat.alert()) + "; shelterBlocks=" + s.placed;
    }
    public int placed(Bot bot) { State s = states.get(bot); return s == null ? 0 : s.placed; }
    @Nullable public Threat threat(Bot bot, long now) {
        if (!skills.warfare().available() || !skills.enabled(bot, "missiledefense")) return null;
        State s = states.computeIfAbsent(bot, b -> new State());
        if (now < s.scanAt) return s.threat;
        s.scanAt = now + 2;
        Entity body = bot.getRootVehicle();
        s.threat = skills.warfare().access().missiles(bot).stream()
                .map(m -> new Threat("INBOUND", m, m.entity().position(), Math.max(0, (m.entity().position().distanceTo(body.position()) - m.radius() * 2 - body.getBbWidth()) / Math.max(3, m.entity().getDeltaMovement().length()))))
                .min(Comparator.comparingDouble(Threat::impactTicks)).orElse(null);
        if (s.threat == null && now < s.warnedUntil) s.threat = new Threat("LOCK_WARNING", null, s.origin == null ? body.position() : s.origin, 1000);
        if (s.threat == null && now % 4 < 2) {
            // A scoped launcher is only an aim warning; the client's private lock is never inferred as confirmed.
            for (LivingEntity shooter : bot.getBotLevel().getEntitiesOfClass(LivingEntity.class, body.getBoundingBox().inflate(256),
                    e -> e != bot && e.isAlive() && !bot.isAlliedTo(e) && e.getRootVehicle() != body)) {
                ItemStack held = shooter.getMainHandItem();
                if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals("superbwarfare:javelin")) continue;
                if (!skills.warfare().access().isGun(held)) continue;
                var gun = skills.warfare().access().gun(held); var spec = gun.specs();
                if (!spec.projectile().equals("superbwarfare:javelin_missile") || !gun.zooming() || gun.ammo() < spec.cost()) continue;
                Vec3 to = body.getBoundingBox().getCenter().subtract(shooter.getEyePosition());
                if (to.length() > spec.seekRange() || shooter.getLookAngle().dot(to.normalize()) < Math.cos(Math.toRadians(spec.seekAngle()))) continue;
                if (!loaded(bot, new AABB(shooter.getEyePosition(), body.getBoundingBox().getCenter()).inflate(0.1)) || !shooter.hasLineOfSight(bot)) continue;
                s.threat = new Threat("AIM_WARNING", null, shooter.position(), 1000); break;
            }
        }
        if (s.threat != null) { if (now - s.lastThreat > 4) s.firstThreat = now; s.lastThreat = now; }
        return s.threat;
    }
    public void warning(ServerLevel level, Vec3 position, long now) {
        if (!skills.warfare().available()) return;
        for (var terminator : skills.bots()) if (terminator instanceof Bot bot && bot.level() == level && bot.isAlive()
                && bot.getRootVehicle().getOnPos().distSqr(BlockPos.containing(position)) < 3) {
            State s = states.computeIfAbsent(bot, b -> new State()); s.warnedUntil = now + 12; s.scanAt = 0;
        }
    }
    public boolean tick(Bot bot, long now) {
        if (!skills.warfare().available() || !skills.enabled(bot, "missiledefense") || !bot.isAlive()) { forget(bot); return false; }
        Threat alert = threat(bot, now); State s = states.get(bot);
        if (bot.isPassenger()) return false; // The native vehicle pilot consumes the same threat.
        if (alert == null && (!s.engaged || now - s.lastThreat > 35)) { release(bot, s); return false; }
        if (alert != null && !s.engaged) {
            if (now - s.firstThreat < skills.hardness(bot).reactionTicks()) return false;
            s.engaged = true; s.started = now;
            skills.ordnance().cancel(bot); skills.warfare().release(bot);
            bot.cancelRecoveryItem(); bot.lowerBow();
            skills.cancelForMissile(bot);
        }
        if (!s.engaged) return false;
        skills.warfare().release(bot);
        if (bot.isGliding() || !bot.isBotOnGround() && bot.position().y - surface(bot, bot.position()) > 3) {
            fly(bot, s, alert, now); return true;
        }
        bot.stopGliding();
        BotMemory mem = skills.memory(bot); mem.flight = BotMemory.Flight.NONE; mem.maceDrop = false;
        if (s.shelter != null && (s.secured || enclosed(bot, s.shelter)) && bot.position().subtract(Vec3.atBottomCenterOf(s.shelter)).horizontalDistance() < 1.4) {
            s.secured = true;
            boolean intact = enclosed(bot, s.shelter);
            s.phase = intact ? "COVERED" : "REPAIR_SHELTER";
            if (alert != null && alert.missile() != null) coverMove(bot, s, alert);
            if (!intact) build(bot, s, now);
            return true;
        }
        if (s.shelter == null && now >= s.searchAt) {
            s.searchAt = now + 40; s.shelter = findShelter(bot);
            if (s.shelter != null && bot.position().distanceTo(Vec3.atBottomCenterOf(s.shelter)) > 1) {
                s.path = skills.pathfinder().find(bot.getBotLevel(), bot, s.shelter, 12); s.pathIndex = 1;
                if (s.path == null) s.shelter = null;
            }
        }
        if (s.shelter != null && now - s.started < 100 && bot.position().subtract(Vec3.atBottomCenterOf(s.shelter)).horizontalDistance() > 0.3) {
            s.phase = "SEEK_SHELTER"; Vec3 goal = Vec3.atBottomCenterOf(s.shelter);
            if (s.path != null) {
                while (s.pathIndex < s.path.size() && bot.position().distanceTo(Vec3.atBottomCenterOf(s.path.get(s.pathIndex))) < 0.7) s.pathIndex++;
                if (s.pathIndex < s.path.size()) goal = Vec3.atBottomCenterOf(s.path.get(s.pathIndex));
            }
            walk(bot, goal); return true;
        }
        if (s.shelter == null || Math.abs(bot.getY() - s.shelter.getY()) > 0.6 || bot.position().subtract(Vec3.atBottomCenterOf(s.shelter)).horizontalDistance() > 0.5) { s.shelter = feet(bot); s.secured = false; }
        Vec3 center = Vec3.atBottomCenterOf(s.shelter);
        if (bot.position().subtract(center).horizontalDistance() > 0.12) { s.phase = "ALIGN_SHELTER"; walk(bot, center); return true; }
        bot.stand();
        s.phase = enclosed(bot, s.shelter) ? "COVERED" : "BUILD_SHELTER";
        if (!s.phase.equals("COVERED")) build(bot, s, now);
        if (enclosed(bot, s.shelter)) { s.phase = "COVERED"; s.secured = true; }
        return true;
    }
    private void fly(Bot bot, State s, @Nullable Threat alert, long now) {
        if (!bot.isGliding() && skills.enabled(bot, "elytra") && bot.hasUsableElytra()) bot.startGliding();
        if (!bot.isGliding()) { s.phase = "DESCEND"; return; }
        if (s.landing == null || !loaded(bot, new AABB(s.landing, s.landing).inflate(2))) s.landing = landing(bot);
        double floor = s.landing == null ? surface(bot, bot.position()) : s.landing.y;
        double descent = Math.max(0, bot.getY() - floor) / Math.max(0.7, -bot.getVelocity().y);
        boolean urgent = alert != null && alert.missile() != null && alert.impactTicks() < descent + 10;
        if (urgent && alert.missile().topAttack() && alert.origin().y < bot.getY() + 5
                && alert.origin().subtract(bot.position()).horizontalDistance() > 45) urgent = false;
        if (urgent) {
            s.phase = "EVASIVE";
            if (now >= s.maneuverAt) {
                s.maneuverAt = now + 8;
                Vec3 approach = alert.missile().entity().getDeltaMovement().normalize();
                if (approach.horizontalDistanceSqr() < 0.01) approach = bot.position().subtract(alert.origin()).normalize();
                Vec3 side = new Vec3(-approach.z, 0, approach.x).normalize();
                int sign = bot.getUUID().hashCode() % 2 == 0 ? 1 : -1;
                boolean passBelow = alert.missile().topAttack() && alert.origin().y < bot.getY() - 5;
                // Top guidance pauses below its target. Cross above the incoming course before its terminal turn.
                Vec3 escape = passBelow ? bot.position().add(alert.origin().subtract(bot.position()).multiply(1, 0, 1).normalize().scale(35))
                        : bot.position().add(side.scale(sign * 35));
                s.yaw = SkillUtil.yawTo(bot.position(), MovementBounds.clamp(bot, escape, 3));
                s.pitch = passBelow ? -25 : 50;
            }
        } else {
            s.phase = "DESCEND";
            Vec3 landing = s.landing == null ? bot.position().add(bot.getLookAngle().multiply(8, 0, 8)).add(0, -6, 0) : s.landing;
            if (alert != null && alert.missile() != null) {
                Vec3 away = bot.position().subtract(alert.origin()).multiply(1, 0, 1).normalize();
                Vec3 forwardLanding = MovementBounds.clamp(bot, bot.position().add(away.scale(8)), 3);
                if (loaded(bot, new AABB(forwardLanding, forwardLanding).inflate(2))) landing = new Vec3(forwardLanding.x, surface(bot, forwardLanding), forwardLanding.z);
            }
            s.yaw = SkillUtil.yawTo(bot.position(), landing);
            s.pitch = (float) Mth.clamp(-Math.toDegrees(Math.atan2(landing.y - bot.getY(), Math.max(1, landing.subtract(bot.position()).horizontalDistance()))), 25, 65);
        }
        float turn = urgent && skills.hardness(bot).level() >= 8 ? 90 : 35;
        float yaw = Mth.approachDegrees(bot.getYRot(), s.yaw, turn), pitch = Mth.approach(bot.getXRot(), s.pitch, 20);
        Vec3 ahead = bot.position().add(Vec3.directionFromRotation(pitch, yaw).scale(5)).add(bot.getVelocity().scale(3));
        boolean landingSurface = s.phase.equals("DESCEND") && loaded(bot, new AABB(bot.position(), ahead).inflate(0.5))
                && bot.getBotLevel().clip(new ClipContext(bot.position(), ahead, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot)).getLocation().y <= floor + 0.5;
        if (!clear(bot, bot.position(), ahead) && !landingSurface && bot.getY() - floor > 5) { pitch = -35; yaw = Mth.approachDegrees(bot.getYRot(), s.yaw + 90, 35); }
        if (bot.getY() > bot.level().getMaxBuildHeight() - 24) s.ceilingEscape = true;
        if (bot.getY() < bot.level().getMaxBuildHeight() - 48) s.ceilingEscape = false;
        if (s.ceilingEscape) pitch = Math.max(pitch, 55);
        bot.setLook(yaw, pitch);
        if ((urgent || alert != null && alert.missile() != null && bot.getY() - floor > 5) && now >= s.rocketAt && skills.enabled(bot, "elytra") && clear(bot, bot.position(), ahead)) {
            if (bot.fireRocket()) { s.rocketAt = now + 30; skills.memory(bot).lastRocket = now; }
        }
    }
    @Nullable private Vec3 landing(Bot bot) {
        for (int radius : new int[]{0, 4, 8}) for (int i = 0; i < (radius == 0 ? 1 : 8); i++) {
            Vec3 p = bot.position().add(Math.cos(i * Math.PI / 4) * radius, 0, Math.sin(i * Math.PI / 4) * radius);
            if (!loaded(bot, new AABB(p, p).inflate(2))) continue;
            p = new Vec3(p.x, surface(bot, p), p.z); BlockPos feet = BlockPos.containing(p);
            if (p.y <= bot.getBotLevel().getMinBuildHeight() || p.y > bot.getY() + 1 || !solid(bot, feet.below()) || !bot.getBotLevel().getFluidState(feet).isEmpty()) continue;
            if (clear(bot, bot.position(), p.add(0, 1, 0))) return p;
        }
        return null;
    }
    @Nullable private BlockPos findShelter(Bot bot) {
        BlockPos here = feet(bot);
        if (enclosed(bot, here)) return here;
        if (!loaded(bot, new AABB(here).inflate(14, 2, 14))) return null;
        for (int r : new int[]{2, 4, 6, 8}) for (int i = 0; i < 8; i++) {
            BlockPos p = here.offset(Mth.floor(Math.cos(i * Math.PI / 4) * r), 0, Mth.floor(Math.sin(i * Math.PI / 4) * r));
            if (!solid(bot, p.below()) || !SkillUtil.passable(bot.getBotLevel(), p) || !SkillUtil.passable(bot.getBotLevel(), p.above())) continue;
            if (innerShell(p).stream().filter(b -> solid(bot, b)).count() >= 54 && solid(bot, p.above(2))) {
                boolean room = true;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                    room &= SkillUtil.passable(bot.getBotLevel(), p.offset(dx, 0, dz)) && SkillUtil.passable(bot.getBotLevel(), p.offset(dx, 1, dz));
                if (room) return p;
            }
        }
        return null;
    }
    public boolean enclosed(Bot bot, BlockPos feet) {
        return loaded(bot, new AABB(feet).inflate(2)) && shell(feet).stream().allMatch(b -> solid(bot, b));
    }
    private static BlockPos feet(Bot bot) { return BlockPos.containing(bot.getX(), bot.getY() + 0.05, bot.getZ()); }
    private static List<BlockPos> innerShell(BlockPos feet) {
        List<BlockPos> result = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) result.add(feet.offset(dx, 2, dz));
        for (int dy = 0; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            if (Math.abs(dx) == 2 || Math.abs(dz) == 2) result.add(feet.offset(dx, dy, dz));
        result.add(feet.below()); return result;
    }
    private static List<BlockPos> shell(BlockPos feet) {
        List<BlockPos> result = innerShell(feet);
        // The native projectile can cross thin walls in a single tick. Keep a second roof and room to move inside.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) result.add(feet.offset(dx, 3, dz));
        return result;
    }
    private static void coverMove(Bot bot, State s, Threat alert) {
        Entity missile = alert.missile().entity();
        Vec3 center = Vec3.atBottomCenterOf(s.shelter);
        if (s.coverMissile != missile) {
            s.coverMissile = missile;
            Vec3 heading = missile.getDeltaMovement();
            if (heading.horizontalDistanceSqr() < 0.01) heading = bot.position().subtract(missile.position());
            s.coverDirection = new Vec3(-heading.z, 0, heading.x).normalize();
        }
        double distance = missile.position().distanceTo(bot.position());
        // Move clear of the incoming line early enough to brake before native lag-compensated contact.
        Vec3 step;
        if (alert.missile().topAttack()) {
            // Top attack can turn almost instantly; continuous lateral motion works better than a terminal reversal.
            Vec3 radial = bot.position().subtract(center).multiply(1, 0, 1);
            Vec3 tangent = radial.lengthSqr() > 0.04 ? new Vec3(-radial.z, 0, radial.x).normalize() : s.coverDirection;
            step = tangent.scale(0.2).subtract(radial.scale(0.3));
        } else {
            Vec3 goal = center.add(s.coverDirection.scale(distance < 24 ? 1.1 : 0));
            Vec3 delta = goal.subtract(bot.position()).multiply(1, 0, 1);
            step = delta.length() < 0.15 ? bot.getVelocity().multiply(-1, 0, -1) : delta.normalize().scale(0.2);
        }
        AABB next = bot.getBoundingBox().move(step.normalize().scale(0.4)).deflate(0.01);
        if (loaded(bot, next) && bot.level().noCollision(bot, next)) { bot.stand(); bot.walk(step); }
    }
    private void build(Bot bot, State s, long now) {
        if (placementTick != now) {
            placementTick = now; placementBudget = 24;
            List<Bot> builders = states.entrySet().stream().filter(e -> e.getValue().engaged && e.getKey().isAlive() && !e.getKey().isPassenger())
                    .map(Map.Entry::getKey).sorted(Comparator.comparing(Bot::getUUID)).toList();
            placementShare = Math.max(1, 24 / Math.max(1, builders.size()));
            Set<Bot> turn = new HashSet<>();
            for (int i = 0; i < Math.min(24, builders.size()); i++) turn.add(builders.get((int) Math.floorMod(now + i, builders.size())));
            placementTurn = turn;
        }
        if (!placementTurn.contains(bot)) return;
        int limit = Math.min(Math.min(placementBudget, placementShare), skills.hardness(bot).level() >= 9 ? 6 : skills.hardness(bot).level() >= 7 ? 3 : 1);
        Block block = skills.settings().buildBlock; ItemStack resource = ItemStack.EMPTY;
        for (int slot = 1; slot < bot.getInventory().items.size(); slot++) {
            ItemStack candidate = bot.getInventory().items.get(slot);
            if (!candidate.isEmpty() && candidate.getItem() instanceof BlockItem item && strength(item.getBlock()) >= strength(block)) { block = item.getBlock(); resource = candidate; }
        }
        BlockState state = block.defaultBlockState();
        boolean fromInventory = !resource.isEmpty();
        for (BlockPos pos : shell(s.shelter)) {
            if (limit == 0 || !loaded(bot, new AABB(pos))) break;
            if (solid(bot, pos) || !state.isCollisionShapeFullBlock(bot.level(), pos) || !bot.getBotLevel().getBlockState(pos).canBeReplaced()
                    || !bot.getBotLevel().getFluidState(pos).isEmpty() || bot.position().distanceTo(Vec3.atCenterOf(pos)) > 4.5
                    || new AABB(pos).intersects(bot.getBoundingBox())
                    || !bot.getBotLevel().getEntities(bot, new AABB(pos), e -> e.blocksBuilding && !e.isSpectator()).isEmpty()) continue;
            if (fromInventory && (resource.isEmpty() || !bot.consumeItem(resource.getItem()))) break;
            bot.attemptBlockPlace(pos, block, false);
            if (bot.getBotLevel().getBlockState(pos).equals(state)) {
                s.blocks.put(GlobalPos.of(bot.getBotLevel().dimension(), pos), state); s.placed++; limit--; placementBudget--;
            }
        }
    }
    private static double strength(Block block) {
        return block.defaultDestroyTime() * (block.defaultBlockState().getSoundType() == SoundType.METAL ? 3 : 1);
    }
    private static boolean solid(Bot bot, BlockPos pos) {
        return bot.getBotLevel().hasChunkAt(pos) && bot.getBotLevel().getBlockState(pos).isCollisionShapeFullBlock(bot.level(), pos);
    }
    private static double surface(Bot bot, Vec3 point) {
        return bot.getBotLevel().hasChunkAt(BlockPos.containing(point)) ? bot.getBotLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(point.x), Mth.floor(point.z)) : bot.getY();
    }
    private static void walk(Bot bot, Vec3 goal) {
        Vec3 delta = MovementBounds.clamp(bot, goal, 2).subtract(bot.position()).multiply(1, 0, 1);
        bot.stand(); bot.faceLocation(goal);
        Vec3 step = delta.normalize().scale(delta.length() < 0.3 ? 0.05 : 0.2);
        if (bot.horizontalCollision) bot.jump(step.add(0, 0.42, 0)); else bot.walk(step);
    }
    private static boolean loaded(Bot bot, AABB box) {
        if (box.minY < bot.level().getMinBuildHeight() || box.maxY >= bot.level().getMaxBuildHeight()
                || !MovementBounds.contains(bot.level(), new Vec3(box.minX, box.minY, box.minZ), 0) || !MovementBounds.contains(bot.level(), new Vec3(box.maxX, box.maxY, box.maxZ), 0)) return false;
        for (int x = Mth.floor(box.minX) >> 4; x <= Mth.floor(box.maxX) >> 4; x++) for (int z = Mth.floor(box.minZ) >> 4; z <= Mth.floor(box.maxZ) >> 4; z++)
            if (!bot.getBotLevel().hasChunk(x, z)) return false;
        return true;
    }
    private static boolean clear(Bot bot, Vec3 from, Vec3 to) {
        return loaded(bot, new AABB(from, to).inflate(0.5)) && bot.getBotLevel().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, bot)).getType() == HitResult.Type.MISS;
    }
    private void release(Bot bot, State s) {
        if (s.engaged) { skills.memory(bot).nextTakeoff = Math.max(skills.memory(bot).nextTakeoff, bot.getServer().getTickCount() + 100); cleanup(s); }
        s.engaged = false; s.secured = false; s.ceilingEscape = false;
        s.phase = "IDLE"; s.shelter = null; s.landing = null; s.path = null; s.coverMissile = null; s.coverDirection = null;
    }
    private void cleanup(State s) {
        pending.putAll(s.blocks); s.blocks.clear(); sweep();
    }
    public void sweep() {
        pending.entrySet().removeIf(entry -> {
            var level = net.nuggetmc.tplus.TerminatorPlus.getManager().getServer().getLevel(entry.getKey().dimension());
            BlockPos p = entry.getKey().pos();
            if (level == null || !level.hasChunkAt(p)) return false;
            if (level.getBlockState(p).equals(entry.getValue())) level.removeBlock(p, false);
            return true;
        });
    }
    public void invalidate(GlobalPos pos) { pending.remove(pos); states.values().forEach(s -> s.blocks.remove(pos)); }
    public void forget(Bot bot) { State s = states.remove(bot); if (s != null) cleanup(s); }
    public void clear() { for (Bot b : List.copyOf(states.keySet())) forget(b); }
}
