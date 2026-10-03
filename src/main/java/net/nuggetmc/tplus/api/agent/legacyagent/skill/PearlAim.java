package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Picks the throwing angle for an ender pearl by simulating the vanilla projectile motion
 * (1.5 blocks/tick launch speed, 0.99 drag, 0.03 gravity) against the actual terrain.
 */
public final class PearlAim {

    public record Solution(float yaw, float pitch, Vec3 landing, double error) {
    }

    private static final double SPEED = 1.5;
    private static final int MAX_TICKS = 160;

    private PearlAim() {
    }

    /**
     * @param goal      where the thrower wants to end up
     * @param tolerance how far from the goal the landing spot may be
     */
    @Nullable
    public static Solution solve(ServerLevel level, Entity thrower, Vec3 goal, double tolerance) {
        Vec3 start = new Vec3(thrower.getX(), thrower.getEyeY() - 0.1, thrower.getZ());
        float yaw = (float) Math.toDegrees(Math.atan2(-(goal.x - start.x), goal.z - start.z));

        Solution best = null;

        for (float pitch = -75; pitch <= 60; pitch += 2.5F) {
            best = better(best, simulate(level, thrower, start, yaw, pitch, goal));
        }

        if (best != null) {
            float center = best.pitch();
            for (float pitch = center - 2.5F; pitch <= center + 2.5F; pitch += 0.5F) {
                best = better(best, simulate(level, thrower, start, yaw, pitch, goal));
            }
        }

        return best != null && best.error() <= tolerance ? best : null;
    }

    @Nullable
    private static Solution better(@Nullable Solution a, @Nullable Solution b) {
        if (b == null) return a;
        if (a == null) return b;
        return b.error() < a.error() ? b : a;
    }

    @Nullable
    private static Solution simulate(ServerLevel level, Entity thrower, Vec3 start, float yaw, float pitch, Vec3 goal) {
        float yawRad = yaw * Mth.DEG_TO_RAD;
        float pitchRad = pitch * Mth.DEG_TO_RAD;
        Vec3 velocity = new Vec3(-Mth.sin(yawRad) * Mth.cos(pitchRad), -Mth.sin(pitchRad), Mth.cos(yawRad) * Mth.cos(pitchRad))
                .normalize().scale(SPEED);
        Vec3 pos = start;

        for (int tick = 0; tick < MAX_TICKS; tick++) {
            Vec3 next = pos.add(velocity);

            // never load chunks just to aim
            if (!level.hasChunkAt(BlockPos.containing(next)) || !level.hasChunkAt(BlockPos.containing(pos))) {
                return null;
            }

            BlockHitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, thrower));

            if (hit.getType() == HitResult.Type.BLOCK) {
                if (hit.getDirection() != Direction.UP || !isSafeLanding(level, hit.getBlockPos())) {
                    return null;
                }

                // The owner is teleported to where the pearl was at the start of the tick it hit something.
                double error = Math.sqrt(Mth.square(pos.x - goal.x) + Mth.square(pos.z - goal.z)) + Math.abs(pos.y - goal.y) * 0.5;
                return new Solution(yaw, pitch, pos, error);
            }

            pos = next;

            if (pos.y < level.getMinBuildHeight()) {
                return null;
            }

            velocity = velocity.scale(0.99).subtract(0, 0.03, 0);
        }

        return null;
    }

    public static boolean isSafeLanding(ServerLevel level, BlockPos ground) {
        BlockState state = level.getBlockState(ground);

        if (state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(BlockTags.FIRE)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) {
            return false;
        }

        for (int dy = 1; dy <= 2; dy++) {
            BlockState above = level.getBlockState(ground.above(dy));
            if (!above.getCollisionShape(level, ground.above(dy)).isEmpty() || above.is(Blocks.LAVA) || above.is(BlockTags.FIRE)) {
                return false;
            }
        }

        return true;
    }
}
