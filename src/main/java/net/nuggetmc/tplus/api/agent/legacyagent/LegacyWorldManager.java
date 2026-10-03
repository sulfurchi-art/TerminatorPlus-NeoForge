package net.nuggetmc.tplus.api.agent.legacyagent;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class LegacyWorldManager {

    /*
     * This is where the respawning queue will be managed
     */

    public static boolean aboveGround(Level level, Vec3 loc) {
        int y = 1;

        while (y < 25) {
            if (!level.getBlockState(BlockPos.containing(loc.add(0, y, 0))).is(Blocks.AIR)) {
                return false;
            }

            y++;
        }

        return true;
    }
}
