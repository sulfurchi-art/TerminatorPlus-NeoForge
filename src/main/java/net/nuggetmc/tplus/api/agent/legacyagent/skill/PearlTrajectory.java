package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** Forecasts a loaded, already flying pearl without moving it or loading terrain. */
public final class PearlTrajectory {
    public record Landing(Vec3 position, int ticks) {}

    private PearlTrajectory() {}

    @Nullable
    public static Landing predict(ThrownEnderpearl pearl, int horizon) {
        if (!(pearl.level() instanceof ServerLevel level) || !pearl.isAlive()) return null;
        Vec3 position = pearl.position(), velocity = pearl.getDeltaMovement();
        for (int tick = 1; tick <= Math.min(120, horizon); tick++) {
            Vec3 next = position.add(velocity);
            if (!level.hasChunkAt(BlockPos.containing(position)) || !level.hasChunkAt(BlockPos.containing(next))
                    || position.y < level.getMinBuildHeight()) return null;
            var state = level.getBlockState(BlockPos.containing(position));
            // Portals and gateways invalidate a forecast within this dimension.
            if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_GATEWAY)) return null;
            HitResult block = level.clip(new ClipContext(position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pearl));
            Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            AABB box = pearl.getBoundingBox().move(position.subtract(pearl.position())).expandTowards(velocity).inflate(1);
            var entity = ProjectileUtil.getEntityHitResult(level, pearl, position, end, box,
                    e -> e != pearl.getOwner() && e.canBeHitByProjectile());
            if (entity != null || block.getType() != HitResult.Type.MISS) {
                // ThrownEnderpearl teleports its owner to the pre-move position, not the hit location.
                return new Landing(position, tick);
            }
            double drag = level.getFluidState(BlockPos.containing(position)).is(FluidTags.WATER) ? 0.8F : 0.99F;
            position = next;
            velocity = velocity.scale(drag).subtract(0, pearl.isNoGravity() ? 0 : 0.03, 0);
        }
        return null;
    }
}
