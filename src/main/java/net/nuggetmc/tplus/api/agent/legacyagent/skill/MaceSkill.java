package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;

import javax.annotation.Nullable;

/**
 * Mace smash attacks: get above the target (wind charge launch when close, elytra climb when further away), then fall
 * onto it steering in the air, and hit it while falling so the vanilla smash bonus applies.
 */
class MaceSkill {

    private final BotSkills skills;

    MaceSkill(BotSkills skills) {
        this.skills = skills;
    }

    boolean tryStart(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!skills.settings().mace() || !bot.hasMace() || now < mem.nextMaceAttempt) return false;

        Vec3 pos = bot.getLocation();
        double horizontal = SkillUtil.horizontalDistance(pos, target.position());
        double dy = target.getY() - pos.y;

        // close: wind charge at our feet, up we go
        if (skills.settings().windCharges() && bot.countItem(Items.WIND_CHARGE) > 0 && now >= mem.nextWindCharge
                && horizontal >= 1.0 && horizontal <= 5.5 && Math.abs(dy) < 2.5
                && SkillUtil.headroom(bot.getBotLevel(), bot.getEntity(), 9)) {
            launch(bot, mem, target, horizontal, now);
            return true;
        }

        // further away with open sky above the target: climb with the elytra and dive
        if (skills.settings().elytra() && skills.pilot().canFly(bot) && horizontal >= 3 && horizontal <= 80
                && SkillUtil.openSky(bot.getBotLevel(), target)) {
            mem.nextMaceAttempt = now + 200;
            return skills.pilot().tryStart(bot, mem, BotMemory.FlightPlan.MACE, now);
        }

        return false;
    }

    private void launch(Terminator bot, BotMemory mem, LivingEntity target, double horizontal, long now) {
        Vec3 pos = bot.getLocation();
        Vec3 dir = new Vec3(target.getX() - pos.x, 0, target.getZ() - pos.z);
        dir = dir.lengthSqr() < 1.0E-4 ? Vec3.ZERO : dir.normalize();

        bot.stand();
        bot.throwWindCharge(SkillUtil.yawTo(pos, target.position()), 90.0F);

        double push = Math.min(0.12, horizontal / 30);
        bot.launch(new Vec3(dir.x * push, 1.05, dir.z * push));

        mem.nextWindCharge = now + 30;
        mem.nextMaceAttempt = now + 30;
        startDrop(mem);
    }

    void startDrop(BotMemory mem) {
        mem.clearMovement();
        mem.maceDrop = true;
        mem.maceDropTicks = 0;
    }

    /**
     * @return whether the drop is still going
     */
    boolean tickDrop(Terminator bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        mem.maceDropTicks++;

        boolean landed = mem.maceDropTicks > 3 && (bot.isBotOnGround() || bot.isBotInWater() || bot.isClimbing());

        if (target == null || !target.isAlive() || target.level() != bot.getBotLevel() || landed || mem.maceDropTicks > 120) {
            mem.maceDrop = false;
            return false;
        }

        Vec3 pos = bot.getLocation();
        Vec3 velocity = bot.getVelocity();
        Vec3 targetPos = target.position();

        // steer towards the target while falling
        double height = Math.max(0.1, pos.y - targetPos.y);
        double ticksLeft = velocity.y < 0
                ? height / Math.max(0.3, -velocity.y)
                : Math.sqrt(2 * height / 0.08) + velocity.y / 0.08;
        ticksLeft = Math.max(1, ticksLeft);

        Vec3 wanted = new Vec3((targetPos.x - pos.x) / ticksLeft, 0, (targetPos.z - pos.z) / ticksLeft);
        if (wanted.length() > 0.5) wanted = wanted.normalize().scale(0.5);

        Vec3 change = wanted.subtract(velocity.x, 0, velocity.z);
        if (change.length() > 0.08) change = change.normalize().scale(0.08);

        bot.setVelocity(velocity.add(change.x, 0, change.z));
        bot.faceLocation(target.getBoundingBox().getCenter());
        bot.stand();
        bot.setItem(maceStack(bot));

        if (velocity.y < 0 && bot.canSmash() && SkillUtil.reach(bot.getEntity(), target) < 3.5) {
            bot.smash(target);
            mem.maceDrop = false;
            mem.nextMaceAttempt = now + 40;
            return true;
        }

        return true;
    }

    private static ItemStack maceStack(Terminator bot) {
        ItemStack mace = bot.findItem(stack -> stack.getItem() instanceof MaceItem);
        return mace.isEmpty() ? new ItemStack(Items.MACE) : mace;
    }
}
