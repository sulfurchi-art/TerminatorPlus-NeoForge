package net.nuggetmc.tplus.api.agent.legacyagent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.BotData;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.BotNode;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.BotSkills;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.SkillSettings;
import net.nuggetmc.tplus.api.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.api.event.BotDeathEvent;
import net.nuggetmc.tplus.api.event.BotFallDamageEvent;
import net.nuggetmc.tplus.api.event.TerminatorLocateTargetEvent;
import net.nuggetmc.tplus.api.scheduler.TickTask;
import net.nuggetmc.tplus.api.utils.BotUtils;
import net.nuggetmc.tplus.api.utils.MathUtils;
import net.nuggetmc.tplus.api.utils.PlayerUtils;

import javax.annotation.Nullable;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

// Yes, this code is very unoptimized, I know.
public class LegacyAgent extends Agent {

    private static final Pattern NAME_PATTERN = Pattern.compile("[^A-Za-z]+");
    public final Set<Terminator> noFace = new HashSet<>();
    public final Set<LivingEntity> noJump = new HashSet<>();
    public final Set<Terminator> slow = new HashSet<>();
    private final LegacyBlockCheck blockCheck;
    private final Map<LivingEntity, TickTask> miningAnim = new HashMap<>();
    private final Set<Boat> boats = new HashSet<>();
    private final Map<LivingEntity, BlockPos> btList = new HashMap<>();
    private final Map<LivingEntity, Boolean> btCheck = new HashMap<>();
    private final Map<LivingEntity, Vec3> towerList = new HashMap<>();
    private final Set<Terminator> boatCooldown = new HashSet<>();
    private final Map<GlobalPos, Short> crackList = new HashMap<>();
    private final Map<TickTask, Byte> mining = new HashMap<>();
    private final Set<Terminator> fallDamageCooldown = new HashSet<>();
    public boolean offsets = true;
    private EnumTargetGoal goal;
    private AABB region;
    private double regionWeightX;
    private double regionWeightY;
    private double regionWeightZ;

    public static final Set<EntityType<?>> CUSTOM_MOB_LIST = ConcurrentHashMap.newKeySet();
    public static CustomListMode customListMode = CustomListMode.CUSTOM;

    private final SkillSettings skillSettings = new SkillSettings();
    private final BotSkills skills;

    public LegacyAgent(BotManager manager) {
        super(manager);

        this.goal = EnumTargetGoal.NEAREST_VULNERABLE_PLAYER;
        this.blockCheck = new LegacyBlockCheck(this, scheduler);
        this.skills = new BotSkills(manager, skillSettings);
    }

    public SkillSettings getSkillSettings() {
        return skillSettings;
    }

    public BotSkills getSkills() {
        return skills;
    }

    /**
     * What bots build with ({@code /bot settings buildblock}).
     */
    public Block buildBlock() {
        return skillSettings.buildBlock;
    }

    private static boolean checkSideBreak(Block type) {
        return !LegacyMats.BREAK.contains(type);// && !LegacyMats.LEAVES.contains(type);
    }

    private static Block type(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getBlock();
    }

    private static Block type(ServerLevel level, Vec3 pos) {
        return level.getBlockState(BlockPos.containing(pos)).getBlock();
    }

    /**
     * Center of the block column the entity stands in, at the entity's own height.
     */
    private static Vec3 blockCenter(Entity entity) {
        return new Vec3(Mth.floor(entity.getX()) + 0.5, entity.getY(), Mth.floor(entity.getZ()) + 0.5);
    }

    @Override
    protected void tick() {
        manager.fetch().forEach(this::tickBot);
    }

    @Override
    public void onBotRemoved(Terminator bot) {
        ServerPlayer entity = bot.getEntity();

        TickTask task = miningAnim.remove(entity);
        if (task != null) {
            task.cancel();
        }

        noFace.remove(bot);
        noJump.remove(entity);
        slow.remove(bot);
        btList.remove(entity);
        btCheck.remove(entity);
        towerList.remove(entity);
        boatCooldown.remove(bot);
        fallDamageCooldown.remove(bot);
        skills.forget(bot);
    }

    private void center(Terminator bot) {
        if (bot == null || !bot.isBotAlive()) {
            return;
        }

        final LivingEntity botEntity = bot.getEntity();

        BlockPos prev = btList.get(botEntity);
        BlockPos loc = botEntity.blockPosition();

        if (prev != null) {
            btCheck.put(botEntity, loc.getX() == prev.getX() && loc.getZ() == prev.getZ());
        }

        btList.put(botEntity, loc);
    }

    private void tickBot(Terminator bot) {
        if (!bot.isBotAlive()) {
            return;
        }

        if (bot.tickDelay(20)) {
            center(bot);
        }

        ServerPlayer botPlayer = bot.getEntity();
        ServerLevel world = bot.getBotLevel();
        Vec3 loc = bot.getLocation();
        LivingEntity livingTarget = locateTarget(bot, loc);

        // flying, mace dives, void pearls: these steer the bot on their own while they last
        if (skills.tickActive(bot, livingTarget)) {
            stopMining(bot);
            return;
        }

        blockCheck.tryPreMLG(bot, loc);

        if (livingTarget == null) {
            stopMining(bot);
            return;
        }

        if (skills.tryStart(bot, livingTarget)) {
            stopMining(bot);
            return;
        }

        blockCheck.clutch(bot, livingTarget);

        fallDamageCheck(bot);
        miscellaneousChecks(bot, livingTarget);

        // the bot may have died or been removed during the checks above
        if (!bot.isBotAlive()) {
            return;
        }

        Vec3 target = offsets ? livingTarget.position().add(bot.getOffset()) : livingTarget.position();

        boolean ai = bot.hasNeuralNetwork();

        NeuralNetwork network = ai ? bot.getNeuralNetwork() : null;

        if (ai) {
            network.feed(BotData.generate(bot, livingTarget));
        }

        if (bot.tickDelay(3) && !miningAnim.containsKey(botPlayer)) {
            Vec3 botEyeLoc = botPlayer.getEyePosition();
            Vec3 playerEyeLoc = livingTarget.getEyePosition();
            Vec3 playerLoc = livingTarget.position();

            if (ai) {
                if (network.check(BotNode.BLOCK) && loc.distanceTo(livingTarget.position()) < 6) {
                    bot.block(10, 10);
                }
            }

            if (LegacyUtils.checkFreeSpace(world, botEyeLoc, playerEyeLoc) || LegacyUtils.checkFreeSpace(world, botEyeLoc, playerLoc)) {
                attack(bot, livingTarget, loc);
            }
        }

        // ladders, detours, pillaring over walls... when the straight line doesn't work
        if (skills.tickNavigation(bot, livingTarget)) {
            stopMining(bot);
            return;
        }

        boolean waterGround = (LegacyMats.WATER.contains(type(world, loc.add(0, -0.1, 0)))
                && !LegacyMats.AIR.contains(type(world, loc.add(0, -0.6, 0))));

        boolean withinTargetXZ = false, sameXZ = false;

        if (btCheck.containsKey(botPlayer)) sameXZ = btCheck.get(botPlayer);

        if (waterGround || bot.isBotOnGround() || onBoat(botPlayer)) {
            byte sideResult = 1;

            if (towerList.containsKey(botPlayer)) {
                if (Mth.floor(loc.y) > livingTarget.blockPosition().getY()) {
                    towerList.remove(botPlayer);
                    resetHand(bot, livingTarget, botPlayer);
                }
            }

            BlockPos block = BlockPos.containing(loc.add(0, 1, 0));
            BlockPos targetBlock = BlockPos.containing(target);

            if (Math.abs(Mth.floor(loc.x) - targetBlock.getX()) <= 3 &&
                    Math.abs(Mth.floor(loc.z) - targetBlock.getZ()) <= 3) {
                withinTargetXZ = true;
            }

            boolean bothXZ = withinTargetXZ || sameXZ;

            if (checkAt(bot, block, botPlayer)) return;

            if (checkFenceAndGates(bot, BlockPos.containing(loc), botPlayer)) return;

            if (checkObstacles(bot, BlockPos.containing(loc), botPlayer)) return;

            if (checkDown(bot, botPlayer, livingTarget.position(), bothXZ)) return;

            if ((withinTargetXZ || sameXZ) && checkUp(bot, livingTarget, botPlayer, target, withinTargetXZ, sameXZ)) return;

            if (bothXZ) sideResult = checkSide(bot, livingTarget, botPlayer);

            switch (sideResult) {
                case 1:
                    resetHand(bot, livingTarget, botPlayer);
                    if (!noJump.contains(botPlayer) && !waterGround) move(bot, livingTarget, loc, target, ai);
                    return;

                case 2:
                    if (!waterGround) move(bot, livingTarget, loc, target, ai);
            }
        } else if (LegacyMats.WATER.contains(type(world, loc))) {
            swim(bot, target, botPlayer, livingTarget, LegacyMats.WATER.contains(type(world, loc.add(0, -1, 0))));
        }
    }

    private void move(Terminator bot, LivingEntity livingTarget, Vec3 loc, Vec3 target, boolean ai) {
        Vec3 vel = target.subtract(loc).normalize();

        if (bot.tickDelay(5)) bot.faceLocation(livingTarget.position());
        if (!bot.isBotOnGround()) return; // calling this a second time later on

        bot.stand(); // eventually create a memory system so packets do not have to be sent every tick
        bot.setItem(null); // method to check item in main hand, bot.getItemInHand()

        vel = vel.add(bot.getVelocity());

        if (MathUtils.isNotFinite(vel)) {
            vel = MathUtils.clean(vel);
        }

        if (vel.length() > 1) vel = vel.normalize();

        double distance = loc.distanceTo(target);

        if (distance <= 5) {
            vel = vel.scale(0.3);
        } else {
            vel = vel.scale(0.4);
        }

        if (slow.contains(bot)) {
            vel = MathUtils.withY(vel, 0).scale(0.5);
        } else {
            vel = MathUtils.withY(vel, 0.4);
        }

        vel = MathUtils.withY(vel, vel.y - Math.random() * 0.05);

        if (ai) {
            NeuralNetwork network = bot.getNeuralNetwork();

            if (network.dynamicLR()) {
                if (bot.isBotBlocking()) {
                    vel = vel.scale(0.6);
                }

                if (distance <= 6) {

                    // positive y rotation means left, negative means right
                    // if left > right, value will be positive

                    double value = network.value(BotNode.LEFT) - network.value(BotNode.RIGHT);

                    vel = MathUtils.rotateAroundY(vel, value * Math.PI / 8);

                    if (network.check(BotNode.JUMP)) {
                        bot.jump(vel);
                    } else {
                        bot.walk(MathUtils.withY(vel, 0));
                        final Vec3 jumpVel = vel;
                        scheduler.runTaskLater(() -> bot.jump(jumpVel), 10);
                    }

                    return;
                }
            } else {
                boolean left = network.check(BotNode.LEFT);
                boolean right = network.check(BotNode.RIGHT);

                if (bot.isBotBlocking()) {
                    vel = vel.scale(0.6);
                }

                if (left != right && distance <= 6) {

                    if (left) {
                        vel = MathUtils.rotateAroundY(vel, Math.PI / 4);
                    }

                    if (right) {
                        vel = MathUtils.rotateAroundY(vel, -Math.PI / 4);
                    }

                    if (network.check(BotNode.JUMP)) {
                        bot.jump(vel);
                    } else {
                        bot.walk(MathUtils.withY(vel, 0));
                        final Vec3 jumpVel = vel;
                        scheduler.runTaskLater(() -> bot.jump(jumpVel), 10);
                    }

                    return;
                }
            }
        }

        bot.jump(vel);
    }

    private void fallDamageCheck(Terminator bot) {
        if (bot.isBotFalling()) {
            bot.look(Direction.DOWN);

            Item itemType;

            if (bot.isInNether()) {
                itemType = Items.TWISTING_VINES;
            } else {
                itemType = Items.WATER_BUCKET;
            }

            bot.setItem(new ItemStack(itemType));
        }
    }

    @Override
    public void onBotDeath(BotDeathEvent event) {
        if (!drops) {
            event.getDrops().clear();
        }
    }

    @Override
    public void onPlayerDamage(BotDamageByPlayerEvent event) {
        Terminator bot = event.getBot();
        Vec3 loc = bot.getLocation();
        Player player = event.getPlayer();

        double dot = loc.subtract(player.position()).normalize().dot(bot.getEntity().getLookAngle());

        if (bot.isBotBlocking() && dot >= -0.1) {
            bot.getBotLevel().playSound(null, loc.x, loc.y, loc.z, SoundEvents.SHIELD_BLOCK, SoundSource.MASTER, 1, 1);
            event.setCancelled(true);
        }
    }

    @Override
    public void onFallDamage(BotFallDamageEvent event) {
        Terminator bot = event.getBot();
        ServerLevel world = bot.getBotLevel();

        bot.look(Direction.DOWN);

        Item itemType;
        Block placeType;
        SoundEvent sound;
        BlockPos groundLoc = null;
        boolean nether = bot.isInNether();
        double yPos = bot.getEntity().getY();

        if (nether) {
            itemType = Items.TWISTING_VINES;
            sound = SoundEvents.WEEPING_VINES_PLACE;
            placeType = Blocks.TWISTING_VINES;

            for (BlockPos block : event.getStandingOn()) {
                if (LegacyMats.canPlaceTwistingVines(world, block)) {
                    groundLoc = block;
                    break;
                }
            }
        } else {
            itemType = Items.WATER_BUCKET;
            sound = SoundEvents.BUCKET_EMPTY;
            placeType = Blocks.WATER;

            for (BlockPos block : event.getStandingOn()) {
                if (LegacyMats.canPlaceWater(world, block, OptionalDouble.of(yPos))) {
                    groundLoc = block;
                    break;
                }
            }
        }

        if (groundLoc == null) return;

        BlockPos loc = !LegacyMats.shouldReplace(world, groundLoc, yPos, nether) ? groundLoc.above() : groundLoc;
        BlockState locState = world.getBlockState(loc);
        boolean waterloggable = !nether && locState.hasProperty(BlockStateProperties.WATERLOGGED);
        boolean waterlogged = waterloggable && locState.getValue(BlockStateProperties.WATERLOGGED);

        event.setCancelled(true);

        if (locState.getBlock() != placeType && !waterlogged) {
            bot.punch();
            if (waterloggable) {
                world.setBlockAndUpdate(loc, locState.setValue(BlockStateProperties.WATERLOGGED, true));
            } else {
                world.setBlockAndUpdate(loc, placeType.defaultBlockState());
            }
            world.playSound(null, loc, sound, SoundSource.MASTER, 1, 1);

            if (itemType == Items.WATER_BUCKET) {
                bot.setItem(new ItemStack(Items.BUCKET));

                scheduler.runTaskLater(() -> {
                    BlockState state = world.getBlockState(loc);

                    boolean waterloggedNow = !nether && state.hasProperty(BlockStateProperties.WATERLOGGED)
                            && state.getValue(BlockStateProperties.WATERLOGGED);
                    if (state.is(Blocks.WATER) || waterloggedNow) {
                        bot.look(Direction.DOWN);
                        bot.setItem(new ItemStack(Items.WATER_BUCKET));
                        world.playSound(null, loc, SoundEvents.BUCKET_FILL, SoundSource.MASTER, 1, 1);
                        if (waterloggedNow) {
                            world.setBlockAndUpdate(loc, state.setValue(BlockStateProperties.WATERLOGGED, false));
                        } else {
                            world.setBlockAndUpdate(loc, Blocks.AIR.defaultBlockState());
                        }
                    }
                }, 5);
            }
        }
    }

    private void swim(Terminator bot, Vec3 loc, LivingEntity playerNPC, LivingEntity target, boolean anim) {
        playerNPC.setShiftKeyDown(false);

        Vec3 at = bot.getLocation();

        Vec3 vector = loc.subtract(at);
        if (Mth.floor(at.y) < target.blockPosition().getY()) {
            vector = MathUtils.withY(vector, 0);
        }

        vector = vector.normalize().scale(0.05);
        vector = MathUtils.withY(vector, vector.y * 1.2);

        TickTask task = miningAnim.remove(playerNPC);
        if (task != null) {
            task.cancel();
        }

        if (anim) {
            bot.swim();
        } else {
            vector = MathUtils.withY(vector, 0).scale(0.7);
        }

        bot.faceLocation(target.position());
        bot.addVelocity(vector);
    }

    private void stopMining(Terminator bot) {
        TickTask task = miningAnim.remove(bot.getEntity());
        if (task != null) {
            task.cancel();
        }
    }

    private byte checkSide(Terminator npc, LivingEntity target, LivingEntity playerNPC) {  // make it so they don't jump when checking side
        Vec3 a = playerNPC.getEyePosition();
        Vec3 b = target.position().add(0, 1, 0);

        if (npc.getLocation().distanceTo(target.position()) < 2.9 && LegacyUtils.checkFreeSpace(npc.getBotLevel(), a, b)) {
            resetHand(npc, target, playerNPC);
            return 1;
        }

        LegacyLevel level = checkNearby(target, npc);

        if (level == null) {
            resetHand(npc, target, playerNPC);
            return 1;
        } else if (level.isSide() || level == LegacyLevel.BELOW || level == LegacyLevel.ABOVE) {
            return 0;
        } else {
            return 2;
        }
    }

    private record Hit(BlockPos block, @Nullable LegacyLevel level) {
    }

    /**
     * The per-direction part of {@link #checkNearby}: look at the block in front of the bot (head height, then feet, then
     * a fence below) and fall back to breaking the block the bot stands on or the one above it.
     */
    private Hit checkFacing(Terminator npc, ServerPlayer player, int dx, int dz,
                            LegacyLevel side, LegacyLevel sideDown, LegacyLevel sideDown2, LegacyLevel sideUp) {
        ServerLevel world = npc.getBotLevel();
        BlockPos get = player.blockPosition().offset(dx, 1, dz);

        if (checkSideBreak(type(world, get))) {
            return new Hit(get, side);
        } else if (checkSideBreak(type(world, get.below()))) {
            return new Hit(get.below(), sideDown);
        } else if (LegacyMats.FENCE.contains(type(world, get.below(2)))) {
            return new Hit(get.below(2), sideDown2);
        }

        BlockPos standing = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
        if (standing == null) {
            return new Hit(get, null);
        }

        int playerY = player.blockPosition().getY();
        Block standingType = type(world, standing);
        boolean obstructed = standing.getY() == playerY
                || (standing.getY() + 1 == playerY
                && (LegacyMats.FENCE.contains(standingType) || LegacyMats.GATES.contains(standingType)));

        if (obstructed) {
            Block belowStanding = type(world, standing.below());
            if (!LegacyMats.BREAK.contains(belowStanding) && !LegacyMats.NONSOLID.contains(belowStanding)) {
                //Break standing block
                return new Hit(standing, LegacyLevel.BELOW);
            } else {
                //Break above
                BlockPos above = npc.getEntity().blockPosition().above(2);
                BlockPos aboveSide = get.above();
                if (!LegacyMats.BREAK.contains(type(world, above))) {
                    return new Hit(above, LegacyLevel.ABOVE);
                } else if (!LegacyMats.BREAK.contains(type(world, aboveSide))) {
                    return new Hit(aboveSide, sideUp);
                }
            }
        }

        return new Hit(get, null);
    }

    private LegacyLevel checkNearby(LivingEntity target, Terminator npc) {
        ServerPlayer player = npc.getEntity();
        ServerLevel world = npc.getBotLevel();

        npc.faceLocation(target.position());

        Direction dir = player.getDirection();
        LegacyLevel level = null;
        BlockPos get = null;

        AABB box = player.getBoundingBox();
        double[] xVals = new double[]{
                box.minX,
                box.maxX - 0.01
        };

        double[] zVals = new double[]{
                box.minZ,
                box.maxZ - 0.01
        };
        int blockY = Mth.floor(npc.getLocation().y);
        List<BlockPos> locStanding = new ArrayList<>();
        for (double x : xVals) {
            for (double z : zVals) {
                BlockPos loc = new BlockPos(Mth.floor(x), blockY, Mth.floor(z));
                if (!locStanding.contains(loc))
                    locStanding.add(loc);
            }
        }
        Vec3 playerLoc = player.position();
        locStanding.sort((a, b) ->
                Double.compare(BotUtils.getHorizSqDist(a, playerLoc), BotUtils.getHorizSqDist(b, playerLoc)));

        //Break potential obstructing walls
        BlockPos playerBlock = player.blockPosition();
        for (BlockPos loc : locStanding) {
            boolean up = false;
            get = loc;
            if (!LegacyMats.FENCE.contains(type(world, get))) {
                up = true;
                get = loc.above();
                if (!LegacyMats.FENCE.contains(type(world, get))) {
                    get = null;
                }
            }

            if (get != null) {
                int distanceX = get.getX() - playerBlock.getX();
                int distanceZ = get.getZ() - playerBlock.getZ();
                if (distanceX == 1 && distanceZ == 0) {
                    if (dir == Direction.NORTH || dir == Direction.SOUTH) {
                        npc.faceLocation(Vec3.atLowerCornerOf(get));
                        level = up ? LegacyLevel.EAST : LegacyLevel.EAST_D;
                    }
                } else if (distanceX == -1 && distanceZ == 0) {
                    if (dir == Direction.NORTH || dir == Direction.SOUTH) {
                        npc.faceLocation(Vec3.atLowerCornerOf(get));
                        level = up ? LegacyLevel.WEST : LegacyLevel.WEST_D;
                    }
                } else if (distanceX == 0 && distanceZ == 1) {
                    if (dir == Direction.EAST || dir == Direction.WEST) {
                        npc.faceLocation(Vec3.atLowerCornerOf(get));
                        level = up ? LegacyLevel.SOUTH : LegacyLevel.SOUTH_D;
                    }
                } else if (distanceX == 0 && distanceZ == -1) {
                    if (dir == Direction.EAST || dir == Direction.WEST) {
                        npc.faceLocation(Vec3.atLowerCornerOf(get));
                        level = up ? LegacyLevel.NORTH : LegacyLevel.NORTH_D;
                    }
                }

                if (level != null) {
                    preBreak(npc, player, get, level);
                    return level;
                }
            }
        }

        Hit hit = switch (dir) {
            case NORTH -> checkFacing(npc, player, 0, -1, LegacyLevel.NORTH, LegacyLevel.NORTH_D, LegacyLevel.NORTH_D_2, LegacyLevel.NORTH_U);
            case SOUTH -> checkFacing(npc, player, 0, 1, LegacyLevel.SOUTH, LegacyLevel.SOUTH_D, LegacyLevel.SOUTH_D_2, LegacyLevel.SOUTH_U);
            case EAST -> checkFacing(npc, player, 1, 0, LegacyLevel.EAST, LegacyLevel.EAST_D, LegacyLevel.EAST_D_2, LegacyLevel.EAST_U);
            case WEST -> checkFacing(npc, player, -1, 0, LegacyLevel.WEST, LegacyLevel.WEST_D, LegacyLevel.WEST_D_2, LegacyLevel.WEST_U);
            default -> null;
        };

        if (hit != null) {
            get = hit.block();
            level = hit.level();
        }

        if (level == LegacyLevel.EAST_D || level == LegacyLevel.WEST_D || level == LegacyLevel.NORTH_D || level == LegacyLevel.SOUTH_D
                || level == LegacyLevel.EAST_D_2 || level == LegacyLevel.WEST_D_2 || level == LegacyLevel.NORTH_D_2 || level == LegacyLevel.SOUTH_D_2) {
            if (LegacyMats.AIR.contains(type(world, playerBlock.above(2)))
                    && LegacyMats.AIR.contains(type(world, get.above(2)))
                    && !LegacyMats.FENCE.contains(type(world, get)) && !LegacyMats.GATES.contains(type(world, get))) {
                return null;
            }
        }
        if (level == LegacyLevel.ABOVE || level == LegacyLevel.BELOW) {
            BlockPos check = switch (dir) {
                case NORTH -> playerBlock.offset(0, 2, -1);
                case SOUTH -> playerBlock.offset(0, 2, 1);
                case EAST -> playerBlock.offset(1, 2, 0);
                case WEST -> playerBlock.offset(-1, 2, 0);
                default -> null;
            };
            if (check != null && LegacyMats.AIR.contains(type(world, playerBlock.above(2)))
                    && LegacyMats.AIR.contains(type(world, check)))
                return null;
        }

        if (level != null) {
            if (level == LegacyLevel.BELOW) {
                noJump.add(player);
                scheduler.runTaskLater(() -> noJump.remove(player), 15);

                npc.look(Direction.DOWN);
                downMine(npc, player, get);
            } else if (level == LegacyLevel.ABOVE)
                npc.look(Direction.UP);
            preBreak(npc, player, get, level);
        }

        return level;
    }

    private boolean checkUp(Terminator npc, LivingEntity target, LivingEntity playerNPC, Vec3 loc, boolean c, boolean sameXZ) {
        ServerLevel world = npc.getBotLevel();

        Vec3 a = MathUtils.withY(playerNPC.position(), 0);
        Vec3 b = MathUtils.withY(target.position(), 0);

        boolean above = LegacyWorldManager.aboveGround(world, playerNPC.position());

        Direction dir = playerNPC.getDirection();
        BlockPos npcBlock = playerNPC.blockPosition();
        BlockPos get = switch (dir) {
            case NORTH -> npcBlock.offset(0, 1, -1);
            case SOUTH -> npcBlock.offset(0, 1, 1);
            case EAST -> npcBlock.offset(1, 1, 0);
            case WEST -> npcBlock.offset(-1, 1, 0);
            default -> null;
        };

        if (get == null || LegacyMats.BREAK.contains(type(world, get))) {
            if (a.distanceTo(b) >= 16 && above) return false;
        }

        if (npcBlock.getY() < target.blockPosition().getY() - 1) {
            Block m0 = type(world, npcBlock);
            Block m1 = type(world, npcBlock.above());
            Block m2 = type(world, npcBlock.above(2));

            if (LegacyMats.BREAK.contains(m0) && LegacyMats.BREAK.contains(m1) && LegacyMats.BREAK.contains(m2)) {

                npc.setItem(new ItemStack(buildBlock()));

                BlockPos place = npcBlock;

                TickTask task = miningAnim.remove(playerNPC);
                if (task != null) {
                    task.cancel();
                }

                npc.look(Direction.DOWN);

                // maybe put this in lower if statement onGround()
                if (m0 != Blocks.WATER)
                    scheduler.runTaskLater(() -> {
                        npc.sneak();
                        npc.setItem(new ItemStack(buildBlock()));
                        npc.punch();
                        npc.look(Direction.DOWN);

                        scheduler.runTaskLater(() -> npc.look(Direction.DOWN), 1);

                        blockCheck.placeBlock(npc, playerNPC, place);

                        if (!towerList.containsKey(playerNPC)) {
                            if (c) {
                                towerList.put(playerNPC, playerNPC.position());
                            }
                        }
                    }, 3);

                if (npc.isBotOnGround()) {
                    if (target.position().distanceTo(playerNPC.position()) < 16) {
                        if (noJump.contains(playerNPC)) {

                            scheduler.runTaskLater(() -> npc.setVelocity(new Vec3(0, 0.5, 0)), 1);

                        } else {
                            Vec3 vector = loc.subtract(playerNPC.position()).normalize();
                            npc.stand();

                            Vec3 move = npc.getVelocity().add(vector);
                            if (move.length() > 1) move = move.normalize();
                            move = move.scale(0.1);
                            move = MathUtils.withY(move, 0.5);

                            npc.setVelocity(move);
                            return true;
                        }
                    } else {
                        if (npc.isBotOnGround()) {
                            Vec3 vector = blockCenter(playerNPC).subtract(playerNPC.position());
                            if (vector.length() > 1) vector = vector.normalize();
                            vector = vector.scale(0.1);
                            vector = MathUtils.withY(vector, 0.5);

                            npc.addVelocity(vector);
                            return true;
                        }
                    }
                }

                return false;

            } else if (LegacyMats.BREAK.contains(m0) && LegacyMats.BREAK.contains(m1) && !LegacyMats.BREAK.contains(m2)) {
                BlockPos block = npc.getEntity().blockPosition().above(2);
                npc.look(Direction.UP);
                preBreak(npc, playerNPC, block, LegacyLevel.ABOVE);

                if (npc.isBotOnGround()) {
                    Vec3 vector = blockCenter(playerNPC).subtract(playerNPC.position());
                    if (vector.length() > 1) vector = vector.normalize();
                    vector = vector.scale(0.1);
                    vector = MathUtils.withY(vector, 0);

                    npc.addVelocity(vector);
                }

                return true;
            } else if (sameXZ && LegacyMats.BREAK.contains(m1)) {
                BlockPos block = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
                if (block != null && block.getY() == npcBlock.getY()
                        && !LegacyMats.BREAK.contains(type(world, block))) {
                    npc.look(Direction.DOWN);

                    downMine(npc, playerNPC, block);
                    preBreak(npc, playerNPC, block, LegacyLevel.BELOW);
                    return true;
                }
            }
        }

        return false;
    }

    private boolean checkDown(Terminator npc, LivingEntity player, Vec3 loc, boolean c) { // possibly a looser check for c
        ServerLevel world = npc.getBotLevel();

        if (LegacyUtils.checkFreeSpace(world, npc.getLocation(), loc) || LegacyUtils.checkFreeSpace(world, player.getEyePosition(), loc))
            return false;

        int npcBlockY = Mth.floor(npc.getLocation().y);
        int targetBlockY = Mth.floor(loc.y);

        if (c && npcBlockY > targetBlockY + 1) {
            BlockPos block = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
            if (block == null)
                return false;
            npc.look(Direction.DOWN);

            downMine(npc, player, block);
            preBreak(npc, player, block, LegacyLevel.BELOW);
            return true;
        } else {
            Vec3 a = MathUtils.withY(loc, 0);
            Vec3 b = MathUtils.withY(player.position(), 0);

            if (npcBlockY > targetBlockY + 10 && a.distanceTo(b) < 10) {
                BlockPos block = npc.getStandingOn().isEmpty() ? null : npc.getStandingOn().get(0);
                if (block == null)
                    return false;
                npc.look(Direction.DOWN);

                downMine(npc, player, block);
                preBreak(npc, player, block, LegacyLevel.BELOW);
                return true;

            } else {
                return false;
            }
        }
    }

    private void downMine(Terminator npc, LivingEntity player, BlockPos block) {
        if (!LegacyMats.NO_CRACK.contains(type(npc.getBotLevel(), block))) {
            Vec3 vector = blockCenter(player).subtract(player.position());
            if (vector.length() > 1) vector = vector.normalize();
            vector = MathUtils.withY(vector, 0);
            vector = vector.scale(0.1);
            npc.setVelocity(vector);
        }

        if (npc.isBotInWater()) {
            Vec3 vector = blockCenter(player).subtract(player.position());
            if (vector.length() > 1) vector = vector.normalize();
            vector = vector.scale(0.3);
            vector = MathUtils.withY(vector, -1);

            if (!fallDamageCooldown.contains(npc)) {
                fallDamageCooldown.add(npc);

                scheduler.runTaskLater(() -> fallDamageCooldown.remove(npc), 10);
            }

            npc.setVelocity(vector);
        }
    }

    private boolean checkFenceAndGates(Terminator bot, BlockPos block, LivingEntity player) {
        Block type = type(bot.getBotLevel(), block);

        if (LegacyMats.FENCE.contains(type) || LegacyMats.GATES.contains(type)) {
            preBreak(bot, player, block, LegacyLevel.AT_D);
            return true;
        }

        return false;
    }

    private boolean checkObstacles(Terminator bot, BlockPos block, LivingEntity player) {
        if (LegacyMats.OBSTACLES.contains(type(bot.getBotLevel(), block)) || isDoorObstacle(bot.getBotLevel(), block)) {
            preBreak(bot, player, block, LegacyLevel.AT_D);
            return true;
        }

        return false;
    }

    private boolean isDoorObstacle(ServerLevel world, BlockPos block) {
        BlockState state = world.getBlockState(block);
        if (state.getBlock() instanceof DoorBlock)
            return true;
        return state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN);
    }

    private boolean checkAt(Terminator bot, BlockPos block, LivingEntity player) {
        if (LegacyMats.BREAK.contains(type(bot.getBotLevel(), block))) {
            return false;
        } else {
            preBreak(bot, player, block, LegacyLevel.AT);
            return true;
        }
    }

    private void preBreak(Terminator bot, LivingEntity player, BlockPos block, LegacyLevel level) {
        List<Item> materials = List.of(LegacyItems.PICKAXE, LegacyItems.AXE, LegacyItems.SHOVEL);
        BlockState state = bot.getBotLevel().getBlockState(block);
        ItemStack optimal = ItemStack.EMPTY;
        float optimalSpeed = 1;

        for (Item mat : materials) {
            ItemStack tool = new ItemStack(mat);
            float destroySpeed = tool.getDestroySpeed(state);

            if (destroySpeed > optimalSpeed) {
                optimal = tool;
                optimalSpeed = destroySpeed;
            }
        }

        bot.setItem(optimal);

        if (level.isSideDown() || level.isSideDown2()) {
            bot.setBotPitch(69);

            scheduler.runTaskLater(() -> btCheck.put(player, true), 5);
        } else if (level.isSideUp()) {
            bot.setBotPitch(-53);
        } else if (level == LegacyLevel.AT_D || level == LegacyLevel.AT) {
            Vec3 blockLoc = Vec3.atLowerCornerOf(block).add(0.5, -1, 0.5);
            bot.faceLocation(blockLoc);
        }

        if (!miningAnim.containsKey(player)) {

            TickTask task = new TickTask() {

                @Override
                public void run() {
                    bot.punch();
                }
            };

            scheduler.runTaskTimer(task, 0, 4);
            taskList.add(task);
            miningAnim.put(player, task);
        }

        blockBreakEffect(bot, player, block, new LegacyLevel.LevelWrapper(level));
    }

    private static boolean isUnbreakable(ServerLevel world, BlockPos pos, BlockState state) {
        Block type = state.getBlock();
        return type == Blocks.BARRIER || type == Blocks.BEDROCK || type == Blocks.END_PORTAL_FRAME
                || type == Blocks.STRUCTURE_BLOCK || type == Blocks.COMMAND_BLOCK || type == Blocks.REPEATING_COMMAND_BLOCK
                || type == Blocks.CHAIN_COMMAND_BLOCK
                // anything else that survival players can't break either (jigsaw, reinforced deepslate, modded bedrock...)
                || state.getDestroySpeed(world, pos) < 0;
    }

    private void blockBreakEffect(Terminator bot, LivingEntity player, BlockPos block, LegacyLevel.LevelWrapper wrapper) {
        ServerLevel world = bot.getBotLevel();

        if (LegacyMats.NO_CRACK.contains(type(world, block))) return;

        GlobalPos key = GlobalPos.of(world.dimension(), block);

        if (!crackList.containsKey(key)) {
            TickTask task = new TickTask() {

                @Override
                public void run() {
                    byte i = mining.get(this);

                    BlockPos cur;
                    if (wrapper.getLevel() == null)
                        cur = player.blockPosition().above();
                    else if (wrapper.getLevel() == LegacyLevel.BELOW)
                        cur = bot.getStandingOn().isEmpty() ? null : bot.getStandingOn().get(0);
                    else
                        cur = wrapper.getLevel().offset(player.blockPosition());

                    // Fix boat clutching while breaking block
                    // As a side effect, the bot is able to break multiple blocks at once while over lava
                    if ((wrapper.getLevel().isSideAt() || wrapper.getLevel().isSideUp())
                            && type(world, bot.getLocation().add(0, -2, 0)) == Blocks.LAVA
                            && block.above().equals(cur)) {
                        cur = block;
                        wrapper.setLevel(wrapper.getLevel().sideDown());

                        if (wrapper.getLevel().isSideDown() || wrapper.getLevel().isSideDown2())
                            bot.setBotPitch(69);
                        else if (wrapper.getLevel().isSideUp())
                            bot.setBotPitch(-53);
                        else if (wrapper.getLevel().isSide())
                            bot.setBotPitch(0);
                    }
                    if ((wrapper.getLevel().isSideAt() || wrapper.getLevel().isSideDown())
                            && type(world, bot.getLocation().add(0, -1, 0)) == Blocks.LAVA
                            && block.below().equals(cur)) {
                        cur = block;
                        wrapper.setLevel(wrapper.getLevel().sideUp());

                        if (wrapper.getLevel().isSideDown() || wrapper.getLevel().isSideDown2())
                            bot.setBotPitch(69);
                        else if (wrapper.getLevel().isSideUp())
                            bot.setBotPitch(-53);
                        else if (wrapper.getLevel().isSide())
                            bot.setBotPitch(0);
                    }

                    // wow this repeated code is so bad lmao

                    if (!player.isAlive() || player.isRemoved() || cur == null || !block.equals(cur)) {
                        this.cancel();

                        sendBlockDestructionPacket(crackList.get(key), world, block, -1);

                        crackList.remove(key);
                        mining.remove(this);
                        return;
                    }

                    BlockState state = world.getBlockState(block);
                    SoundEvent sound = LegacyUtils.breakBlockSound(world, block);

                    if (i == 9) {
                        this.cancel();

                        sendBlockDestructionPacket(crackList.get(key), world, block, -1);

                        // destroyBlock plays the break sound and particles itself
                        world.destroyBlock(block, true, player);
                        skills.onBlockBroken(bot);

                        if (wrapper.getLevel() == LegacyLevel.ABOVE) {
                            noJump.add(player);

                            scheduler.runTaskLater(() -> noJump.remove(player), 15);
                        }

                        crackList.remove(key);
                        mining.remove(this);
                        return;
                    }

                    if (sound != null) {
                        world.playSound(null, block, sound, SoundSource.BLOCKS, 0.3f, 1);
                    }

                    if (isUnbreakable(world, block, state)) {
                        // can't dig through this one: let the skills find another way
                        skills.onUnbreakable(bot);
                        return;
                    }

                    if (LegacyMats.INSTANT_BREAK.contains(state.getBlock())) { // instant break blocks
                        world.destroyBlock(block, true, player);
                        return;
                    }

                    sendBlockDestructionPacket(crackList.get(key), world, block, i);

                    mining.put(this, (byte) (i + 1));
                }
            };

            taskList.add(task);
            mining.put(task, (byte) 0);
            crackList.put(key, (short) random.nextInt(2000));
            scheduler.runTaskTimer(task, 0, 2);
        }
    }

    /**
     * Shows the block cracking animation to every player within 64 blocks.
     */
    private static void sendBlockDestructionPacket(@Nullable Short id, ServerLevel world, BlockPos pos, int progress) {
        if (id == null) return;

        ClientboundBlockDestructionPacket crack = new ClientboundBlockDestructionPacket(id, pos, progress);

        for (ServerPlayer viewer : world.players()) {
            if (!(viewer instanceof Terminator) && viewer.distanceToSqr(Vec3.atCenterOf(pos)) <= 64 * 64) {
                viewer.connection.send(crack);
            }
        }
    }

    private void placeWaterDown(Terminator bot, ServerLevel world, BlockPos loc) {
        if (world.getBlockState(loc).is(Blocks.WATER)) return;

        bot.look(Direction.DOWN);
        bot.punch();
        world.setBlockAndUpdate(loc, Blocks.WATER.defaultBlockState());
        world.playSound(null, loc, SoundEvents.BUCKET_EMPTY, SoundSource.MASTER, 1, 1);
        bot.setItem(new ItemStack(Items.BUCKET));

        scheduler.runTaskLater(() -> {
            if (world.getBlockState(loc).is(Blocks.WATER)) {
                bot.look(Direction.DOWN);
                bot.setItem(new ItemStack(Items.WATER_BUCKET));
                world.playSound(null, loc, SoundEvents.BUCKET_FILL, SoundSource.MASTER, 1, 1);
                world.setBlockAndUpdate(loc, Blocks.AIR.defaultBlockState());
            }
        }, 5);
    }

    private void extinguish(Terminator bot, ServerLevel world, BlockPos pos) {
        bot.look(Direction.DOWN);
        bot.punch();
        world.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1, 1);
        world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
    }

    private void miscellaneousChecks(Terminator bot, LivingEntity target) {
        ServerPlayer botPlayer = bot.getEntity();
        ServerLevel world = bot.getBotLevel();
        Vec3 loc = bot.getLocation();
        BlockPos at = BlockPos.containing(loc);
        boolean nether = bot.isInNether();

        if (bot.isBotOnFire()) {
            if (!nether) {
                placeWaterDown(bot, world, at);
            }
        }

        Block atType = type(world, at);

        if (atType == Blocks.FIRE || atType == Blocks.SOUL_FIRE) {
            if (!nether) {
                placeWaterDown(bot, world, at);
                world.playSound(null, at, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1, 1);
            } else {
                extinguish(bot, world, at);
            }
        }

        if (atType == Blocks.LAVA) {
            if (nether) {
                bot.attemptBlockPlace(at, buildBlock(),false);
            } else {
                placeWaterDown(bot, world, at);
            }
        }

        BlockPos head = at.above();
        Block headType = type(world, head);

        if (headType == Blocks.LAVA) {
            if (nether) {
                bot.attemptBlockPlace(head, buildBlock(),false);
            } else {
                placeWaterDown(bot, world, head);
            }
        }

        if (headType == Blocks.FIRE || headType == Blocks.SOUL_FIRE) {
            if (nether) {
                extinguish(bot, world, head);
            } else {
                placeWaterDown(bot, world, head);
            }
        }

        BlockPos under = at.below();
        Block underType = type(world, under);

        if (underType == Blocks.FIRE || underType == Blocks.SOUL_FIRE) {
            extinguish(bot, world, under);
        }

        BlockPos under2 = at.below(2);
        Block under2Type = type(world, under2);

        if (under2Type == Blocks.MAGMA_BLOCK) {
            if (LegacyMats.SPAWN.contains(under2Type)) {
                bot.attemptBlockPlace(under2, buildBlock(),true);
            }
        }

        if (botPlayer.blockPosition().getY() <= target.blockPosition().getY() + 1) {
            if (!miningAnim.containsKey(botPlayer)) {
                double y = bot.getVelocity().y;

                if (y >= -0.6) {
                    if (type(world, loc.add(0, -0.6, 0)) == Blocks.WATER
                            && !LegacyMats.NO_CRACK.contains(under2Type)
                            && world.getBlockState(BlockPos.containing(botPlayer.getEyePosition())).isAir()) {

                        BlockPos place = BlockPos.containing(loc.add(0, -1, 0));
                        if (LegacyMats.WATER.contains(type(world, place))) {
                            bot.attemptBlockPlace(place, buildBlock(),true);
                        }
                    }
                }
            }
        }

        underType = type(world, loc.add(0, -0.6, 0));

        if (underType == Blocks.LAVA) {
            if (!boatCooldown.contains(bot)) {
                boatCooldown.add(bot);

                Vec3 place = loc.add(0, -0.1, 0);

                bot.setItem(new ItemStack(Items.OAK_BOAT));
                bot.look(Direction.DOWN);
                bot.punch();

                Boat boat = new Boat(world, place.x, place.y, place.z);
                boat.setYRot(botPlayer.getYRot());
                world.addFreshEntity(boat);

                scheduler.runTaskLater(() -> {
                    if (boat.isAlive()) {
                        boats.remove(boat);
                        boat.discard();
                    }
                }, 20);

                scheduler.runTaskLater(() -> bot.look(Direction.DOWN), 1);

                boats.add(boat);

                Vec3 targetLoc = target.position();

                bot.stand();
                Vec3 vector = targetLoc.subtract(bot.getLocation()).normalize();
                vector = vector.scale(0.8);

                Vec3 move = MathUtils.withY(bot.getVelocity().add(vector), 0);
                if (move.length() > 1) move = move.normalize();
                move = move.scale(0.5);
                move = MathUtils.withY(move, 0.42);
                bot.setVelocity(move);

                scheduler.runTaskLater(() -> {
                    boatCooldown.remove(bot);
                    if (bot.isBotAlive()) {
                        bot.faceLocation(target.position());
                    }
                }, 5);
            }
        }
    }

    private void resetHand(Terminator npc, LivingEntity target, LivingEntity playerNPC) {
        if (!noFace.contains(npc)) { // LESSLAG if there is no if statement here
            npc.faceLocation(target.position());
        }

        TickTask task = miningAnim.remove(playerNPC);
        if (task != null) {
            task.cancel();
        }

        if (boatCooldown.contains(npc)) return;

        npc.setItem(null);
    }

    private boolean onBoat(LivingEntity player) {
        Set<Boat> cache = new HashSet<>();

        boolean check = false;

        for (Boat boat : boats) {
            if (player.level() != boat.level()) continue;

            if (!boat.isAlive()) {
                cache.add(boat);
                continue;
            }

            if (player.position().distanceTo(boat.position()) < 1) {
                check = true;
                break;
            }
        }

        boats.removeAll(cache);

        return check;
    }

    private void attack(Terminator bot, LivingEntity target, Vec3 loc) {
        if ((target instanceof Player player && PlayerUtils.isInvincible(player)) || target.invulnerableTime >= 5 || loc.distanceTo(target.position()) >= 4)
            return;

        bot.attackTarget(target);
    }

    public void setRegion(@Nullable AABB region, double regionWeightX, double regionWeightY, double regionWeightZ) {
        this.region = region;
        this.regionWeightX = regionWeightX;
        this.regionWeightY = regionWeightY;
        this.regionWeightZ = regionWeightZ;
    }

    @Nullable
    public AABB getRegion() {
        return region;
    }

    public double getRegionWeightX() {
        return regionWeightX;
    }

    public double getRegionWeightY() {
        return regionWeightY;
    }

    public double getRegionWeightZ() {
        return regionWeightZ;
    }

    public EnumTargetGoal getTargetType() {
        return goal;
    }

    public void setTargetType(EnumTargetGoal goal) {
        this.goal = goal;
    }

    /**
     * Real players only: bots that were added to the player list are skipped.
     */
    private Iterable<ServerPlayer> realPlayers() {
        return manager.getServer().getPlayerList().getPlayers().stream().filter(p -> !(p instanceof Terminator)).toList();
    }

    /**
     * Living entities in the bot's level, limited to the target range if one is set.
     */
    private Iterable<LivingEntity> livingEntities(ServerLevel world, LivingEntity self) {
        double range = skillSettings.targetRange;

        if (range > 0) {
            return world.getEntitiesOfClass(LivingEntity.class, self.getBoundingBox().inflate(range), entity -> entity != self);
        }

        List<LivingEntity> list = new ArrayList<>();
        for (Entity entity : world.getAllEntities()) {
            if (entity instanceof LivingEntity living && living != self) {
                list.add(living);
            }
        }
        return list;
    }

    /**
     * Whoever hit the bot in the last 5 seconds, if it can be fought back.
     */
    @Nullable
    private LivingEntity retaliationTarget(Terminator bot, ServerLevel world, Vec3 loc) {
        ServerPlayer self = bot.getEntity();
        LivingEntity attacker = self.getLastHurtByMob();

        if (attacker == null || attacker == self || !attacker.isAlive() || attacker.level() != world
                || self.tickCount - self.getLastHurtByMobTimestamp() > 100) {
            return null;
        }

        if (attacker instanceof Player player && PlayerUtils.isInvincible(player)) {
            return null;
        }

        double range = skillSettings.targetRange;
        if (range > 0 && loc.distanceToSqr(attacker.position()) > range * range) {
            return null;
        }

        return attacker;
    }

    @Nullable
    private LivingEntity locateTarget(Terminator bot, Vec3 loc, EnumTargetGoal... targetGoal) {
        LivingEntity result = null;
        ServerLevel world = bot.getBotLevel();

        EnumTargetGoal g = goal;
        if (targetGoal.length > 0) g = targetGoal[0];

        LivingEntity revenge = g != EnumTargetGoal.NONE && skillSettings.retaliate() ? retaliationTarget(bot, world, loc) : null;

        switch (g) {
            default:
                return null;

            case NEAREST_PLAYER: {
                for (ServerPlayer player : realPlayers()) {
                    if (validateCloserEntity(player, world, loc, result)) {
                        result = player;
                    }
                }

                break;
            }

            case NEAREST_VULNERABLE_PLAYER: {
                for (ServerPlayer player : realPlayers()) {
                    if (!PlayerUtils.isInvincible(player) && validateCloserEntity(player, world, loc, result)) {
                        result = player;
                    }
                }

                break;
            }

            case NEAREST_HOSTILE: {
                for (LivingEntity entity : livingEntities(world, bot.getEntity())) {
                    // Enemy also covers hostiles that aren't Monster subclasses: slimes, phantoms, ghasts, shulkers, hoglins...
                    if ((entity instanceof Enemy || (customListMode == CustomListMode.HOSTILE && CUSTOM_MOB_LIST.contains(entity.getType()))) && validateCloserEntity(entity, world, loc, result)) {
                        result = entity;
                    }
                }

                break;
            }

            case NEAREST_RAIDER: {
                for (LivingEntity entity : livingEntities(world, bot.getEntity())) {
                    boolean raider = entity instanceof Raider || (entity instanceof Vex vex && vex.getOwner() instanceof Raider);
                    if ((raider || (customListMode == CustomListMode.RAIDER && CUSTOM_MOB_LIST.contains(entity.getType()))) && validateCloserEntity(entity, world, loc, result)) {
                        result = entity;
                    }
                }

                break;
            }

            case NEAREST_MOB: {
                for (LivingEntity entity : livingEntities(world, bot.getEntity())) {
                    if ((entity instanceof Mob || (customListMode == CustomListMode.MOB && CUSTOM_MOB_LIST.contains(entity.getType()))) && validateCloserEntity(entity, world, loc, result)) {
                        result = entity;
                    }
                }

                break;
            }

            case NEAREST_BOT: {
                for (Terminator otherBot : manager.fetch()) {
                    if (bot != otherBot) {
                        LivingEntity player = otherBot.getEntity();

                        if (validateCloserEntity(player, world, loc, result)) {
                            result = player;
                        }
                    }
                }

                break;
            }

            case NEAREST_BOT_DIFFER: {
                String name = bot.getBotName();

                for (Terminator otherBot : manager.fetch()) {
                    if (bot != otherBot) {
                        LivingEntity player = otherBot.getEntity();

                        if (!name.equals(otherBot.getBotName()) && validateCloserEntity(player, world, loc, result)) {
                            result = player;
                        }
                    }
                }

                break;
            }

            case NEAREST_BOT_DIFFER_ALPHA: {
                String name = NAME_PATTERN.matcher(bot.getBotName()).replaceAll("");

                for (Terminator otherBot : manager.fetch()) {
                    if (bot != otherBot) {
                        LivingEntity player = otherBot.getEntity();

                        if (!name.equals(NAME_PATTERN.matcher(otherBot.getBotName()).replaceAll("")) && validateCloserEntity(player, world, loc, result)) {
                            result = player;
                        }
                    }
                }

                break;
            }

            case CUSTOM_LIST: {
                for (LivingEntity entity : livingEntities(world, bot.getEntity())) {
                    if (customListMode == CustomListMode.CUSTOM && CUSTOM_MOB_LIST.contains(entity.getType()) && validateCloserEntity(entity, world, loc, result)) {
                        result = entity;
                    }
                }

                break;
            }

            case PLAYER: {
                if (bot.getTargetPlayer() != null) {
                    ServerPlayer player = manager.getServer().getPlayerList().getPlayer(bot.getTargetPlayer());
                    if (player != null && !(player instanceof Terminator) && validateCloserEntity(player, world, loc, null)) {
                        result = player;
                    }
                }

                if (result == null) {
                    // As documented for the PLAYER goal: fall back to the nearest vulnerable player.
                    return locateTarget(bot, loc, EnumTargetGoal.NEAREST_VULNERABLE_PLAYER);
                }

                break;
            }
        }

        // fight back first
        if (revenge != null) {
            result = revenge;
        }

        TerminatorLocateTargetEvent event = new TerminatorLocateTargetEvent(bot, result);
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return null;
        return event.getTarget();
    }

    private boolean validateCloserEntity(LivingEntity entity, ServerLevel world, Vec3 loc, @Nullable LivingEntity result) {
        double range = skillSettings.targetRange;
        if (range > 0 && loc.distanceToSqr(entity.position()) > range * range)
            return false;
        double regionDistEntity = getWeightedRegionDist(entity.position());
        if (regionDistEntity == Double.MAX_VALUE)
            return false;
        double regionDistResult = result == null ? 0 : getWeightedRegionDist(result.position());
        return world == entity.level() && entity.isAlive()
                && (result == null || (loc.distanceToSqr(entity.position()) + regionDistEntity) < (loc.distanceToSqr(result.position())) + regionDistResult);
    }

    private double getWeightedRegionDist(Vec3 loc) {
        if (region == null)
            return 0;
        Vec3 center = region.getCenter();
        double diffX = Math.max(0, Math.abs(center.x - loc.x) - region.getXsize() * 0.5);
        double diffY = Math.max(0, Math.abs(center.y - loc.y) - region.getYsize() * 0.5);
        double diffZ = Math.max(0, Math.abs(center.z - loc.z) - region.getZsize() * 0.5);
        if (regionWeightX == 0 && regionWeightY == 0 && regionWeightZ == 0)
            if (diffX > 0 || diffY > 0 || diffZ > 0)
                return Double.MAX_VALUE;
        return diffX * diffX * regionWeightX + diffY * diffY * regionWeightY + diffZ * diffZ * regionWeightZ;
    }

    @Override
    public void stopAllTasks() {
        super.stopAllTasks();

        if (skills != null) {
            skills.clear();
        }

        Iterator<Entry<GlobalPos, Short>> itr = crackList.entrySet().iterator();
        while (itr.hasNext()) {
            Entry<GlobalPos, Short> entry = itr.next();
            ServerLevel world = manager.getServer().getLevel(entry.getKey().dimension());
            if (world != null) {
                sendBlockDestructionPacket(entry.getValue(), world, entry.getKey().pos(), -1);
            }
            itr.remove();
        }
        mining.clear();
        miningAnim.clear();
    }
}
