package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;

import javax.annotation.Nullable;

/**
 * Flies bots with an elytra and firework rockets: take off with a rocket, cruise above the terrain towards the target
 * and either land next to it ({@link BotMemory.FlightPlan#TRAVEL}) or get above it and let go for a mace smash
 * ({@link BotMemory.FlightPlan#MACE}).
 */
class ElytraPilot {

    /**
     * Fly instead of walking when the target is at least this far away (horizontally).
     */
    private static final double TRAVEL_DISTANCE = 30;
    private static final int MAX_FLIGHT_TICKS = 1200;

    private final BotSkills skills;

    ElytraPilot(BotSkills skills) {
        this.skills = skills;
    }

    boolean canFly(Terminator bot) {
        return skills.settings().elytra() && bot.hasUsableElytra() && bot.countItem(Items.FIREWORK_ROCKET) > 0;
    }

    /**
     * Long distance (or far above): fly there.
     */
    boolean tryTravel(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!canFly(bot) || now < mem.nextTakeoff) return false;

        Vec3 pos = bot.getLocation();
        double horizontal = SkillUtil.horizontalDistance(pos, target.position());
        double up = target.getY() - pos.y;

        if (horizontal < TRAVEL_DISTANCE && !(up > 10 && horizontal > 4)) return false;

        return tryStart(bot, mem, BotMemory.FlightPlan.TRAVEL, now);
    }

    boolean tryStart(Terminator bot, BotMemory mem, BotMemory.FlightPlan plan, long now) {
        if (!canFly(bot) || now < mem.nextTakeoff || !bot.isBotOnGround() || bot.isBotInWater()) return false;
        if (!SkillUtil.headroom(bot.getBotLevel(), bot.getEntity(), 5)) return false;

        mem.clearMovement();
        mem.flight = BotMemory.Flight.TAKEOFF;
        mem.plan = plan;
        mem.flightTicks = 0;
        mem.nextTakeoff = now + 100;
        return true;
    }

    void tick(Terminator bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        mem.flightTicks++;

        if (mem.flight == BotMemory.Flight.TAKEOFF) {
            takeoff(bot, mem, target, now);
            return;
        }

        if (!bot.isGliding()) {
            // landed, out of elytra durability, fell into water...
            end(mem, now);
            return;
        }

        if (mem.flightTicks > MAX_FLIGHT_TICKS) {
            bot.stopGliding();
            end(mem, now);
            return;
        }

        cruise(bot, mem, target, now);
    }

    private void takeoff(Terminator bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        Vec3 pos = bot.getLocation();
        float yaw = target != null ? SkillUtil.yawTo(pos, target.position()) : bot.getEntity().getYRot();
        bot.setLook(yaw, -50);
        bot.stand();

        if (mem.flightTicks == 1) {
            bot.launch(new Vec3(0, 0.42, 0));
        } else if (!bot.isBotOnGround() && bot.getVelocity().y < 0.25) {
            if (bot.startGliding()) {
                bot.fireRocket();
                mem.lastRocket = now;
                mem.flight = BotMemory.Flight.CRUISE;
            } else {
                end(mem, now);
            }
        }

        if (mem.flightTicks > 20 && mem.flight == BotMemory.Flight.TAKEOFF) {
            end(mem, now);
        }
    }

    private void cruise(Terminator bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        ServerLevel level = bot.getBotLevel();
        ServerPlayer entity = bot.getEntity();
        Vec3 pos = bot.getLocation();
        Vec3 velocity = bot.getVelocity();

        if (target == null || !target.isAlive() || target.level() != level) {
            // nothing to fly to: glide down and land
            steer(bot, entity.getYRot(), 25);
            return;
        }

        Vec3 targetPos = target.position();
        double horizontal = SkillUtil.horizontalDistance(pos, targetPos);
        double ground = SkillUtil.surfaceY(level, pos);
        double desiredY;
        boolean boost = false;
        Float forcedPitch = null;

        if (mem.plan == BotMemory.FlightPlan.MACE) {
            desiredY = Math.max(targetPos.y + 14, ground + 6);
            double height = pos.y - targetPos.y;
            double horizontalSpeed = velocity.horizontalDistance();

            if (height > 6) {
                // where would the bot come down if it let go now? (bots keep their horizontal speed in the air)
                double fallTicks = Math.sqrt(2 * height / 0.08) + 2;
                Vec3 landing = pos.add(velocity.x * fallTicks, 0, velocity.z * fallTicks);

                if (SkillUtil.horizontalDistance(landing, targetPos) < 2.5 || (horizontal < 2.5 && horizontalSpeed < 0.5)) {
                    bot.stopGliding();
                    end(mem, now);
                    skills.mace().startDrop(mem);
                    return;
                }

                // close in: pull up to bleed off speed (and gain height) instead of overshooting
                if (horizontal < 14 && horizontalSpeed > 0.45) {
                    forcedPitch = -65F;
                }
            }
        } else {
            desiredY = horizontal > 20 ? Math.max(targetPos.y + 8, ground + 5) : targetPos.y + 1;

            if (horizontal < 5 && pos.y - targetPos.y < 4) {
                // there: drop in (gently) and fight on foot
                bot.setVelocity(new Vec3(velocity.x * 0.5, Math.max(velocity.y, -0.3), velocity.z * 0.5));
                bot.stopGliding();
                end(mem, now);
                return;
            }
        }

        float pitch = forcedPitch != null ? forcedPitch : (float) Mth.clamp(-(desiredY - pos.y) * 4, -50, 40);

        // don't fly into terrain (diving at the target on the ground is fine though)
        boolean landing = mem.plan == BotMemory.FlightPlan.TRAVEL && horizontal < 20;
        Vec3 eye = entity.getEyePosition();
        Vec3 ahead = velocity.lengthSqr() > 1.0E-4 ? velocity.normalize() : entity.getLookAngle();
        HitResult hit = level.clip(new ClipContext(eye, eye.add(ahead.scale(10)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        boolean obstacle = hit.getType() != HitResult.Type.MISS
                && !(landing && SkillUtil.horizontalDistance(hit.getLocation(), targetPos) < 6);
        if (obstacle || pos.y - ground < (landing ? 1.5 : 3)) {
            pitch = -55;
            boost = true;
        }

        steer(bot, SkillUtil.yawTo(pos, targetPos), pitch);

        double speed = velocity.length();
        boolean needAltitude = desiredY - pos.y > 4 && velocity.y < 0.2;

        if (forcedPitch == null && !bot.isRocketBoosting() && now - mem.lastRocket > 15 && (boost || speed < 0.9 || needAltitude)) {
            if (bot.fireRocket()) {
                mem.lastRocket = now;
            }
        }

        // hit whatever we fly past
        if (now >= mem.nextFlyingHit && SkillUtil.reach(entity, target) < 3.5) {
            bot.attackTarget(target);
            mem.nextFlyingHit = now + 10;
        }

        if (!bot.isRocketBoosting()) {
            bot.setItem(null);
        }
    }

    private static void steer(Terminator bot, float yaw, float pitch) {
        ServerPlayer entity = bot.getEntity();
        float newYaw = Mth.approachDegrees(entity.getYRot(), yaw, 15);
        float newPitch = Mth.approach(entity.getXRot(), pitch, 10);
        bot.setLook(newYaw, newPitch);
    }

    private static void end(BotMemory mem, long now) {
        mem.flight = BotMemory.Flight.NONE;
        mem.nextTakeoff = Math.max(mem.nextTakeoff, now + 60);
    }
}
