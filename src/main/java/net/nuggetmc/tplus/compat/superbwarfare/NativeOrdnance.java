package net.nuggetmc.tplus.compat.superbwarfare;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.compat.WarfareAccess;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Public, cached 0.8.9.1 bindings. All motion and explosions remain native. */
final class NativeOrdnance implements WarfareAccess.Ordnance {
    private static final String ROOT = "com.atsuishio.superbwarfare.";
    private final Class<?> drone;
    private final Method controller, currentItem, processInput, mouseInput, fire, getC4, configGet, count, dropData, dropPosition;
    private final EntityDataAccessor<Integer> ammo;
    private final EntityDataAccessor<Boolean> linked, controllable;
    private final Field kamikaze, radius, damage;
    private final Map<String, ?> payloads;
    private final Object c4Radius;

    @SuppressWarnings("unchecked")
    NativeOrdnance() throws ReflectiveOperationException {
        drone = Class.forName(ROOT + "entity.vehicle.DroneEntity");
        controller = drone.getMethod("getController"); currentItem = drone.getMethod("getCurrentItem");
        processInput = drone.getMethod("processInput", short.class);
        mouseInput = drone.getMethod("mouseInput", double.class, double.class);
        fire = drone.getMethod("setFire", boolean.class);
        ammo = (EntityDataAccessor<Integer>) drone.getField("AMMO").get(null);
        linked = (EntityDataAccessor<Boolean>) drone.getField("LINKED").get(null);
        controllable = (EntityDataAccessor<Boolean>) Class.forName(ROOT + "entity.projectile.C4Entity").getField("IS_CONTROLLABLE").get(null);
        payloads = (Map<String, ?>) Class.forName(ROOT + "data.CustomData").getField("DRONE_ATTACHMENT").get(null);
        Class<?> data = Class.forName(ROOT + "data.drone_attachment.DroneAttachmentData");
        kamikaze = data.getField("isKamikaze"); radius = data.getField("explosionRadius"); damage = data.getField("explosionDamage");
        count = data.getMethod("count"); dropData = data.getMethod("dropData"); dropPosition = data.getMethod("dropPosition");
        getC4 = Class.forName(ROOT + "item.misc.DetonatorItem").getMethod("getC4", Player.class, Level.class);
        c4Radius = Class.forName(ROOT + "config.server.ExplosionConfig").getField("C4_EXPLOSION_RADIUS").get(null);
        configGet = net.neoforged.neoforge.common.ModConfigSpec.ConfigValue.class.getMethod("get");
    }
    private static Object call(Method method, Object object, Object... args) {
        try { return method.invoke(object, args); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("SBW ordnance " + method.getName(), e); }
    }
    @Override public boolean isDrone(Entity e) { return drone.isInstance(e); }
    @Override public WarfareAccess.Drone drone(Entity e) {
        if (!isDrone(e)) throw new IllegalArgumentException("Not a native drone");
        return new RemoteDrone(e);
    }
    @Override public WarfareAccess.Payload payload(ItemStack stack) {
        Object data = payloads.get(stack.getItem().toString());
        if (data == null || stack.isEmpty()) return null;
        try {
            double r = radius.getFloat(data), d = damage.getFloat(data);
            JsonObject drop = (JsonObject) call(dropData, data);
            if (drop != null) {
                if (drop.has("Radius")) r = Math.max(r, drop.get("Radius").getAsDouble());
                if (drop.has("ExplosionRadius")) r = Math.max(r, drop.get("ExplosionRadius").getAsDouble());
                if (drop.has("ExplosionDamage")) d = Math.max(d, drop.get("ExplosionDamage").getAsDouble());
            }
            float[] position = (float[]) call(dropPosition, data);
            return new WarfareAccess.Payload(kamikaze.getBoolean(data), ((Number) call(count, data)).intValue(), r, d,
                    new net.minecraft.world.phys.Vec3(position[0], position[1], position[2]));
        } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    @Override @SuppressWarnings("unchecked") public List<Entity> charges(Bot bot) {
        return ((List<Entity>) call(getC4, null, bot, bot.level())).stream()
                .filter(e -> !e.isRemoved() && e.getEntityData().get(controllable)).toList();
    }
    @Override public double chargeRadius() { return ((Number) call(configGet, c4Radius)).doubleValue(); }

    private final class RemoteDrone implements WarfareAccess.Drone {
        private final Entity entity;
        RemoteDrone(Entity entity) { this.entity = entity; }
        @Override public Entity entity() { return entity; }
        @Override public int ammo() { return entity.getEntityData().get(ammo); }
        @Override public WarfareAccess.Payload payload() { return NativeOrdnance.this.payload((ItemStack) call(currentItem, entity)); }
        @Override public boolean linked() { return entity.getEntityData().get(linked); }
        @Override public boolean owned(Bot bot) { return !entity.isRemoved() && call(controller, entity) == bot; }
        private boolean controls(Bot bot) {
            if (!owned(bot) || bot.level() != entity.level()) return false;
            ItemStack hand = bot.getMainHandItem();
            var tag = hand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            return hand.getItem().toString().equals("superbwarfare:monitor") && tag.getBoolean("Linked")
                    && tag.getBoolean("Using") && entity.getStringUUID().equals(tag.getString("LinkedDrone"));
        }
        @Override public boolean link(Bot bot) {
            if (linked() || entity.position().distanceTo(bot.getEyePosition()) > bot.entityInteractionRange()) return false;
            boolean shift = bot.isShiftKeyDown(); bot.setShiftKeyDown(false);
            try { entity.interact(bot, InteractionHand.MAIN_HAND); }
            finally { bot.setShiftKeyDown(shift); }
            if (owned(bot)) bot.getMainHandItem().getItem().use(bot.level(), bot, InteractionHand.MAIN_HAND);
            return controls(bot);
        }
        @Override public void input(Bot bot, int keys, double x, double y) {
            if (!controls(bot)) return;
            call(processInput, entity, (short) keys); call(mouseInput, entity, x, y);
        }
        @Override public void fire(Bot bot) { if (controls(bot) && ammo() > 0) call(fire, entity, true); }
        @Override public void stop(Bot bot, ItemStack monitor) {
            if (owned(bot)) { call(processInput, entity, (short) 0); call(mouseInput, entity, 0d, 0d); call(fire, entity, false); }
            // Ending remote view requires no teleport or interaction with a distant entity.
            // Keep the native binding, allowing its actual owner to retrieve/reuse the drone.
            CustomData.update(DataComponents.CUSTOM_DATA, monitor, tag -> tag.putBoolean("Using", false));
        }
    }
}
