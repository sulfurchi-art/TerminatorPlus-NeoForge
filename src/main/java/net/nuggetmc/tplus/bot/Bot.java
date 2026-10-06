package net.nuggetmc.tplus.bot;

import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.Hardness;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyMats;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.event.BotDamageByPlayerEvent;
import net.nuggetmc.tplus.api.event.BotFallDamageEvent;
import net.nuggetmc.tplus.api.event.BotKilledByPlayerEvent;
import net.nuggetmc.tplus.api.scheduler.TaskScheduler;
import net.nuggetmc.tplus.api.utils.*;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Predicate;

/**
 * A fake player that lives in the world like a real one, but moves with its own simple physics and is driven by the
 * bot agent.
 */
public class Bot extends ServerPlayer implements Terminator {

    /**
     * All skin layers shown, smallest view distance (bots don't need chunks sent to them).
     */
    private static final ClientInformation CLIENT_INFORMATION = new ClientInformation(
            "en_us", 2, ChatVisiblity.FULL, true, 0x7F, HumanoidArm.RIGHT, false, true);

    /**
     * Hotbar slot the agent puts tools/blocks into. Everything else in the inventory belongs to the bot.
     */
    private static final int HAND_SLOT = 0;

    private final BotManagerImpl manager;
    private final TaskScheduler scheduler;
    private final Agent agent;
    private final Vec3 offset;
    public ItemStack defaultItem;
    private NeuralNetwork network;
    private boolean shield;
    private boolean blocking;
    private boolean blockUse;
    private int shieldSession;
    private Vec3 velocity;
    private Vec3 oldVelocity;
    private boolean removeOnDeath;
    private int aliveTicks;
    private int kills;
    private byte groundTicks;
    private byte jumpTicks;
    private byte noFallTicks;
    private List<BlockPos> standingOn = new ArrayList<>();
    private UUID targetPlayer = null;
    private boolean inPlayerList;
    private boolean botRemoved;

    private boolean lastHorizontalCollision;
    private int climbTicks;
    private boolean gliding;
    private int glideTicks;
    private boolean elytraSwapped;
    private int hardnessOverride;
    private int consumableSlot = -1;
    private int consumableTicks;
    private ItemStack consumableHand = ItemStack.EMPTY;
    private final Map<String, Boolean> profileAbilities = new HashMap<>();
    private String personality;

    public String personality() {
        if (personality == null) personality = List.of("分析", "嘲讽", "冷血", "队长", "阴人").get(Math.floorMod(getUUID().hashCode(), 5));
        return personality;
    }
    public void setPersonality(String name) {
        if (!Set.of("分析", "嘲讽", "冷血", "队长", "阴人").contains(name)) throw new IllegalArgumentException("未知人设：" + name);
        personality = name;
    }
    public Map<String, Boolean> profileAbilities() { return profileAbilities; }

    public int getHardnessOverride() { return hardnessOverride; }
    public void setHardnessOverride(int value) {
        if (value < 0 || value > 10) throw new IllegalArgumentException("AI hardness must be 1..10, or 0 to inherit");
        hardnessOverride = value;
        getAbilities().instabuild = hardness().infiniteSupplies();
    }
    public Hardness hardness() {
        return ((LegacyAgent) agent).getSkills().hardness(this);
    }

    public boolean isConsuming() { return consumableSlot >= 0; }

    public boolean hasRecoveryItem() {
        return findSlot(this::usefulRecoveryItem) >= 0;
    }

    private boolean usefulRecoveryItem(ItemStack stack) {
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals("superbwarfare:medical_kit"))
            return getHealth() < getMaxHealth() && !getCooldowns().isOnCooldown(stack.getItem()) && !hasEffect(MobEffects.REGENERATION);
        if (stack.is(Items.CHORUS_FRUIT)) return false;
        if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) return !hasEffect(MobEffects.REGENERATION);
        if (stack.getFoodProperties(this) != null) return getFoodData().needsFood();
        if (stack.is(Items.POTION)) {
            var contents = stack.getOrDefault(DataComponents.POTION_CONTENTS,
                    PotionContents.EMPTY);
            for (var effect : contents.getAllEffects()) {
                if (effect.getEffect().is(MobEffects.HEAL)
                        || effect.getEffect().is(MobEffects.REGENERATION)) return true;
            }
        }
        return false;
    }

    /** Use the real stack for the vanilla duration and effects; never consume the display copy. */
    public boolean beginRecoveryItem(boolean chorus) {
        if (isConsuming()) return true;
        int slot = !chorus ? findSlot(s -> s.is(Items.ENCHANTED_GOLDEN_APPLE) && usefulRecoveryItem(s)) : -1;
        if (slot < 0 && !chorus) slot = findSlot(s -> s.is(Items.GOLDEN_APPLE) && usefulRecoveryItem(s));
        if (slot < 0) slot = findSlot(s -> chorus ? s.is(Items.CHORUS_FRUIT) : usefulRecoveryItem(s));
        if (slot < 0 || getCooldowns().isOnCooldown(getInventory().items.get(slot).getItem())) return false;
        releaseWarfareHand();
        lowerBow();
        interruptShield();
        consumableSlot = slot;
        consumableHand = getInventory().items.get(HAND_SLOT);
        getInventory().items.set(HAND_SLOT, getInventory().items.get(slot));
        getInventory().items.set(slot, ItemStack.EMPTY);
        startUsingItem(InteractionHand.MAIN_HAND);
        consumableTicks = getMainHandItem().getUseDuration(this);
        if (!isUsingItem() || consumableTicks <= 0) { cancelRecoveryItem(); return false; }
        return true;
    }

    public void cancelRecoveryItem() {
        if (!isConsuming()) return;
        stopUsingItem();
        getInventory().items.set(consumableSlot, getMainHandItem());
        getInventory().items.set(HAND_SLOT, consumableHand);
        consumableSlot = -1;
        consumableHand = ItemStack.EMPTY;
    }

    private void tickRecoveryItem() {
        if (!isConsuming()) return;
        if (--consumableTicks > 0) return;
        ItemStack result = getMainHandItem().finishUsingItem(level(), this);
        getInventory().items.set(HAND_SLOT, result);
        cancelRecoveryItem();
    }

    public void prepareEquipmentPreset() {
        ((LegacyAgent) agent).getSkills().ordnance().cancel(this);
        releaseWarfareHand();
        cancelRecoveryItem();
        lowerBow();
        interruptShield();
        stopGliding();
        elytraSwapped = false;
        if (rocket != null) { rocket.discard(); rocket = null; }
    }
    @Nullable
    private FireworkRocketEntity rocket;

    private Bot(MinecraftServer server, ServerLevel level, GameProfile profile, BotManagerImpl manager) {
        super(server, level, profile, CLIENT_INFORMATION);

        this.manager = manager;
        this.scheduler = manager.getScheduler();
        this.agent = manager.getAgent();
        this.defaultItem = ItemStack.EMPTY;
        this.velocity = Vec3.ZERO;
        this.oldVelocity = velocity;
        this.noFallTicks = 60;
        this.removeOnDeath = true;
        this.offset = MathUtils.circleOffset(3);

        // Bots don't earn advancements or keep statistics: detach them from the player list's per-UUID caches right away,
        // otherwise every bot would leave an entry behind (and could get chat-announced advancements).
        PlayerList playerList = server.getPlayerList();
        getAdvancements().stopListening();
        playerList.advancements.remove(getUUID(), getAdvancements());
        playerList.stats.remove(getUUID(), getStats());
    }

    public static Bot createBot(Location loc, String name) {
        return createBot(loc, name, MojangAPI.getSkin(name));
    }

    public static Bot createBot(Location loc, String name, @Nullable String[] skin) {
        return createBot(loc, name, skin, true);
    }

    public static Bot createBot(Location loc, String name, @Nullable String[] skin, boolean defaults) {
        ServerLevel level = loc.level();
        MinecraftServer server = level.getServer();
        BotManagerImpl manager = TerminatorPlus.getManager();
        if (defaults && manager.presets().selected() != null) manager.presets().selected().validateTeam(server);

        UUID uuid = BotUtils.randomSteveUUID();

        GameProfile profile = new GameProfile(uuid, ChatUtils.trim16(name));

        if (skin != null) {
            profile.getProperties().put("textures", new Property("textures", skin[0], skin[1]));
        }

        boolean addPlayerList = manager.addToPlayerList();

        Bot bot = new Bot(server, level, profile, manager);

        new BotPacketListener(server, bot); // assigns bot.connection

        bot.moveTo(loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
        bot.setYHeadRot(loc.yaw());
        bot.invulnerableTime = 0;

        // The client needs the profile (skin) of a player before the player entity is sent to it.
        if (addPlayerList) {
            server.getPlayerList().players.add(bot);
            bot.inPlayerList = true;
            broadcast(server, ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(bot)));
            level.addNewPlayer(bot);
        } else {
            broadcast(server, new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, bot));
            level.addFreshEntity(bot);
        }

        manager.add(bot, defaults);

        return bot;
    }

    /**
     * Sends a packet to every real player.
     */
    private static void broadcast(MinecraftServer server, Packet<?> packet) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof Bot)) {
                player.connection.send(packet);
            }
        }
    }

    private void sendPacket(Packet<?> packet) {
        broadcast(server, packet);
    }

    @Override
    public String getBotName() {
        return getGameProfile().getName();
    }

    @Override
    public int getEntityId() {
        return getId();
    }

    @Override
    public ServerPlayer getEntity() {
        return this;
    }

    @Override
    public ServerLevel getBotLevel() {
        return serverLevel();
    }

    @Override
    public NeuralNetwork getNeuralNetwork() {
        return network;
    }

    @Override
    public void setNeuralNetwork(NeuralNetwork network) {
        this.network = network;
    }

    @Override
    public boolean hasNeuralNetwork() {
        return network != null;
    }

    public void render(ServerPlayer viewer) {
        viewer.connection.send(inPlayerList
                ? ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(this))
                : new ClientboundPlayerInfoUpdatePacket(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, this));
    }

    @Override
    public void renderBot(ServerPlayer viewer, boolean login) {
        if (viewer instanceof Bot) return;
        render(viewer);
    }

    @Override
    public void setDefaultItem(ItemStack item) {
        this.defaultItem = item;
    }

    @Override
    public Vec3 getOffset() {
        return offset;
    }

    @Override
    public Vec3 getVelocity() {
        return velocity;
    }

    @Override
    public void setVelocity(Vec3 vector) {
        this.velocity = vector;
    }

    @Override
    public void addVelocity(Vec3 vector) { // This can cause lag? (maybe i fixed it with the new static method)
        if (MathUtils.isNotFinite(vector)) {
            velocity = vector;
            return;
        }

        velocity = velocity.add(vector);
    }

    @Override
    public int getAliveTicks() {
        return aliveTicks;
    }

    @Override
    public int getNoFallTicks() {
        return noFallTicks;
    }

    @Override
    public boolean tickDelay(int i) {
        return aliveTicks % i == 0;
    }

    @Override
    public boolean isBotAlive() {
        return isAlive();
    }

    @Override
    public float getBotHealth() {
        return getHealth();
    }

    @Override
    public float getBotMaxHealth() {
        return getMaxHealth();
    }

    @Override
    public void tick() {
        loadChunks();

        super.tick();

        if (!isAlive()) return;

        aliveTicks++;
        // Players charge their attacks in Player#tick, which bots skip. Only matters for vanilla attacks (mace smashes).
        attackStrengthTicker++;

        if (jumpTicks > 0) --jumpTicks;
        if (noFallTicks > 0) --noFallTicks;

        escapeEmbedding();

        if (checkGround()) {
            if (groundTicks < 5) groundTicks++;
        } else {
            groundTicks = 0;
        }

        updateLocation();

        tickRecoveryItem();
        getAbilities().instabuild = hardness().infiniteSupplies();
        if (hardness().tactical() && blocking && isUsingItem()
                && getUsedItemHand() == InteractionHand.OFF_HAND) updateUsingItem(getUseItem());
        if (hardness().tactical()) {
            getFoodData().addExhaustion((float) velocity.horizontalDistance() * 0.01F);
            getFoodData().tick(this);
            getCooldowns().tick();
        }

        if (!isAlive()) return;

        if (elytraSwapped && !gliding && groundTicks > 1) {
            restoreChestplate();
        }

        float health = getHealth();
        float maxHealth = getMaxHealth();
        float regenAmount = 0.025f;
        float amount;

        if (health < maxHealth - regenAmount) {
            amount = health + regenAmount;
        } else {
            amount = maxHealth;
        }

        if (hardness().passiveRegeneration()) setHealth(amount);

        fallDamageCheck();

        oldVelocity = velocity;

        doTick();
    }

    private void loadChunks() {
        Level world = level();
        int chunkX = chunkPosition().x;
        int chunkZ = chunkPosition().z;

        for (int i = chunkX - 1; i <= chunkX + 1; i++) {
            for (int j = chunkZ - 1; j <= chunkZ + 1; j++) {
                world.getChunk(i, j);
            }
        }
    }

    /**
     * Real players report their movement to the chunk map from the network handler. Bots move on their own, so tell the
     * chunk map whenever the bot enters another section: that keeps the chunks around the bot loaded and ticking
     * (otherwise the bot would freeze as soon as it walks away from its spawn point and from all real players).
     */
    private void updateChunkTracking() {
        if (!isRemoved() && SectionPos.of(this).asLong() != getLastSectionPos().asLong()) {
            serverLevel().getChunkSource().move(this);
        }
    }

    void onTeleported() {
        // pearls, /tp...: start from rest at the new position
        velocity = Vec3.ZERO;
        oldVelocity = Vec3.ZERO;
        fallDistance = 0;

        if (!isRemoved() && serverLevel().getEntity(getId()) == this) {
            connection.resetPosition();
            serverLevel().getChunkSource().move(this);
        }
    }

    /**
     * Blocks placed at the bot's feet (lava clutches, sand, other players...) would otherwise trap it for good: entities
     * that overlap a block can't move in any direction. Push the bot up, or sideways, into free space.
     */
    private void escapeEmbedding() {
        if (isPassenger() || gliding) return;

        AABB box = getBoundingBox().deflate(1.0E-3);
        if (!isEmbedded(box)) return;

        for (int i = 1; i <= 12; i++) {
            double dy = i * 0.1;
            if (!isEmbedded(box.move(0, dy, 0))) {
                setPos(getX(), getY() + dy, getZ());
                return;
            }
        }

        for (double distance = 0.35; distance <= 1.05; distance += 0.35) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                double dx = direction.getStepX() * distance;
                double dz = direction.getStepZ() * distance;
                if (!isEmbedded(box.move(dx, 0, dz))) {
                    setPos(getX() + dx, getY(), getZ() + dz);
                    return;
                }
            }
        }
    }

    private boolean isEmbedded(AABB box) {
        for (VoxelShape shape : level().getBlockCollisions(this, box)) {
            if (!shape.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    @Override
    public UUID getTargetPlayer() {
        return targetPlayer;
    }

    @Override
    public void setTargetPlayer(@Nullable UUID target) {
        this.targetPlayer = target;
    }

    @Override
    public boolean isBotOnFire() {
        return this.isOnFire();
    }

    private void fallDamageCheck() { // TODO create a better bot event system in the future, also have bot.getAgent()
        if (groundTicks != 0 && noFallTicks == 0 && !(oldVelocity.y >= -0.8) && !isFallBlocked()) {
            BotFallDamageEvent event = new BotFallDamageEvent(this, new ArrayList<>(getStandingOn()));

            agent.onFallDamage(event);

            if (!event.isCancelled()) {
                hurt(damageSources().fall(), (float) Math.pow(3.6, -oldVelocity.y));
            }
        }
    }

    private boolean isFallBlocked() {
        AABB box = getBoundingBox();
        double[] xVals = new double[]{
                box.minX,
                box.maxX - 0.01
        };

        double[] zVals = new double[]{
                box.minZ,
                box.maxZ - 0.01
        };
        AABB playerBox = new AABB(box.minX, position().y - 0.01, box.minZ,
                box.maxX, position().y + getBbHeight(), box.maxZ);
        for (double x : xVals) {
            for (double z : zVals) {
                BlockPos pos = BlockPos.containing(x, getY(), z);
                BlockState state = level().getBlockState(pos);
                if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
                    return true;
                Block type = state.getBlock();
                if (BotUtils.NO_FALL.contains(type) && (BotUtils.overlaps(playerBox, BotUtils.getBlockBoundingBox(level(), pos))
                        || type == Blocks.WATER || type == Blocks.LAVA))
                    return true;
            }
        }
        return false;
    }

    @Override
    public boolean isBotFalling() {
        return velocity.y < -0.8;
    }

    @Override
    public void block(int blockLength, int cooldown) {
        if (!shield || blockUse || isConsuming()) return;
        if (hardness().tactical() && (!getOffhandItem().canPerformAction(net.neoforged.neoforge.common.ItemAbilities.SHIELD_BLOCK)
                || getCooldowns().isOnCooldown(getOffhandItem().getItem()))) return;
        lowerBow();
        startBlocking();
        int session = ++shieldSession;
        scheduler.runTaskLater(() -> {
            if (session == shieldSession) stopBlocking(cooldown, session);
        }, blockLength);
    }

    private void startBlocking() {
        this.blocking = true;
        this.blockUse = true;
        startUsingItem(InteractionHand.OFF_HAND);
    }

    private void stopBlocking(int cooldown, int session) {
        this.blocking = false;
        if (isUsingItem() && getUsedItemHand() == InteractionHand.OFF_HAND) stopUsingItem();
        scheduler.runTaskLater(() -> { if (session == shieldSession) this.blockUse = false; }, cooldown);
    }

    private void interruptShield() {
        if (!blocking && !blockUse) return;
        shieldSession++;
        blocking = false;
        blockUse = false;
        if (isUsingItem() && getUsedItemHand() == InteractionHand.OFF_HAND) stopUsingItem();
    }

    public void lowerShield() { interruptShield(); }

    @Override
    public boolean isBotBlocking() {
        return isBlocking();
    }

    @Override
    public void setShield(boolean enabled) {
        this.shield = enabled;

        setItemOffhand(new ItemStack(enabled ? Items.SHIELD : Items.AIR));
    }

    public void setShieldEnabled(boolean enabled) { shield = enabled; if (!enabled) interruptShield(); }

    private void updateLocation() {
        if (isPassenger()) {
            velocity = Vec3.ZERO;
            fallDistance = 0;
            groundTicks = 0;
            updateChunkTracking();
            return;
        }
        velocity = MathUtils.clean(velocity); // TODO lag????

        if (gliding) {
            glide();
            updateChunkTracking();
            return;
        }

        double y;
        boolean inWater = isBotInWater();
        boolean onClimbable = !inWater && onClimbable();

        if (inWater) {
            y = Math.min(velocity.y + 0.1, 0.1);
            addFriction(0.8);

            // Swimming against a bank: hop out of the water like a player holding jump does.
            if (lastHorizontalCollision && canLeaveLiquid()) {
                y = 0.42;
            }

            velocity = MathUtils.withY(velocity, y);
        } else if (onClimbable && (climbTicks > 0 || lastHorizontalCollision)) {
            // ladders, vines, scaffolding...: climb at the vanilla climbing speed
            y = 0.2;
            velocity = new Vec3(Mth.clamp(velocity.x, -0.15, 0.15), y, Mth.clamp(velocity.z, -0.15, 0.15));
        } else if (onClimbable && groundTicks == 0) {
            // hold on and slide down slowly instead of falling
            y = Math.max(velocity.y - 0.08, -0.15);
            velocity = new Vec3(Mth.clamp(velocity.x, -0.15, 0.15), y, Mth.clamp(velocity.z, -0.15, 0.15));
        } else {
            if (groundTicks != 0) {
                velocity = MathUtils.withY(velocity, 0);
                addFriction(0.5);
                y = 0;
            } else {
                y = velocity.y;
                if (jumpTicks - 3 <= 0) {
                    velocity = MathUtils.withY(velocity, Math.max(y - 0.08, -3.5));
                }
            }
        }

        double startY = getY();

        this.move(MoverType.SELF, new Vec3(velocity.x, y, velocity.z));

        lastHorizontalCollision = horizontalCollision;
        trackFallDistance(getY() - startY, inWater || onClimbable);

        if (climbTicks > 0) climbTicks--;

        updateChunkTracking();
    }

    /**
     * Vanilla's check for jumping out of water: the space 0.6 blocks up is free of blocks and of water.
     */
    private boolean canLeaveLiquid() {
        AABB box = getBoundingBox().move(0, 0.6, 0);
        return level().noCollision(this, box) && !level().containsAnyLiquid(box);
    }

    /**
     * Keeps the vanilla fall distance up to date (it drives mace smash attacks). Bots never get vanilla fall damage, that's
     * handled by {@link #fallDamageCheck()}.
     */
    private void trackFallDistance(double dy, boolean reset) {
        if (reset || onGround() || gliding) {
            fallDistance = 0;
        } else if (dy < 0) {
            fallDistance -= (float) dy;
        }
    }

    // ---- elytra ---------------------------------------------------------------------------------------------------

    /**
     * Vanilla elytra flight physics ({@code LivingEntity#travel} while fall flying), driven by the bot's rotation, plus the
     * firework boost a rocket attached to the player applies every tick.
     */
    private void glide() {
        ItemStack chest = getItemBySlot(EquipmentSlot.CHEST);

        if (!chest.canElytraFly(this) || isBotInWater() || isPassenger()) {
            stopGliding();
            return;
        }

        // durability and glide game events, exactly like players
        chest.elytraFlightTick(this, glideTicks++);

        Vec3 look = getLookAngle();
        float pitchRad = getXRot() * Mth.DEG_TO_RAD;
        double horizontalLook = Math.sqrt(look.x * look.x + look.z * look.z);
        double horizontalSpeed = velocity.horizontalDistance();
        double lift = Math.cos(pitchRad);
        lift = lift * lift * Math.min(1.0, look.length() / 0.4);

        Vec3 v = velocity.add(0.0, 0.08 * (-1.0 + lift * 0.75), 0.0);

        if (v.y < 0.0 && horizontalLook > 0.0) {
            double d = v.y * -0.1 * lift;
            v = v.add(look.x * d / horizontalLook, d, look.z * d / horizontalLook);
        }

        if (pitchRad < 0.0F && horizontalLook > 0.0) {
            double d = horizontalSpeed * (-Mth.sin(pitchRad)) * 0.04;
            v = v.add(-look.x * d / horizontalLook, d * 3.2, -look.z * d / horizontalLook);
        }

        if (horizontalLook > 0.0) {
            v = v.add((look.x / horizontalLook * horizontalSpeed - v.x) * 0.1, 0.0, (look.z / horizontalLook * horizontalSpeed - v.z) * 0.1);
        }

        if (isRocketBoosting()) {
            v = v.add(look.x * 0.1 + (look.x * 1.5 - v.x) * 0.5, look.y * 0.1 + (look.y * 1.5 - v.y) * 0.5, look.z * 0.1 + (look.z * 1.5 - v.z) * 0.5);
        }

        v = v.multiply(0.99, 0.98, 0.99);

        Vec3 before = position();
        move(MoverType.SELF, v);
        Vec3 moved = position().subtract(before);

        // Like vanilla's delta movement: whatever ran into a block stops.
        double speedBefore = v.horizontalDistance();
        velocity = new Vec3(
                Math.abs(moved.x - v.x) > 1.0E-4 ? 0 : v.x,
                verticalCollision ? 0 : v.y,
                Math.abs(moved.z - v.z) > 1.0E-4 ? 0 : v.z);

        lastHorizontalCollision = horizontalCollision;

        if (horizontalCollision) {
            float damage = (float) ((speedBefore - velocity.horizontalDistance()) * 10.0 - 3.0);
            if (damage > 0.0F) {
                playSound(damage > 4 ? getFallSounds().big() : getFallSounds().small(), 1.0F, 1.0F);
                hurt(damageSources().flyIntoWall(), damage);
            }
        }

        // lets clients render the glide (and is what an attached rocket reads)
        setDeltaMovement(velocity);
        fallDistance = 0;

        if (onGround()) {
            stopGliding();
        }
    }

    @Override
    public boolean hasUsableElytra() {
        return getItemBySlot(EquipmentSlot.CHEST).canElytraFly(this) || !findItem(stack -> stack.canElytraFly(this)).isEmpty();
    }

    @Override
    public boolean isGliding() {
        return gliding;
    }

    @Override
    public boolean startGliding() {
        if (gliding) return true;
        if (groundTicks != 0 || onGround() || isBotInWater() || isPassenger()) return false;
        if (!equipElytra()) return false;

        gliding = true;
        glideTicks = 0;
        fallDistance = 0;
        startFallFlying();
        return true;
    }

    @Override
    public void stopGliding() {
        if (!gliding) return;

        gliding = false;
        stopFallFlying();
        setDeltaMovement(Vec3.ZERO);
    }

    /**
     * Swaps an elytra from the inventory into the chest slot (the chestplate goes where the elytra was).
     */
    private boolean equipElytra() {
        Inventory inventory = getInventory();
        int chestIndex = EquipmentSlot.CHEST.getIndex();
        ItemStack chest = inventory.armor.get(chestIndex);

        if (chest.canElytraFly(this)) return true;

        for (int i = 1; i < inventory.items.size(); i++) {
            ItemStack stack = inventory.items.get(i);
            if (!stack.isEmpty() && stack.canElytraFly(this)) {
                inventory.items.set(i, chest);
                inventory.armor.set(chestIndex, stack);
                elytraSwapped = true;
                return true;
            }
        }

        return false;
    }

    /**
     * Back on the ground: put the chestplate back on.
     */
    private void restoreChestplate() {
        elytraSwapped = false;

        Inventory inventory = getInventory();
        int chestIndex = EquipmentSlot.CHEST.getIndex();
        ItemStack chest = inventory.armor.get(chestIndex);

        for (int i = 1; i < inventory.items.size(); i++) {
            ItemStack stack = inventory.items.get(i);
            if (stack.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == EquipmentSlot.CHEST) {
                inventory.items.set(i, chest);
                inventory.armor.set(chestIndex, stack);
                return;
            }
        }
    }

    @Override
    public boolean fireRocket() {
        if (!gliding || isRocketBoosting()) return false;

        ItemStack stack = findItem(item -> item.is(Items.FIREWORK_ROCKET));
        if (stack.isEmpty()) return false;

        FireworkRocketEntity entity = new FireworkRocketEntity(level(), stack.copyWithCount(1), this);
        level().addFreshEntity(entity);
        if (!hardness().infiniteSupplies()) stack.shrink(1);
        rocket = entity;

        setItem(new ItemStack(Items.FIREWORK_ROCKET));
        punch();
        return true;
    }

    @Override
    public boolean isRocketBoosting() {
        return rocket != null && rocket.isAlive();
    }

    // ---- throwables & mace ----------------------------------------------------------------------------------------

    @Override
    public boolean throwEnderPearl(float yaw, float pitch) {
        ItemStack stack = findItem(item -> item.is(Items.ENDER_PEARL));
        if (stack.isEmpty()) return false;

        setLook(yaw, pitch);
        setItem(new ItemStack(Items.ENDER_PEARL));
        punch();

        ThrownEnderpearl pearl = new ThrownEnderpearl(level(), this);
        pearl.setItem(stack.copyWithCount(1));
        pearl.shootFromRotation(this, pitch, yaw, 0.0F, 1.5F, 1.0F);
        level().addFreshEntity(pearl);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ENDER_PEARL_THROW, SoundSource.NEUTRAL, 0.5F,
                0.4F / (random.nextFloat() * 0.4F + 0.8F));

        if (!hardness().infiniteSupplies()) stack.shrink(1);
        return true;
    }

    @Override
    public boolean throwWindCharge(float yaw, float pitch) {
        ItemStack stack = findItem(item -> item.is(Items.WIND_CHARGE));
        if (stack.isEmpty()) return false;

        setLook(yaw, pitch);
        setItem(new ItemStack(Items.WIND_CHARGE));
        punch();

        WindCharge charge = new WindCharge(this, level(), getX(), getEyeY(), getZ());
        charge.shootFromRotation(this, pitch, yaw, 0.0F, 1.5F, 1.0F);
        level().addFreshEntity(charge);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.WIND_CHARGE_THROW, SoundSource.NEUTRAL, 0.5F,
                0.4F / (random.nextFloat() * 0.4F + 0.8F));

        if (!hardness().infiniteSupplies()) stack.shrink(1);
        return true;
    }

    @Override
    public boolean hasMace() {
        return defaultItem.getItem() instanceof MaceItem || !findItem(stack -> stack.getItem() instanceof MaceItem).isEmpty();
    }

    @Override
    public boolean canSmash() {
        return MaceItem.canSmashAttack(this) && hasMace();
    }

    @Override
    public boolean smash(LivingEntity target) {
        if (isConsuming() || isAlliedTo(target)) return false;
        lowerBow();
        interruptShield();
        Inventory inventory = getInventory();
        ItemStack hand = inventory.items.get(HAND_SLOT);
        int maceSlot = -1;

        for (int i = 1; i < inventory.items.size(); i++) {
            if (inventory.items.get(i).getItem() instanceof MaceItem) {
                maceSlot = i;
                break;
            }
        }

        // the real mace goes into the hand, so durability and enchantments apply to it
        if (maceSlot >= 0) {
            inventory.items.set(HAND_SLOT, inventory.items.get(maceSlot));
            inventory.items.set(maceSlot, hand);
        } else if (defaultItem.getItem() instanceof MaceItem) {
            inventory.items.set(HAND_SLOT, defaultItem.copy());
        } else {
            return false;
        }

        // apply the mace's attack damage/speed before attacking
        detectEquipmentUpdates();

        boolean smashAttack = MaceItem.canSmashAttack(this);

        faceLocation(target.getBoundingBox().getCenter());
        ((LegacyAgent) agent).getSkills().beginAttempt(this, target, net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.MACE);
        attack(target);
        punch();

        if (maceSlot >= 0) {
            ItemStack used = inventory.items.get(HAND_SLOT);
            inventory.items.set(HAND_SLOT, inventory.items.get(maceSlot));
            inventory.items.set(maceSlot, used);
        } else {
            defaultItem = inventory.items.get(HAND_SLOT);
            inventory.items.set(HAND_SLOT, hand);
        }

        detectEquipmentUpdates();

        if (smashAttack) {
            // vanilla stops the attacker's fall after a smash
            velocity = MathUtils.withY(velocity, 0.01);
        }

        return smashAttack;
    }

    // ---- bow & totems ---------------------------------------------------------------------------------------------

    /**
     * @return the first inventory slot (never the hand slot) holding a matching stack, or -1
     */
    private int findSlot(Predicate<ItemStack> filter) {
        NonNullList<ItemStack> items = getInventory().items;

        for (int i = 1; i < items.size(); i++) {
            if (!items.get(i).isEmpty() && filter.test(items.get(i))) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public boolean canShootBow() {
        int slot = findSlot(stack -> stack.getItem() instanceof BowItem);
        return slot >= 0 && !getProjectile(getInventory().items.get(slot)).isEmpty();
    }

    @Override
    public void drawBow() {
        if (isConsuming()) return;
        interruptShield();
        ItemStack bow = findItem(stack -> stack.getItem() instanceof BowItem);
        if (bow.isEmpty()) return;

        setItem(bow);

        if (!isUsingItem()) {
            startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void lowerBow() {
        if (isUsingItem() && getUseItem().getItem() instanceof BowItem) {
            stopUsingItem();
        }
    }

    @Override
    public boolean shootBow(float yaw, float pitch) {
        if (isConsuming()) return false;
        interruptShield();
        Inventory inventory = getInventory();
        int bowSlot = findSlot(stack -> stack.getItem() instanceof BowItem);
        if (bowSlot < 0) return false;

        ItemStack bow = inventory.items.get(bowSlot);
        if (getProjectile(bow).isEmpty()) return false;

        lowerBow();
        setLook(yaw, pitch);
        // arrows inherit the shooter's movement; the bot's own physics doesn't keep the vanilla one up to date
        setDeltaMovement(Vec3.ZERO);

        // the real bow goes into the hand, so its enchantments and durability apply
        ItemStack hand = inventory.items.get(HAND_SLOT);
        inventory.items.set(HAND_SLOT, bow);
        inventory.items.set(bowSlot, hand);

        // let go after a full draw (20 ticks): full power, critical arrow
        var skills = ((LegacyAgent) agent).getSkills();
        skills.beginAttempt(this, skills.currentTarget(this), net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.BOW);
        bow.releaseUsing(level(), this, bow.getUseDuration(this) - 20);

        ItemStack used = inventory.items.get(HAND_SLOT);
        inventory.items.set(HAND_SLOT, inventory.items.get(bowSlot));
        inventory.items.set(bowSlot, used);

        return true;
    }

    @Override
    public boolean equipTotem() {
        if (blocking) return false;
        Inventory inventory = getInventory();
        ItemStack offhand = inventory.offhand.get(0);
        if (offhand.is(Items.TOTEM_OF_UNDYING)) return false;

        int slot = findSlot(stack -> stack.is(Items.TOTEM_OF_UNDYING));
        if (slot < 0) return false;

        inventory.offhand.set(0, inventory.items.get(slot));
        inventory.items.set(slot, offhand);
        return true;
    }

    public boolean equipShield() {
        if (isConsuming() || blocking) return false;
        var action = net.neoforged.neoforge.common.ItemAbilities.SHIELD_BLOCK;
        Inventory inventory = getInventory();
        ItemStack offhand = inventory.offhand.get(0);
        if (offhand.canPerformAction(action)) return true;
        int slot = findSlot(stack -> stack.canPerformAction(action));
        if (slot < 0) return false;
        inventory.offhand.set(0, inventory.items.get(slot));
        inventory.items.set(slot, offhand);
        return true;
    }

    // ---- inventory ------------------------------------------------------------------------------------------------

    @Override
    public int countItem(Item item) {
        NonNullList<ItemStack> items = getInventory().items;
        int count = 0;

        for (int i = 1; i < items.size(); i++) {
            if (items.get(i).is(item)) {
                count += items.get(i).getCount();
            }
        }

        return count;
    }

    @Override
    public ItemStack findItem(Predicate<ItemStack> filter) {
        NonNullList<ItemStack> items = getInventory().items;

        for (int i = 1; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (!stack.isEmpty() && filter.test(stack)) {
                return stack;
            }
        }

        return ItemStack.EMPTY;
    }

    @Override
    public boolean consumeItem(Item item) {
        ItemStack stack = findItem(s -> s.is(item));
        if (stack.isEmpty()) return false;
        if (!hardness().infiniteSupplies() || item == Items.TOTEM_OF_UNDYING) stack.shrink(1);
        return true;
    }

    @Override
    public ItemStack giveItem(ItemStack stack) {
        NonNullList<ItemStack> items = getInventory().items;
        ItemStack remaining = stack.copy();

        for (int i = 1; i < items.size() && !remaining.isEmpty(); i++) {
            if (i == consumableSlot) continue;
            ItemStack existing = items.get(i);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining) && existing.getCount() < existing.getMaxStackSize()) {
                int move = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
                existing.grow(move);
                remaining.shrink(move);
            }
        }

        for (int i = 1; i < items.size() && !remaining.isEmpty(); i++) {
            if (i == consumableSlot) continue;
            if (items.get(i).isEmpty()) {
                items.set(i, remaining.split(Math.min(remaining.getCount(), remaining.getMaxStackSize())));
            }
        }

        return remaining;
    }

    @Override
    public void clearInventory() {
        ((LegacyAgent) agent).getSkills().ordnance().cancel(this);
        releaseWarfareHand();
        cancelRecoveryItem();
        NonNullList<ItemStack> items = getInventory().items;

        for (int i = 1; i < items.size(); i++) {
            items.set(i, ItemStack.EMPTY);
        }
    }

    @Override
    public ItemStack getWeapon() {
        ItemStack best = defaultItem;
        double bestDamage = ItemUtils.getLegacyAttackDamage(defaultItem);
        NonNullList<ItemStack> items = getInventory().items;

        for (int i = 1; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;

            double damage = ItemUtils.getLegacyAttackDamage(stack);
            if (damage > bestDamage) {
                best = stack;
                bestDamage = damage;
            }
        }

        return best;
    }

    // ---- movement -------------------------------------------------------------------------------------------------

    @Override
    public void launch(Vec3 vel) {
        velocity = vel;
        jumpTicks = 0;
        groundTicks = 0;
    }

    @Override
    public void climb() {
        climbTicks = 2;
    }

    @Override
    public boolean isClimbing() {
        return onClimbable();
    }

    @Override
    public float getFallHeight() {
        return fallDistance;
    }

    @Override
    public void setLook(float yaw, float pitch) {
        setRot(yaw, Mth.clamp(pitch, -90.0F, 90.0F));
        setYHeadRot(yaw);
        setYBodyRot(yaw);
    }

    @Override
    public void rideTick() {
        super.rideTick();
        updateChunkTracking();
    }

    @Override
    public boolean isBotInWater() {
        Vec3 loc = position();

        for (int i = 0; i <= 2; i++) {
            Block type = level().getBlockState(BlockPos.containing(loc)).getBlock();

            if (type == Blocks.WATER || type == Blocks.LAVA) {
                return true;
            }

            loc = loc.add(0, 0.9, 0);
        }

        return false;
    }

    @Override
    public void jump(Vec3 vel) {
        if (jumpTicks == 0 && groundTicks > 1) {
            jumpTicks = 4;
            if (hardness().tactical()) {
                double boost = hasEffect(MobEffects.JUMP)
                        ? 0.1 * (getEffect(MobEffects.JUMP).getAmplifier() + 1) : 0;
                vel = new Vec3(vel.x * potionSpeed(), vel.y + boost, vel.z * potionSpeed());
            }
            velocity = vel;
        }
    }

    @Override
    public void jump() {
        jump(new Vec3(0, 0.42, 0));
    }

    @Override
    public void walk(Vec3 vel) {
        double multiplier = hardness().tactical() ? potionSpeed() : 1;
        double max = 0.4 * multiplier;
        vel = new Vec3(vel.x * multiplier, vel.y, vel.z * multiplier);

        Vec3 sum = velocity.add(vel);
        if (sum.length() > max) sum = sum.normalize().scale(max);

        velocity = sum;
    }

    /** Only the vanilla potion modifiers; difficulty itself never increases movement. */
    private double potionSpeed() {
        double speed = hasEffect(MobEffects.MOVEMENT_SPEED)
                ? 0.2 * (getEffect(MobEffects.MOVEMENT_SPEED).getAmplifier() + 1) : 0;
        double slow = hasEffect(MobEffects.MOVEMENT_SLOWDOWN)
                ? 0.15 * (getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier() + 1) : 0;
        return Math.max(0, 1 + speed - slow);
    }

    @Override
    public void attackTarget(Entity entity) {
        if (isConsuming() || isAlliedTo(entity)) return;
        // any hit while falling with a mace becomes a smash attack, like for players
        if (entity instanceof LivingEntity living && canSmash()) {
            smash(living);
            return;
        }

        if (hardness().tactical() && entity instanceof LivingEntity living) {
            vanillaMelee(living);
            return;
        }

        faceLocation(entity.position());
        punch();

        double damage = ItemUtils.getLegacyAttackDamage(getWeapon());

        if (entity instanceof LivingEntity) {
            entity.hurt(damageSources().playerAttack(this), (float) damage);
        }
    }

    private void vanillaMelee(LivingEntity target) {
        lowerBow();
        interruptShield();
        var skills = ((LegacyAgent) agent).getSkills();
        ItemStack preferred = skills.meleeWeapon(this, target);
        int slot = findSlot(s -> s == preferred);
        ItemStack weapon = slot < 0 ? preferred.copy() : preferred;
        ItemStack hand = getMainHandItem();
        getInventory().items.set(HAND_SLOT, weapon);
        if (slot >= 0) getInventory().items.set(slot, hand);
        net.nuggetmc.tplus.compat.WarfareItems.chargeBaton(this, weapon);
        detectEquipmentUpdates();
        boolean ready = getAttackStrengthScale(0.5F) >= 0.95F;
        boolean criticals = ((LegacyAgent) agent).getSkills().enabled(this, "criticals");
        boolean critWeapon = weapon.getItem() instanceof SwordItem || weapon.getItem() instanceof AxeItem;
        boolean shieldBreak = hardness().level() == 10 && target.isBlocking() && weapon.getItem() instanceof AxeItem;
        if (!shieldBreak && ready && criticals && critWeapon && isBotOnGround() && !isBotInWater() && !isClimbing()
                && !hasEffect(MobEffects.BLINDNESS) && position().distanceTo(target.position()) < 2.8) {
            jump();
        } else if (ready && (shieldBreak || !criticals || !critWeapon || velocity.y <= 0 || isBotInWater() || isClimbing())) {
            faceLocation(target.getBoundingBox().getCenter());
            setSprinting(false);
            skills.beginAttempt(this, target, shieldBreak ? net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.SHIELD_BREAK
                    : net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.MELEE);
            attack(target);
            net.nuggetmc.tplus.compat.WarfareItems.chargeBaton(this, weapon);
            if (shieldBreak && !target.isBlocking()) skills.onShieldBreak(this, target);
            punch();
        }
        ItemStack used = getInventory().items.get(HAND_SLOT);
        getInventory().items.set(HAND_SLOT, hand);
        if (slot >= 0) getInventory().items.set(slot, used);
        else defaultItem = used;
        detectEquipmentUpdates();
    }

    @Override
    public void punch() {
        swing(InteractionHand.MAIN_HAND);
    }

    @Override
    public void crit(Entity target) {
        if (target instanceof LivingEntity living) ((LegacyAgent) agent).getSkills().onCriticalHit(this, living);
    }

    public boolean checkGround() {
        double vy = velocity.y;

        if (vy > 0) {
            return false;
        }

        return checkStandingOn();
    }

    public boolean checkStandingOn() {
        Level world = level();
        AABB box = getBoundingBox();

        double[] xVals = new double[]{
                box.minX,
                box.maxX
        };

        double[] zVals = new double[]{
                box.minZ,
                box.maxZ
        };
        AABB playerBox = new AABB(box.minX, position().y - 0.01, box.minZ,
                box.maxX, position().y + getBbHeight(), box.maxZ);
        List<BlockPos> standingOn = new ArrayList<>();

        for (double x : xVals) {
            for (double z : zVals) {
                BlockPos pos = BlockPos.containing(x, position().y - 0.01, z);
                Block type = world.getBlockState(pos).getBlock();

                if ((LegacyMats.isSolid(type) || LegacyMats.canStandOn(type)) && BotUtils.overlaps(playerBox, BotUtils.getBlockBoundingBox(world, pos))) {
                    if (!standingOn.contains(pos)) {
                        standingOn.add(pos);
                    }
                }
            }
        }

        //Fence/wall check
        for (double x : xVals) {
            for (double z : zVals) {
                BlockPos pos = BlockPos.containing(x, position().y - 0.51, z);
                Block type = world.getBlockState(pos).getBlock();
                AABB blockBox = BotUtils.getBlockBoundingBox(world, pos);

                if (blockBox == null) continue;

                AABB modifiedBox = new AABB(blockBox.minX, blockBox.minY, blockBox.minZ, blockBox.maxX,
                        blockBox.minY + 1.5, blockBox.maxZ);

                if ((LegacyMats.FENCE.contains(type) || LegacyMats.GATES.contains(type))
                        && LegacyMats.isSolid(type) && BotUtils.overlaps(playerBox, modifiedBox)) {
                    if (!standingOn.contains(pos)) {
                        standingOn.add(pos);
                    }
                }
            }
        }

        //Closest block comes first
        Vec3 here = position();
        standingOn.sort((a, b) ->
                Double.compare(BotUtils.getHorizSqDist(a, here), BotUtils.getHorizSqDist(b, here)));

        this.standingOn = standingOn;
        return !standingOn.isEmpty();
    }

    @Override
    public List<BlockPos> getStandingOn() {
        return standingOn;
    }

    @Override
    public boolean isBotOnGround() {
        return groundTicks != 0;
    }

    @Override
    public void addFriction(double factor) {
        double frictionMin = 0.01;

        double x = velocity.x;
        double z = velocity.z;

        velocity = new Vec3(Math.abs(x) < frictionMin ? 0 : x * factor, velocity.y, Math.abs(z) < frictionMin ? 0 : z * factor);
    }

    @Override
    public void removeVisually() {
        this.removeTab();
        this.setDead();
    }

    @Override
    public void removeBot() {
        if (!server.isSameThread()) {
            server.execute(this::removeBot);
            return;
        }

        if (botRemoved) return;
        botRemoved = true;

        manager.remove(this);

        if (!this.isRemoved()) {
            this.remove(RemovalReason.DISCARDED);
        }

        this.removeVisually();

        if (inPlayerList) {
            server.getPlayerList().players.remove(this);
        }

        agent.onBotRemoved(this);
    }

    private void removeTab() {
        sendPacket(new ClientboundPlayerInfoRemovePacket(List.of(this.getUUID())));
    }

    public void setRemoveOnDeath(boolean enabled) {
        this.removeOnDeath = enabled;
    }

    private void setDead() {
        sendPacket(new ClientboundRemoveEntitiesPacket(getId()));

        this.dead = true;
        this.inventoryMenu.removed(this);
        if (this.containerMenu != null) {
            this.containerMenu.removed(this);
        }
    }

    private void dieCheck() {
        if (removeOnDeath) {

            // I replaced HashSet with ConcurrentHashMap.newKeySet which creates a "ConcurrentHashSet"
            // this should fix the concurrentmodificationexception mentioned above, I used the ConcurrentHashMap.newKeySet to make a "ConcurrentHashSet"
            manager.remove(this);

            scheduler.runTaskLater(this::removeBot, 20);

            this.removeTab();
        }
    }

    @Override
    public void die(DamageSource damageSource) {
        stopGliding();

        super.die(damageSource);

        // the death can be cancelled by other mods (LivingDeathEvent)
        if (!isAlive()) {
            this.dieCheck();
        }
    }

    @Nullable
    @Override
    public Entity changeDimension(DimensionTransition transition) {
        Entity entity = super.changeDimension(transition);

        // A real client confirms the dimension change; until then the player is invulnerable. Bots have no client.
        hasChangedDimension();

        return entity;
    }

    @Override
    public void push(Entity entity) {
        if (!this.isPassengerOfSameVehicle(entity) && !entity.noPhysics && !this.noPhysics) {
            double d0 = entity.getX() - this.getX();
            double d1 = entity.getZ() - this.getZ();
            double d2 = Mth.absMax(d0, d1);
            if (d2 >= 0.009999999776482582D) {
                d2 = Math.sqrt(d2);
                d0 /= d2;
                d1 /= d2;
                double d3 = 1.0D / d2;
                if (d3 > 1.0D) {
                    d3 = 1.0D;
                }

                d0 *= d3;
                d1 *= d3;
                d0 *= 0.05000000074505806D;
                d1 *= 0.05000000074505806D;

                if (!this.isVehicle()) {
                    velocity = velocity.add(-d0, 0.0D, -d1);
                }

                if (!entity.isVehicle()) {
                    entity.push(d0, 0.0D, d1);
                }
            }
        }
    }

    @Override
    public boolean hurt(DamageSource damagesource, float f) {
        Entity attacker = damagesource.getEntity();

        float damage;

        boolean playerInstance = attacker instanceof ServerPlayer;

        ServerPlayer killer;

        if (playerInstance) {
            killer = (ServerPlayer) attacker;

            BotDamageByPlayerEvent event = new BotDamageByPlayerEvent(this, killer, f);

            agent.onPlayerDamage(event);

            if (event.isCancelled()) {
                return false;
            }

            damage = event.getDamage();
        } else {
            killer = null;
            damage = f;
        }

        boolean damaged = super.hurt(damagesource, damage);

        if (damaged && attacker instanceof LivingEntity living && attacker != this && !isAlliedTo(attacker)) {
            ((LegacyAgent) agent).getSkills().onDamage(this, living);
        }

        if (!damaged && blocking) {
            level().playSound(null, getX(), getY(), getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.MASTER, 1, 1);
        }

        if (damaged && attacker != null && attacker != this) {
            if (playerInstance && !isAlive()) {
                agent.onBotKilledByPlayer(new BotKilledByPlayerEvent(this, killer));
            } else if (!gliding) {
                kb(position(), attacker.position(), attacker);
            }
        }

        return damaged;
    }

    private void kb(Vec3 loc1, Vec3 loc2, Entity attacker) {
        Vec3 vel = MathUtils.withY(loc1.subtract(loc2), 0).normalize().scale(0.3);

        if (isBotOnGround()) vel = MathUtils.withY(vel.scale(0.8), 0.4);

        if (attacker instanceof Player player) {
            ItemStack hand = player.getMainHandItem();

            if (!hand.isEmpty()) {
                Holder<Enchantment> knockback = level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(Enchantments.KNOCKBACK);
                int kbLevel = hand.getEnchantmentLevel(knockback);

                if (kbLevel > 0) {
                    if (kbLevel == 1) {
                        vel = MathUtils.withY(vel.scale(1.05), .4);
                    } else {
                        vel = MathUtils.withY(vel.scale(1.9), .4);
                    }
                }
            }
        }

        velocity = vel;
    }

    @Override
    public int getKills() {
        return kills;
    }

    @Override
    public void incrementKills() {
        kills++;
    }

    @Override
    public Vec3 getLocation() {
        return position();
    }

    @Override
    public AABB getBotBoundingBox() {
        return getBoundingBox();
    }

    @Override
    public void setBotPitch(float pitch) {
        super.setXRot(pitch);
    }

    @Override
    public void faceLocation(Vec3 loc) {
        look(loc.subtract(position()), false);
    }

    @Override
    public void look(Direction face) {
        look(Vec3.atLowerCornerOf(face.getNormal()), face == Direction.DOWN || face == Direction.UP);
    }

    private void look(Vec3 dir, boolean keepYaw) {
        float yaw, pitch;

        if (keepYaw) {
            yaw = this.getYRot();
            pitch = MathUtils.fetchPitch(dir);
        } else {
            float[] vals = MathUtils.fetchYawPitch(dir);
            yaw = vals[0];
            pitch = vals[1];

            setYHeadRot(yaw);
        }

        setRot(yaw, pitch);
    }

    @Override
    public void attemptBlockPlace(BlockPos loc, Block type, boolean down) {
        if (down) {
            look(Direction.DOWN);
        } else {
            faceLocation(Vec3.atLowerCornerOf(loc));
        }

        setItem(new ItemStack(type));
        punch();

        Level world = level();

        if (!LegacyMats.isSolid(world.getBlockState(loc).getBlock())) {
            world.setBlockAndUpdate(loc, type.defaultBlockState());
            world.playSound(null, loc, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1, 1);
        }
    }

    @Override
    public void setItem(@Nullable ItemStack item) {
        setItem(item, EquipmentSlot.MAINHAND);
    }

    @Override
    public void setItemOffhand(@Nullable ItemStack item) {
        setItem(item, EquipmentSlot.OFFHAND);
    }

    /**
     * Puts an item straight into the bot's inventory. Equipment changes are broadcast to nearby players by
     * {@link #detectEquipmentUpdates()} during the bot's tick (the plugin sent an equipment packet on every call).
     * {@code null} means "the weapon": the default item, or a better weapon from the inventory.
     */
    @Override
    public void setItem(@Nullable ItemStack item, EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND && agent instanceof LegacyAgent legacy) legacy.getSkills().warfare().interrupt(this);
        if (slot == EquipmentSlot.MAINHAND && isConsuming()) return;
        if (item == null) item = slot == EquipmentSlot.MAINHAND ? getWeapon() : defaultItem;

        Inventory inventory = getInventory();

        switch (slot) {
            case MAINHAND -> setIfDifferent(inventory.items, inventory.selected, item);
            case OFFHAND -> setIfDifferent(inventory.offhand, 0, item);
            case FEET, LEGS, CHEST, HEAD -> setIfDifferent(inventory.armor, slot.getIndex(), item);
            default -> {
            }
        }
    }

    private static void setIfDifferent(NonNullList<ItemStack> list, int index, ItemStack item) {
        if (!ItemStack.matches(list.get(index), item)) {
            list.set(index, item.copy());
        }
    }

    private void releaseWarfareHand() {
        if (agent instanceof LegacyAgent legacy) legacy.getSkills().warfare().release(this);
    }

    @Override
    public void swim() {
        setSwimming(true);
    }

    @Override
    public void sneak() {
        setShiftKeyDown(true);
    }

    @Override
    public void stand() {
        setShiftKeyDown(false);
        setSwimming(false);
    }

    /**
     * Bots skip the vanilla player movement/ai step entirely (they have their own physics in {@link #tick()}),
     * but still need the base entity tick (fire, drowning, effects...) and equipment syncing.
     */
    @Override
    public void doTick() {
        detectEquipmentUpdates();
        baseTick();
    }

    @Override
    public boolean isInPlayerList() {
        return inPlayerList;
    }

    @Override
    public ResourceKey<Level> getDimension() {
        return level().dimension();
    }

    @Override
    public boolean isInNether() {
        return level().dimensionType().ultraWarm();
    }
}
