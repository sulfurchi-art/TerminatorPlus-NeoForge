package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.common.ItemAbilities;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.bot.Bot;

import javax.annotation.Nullable;

/** Timed shield use is a defensive action with the same native charge time and cooldown as players. */
final class ShieldDefense {
    private final BotSkills skills;
    ShieldDefense(BotSkills skills) { this.skills = skills; }

    boolean tick(Terminator terminator, BotMemory memory, @Nullable LivingEntity target, long now) {
        if (!(terminator instanceof Bot bot)) return false;
        boolean available = skills.enabled(bot, "shield") && !bot.isConsuming() && !bot.isGliding()
                && memory.flight == BotMemory.Flight.NONE && !memory.maceDrop && memory.bowTicks == 0
                && memory.feint == BotMemory.Feint.NONE && memory.interceptPoint == null
                && (memory.tactic == BotMemory.Tactic.FIGHT || memory.tactic == BotMemory.Tactic.COVER_ALLY);
        ItemStack shield = bot.getOffhandItem().canPerformAction(ItemAbilities.SHIELD_BLOCK) ? bot.getOffhandItem()
                : bot.findItem(s -> s.canPerformAction(ItemAbilities.SHIELD_BLOCK));
        available &= !shield.isEmpty() && !bot.getCooldowns().isOnCooldown(shield.getItem());
        if (memory.defendingUntil > 0) {
            if (!available || now >= memory.defendingUntil || !bot.isUsingItem() || bot.getUsedItemHand() != InteractionHand.OFF_HAND) {
                bot.lowerShield(); memory.defendingUntil = 0;
                return false;
            }
            var threat = bot.getBotLevel().getEntity(memory.defendingThreat);
            if (threat != null && threat.isAlive() && (!(threat instanceof AbstractArrow arrow) || impactTicks(bot, arrow) > 0)) bot.faceLocation(threat.position());
            bot.stand();
            return true;
        }
        if (!available || now < memory.nextDefense || !bot.isBotOnGround() || bot.isUsingItem()
                || bot.getOffhandItem().is(Items.TOTEM_OF_UNDYING) && bot.getHealth() / bot.getMaxHealth() < 0.65) return false;
        var arrow = bot.getBotLevel().getEntitiesOfClass(AbstractArrow.class, bot.getBoundingBox().inflate(24), a -> !a.onGround()
                && a.getDeltaMovement().lengthSqr() > 0.02
                && a.getOwner() != bot && (a.getOwner() == null || !bot.isAlliedTo(a.getOwner())) && visible(bot, a))
                .stream().map(a -> new Incoming(a, impactTicks(bot, a))).filter(a -> a.ticks() > 0)
                .min(java.util.Comparator.comparingInt(Incoming::ticks)).map(Incoming::arrow).orElse(null);
        boolean imminent = target instanceof Player player && (skills.hardness(bot).level() == 10 ? player.getAttackStrengthScale(0.5F) > 0.85F
                : target.swinging || memory.targetVelocity.y < -0.1
                || memory.targetVelocity.dot(bot.position().subtract(target.position()).multiply(1, 0, 1).normalize()) > 0.06);
        LivingEntity attacker = target != null && target.isAlive() && !bot.isAlliedTo(target) && bot.distanceTo(target) < 4.5
                && !(target.getMainHandItem().getItem() instanceof AxeItem) && target instanceof Player player
                && imminent && target.getLookAngle().dot(bot.getEyePosition().subtract(target.getEyePosition()).normalize()) > 0.6
                && skills.canSee(bot, target) && bot.hasLineOfSight(target) ? target : null;
        if (arrow == null && attacker == null) return false;
        var threat = arrow != null ? arrow : attacker;
        if (!bot.equipShield()) return false;
        bot.setShieldEnabled(true); bot.faceLocation(threat.position()); bot.stand(); bot.block(16, 10);
        if (!bot.isUsingItem() || bot.getUsedItemHand() != InteractionHand.OFF_HAND) return false;
        memory.defendingThreat = threat.getId(); memory.defendingUntil = now + 16; memory.nextDefense = now + 32;
        return true;
    }

    private record Incoming(AbstractArrow arrow, int ticks) {}

    private boolean visible(Bot bot, AbstractArrow arrow) {
        Vec3 direction = arrow.position().subtract(bot.getEyePosition());
        return (!skills.hardness(bot).limitedPerception() || bot.getLookAngle().dot(direction.normalize()) >= 0.34)
                && bot.hasLineOfSight(arrow);
    }

    private static int impactTicks(Bot bot, AbstractArrow arrow) {
        Vec3 position = arrow.position(), velocity = arrow.getDeltaMovement();
        AABB body = bot.getBoundingBox().inflate(0.3);
        for (int tick = 1; tick <= 16; tick++) {
            Vec3 next = position.add(velocity);
            if (!bot.getBotLevel().hasChunkAt(BlockPos.containing(position)) || !bot.getBotLevel().hasChunkAt(BlockPos.containing(next))) return -1;
            var block = bot.getBotLevel().clip(new ClipContext(position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, arrow));
            Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            if (body.clip(position, end).isPresent()) return tick;
            if (block.getType() != HitResult.Type.MISS) return -1;
            position = next;
            velocity = velocity.scale(arrow.isInWater() ? 0.6F : 0.99F).subtract(0, arrow.isNoGravity() ? 0 : 0.05, 0);
        }
        return -1;
    }
}
