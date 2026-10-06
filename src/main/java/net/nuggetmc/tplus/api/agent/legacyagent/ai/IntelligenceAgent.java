package net.nuggetmc.tplus.api.agent.legacyagent.ai;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.nuggetmc.tplus.api.AIManager;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.utils.*;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * Population based training session ({@code /ai reinforcement}).
 * <p>
 * Like the plugin, the session runs on its own thread and sleeps between the phases of a generation. Everything that touches
 * the world (spawning/removing bots, changing the agent, sending messages) is handed to the server thread.
 */
public class IntelligenceAgent {

    private static final Logger LOGGER = LogUtils.getLogger();

    /*
     * export all agent data to the plugin folder as separate folder things
     * commands /ai stop and /ai pause
     * if a session with name already exists keep adding underscores
     * /ai conclude or /ai finish
     * default anchor location, /ai relocateanchor
     */

    private final MinecraftServer server;
    private final BotManager manager;
    private final AIManager aiManager;

    private LegacyAgent agent;
    private final Thread thread;
    private volatile boolean active;

    private final String name;

    private final String botName;
    private final String botSkin;
    private final int cutoff;

    private final Map<String, Terminator> bots;

    private int populationSize;
    private volatile int generation;

    @Nullable
    private volatile UUID primary;
    @Nullable
    private volatile ServerPlayer primaryPlayer;

    private final Set<UUID> users;
    private final Map<Integer, Set<Map<BotNode, Map<BotDataType, Double>>>> genProfiles;

    public IntelligenceAgent(AIManager aiManager, int populationSize, String name, @Nullable String skin, BotManager manager) {
        this.server = manager.getServer();
        this.manager = manager;
        this.aiManager = aiManager;
        this.name = new SimpleDateFormat("yyyy-MM-dd HH-mm-ss").format(Calendar.getInstance().getTime());
        this.botName = name;
        this.botSkin = skin;
        this.bots = new ConcurrentHashMap<>();
        this.users = ConcurrentHashMap.newKeySet();
        this.cutoff = 5;
        this.genProfiles = new HashMap<>();
        this.populationSize = populationSize;
        this.active = true;

        this.thread = new Thread(() -> {
            try {
                task();
            } catch (Exception e) {
                print(e);
                print("The thread has been interrupted.");
                print("The session will now close.");
                close();
            }
        }, "TerminatorPlus AI session " + this.name);

        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void task() throws InterruptedException {
        setup();
        sleep(1000);

        while (active) {
            runGeneration();
        }

        sleep(5000);
        close();
    }

    private void runGeneration() throws InterruptedException {
        generation++;

        print("Starting generation " + ChatFormatting.RED + generation + ChatFormatting.RESET + "...");

        sleep(2000);

        String skinName = botSkin == null ? this.botName : botSkin;

        print("Fetching skin data for " + ChatFormatting.GREEN + skinName + ChatFormatting.RESET + "...");

        String[] skinData = MojangAPI.getSkin(skinName);

        String botName = this.botName.endsWith("%") ? this.botName : this.botName + "%";

        print("Creating " + (populationSize == 1 ? "new bot" : ChatFormatting.RED + NumberFormat.getInstance(Locale.US).format(populationSize) + ChatFormatting.RESET + " new bots")
                + " with name " + ChatFormatting.GREEN + botName.replace("%", ChatFormatting.LIGHT_PURPLE + "%" + ChatFormatting.RESET)
                + (botSkin == null ? "" : ChatFormatting.RESET + " and skin " + ChatFormatting.GREEN + botSkin)
                + ChatFormatting.RESET + "...");

        Set<Map<BotNode, Map<BotDataType, Double>>> loadedProfiles = genProfiles.get(generation);

        boolean created = callSync(() -> {
            ServerPlayer anchor = getPrimaryPlayer();

            if (anchor == null) {
                print("The primary user could not be found.");
                return false;
            }

            Location loc = PlayerUtils.findAbove(Location.of(anchor), 20);
            Set<Terminator> created1;

            if (loadedProfiles == null) {
                created1 = manager.createBots(loc, botName, skinData, populationSize, NeuralNetwork.RANDOM);
            } else {
                List<NeuralNetwork> networks = new ArrayList<>();
                loadedProfiles.forEach(profile -> networks.add(NeuralNetwork.createNetworkFromProfile(profile)));

                if (populationSize != networks.size()) {
                    print("An exception has occured.");
                    print("The stored population size differs from the size of the stored networks.");
                    return false;
                }

                created1 = manager.createBots(loc, botName, skinData, networks);
            }

            created1.forEach(bot -> {
                String name = bot.getBotName();

                while (this.bots.containsKey(name)) {
                    name += "_";
                }

                this.bots.put(name, bot);
            });

            return true;
        });

        if (!created) {
            close();
            throw new InterruptedException("The generation could not be created.");
        }

        while (bots.size() != populationSize) {
            sleep(1000);
        }

        sleep(2000);
        print("The bots will now attack each other.");

        callSync(() -> {
            agent.setTargetType(EnumTargetGoal.NEAREST_BOT);
            return null;
        });

        while (aliveCount() > 1) {
            sleep(1000);
        }

        print("Generation " + ChatFormatting.RED + generation + ChatFormatting.RESET + " has ended.");

        HashMap<Terminator, Integer> values = new HashMap<>();

        for (Terminator bot : bots.values()) {
            values.put(bot, bot.getAliveTicks());
        }

        List<Map.Entry<Terminator, Integer>> sorted = MathUtils.sortByValue(values);
        Set<Terminator> winners = new HashSet<>();

        int i = 1;

        for (Map.Entry<Terminator, Integer> entry : sorted) {
            Terminator bot = entry.getKey();
            boolean check = i <= cutoff;
            if (check) {
                print(ChatFormatting.GRAY + "[" + ChatFormatting.YELLOW + "#" + i + ChatFormatting.GRAY + "] " + ChatFormatting.GREEN + bot.getBotName()
                        + ChatUtils.BULLET_FORMATTED + ChatFormatting.RED + bot.getKills() + " kills");
                winners.add(bot);
            }

            i++;
        }

        sleep(3000);

        Map<BotNode, Map<BotDataType, List<Double>>> lists = new HashMap<>();

        winners.forEach(bot -> {
            Map<BotNode, Map<BotDataType, Double>> data = bot.getNeuralNetwork().values();

            data.forEach((nodeType, node) -> {
                if (!lists.containsKey(nodeType)) {
                    lists.put(nodeType, new HashMap<>());
                }

                Map<BotDataType, List<Double>> nodeValues = lists.get(nodeType);

                node.forEach((dataType, value) -> {
                    if (!nodeValues.containsKey(dataType)) {
                        nodeValues.put(dataType, new ArrayList<>());
                    }

                    nodeValues.get(dataType).add(value);
                });
            });
        });

        Set<Map<BotNode, Map<BotDataType, Double>>> profiles = new HashSet<>();

        double mutationSize = Math.pow(Math.E, 2); //MathUtils.getMutationSize(generation);

        for (int j = 0; j < populationSize; j++) {
            Map<BotNode, Map<BotDataType, Double>> profile = new HashMap<>();

            lists.forEach((nodeType, map) -> {
                Map<BotDataType, Double> points = new HashMap<>();

                map.forEach((dataType, dataPoints) -> {
                    double value = ((int) (10 * MathUtils.generateConnectionValue(dataPoints, mutationSize))) / 10D;

                    points.put(dataType, value);
                });

                profile.put(nodeType, points);
            });

            profiles.add(profile);
        }

        genProfiles.put(generation + 1, profiles);

        sleep(2000);

        clearBots();

        callSync(() -> {
            agent.setTargetType(EnumTargetGoal.NONE);
            return null;
        });
    }

    private int aliveCount() throws InterruptedException {
        return callSync(() -> (int) bots.values().stream().filter(Terminator::isBotAlive).count());
    }

    private void close() {
        server.execute(() -> aiManager.clearSession(this));
        stop(); // safety call
    }

    public void stop() {
        if (this.active) {
            this.active = false;
        }

        if (!thread.isInterrupted()) {
            this.thread.interrupt();
        }
    }

    private void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    /**
     * Runs the task on the server thread and waits for the result.
     */
    private <T> T callSync(Supplier<T> task) throws InterruptedException {
        if (!active || server.isStopped()) {
            throw new InterruptedException("The session is no longer active.");
        }

        try {
            return server.submit(() -> {
                // the session may have been stopped while this was waiting in the server's task queue
                if (!active) {
                    throw new CancellationException("The session is no longer active.");
                }
                return task.get();
            }).get();
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public String getName() {
        return name;
    }

    public int getGeneration() {
        return generation;
    }

    public void addUser(CommandSourceStack sender) {
        ServerPlayer player = sender.getPlayer();

        if (player == null) {
            // the console always receives the session output
            return;
        }

        if (!users.add(player.getUUID())) return;

        print(sender.getTextName() + " has been added to the userlist.");

        if (primary == null) {
            setPrimary(player);
        }
    }

    public void setPrimary(ServerPlayer player) {
        this.primary = player.getUUID();
        this.primaryPlayer = player;
        print(player.getName().getString() + " has been set as the primary user.");
    }

    /**
     * The primary user, looked up again in case they died (new player object) or relogged. Falls back to the last known
     * player object, so a session keeps spawning at the last known spot when its owner goes offline.
     */
    @Nullable
    private ServerPlayer getPrimaryPlayer() {
        UUID uuid = primary;

        if (uuid != null) {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);

            if (online != null) {
                primaryPlayer = online;
                return online;
            }
        }

        return primaryPlayer;
    }

    private void print(Object... objects) {
        String message = ChatFormatting.DARK_GREEN + "[REINFORCEMENT] " + ChatFormatting.RESET + String.join(" ", Arrays.stream(objects).map(String::valueOf).toArray(String[]::new));
        Component component = ChatUtils.legacy(message);

        if (server.isStopped()) {
            LOGGER.info(ChatUtils.stripColor(message));
            return;
        }

        server.execute(() -> {
            server.sendSystemMessage(component);

            for (UUID uuid : users) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);

                if (player != null) {
                    player.sendSystemMessage(component);
                }
            }
        });
    }

    private void setup() throws InterruptedException {
        clearBots();

        if (populationSize < cutoff) {
            populationSize = cutoff;
            print("The input value for the population size is lower than the cutoff (" + ChatFormatting.RED + cutoff + ChatFormatting.RESET + ")!"
                    + " The new population size is " + ChatFormatting.RED + populationSize + ChatFormatting.RESET + ".");
        }

        if (!(manager.getAgent() instanceof LegacyAgent legacyAgent)) {
            print("The AI manager currently only supports " + ChatFormatting.AQUA + "LegacyAgent" + ChatFormatting.RESET + ".");
            close();
            return;
        }

        agent = legacyAgent;

        callSync(() -> {
            agent.setTargetType(EnumTargetGoal.NONE);
            return null;
        });

        print("The bot target goal has been set to " + ChatFormatting.YELLOW + EnumTargetGoal.NONE.name() + ChatFormatting.RESET + ".");
        print("Disabling target offsets...");

        callSync(() -> {
            agent.offsets = false;
            return null;
        });

        print("Disabling bot drops...");

        callSync(() -> {
            agent.setDrops(false);
            return null;
        });

        print(ChatFormatting.GREEN + "Setup is now complete.");
    }

    private void clearBots() throws InterruptedException {
        if (!bots.isEmpty()) {
            print("Removing all cached bots...");

            callSync(() -> {
                bots.values().forEach(Terminator::removeBot);
                bots.clear();
                return null;
            });
        }
    }
}
