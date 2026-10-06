package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Aims a fully drawn bow by simulating the vanilla arrow flight (3 blocks/tick launch speed, 0.99 drag, 0.05 gravity),
 * leading the target by its velocity over the flight time.
 */
public final class ArrowAim {

    public record Solution(float yaw, float pitch, int ticks) {
    }

    private static final double SPEED = 3.0;
    private static final int MAX_TICKS = 100;
    private static final double MIN_ELEVATION = -60;
    private static final double MAX_ELEVATION = 40;

    private ArrowAim() {
    }

    /**
     * @param targetVelocity the target's movement per tick
     * @return the angles to shoot at, or {@code null} if the target is out of range or the flight path is blocked
     */
    @Nullable
    public static Solution solve(ServerLevel level, Entity shooter, LivingEntity target, Vec3 targetVelocity) {
        Vec3 start = new Vec3(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
        Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
        // jumping around on the ground isn't worth leading vertically
        Vec3 velocity = target.onGround() ? new Vec3(targetVelocity.x, 0, targetVelocity.z) : targetVelocity;

        Vec3 goal = center;
        Solution solution = solveFixed(start, goal);

        // where will the target be once the arrow gets there? a couple of rounds converge quickly
        for (int i = 0; i < 2 && solution != null; i++) {
            goal = center.add(velocity.scale(solution.ticks()));
            solution = solveFixed(start, goal);
        }

        return solution != null && isClear(level, shooter, start, solution, goal) ? solution : null;
    }

    /**
     * Lowest arc that reaches a fixed point.
     */
    @Nullable
    private static Solution solveFixed(Vec3 start, Vec3 goal) {
        double distance = horizontal(start, goal);
        double rise = goal.y - start.y;
        float yaw = (float) Math.toDegrees(Math.atan2(-(goal.x - start.x), goal.z - start.z));

        // the height at the target's distance only grows with the elevation (up to the max range angle)
        double[] high = heightAt(MAX_ELEVATION, distance);
        if (high == null || high[0] < rise) return null;

        double low = MIN_ELEVATION;
        double up = MAX_ELEVATION;
        double[] result = high;

        for (int i = 0; i < 24; i++) {
            double mid = (low + up) / 2;
            double[] height = heightAt(mid, distance);

            if (height == null || height[0] < rise) {
                low = mid;
            } else {
                up = mid;
                result = height;
            }
        }

        return new Solution(yaw, (float) -up, (int) Math.ceil(result[1]));
    }

    /**
     * @return {height reached at the horizontal distance, ticks it took}, or {@code null} if the arrow doesn't get there
     */
    @Nullable
    private static double[] heightAt(double elevation, double distance) {
        double rad = Math.toRadians(elevation);
        double vh = Math.cos(rad) * SPEED;
        double vy = Math.sin(rad) * SPEED;
        double h = 0;
        double y = 0;

        for (int tick = 1; tick <= MAX_TICKS; tick++) {
            double nh = h + vh;
            double ny = y + vy;

            if (nh >= distance) {
                double part = vh <= 0 ? 0 : (distance - h) / vh;
                return new double[]{y + vy * part, tick - 1 + part};
            }

            h = nh;
            y = ny;
            vh *= 0.99;
            vy = vy * 0.99 - 0.05;
        }

        return null;
    }

    /**
     * Follows the shot until it reaches the goal and checks it doesn't hit a block on the way.
     */
    private static boolean isClear(ServerLevel level, Entity shooter, Vec3 start, Solution solution, Vec3 goal) {
        float yawRad = solution.yaw() * Mth.DEG_TO_RAD;
        float pitchRad = solution.pitch() * Mth.DEG_TO_RAD;
        Vec3 velocity = new Vec3(-Mth.sin(yawRad) * Mth.cos(pitchRad), -Mth.sin(pitchRad), Mth.cos(yawRad) * Mth.cos(pitchRad))
                .scale(SPEED);
        double distance = horizontal(start, goal);
        Vec3 pos = start;

        for (int tick = 0; tick <= solution.ticks(); tick++) {
            Vec3 next = pos.add(velocity);

            // never load chunks just to aim
            if (!level.hasChunkAt(BlockPos.containing(next))) {
                return false;
            }

            HitResult hit = level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
            if (hit.getType() != HitResult.Type.MISS) {
                // a block in the way, unless it is behind the target
                return horizontal(start, hit.getLocation()) >= distance - 0.5;
            }

            pos = next;
            velocity = velocity.scale(0.99).subtract(0, 0.05, 0);

            if (horizontal(start, pos) >= distance) {
                return true;
            }
        }

        return true;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.sqrt(Mth.square(a.x - b.x) + Mth.square(a.z - b.z));
    }
}
