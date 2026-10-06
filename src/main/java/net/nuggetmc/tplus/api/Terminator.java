package net.nuggetmc.tplus.api;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * A server side player bot. The implementation is a {@link ServerPlayer}, see {@link #getEntity()}.
 */
public interface Terminator {

    String getBotName();

    int getEntityId();

    GameProfile getGameProfile();

    /**
     * The player entity behind this bot (was {@code getBukkitEntity()} in the Paper plugin).
     */
    ServerPlayer getEntity();

    ServerLevel getBotLevel();

    NeuralNetwork getNeuralNetwork();

    void setNeuralNetwork(NeuralNetwork neuralNetwork);

    boolean hasNeuralNetwork();

    /**
     * Feet position of the bot.
     */
    Vec3 getLocation();

    AABB getBotBoundingBox();

    boolean isBotAlive();

    float getBotHealth();

    float getBotMaxHealth();

    boolean isBotOnFire();

    boolean isBotFalling();

    boolean isBotBlocking();

    void block(int length, int cooldown);

    boolean isBotInWater();

    boolean isBotOnGround();

    /**
     * The blocks the bot is standing on, closest first.
     */
    List<BlockPos> getStandingOn();

    void setBotPitch(float pitch);

    default void setBotXRot(float pitch) {
        setBotPitch(pitch);
    }

    void jump(Vec3 velocity);

    void jump();

    void walk(Vec3 velocity);

    void look(Direction face);

    void faceLocation(Vec3 location);

    /**
     * Swings at and damages the target with the legacy damage of the bot's default item.
     */
    void attackTarget(Entity target);

    void attemptBlockPlace(BlockPos pos, Block type, boolean down);

    void punch();

    void swim();

    void sneak();

    void stand();

    void addFriction(double factor);

    void removeVisually();

    void removeBot();

    int getKills();

    void incrementKills();

    void setItem(@Nullable ItemStack item);

    void setItem(@Nullable ItemStack item, EquipmentSlot slot);

    void setItemOffhand(@Nullable ItemStack item);

    void setDefaultItem(ItemStack item);

    Vec3 getOffset();

    Vec3 getVelocity();

    void setVelocity(Vec3 velocity);

    void addVelocity(Vec3 velocity);

    int getAliveTicks();

    int getNoFallTicks();

    boolean tickDelay(int ticks);

    /**
     * Sends the tab-list/profile information of this bot to a player, so the player's client can render it.
     */
    void renderBot(ServerPlayer viewer, boolean login);

    @Nullable
    UUID getTargetPlayer();

    void setTargetPlayer(@Nullable UUID target);

    boolean isInPlayerList();

    ResourceKey<Level> getDimension();

    /**
     * True in dimensions where water evaporates (the Nether), which is where the Paper plugin checked for
     * {@code World.Environment.NETHER}.
     */
    boolean isInNether();

    void setShield(boolean b);

    // ---------------------------------------------------------------------------------------------------------------
    // Inventory. Hotbar slot 0 is the "hand" the agent swaps tools and blocks into; the other 35 slots are the bot's
    // real inventory (filled with /bot inventory or by other mods).
    // ---------------------------------------------------------------------------------------------------------------

    int countItem(Item item);

    /**
     * @return the first inventory stack (not a copy) matching the filter, or {@link ItemStack#EMPTY}
     */
    ItemStack findItem(Predicate<ItemStack> filter);

    /**
     * Removes one item from the inventory.
     */
    boolean consumeItem(Item item);

    /**
     * @return whatever did not fit
     */
    ItemStack giveItem(ItemStack stack);

    void clearInventory();

    /**
     * What the bot hits with: the best of its default item and the weapons in its inventory.
     */
    ItemStack getWeapon();

    // ---------------------------------------------------------------------------------------------------------------
    // Movement
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Sets the velocity regardless of whether the bot stands on the ground (unlike {@link #jump(Vec3)}).
     */
    void launch(Vec3 velocity);

    /**
     * Climb up on ladders, vines, scaffolding... (only has an effect for the current tick).
     */
    void climb();

    boolean isClimbing();

    /**
     * How far the bot has fallen since it last touched the ground (the vanilla fall distance).
     */
    float getFallHeight();

    void setLook(float yaw, float pitch);

    // ---------------------------------------------------------------------------------------------------------------
    // Elytra
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * An elytra that can still fly is equipped or in the inventory.
     */
    boolean hasUsableElytra();

    boolean isGliding();

    /**
     * Equips an elytra from the inventory if needed and starts gliding. Only works in the air.
     */
    boolean startGliding();

    void stopGliding();

    /**
     * Uses a firework rocket from the inventory for a boost while gliding.
     */
    boolean fireRocket();

    boolean isRocketBoosting();

    // ---------------------------------------------------------------------------------------------------------------
    // Throwables & mace
    // ---------------------------------------------------------------------------------------------------------------

    boolean throwEnderPearl(float yaw, float pitch);

    boolean throwWindCharge(float yaw, float pitch);

    /**
     * Has a mace (inventory or default item).
     */
    boolean hasMace();

    /**
     * Falling fast enough (and not gliding) for a mace smash attack.
     */
    boolean canSmash();

    /**
     * Hits the target with the mace through the vanilla attack, so the smash bonus, enchantments, area knockback,
     * sounds and durability all behave like for a real player.
     *
     * @return whether it was a smash attack
     */
    boolean smash(LivingEntity target);

    // ---------------------------------------------------------------------------------------------------------------
    // Bow & totems
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Has a bow in the inventory and arrows to shoot with it.
     */
    boolean canShootBow();

    /**
     * Holds the bow up in the drawing pose (just the looks, {@link #shootBow} fires the arrow).
     */
    void drawBow();

    /**
     * Stops drawing the bow without shooting.
     */
    void lowerBow();

    /**
     * Shoots a fully drawn arrow in the given direction through the vanilla bow, so its enchantments (power, punch,
     * flame, infinity), durability and arrow use behave like for a real player.
     */
    boolean shootBow(float yaw, float pitch);

    /**
     * Moves a totem of undying from the inventory to the off hand, unless the off hand already holds one.
     *
     * @return whether a totem was moved
     */
    boolean equipTotem();
}
