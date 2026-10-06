package net.nuggetmc.tplus.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.BotSkills;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.WarfareAccess.Gun;
import net.nuggetmc.tplus.compat.WarfareAccess.Specs;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.*;

/** Server-thread firearm controls. Selection, aim error and cadence never modify gun stats. */
public final class WarfareSupport {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PROJECTILE_BUDGET = 64;
    private final BotSkills skills;
    private final VehicleCrew crew;
    private final Map<Bot, State> states = new HashMap<>();
    private final LinkedHashMap<Bot, Long> waiting = new LinkedHashMap<>();
    @Nullable private WarfareAccess access;
    private String status = "not installed";
    private long budgetTick = -1;
    private int budget;
    private final Map<Long, Integer> reservedProjectiles = new HashMap<>();

    private record ReloadWindow(long ends, int ticks, boolean empty) {}
    private static final class State {
        Gun held;
        int source = -1, target = -1, burst;
        ItemStack display = ItemStack.EMPTY;
        long lastTick = -1, aimSince, lockSince = -1, selectedAt, nextSelect, blockedSince = -1, errorUntil, shots, lastShot = -1;
        double nextShot, errorYaw, errorPitch, recoil;
        boolean zoom;
        String antiRole = "";
        long interruptedUntil, nextStrafe, nextJump, nextCover, hideUntil;
        int strafe = 1;
        Vec3 cover;
        final Map<ItemStack, ReloadWindow> reloads = new IdentityHashMap<>();
        final Map<ItemStack, Long> bolts = new IdentityHashMap<>();
        final Map<ItemStack, Integer> supplied = new IdentityHashMap<>();
    }

    public WarfareSupport(BotSkills skills) {
        this.skills = skills;
        this.crew = new VehicleCrew(skills, this);
        var mod = ModList.get().getModContainerById("superbwarfare");
        if (mod.isEmpty()) return;
        String version = mod.get().getModInfo().getVersion().toString();
        if (!version.equals("0.8.9.1")) {
            status = "disabled: unsupported SBW " + version;
            LOGGER.warn("TerminatorPlus warfare support {}", status);
            return;
        }
        try {
            access = (WarfareAccess) Class.forName("net.nuggetmc.tplus.compat.superbwarfare.SuperbWarfareAccess")
                    .getConstructor().newInstance();
            status = "SBW 0.8.9.1 ready";
            LOGGER.info("TerminatorPlus {}", status);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { disable(e); }
    }
    public boolean available() { return access != null; }
    public boolean isVehicle(Entity entity) { return access != null && access.isVehicle(entity); }
    public String status() { return status; }
    public VehicleCrew crew() { return crew; }
    public WarfareAccess.Vessel vessel(Entity entity) {
        if (access == null || !access.isVehicle(entity)) throw new IllegalArgumentException("卓越前线兼容尚未启用，或实体不是载具。");
        return access.vessel(entity);
    }
    public WarfareAccess access() { return access; }
    public boolean tickCrew(Bot bot, @Nullable LivingEntity enemy, Vec3 velocity, long now) {
        if (access == null) return false;
        try { return crew.tick(bot, enemy, velocity, now); }
        catch (RuntimeException | LinkageError error) { disable(error); return false; }
    }
    public boolean carriesGun(Bot bot) {
        if (access == null) return false;
        for (int i = 1; i < bot.getInventory().items.size(); i++) if (access.isGun(bot.getInventory().items.get(i))) return true;
        State state = states.get(bot);
        return state != null && state.held != null;
    }
    public String describe(Bot bot) {
        State s = states.get(bot);
        return status + (s == null ? "" : "; shots=" + s.shots + "; coolingGuns=" + s.reloads.size() + (s.held == null ? "; hand idle" :
                "; " + BuiltInRegistries.ITEM.getKey(s.held.stack().getItem()) + "; ammo=" + s.held.ammo()
                        + "; reloadTicks=" + reloadRemaining(bot, s.held.stack()) + "; zoom=" + s.zoom + (s.antiRole.isEmpty() ? "" : "; antiTank=" + s.antiRole))) + crew.describe(bot) + skills.ordnance().describe(bot);
    }
    public long shots(Bot bot) { State s = states.get(bot); return s == null ? 0 : s.shots; }
    public long lastShot(Bot bot) { State s = states.get(bot); return s == null ? -1 : s.lastShot; }
    public boolean holding(Bot bot) { State s = states.get(bot); return s != null && s.held != null; }
    /** Independent cooldowns never reserve the hand or interrupt another skill. */
    public boolean reloadCommitted(Bot bot) { return false; }
    public long reloadRemaining(Bot bot, ItemStack stack) {
        State s = states.get(bot); ReloadWindow window = s == null ? null : s.reloads.get(stack);
        return window == null ? 0 : Math.max(0, window.ends() - bot.server.getTickCount());
    }
    public long longestReload(Bot bot) {
        State s = states.get(bot); return s == null ? 0 : s.reloads.values().stream().mapToLong(w -> Math.max(0, w.ends() - bot.server.getTickCount())).max().orElse(0);
    }
    private boolean cooling(State state, Gun gun) {
        return state.reloads.containsKey(gun.stack()) || state.bolts.containsKey(gun.stack());
    }
    public boolean readyToEngage(Bot bot, @Nullable LivingEntity enemy) {
        if (access == null || enemy == null || !skills.enabled(bot, "guns")) return false;
        State s = states.get(bot); if (s == null) return false;
        double distance = bot.getEyePosition().distanceTo(enemy.getRootVehicle().getBoundingBox().getCenter());
        for (Gun gun : ownedGuns(bot, s)) {
            Specs spec = gun.specs();
            double range = switch (spec.type()) { case "SHOTGUN" -> 16; case "SMG" -> 40; case "PISTOL" -> 32; default -> 160; };
            if (!spec.special() && !cooling(s, gun) && gun.ammo() >= spec.cost() && distance <= Math.min(range, spec.range())) return true;
        }
        return false;
    }
    private List<Gun> ownedGuns(Bot bot, State state) {
        List<Gun> guns = new ArrayList<>(); if (state.held != null) guns.add(state.held);
        for (int i = 1; i < bot.getInventory().items.size(); i++) {
            ItemStack stack = bot.getInventory().items.get(i); if (access.isGun(stack)) guns.add(access.gun(stack));
        }
        return guns;
    }
    private static double usefulRange(Specs spec) {
        return Math.min(spec.range(), switch (spec.type()) { case "SHOTGUN" -> 16; case "PISTOL" -> 32; case "SMG" -> 40; default -> 160; });
    }
    public boolean canEngage(Bot bot, LivingEntity enemy) {
        State s = states.get(bot); if (access == null || s == null) return false;
        double distance = bot.getEyePosition().distanceTo(enemy.getRootVehicle().getBoundingBox().getCenter());
        for (Gun gun : ownedGuns(bot, s)) {
            Specs spec = gun.specs();
            if (!spec.special() && (gun.ammo() >= spec.cost() || gun.reserve(bot) > 0 || s.reloads.containsKey(gun.stack()))
                    && distance <= usefulRange(spec)) return true;
        }
        return false;
    }
    public boolean canThreaten(WarfareAccess.Vessel own, LivingEntity enemy) {
        if (access == null) return true;
        if (access.isVehicle(enemy.getRootVehicle())) {
            var hostile = access.vessel(enemy.getRootVehicle());
            return !hostile.wreck() && hostile.weapons(enemy).stream().anyMatch(w -> (w.ammo() >= w.specs().cost() || w.reserve() > 0)
                    && access.vehicleDamage(own.entity(), enemy, hostile.weaponGun(enemy, w.index())) > 0);
        }
        if (enemy instanceof net.minecraft.world.entity.player.Player player) {
            for (ItemStack stack : player.getInventory().items) if (access.isGun(stack)) {
                Gun gun = access.gun(stack);
                if ((gun.ammo() >= gun.specs().cost() || gun.reserve(enemy) > 0) && access.vehicleDamage(own.entity(), enemy, gun) > 0) return true;
            }
        } else if (access.isGun(enemy.getMainHandItem()) && access.vehicleDamage(own.entity(), enemy, access.gun(enemy.getMainHandItem())) > 0) return true;
        return access.meleeDamage(own.entity(), enemy) > 0;
    }
    public boolean antiAirThreat(WarfareAccess.Vessel own, LivingEntity enemy) {
        if (access == null) return false;
        if (access.isVehicle(enemy.getRootVehicle())) {
            var v = access.vessel(enemy.getRootVehicle());
            return !v.wreck() && v.weapons(enemy).stream().anyMatch(w -> (w.ready() || w.reserve() > 0)
                    && access.vehicleDamage(own.entity(), enemy, v.weaponGun(enemy, w.index())) > 0);
        }
        ItemStack stack = enemy.getMainHandItem();
        if (!access.isGun(stack)) return false;
        Gun gun = access.gun(stack); Specs spec = gun.specs();
        return (spec.seekTime() > 0 || spec.type().endsWith("LAUNCHER")) && (gun.ammo() >= spec.cost() || gun.reserve(enemy) > 0)
                && access.vehicleDamage(own.entity(), enemy, gun) > 0;
    }
    public boolean isGun(ItemStack stack) { return access != null && access.isGun(stack); }
    public int ammo(ItemStack stack) { return access != null && access.isGun(stack) ? access.gun(stack).ammo() : -1; }
    public boolean reloading(ItemStack stack) {
        return access != null && access.isGun(stack) && (states.values().stream().anyMatch(s -> s.reloads.containsKey(stack)) || access.gun(stack).reloading());
    }

    /** Bots skip Player.aiStep, so tick real guns exactly once, including the temporarily held stack. */
    public void tickInventory(Bot bot, long now) {
        if (access == null) return;
        try {
            State state = states.computeIfAbsent(bot, b -> new State());
            if (state.lastTick == now) return;
            state.lastTick = now;
            reconcile(bot, state);
            // Revoke level-ten gifts before a due reload can debit them after a downgrade/disable.
            if (skills.hardness(bot).level() != 10 || !skills.enabled(bot, "guns")) {
                for (var e : state.supplied.entrySet()) { Gun gun = access.gun(e.getKey()); gun.virtualAmmo(Math.min(e.getValue(), gun.virtualAmmo())); }
                state.supplied.clear();
            }
            Set<ItemStack> present = Collections.newSetFromMap(new IdentityHashMap<>());
            for (int i = 1; i < bot.getInventory().items.size(); i++) {
                ItemStack stack = bot.getInventory().items.get(i);
                if (access.isGun(stack)) tickGun(bot, state, access.gun(stack), false, present);
            }
            if (state.held != null) tickGun(bot, state, state.held, true, present);
            state.reloads.keySet().removeIf(stack -> !present.contains(stack));
            state.bolts.keySet().removeIf(stack -> !present.contains(stack));
            for (var entry : List.copyOf(state.supplied.entrySet())) {
                if (!present.contains(entry.getKey()) || skills.hardness(bot).level() != 10 || !skills.enabled(bot, "guns")) {
                    Gun gun = access.gun(entry.getKey());
                    gun.virtualAmmo(Math.min(entry.getValue(), gun.virtualAmmo()));
                    state.supplied.remove(entry.getKey());
                }
            }
        } catch (RuntimeException | LinkageError e) { disable(e); }
    }
    private void tickGun(Bot bot, State state, Gun gun, boolean held, Set<ItemStack> present) {
        present.add(gun.stack());
        if (skills.enabled(bot, "guns") && !state.reloads.containsKey(gun.stack())) gun.selectAvailableAmmo(bot);
        if (skills.hardness(bot).level() == 10 && skills.enabled(bot, "guns")) {
            state.supplied.putIfAbsent(gun.stack(), gun.virtualAmmo());
            // Level ten uses native virtual reserve; cooldown completion still uses native ammo debit.
            if (gun.virtualAmmo() < 256) gun.virtualAmmo(256);
        }
        // Keep native heat/perks/animation ticking; held-only native reload/bolt must not compete with AI clocks.
        gun.tick(bot, false);
        long now = state.lastTick;
        ReloadWindow reload = state.reloads.get(gun.stack());
        if (reload != null && now >= reload.ends()) {
            gun.finishCooldown(bot, reload.empty()); state.reloads.remove(gun.stack()); state.bolts.remove(gun.stack());
            skills.afterGunReload(bot, reload.ticks());
        }
        if (gun.boltPending() && !state.bolts.containsKey(gun.stack()) && !state.reloads.containsKey(gun.stack()) && gun.specs().boltTime() > 0)
            state.bolts.put(gun.stack(), now + gun.specs().boltTime());
        Long bolt = state.bolts.get(gun.stack());
        if (bolt != null && now >= bolt) { gun.finishBolt(); state.bolts.remove(gun.stack()); }
        if (skills.enabled(bot, "guns") && !gun.specs().special()
                && (gun.ammo() < gun.specs().cost() || gun.reloading())) startReload(bot, state, gun, now);
    }
    private void startReload(Bot bot, State state, Gun gun, long now) {
        if (state.reloads.containsKey(gun.stack()) || gun.specs().special() || gun.specs().magazine() <= 0 || gun.reserve(bot) <= 0) return;
        boolean empty = gun.ammo() == 0;
        int ticks = gun.reloadTicks(empty);
        gun.beginCooldown(bot, empty);
        state.reloads.put(gun.stack(), new ReloadWindow(now + ticks, ticks, empty));
    }

    /** Restore by reference; inventory commands may have replaced either slot during an action. */
    public void release(Bot bot) {
        State s = states.get(bot);
        waiting.remove(bot);
        if (s == null || s.held == null) return;
        Gun held = s.held;
        ItemStack stack = held.stack();
        var items = bot.getInventory().items;
        if (items.get(0) == stack) items.set(0, s.display);
        boolean alreadyStored = items.stream().anyMatch(item -> item == stack);
        if (!alreadyStored && !stack.isEmpty()) {
            int slot = items.get(s.source).isEmpty() ? s.source : -1;
            if (slot < 0) for (int i = 1; i < items.size(); i++) if (items.get(i).isEmpty()) { slot = i; break; }
            if (slot >= 0) items.set(slot, stack);
            else bot.drop(stack, false);
        }
        s.held = null; s.source = -1; s.cover = null; s.zoom = false; s.blockedSince = -1;
        try { held.zoom(false); } catch (RuntimeException | LinkageError e) { if (access != null) disable(e); }
    }
    private void reconcile(Bot bot, State s) {
        if (s.held != null && (s.held.stack().isEmpty() || bot.getMainHandItem() != s.held.stack() || !skills.enabled(bot, "guns"))) release(bot);
    }
    public void forget(Terminator bot) {
        if (!(bot instanceof Bot b)) return;
        crew.forget(b);
        release(b);
        State state = states.remove(b);
        if (access != null && state != null) for (var e : state.supplied.entrySet()) {
            try { Gun gun = access.gun(e.getKey()); gun.virtualAmmo(Math.min(e.getValue(), gun.virtualAmmo())); }
            catch (RuntimeException | LinkageError failure) { disable(failure); break; }
        }
    }
    public void clear() { crew.clear(); for (Bot bot : List.copyOf(states.keySet())) forget(bot); waiting.clear(); reservedProjectiles.clear(); }
    void disable(Throwable error) {
        access = null;
        status = "disabled: SBW API failure";
        LOGGER.error("TerminatorPlus warfare compatibility disabled; vanilla bot AI remains available", error);
        for (Bot bot : List.copyOf(states.keySet())) release(bot);
        crew.clear();
        waiting.clear();
    }

    public boolean tick(Terminator terminator, @Nullable LivingEntity enemy, Vec3 observedVelocity, long now) {
        if (!(terminator instanceof Bot bot) || access == null) return false;
        try { return control(bot, enemy, observedVelocity, now, true); }
        catch (RuntimeException | LinkageError e) { disable(e); return false; }
    }
    public boolean prepare(Bot bot, @Nullable LivingEntity enemy, Vec3 velocity, long now) {
        if (access == null) return false;
        try { return control(bot, enemy, velocity, now, false); }
        catch (RuntimeException | LinkageError error) { disable(error); return false; }
    }
    /** An actual hand action takes priority until the following AI tick. */
    public void interrupt(Bot bot) {
        boolean ownedHand = holding(bot);
        release(bot);
        if (access != null && ownedHand) states.computeIfAbsent(bot, b -> new State()).interruptedUntil = bot.server.getTickCount();
    }
    private boolean control(Bot bot, @Nullable LivingEntity enemy, Vec3 observedVelocity, long now, boolean fire) {
        State s = states.computeIfAbsent(bot, b -> new State());
        int difficulty = skills.hardness(bot).level();
        if (now <= s.interruptedUntil || !skills.enabled(bot, "guns") || bot.isGliding() || bot.isConsuming() || bot.isUsingItem()
                || bot.isPassenger() && !crew.canUseHand(bot)) {
            release(bot); return false;
        }
        if (enemy == null || !enemy.isAlive() || bot.isAlliedTo(enemy) || !skills.react(bot, enemy)) return idleLoad(bot, s, now);

        Entity target = access.isVehicle(enemy.getRootVehicle()) ? enemy.getRootVehicle() : enemy;
        if (target.getPassengers().stream().anyMatch(bot::isAlliedTo)) { release(bot); return false; }
        Vec3 center = target.getBoundingBox().getCenter();
        double distance = bot.getEyePosition().distanceTo(center);
        boolean visible = line(bot, center);
        if (distance > 160) return idleLoad(bot, s, now);
        if (difficulty >= 8 && !access.isVehicle(target) && distance < 3 && !bot.getWeapon().isEmpty()
                && bot.getWeapon().getItem() instanceof net.minecraft.world.item.TieredItem) { release(bot); return false; }
        if (s.target != target.getId()) {
            s.antiRole = "";
            s.target = target.getId(); s.aimSince = now; s.lockSince = -1; s.nextSelect = 0; s.burst = 0; s.cover = null;
        }
        if (s.held == null || cooling(s, s.held) || now >= s.nextSelect && now - s.selectedAt >= 40) {
            Gun chosen = choose(bot, s, target, distance, difficulty);
            s.nextSelect = now + 10;
            if (chosen == null) { release(bot); return false; }
            if (s.held == null || s.held.stack() != chosen.stack()) {
                if (!hold(bot, s, chosen, now)) return false;
                s.nextShot = Math.max(s.nextShot, now + 4 + (10 - difficulty) * 2);
            }
        }
        Gun gun = s.held;
        Specs spec = gun.specs();
        if (difficulty >= 7 && !access.isVehicle(target) && spec.magazine() > 5 && gun.ammo() > 0
                && gun.ammo() <= Math.max(spec.cost(), spec.magazine() / 8) && distance > 12 && gun.reserve(bot) > 0) {
            Vec3 cover = findCover(bot, center);
            if (cover != null) { s.cover = cover; startReload(bot, s, gun, now); return true; }
        }
        if (gun.ammo() < spec.cost() && gun.reserve(bot) > 0) {
            if (difficulty >= 7 && access.isVehicle(target) && visible && spec.type().endsWith("LAUNCHER")) {
                if (s.cover == null) s.cover = findCover(bot, center);
                if (s.cover != null && bot.position().distanceToSqr(s.cover) > 1) return true;
            }
            startReload(bot, s, gun, now); return true;
        }
        if (cooling(s, gun) || !fire || !visible || distance > spec.range()) return true;
        if (difficulty >= 7 && access.isVehicle(target) && spec.type().endsWith("LAUNCHER")
                && now - s.aimSince < (s.antiRole.equals("FLANK") ? 240 : 120)
                && frontal(bot, target) && !access.vessel(target).engine().equals("HELICOPTER")) return true;
        boolean zoom = difficulty >= 4 && (distance > 12 || spec.seekTime() > 0);
        gun.zoom(zoom); s.zoom = zoom;
        boolean automatic = distance < 16 || difficulty <= 3;
        gun.fireMode(automatic, access.isVehicle(target) && spec.seekTime() > 0 && !spec.type().equals("CURVED_LAUNCHER"));
        // Fire-mode overrides can change the projectile and ballistic properties.
        spec = gun.specs();
        Vec3 aim = access.isVehicle(target) ? center : target.position().add(0,
                difficulty >= 7 ? target.getEyeHeight() - 0.08 : target.getBbHeight() * 0.58, 0);
        if (difficulty >= 7 && spec.velocity() > 0) {
            double time = Math.min(30, distance / spec.velocity());
            aim = aim.add(observedVelocity.scale(time)).add(0, spec.gravity() * time * time / 2, 0);
        }
        if (now >= s.errorUntil) {
            double error = aimErrorDegrees(difficulty);
            s.errorYaw = bot.getRandom().nextGaussian() * error;
            s.errorPitch = bot.getRandom().nextGaussian() * error * 0.7;
            s.errorUntil = now + 8 + (10 - difficulty) * 2;
        }
        s.recoil *= 0.65 + (10 - difficulty) * 0.025;
        Vec3 direction = aim.subtract(bot.getEyePosition());
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z)) + (float) s.errorYaw;
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(direction.y, direction.horizontalDistance()))
                + (float) s.errorPitch - (float) s.recoil;
        float turn = 3 + difficulty * 1.3F;
        bot.setLook(bot.getYRot() + Mth.clamp(Mth.wrapDegrees(desiredYaw - bot.getYRot()), -turn, turn),
                bot.getXRot() + Mth.clamp(desiredPitch - bot.getXRot(), -turn, turn));
        if (bot.isPassenger()) access.vessel(bot.getVehicle()).clampLook(bot);
        double angle = Math.toDegrees(Math.acos(Mth.clamp(bot.getLookAngle().dot(direction.normalize()), -1, 1)));
        // Aim error must not continually restart a novice's preparation time. Missile lock is separate and continuous.
        if (angle > Math.max(3, aimErrorDegrees(difficulty) * 3)) { s.lockSince = -1; return true; }
        if (spec.seekTime() > 0) {
            double height = target.getY() - bot.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    target.blockPosition().getX(), target.blockPosition().getZ());
            if (distance > spec.seekRange() || height < spec.minTargetHeight()
                    || height > spec.maxTargetHeight()) { release(bot); return false; }
            if (angle > spec.seekAngle()) { s.lockSince = -1; return true; }
            if (s.lockSince < 0) s.lockSince = now;
            if (now - s.lockSince < spec.seekTime()) return true;
        }
        if (now - s.aimSince < 4 + (10 - difficulty) * 3 || now < s.nextShot || !gun.canShoot(bot)) return true;
        if (!safeShot(bot, target, spec)) {
            waiting.remove(bot);
            if (s.blockedSince < 0) s.blockedSince = now;
            if (now - s.blockedSince > 20) { release(bot); return false; }
            s.nextStrafe = 0;
            return true;
        }
        s.blockedSince = -1;
        if (!permit(bot, Math.max(1, spec.projectiles()), now)) return true;
        double basic = spec.spread();
        double stance = bot.isBotOnGround() ? -0.25 * basic : 0.35 * basic;
        if (bot.getVelocity().horizontalDistance() > 0.03) stance += 0.3 * basic;
        double spread = (spec.projectiles() > 1 ? 1.2 * (basic + 0.2 * stance) : 0.7 * basic + stance);
        if (zoom) spread *= spec.zoomSpread();
        gun.shoot(bot, Math.max(0, spread), zoom, spec.seekTime() > 0 ? target : null);
        if (spec.boltTime() > 0) s.bolts.put(gun.stack(), now + spec.boltTime());
        s.shots++; s.lastShot = now; s.burst++;
        if (difficulty >= 7 && access.isVehicle(target)) s.hideUntil = now + 35 + (difficulty - 7) * 10;
        s.recoil += spec.recoilY() * 100 * (10 - difficulty + 1) / 10;
        double interval = Math.max(1, 1200 / Math.max(1, spec.rpm()));
        s.nextShot = Math.max(now, s.nextShot) + interval + (automatic ? 0 : (10 - difficulty) * 0.6);
        if (!automatic && s.burst >= (spec.boltTime() > 0 ? 1 : 3 + difficulty / 5)) {
            s.nextShot = Math.max(s.nextShot, now + 6 + (10 - difficulty) * 2); s.burst = 0;
        }
        return true;
    }

    public static double aimErrorDegrees(int difficulty) {
        return switch (difficulty) { case 1 -> 12; case 2 -> 9; case 3 -> 7; case 4 -> 5; case 5 -> 3.8;
            case 6 -> 2.8; case 7 -> 1.8; case 8 -> 1.2; case 9 -> 0.8; default -> 0.5; };
    }
    private Gun choose(Bot bot, State state, Entity target, double distance, int difficulty) {
        List<Gun> guns = new ArrayList<>();
        if (state.held != null) guns.add(state.held);
        for (int i = 1; i < bot.getInventory().items.size(); i++) {
            ItemStack stack = bot.getInventory().items.get(i);
            if (access.isGun(stack)) guns.add(access.gun(stack));
        }
        Gun best = null; double score = -Double.MAX_VALUE;
        for (Gun gun : guns) {
            Specs s = gun.specs();
            if (s.special() || s.projectiles() < 1 || s.projectiles() > PROJECTILE_BUDGET || distance > Math.min(160, s.range())
                    || gun.ammo() < s.cost() && gun.reserve(bot) <= 0) continue;
            double value = cooling(state, gun) ? -40 : gun.ammo() >= s.cost() ? 2 : -3;
            // A loaded shotgun is not a useful replacement for a briefly cooling rifle sixty blocks away.
            if (distance > usefulRange(s)) value -= 60;
            boolean launcher = s.type().endsWith("LAUNCHER");
            if (launcher && distance < Math.max(8, s.explosionRadius() * 2 + 3)) continue;
            if (difficulty <= 3) value += gun == state.held ? 20 : 0;
            else {
                value += distance < 8 ? (s.type().equals("SHOTGUN") ? 12 : s.type().equals("SMG") ? 10 : 2)
                        : distance > 40 ? (s.type().equals("SNIPER") ? 12 : s.type().equals("RIFLE") ? 5 : 1)
                        : s.type().equals("RIFLE") || s.type().equals("MACHINE_GUN") ? 10 : 3;
                if (!access.isVehicle(target) && launcher) value -= 12;
                if (target instanceof LivingEntity living && living.getArmorValue() >= 15) value += s.armorPiercing() * 5;
            }
            if (access.isVehicle(target) && difficulty >= 4) {
                double effective = access.vehicleDamage(target, bot, gun);
                if (effective <= 0) continue;
                value += Math.min(30, Math.log1p(effective) * 5);
            }
            if (state.held != null && state.held.stack() == gun.stack()) value += 3.5;
            if (value > score) { score = value; best = gun; }
        }
        return best;
    }
    private boolean permit(Bot bot, int count, long now) {
        return permit(bot, count, now, 0);
    }
    boolean permit(Bot bot, int count, long now, int delay) {
        if (now != budgetTick) {
            budgetTick = now; reservedProjectiles.keySet().removeIf(t -> t < now);
            waiting.entrySet().removeIf(e -> now - e.getValue() > 40 || !e.getKey().isAlive());
        }
        long firingAt = now + delay;
        budget = PROJECTILE_BUDGET - reservedProjectiles.getOrDefault(firingAt, 0);
        waiting.putIfAbsent(bot, now);
        if (budget < count || waiting.keySet().iterator().next() != bot) return false;
        waiting.remove(bot); reservedProjectiles.merge(firingAt, count, Integer::sum);
        return true;
    }
    private boolean line(Bot bot, Vec3 point) {
        return bot.level().hasChunkAt(BlockPos.containing(point)) && bot.level().clip(new ClipContext(bot.getEyePosition(),
                point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.MISS;
    }
    private boolean safeShot(Bot bot, Entity target, Specs spec) {
        return safeShot(bot, target, spec, bot.getEyePosition(), bot.getLookAngle(), false);
    }
    boolean safeShot(Bot bot, Entity target, Specs spec, Vec3 start, Vec3 direction) {
        return safeShot(bot, target, spec, start, direction, true);
    }
    private boolean safeShot(Bot bot, Entity target, Specs spec, Vec3 start, Vec3 direction, boolean mounted) {
        Vec3 end = start.add(direction.scale(start.distanceTo(target.getBoundingBox().getCenter())));
        // Misses into terrain remain real misses; requiring a clear erroneous aim ray filters out low-skill shots.
        if (!bot.level().hasChunkAt(target.blockPosition()) || bot.level().clip(new ClipContext(start, target.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() != HitResult.Type.MISS) return false;
        // SBW CustomExplosion applies entity damage out to twice its configured radius.
        double radius = spec.explosionRadius() * 2;
        if (radius > 0 && start.distanceTo(target.getBoundingBox().getCenter()) <= radius + 3) return false;
        // Preserve inaccurate distant shots, but do not fire explosives into immediate cover or our feet.
        if (radius > 0) {
            var impact = bot.level().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
            if (impact.getType() != HitResult.Type.MISS && start.distanceTo(impact.getLocation()) <= radius + 3) return false;
        }
        for (Entity other : bot.level().getEntities(bot, bot.getBoundingBox().minmax(target.getBoundingBox()).inflate(radius + 2))) {
            if (other == target || other == bot.getVehicle() || mounted && other.getRootVehicle() == bot.getRootVehicle() || !bot.isAlliedTo(other)) continue;
            if (other.getBoundingBox().inflate(0.5).clip(start, end).isPresent()) return false;
            if (radius > 0 && other.getBoundingBox().getCenter().distanceTo(target.getBoundingBox().getCenter()) < radius + 2) return false;
        }
        return true;
    }
    @Nullable private Vec3 findCover(Bot bot, Vec3 enemy) {
        for (int radius : new int[]{0, 2, 4, 6, 8, 12}) for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            Vec3 point = bot.position().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            if (!safeStep(bot, point) || bot.level().clip(new ClipContext(bot.getEyePosition(), point.add(0, bot.getEyeHeight(), 0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() != HitResult.Type.MISS) continue;
            if (bot.level().clip(new ClipContext(point.add(0, bot.getEyeHeight(), 0), enemy, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, bot)).getType() != HitResult.Type.MISS) return point;
        }
        return null;
    }
    private static boolean frontal(Bot bot, Entity vehicle) {
        return vehicle.getLookAngle().multiply(1, 0, 1).normalize().dot(bot.position().subtract(vehicle.position()).multiply(1, 0, 1).normalize()) > 0.35;
    }
    private boolean lineFrom(Bot bot, Vec3 start, Vec3 end) {
        return bot.level().hasChunkAt(BlockPos.containing(end)) && bot.level().clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.MISS;
    }
    private boolean hold(Bot bot, State s, Gun chosen, long now) {
        if (s.held != null && s.held.stack() == chosen.stack()) return true;
        release(bot);
        var items = bot.getInventory().items; int slot = -1;
        for (int i = 1; i < items.size(); i++) if (items.get(i) == chosen.stack()) { slot = i; break; }
        if (slot < 0) return false;
        s.display = items.get(0); s.source = slot; s.held = chosen;
        items.set(0, chosen.stack()); items.set(slot, ItemStack.EMPTY);
        s.aimSince = now; s.lockSince = -1; s.selectedAt = now; s.burst = 0; s.recoil = 0;
        return true;
    }
    private boolean idleLoad(Bot bot, State s, long now) {
        for (Gun gun : ownedGuns(bot, s)) {
            Specs spec = gun.specs();
            if (!spec.special() && spec.magazine() > 0 && gun.ammo() < spec.magazine() && gun.reserve(bot) > 0) startReload(bot, s, gun, now);
        }
        release(bot); return false;
    }
    private boolean safeStep(Bot bot, Vec3 point) {
        BlockPos p = BlockPos.containing(point);
        return net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds.contains(bot, point, 3)
                && bot.level().hasChunkAt(p) && bot.level().getFluidState(p).isEmpty()
                && bot.level().getBlockState(p.below()).isSolid()
                && bot.level().noCollision(bot, bot.getBoundingBox().move(point.subtract(bot.position())));
    }
    /** A waypoint for the existing walking/pathfinding layer, never an independent movement mode. */
    public Vec3 pursuit(Bot bot, LivingEntity enemy, Vec3 fallback, long now) {
        State s = states.get(bot);
        if (s == null || bot.isPassenger()) return fallback;
        if (s.held == null) {
            if (skills.hardness(bot).level() >= 7 && access.isVehicle(enemy.getRootVehicle()) && carriesGun(bot)) {
                Vec3 away = bot.position().subtract(enemy.getRootVehicle().position()).multiply(1, 0, 1).normalize();
                Vec3 standOff = bot.position().add(away.scale(4));
                if (safeStep(bot, standOff) && bot.position().distanceTo(enemy.position()) < 24) return standOff;
            }
            return fallback;
        }
        Specs spec = s.held.specs();
        Entity target = access.isVehicle(enemy.getRootVehicle()) ? enemy.getRootVehicle() : enemy;
        boolean antiTank = skills.hardness(bot).level() >= 7 && access.isVehicle(target) && spec.type().endsWith("LAUNCHER");
        double distance = bot.position().distanceTo(target.position());
        if (antiTank) {
            if (now >= s.nextCover) { s.nextCover = now + 40; if (s.cover == null || !safeStep(bot, s.cover)) s.cover = findCover(bot, target.getBoundingBox().getCenter()); }
            Vec3 forward = target.getLookAngle().multiply(1, 0, 1).normalize();
            List<Terminator> group = skills.enabled(bot, "teamwork") ? skills.bots().stream().filter(b -> b instanceof Bot mate && mate.isAlive() && mate.level() == bot.level()
                    && (mate == bot || bot.getTeam() != null && mate.getTeam() == bot.getTeam()) && skills.enabled(mate, "teamwork")
                    && skills.currentTarget(mate) != null && skills.currentTarget(mate).getRootVehicle() == target)
                    .sorted(Comparator.comparing(b -> b.getEntity().getUUID())).toList() : List.of(bot);
            int slot = group.indexOf(bot); int sign = slot % 2 == 0 ? 1 : -1;
            boolean bait = group.size() >= 2 && slot == 0 && bot.getHealth() > bot.getMaxHealth() * 0.65;
            s.antiRole = group.size() < 2 ? "SOLO" : bait ? "BAIT" : "FLANK";
            if (s.cover != null) {
                if (cooling(s, s.held) || s.held.ammo() < spec.cost() || now < s.hideUntil
                        || bait && (now + bot.getId()) % 100 >= 20) return s.cover;
                Vec3 away = s.cover.subtract(target.position()).multiply(1, 0, 1).normalize();
                for (int direction : new int[]{sign, -sign}) {
                    for (double offset : new double[]{2.5, 4, 6}) {
                        Vec3 peek = s.cover.add(-away.z * direction * offset, 0, away.x * direction * offset);
                        if (safeStep(bot, peek) && lineFrom(bot, s.cover.add(0, bot.getEyeHeight(), 0), peek.add(0, bot.getEyeHeight(), 0))
                                && lineFrom(bot, peek.add(0, bot.getEyeHeight(), 0), target.getBoundingBox().getCenter())
                                && (bait || !frontal(bot, target) || now - s.aimSince >= 120)) return peek;
                    }
                }
            }
            if (bait && s.cover == null && distance >= 24 && distance < 55) return bot.position();
            Vec3 side = new Vec3(-forward.z * sign, 0, forward.x * sign);
            Vec3 flank = target.position().add(side.scale(Math.max(24, spec.explosionRadius() * 2 + 8))).add(forward.scale(-18));
            Vec3 step = bot.position().add(flank.subtract(bot.position()).multiply(1, 0, 1).normalize().scale(4));
            if (safeStep(bot, step)) return step;
        }
        double max = Math.min(spec.range(), switch (spec.type()) {
            case "SHOTGUN" -> 8; case "SMG", "PISTOL" -> 15;
            case "SNIPER", "DMR" -> 90; default -> 50;
        });
        double min = switch (spec.type()) { case "SNIPER", "DMR" -> 50; case "RIFLE", "MACHINE_GUN" -> 15; default -> 3; };
        if (distance > max || !line(bot, enemy.getBoundingBox().getCenter()) && !cooling(s, s.held)) return fallback;
        int difficulty = skills.hardness(bot).level();
        if (difficulty <= 3) return bot.position();
        if (now >= s.nextStrafe) {
            s.strafe = bot.getRandom().nextBoolean() ? 1 : -1;
            s.nextStrafe = now + 24 + bot.getRandom().nextInt(28);
        }
        if (now >= s.nextCover && (cooling(s, s.held) || difficulty >= 7)) {
            s.nextCover = now + 40;
            if (s.cover == null || !safeStep(bot, s.cover)) s.cover = findCover(bot, enemy.getEyePosition());
        }
        if (s.cover != null) {
            if (cooling(s, s.held) || (now + bot.getId()) % 100 < 30) return s.cover;
            // Leave the same real cover by a clear, reachable side before attempting another burst.
            Vec3 away = s.cover.subtract(enemy.position()).multiply(1, 0, 1).normalize();
            for (int sign : new int[]{s.strafe, -s.strafe}) {
                Vec3 peek = s.cover.add(-away.z * sign * 2.5, 0, away.x * sign * 2.5);
                if (safeStep(bot, peek) && bot.level().clip(new ClipContext(peek.add(0, bot.getEyeHeight(), 0), enemy.getEyePosition(),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot)).getType() == HitResult.Type.MISS) return peek;
            }
            s.cover = null;
        }
        Vec3 forward = enemy.position().subtract(bot.position()).multiply(1, 0, 1).normalize();
        double radial = cooling(s, s.held) || distance < min ? -0.65 : distance > max * 0.8 ? 0.4 : 0;
        Vec3 delta = forward.scale(radial).add(-forward.z * s.strafe, 0, forward.x * s.strafe).normalize().scale(3);
        Vec3 goal = bot.position().add(delta);
        if (safeStep(bot, goal)) return goal;
        s.nextStrafe = now; return fallback;
    }
    public boolean jumpWhileFiring(Bot bot, long now) {
        State s = states.get(bot);
        if (s == null || s.held == null || skills.hardness(bot).level() < 4 || cooling(s, s.held) || now < s.nextJump) return false;
        s.nextJump = now + 70 + bot.getRandom().nextInt(70);
        return true;
    }
}
