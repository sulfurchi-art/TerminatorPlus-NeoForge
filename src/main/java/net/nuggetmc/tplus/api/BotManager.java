package net.nuggetmc.tplus.api;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.scheduler.TaskScheduler;
import net.nuggetmc.tplus.api.utils.Location;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface BotManager {
    Set<Terminator> fetch();

    Agent getAgent();

    MinecraftServer getServer();

    TaskScheduler getScheduler();

    void add(Terminator bot);

    /**
     * @param target if given, the bot closest to this spot (in the same level) wins
     */
    @Nullable
    Terminator getFirst(String name, @Nullable Location target);

    List<String> fetchNames();

    Terminator createBot(Location loc, String name, String skin, String signature);

    /**
     * Command entry point: fetches the skin asynchronously, then spawns the bots on the server thread.
     */
    void createBots(@Nullable CommandSourceStack sender, String name, @Nullable String skinName, int n, @Nullable Location location);

    void createBots(@Nullable CommandSourceStack sender, String name, @Nullable String skinName, int n, @Nullable NeuralNetwork network, @Nullable Location location);

    Set<Terminator> createBots(Location loc, String name, @Nullable String[] skin, List<NeuralNetwork> networks);

    Set<Terminator> createBots(Location loc, String name, @Nullable String[] skin, int n, @Nullable NeuralNetwork network);

    void remove(Terminator bot);

    void reset();

    @Nullable
    Terminator getBot(UUID uuid);

    @Nullable
    Terminator getBot(int entityId);

    boolean isMobTarget();

    void setMobTarget(boolean mobTarget);

    boolean addToPlayerList();

    void setAddToPlayerList(boolean addPlayerList);
}
