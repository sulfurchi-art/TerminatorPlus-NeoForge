package net.nuggetmc.tplus.compat.superbwarfare;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.WarfareAccess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;

/** Cached public API bindings for SBW 0.8.9.1; no compile or bundled mod dependency. */
public final class SuperbWarfareAccess implements WarfareAccess {
    private final WarfareAccess.Ordnance ordnance;
    @Override public WarfareAccess.Ordnance ordnance() { return ordnance; }
    private static final String ROOT = "com.atsuishio.superbwarfare.";
    private final Class<?> gunItem, gunData, vehicle;
    private final Method from, property, tick, save, canShoot, reserve, reloading, shouldBolt, startBolt,
            shoot, reload, intGet, intSet, boolSet, modeName, projectileId, damageModifier, computeDamage, invalidateState, resetStatus, finishEmpty, finishNormal, soundEmpty, soundNormal;
    private final Object handler;
    private final Map<String, Object> props = new HashMap<>();
    private final Map<String, Field> values = new HashMap<>();
    private final Map<String, Method> vehicleMethods = new HashMap<>(), seatMethods = new HashMap<>();
    private final Field gunStack, boltState, boltNeeded, fireModeKind, fireModeLabel;
    private final Method boolGet;
    private final Method vehicleEngine, vehicleSeats, footprintSize, footprintCenter;
    private final Class<?> missile;
    private final Method missileTarget, missileRadius, changeAmmo, countAmmo;
    private final net.minecraft.network.syncher.EntityDataAccessor<Boolean> missileTop;

    public SuperbWarfareAccess() throws ReflectiveOperationException {
        ordnance = new NativeOrdnance();
        gunItem = Class.forName(ROOT + "item.gun.GunItem");
        gunData = Class.forName(ROOT + "data.gun.GunData");
        vehicle = Class.forName(ROOT + "entity.vehicle.base.VehicleEntity");
        Class<?> prop = Class.forName(ROOT + "data.gun.GunProp");
        for (String name : List.of("GUN_TYPE", "PROJECTILE", "DAMAGE", "BYPASSES_ARMOR", "VELOCITY", "GRAVITY",
                "RANGE", "MAGAZINE", "AMMO_COST_PER_SHOOT", "PROJECTILE_AMOUNT", "RPM", "BOLT_ACTION_TIME",
                "EMPTY_RELOAD_TIME", "NORMAL_RELOAD_TIME", "SOUND_INFO", "SPREAD", "ZOOM_SPREAD_RATE", "RECOIL_X", "RECOIL_Y", "EXPLOSION_RADIUS",
                "SEEK_TIME", "SEEK_ANGLE", "SEEK_RANGE", "MIN_TARGET_HEIGHT", "MAX_TARGET_HEIGHT",
                "AVAILABLE_FIRE_MODES", "SHOOT_DELAY_TIME", "SEEK_TYPE", "AMMO_CONSUMER")) props.put(name, prop.getField(name).get(null));
        for (String name : List.of("ammo", "virtualAmmo", "zooming", "selectedFireMode", "selectedAmmoType", "nbtVersion")) values.put(name, gunData.getField(name));
        invalidateState = Class.forName(ROOT + "data.gun.NbtVersion").getMethod("invalidateState");
        from = gunData.getMethod("from", ItemStack.class);
        property = gunData.getMethod("get", prop);
        tick = gunData.getMethod("tick", Entity.class, boolean.class);
        save = gunData.getMethod("save");
        canShoot = gunData.getMethod("canShoot", Entity.class);
        reserve = gunData.getMethod("countBackupAmmo", Entity.class);
        changeAmmo = gunData.getMethod("changeAmmoConsumer", int.class, Entity.class);
        countAmmo = Class.forName(ROOT + "data.gun.AmmoConsumer").getMethod("count", gunData, Entity.class);
        reloading = gunData.getMethod("reloading");
        shouldBolt = gunData.getMethod("shouldStartBolt");
        startBolt = gunData.getMethod("startBolt");
        shoot = gunData.getMethod("shoot", Entity.class, double.class, boolean.class, UUID.class);
        Class<?> event = Class.forName(ROOT + "event.GunEventHandler");
        handler = event.getField("INSTANCE").get(null);
        reload = event.getMethod("tryStartReload", Entity.class, gunData, boolean.class);
        resetStatus = gunData.getMethod("resetStatus");
        finishEmpty = event.getMethod("finishGunEmptyReload", Entity.class, gunData);
        finishNormal = event.getMethod("finishGunNormalReload", Entity.class, gunData);
        Class<?> sounds = Class.forName(ROOT + "data.gun.SoundInfo");
        soundEmpty = sounds.getMethod("getReloadEmpty"); soundNormal = sounds.getMethod("getReloadNormal");
        Class<?> intValue = Class.forName(ROOT + "data.gun.value.IntValue");
        intGet = intValue.getMethod("get"); intSet = intValue.getMethod("set", int.class);
        boolSet = Class.forName(ROOT + "data.gun.value.BooleanValue").getMethod("set", boolean.class);
        boolGet = Class.forName(ROOT + "data.gun.value.BooleanValue").getMethod("get");
        boltState = gunData.getField("bolt");
        boltNeeded = Class.forName(ROOT + "data.gun.subdata.Bolt").getField("needed");
        Class<?> fireModeInfo = Class.forName(ROOT + "data.gun.FireModeInfo");
        fireModeKind = fireModeInfo.getField("mode"); fireModeLabel = fireModeInfo.getField("name");
        modeName = fireModeKind.getType().getMethod("getTypeName");
        projectileId = Class.forName(ROOT + "data.gun.ProjectileInfo").getMethod("getId");
        damageModifier = vehicle.getMethod("getDamageModifier");
        computeDamage = Class.forName(ROOT + "entity.vehicle.damage.DamageModifier").getMethod("compute", Entity.class, DamageSource.class, float.class);
        gunStack = gunData.getField("stack");
        Class<?> seat = Class.forName(ROOT + "data.vehicle.subdata.SeatInfo");
        for (String name : List.of("getBanHand", "isEnclosed", "getCanRotateHead", "getMinPitch", "getMaxPitch",
                "getMinYaw", "getMaxYaw", "weapons")) seatMethods.put(name, seat.getMethod(name));
        for (String name : List.of("computed", "getLocked", "isWreck", "getHealth", "getMaxHealth", "getEnergy", "getMaxEnergy",
                "getRoll", "getSynchedPropellerRot", "getHoverMode", "getEngineStartOver", "getAmmoSupplier", "getLastDriver", "setChanged")) bind(name);
        bind("getDismountLocationForIndex", LivingEntity.class, int.class); bind("getDecoyCount"); bind("countDecoyItem");
        bind("getSeatIndex", Entity.class); bind("getNthEntity", int.class); bind("changeSeat", Entity.class, int.class);
        bind("processInput", short.class); bind("mouseInput", double.class, double.class); bind("onPassengerTurned", Entity.class);
        bind("getGunData", int.class, int.class); bind("getAmmo", gunData); bind("getWeaponIndex", int.class);
        bind("setWeaponIndex", int.class, int.class); bind("getShootPos", Entity.class, float.class);
        bind("getShootVec", Entity.class, float.class); bind("vehicleShoot", LivingEntity.class, UUID.class, Vec3.class);
        bind("setEnergy", int.class);
        for (String name : List.of("getTurretHealth", "getLeftWheelHealth", "getRightWheelHealth", "getMainEngineHealth", "getSubEngineHealth")) bind(name);
        bind("getCollisionOBBInfo");
        Class<?> obbInfo = Class.forName(ROOT + "data.vehicle.subdata.OBBInfo");
        footprintSize = obbInfo.getMethod("getSize"); footprintCenter = obbInfo.getMethod("getPosition");
        Class<?> computed = Class.forName(ROOT + "data.vehicle.DefaultVehicleData");
        vehicleEngine = computed.getMethod("getEngineType"); vehicleSeats = computed.getMethod("seats");
        missile = Class.forName(ROOT + "entity.projectile.MissileProjectile");
        missileTarget = missile.getMethod("getTargetUUID");
        missileRadius = missile.getMethod("getExplosionRadius");
        missileTop = (net.minecraft.network.syncher.EntityDataAccessor<Boolean>) Class.forName(ROOT + "entity.projectile.JavelinMissileEntity").getField("TOP").get(null);
    }
    private void bind(String name, Class<?>... types) throws NoSuchMethodException { vehicleMethods.put(name, vehicle.getMethod(name, types)); }

    private static Object call(Method method, Object instance, Object... arguments) {
        try { return method.invoke(instance, arguments); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("SBW API " + method.getName(), e); }
    }
    private static Object field(Field field, Object instance) {
        try { return field.get(instance); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("SBW field " + field.getName(), e); }
    }
    @Override public boolean isGun(ItemStack stack) { return !stack.isEmpty() && gunItem.isInstance(stack.getItem()); }
    @Override public Gun gun(ItemStack stack) { return new NativeGun(stack, call(from, null, stack)); }
    @Override public boolean isVehicle(Entity entity) { return vehicle.isInstance(entity); }
    @Override public Vessel vessel(Entity entity) {
        if (!isVehicle(entity)) throw new IllegalArgumentException("Not an SBW vehicle");
        return new NativeVessel(entity);
    }
    @Override public List<Missile> missiles(Entity target) {
        Entity root = target.getRootVehicle();
        java.util.Set<String> ids = new java.util.HashSet<>();
        ids.add(target.getStringUUID()); ids.add(root.getStringUUID());
        root.getPassengers().forEach(e -> ids.add(e.getStringUUID()));
        List<Missile> found = new ArrayList<>();
        for (Entity e : target.level().getEntities(target, root.getBoundingBox().inflate(256), missile::isInstance)) {
            if (!e.isAlive() || !ids.contains((String) call(missileTarget, e))) continue;
            boolean top = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("javelin_missile") && e.getEntityData().get(missileTop);
            found.add(new Missile(e, top, ((Number) call(missileRadius, e)).doubleValue()));
        }
        return found;
    }

    @Override public double vehicleDamage(Entity target, LivingEntity bot, Gun gun) {
        Specs s = gun.specs();
        ResourceLocation id = ResourceLocation.tryParse(s.projectile());
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return 0;
        // An unspawned source carries the correct entity tags and incoming angle for SBW's own calculator.
        Entity source = BuiltInRegistries.ENTITY_TYPE.get(id).create(bot.level());
        if (source == null) return 0;
        source.setPos(bot.getEyePosition());
        if (source instanceof Projectile projectile) projectile.setOwner(bot);
        String kind = s.explosionRadius() > 0 ? "projectile_hit" : "gunfire";
        var holder = bot.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(
                ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath("superbwarfare", kind)));
        Object modifier = call(damageModifier, target);
        return ((Number) call(computeDamage, modifier, target, new DamageSource(holder, source, bot), (float) s.damage())).doubleValue();
    }

    @Override public double meleeDamage(Entity target, LivingEntity attacker) {
        DamageSource source = attacker instanceof net.minecraft.world.entity.player.Player player
                ? attacker.damageSources().playerAttack(player) : attacker.damageSources().mobAttack(attacker);
        return ((Number) call(computeDamage, call(damageModifier, target), target, source, 10f)).doubleValue();
    }

    private final class NativeGun implements Gun {
        private final ItemStack stack;
        private final Object data;
        NativeGun(ItemStack stack, Object data) { this.stack = stack; this.data = data; }
        Object prop(String name) { return call(property, data, props.get(name)); }
        double num(String name) { return ((Number) prop(name)).doubleValue(); }
        int integer(String name) { return (int) num(name); }
        Object value(String name) { return field(values.get(name), data); }
        void flush() { call(invalidateState, value("nbtVersion")); call(save, data); }
        @Override public ItemStack stack() { return stack; }
        @Override public Specs specs() {
            boolean special = prop("GUN_TYPE").toString().equals("SPECIAL") || (Boolean) call(findSpecial(), stack.getItem(), data);
            return new Specs(prop("GUN_TYPE").toString(), (String) call(projectileId, prop("PROJECTILE")),
                    num("DAMAGE"), num("BYPASSES_ARMOR"), num("VELOCITY"), num("GRAVITY"), num("RANGE"),
                    integer("MAGAZINE"), integer("AMMO_COST_PER_SHOOT"), integer("PROJECTILE_AMOUNT"), num("RPM"),
                    integer("BOLT_ACTION_TIME"), integer("EMPTY_RELOAD_TIME"), num("SPREAD"), num("ZOOM_SPREAD_RATE"),
                    num("RECOIL_X"), num("RECOIL_Y"), num("EXPLOSION_RADIUS"), prop("SEEK_TYPE").toString().equals("NONE") ? 0 : integer("SEEK_TIME"), num("SEEK_ANGLE"),
                    num("SEEK_RANGE"), num("MIN_TARGET_HEIGHT"), num("MAX_TARGET_HEIGHT"), special, integer("SHOOT_DELAY_TIME"));
        }
        @Override public void selectAvailableAmmo(Bot bot) {
            if (ammo() > 0 || virtualAmmo() > 0 || reloading() || reserve(bot) > 0) return;
            List<?> consumers = (List<?>) prop("AMMO_CONSUMER");
            for (int i = 0; i < consumers.size(); i++) {
                if (((Number) call(countAmmo, consumers.get(i), data, bot)).intValue() > 0) {
                    call(changeAmmo, data, i, bot); flush(); return;
                }
            }
        }
        @Override public void tick(Bot bot, boolean held) { call(tick, data, bot, held); flush(); }
        @Override public int ammo() { return (int) call(intGet, value("ammo")); }
        @Override public int virtualAmmo() { return (int) call(intGet, value("virtualAmmo")); }
        @Override public void virtualAmmo(int rounds) { call(intSet, value("virtualAmmo"), rounds); flush(); }
        @Override public int reserve(LivingEntity bot) { return (int) call(reserve, data, bot); }
        @Override public boolean reloading() { return (boolean) call(reloading, data); }
        @Override public boolean canShoot(Bot bot) { return (boolean) call(canShoot, data, bot); }
        @Override public void reload(Bot bot) { call(reload, handler, bot, data, true); }
        @Override public int reloadTicks(boolean empty) { return Math.max(1, integer(empty ? "EMPTY_RELOAD_TIME" : "NORMAL_RELOAD_TIME")); }
        @Override public void beginCooldown(Bot bot, boolean empty) {
            call(resetStatus, data);
            var sound = (net.minecraft.sounds.SoundEvent) call(empty ? soundEmpty : soundNormal, prop("SOUND_INFO"));
            if (sound != null) bot.level().playSound(null, bot.getX(), bot.getY(), bot.getZ(), sound, net.minecraft.sounds.SoundSource.PLAYERS, 1f, 1f);
            flush();
        }
        @Override public void finishCooldown(Bot bot, boolean empty) {
            // Native completion owns finite reserve debit, chamber allowance and reload events.
            call(empty ? finishEmpty : finishNormal, handler, bot, data); call(resetStatus, data); flush();
        }
        @Override public boolean boltPending() { return (boolean) call(boolGet, field(boltNeeded, field(boltState, data))); }
        @Override public void finishBolt() { call(resetStatus, data); flush(); }
        @Override public void bolt() { if ((boolean) call(shouldBolt, data)) { call(startBolt, data); call(save, data); } }
        @Override public void zoom(boolean zoom) { call(boolSet, value("zooming"), zoom); flush(); }
        @Override public boolean zooming() { return (boolean) call(boolGet, value("zooming")); }
        @Override public void fireMode(boolean automatic, boolean topAttack) {
            List<?> modes = (List<?>) prop("AVAILABLE_FIRE_MODES");
            String desired = topAttack ? "Top" : automatic ? "Auto" : "Semi";
            for (int i = 0; i < modes.size(); i++) {
                Object mode = field(fireModeKind, modes.get(i));
                String name = (String) field(fireModeLabel, modes.get(i));
                if (desired.equalsIgnoreCase(name) || !topAttack && desired.equalsIgnoreCase((String) call(modeName, mode))) {
                    if ((int) call(intGet, value("selectedFireMode")) != i) {
                        call(intSet, value("selectedFireMode"), i); call(save, data);
                    }
                    return;
                }
            }
        }
        @Override public void shoot(Bot bot, double spread, boolean zoom, Entity locked) {
            call(shoot, data, bot, spread, zoom, locked == null ? null : locked.getUUID());
            flush();
        }
    }
    private final class NativeVessel implements Vessel {
        private final Entity entity;
        NativeVessel(Entity entity) { this.entity = entity; }
        private Footprint shape; private int shapeTick = -100;
        Object v(String method, Object... args) { return call(vehicleMethods.get(method), entity, args); }
        @Override public Entity entity() { return entity; }
        @Override public String engine() { return call(vehicleEngine, v("computed")).toString(); }
        @Override public Footprint footprint() {
            if (shape != null && entity.tickCount - shapeTick < 10) return shape;
            shapeTick = entity.tickCount;
            Object info = v("getCollisionOBBInfo");
            if (info != null) return shape = new Footprint((Vec3) call(footprintCenter, info), (Vec3) call(footprintSize, info));
            double width = Math.max(1.5, entity.getBbWidth() / 2);
            double height = Math.max(2, entity.getBbHeight());
            return shape = new Footprint(new Vec3(0, height / 2, 0), new Vec3(width, height / 2, width));
        }
        @Override public List<Seat> seats() {
            List<?> seats = (List<?>) call(vehicleSeats, v("computed")); List<Seat> result = new ArrayList<>();
            for (int i = 0; i < seats.size(); i++) {
                Object seat = seats.get(i);
                result.add(new Seat(i, (boolean) call(seatMethods.get("getBanHand"), seat),
                        Boolean.TRUE.equals(call(seatMethods.get("isEnclosed"), seat)),
                        (boolean) call(seatMethods.get("getCanRotateHead"), seat),
                        ((Number) call(seatMethods.get("getMinPitch"), seat)).floatValue(),
                        ((Number) call(seatMethods.get("getMaxPitch"), seat)).floatValue(),
                        ((Number) call(seatMethods.get("getMinYaw"), seat)).floatValue(),
                        ((Number) call(seatMethods.get("getMaxYaw"), seat)).floatValue(),
                        List.copyOf((List<String>) call(seatMethods.get("weapons"), seat))));
            }
            return result;
        }
        @Override public int seatIndex(Entity passenger) { return (int) v("getSeatIndex", passenger); }
        @Override public Entity passenger(int index) { return (Entity) v("getNthEntity", index); }
        @Override public boolean locked() { return (boolean) v("getLocked"); }
        @Override public boolean wreck() { return (boolean) v("isWreck"); }
        @Override public float health() { return ((Number) v("getHealth")).floatValue(); }
        @Override public float maxHealth() { return ((Number) v("getMaxHealth")).floatValue(); }
        @Override public Map<String, Float> partHealth() {
            Map<String, Float> out = new java.util.LinkedHashMap<>();
            for (String part : List.of("Turret", "LeftWheel", "RightWheel", "MainEngine", "SubEngine")) out.put(part, ((Number) v("get" + part + "Health")).floatValue());
            return out;
        }
        @Override public int energy() { return (int) v("getEnergy"); }
        @Override public int maxEnergy() { return (int) v("getMaxEnergy"); }
        @Override public void energy(int value) { v("setEnergy", value); }
        @Override public void changed() { v("setChanged"); }
        @Override public float roll() { return ((Number) v("getRoll")).floatValue(); }
        @Override public float rotor() { return ((Number) v("getSynchedPropellerRot")).floatValue(); }
        @Override public boolean hovering() { return (boolean) v("getHoverMode"); }
        @Override public boolean engineStarted() { return (boolean) v("getEngineStartOver"); }
        @Override public Vec3 entry(Bot bot, int index) { return (Vec3) v("getDismountLocationForIndex", bot, index); }
        @Override public int decoys() { return (int) v("getDecoyCount"); }
        @Override public int decoyItems() { return (int) v("countDecoyItem"); }
        @Override public boolean board(Bot bot, int index) {
            if (locked() || wreck() || passenger(index) != null || index < 0 || index >= seats().size()
                    || bot.level() != entity.level() || !bot.canInteractWithEntity(entity.getBoundingBox(), 0)) return false;
            if (!bot.startRiding(entity)) return false;
            if (seatIndex(bot) == index || changeSeat(bot, index)) return true;
            bot.stopRiding(); return false;
        }
        @Override public boolean changeSeat(Bot bot, int index) {
            return bot.getVehicle() == entity && passenger(index) == null && (boolean) v("changeSeat", bot, index);
        }
        @Override public void input(Bot driver, int keys, double x, double y) {
            if (driver.getVehicle() != entity || seatIndex(driver) != 0) return;
            v("processInput", (short) keys); v("mouseInput", x, y);
        }
        @Override public void clampLook(Bot bot) { if (bot.getVehicle() == entity) v("onPassengerTurned", bot); }
        @Override public List<MountedWeapon> weapons(LivingEntity bot) {
            int index = seatIndex(bot); if (index < 0) return List.of();
            List<String> names = seats().get(index).weapons(); List<MountedWeapon> result = new ArrayList<>();
            Object supplier = v("getAmmoSupplier");
            for (int i = 0; i < names.size(); i++) {
                Object data = v("getGunData", index, i); if (data == null) continue;
                NativeGun gun = new NativeGun((ItemStack) field(gunStack, data), data);
                Specs specs = gun.specs(); int backup = (int) call(reserve, data, supplier);
                // Native virtual consumption may leave the displayed backup count stale until the next scan.
                boolean ready = (boolean) call(canShoot, data, supplier) && (specs.magazine() > 0 || backup >= specs.cost());
                result.add(new MountedWeapon(i, names.get(i), specs, (int) v("getAmmo", data), backup, ready, gun.reloading()));
            }
            return result;
        }
        @Override public Gun weaponGun(LivingEntity bot, int index) {
            int seat = seatIndex(bot); if (seat < 0) return null;
            Object data = v("getGunData", seat, index);
            return data == null ? null : new NativeGun((ItemStack) field(gunStack, data), data);
        }
        @Override public int selectedWeapon(Bot bot) { return (int) v("getWeaponIndex", seatIndex(bot)); }
        @Override public void selectWeapon(Bot bot, int index) {
            int seat = seatIndex(bot); if (seat >= 0 && index >= 0 && index < seats().get(seat).weapons().size()) v("setWeaponIndex", seat, index);
        }
        @Override public Vec3 muzzle(Bot bot) { return (Vec3) v("getShootPos", bot, 1f); }
        @Override public Vec3 direction(Bot bot) { return (Vec3) v("getShootVec", bot, 1f); }
        @Override public void shoot(Bot bot, Entity lockedTarget) {
            if (bot.getVehicle() == entity && seatIndex(bot) >= 0 && !wreck())
                v("vehicleShoot", bot, lockedTarget == null ? null : lockedTarget.getUUID(), null);
        }
        @Override public boolean threatened() {
            String uuid = entity.getUUID().toString();
            for (Entity incoming : entity.level().getEntities(entity, entity.getBoundingBox().inflate(128), missile::isInstance)) {
                String target = (String) call(missileTarget, incoming);
                if (uuid.equals(target) || entity.getPassengers().stream().anyMatch(e -> e.getUUID().toString().equals(target))) return true;
            }
            return false;
        }
    }
    private Method special;
    private Method findSpecial() {
        if (special == null) {
            try { special = gunItem.getMethod("useSpecialFireProcedure", gunData); }
            catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
        }
        return special;
    }
}
