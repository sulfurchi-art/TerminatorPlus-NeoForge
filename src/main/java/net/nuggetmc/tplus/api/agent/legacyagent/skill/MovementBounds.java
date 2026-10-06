package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Destination limits include the entity footprint; movement still uses the existing native controls. */
public final class MovementBounds {
    private MovementBounds() {}
    public static boolean contains(Level level, Vec3 point, double margin) {
        var border = level.getWorldBorder();
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z)
                && point.x >= border.getMinX() + margin && point.x <= border.getMaxX() - margin
                && point.z >= border.getMinZ() + margin && point.z <= border.getMaxZ() - margin;
    }
    public static boolean contains(Entity entity, Vec3 point, double extra) {
        return contains(entity.level(), point, entity.getBbWidth() / 2 + extra);
    }
    public static Vec3 clamp(Entity entity, Vec3 point, double extra) {
        var b = entity.level().getWorldBorder();
        double margin = Math.min(entity.getBbWidth() / 2 + extra, Math.max(0, b.getSize() / 2 - 0.1));
        return new Vec3(Mth.clamp(point.x, b.getMinX() + margin, b.getMaxX() - margin), point.y,
                Mth.clamp(point.z, b.getMinZ() + margin, b.getMaxZ() - margin));
    }
}
