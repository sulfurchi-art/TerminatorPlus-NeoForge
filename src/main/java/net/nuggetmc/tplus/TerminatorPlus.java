package net.nuggetmc.tplus;

import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import net.nuggetmc.tplus.api.TerminatorPlusAPI;
import net.nuggetmc.tplus.api.scheduler.TaskScheduler;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotManagerImpl;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.command.commands.AICommand;
import net.nuggetmc.tplus.utils.SelfTest;
import org.slf4j.Logger;

import javax.annotation.Nullable;

@Mod(TerminatorPlus.MOD_ID)
public class TerminatorPlus {

    public static final String MOD_ID = "terminatorplus";
    public static final String NAME = "TerminatorPlus";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Same permission node as the plugin. Defaults to operators (permission level 2); permission mods can grant it to anyone.
     */
    public static final PermissionNode<Boolean> MANAGE = new PermissionNode<>(MOD_ID, "manage", PermissionTypes.BOOLEAN,
            (player, uuid, context) -> player != null && player.hasPermissions(2));

    private static TerminatorPlus instance;
    private static String version;

    private final CommandHandler handler;

    // Only exist while a server (dedicated or integrated) is running.
    @Nullable
    private MinecraftServer server;
    @Nullable
    private TaskScheduler scheduler;
    @Nullable
    private BotManagerImpl manager;

    public TerminatorPlus(IEventBus modEventBus, ModContainer container) {
        instance = this;
        version = container.getModInfo().getVersion().toString();

        this.handler = new CommandHandler();

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(this::onServerStarting);
        bus.addListener(this::onServerStopping);
        bus.addListener(this::onServerStopped);
        bus.addListener(this::onServerTick);
        bus.addListener(this::onCombatDamage);
        bus.addListener(net.neoforged.bus.api.EventPriority.LOWEST, this::onPlayerAttack);
        bus.addListener(this::onCoverBroken);
        bus.addListener(this::onCoverPlaced);
        bus.addListener(this::onMissileWarning);
        bus.addListener(this::onRegisterCommands);
        bus.addListener(this::onPermissionNodes);
        bus.addListener(this::onPlayerLoggedIn);
        bus.addListener(this::onLivingDrops);
        bus.addListener(this::onLivingChangeTarget);

        if (Boolean.getBoolean("terminatorplus.selftest")) {
            SelfTest.register();
        }
    }

    public static TerminatorPlus getInstance() {
        return instance;
    }

    public static String getVersion() {
        return version;
    }

    /**
     * The bot manager of the running server, or {@code null} when no server is running.
     */
    public static BotManagerImpl getManager() {
        return instance.manager;
    }

    public static TaskScheduler getScheduler() {
        return instance.scheduler;
    }

    public static CommandHandler getHandler() {
        return instance.handler;
    }

    public static boolean canManage(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();

        if (player == null || player instanceof Bot) {
            return source.hasPermission(2);
        }

        return PermissionAPI.getPermission(player, MANAGE);
    }

    private void onServerStarting(ServerStartingEvent event) {
        this.server = event.getServer();
        this.scheduler = new TaskScheduler();
        this.manager = new BotManagerImpl(server, scheduler);

        TerminatorPlusAPI.setBotManager(manager);

        LOGGER.info("TerminatorPlus {} ready", version);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        if (handler.getCommand("ai") instanceof AICommand ai) {
            ai.clearSession();
        }

        if (manager != null) {
            try { ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills().saveLearning(); }
            catch (java.io.IOException e) { LOGGER.error("Cannot save opponent learning", e); }
            try { manager.saveSettings(); }
            catch (IllegalStateException e) { LOGGER.error("Cannot save TerminatorPlus settings", e); }
            manager.reset();
        }
    }

    private void onCombatDamage(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (manager == null || event.getNewDamage() <= 0 || !(event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity attacker)) return;
        var skills = ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills();
        skills.onTeamDamage(event.getEntity(), attacker);
        if (attacker instanceof Bot bot) {
            boolean arrow = event.getSource().getDirectEntity() instanceof net.minecraft.world.entity.projectile.AbstractArrow;
            boolean smash = bot.getMainHandItem().is(net.minecraft.world.item.Items.MACE) && bot.canSmash();
            skills.onConfirmedDamage(bot, event.getEntity(), arrow, smash);
        }
    }

    private void onPlayerAttack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (manager != null && !event.isCanceled() && event.getEntity() instanceof ServerPlayer player
                && !(player instanceof Bot) && event.getTarget() instanceof net.minecraft.world.entity.LivingEntity target)
            ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills().onPlayerAttack(player, target);
    }

    private void onCoverBroken(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (manager != null && !event.isCanceled() && event.getLevel() instanceof net.minecraft.server.level.ServerLevel world)
            ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills().invalidateCover(world, event.getPos());
    }
    private void onMissileWarning(net.neoforged.neoforge.event.PlayLevelSoundEvent.AtPosition event) {
        if (manager == null || event.isCanceled() || event.getSound() == null || !(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        var id = event.getSound().value().getLocation();
        if (id.getNamespace().equals("superbwarfare") && (id.getPath().equals("locked_warning") || id.getPath().equals("locking_warning")))
            ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills().missiles().warning(level, event.getPosition(), level.getServer().getTickCount());
    }
    private void onCoverPlaced(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (manager != null && !event.isCanceled() && event.getLevel() instanceof net.minecraft.server.level.ServerLevel world)
            ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getSkills().invalidateCover(world, event.getPos());
    }

    private void onServerStopped(ServerStoppedEvent event) {
        if (scheduler != null) {
            scheduler.cancelAll();
        }

        TerminatorPlusAPI.setBotManager(null);

        this.manager = null;
        this.scheduler = null;
        this.server = null;
    }

    private void onServerTick(ServerTickEvent.Pre event) {
        if (scheduler != null) {
            scheduler.tick();
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        handler.registerBrigadier(event.getDispatcher());
    }

    private void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(MANAGE);
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (manager != null) {
            manager.onJoin(event);
        }
    }

    private void onLivingDrops(LivingDropsEvent event) {
        if (manager != null) {
            manager.onDrops(event);
        }
    }

    private void onLivingChangeTarget(LivingChangeTargetEvent event) {
        if (manager != null) {
            manager.onMobTarget(event);
        }
    }
}
