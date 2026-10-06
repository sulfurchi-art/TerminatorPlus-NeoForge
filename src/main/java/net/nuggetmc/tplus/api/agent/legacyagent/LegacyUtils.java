package net.nuggetmc.tplus.api.agent.legacyagent;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public class LegacyUtils {

    /**
     * Walks the segment between {@code a} and {@code b} in 1/32 block steps and checks that every block on the way is "air"
     * (see {@link LegacyMats#AIR}).
     */
    public static boolean checkFreeSpace(Level world, Vec3 a, Vec3 b) {
        Vec3 v = b.subtract(a);

        int n = 32;
        double m = 1 / (double) n;

        double length = v.length();
        double j = Math.floor(length * n);
        v = length == 0 ? Vec3.ZERO : v.scale(m / length);

        for (int i = 0; i <= j; i++) {
            BlockPos pos = BlockPos.containing(a.add(v.scale(i)));

            if (!LegacyMats.AIR.contains(world.getBlockState(pos).getBlock())) {
                return false;
            }
        }

        return true;
    }

    @Nullable
    public static SoundEvent breakBlockSound(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getSoundType(level, pos, null).getBreakSound();
    }
}
