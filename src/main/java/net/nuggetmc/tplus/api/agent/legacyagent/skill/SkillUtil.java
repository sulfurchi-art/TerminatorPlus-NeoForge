package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class SkillUtil {

    private SkillUtil() {
    }

    static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.sqrt(Mth.square(a.x - b.x) + Mth.square(a.z - b.z));
    }

    /**
     * Minecraft yaw (0 = south/+z) of the horizontal direction from {@code from} to {@code to}.
     */
    static float yawTo(Vec3 from, Vec3 to) {
        return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    static float pitchTo(Vec3 from, Vec3 to) {
        double horizontal = horizontalDistance(from, to);
        return (float) -Math.toDegrees(Math.atan2(to.y - from.y, horizontal));
    }

    /**
     * Nothing to collide with (air, plants, water...), and not something that hurts.
     */
    static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && !state.is(Blocks.LAVA) && !state.is(BlockTags.FIRE)
                && !state.is(Blocks.COBWEB) && !state.is(Blocks.SWEET_BERRY_BUSH) && !state.is(Blocks.POWDER_SNOW);
    }

    static boolean climbable(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }

    /**
     * All blocks from 2 to {@code height} above the entity's feet are free.
     */
    static boolean headroom(ServerLevel level, Entity entity, int height) {
        BlockPos feet = entity.blockPosition();
        for (int dy = 2; dy <= height; dy++) {
            if (!passable(level, feet.above(dy))) {
                return false;
            }
        }
        return true;
    }

    /**
     * No blocks above the entity (open sky to dive from).
     */
    static boolean openSky(ServerLevel level, Entity entity) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, entity.getBlockX(), entity.getBlockZ()) <= entity.getBlockY() + 1;
    }

    /**
     * Nothing at all below this column: falling here ends in the void.
     */
    static boolean voidBelow(ServerLevel level, Vec3 pos) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(pos.x), Mth.floor(pos.z)) <= level.getMinBuildHeight();
    }

    static int surfaceY(ServerLevel level, Vec3 pos) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(pos.x), Mth.floor(pos.z));
    }

    /**
     * Distance from the attacker's eyes to the closest point of the target's hitbox (how vanilla measures reach).
     */
    static double reach(Entity attacker, Entity target) {
        Vec3 eye = attacker.getEyePosition();
        AABB box = target.getBoundingBox();
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);
        return eye.distanceTo(new Vec3(x, y, z));
    }
}
