package net.nuggetmc.tplus.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.bot.Bot;
import java.util.List;

/** No foreign mod types cross this boundary. */
public interface WarfareAccess {
    boolean isGun(ItemStack stack);
    Gun gun(ItemStack stack);
    boolean isVehicle(Entity entity);
    double vehicleDamage(Entity vehicle, net.minecraft.world.entity.LivingEntity shooter, Gun gun);
    double meleeDamage(Entity vehicle, net.minecraft.world.entity.LivingEntity shooter);
    Vessel vessel(Entity entity);
    Ordnance ordnance();
    List<Missile> missiles(Entity target);
    record Missile(Entity entity, boolean topAttack, double radius) {}

    record Payload(boolean kamikaze, int capacity, double radius, double damage, Vec3 dropPosition) {}
    interface Drone {
        Entity entity();
        int ammo();
        Payload payload();
        boolean owned(Bot bot);
        boolean linked();
        boolean link(Bot bot);
        void stop(Bot bot, ItemStack monitor);
        void input(Bot bot, int keys, double mouseX, double mouseY);
        void fire(Bot bot);
    }
    interface Ordnance {
        boolean isDrone(Entity entity);
        Drone drone(Entity entity);
        Payload payload(ItemStack stack);
        java.util.List<Entity> charges(Bot bot);
        double chargeRadius();
    }

    record Specs(String type, String projectile, double damage, double armorPiercing, double velocity,
                 double gravity, double range, int magazine, int cost, int projectiles, double rpm,
                 int boltTime, int reloadTime, double spread, double zoomSpread, double recoilX, double recoilY,
                 double explosionRadius, int seekTime, double seekAngle, double seekRange,
                 double minTargetHeight, double maxTargetHeight, boolean special, int shootDelay) {}

    record Seat(int index, boolean banHand, boolean enclosed, boolean rotateHead, float minPitch, float maxPitch,
                float minYaw, float maxYaw, List<String> weapons) {}
    record MountedWeapon(int index, String name, Specs specs, int ammo, int reserve, boolean ready, boolean reloading) {}
    record Footprint(Vec3 center, Vec3 halfSize) {}

    /** Every mutating operation verifies the actual occupied seat in the native adapter. */
    interface Vessel {
        Entity entity();
        String engine();
        Footprint footprint();
        List<Seat> seats();
        int seatIndex(Entity passenger);
        Entity passenger(int seat);
        boolean locked();
        boolean wreck();
        float health();
        float maxHealth();
        int energy();
        int maxEnergy();
        void energy(int value);
        void changed();
        float roll();
        float rotor();
        boolean hovering();
        boolean engineStarted();
        Vec3 entry(Bot bot, int seat);
        int decoys();
        int decoyItems();
        boolean board(Bot bot, int seat);
        boolean changeSeat(Bot bot, int seat);
        void input(Bot driver, int keys, double mouseX, double mouseY);
        void clampLook(Bot bot);
        List<MountedWeapon> weapons(net.minecraft.world.entity.LivingEntity bot);
        Gun weaponGun(net.minecraft.world.entity.LivingEntity bot, int index);
        int selectedWeapon(Bot bot);
        void selectWeapon(Bot bot, int index);
        Vec3 muzzle(Bot bot);
        Vec3 direction(Bot bot);
        void shoot(Bot bot, Entity lockedTarget);
        boolean threatened();
    }

    interface Gun {
        ItemStack stack();
        Specs specs();
        void selectAvailableAmmo(Bot bot);
        void tick(Bot bot, boolean held);
        int ammo();
        int reserve(net.minecraft.world.entity.LivingEntity bot);
        int virtualAmmo();
        void virtualAmmo(int rounds);
        boolean reloading();
        boolean canShoot(Bot bot);
        void reload(Bot bot);
        int reloadTicks(boolean empty);
        void beginCooldown(Bot bot, boolean empty);
        void finishCooldown(Bot bot, boolean empty);
        void finishBolt();
        boolean boltPending();
        void bolt();
        void zoom(boolean zoom);
        boolean zooming();
        void fireMode(boolean automatic, boolean topAttack);
        void shoot(Bot bot, double spread, boolean zoom, Entity lockedTarget);
    }
}
