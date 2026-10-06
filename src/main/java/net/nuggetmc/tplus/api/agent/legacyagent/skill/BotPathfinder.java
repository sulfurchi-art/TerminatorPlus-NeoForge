package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Finds walking routes with the vanilla mob pathfinder. Bots aren't mobs, so the search runs through a zombie that is
 * never added to the world: it only serves as the "body" (size, step height, fall distance, door handling) the
 * pathfinder plans for, which is close enough to a player.
 */
public class BotPathfinder {

    private final Map<ServerLevel, Zombie> dummies = new WeakHashMap<>();

    /**
     * @return the blocks to walk through (feet positions), starting at the bot, or {@code null} if there's no useful path
     */
    @Nullable
    public List<BlockPos> find(ServerLevel level, Entity from, BlockPos goal, float range) {
        long perfStart = net.nuggetmc.tplus.utils.PerfProbe.begin();
        try {

        Zombie dummy = dummies.computeIfAbsent(level, BotPathfinder::createDummy);

        dummy.moveTo(from.getX(), from.getY(), from.getZ(), 0, 0);
        dummy.setOnGround(true);

        AttributeInstance followRange = dummy.getAttribute(Attributes.FOLLOW_RANGE);
        if (followRange != null) {
            followRange.setBaseValue(range);
        }

        PathNavigation navigation = dummy.getNavigation();
        navigation.stop();

        Path path = navigation.createPath(goal, 1);

        if (path == null || path.getNodeCount() < 2) {
            return null;
        }

        List<BlockPos> nodes = new ArrayList<>(path.getNodeCount());
        for (int i = 0; i < path.getNodeCount(); i++) {
            nodes.add(path.getNode(i).asBlockPos());
        }

        return nodes;
    
        } finally { net.nuggetmc.tplus.utils.PerfProbe.end(net.nuggetmc.tplus.utils.PerfProbe.PATH, perfStart); }
    }

    public void clear() {
        dummies.clear();
    }

    private static Zombie createDummy(ServerLevel level) {
        Zombie zombie = new Zombie(EntityType.ZOMBIE, level);

        if (zombie.getNavigation() instanceof GroundPathNavigation navigation) {
            navigation.setCanOpenDoors(true);
            navigation.setCanPassDoors(true);
            navigation.setCanFloat(true);
        }

        zombie.getNavigation().setMaxVisitedNodesMultiplier(2.0F);
        // bots swim just fine
        zombie.setPathfindingMalus(PathType.WATER, 1.0F);
        zombie.setPathfindingMalus(PathType.WATER_BORDER, 0.0F);

        return zombie;
    }
}
