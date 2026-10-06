package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.utils.PlayerUtils;

import javax.annotation.Nullable;

/**
 * Bow and arrows at range: stand, draw for a full second while following the target, then let go where the target will
 * be when the arrow gets there. Only used when the bot carries a bow and arrows.
 */
class BowSkill {

    private static final int DRAW_TICKS = 20;
    private static final double MIN_RANGE = 8;
    private static final double MAX_RANGE = 60;

    private final BotSkills skills;

    BowSkill(BotSkills skills) {
        this.skills = skills;
    }

    boolean tryStart(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!skills.enabled(bot, "bow") || now < mem.nextArrow) return false;

        double distance = bot.getLocation().distanceTo(target.position());
        if (distance < MIN_RANGE || distance > MAX_RANGE) return false;
        if (target instanceof Player player && PlayerUtils.isInvincible(player)) return false;
        if (!bot.canShootBow()) return false;

        // no clear shot from here: keep walking and look again in a moment
        if (ArrowAim.solve(bot.getBotLevel(), bot.getEntity(), target, mem.targetVelocity.scale(skills.hardness(bot).level() >= 7 ? 1 : 0)) == null) {
            mem.nextArrow = now + 10;
            return false;
        }

        mem.clearMovement();
        mem.bowTicks = 1;
        bot.stand();
        bot.drawBow();
        return true;
    }

    /**
     * @return whether the bot is busy with the bow this tick
     */
    boolean tick(Terminator bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        if (target == null || !target.isAlive() || target.level() != bot.getBotLevel() || bot.isBotInWater()
                || bot.getLocation().distanceTo(target.position()) < 4) {
            // gone, or close enough for melee
            stop(bot, mem, now, 10);
            return false;
        }

        ArrowAim.Solution aim = ArrowAim.solve(bot.getBotLevel(), bot.getEntity(), target, mem.targetVelocity.scale(skills.hardness(bot).level() >= 7 ? 1 : 0));

        if (aim != null) {
            bot.setLook(aim.yaw(), aim.pitch());
        } else {
            bot.faceLocation(target.getEyePosition());
        }

        bot.drawBow();

        if (++mem.bowTicks < DRAW_TICKS) {
            return true;
        }

        if (aim != null) {
            int level = skills.hardness(bot).level();
            float error = level >= 7 ? 0 : (7 - level) * 1.2F;
            bot.shootBow(aim.yaw() + (bot.getEntity().getRandom().nextFloat() - 0.5F) * error, aim.pitch());
        }

        // up close the sword and the mace hit harder: leave them some time between arrows
        stop(bot, mem, now, bot.getLocation().distanceTo(target.position()) < 16 ? 50 : 12);
        return true;
    }

    private static void stop(Terminator bot, BotMemory mem, long now, int cooldown) {
        bot.lowerBow();
        mem.bowTicks = 0;
        mem.nextArrow = now + cooldown;
    }
}
