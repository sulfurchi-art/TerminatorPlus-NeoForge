package net.nuggetmc.tplus.api.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A position inside a specific level, with a rotation. Immutable replacement for Bukkit's {@code Location},
 * used wherever a bot needs to be spawned or a spot needs to be remembered across levels.
 */
public record Location(ServerLevel level, double x, double y, double z, float yaw, float pitch) {

    public Location(ServerLevel level, double x, double y, double z) {
        this(level, x, y, z, 0, 0);
    }

    public Location(ServerLevel level, Vec3 position) {
        this(level, position.x, position.y, position.z, 0, 0);
    }

    public static Location of(Entity entity) {
        return new Location((ServerLevel) entity.level(), entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
    }

    public Vec3 position() {
        return new Vec3(x, y, z);
    }

    public BlockPos blockPos() {
        return BlockPos.containing(x, y, z);
    }

    public Location add(double dx, double dy, double dz) {
        return new Location(level, x + dx, y + dy, z + dz, yaw, pitch);
    }

    public Location add(Vec3 vec) {
        return add(vec.x, vec.y, vec.z);
    }

    public Location withY(double newY) {
        return new Location(level, x, newY, z, yaw, pitch);
    }

    @Override
    public String toString() {
        return level.dimension().location() + " " + MathUtils.round2Dec(x) + ", " + MathUtils.round2Dec(y) + ", " + MathUtils.round2Dec(z);
    }
}
