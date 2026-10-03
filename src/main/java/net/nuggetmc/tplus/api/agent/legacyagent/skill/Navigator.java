package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;
import org.slf4j.Logger;

import java.util.List;
import java.util.Random;

/**
 * Gets bots over obstacles and out of places where the straight-line legacy movement gets stuck.
 * <p>
 * The legacy agent stays in charge as long as the bot makes progress towards its target. When it doesn't for
 * {@link #STUCK_TICKS}, a recovery is started; each new stuck event escalates to the next strategy:
 * walk a detour found by the vanilla pathfinder, pillar up over the wall, use a nearby ladder/vines, throw an ender
 * pearl, fly with an elytra, or side step. Ladders and vines are also climbed whenever the target is above.
 */
class Navigator {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int STUCK_TICKS = 80;
    private static final float PATH_RANGE = 48;

    private enum Strategy {
        PATH, CLIMB, LADDER, PEARL, ELYTRA, SIDESTEP
    }

    private final BotSkills skills;
    private final Random random = new Random();

    Navigator(BotSkills skills) {
        this.skills = skills;
    }

    boolean tick(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        Vec3 pos = bot.getLocation();
        Vec3 targetPos = target.position();

        trackProgress(bot, mem, targetPos, now);

        // ladders, vines, scaffolding: climb when the target is above
        if (skills.settings().climbing() && bot.isClimbing() && targetPos.y > pos.y + 0.5) {
            climbHere(bot, target);
            return true;
        }

        if (mem.isTowering() && tickTower(bot, mem, now)) return true;
        if (mem.ladder != null && tickLadder(bot, mem, now)) return true;
        if (mem.path != null && tickPath(bot, mem, target, now)) return true;
        if (mem.jiggleUntil > now) {
            tickSidestep(bot, mem);
            return true;
        }

        if (now - mem.lastProgress > STUCK_TICKS && now >= mem.nextRecovery) {
            return startRecovery(bot, mem, target, now);
        }

        return false;
    }

    // ---- progress ---------------------------------------------------------------------------------------------------

    private void trackProgress(Terminator bot, BotMemory mem, Vec3 targetPos, long now) {
        Vec3 pos = bot.getLocation();
        double distance = pos.distanceTo(targetPos);

        if (mem.progressPos == null) {
            mem.progressPos = pos;
            mem.progressDistance = distance;
            mem.lastProgress = now;
            return;
        }

        boolean closer = distance < mem.progressDistance - 1.5 || distance < 3.5;

        if (closer || pos.distanceTo(mem.progressPos) > 2.0) {
            mem.progressPos = pos;
            mem.progressDistance = distance;
            mem.lastProgress = now;

            if (closer) {
                mem.recoveryAttempts = 0;
            }
        }
    }

    // ---- recovery ---------------------------------------------------------------------------------------------------

    private boolean startRecovery(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        mem.nextRecovery = now + 20;
        // give whatever we start now time to work before calling the bot stuck again
        mem.lastProgress = now;

        Strategy[] strategies = Strategy.values();
        int first = mem.recoveryAttempts++;

        for (int i = 0; i < strategies.length; i++) {
            Strategy strategy = strategies[(first + i) % strategies.length];

            if (start(strategy, bot, mem, target, now)) {
                LOGGER.debug("{} is stuck, trying {}", bot.getBotName(), strategy);
                return true;
            }
        }

        return false;
    }

    private boolean start(Strategy strategy, Terminator bot, BotMemory mem, LivingEntity target, long now) {
        return switch (strategy) {
            case PATH -> startPath(bot, mem, target, now);
            case CLIMB -> startTower(bot, mem, target, now);
            case LADDER -> startLadder(bot, mem, target, now);
            case PEARL -> skills.pearls().tryUnstuck(bot, mem, target, now);
            case ELYTRA -> skills.pilot().tryStart(bot, mem, BotMemory.FlightPlan.TRAVEL, now);
            case SIDESTEP -> startSidestep(bot, mem, target, now);
        };
    }

    // ---- detours ----------------------------------------------------------------------------------------------------

    private boolean startPath(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!skills.settings().pathfinding() || now < mem.nextPathSearch) return false;
        mem.nextPathSearch = now + 40;

        List<BlockPos> path = skills.pathfinder().find(bot.getBotLevel(), bot.getEntity(), target.blockPosition(), PATH_RANGE);
        if (path == null) return false;

        // only worth it if it leads somewhere closer than where the bot stands
        Vec3 end = Vec3.atBottomCenterOf(path.get(path.size() - 1));
        double endDistance = end.distanceTo(target.position());
        if (endDistance > 2.5 && endDistance > bot.getLocation().distanceTo(target.position()) - 2) return false;

        mem.clearMovement();
        mem.path = path;
        mem.pathIndex = 1;
        mem.pathDeadline = now + 40 + path.size() * 15L;
        return true;
    }

    private boolean tickPath(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        List<BlockPos> path = mem.path;
        Vec3 pos = bot.getLocation();

        // close enough for the normal combat movement, or the detour took too long
        if (now > mem.pathDeadline || mem.pathIndex >= path.size() || pos.distanceTo(target.position()) < 3.0) {
            mem.path = null;
            return false;
        }

        BlockPos node = path.get(mem.pathIndex);
        Vec3 nodePos = Vec3.atBottomCenterOf(node);

        if (SkillUtil.horizontalDistance(pos, nodePos) < 0.45 && Math.abs(nodePos.y - pos.y) < 1.2) {
            mem.pathIndex++;
            if (mem.pathIndex >= path.size()) {
                mem.path = null;
                return false;
            }
            node = path.get(mem.pathIndex);
            nodePos = Vec3.atBottomCenterOf(node);
        }

        // knocked off the path
        if (SkillUtil.horizontalDistance(pos, nodePos) > 4.5 || nodePos.y - pos.y > 2.5) {
            mem.path = null;
            return false;
        }

        ServerLevel level = bot.getBotLevel();
        openDoor(bot, level, node);
        openDoor(bot, level, node.above());

        float yaw = SkillUtil.yawTo(pos, nodePos);
        bot.setLook(yaw, 0);
        bot.stand();
        bot.setItem(null);

        Vec3 dir = new Vec3(nodePos.x - pos.x, 0, nodePos.z - pos.z);
        dir = dir.lengthSqr() < 1.0E-6 ? Vec3.ZERO : dir.normalize();

        if (bot.isBotInWater()) {
            bot.walk(dir.scale(0.06));
            return true;
        }

        if (!bot.isBotOnGround()) {
            return true;
        }

        if (node.getY() > Mth.floor(pos.y)) {
            bot.jump(new Vec3(dir.x * 0.25, 0.42, dir.z * 0.25));
        } else {
            bot.walk(dir.scale(0.22));
        }

        return true;
    }

    private static void openDoor(Terminator bot, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand() && !state.getValue(DoorBlock.OPEN)) {
            door.setOpen(bot.getEntity(), level, state, pos, true);
            bot.punch();
        } else if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, true), 10);
            level.playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1, 1);
            bot.punch();
        }
    }

    // ---- pillaring --------------------------------------------------------------------------------------------------

    /**
     * Pillar up next to the wall in front (towards the target) until the bot can step onto it, or straight up when the
     * target is right above (out of a pit).
     */
    private boolean startTower(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!skills.settings().climbing() || !bot.isBotOnGround() || bot.isBotInWater()) return false;

        ServerLevel level = bot.getBotLevel();
        ServerPlayer entity = bot.getEntity();
        BlockPos feet = entity.blockPosition();
        Vec3 pos = bot.getLocation();
        Vec3 targetPos = target.position();
        int goal;

        if (targetPos.y > pos.y + 2 && SkillUtil.horizontalDistance(pos, targetPos) < 4) {
            goal = Math.min(Mth.floor(targetPos.y), feet.getY() + 10);
        } else {
            Direction dir = Direction.getNearest(targetPos.x - pos.x, 0, targetPos.z - pos.z);
            BlockPos front = feet.relative(dir);

            int height = 0;
            while (height <= 10 && !SkillUtil.passable(level, front.above(height))) {
                height++;
            }

            // 1 high: a normal hop; taller than 10: not worth it
            if (height < 2 || height > 10) return false;
            // room to stand on top of the wall
            if (!SkillUtil.passable(level, front.above(height + 1))) return false;

            goal = feet.getY() + height - 1;
        }

        for (int y = feet.getY() + 2; y <= goal + 2; y++) {
            if (!SkillUtil.passable(level, new BlockPos(feet.getX(), y, feet.getZ()))) {
                return false;
            }
        }

        mem.clearMovement();
        mem.towerGoalY = goal;
        mem.towerBase = null;
        mem.towerPlaced = false;
        mem.towerDeadline = now + 40 + (goal - feet.getY()) * 20L;
        return true;
    }

    private boolean tickTower(Terminator bot, BotMemory mem, long now) {
        ServerPlayer entity = bot.getEntity();
        ServerLevel level = bot.getBotLevel();
        BlockPos feet = entity.blockPosition();

        if (now > mem.towerDeadline || (bot.isBotOnGround() && feet.getY() >= mem.towerGoalY)) {
            mem.towerGoalY = Integer.MIN_VALUE;
            mem.towerBase = null;
            return false;
        }

        if (bot.isBotOnGround()) {
            if (!SkillUtil.passable(level, feet.above(2))) {
                mem.towerGoalY = Integer.MIN_VALUE;
                return false;
            }

            // stay in the middle of the column
            Vec3 center = Vec3.atBottomCenterOf(feet);
            bot.setVelocity(new Vec3((center.x - entity.getX()) * 0.3, 0, (center.z - entity.getZ()) * 0.3));
            bot.stand();
            bot.look(Direction.DOWN);
            bot.setItem(new ItemStack(skills.settings().buildBlock));
            bot.jump(new Vec3(0, 0.42, 0));

            if (bot.getVelocity().y > 0.4) {
                mem.towerBase = feet;
                mem.towerPlaced = false;
            }
        } else if (!mem.towerPlaced && mem.towerBase != null && entity.getY() >= mem.towerBase.getY() + 1.0) {
            BlockState state = level.getBlockState(mem.towerBase);

            if (state.canBeReplaced()) {
                level.setBlockAndUpdate(mem.towerBase, skills.settings().buildBlock.defaultBlockState());
                level.playSound(null, mem.towerBase, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1, 1);
                bot.punch();
            }

            mem.towerPlaced = true;
        }

        return true;
    }

    // ---- ladders ----------------------------------------------------------------------------------------------------

    private void climbHere(Terminator bot, LivingEntity target) {
        bot.climb();
        bot.stand();
        bot.setItem(null);

        Vec3 pos = bot.getLocation();
        bot.setLook(SkillUtil.yawTo(pos, target.position()), -30);

        // keep pushing towards the target like a player holding forward: that's what gets it over the top edge
        Vec3 toward = new Vec3(target.getX() - pos.x, 0, target.getZ() - pos.z);
        if (toward.lengthSqr() > 1.0E-4) {
            bot.addVelocity(toward.normalize().scale(0.1));
        }
    }

    /**
     * Walk to a ladder/vine column nearby when the target is higher up.
     */
    private boolean startLadder(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!skills.settings().climbing() || target.getY() < bot.getLocation().y + 2) return false;

        ServerLevel level = bot.getBotLevel();
        BlockPos feet = bot.getEntity().blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -6; dx <= 6; dx++) {
            for (int dz = -6; dz <= 6; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = feet.offset(dx, dy, dz);

                    if (SkillUtil.climbable(level, pos) && SkillUtil.climbable(level, pos.above()) && SkillUtil.climbable(level, pos.above(2))) {
                        double distance = dx * dx + dz * dz + dy * dy;
                        if (distance < bestDistance) {
                            bestDistance = distance;
                            best = pos;
                        }
                    }
                }
            }
        }

        if (best == null) return false;

        mem.clearMovement();
        mem.ladder = best;
        mem.ladderDeadline = now + 200;
        return true;
    }

    private boolean tickLadder(Terminator bot, BotMemory mem, long now) {
        if (now > mem.ladderDeadline || bot.isClimbing()) {
            // on it: the climbing check at the top of tick() takes over
            mem.ladder = null;
            return false;
        }

        Vec3 pos = bot.getLocation();
        Vec3 ladderPos = Vec3.atBottomCenterOf(mem.ladder);
        Vec3 dir = new Vec3(ladderPos.x - pos.x, 0, ladderPos.z - pos.z);

        bot.setLook(SkillUtil.yawTo(pos, ladderPos), 0);
        bot.stand();

        if (bot.isBotOnGround() && dir.lengthSqr() > 1.0E-4) {
            dir = dir.normalize();
            if (mem.ladder.getY() > Mth.floor(pos.y)) {
                bot.jump(new Vec3(dir.x * 0.2, 0.42, dir.z * 0.2));
            } else {
                bot.walk(dir.scale(0.2));
            }
        }

        return true;
    }

    // ---- side step --------------------------------------------------------------------------------------------------

    private boolean startSidestep(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        Vec3 pos = bot.getLocation();
        Vec3 toTarget = new Vec3(target.getX() - pos.x, 0, target.getZ() - pos.z);
        toTarget = toTarget.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : toTarget.normalize();

        Vec3 side = new Vec3(-toTarget.z, 0, toTarget.x).scale(random.nextBoolean() ? 1 : -1);

        mem.clearMovement();
        mem.jiggleDir = side.add(toTarget.scale(-0.3)).normalize();
        mem.jiggleUntil = now + 20;
        return true;
    }

    private void tickSidestep(Terminator bot, BotMemory mem) {
        bot.stand();
        if (bot.isBotOnGround() && mem.jiggleDir != null) {
            bot.jump(new Vec3(mem.jiggleDir.x * 0.3, 0.42, mem.jiggleDir.z * 0.3));
        }
    }
}
