package net.nuggetmc.tplus.api.agent.legacyagent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.scheduler.TaskScheduler;
import net.nuggetmc.tplus.api.utils.BotUtils;

import java.util.*;

public class LegacyBlockCheck {

    private final LegacyAgent agent;
    private final TaskScheduler scheduler;

    public LegacyBlockCheck(LegacyAgent agent, TaskScheduler scheduler) {
        this.agent = agent;
        this.scheduler = scheduler;
    }

    private static Block type(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock();
    }

    private static void playPlaceSound(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1, 1);
    }

    private void placeFinal(Terminator bot, LivingEntity player, BlockPos loc) {
        ServerLevel level = bot.getBotLevel();

        if (type(level, loc) != agent.buildBlock()) {
            playPlaceSound(level, loc);
            bot.setItem(new ItemStack(agent.buildBlock()));
            level.setBlockAndUpdate(loc, agent.buildBlock().defaultBlockState());

            BlockPos under = loc.below();
            if (type(level, under) == Blocks.LAVA) {
                level.setBlockAndUpdate(under, agent.buildBlock().defaultBlockState());
            }
        }
    }

    public void placeBlock(Terminator bot, LivingEntity player, BlockPos block) {
        ServerLevel level = bot.getBotLevel();

        BlockPos under = block.below();

        if (LegacyMats.SPAWN.contains(type(level, under))) {
            placeFinal(bot, player, under);
            scheduler.runTaskLater(() -> placeFinal(bot, player, block), 2);
        }

        List<BlockPos> face = List.of(block.east(), block.west(), block.south(), block.north());

        boolean a = false;
        for (BlockPos side : face) {
            if (!LegacyMats.SPAWN.contains(type(level, side))) {
                a = true;
            }
        }

        if (a) {
            placeFinal(bot, player, block);
            return;
        }

        List<BlockPos> edge = List.of(under.east(), under.west(), under.south(), under.north());

        boolean b = false;
        for (BlockPos side : edge) {
            if (!LegacyMats.SPAWN.contains(type(level, side))) {
                b = true;
            }
        }

        if (b && LegacyMats.SPAWN.contains(type(level, under))) {
            placeFinal(bot, player, under);
            scheduler.runTaskLater(() -> placeFinal(bot, player, block), 2);
            return;
        }

        BlockPos c1 = block.offset(1, -1, 1);
        BlockPos c2 = block.offset(1, -1, -1);
        BlockPos c3 = block.offset(-1, -1, 1);
        BlockPos c4 = block.offset(-1, -1, -1);

        boolean t = false;

        if (!LegacyMats.SPAWN.contains(type(level, c1)) || !LegacyMats.SPAWN.contains(type(level, c2))) {

            BlockPos b1 = block.offset(1, -1, 0);
            if (LegacyMats.SPAWN.contains(type(level, b1))) {
                placeFinal(bot, player, b1);
            }

            t = true;

        } else if (!LegacyMats.SPAWN.contains(type(level, c3)) || !LegacyMats.SPAWN.contains(type(level, c4))) {

            BlockPos b1 = block.offset(-1, -1, 0);
            if (LegacyMats.SPAWN.contains(type(level, b1))) {
                placeFinal(bot, player, b1);
            }

            t = true;
        }

        if (t) {
            scheduler.runTaskLater(() -> {
                BlockPos b2 = block.below();
                if (LegacyMats.SPAWN.contains(type(level, b2))) {
                    playPlaceSound(level, block);
                    placeFinal(bot, player, b2);
                }
            }, 1);

            scheduler.runTaskLater(() -> {
                playPlaceSound(level, block);
                placeFinal(bot, player, block);
            }, 3);
            return;
        }

        playPlaceSound(level, block);
        placeFinal(bot, player, block);
    }

    public boolean tryPreMLG(Terminator bot, Vec3 botLoc) {
        if (bot.isBotOnGround() || bot.getVelocity().y >= -0.8D || bot.getNoFallTicks() > 7)
            return false;
        if (tryPreMLG(bot, botLoc, 3))
            return true;
        return tryPreMLG(bot, botLoc, 2);
    }

    private boolean tryPreMLG(Terminator bot, Vec3 botLoc, int blocksBelow) {
        ServerLevel level = bot.getBotLevel();
        AABB box = bot.getBotBoundingBox();
        double[] xVals = new double[]{
                box.minX,
                box.maxX - 0.01
        };

        double[] zVals = new double[]{
                box.minZ,
                box.maxZ - 0.01
        };
        Set<BlockPos> below2Set = new HashSet<>();
        int botBlockY = Mth.floor(bot.getLocation().y);

        for (double x : xVals) {
            for (double z : zVals) {
                double belowY = botBlockY;
                for (int i = 0; i < blocksBelow - 1; i++) {
                    belowY -= 1;

                    // Blocks before must all be pass-through
                    Block type = type(level, BlockPos.containing(x, belowY, z));
                    if (LegacyMats.isSolid(type) || LegacyMats.canStandOn(type))
                        return false;
                }
                below2Set.add(BlockPos.containing(x, botBlockY - blocksBelow, z));
            }
        }

        // Second block below must have at least one unplaceable block (that is landable)
        boolean nether = bot.isInNether();
        Iterator<BlockPos> itr = below2Set.iterator();
        while (itr.hasNext()) {
            BlockPos next = itr.next();
            Block type = type(level, next);
            boolean placeable = nether ? LegacyMats.canPlaceTwistingVines(level, next)
                    : LegacyMats.canPlaceWater(level, next, OptionalDouble.empty());
            if (placeable || (!LegacyMats.isSolid(type) && !LegacyMats.canStandOn(type)))
                itr.remove();
        }

        // Clutch
        if (!below2Set.isEmpty()) {
            List<BlockPos> below2List = new ArrayList<>(below2Set);
            below2List.sort((a, b) -> {
                boolean aAir = level.getBlockState(a.above()).isAir();
                boolean bAir = level.getBlockState(b.above()).isAir();
                if (aAir && !bAir)
                    return -1;
                if (!aAir && bAir)
                    return 1;
                return Double.compare(BotUtils.getHorizSqDist(a, botLoc), BotUtils.getHorizSqDist(b, botLoc));
            });

            BlockPos faceBlock = below2List.get(0);
            Vec3 faceLoc = Vec3.atLowerCornerOf(faceBlock);
            BlockPos loc = faceBlock.above();
            bot.faceLocation(faceLoc);
            bot.look(Direction.DOWN);

            scheduler.runTaskLater(() -> bot.faceLocation(faceLoc), 1);

            bot.punch();
            playPlaceSound(level, loc);
            bot.setItem(new ItemStack(agent.buildBlock()));
            level.setBlockAndUpdate(loc, agent.buildBlock().defaultBlockState());
            return true;
        }

        return false;
    }

    public void clutch(Terminator bot, LivingEntity target) {
        ServerLevel level = bot.getBotLevel();
        BlockPos botBlock = BlockPos.containing(bot.getLocation());

        Block type = type(level, botBlock.below());
        Block type2 = type(level, botBlock.below(2));

        if (!(LegacyMats.SPAWN.contains(type) && LegacyMats.SPAWN.contains(type2))) return;

        if (target.blockPosition().getY() >= botBlock.getY()) {
            BlockPos loc = botBlock.below();

            List<BlockPos> face = List.of(loc.east(), loc.west(), loc.south(), loc.north());

            BlockPos at = null;
            for (BlockPos side : face) {
                if (!LegacyMats.SPAWN.contains(type(level, side))) {
                    at = side;
                }
            }

            if (at != null) {
                agent.slow.add(bot);
                agent.noFace.add(bot);

                scheduler.runTaskLater(() -> {
                    bot.stand();
                    agent.slow.remove(bot);
                }, 12);

                scheduler.runTaskLater(() -> agent.noFace.remove(bot), 15);

                Vec3 faceLoc = Vec3.atLowerCornerOf(at).add(0, -1.5, 0);

                bot.faceLocation(faceLoc);
                bot.look(Direction.DOWN);

                scheduler.runTaskLater(() -> bot.faceLocation(faceLoc), 1);

                bot.punch();
                bot.sneak();
                playPlaceSound(level, loc);
                bot.setItem(new ItemStack(agent.buildBlock()));
                level.setBlockAndUpdate(loc, agent.buildBlock().defaultBlockState());
            }
        }
    }
}
