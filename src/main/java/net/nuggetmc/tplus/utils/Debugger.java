package net.nuggetmc.tplus.utils;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.IntelligenceAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.NeuralNetwork;
import net.nuggetmc.tplus.api.scheduler.TaskScheduler;
import net.nuggetmc.tplus.api.scheduler.TickTask;
import net.nuggetmc.tplus.api.utils.*;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.BotManagerImpl;
import net.nuggetmc.tplus.command.commands.AICommand;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * {@code /bot debug <method(args)>}: calls one of the public methods below by name.
 * (The plugin used {@code java.beans.Statement}; that lives in the desktop module, so this does the lookup by hand.)
 */
public class Debugger {

    private final CommandSourceStack sender;
    public static final Set<String> AUTOFILL_METHODS = new HashSet<>();

    private static TickTask yVelTracker;

    static {
        for (Method method : Debugger.class.getDeclaredMethods()) {
            if (isDebugMethod(method)) {
                String autofill = method.getName() + "(";
                for (Parameter par : method.getParameters()) {
                    autofill += par.getType().getSimpleName() + ",";
                }
                autofill = method.getParameters().length > 0 ? autofill.substring(0, autofill.length() - 1) : autofill;
                autofill += ")";
                AUTOFILL_METHODS.add(autofill);
            }
        }
    }

    private static boolean isDebugMethod(Method method) {
        int modifiers = method.getModifiers();
        return Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers)
                && !method.getName().equals("print") && !method.getName().equals("execute") && !method.getName().equals("buildObjects")
                && !method.getName().startsWith("lambda$");
    }

    public Debugger(CommandSourceStack sender) {
        this.sender = sender;
    }

    private void print(Object... objects) {
        ChatUtils.send(sender, DebugLogUtils.PREFIX + String.join(" ", DebugLogUtils.fromStringArray(objects)));
    }

    private static BotManagerImpl manager() {
        return TerminatorPlus.getManager();
    }

    private static TaskScheduler scheduler() {
        return TerminatorPlus.getScheduler();
    }

    private MinecraftServer server() {
        return sender.getServer();
    }

    private ServerPlayer player() {
        return sender.getPlayer();
    }

    public void execute(String cmd) {
        try {
            int[] pts = {cmd.indexOf('('), cmd.indexOf(')')};
            if (pts[0] == -1 || pts[1] == -1) throw new IllegalArgumentException();

            String name = cmd.substring(0, pts[0]);
            String content = cmd.substring(pts[0] + 1, pts[1]);

            Object[] args = content.isEmpty() ? new Object[0] : buildObjects(content);

            print("Running the expression \"" + ChatFormatting.AQUA + cmd + ChatFormatting.RESET + "\"...");
            invoke(name, args);
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            print("Error: the expression \"" + ChatFormatting.AQUA + cmd + ChatFormatting.RESET + "\" failed to execute.");
            print(cause.toString());
        }
    }

    private void invoke(String name, Object[] args) throws Exception {
        for (Method method : Debugger.class.getMethods()) {
            if (!method.getName().equals(name) || !isDebugMethod(method) || method.getParameterCount() != args.length) {
                continue;
            }

            Class<?>[] types = method.getParameterTypes();
            Object[] converted = new Object[args.length];
            boolean applicable = true;

            for (int i = 0; i < args.length && applicable; i++) {
                converted[i] = convert(args[i], types[i]);
                applicable = converted[i] != null;
            }

            if (applicable) {
                method.invoke(this, converted);
                return;
            }
        }

        throw new NoSuchMethodException(name + Arrays.toString(args));
    }

    private static Object convert(Object value, Class<?> type) {
        if ((type == int.class || type == Integer.class) && value instanceof Integer) return value;
        if (type == double.class || type == Double.class) {
            if (value instanceof Double) return value;
            if (value instanceof Integer i) return i.doubleValue();
        }
        if ((type == boolean.class || type == Boolean.class) && value instanceof Boolean) return value;
        if (type == String.class) return String.valueOf(value);
        return null;
    }

    public Object[] buildObjects(String content) {
        List<Object> list = new ArrayList<>();

        if (!content.isEmpty()) {
            String[] values = content.split(",");

            for (String str : values) {
                String value = str.startsWith(" ") ? str.substring(1) : str;
                Object obj = value;

                try {
                    obj = Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                }

                try {
                    obj = Integer.parseInt(value);
                } catch (NumberFormatException ignored) {
                }

                if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
                    obj = Boolean.parseBoolean(value);
                }

                list.add(obj);
            }
        }

        return list.toArray();
    }

    /*
     * DEBUGGER METHODS
     */

    public void mobWaves(int n) {
        ServerLevel world = server().getLevel(Level.OVERWORLD);

        if (world == null) {
            print("world is null");
            return;
        }

        Set<Location> locs = new HashSet<>();
        locs.add(new Location(world, 128, 36, -142));
        locs.add(new Location(world, 236, 44, -179));
        locs.add(new Location(world, 310, 36, -126));
        locs.add(new Location(world, 154, 35, -101));
        locs.add(new Location(world, 202, 46, -46));
        locs.add(new Location(world, 274, 52, -44));
        locs.add(new Location(world, 297, 38, -97));
        locs.add(new Location(world, 271, 43, -173));
        locs.add(new Location(world, 216, 50, -187));
        locs.add(new Location(world, 181, 35, -150));

        ServerPlayer player = player();

        if (player != null) {
            server().getCommands().performPrefixedCommand(player.createCommandSourceStack(), "team join a @a");

            broadcast(ChatFormatting.YELLOW + "Starting wave " + ChatFormatting.RED + n + ChatFormatting.YELLOW + "...");

            broadcast(ChatFormatting.YELLOW + "Unleashing the Super Zombies...");

            String name = "*";

            CompletableFuture.supplyAsync(() -> MojangAPI.getSkin("Lozimac"), Util.ioPool()).thenAcceptAsync(skin -> {
                switch (n) {
                    case 1: {
                        for (int i = 0; i < 20; i++) {
                            Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                        }
                        break;
                    }

                    case 2: {
                        for (int i = 0; i < 30; i++) {
                            Bot bot = Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                            bot.setDefaultItem(new ItemStack(Items.WOODEN_AXE));
                        }
                        break;
                    }

                    case 3: {
                        for (int i = 0; i < 30; i++) {
                            Bot bot = Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                            bot.setNeuralNetwork(NeuralNetwork.generateRandomNetwork());
                            bot.setShield(true);
                            bot.setDefaultItem(new ItemStack(Items.STONE_AXE));
                        }
                        break;
                    }

                    case 4: {
                        for (int i = 0; i < 40; i++) {
                            Bot bot = Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                            bot.setNeuralNetwork(NeuralNetwork.generateRandomNetwork());
                            bot.setShield(true);
                            bot.setDefaultItem(new ItemStack(Items.IRON_AXE));
                        }
                        break;
                    }

                    case 5: {
                        for (int i = 0; i < 50; i++) {
                            Bot bot = Bot.createBot(MathUtils.getRandomSetElement(locs), name, skin);
                            bot.setNeuralNetwork(NeuralNetwork.generateRandomNetwork());
                            bot.setShield(true);
                            bot.setDefaultItem(new ItemStack(Items.DIAMOND_AXE));
                        }
                        break;
                    }
                }

                broadcast(ChatFormatting.YELLOW + "The Super Zombies have been unleashed.");

                hideNametags();
            }, server());
        }
    }

    private void broadcast(String message) {
        server().getPlayerList().broadcastSystemMessage(ChatUtils.legacy(message), false);
    }

    public void renderBots() {
        int rendered = 0;
        for (Terminator fetch : manager().fetch()) {
            rendered++;
            for (ServerPlayer viewer : server().getPlayerList().getPlayers()) {
                fetch.renderBot(viewer, true);
            }
        }
        print("Rendered " + rendered + " bots.");
    }

    public void lol(String name, String skinName) {
        CompletableFuture.supplyAsync(() -> MojangAPI.getSkin(skinName), Util.ioPool()).thenAcceptAsync(skin -> {
            for (ServerPlayer player : realPlayers()) {
                Bot.createBot(Location.of(player), name, skin);
            }
        }, server());
    }

    private List<ServerPlayer> realPlayers() {
        return server().getPlayerList().getPlayers().stream().filter(p -> !(p instanceof Terminator)).collect(Collectors.toList());
    }

    public void colorTest() {
        ServerPlayer player = player();
        if (player == null) return;
        Location loc = Location.of(player);

        CompletableFuture.supplyAsync(() -> MojangAPI.getSkin("Kubepig"), Util.ioPool()).thenAcceptAsync(skin -> {
            long delay = 0;

            for (int n = 1; n <= 40; n++) {
                int wait = (int) (Math.pow(1.05, 130 - n) + 100);
                delay += Math.max(1, wait / 50);

                scheduler().runTaskLater(() -> {
                    Bot.createBot(PlayerUtils.findBottom(loc.add(Math.random() * 20 - 10, 0, Math.random() * 20 - 10)), ChatFormatting.GREEN + "-$26.95", skin);
                    player.playNotifySound(SoundEvents.ITEM_PICKUP, SoundSource.MASTER, 1, 1);
                }, delay);
            }
        }, server());
    }

    public void tpall() {
        ServerPlayer player = player();
        if (player == null) return;
        manager().fetch().stream().filter(Terminator::isBotAlive).forEach(bot ->
                bot.getEntity().teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
    }

    public void viewsession() {
        IntelligenceAgent session = ((AICommand) TerminatorPlus.getHandler().getCommand("ai")).getSession();

        if (session == null) {
            print("No session is currently active.");
            return;
        }

        realPlayers().stream().filter(p -> server().getPlayerList().isOp(p.getGameProfile()))
                .forEach(p -> session.addUser(p.createCommandSourceStack()));
    }

    public void block() {
        manager().fetch().forEach(bot -> bot.block(10, 10));
    }

    public void shield() {
        manager().fetch().forEach(bot -> bot.setShield(true));
    }

    public void totem() {
        manager().fetch().forEach(bot -> bot.setItemOffhand(new ItemStack(Items.TOTEM_OF_UNDYING)));
    }

    public void clearMainHand() {
        manager().fetch().forEach(bot -> bot.setItem(ItemStack.EMPTY));
    }

    public void clearOffHand() {
        manager().fetch().forEach(bot -> bot.setItemOffhand(ItemStack.EMPTY));
    }

    public void offsets(boolean b) {
        Agent agent = manager().getAgent();
        if (!(agent instanceof LegacyAgent legacyAgent)) {
            print("This method currently only supports " + ChatFormatting.AQUA + "LegacyAgent" + ChatFormatting.RESET + ".");
            return;
        }

        legacyAgent.offsets = b;

        print("Bot target offsets are now " + (legacyAgent.offsets ? ChatFormatting.GREEN + "ENABLED" : ChatFormatting.RED + "DISABLED") + ChatFormatting.RESET + ".");
    }

    public void confuse(int n) {
        ServerPlayer player = player();
        if (player == null) return;

        Location loc = Location.of(player);

        double f = n < 100 ? .004 * n : .4;

        List<ServerPlayer> online = realPlayers();
        List<String> names = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            ServerPlayer target = online.isEmpty() ? null : online.get((int) (online.size() * Math.random()));
            names.add(target == null ? "Steve" : target.getGameProfile().getName());
        }

        // fetch every distinct skin once, off the server thread
        CompletableFuture.supplyAsync(() -> {
            Map<String, String[]> skins = new HashMap<>();
            for (String name : new HashSet<>(names)) {
                skins.put(name, MojangAPI.getSkin(name));
            }
            return skins;
        }, Util.ioPool()).thenAcceptAsync(skins -> {
            for (String name : names) {
                Bot bot = Bot.createBot(loc, name, skins.get(name));
                bot.setVelocity(new Vec3(Math.random() - 0.5, 0.5, Math.random() - 0.5).normalize().scale(f));
                bot.faceLocation(bot.getLocation().add(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5));
            }

            loc.level().sendParticles(ParticleTypes.CLOUD, loc.x(), loc.y(), loc.z(), 100, 1, 1, 1, 0.5);
        }, server());
    }

    public void dreamsmp() {
        spawnBots(Arrays.asList("Dream", "GeorgeNotFound", "Callahan", "Sapnap", "awesamdude", "Ponk", "BadBoyHalo", "TommyInnit", "Tubbo_", "ItsFundy", "Punz", "Purpled", "WilburSoot", "Jschlatt", "Skeppy", "The_Eret", "JackManifoldTV", "Nihachu", "Quackity", "KarlJacobs", "HBomb94", "Technoblade", "Antfrost", "Ph1LzA", "ConnorEatsPants", "CaptainPuffy", "Vikkstar123", "LazarCodeLazar", "Ranboo", "FoolishG", "hannahxxrose", "Slimecicle", "Michaelmcchill"));
    }

    private void spawnBots(List<String> players) {
        ServerPlayer player = player();
        if (player == null) return;

        print("Processing request asynchronously...");

        Location loc = Location.of(player);
        List<String> shuffled = new ArrayList<>(players);
        Collections.shuffle(shuffled);

        CompletableFuture.runAsync(() -> {
            try {
                server().execute(() -> print("Fetching skin data from the Mojang API for:"));

                Map<String, String[]> skinCache = new LinkedHashMap<>();

                int size = shuffled.size();
                int i = 1;

                for (String name : shuffled) {
                    int index = i;
                    server().execute(() -> print(name, ChatFormatting.GRAY + "(" + ChatFormatting.GREEN + index + ChatFormatting.GRAY + "/" + size + ")"));
                    String[] skin = MojangAPI.getSkin(name);
                    skinCache.put(name, skin);

                    i++;
                }

                server().execute(() -> {
                    print("Creating bots...");

                    double f = .004 * shuffled.size();

                    skinCache.forEach((name, skin) -> {
                        Bot bot = Bot.createBot(loc, name, skin);
                        bot.setVelocity(new Vec3(Math.random() - 0.5, 0.5, Math.random() - 0.5).normalize().scale(f));
                        bot.faceLocation(bot.getLocation().add(Math.random() - 0.5, Math.random() - 0.5, Math.random() - 0.5));
                    });

                    loc.level().sendParticles(ParticleTypes.CLOUD, loc.x(), loc.y(), loc.z(), 100, 1, 1, 1, 0.5);

                    print("Done.");
                });
            } catch (Exception e) {
                server().execute(() -> print(e));
            }
        }, Util.ioPool());
    }

    public void item() {
        manager().fetch().forEach(b -> b.setDefaultItem(new ItemStack(Items.IRON_SWORD)));
    }

    public void j(boolean b) {
        manager().joinMessages = b;
    }

    public void epic(int n) {
        if (player() == null) return;

        print("Fetching names asynchronously...");

        List<String> players = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            String name = PlayerUtils.randomName(server());
            if (name == null) break;
            players.add(name);
            print(name);
        }

        spawnBots(players);
    }

    public void tp() {
        Terminator bot = MathUtils.getRandomSetElement(manager().fetch().stream().filter(Terminator::isBotAlive).collect(Collectors.toSet()));

        if (bot == null) {
            print("Failed to locate a bot.");
            return;
        }

        print("Located bot", (ChatFormatting.GREEN + bot.getBotName() + ChatFormatting.RESET + "."));

        ServerPlayer player = player();
        if (player != null) {
            print("Teleporting...");
            ServerPlayer entity = bot.getEntity();
            player.teleportTo(bot.getBotLevel(), entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
        }
    }

    public void setTarget(int n) {
        print("This has been established as a feature as \"" + ChatFormatting.AQUA + "/bot settings setgoal" + ChatFormatting.RESET + "\"!");
    }

    /**
     * Prints the sender's y velocity every tick. Call it again to stop.
     */
    public void trackYVel() {
        ServerPlayer player = player();
        if (player == null) return;

        if (yVelTracker != null && !yVelTracker.isCancelled()) {
            yVelTracker.cancel();
            yVelTracker = null;
            print("Stopped tracking.");
            return;
        }

        yVelTracker = scheduler().runTaskTimer(() -> print(player.getDeltaMovement().y), 0, 1);
    }

    private static ArmorStand createSeat(ServerLevel world, double x, double y, double z, boolean gravity) {
        ArmorStand seat = new ArmorStand(world, x, y, z);
        seat.setInvisible(true);
        seat.setSmall(true);
        seat.setNoGravity(!gravity);
        world.addFreshEntity(seat);
        return seat;
    }

    public void hideNametags() { // this works for some reason
        for (Terminator bot : manager().fetch()) {
            ServerPlayer entity = bot.getEntity();
            ServerLevel world = bot.getBotLevel();

            ArmorStand seat = createSeat(world, entity.getBlockX() + 0.5, entity.getBlockY() + 0.5, entity.getBlockZ() + 0.5, true);

            seat.startRiding(entity, true);
        }
    }

    public void sit() {
        for (Terminator bot : manager().fetch()) {
            ServerPlayer entity = bot.getEntity();
            ServerLevel world = bot.getBotLevel();

            ArmorStand seat = createSeat(world, entity.getBlockX() + 0.5, entity.getBlockY() - 1.5, entity.getBlockZ() + 0.5, false);

            entity.startRiding(seat, true);
        }
    }

    public void look() {
        ServerPlayer player = player();
        if (player == null) {
            print("Unspecified player.");
            return;
        }

        for (Terminator bot : manager().fetch()) {
            bot.faceLocation(player.getEyePosition());
        }
    }

    public void toggleAgent() {
        Agent agent = manager().getAgent();

        boolean b = agent.isEnabled();
        agent.setEnabled(!b);

        print("The Bot Agent is now " + (b ? ChatFormatting.RED + "DISABLED" : ChatFormatting.GREEN + "ENABLED") + ChatFormatting.RESET + ".");
    }

    public void printSurroundingMobs(double dist) {
        ServerPlayer player = player();
        if (player == null) {
            print("You must be a player to call this.");
            return;
        }

        double distSq = Math.pow(dist, 2);
        for (Entity en : player.serverLevel().getAllEntities()) {
            if (en.distanceToSqr(player) < distSq)
                print(String.format("Entity at " + ChatFormatting.BLUE + "(%d, %d, %d)" + ChatFormatting.RESET + ": Type " + ChatFormatting.GREEN + "%s" + ChatFormatting.RESET,
                        en.getBlockX(), en.getBlockY(), en.getBlockZ(), en.getType().toShortString()));
        }
    }
}
