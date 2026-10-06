package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;

import javax.annotation.Nullable;

/** Forecast the first legal smash contact, using the existing fall and steering limits. */
public final class DiveForecast {
    public record Contact(int ticks, Vec3 position, Vec3 targetPosition) {}
    private DiveForecast() {}

    @Nullable public static Contact contact(Terminator bot, LivingEntity target, Vec3 targetVelocity) {
        var level = bot.getBotLevel();
        if (target.level() != level || !target.isAlive()) return null;
        Vec3 position = bot.getLocation(), velocity = bot.getVelocity();
        Vec3 horizontal = velocity.multiply(1, 0, 1);
        double vertical = velocity.y, fallen = 0;
        var dimensions = bot.getEntity().getDimensions(Pose.STANDING);
        double halfWidth = dimensions.width() * 0.5;
        for (int tick = 0; tick <= 60; tick++) {
            Vec3 destination = target.position().add(targetVelocity.scale(Math.min(25, tick)));
            if (!level.hasChunkAt(BlockPos.containing(position)) || !level.hasChunkAt(BlockPos.containing(destination))) return null;
            AABB body = target.getBoundingBox().move(destination.subtract(target.position()));
            Vec3 eye = position.add(0, dimensions.eyeHeight(), 0);
            Vec3 closest = new Vec3(Mth.clamp(eye.x, body.minX, body.maxX), Mth.clamp(eye.y, body.minY, body.maxY), Mth.clamp(eye.z, body.minZ, body.maxZ));
            if (tick > 0 && vertical < 0 && fallen > 1.5 && eye.distanceTo(closest) < 3
                    && level.clip(new ClipContext(eye, body.getCenter(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot.getEntity())).getType() == HitResult.Type.MISS) {
                if (SkillUtil.voidBelow(level, destination)) return null;
                BlockPos floor = BlockPos.containing(destination.x, SkillUtil.surfaceY(level, destination) - 1, destination.z);
                return PearlAim.isSafeLanding(level, floor) ? new Contact(tick, position, destination) : null;
            }
            // Release happens in the pilot. The drop controller first steers on the following tick.
            if (tick > 0) {
                int remaining = MaceSkill.fallTicks(Math.max(0.1, position.y - destination.y), vertical);
                Vec3 predicted = target.position().add(targetVelocity.scale(Math.min(25, tick + remaining)));
                Vec3 wanted = predicted.subtract(position).multiply(1, 0, 1).scale(1.0 / remaining);
                if (wanted.length() > 0.5) wanted = wanted.normalize().scale(0.5);
                Vec3 change = wanted.subtract(horizontal);
                if (change.length() > 0.08) change = change.normalize().scale(0.08);
                horizontal = horizontal.add(change);
            }
            Vec3 movement = horizontal.add(0, vertical, 0);
            AABB box = new AABB(position.x - halfWidth, position.y, position.z - halfWidth,
                    position.x + halfWidth, position.y + dimensions.height(), position.z + halfWidth);
            if (!level.noCollision(bot.getEntity(), box.expandTowards(movement).deflate(1.0E-4))
                    || !level.getFluidState(BlockPos.containing(position.add(movement))).isEmpty()) return null;
            position = position.add(movement);
            if (vertical < 0) fallen -= vertical;
            vertical = Math.max(-3.5, vertical - 0.08);
        }
        return null;
    }
}
