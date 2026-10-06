package net.nuggetmc.tplus.bot;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.event.BotDeathEvent;
import net.nuggetmc.tplus.api.scheduler.TaskScheduler;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.Location;
import net.nuggetmc.tplus.api.utils.MojangAPI;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Keeps track of the bots of the running server. One instance per server start.
 */
public class BotManagerImpl implements BotManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final MinecraftServer server;
    private final TaskScheduler scheduler;
    private final Agent agent;
    private final Set<Terminator> bots;
    private final NumberFormat numberFormat;

    public boolean joinMessages = false;
    private boolean mobTarget = false;
    private boolean addPlayerList = false;

    public BotManagerImpl(MinecraftServer server, TaskScheduler scheduler) {
        this.server = server;
        this.scheduler = scheduler;
        this.bots = ConcurrentHashMap.newKeySet(); //should fix concurrentmodificationexception
        this.numberFormat = NumberFormat.getInstance(Locale.US);
        this.agent = new LegacyAgent(this);
    }

    @Override
    public Set<Terminator> fetch() {
        return bots;
    }

    @Override
    public MinecraftServer getServer() {
        return server;
    }

    @Override
    public TaskScheduler getScheduler() {
        return scheduler;
    }

    @Override
    public void add(Terminator bot) {
        if (joinMessages) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(bot.getBotName() + " joined the game").withStyle(ChatFormatting.YELLOW), false);
        }

        bots.add(bot);
    }

    @Nullable
    @Override
    public Terminator getFirst(String name, @Nullable Location target) {
        if (target != null) {
            Terminator closest = null;
            for (Terminator bot : bots) {
                if (name.equals(bot.getBotName()) && (closest == null || isCloser(target, bot, closest))) {
                    closest = bot;
                }
            }
            return closest;
        }
        for (Terminator bot : bots) {
            if (name.equals(bot.getBotName())) {
                return bot;
            }
        }

        return null;
    }

    /**
     * Bots in the target's level are always closer than bots in other levels.
     */
    private static boolean isCloser(Location target, Terminator bot, Terminator closest) {
        boolean botSameLevel = bot.getBotLevel() == target.level();
        boolean closestSameLevel = closest.getBotLevel() == target.level();

        if (botSameLevel != closestSameLevel) {
            return botSameLevel;
        }

        Vec3 pos = target.position();
        return pos.distanceToSqr(bot.getLocation()) < pos.distanceToSqr(closest.getLocation());
    }

    @Override
    public List<String> fetchNames() {
        return bots.stream().map(Terminator::getBotName).collect(Collectors.toList());
    }

    @Override
    public Terminator createBot(Location loc, String name, String skin, String sig) {
        return Bot.createBot(loc, name, new String[]{skin, sig});
    }

    @Override
    public Agent getAgent() {
        return agent;
    }

    @Override
    public void createBots(@Nullable CommandSourceStack sender, String name, @Nullable String skinName, int n, @Nullable Location loc) {
        createBots(sender, name, skinName, n, null, loc);
    }

    @Override
    public void createBots(@Nullable CommandSourceStack sender, String name, @Nullable String skinName, int n, @Nullable NeuralNetwork network, @Nullable Location location) {
        long timestamp = System.currentTimeMillis();

        if (n < 1) n = 1;

        if (sender != null) {
            String message = "Creating " + (n == 1 ? "new bot" : ChatFormatting.RED + numberFormat.format(n) + ChatFormatting.RESET + " new bots")
                    + " with name " + ChatFormatting.GREEN + name.replace("%", ChatFormatting.LIGHT_PURPLE + "%" + ChatFormatting.RESET)
                    + (skinName == null ? "" : ChatFormatting.RESET + " and skin " + ChatFormatting.GREEN + skinName)
                    + ChatFormatting.RESET + "...";
            ChatUtils.send(sender, message);
        }

        String skin = skinName == null ? name : skinName;

        Location spawn;

        if (location != null) {
            spawn = location;
        } else if (sender != null && sender.getPlayer() != null) {
            spawn = Location.of(sender.getPlayer());
        } else {
            spawn = new Location(server.overworld(), 0, 0, 0);
            if (sender != null)
                ChatUtils.send(sender, ChatFormatting.RED + "No location specified, defaulting to " + spawn.x() + ", " + spawn.y() + ", " + spawn.z() + ".");
        }

        int count = n;

        // The skin lookup talks to the Mojang API: do it off the server thread, then spawn the bots on it.
        CompletableFuture.supplyAsync(() -> MojangAPI.getSkin(skin), Util.ioPool())
                .thenAcceptAsync(skinData -> {
                    createBots(spawn, name, skinData, count, network);

                    if (sender != null)
                        ChatUtils.send(sender, "Process completed (" + ChatFormatting.RED + ((System.currentTimeMillis() - timestamp) / 1000D) + "s" + ChatFormatting.RESET + ").");
                }, server)
                .exceptionally(throwable -> {
                    LOGGER.error("Failed to create bots", throwable);
                    if (sender != null) server.execute(() -> ChatUtils.send(sender, ChatUtils.EXCEPTION_MESSAGE));
                    return null;
                });
    }

    @Override
    public Set<Terminator> createBots(Location loc, String name, @Nullable String[] skin, int n, @Nullable NeuralNetwork network) {
        List<NeuralNetwork> networks = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            networks.add(network);
        }

        return createBots(loc, name, skin, networks);
    }

    @Override
    public Set<Terminator> createBots(Location loc, String name, @Nullable String[] skin, List<NeuralNetwork> networks) {
        Set<Terminator> bots = new HashSet<>();
        ServerLevel world = loc.level();

        int n = networks.size();
        int i = 1;

        double f = n < 100 ? .004 * n : .4;

        for (NeuralNetwork network : networks) {
            Bot bot = Bot.createBot(loc, name.replace("%", String.valueOf(i)), skin);

            if (network != null) {
                bot.setNeuralNetwork(network == NeuralNetwork.RANDOM ? NeuralNetwork.generateRandomNetwork() : network);
                bot.setShield(true);
                bot.setDefaultItem(new ItemStack(Items.WOODEN_AXE));
                //bot.setRemoveOnDeath(false);
            }

            if (network != null) {
                bot.setVelocity(randomVelocity());
            } else if (i > 1) {
                bot.setVelocity(randomVelocity().scale(f));
            }

            bots.add(bot);
            i++;
        }

        world.sendParticles(ParticleTypes.CLOUD, loc.x(), loc.y(), loc.z(), 100, 1, 1, 1, 0.5);

        return bots;
    }

    private Vec3 randomVelocity() {
        return new Vec3(Math.random() - 0.5, 0.5, Math.random() - 0.5).normalize();
    }

    @Override
    public void remove(Terminator bot) {
        bots.remove(bot);
    }

    @Override
    public void reset() {
        if (!bots.isEmpty()) {
            bots.forEach(Terminator::removeBot);
            bots.clear(); // Not always necessary, but a good security measure
        }

        agent.stopAllTasks();
    }

    @Nullable
    @Override
    public Terminator getBot(UUID uuid) {
        for (Terminator bot : bots) {
            if (bot.getEntity().getUUID().equals(uuid)) {
                return bot;
            }
        }
        return null;
    }

    @Nullable
    @Override
    public Terminator getBot(int entityId) {
        for (Terminator bot : bots) {
            if (bot.getEntityId() == entityId) {
                return bot;
            }
        }
        return null;
    }

    @Override
    public boolean isMobTarget() {
        return mobTarget;
    }

    @Override
    public void setMobTarget(boolean mobTarget) {
        this.mobTarget = mobTarget;
    }

    @Override
    public boolean addToPlayerList() {
        return addPlayerList;
    }

    @Override
    public void setAddToPlayerList(boolean addPlayerList) {
        this.addPlayerList = addPlayerList;
    }

    // ---- event hooks (wired up in TerminatorPlus) ----

    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof Terminator)) {
            bots.forEach(bot -> bot.renderBot(player, true));
        }
    }

    /**
     * Death drops of a bot (the plugin listened to EntityDeathEvent for this).
     */
    public void onDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof Terminator bot && bots.contains(bot)) {
            agent.onBotDeath(new BotDeathEvent(bot, event.getSource(), event.getDrops()));
        }
    }

    public void onMobTarget(LivingChangeTargetEvent event) {
        LivingEntity target = event.getNewAboutToBeSetTarget();

        if (mobTarget || target == null)
            return;

        if (target instanceof Terminator bot && bots.contains(bot)) {
            event.setCanceled(true);
        }
    }
}
