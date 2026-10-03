package net.nuggetmc.tplus.api.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;

public class BotUtils {

    public static final Set<Block> NO_FALL = Set.of(
        Blocks.WATER,
        Blocks.LAVA,
        Blocks.TWISTING_VINES,
        Blocks.TWISTING_VINES_PLANT,
        Blocks.WEEPING_VINES,
        Blocks.WEEPING_VINES_PLANT,
        Blocks.SWEET_BERRY_BUSH,
        Blocks.POWDER_SNOW,
        Blocks.COBWEB,
        Blocks.VINE
    );

    /**
     * A random UUID that clients show with the classic Steve skin when the bot has no skin of its own. Since 1.19.3 the
     * client picks one of 18 default skins from the UUID's hash ({@code DefaultPlayerSkin#get}); 15 is the wide Steve.
     * (The plugin's "even hash" rule dates from when there were only Steve and Alex.)
     */
    public static UUID randomSteveUUID() {
        UUID uuid;

        do {
            uuid = UUID.randomUUID();
        } while (Math.floorMod(uuid.hashCode(), 18) != 15);

        return uuid;
    }

    public static boolean overlaps(AABB playerBox, @Nullable AABB blockBox) {
        return blockBox != null && playerBox.intersects(blockBox);
    }

    /**
     * World-space bounds of a block's outline shape, like Bukkit's {@code Block#getBoundingBox()}.
     * Returns {@code null} for blocks without a shape (Bukkit returned an empty box at the origin).
     */
    @Nullable
    public static AABB getBlockBoundingBox(BlockGetter level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);

        if (shape.isEmpty()) {
            return null;
        }

        return shape.bounds().move(pos);
    }

    public static double getHorizSqDist(BlockPos blockLoc, Vec3 pLoc) {
        return MathUtils.square(blockLoc.getX() + 0.5 - pLoc.x) + MathUtils.square(blockLoc.getZ() + 0.5 - pLoc.z);
    }
}
