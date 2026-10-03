package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;

import javax.annotation.Nullable;

/**
 * Ender pearls: close the gap to targets running away, get out of places the bot can't walk out of, and save the bot
 * when it falls into the void. Only used when the bot carries pearls.
 */
class PearlSkill {

    private static final double MIN_GAP = 20;
    private static final double MAX_GAP = 60;

    private final BotSkills skills;

    PearlSkill(BotSkills skills) {
        this.skills = skills;
    }

    private boolean ready(Terminator bot, BotMemory mem, long now) {
        return skills.settings().pearls() && bot.countItem(Items.ENDER_PEARL) > 0 && now >= mem.nextPearl;
    }

    /**
     * Target far away: pearl next to it (a pearl costs 5 health, so only with enough of it). Faster than the elytra, so
     * bots that have both throw a pearl first when it's ready.
     */
    boolean tryGapClose(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!ready(bot, mem, now) || bot.getBotHealth() < 10) return false;

        double horizontal = SkillUtil.horizontalDistance(bot.getLocation(), target.position());
        if (horizontal < MIN_GAP || horizontal > MAX_GAP) return false;

        return throwTowards(bot, mem, approachPoint(bot, target), 3.5, now);
    }

    /**
     * Stuck recovery: anywhere closer to the target.
     */
    boolean tryUnstuck(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!ready(bot, mem, now) || bot.getBotHealth() < 7) return false;

        double horizontal = SkillUtil.horizontalDistance(bot.getLocation(), target.position());
        if (horizontal > MAX_GAP) return false;

        return throwTowards(bot, mem, approachPoint(bot, target), Math.max(3.5, horizontal * 0.4), now);
    }

    /**
     * Falling with nothing below: pearl back to where the bot last stood, or to the target.
     */
    boolean tryVoidRescue(Terminator bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        if (!ready(bot, mem, now) || bot.isBotOnGround() || bot.isGliding() || bot.getVelocity().y > -0.5) return false;

        ServerLevel level = bot.getBotLevel();
        if (!SkillUtil.voidBelow(level, bot.getLocation())) return false;

        if (mem.lastGround != null && throwTowards(bot, mem, mem.lastGround, 6, now)) {
            return true;
        }

        if (target != null && target.level() == level && throwTowards(bot, mem, target.position(), 6, now)) {
            return true;
        }

        mem.nextPearl = now + 5;
        return false;
    }

    /**
     * A spot 2.5 blocks in front of the target (seen from the bot), at the target's feet.
     */
    private static Vec3 approachPoint(Terminator bot, LivingEntity target) {
        Vec3 pos = bot.getLocation();
        Vec3 targetPos = target.position();
        Vec3 back = new Vec3(pos.x - targetPos.x, 0, pos.z - targetPos.z);
        if (back.lengthSqr() < 1.0E-4) return targetPos;
        return targetPos.add(back.normalize().scale(2.5));
    }

    private boolean throwTowards(Terminator bot, BotMemory mem, Vec3 goal, double tolerance, long now) {
        mem.nextPearl = now + 40;

        PearlAim.Solution solution = PearlAim.solve(bot.getBotLevel(), bot.getEntity(), goal, tolerance);
        if (solution == null) return false;

        if (!bot.throwEnderPearl(solution.yaw(), solution.pitch())) return false;

        mem.clearMovement();
        mem.nextPearl = now + 100;
        return true;
    }
}
