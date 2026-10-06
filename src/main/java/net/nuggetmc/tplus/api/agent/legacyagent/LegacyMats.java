package net.nuggetmc.tplus.api.agent.legacyagent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Block classifications used by the legacy agent.
 * <p>
 * The Paper plugin built these from {@code Material} names and BlockData classes. Here the same groups are derived from the
 * vanilla block classes (so e.g. modded fences or walls are picked up automatically) plus the explicit lists of the original.
 * The sets are built lazily from the block registry, so this class must only be touched once the game is running.
 */
public class LegacyMats {

    public static final Set<Block> AIR = blocks(
        Blocks.WATER,
        Blocks.FIRE,
        Blocks.LAVA,
        Blocks.SNOW,
        Blocks.CAVE_AIR,
        Blocks.VINE,
        Blocks.FERN,
        Blocks.LARGE_FERN,
        Blocks.SHORT_GRASS,
        Blocks.TALL_GRASS,
        Blocks.SEAGRASS,
        Blocks.TALL_SEAGRASS,
        Blocks.KELP,
        Blocks.KELP_PLANT,
        Blocks.SUNFLOWER,
        Blocks.AIR,
        Blocks.VOID_AIR,
        Blocks.SOUL_FIRE
    );

    public static final Set<Block> NO_CRACK = blocks(
        Blocks.WATER,
        Blocks.FIRE,
        Blocks.LAVA,
        Blocks.CAVE_AIR,
        Blocks.VOID_AIR,
        Blocks.AIR,
        Blocks.SOUL_FIRE
    );

    public static final Set<Block> BREAK = blocks(
        Blocks.AIR,
        Blocks.WATER,
        Blocks.LAVA,
        Blocks.TALL_GRASS,
        Blocks.CAVE_AIR,
        Blocks.VINE,
        Blocks.FERN,
        Blocks.LARGE_FERN,
        Blocks.SUGAR_CANE,
        Blocks.TWISTING_VINES,
        Blocks.TWISTING_VINES_PLANT,
        Blocks.WEEPING_VINES,
        Blocks.SEAGRASS,
        Blocks.TALL_SEAGRASS,
        Blocks.KELP,
        Blocks.KELP_PLANT,
        Blocks.SUNFLOWER,
        Blocks.TORCHFLOWER,
        Blocks.PITCHER_PLANT,
        Blocks.FIRE,
        Blocks.SOUL_FIRE
    );

    public static final Set<Block> WATER = blocks(
        Blocks.WATER,
        Blocks.SEAGRASS,
        Blocks.TALL_SEAGRASS,
        Blocks.KELP,
        Blocks.KELP_PLANT
    );

    public static final Set<Block> SPAWN = blocks(
        Blocks.AIR,
        Blocks.TALL_GRASS,
        Blocks.SNOW,
        Blocks.CAVE_AIR,
        Blocks.VINE,
        Blocks.FERN,
        Blocks.LARGE_FERN,
        Blocks.SUGAR_CANE,
        Blocks.TWISTING_VINES,
        Blocks.WEEPING_VINES,
        Blocks.SEAGRASS,
        Blocks.TALL_SEAGRASS,
        Blocks.KELP,
        Blocks.KELP_PLANT,
        Blocks.SUNFLOWER,
        Blocks.FIRE,
        Blocks.SOUL_FIRE
    );

    public static final Set<Block> FALL = blocks(
        Blocks.AIR,
        Blocks.TALL_GRASS,
        Blocks.SNOW,
        Blocks.CAVE_AIR,
        Blocks.VINE,
        Blocks.FERN,
        Blocks.LARGE_FERN,
        Blocks.SUGAR_CANE,
        Blocks.TWISTING_VINES,
        Blocks.WEEPING_VINES,
        Blocks.SEAGRASS,
        Blocks.TALL_SEAGRASS,
        Blocks.KELP,
        Blocks.KELP_PLANT,
        Blocks.SUNFLOWER,
        Blocks.WATER
    );

    // Bukkit's Fence/Wall block data: every fence and wall, plus plain glass panes and iron bars.
    public static final Set<Block> FENCE = concat(List.of(Blocks.GLASS_PANE, Blocks.IRON_BARS),
        b -> b instanceof FenceBlock || b instanceof WallBlock);

    public static final Set<Block> GATES = concat(List.of(), b -> b instanceof FenceGateBlock);

    public static final Set<Block> OBSTACLES = concat(List.of(
        Blocks.IRON_BARS,
        Blocks.CHAIN,
        Blocks.END_ROD,
        Blocks.LIGHTNING_ROD,
        Blocks.COBWEB,
        Blocks.SWEET_BERRY_BUSH,
        Blocks.FLOWER_POT,
        Blocks.GLASS_PANE
    ), b -> b instanceof StainedGlassPaneBlock || b instanceof FlowerPotBlock);

    //Notice: We exclude blocks that cannot exist without a solid block below (such as rails or crops)
    public static final Set<Block> NONSOLID = concat(List.of(
        Blocks.COBWEB,
        Blocks.END_GATEWAY,
        Blocks.END_PORTAL,
        Blocks.NETHER_PORTAL,
        Blocks.CAVE_VINES_PLANT,
        Blocks.GLOW_LICHEN,
        Blocks.HANGING_ROOTS,
        Blocks.POWDER_SNOW,
        Blocks.SCULK_VEIN,
        Blocks.TRIPWIRE,
        Blocks.TRIPWIRE_HOOK,
        Blocks.LADDER,
        Blocks.VINE,
        Blocks.SOUL_WALL_TORCH,
        Blocks.REDSTONE_WALL_TORCH,
        Blocks.WALL_TORCH,
        Blocks.WEEPING_VINES_PLANT,
        Blocks.WEEPING_VINES,
        Blocks.CAVE_VINES
    ), b -> b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof BaseCoralWallFanBlock
        || b instanceof WallSignBlock || b instanceof WallBannerBlock);

    public static final Set<Block> LEAVES = concat(List.of(), b -> b instanceof LeavesBlock);

    public static final Set<Block> INSTANT_BREAK = concat(List.of(
        Blocks.TALL_GRASS,
        Blocks.SHORT_GRASS,
        Blocks.FERN,
        Blocks.LARGE_FERN,
        Blocks.KELP_PLANT,
        Blocks.DEAD_BUSH,
        Blocks.WHEAT,
        Blocks.POTATOES,
        Blocks.CARROTS,
        Blocks.BEETROOTS,
        Blocks.PUMPKIN_STEM,
        Blocks.MELON_STEM,
        Blocks.SUGAR_CANE,
        Blocks.SWEET_BERRY_BUSH,
        Blocks.LILY_PAD,
        Blocks.DANDELION,
        Blocks.POPPY,
        Blocks.BLUE_ORCHID,
        Blocks.ALLIUM,
        Blocks.AZURE_BLUET,
        Blocks.RED_TULIP,
        Blocks.ORANGE_TULIP,
        Blocks.WHITE_TULIP,
        Blocks.PINK_TULIP,
        Blocks.OXEYE_DAISY,
        Blocks.CORNFLOWER,
        Blocks.LILY_OF_THE_VALLEY,
        Blocks.WITHER_ROSE,
        Blocks.SUNFLOWER,
        Blocks.LILAC,
        Blocks.ROSE_BUSH,
        Blocks.PEONY,
        Blocks.NETHER_WART,
        Blocks.FLOWER_POT,
        Blocks.AZALEA,
        Blocks.FLOWERING_AZALEA,
        Blocks.REPEATER,
        Blocks.COMPARATOR,
        Blocks.REDSTONE_WIRE,
        Blocks.REDSTONE_TORCH,
        Blocks.REDSTONE_WALL_TORCH,
        Blocks.TORCH,
        Blocks.WALL_TORCH,
        Blocks.SOUL_TORCH,
        Blocks.SOUL_WALL_TORCH,
        Blocks.SCAFFOLDING,
        Blocks.SLIME_BLOCK,
        Blocks.HONEY_BLOCK,
        Blocks.TNT,
        Blocks.TRIPWIRE,
        Blocks.TRIPWIRE_HOOK,
        Blocks.SPORE_BLOSSOM,
        Blocks.RED_MUSHROOM,
        Blocks.BROWN_MUSHROOM,
        Blocks.CRIMSON_FUNGUS,
        Blocks.WARPED_FUNGUS,
        Blocks.CRIMSON_ROOTS,
        Blocks.WARPED_ROOTS,
        Blocks.HANGING_ROOTS,
        Blocks.WEEPING_VINES,
        Blocks.WEEPING_VINES_PLANT,
        Blocks.TWISTING_VINES,
        Blocks.TWISTING_VINES_PLANT,
        Blocks.CAVE_VINES,
        Blocks.CAVE_VINES_PLANT,
        Blocks.SEA_PICKLE
    ), b -> b instanceof SaplingBlock || b instanceof BaseCoralPlantTypeBlock || b instanceof FlowerPotBlock);

    private static Set<Block> blocks(Block... blocks) {
        return Set.copyOf(Arrays.asList(blocks));
    }

    private static Set<Block> concat(List<Block> blocks, Predicate<Block> filter) {
        Set<Block> set = new HashSet<>(blocks);
        BuiltInRegistries.BLOCK.stream().filter(filter).forEach(set::add);
        return Collections.unmodifiableSet(set);
    }

    private static boolean isWaterlogged(BlockState state) {
        return state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED);
    }

    private static boolean isCarpet(Block block) {
        return block instanceof CarpetBlock;
    }

    private static boolean isPotted(Block block) {
        return block instanceof FlowerPotBlock && block != Blocks.FLOWER_POT;
    }

    private static boolean isHead(Block block) {
        return block instanceof AbstractSkullBlock;
    }

    private static boolean isGlassPane(Block block) {
        return block == Blocks.GLASS_PANE || block instanceof StainedGlassPaneBlock;
    }

    /**
     * Checks for non-solid blocks that can hold an entity up.
     */
    public static boolean canStandOn(Block mat) {
        if (mat == Blocks.END_ROD || mat == Blocks.FLOWER_POT || mat == Blocks.REPEATER || mat == Blocks.COMPARATOR
            || mat == Blocks.SNOW || mat == Blocks.LADDER || mat == Blocks.VINE || mat == Blocks.SCAFFOLDING
            || mat == Blocks.AZALEA || mat == Blocks.FLOWERING_AZALEA || mat == Blocks.BIG_DRIPLEAF
            || mat == Blocks.CHORUS_FLOWER || mat == Blocks.CHORUS_PLANT || mat == Blocks.COCOA
            || mat == Blocks.LILY_PAD || mat == Blocks.SEA_PICKLE)
            return true;

        if (isCarpet(mat))
            return true;

        if (isPotted(mat))
            return true;

        if (isHead(mat))
            return true;

        return mat instanceof CandleBlock;
    }

    /**
     * @param entityYPos the y position of the falling entity, if known
     */
    public static boolean canPlaceWater(BlockGetter level, BlockPos pos, OptionalDouble entityYPos) {
        BlockState state = level.getBlockState(pos);
        Block type = state.getBlock();

        if (isSolid(type)) {
            if (type == Blocks.CHAIN && state.getValue(ChainBlock.AXIS) == Direction.Axis.Y && !isWaterlogged(state))
                return false;
            if ((type instanceof LeavesBlock || type == Blocks.MANGROVE_ROOTS || type == Blocks.IRON_BARS || isGlassPane(type))
                && !isWaterlogged(state))
                return false;
            if (type instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.TOP && !isWaterlogged(state))
                return false;
            if (type instanceof StairBlock && state.getValue(StairBlock.HALF) == Half.TOP && !isWaterlogged(state))
                return false;
            if (type instanceof StairBlock && state.getValue(StairBlock.HALF) == Half.BOTTOM && !isWaterlogged(state)
                && (entityYPos.isEmpty() || Mth.floor(entityYPos.getAsDouble()) != pos.getY()))
                return false;
            if ((type instanceof FenceBlock || type instanceof WallBlock) && !isWaterlogged(state))
                return false;
            if (type == Blocks.LIGHTNING_ROD && !isWaterlogged(state)
                && state.getValue(LightningRodBlock.FACING).getAxis() == Direction.Axis.Y)
                return false;
            if (type instanceof TrapDoorBlock && (state.getValue(TrapDoorBlock.HALF) == Half.TOP
                || (state.getValue(TrapDoorBlock.HALF) == Half.BOTTOM && state.getValue(TrapDoorBlock.OPEN)))
                && !isWaterlogged(state))
                return false;
            return true;
        } else {
            if (isCarpet(type))
                return true;
            if (type instanceof CandleBlock)
                return true;
            if (isPotted(type))
                return true;
            if (isHead(type))
                return true;
            return type == Blocks.SNOW
                || type == Blocks.AZALEA
                || type == Blocks.FLOWERING_AZALEA
                || type == Blocks.CHORUS_FLOWER
                || type == Blocks.CHORUS_PLANT
                || type == Blocks.COCOA
                || type == Blocks.LILY_PAD
                || type == Blocks.SEA_PICKLE
                || type == Blocks.END_ROD
                || type == Blocks.FLOWER_POT
                || type == Blocks.SCAFFOLDING
                || type == Blocks.COMPARATOR
                || type == Blocks.REPEATER;
        }
    }

    private static final Set<Block> NO_VINES = blocks(
        Blocks.POINTED_DRIPSTONE,
        Blocks.SMALL_AMETHYST_BUD,
        Blocks.MEDIUM_AMETHYST_BUD,
        Blocks.LARGE_AMETHYST_BUD,
        Blocks.AMETHYST_CLUSTER,
        Blocks.BAMBOO,
        Blocks.CACTUS,
        Blocks.DRAGON_EGG,
        Blocks.TURTLE_EGG,
        Blocks.CHAIN,
        Blocks.IRON_BARS,
        Blocks.LANTERN,
        Blocks.SOUL_LANTERN,
        Blocks.ANVIL,
        Blocks.BREWING_STAND,
        Blocks.CHEST,
        Blocks.ENDER_CHEST,
        Blocks.TRAPPED_CHEST,
        Blocks.ENCHANTING_TABLE,
        Blocks.GRINDSTONE,
        Blocks.LECTERN,
        Blocks.STONECUTTER,
        Blocks.BELL,
        Blocks.CAKE,
        Blocks.CAMPFIRE,
        Blocks.SOUL_CAMPFIRE,
        Blocks.CAULDRON,
        Blocks.COMPOSTER,
        Blocks.CONDUIT,
        Blocks.END_PORTAL_FRAME,
        Blocks.FARMLAND,
        Blocks.DAYLIGHT_DETECTOR,
        Blocks.HONEY_BLOCK,
        Blocks.HOPPER,
        Blocks.LIGHTNING_ROD,
        Blocks.SCULK_SENSOR,
        Blocks.SCULK_SHRIEKER
    );

    public static boolean canPlaceTwistingVines(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block type = state.getBlock();

        if (isSolid(type)) {
            if (type instanceof LeavesBlock)
                return false;
            if (type instanceof BaseCoralPlantTypeBlock)
                return false;
            if (isGlassPane(type))
                return false;
            if (type instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM)
                return false;
            if (type instanceof StairBlock && state.getValue(StairBlock.HALF) == Half.BOTTOM)
                return false;
            if (type instanceof FenceBlock || type instanceof WallBlock)
                return false;
            if (type instanceof AbstractBannerBlock)
                return false;
            if (type instanceof BedBlock)
                return false;
            if (type instanceof CandleCakeBlock)
                return false;
            if (type instanceof DoorBlock)
                return false;
            if (type instanceof FenceGateBlock)
                return false;
            if (type == Blocks.PISTON_HEAD && state.getValue(PistonHeadBlock.FACING) != Direction.UP)
                return false;
            if (type instanceof PistonBaseBlock && state.getValue(PistonBaseBlock.FACING) != Direction.DOWN
                && state.getValue(PistonBaseBlock.EXTENDED))
                return false;
            if (type instanceof TrapDoorBlock && (state.getValue(TrapDoorBlock.HALF) == Half.BOTTOM
                || state.getValue(TrapDoorBlock.OPEN)))
                return false;
            return !NO_VINES.contains(type);
        } else {
            if (type == Blocks.CHORUS_FLOWER || type == Blocks.SCAFFOLDING || type == Blocks.AZALEA || type == Blocks.FLOWERING_AZALEA)
                return true;
            if (type == Blocks.SNOW)
                return state.getValue(SnowLayerBlock.LAYERS) == 1 || state.getValue(SnowLayerBlock.LAYERS) == 8;
        }
        return false;
    }

    private static final Set<Block> REPLACE = blocks(
        Blocks.POINTED_DRIPSTONE,
        Blocks.SMALL_AMETHYST_BUD,
        Blocks.MEDIUM_AMETHYST_BUD,
        Blocks.LARGE_AMETHYST_BUD,
        Blocks.AMETHYST_CLUSTER,
        Blocks.SEA_PICKLE,
        Blocks.LANTERN,
        Blocks.SOUL_LANTERN,
        Blocks.CHEST,
        Blocks.ENDER_CHEST,
        Blocks.TRAPPED_CHEST,
        Blocks.CAMPFIRE,
        Blocks.SOUL_CAMPFIRE,
        Blocks.CONDUIT,
        Blocks.LIGHTNING_ROD,
        Blocks.SCULK_SENSOR,
        Blocks.SCULK_SHRIEKER
    );

    public static boolean shouldReplace(BlockGetter level, BlockPos pos, double entityYPos, boolean nether) {
        if (Mth.floor(entityYPos) != pos.getY())
            return false;
        if (nether)
            return false;

        BlockState state = level.getBlockState(pos);
        Block type = state.getBlock();

        if (type instanceof BaseCoralPlantTypeBlock)
            return true;
        if (type instanceof SlabBlock && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM)
            return true;
        if (type instanceof StairBlock && !isWaterlogged(state))
            return true;
        if (type instanceof ChainBlock && !isWaterlogged(state))
            return true;
        if (type instanceof CandleBlock)
            return true;
        if (type instanceof TrapDoorBlock && !isWaterlogged(state))
            return true;
        return REPLACE.contains(type);
    }

    /**
     * This set stores solid blocks that are added by mods (see {@code /botenvironment addSolid}).
     */
    public static final Set<Block> SOLID_MATERIALS = ConcurrentHashMap.newKeySet();

    /**
     * Same definition as Bukkit's {@code Material#isSolid()}: the default state of the block blocks motion.
     */
    @SuppressWarnings("deprecation")
    public static boolean isSolid(Block mat) {
        return mat.defaultBlockState().blocksMotion() || SOLID_MATERIALS.contains(mat);
    }
}
