package net.nuggetmc.tplus.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.BotSkills;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.WarfareAccess.*;

import javax.annotation.Nullable;
import java.util.*;

/** Native seats remain authoritative. A bot never operates another seat's weapons or a human's controls. */
public final class VehicleCrew {
    private static final class Member {
        final Vessel vessel;
        int seat, target = -1, scanDirection = 1;
        long expires, shots, aimSince, lockSince = -1, nextError, mountedPendingUntil = -1;
        double errorYaw, errorPitch;
        boolean leaving;
        List<BlockPos> entryPath; int entryIndex; long nextEntryPath;
        String weaponState = "idle";
        Member(Vessel vessel, int seat, long expires) { this.vessel = vessel; this.seat = seat; this.expires = expires; }
    }
    private record Supply(Gun gun, int baseline) {}
    private static final class Ship {
        final Vessel vessel;
        Ship(Vessel vessel) { this.vessel = vessel; }
        long suppliedTick = -1;
        int energyBaseline = -1;
        final Map<ItemStack, Supply> reserve = new IdentityHashMap<>();
        final Map<String, Double> nextShot = new HashMap<>();
    }
    private final BotSkills skills;
    private final WarfareSupport warfare;
    private final VehiclePilot pilot;
    private final Map<Bot, Member> members = new HashMap<>();
    private final Map<Entity, Ship> ships = new HashMap<>();
    private final Map<Bot, Long> boardingRest = new HashMap<>();

    VehicleCrew(BotSkills skills, WarfareSupport warfare) { this.skills = skills; this.warfare = warfare; this.pilot = new VehiclePilot(warfare, skills); }
    public int navigationPeakExpansions() { return pilot.peakExpansions(); }
    public boolean canUseHand(Bot bot) {
        if (warfare.access() == null || !warfare.access().isVehicle(bot.getVehicle())) return false;
        Vessel v = warfare.access().vessel(bot.getVehicle()); int index = v.seatIndex(bot);
        if (index < 0 || v.wreck()) return false;
        Seat seat = v.seats().get(index);
        return !seat.banHand() && !seat.enclosed() && seat.rotateHead();
    }
    public String describe(Bot bot) {
        Member m = members.get(bot);
        if (m == null) return "";
        return "; crew=" + (bot.getVehicle() == m.vessel.entity() ? "seat " + m.vessel.seatIndex(bot) : "boarding " + m.seat)
                + "; vehicleShots=" + m.shots + "; weapon=" + m.weaponState + "; vehiclePilot=" + pilot.describe(m.vessel.entity()) + "; decoys=" + m.vessel.decoys() + (m.leaving ? "; leaving" : "");
    }
    public long shots(Bot bot) { Member m = members.get(bot); return m == null ? 0 : m.shots; }

    public Entity findVehicle(Bot bot, String name) {
        if (warfare.access() == null) throw new IllegalArgumentException("卓越前线兼容尚未启用。");
        if (!name.equalsIgnoreCase("nearest")) {
            try {
                Entity entity = bot.serverLevel().getEntity(UUID.fromString(name));
                if (entity != null && warfare.access().isVehicle(entity)) return entity;
            } catch (IllegalArgumentException ignored) { }
            throw new IllegalArgumentException("载具 UUID 无效，或载具不在机器人所在维度的已加载区域。");
        }
        return bot.level().getEntities(bot, bot.getBoundingBox().inflate(32), warfare.access()::isVehicle).stream()
                .filter(e -> net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds.contains(e, e.position(), 3))
                .filter(e -> !warfare.access().vessel(e).locked() && !warfare.access().vessel(e).wreck())
                .filter(e -> e.getPassengers().stream().allMatch(p -> p == bot || bot.isAlliedTo(p)))
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(bot)))
                .orElseThrow(() -> new IllegalArgumentException("附近 32 格没有可用的未上锁载具。"));
    }
    public void board(Bot bot, Entity entity, int requestedSeat) {
        Vessel vessel = warfare.vessel(entity);
        if (!net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds.contains(entity, entity.position(), 3))
            throw new IllegalArgumentException("载具须在世界边界内并保留登车空间。");
        if (bot.level() != entity.level() || bot.position().distanceTo(entity.position()) > 48 || vessel.locked() || vessel.wreck())
            throw new IllegalArgumentException("载具须在同维度 48 格内，未上锁且未损毁。");
        if (entity.getPassengers().stream().anyMatch(p -> p != bot && !bot.isAlliedTo(p)))
            throw new IllegalArgumentException("载具上有不同队伍的乘员；先将机器人加入同一原版队伍。");
        int seat = requestedSeat;
        if (seat < 0) {
            for (Seat candidate : vessel.seats()) if (free(vessel, candidate.index(), bot)
                    && (candidate.index() != 0 || supportsDriver(bot, vessel))) { seat = candidate.index(); break; }
        }
        if (seat < 0 || seat >= vessel.seats().size() || !free(vessel, seat, bot)) throw new IllegalArgumentException("该座位已被占用或预留，或者座位编号无效。");
        if (!skills.enabled(bot, "vehicles") && !(vessel.engine().equals("FIXED") && skills.enabled(bot, "vehicleweapons")))
            throw new IllegalArgumentException("登车需要 7 级及 vehicles；固定火力需要 4 级及 vehicleweapons。");
        if (seat == 0 && !supportsDriver(bot, vessel)) throw new IllegalArgumentException("直升机驾驶需要 9–10 级及 helicopters；该载具引擎类型尚不支持驾驶。");
        if (bot.isPassenger() && bot.getVehicle() != entity) throw new IllegalArgumentException("机器人正在另一台载具上，先让它离席。");
        warfare.release(bot); bot.stopGliding();
        members.put(bot, new Member(vessel, seat, bot.server.getTickCount() + 400L));
    }
    public int boardCrew(Bot leader, Entity entity) {
        List<Bot> team = new ArrayList<>(); team.add(leader);
        if (leader.getTeam() != null) skills.bots().stream().filter(t -> t instanceof Bot).map(t -> (Bot) t)
                .filter(b -> b != leader && b.isAlive() && b.level() == leader.level() && leader.isAlliedTo(b)
                        && b.distanceToSqr(leader) <= 32 * 32 && !b.isPassenger() && !members.containsKey(b) && skills.enabled(b, "vehicles"))
                .sorted(Comparator.comparingDouble(b -> b.distanceToSqr(leader))).forEach(team::add);
        Vessel vessel = warfare.vessel(entity); int count = 0;
        // Reserve the driver first; each subsequent request sees earlier reservations immediately.
        board(leader, entity, supportsDriver(leader, vessel) && free(vessel, 0, leader) ? 0 : -1); count++;
        for (Bot bot : team.subList(1, team.size())) {
            if (vessel.seats().stream().noneMatch(s -> free(vessel, s.index(), bot) && (s.index() != 0 || supportsDriver(bot, vessel)))) break;
            board(bot, entity, -1); count++;
        }
        return count;
    }
    public void go(Bot bot, Vec3 point) {
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) throw new IllegalArgumentException("坐标必须为有限数值。");
        Member member = members.get(bot);
        Vessel v = bot.isPassenger() && warfare.access() != null && warfare.access().isVehicle(bot.getVehicle())
                ? warfare.vessel(bot.getVehicle()) : member == null ? null : member.vessel;
        if (v == null || !supportsDriver(bot, v) || (bot.isPassenger() ? v.seatIndex(bot) : member.seat) != 0)
            throw new IllegalArgumentException("先安排该机器人在支持驾驶的载具 0 号位。");
        if (!bot.level().hasChunkAt(BlockPos.containing(point)) || !net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds.contains(v.entity(), point, 8))
            throw new IllegalArgumentException("目的地必须在当前已加载区域和世界边界内。");
        pilot.go(v, point);
    }
    public void leave(Bot bot) {
        Member m = members.get(bot);
        if (m != null) m.leaving = true;
        else if (warfare.access() != null && warfare.access().isVehicle(bot.getVehicle())) {
            m = new Member(warfare.vessel(bot.getVehicle()), warfare.vessel(bot.getVehicle()).seatIndex(bot), Long.MAX_VALUE);
            m.leaving = true; members.put(bot, m);
        }
        boardingRest.put(bot, bot.server.getTickCount() + 200L);
    }
    private boolean free(Vessel v, int seat, Bot bot) {
        if (v.passenger(seat) != null && v.passenger(seat) != bot) return false;
        return members.entrySet().stream().noneMatch(e -> e.getKey() != bot && e.getValue().vessel.entity() == v.entity()
                && e.getValue().seat == seat && e.getKey().isAlive() && !e.getValue().leaving);
    }
    private boolean supportsDriver(Bot bot, Vessel v) {
        return switch (v.engine()) {
            case "HELICOPTER" -> skills.enabled(bot, "vehicles") && skills.enabled(bot, "helicopters");
            case "WHEEL", "TRACK", "WHEELCHAIR" -> skills.enabled(bot, "vehicles");
            case "FIXED" -> skills.enabled(bot, "vehicleweapons");
            default -> false;
        };
    }

    boolean tick(Bot bot, @Nullable LivingEntity enemy, Vec3 observedVelocity, long now) {
        Member m = members.get(bot);
        if (bot.isPassenger() && warfare.access().isVehicle(bot.getVehicle())) {
            if (m == null || m.vessel.entity() != bot.getVehicle()) {
                m = new Member(warfare.vessel(bot.getVehicle()), warfare.vessel(bot.getVehicle()).seatIndex(bot), Long.MAX_VALUE);
                members.put(bot, m);
            }
        } else if (m == null) {
            autoJoin(bot, enemy, now); m = members.get(bot);
            if (m == null) return false;
        }
        Vessel v = m.vessel; Entity entity = v.entity();
        if (entity.isRemoved() || entity.level() != bot.level()) { forget(bot); return false; }
        if (!bot.isPassenger()) {
            if (m.leaving || now > m.expires || v.locked() || v.wreck() || !skills.enabled(bot, "vehicles") && !v.engine().equals("FIXED")) {
                forget(bot); boardingRest.put(bot, now + 100); return false;
            }
            if (bot.canInteractWithEntity(entity.getBoundingBox(), 0) && bot.level().clip(new ClipContext(bot.getEyePosition(), entity.getBoundingBox().getCenter(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.MISS && v.board(bot, m.seat)) {
                m.expires = Long.MAX_VALUE; bot.walk(Vec3.ZERO); bot.setLook(entity.getYRot(), 0);
            } else {
                Vec3 delta = entity.position().subtract(bot.position());
                if (delta.lengthSqr() > 48 * 48) { forget(bot); return false; }
                approachEntry(bot, m, now);
                warfare.release(bot); return true;
            }
        }
        m.seat = v.seatIndex(bot);
        if (m.seat < 0) { forget(bot); return false; }
        Ship ship = ships.computeIfAbsent(entity, e -> new Ship(v));
        updateSupplies(v, ship, now);
        boolean evacuate = v.wreck() || v.health() <= v.maxHealth() * 0.15;
        if (enemy != null && (enemy.getRootVehicle() == entity || bot.isAlliedTo(enemy))) enemy = null;
        boolean requestedLanding = members.values().stream().anyMatch(member -> member.vessel.entity() == entity && member.leaving);
        if (m.leaving || evacuate || requestedLanding && v.engine().equals("HELICOPTER")) {
            warfare.release(bot);
            if (entity.onGround() || !v.engine().equals("HELICOPTER")) {
                if (!m.leaving && !evacuate) return true;
                if (!v.wreck() && now <= m.mountedPendingUntil) return true;
                if (m.seat == 0) v.input(bot, 16, 0, 0);
                bot.stopRiding(); forget(bot); boardingRest.put(bot, now + 200); return true;
            }
            if (v.wreck() && bot.hasUsableElytra()) {
                bot.stopRiding(); forget(bot); bot.startGliding(); boardingRest.put(bot, now + 200); return true;
            }
            // The pilot lands first. Other members stay aboard until a safe dismount is possible.
            if (m.seat == 0) pilot.tick(bot, v, null, now, true, false);
            return true;
        }
        if (now > m.mountedPendingUntil && v.passenger(0) == null && supportsDriver(bot, v) && v.seats().size() > 0 && free(v, 0, bot)) {
            if (v.changeSeat(bot, 0)) m.seat = 0;
        }
        if (m.seat == 0) {
            boolean pending = members.entrySet().stream().anyMatch(e -> e.getKey() != bot && e.getValue().vessel.entity() == entity
                    && !e.getKey().isPassenger() && !e.getValue().leaving && now < e.getValue().expires);
            if (supportsDriver(bot, v)) pilot.tick(bot, v, enemy, now, false, pending);
            else { v.input(bot, v.engine().equals("HELICOPTER") ? 0 : 16, 0, 0); }
        }
        if (enemy == null && (canUseHand(bot) || skills.enabled(bot, "vehicleweapons")) && now % 5 == 0) {
            float before = bot.getYRot();
            bot.setLook(before + m.scanDirection * (3 + skills.hardness(bot).level()), 0); v.clampLook(bot);
            if (Math.abs(Mth.wrapDegrees(bot.getYRot() - before)) < 1) m.scanDirection = -m.scanDirection;
        }
        if (skills.enabled(bot, "recovery") && bot.getHealth() < bot.getMaxHealth() * 0.6 && canUseHand(bot)) bot.beginRecoveryItem(false);
        if (!bot.isConsuming() && skills.enabled(bot, "vehicleweapons") && mounted(bot, m, ship, enemy, observedVelocity, now)) {
            warfare.release(bot); return true;
        }
        if (canUseHand(bot)) warfare.tick(bot, enemy, observedVelocity, now); else warfare.release(bot);
        if (now % 100 == 0) cleanupShips();
        return true;
    }

    private void approachEntry(Bot bot, Member member, long now) {
        Entity e = member.vessel.entity();
        Vec3 entry = member.vessel.entry(bot, member.seat);
        Vec3 side = entry.subtract(e.position()).multiply(1, 0, 1).normalize();
        if (side.lengthSqr() < 0.5) side = e.getLookAngle().multiply(1, 0, 1).normalize().yRot((float) Math.PI / 2);
        double radius = Math.max(3, e.getBbWidth() / 2 + 1.5);
        Vec3 relative = bot.position().subtract(e.position()).multiply(1, 0, 1);
        Vec3 goal = entry;
        // Move beside the tail/body first, then approach the native seat's exit/entry point.
        if (relative.lengthSqr() > 16 && relative.dot(side) < radius - 0.4)
            goal = bot.position().add(side.scale(radius - relative.dot(side)));
        else if (relative.lengthSqr() > 36) goal = e.position().add(side.scale(radius));
        goal = net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds.clamp(bot, goal, 3);
        Vec3 delta = goal.subtract(bot.position()).multiply(1, 0, 1), step = delta.normalize().scale(0.2);
        if (bot.level().noCollision(bot, bot.getBoundingBox().move(step))) {
            member.entryPath = null; bot.faceLocation(goal);
            if (bot.isBotOnGround()) bot.walk(step); return;
        }
        if (now >= member.nextEntryPath) {
            member.nextEntryPath = now + 40; member.entryIndex = 1;
            member.entryPath = skills.pathfinder().find(bot.serverLevel(), bot, BlockPos.containing(goal.x, bot.getY(), goal.z), 32);
        }
        if (member.entryPath != null && member.entryIndex < member.entryPath.size()) {
            Vec3 node = Vec3.atBottomCenterOf(member.entryPath.get(member.entryIndex));
            if (node.subtract(bot.position()).horizontalDistance() < 0.5) member.entryIndex++;
            else {
                Vec3 direction = node.subtract(bot.position()).multiply(1, 0, 1).normalize().scale(0.2);
                bot.faceLocation(node);
                if (bot.isBotOnGround() && bot.level().noCollision(bot, bot.getBoundingBox().move(direction))) {
                    if (node.y > bot.getY() + 0.4) bot.jump(direction.add(0, 0.42, 0)); else bot.walk(direction);
                    return;
                }
            }
        }
        bot.walk(Vec3.ZERO);
    }

    private void autoJoin(Bot bot, @Nullable LivingEntity enemy, long now) {
        if (enemy == null || bot.getTeam() == null || !skills.enabled(bot, "vehicles") || bot.isGliding()
                || now < boardingRest.getOrDefault(bot, 0L) || (now + bot.getId()) % 20 != 0) return;
        for (Entity entity : bot.level().getEntities(bot, bot.getBoundingBox().inflate(10), warfare.access()::isVehicle)) {
            if (entity.getPassengers().isEmpty() || entity.getPassengers().stream().anyMatch(p -> !bot.isAlliedTo(p))) continue;
            Vessel v = warfare.vessel(entity);
            if (v.locked() || v.wreck() || !entity.onGround()
                    || !net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds.contains(entity, entity.position(), 3)) continue;
            if (v.seats().stream().anyMatch(s -> free(v, s.index(), bot) && (s.index() != 0 || supportsDriver(bot, v)))) {
                board(bot, entity, -1); return;
            }
        }
    }
    private void updateSupplies(Vessel v, Ship ship, long now) {
        if (ship.suppliedTick == now) return; ship.suppliedTick = now;
        boolean infiniteEnergy = v.passenger(0) instanceof Bot driver && skills.hardness(driver).level() == 10 && skills.enabled(driver, "vehicles");
        if (infiniteEnergy) {
            if (ship.energyBaseline < 0) ship.energyBaseline = v.energy();
            if (v.energy() < v.maxEnergy()) v.energy(v.maxEnergy());
        } else if (ship.energyBaseline >= 0) {
            v.energy(Math.min(ship.energyBaseline, v.energy())); ship.energyBaseline = -1;
        }
        Set<ItemStack> supplied = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Seat seat : v.seats()) if (v.passenger(seat.index()) instanceof Bot bot && skills.hardness(bot).level() == 10
                && skills.enabled(bot, "vehicleweapons")) {
            for (int i = 0; i < seat.weapons().size(); i++) {
                Gun gun = v.weaponGun(bot, i); if (gun == null) continue;
                supplied.add(gun.stack()); ship.reserve.putIfAbsent(gun.stack(), new Supply(gun, gun.virtualAmmo()));
                if (gun.virtualAmmo() < 64) { gun.virtualAmmo(256); v.changed(); }
            }
        }
        for (var e : List.copyOf(ship.reserve.entrySet())) if (!supplied.contains(e.getKey())) {
            e.getValue().gun().virtualAmmo(Math.min(e.getValue().baseline(), e.getValue().gun().virtualAmmo()));
            ship.reserve.remove(e.getKey()); v.changed();
        }
    }

    private boolean mounted(Bot bot, Member m, Ship ship, @Nullable LivingEntity enemy, Vec3 velocity, long now) {
        Vessel v = m.vessel;
        // SBW resolves the seat's selected weapon when its Post queue executes.
        // Keep the selection and seat stable until the reserved volley has actually fired.
        if (now <= m.mountedPendingUntil) return true;
        if (enemy == null || !skills.react(bot, enemy)) return false;
        Entity target = warfare.access().isVehicle(enemy.getRootVehicle()) ? enemy.getRootVehicle() : enemy;
        if (target.getPassengers().stream().anyMatch(bot::isAlliedTo)) return false;
        List<MountedWeapon> weapons = v.weapons(bot); if (weapons.isEmpty()) return false;
        double distance = v.muzzle(bot).distanceTo(target.getBoundingBox().getCenter());
        MountedWeapon best = null; double score = -Double.MAX_VALUE;
        for (MountedWeapon w : weapons) {
            Specs spec = w.specs();
            if ((!w.ready() && w.reserve() <= 0) || spec.projectiles() < 1 || spec.projectiles() > 64
                    || spec.shootDelay() > 200 || distance > Math.min(256, spec.range()) || spec.explosionRadius() > 0 && distance < spec.explosionRadius() + 5) continue;
            if (spec.seekTime() > 0 && !warfare.access().isVehicle(target) && !target.isPassenger()) continue;
            double value = w.ready() ? 8 : 0;
            if (warfare.access().isVehicle(target)) {
                double effective = warfare.access().vehicleDamage(target, bot, v.weaponGun(bot, w.index()));
                if (effective <= 0) continue;
                value += Math.log1p(effective) * 5;
            } else value += (spec.rpm() >= 300 ? 10 : 0) + Math.min(4, spec.damage() * 0.02) - spec.explosionRadius()
                    + (spec.explosionRadius() == 0 && (spec.magazine() == 0 || spec.magazine() > 5) ? 14 : 0);
            if (w.index() == v.selectedWeapon(bot)) value += 0.5;
            if (value > score) { best = w; score = value; }
        }
        if (best == null) { m.weaponState = "no usable weapon"; return false; }
        if (v.selectedWeapon(bot) != best.index()) { v.selectWeapon(bot, best.index()); m.aimSince = now; m.lockSince = -1; }
        if (m.target != target.getId()) { m.target = target.getId(); m.aimSince = now; m.lockSince = -1; }
        m.weaponState = best.name();
        Specs spec = best.specs();
        int difficulty = skills.hardness(bot).level(); Vec3 origin = v.muzzle(bot);
        Vec3 aim = target.getBoundingBox().getCenter();
        if (target instanceof LivingEntity living && spec.explosionRadius() == 0) aim = living.getEyePosition().add(0, -0.12, 0);
        if (difficulty >= 7 && spec.velocity() > 0) {
            double time = Math.min(25, origin.distanceTo(aim) / spec.velocity());
            aim = aim.add(velocity.scale(time)).add(0, spec.gravity() * time * time / 2, 0);
        }
        if (now >= m.nextError) {
            m.errorYaw = bot.getRandom().nextGaussian() * WarfareSupport.aimErrorDegrees(difficulty);
            m.errorPitch = bot.getRandom().nextGaussian() * WarfareSupport.aimErrorDegrees(difficulty) * 0.7;
            m.nextError = now + 12;
        }
        Vec3 desired = aim.subtract(origin);
        float yaw = (float) Math.toDegrees(Math.atan2(-desired.x, desired.z)) + (float) m.errorYaw;
        float pitch = (float) -Math.toDegrees(Math.atan2(desired.y, desired.horizontalDistance())) + (float) m.errorPitch;
        float turn = 3 + difficulty * 1.3f;
        bot.setLook(bot.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - bot.getYRot()), -turn, turn),
                bot.getXRot() + Mth.clamp(pitch - bot.getXRot(), -turn, turn));
        v.clampLook(bot);
        Vec3 fire = v.direction(bot).normalize();
        double angle = Math.toDegrees(Math.acos(Mth.clamp(fire.dot(aim.subtract(origin).normalize()), -1, 1)));
        if (!best.ready() || angle > Math.max(3, WarfareSupport.aimErrorDegrees(difficulty) * 3)
                || now - m.aimSince < 4 + (10 - difficulty) * 3) {
            m.weaponState += !best.ready() ? " reload/ammo" : " aim " + Math.round(angle) + "deg";
            m.lockSince = -1; return true;
        }
        if (spec.seekTime() > 0) {
            double height = target.getY() - bot.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    target.blockPosition().getX(), target.blockPosition().getZ());
            if (distance > spec.seekRange() || height < spec.minTargetHeight() || height > spec.maxTargetHeight() || angle > spec.seekAngle()) { m.lockSince = -1; return true; }
            if (m.lockSince < 0) m.lockSince = now;
            if (now - m.lockSince < spec.seekTime()) return true;
        }
        if (now < ship.nextShot.getOrDefault(best.name(), 0.0)) return true;
        if (!warfare.safeShot(bot, target, spec, origin, fire)) { m.weaponState += " line/blast safety"; return true; }
        int delay = Math.max(1, spec.shootDelay());
        // AI runs in ServerTick.Pre; SBW's Post queue decrements the delay in this same tick.
        if (!warfare.permit(bot, spec.projectiles(), now, Math.max(0, spec.shootDelay() - 1))) return true;
        v.shoot(bot, spec.seekTime() > 0 ? target : null);
        m.mountedPendingUntil = now + Math.max(0, spec.shootDelay() - 1);
        ship.nextShot.put(best.name(), now + delay + Math.max(1, 1200 / Math.max(1, spec.rpm())));
        m.shots++; return true;
    }

    public void forget(Bot bot) {
        Member member = members.remove(bot); boardingRest.remove(bot);
        if (member != null) {
            try {
                if (member.vessel.passenger(0) == bot) member.vessel.input(bot, member.vessel.entity().onGround() ? 16 : 0, 0, 0);
            } catch (RuntimeException | LinkageError ignored) { }
        }
        cleanupShips();
    }
    private void cleanupShips() {
        for (var entry : List.copyOf(ships.entrySet())) {
            Entity e = entry.getKey();
            if (!e.isRemoved() && e.getPassengers().stream().anyMatch(p -> p instanceof Bot && p.isAlive())) continue;
            Ship ship = ships.remove(e); pilot.forget(e);
            try {
                if (warfare.access() != null && !e.isRemoved()) {
                    Vessel v = warfare.vessel(e);
                    if (ship.energyBaseline >= 0) v.energy(Math.min(ship.energyBaseline, v.energy()));
                    for (Supply s : ship.reserve.values()) s.gun().virtualAmmo(Math.min(s.baseline(), s.gun().virtualAmmo()));
                    v.changed();
                }
            } catch (RuntimeException | LinkageError ignored) { }
        }
    }
    public void clear() {
        for (Ship ship : List.copyOf(ships.values())) {
            try {
                if (!ship.vessel.entity().isRemoved()) {
                    if (ship.energyBaseline >= 0) ship.vessel.energy(Math.min(ship.energyBaseline, ship.vessel.energy()));
                    for (Supply supply : ship.reserve.values()) supply.gun().virtualAmmo(Math.min(supply.baseline(), supply.gun().virtualAmmo()));
                    ship.vessel.changed();
                }
            } catch (RuntimeException | LinkageError ignored) { }
        }
        for (Bot bot : List.copyOf(members.keySet())) forget(bot);
        members.clear(); boardingRest.clear(); pilot.clear(); ships.clear();
    }
}
