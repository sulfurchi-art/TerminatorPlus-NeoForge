package net.nuggetmc.tplus.utils;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.ai.IntelligenceAgent;
import net.nuggetmc.tplus.api.scheduler.TaskScheduler;
import net.nuggetmc.tplus.api.scheduler.TickTask;
import net.nuggetmc.tplus.api.utils.Location;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.EquipmentPresets;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.BotMemory;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.scores.PlayerTeam;
import net.nuggetmc.tplus.command.commands.AICommand;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Development smoke test, only active with {@code -Dterminatorplus.selftest=true} (the {@code runSelfTest} Gradle task).
 * Drives the mod through its commands on a real dedicated server, checks the outcome and stops the server again.
 */
public final class SelfTest {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final MinecraftServer server;
    private final ServerLevel level;
    private final CommandSourceStack console;
    private final TaskScheduler scheduler;
    private final List<String> failures = new ArrayList<>();
    private int checks;
    private long time;
    private final java.util.Map<java.util.UUID, List<net.minecraft.network.protocol.Packet<?>>> humanPackets = new java.util.HashMap<>();
    private final List<Runnable> scenarioHooks = new ArrayList<>();

    private SelfTest(MinecraftServer server) {
        this.server = server;
        this.level = server.overworld();
        this.console = server.createCommandSourceStack();
        this.scheduler = TerminatorPlus.getScheduler();
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> new SelfTest(event.getServer()).start());
    }

    private int ground(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    private void at(long ticks, String description, Runnable step) {
        time += ticks;
        scheduler.runTaskLater(() -> {
            LOGGER.info("[SelfTest] ---- {} ----", description);
            try {
                step.run();
            } catch (Throwable t) {
                fail(description + " threw " + t);
                LOGGER.error("[SelfTest] step failed", t);
            }
        }, time);
    }

    private void run(String command) {
        LOGGER.info("[SelfTest] > /{}", command);
        server.getCommands().performPrefixedCommand(console, command);
    }

    private void check(String name, boolean ok) {
        checks++;
        if (ok) {
            LOGGER.info("[SelfTest] PASS {}", name);
        } else {
            fail(name);
        }
    }

    private void fail(String name) {
        failures.add(name);
        LOGGER.error("[SelfTest] FAIL {}", name);
    }

    private List<Terminator> bots(String prefix) {
        return TerminatorPlus.getManager().fetch().stream().filter(b -> b.getBotName().startsWith(prefix)).collect(Collectors.toList());
    }

    private void logBots() {
        for (Terminator bot : TerminatorPlus.getManager().fetch()) {
            LOGGER.info("[SelfTest]   {} pos={} hp={} alive={} ground={} kills={} main={} vel={}", bot.getBotName(),
                    bot.getEntity().blockPosition().toShortString(), String.format("%.1f", bot.getBotHealth()), bot.isBotAlive(),
                    bot.isBotOnGround(), bot.getKills(), bot.getEntity().getMainHandItem(), bot.getVelocity());
        }
    }

    private void start() {
        legacy().getSkillSettings().load(new net.nuggetmc.tplus.api.agent.legacyagent.skill.SkillSettings().save());
        legacy().getSkillSettings().defaultEquipment = false;
        run("bot settings hardness 7");
        run("bot preset use none");
        LOGGER.info("[SelfTest] starting, flat world surface at y={}", ground(0, 0));

        int y = ground(0, 0);
        String focus = System.getProperty("terminatorplus.selftest.focus", "");
        if (!focus.isBlank()) {
            run("gamerule doDaylightCycle false"); run("gamerule doMobSpawning false"); cleanup();
            List<String> names = List.of(focus.split(","));
            List<Scenario> selected = skillScenarios(y).stream()
                    .filter(s -> names.stream().anyMatch(s.name()::contains)).toList();
            if (selected.isEmpty()) fail("no scenarios match selftestFocus");
            runScenarios(selected, 0, this::summary);
            return;
        }

        // --- spawning & commands ---------------------------------------------------------------------------------
        at(20, "basic commands", () -> {
            run("gamerule doDaylightCycle false");
            run("gamerule doMobSpawning false");
            run("terminatorplus");
            run("bot");
            run("bot settings");
            run("bot settings setgoal");
            run("bot create Alpha Alpha 0 " + y + " 0");
            run("bot multi 3 Beta% Beta 4 " + y + " 4");
            run("bot create Broken");          // console without location -> 0,0,0 in the overworld (falls to the ground)
            run("bot create");                 // usage message
            run("bot multi abc Name");         // parse error
        });

        at(100, "spawned", () -> {
            run("bot count");
            run("bot info Alpha");
            logBots();
            check("5 bots were created", TerminatorPlus.getManager().fetch().size() == 5);
            check("bots are in the world", TerminatorPlus.getManager().fetch().stream().allMatch(b -> level.getEntity(b.getEntityId()) == b.getEntity()));
            check("bots are not in the player list", server.getPlayerList().getPlayers().isEmpty());
            check("bots stand on the ground", TerminatorPlus.getManager().fetch().stream().allMatch(Terminator::isBotOnGround));
        });

        // --- fighting ------------------------------------------------------------------------------------------------
        at(5, "fight", () -> {
            run("bot armor iron");
            run("bot give minecraft:iron_sword");
            run("bot settings setgoal nearestbot");
        });

        at(20, "armor", () -> check("armor was equipped",
                TerminatorPlus.getManager().fetch().stream().allMatch(b -> b.getEntity().getItemBySlot(EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.IRON_CHESTPLATE))
                        && TerminatorPlus.getManager().fetch().stream().allMatch(b -> b.getEntity().getArmorValue() > 0)));

        at(300, "after fighting", () -> {
            logBots();
            boolean someoneHurt = TerminatorPlus.getManager().fetch().stream().anyMatch(b -> b.getBotHealth() < b.getBotMaxHealth() - 1)
                    || TerminatorPlus.getManager().fetch().size() < 5;
            check("bots hit each other", someoneHurt);
            run("bot settings setgoal none");
            run("bot reset");
        });

        at(5, "reset", () -> {
            check("reset removed every bot", TerminatorPlus.getManager().fetch().isEmpty());
            check("reset removed the entities", level.players().isEmpty());
        });

        // --- mining through a wall: a 1 wide corridor blocked by stone, the bots can't walk around it ---------------------
        int wallX = 40;
        int[] corridorY = new int[1];
        at(5, "mining setup", () -> {
            int gy = ground(wallX, 0);
            corridorY[0] = gy;
            for (int x = wallX - 8; x <= wallX + 8; x++) {
                for (int dy = 0; dy < 4; dy++) {
                    level.setBlockAndUpdate(new BlockPos(x, gy + dy, -1), Blocks.OBSIDIAN.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, gy + dy, 1), Blocks.OBSIDIAN.defaultBlockState());
                }
                level.setBlockAndUpdate(new BlockPos(x, gy + 3, 0), Blocks.OBSIDIAN.defaultBlockState());
            }
            level.setBlockAndUpdate(new BlockPos(wallX, gy, 0), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(wallX, gy + 1, 0), Blocks.DIRT.defaultBlockState());
            run("bot debug offsets(false)");
            run("bot create Miner Miner " + (wallX - 5.5) + " " + gy + " 0.5");
            run("bot create Victim Victim " + (wallX + 6.5) + " " + gy + " 0.5");
            run("bot settings setgoal nearestbot");
        });

        at(400, "mining result", () -> {
            logBots();
            int gy = corridorY[0];
            boolean stone = level.getBlockState(new BlockPos(wallX, gy, 0)).is(Blocks.STONE);
            boolean dirt = level.getBlockState(new BlockPos(wallX, gy + 1, 0)).is(Blocks.DIRT);
            LOGGER.info("[SelfTest]   stone left: {}, dirt left: {}", stone, dirt);
            // With head room above, the bots jump over a 1 high obstacle instead of mining it (same as the plugin),
            // so only the head-high block has to be gone.
            check("bots dug through the blocked corridor", !dirt);
            boolean met = bots("Miner").stream().anyMatch(b -> b.getLocation().x > wallX)
                    || bots("Victim").stream().anyMatch(b -> b.getLocation().x < wallX);
            check("bots got past the wall", met);
            run("bot settings setgoal none");
            run("bot debug offsets(true)");
            run("bot reset");
        });

        // --- falling: water bucket clutch -------------------------------------------------------------------------------
        at(5, "fall setup", () -> run("bot create Faller Faller 80 " + (ground(80, 0) + 200) + " 0"));

        at(200, "fall result", () -> {
            logBots();
            List<Terminator> faller = bots("Faller");
            check("bot survived a 200 block fall (water clutch)", faller.size() == 1 && faller.get(0).isBotAlive() && faller.get(0).isBotOnGround());
            run("bot reset");
        });

        // --- AI bots, debug, environment -----------------------------------------------------------------------------
        at(5, "ai + debug", () -> {
            run("ai random 3 Gamma% Gamma 0 " + y + " 30");
            run("botenvironment addSolid minecraft:glass");
            run("botenvironment listSolids");
            run("botenvironment addCustomMob zombie");
            run("botenvironment listCustomMobs");
            run("botenvironment mobListType hostile");
            run("botenvironment mobListType");
            run("botenvironment clearCustomMobs");
            run("botenvironment clearSolids");
            run("bot settings region 0 -64 0 10 0 10 strict");
            run("bot settings region");
            run("bot settings region clear");
        });

        at(60, "ai bots", () -> {
            logBots();
            List<Terminator> gamma = bots("Gamma");
            check("ai bots were created", gamma.size() == 3);
            check("ai bots have neural networks and shields", gamma.stream().allMatch(b -> b.hasNeuralNetwork()
                    && b.getEntity().getOffhandItem().is(net.minecraft.world.item.Items.SHIELD)));
            run("ai info Gamma1");
            run("bot debug block()");
            run("bot debug offsets(false)");
            run("bot debug toggleAgent()");
            run("bot debug toggleAgent()");
            run("bot debug nope()");
            run("bot reset");
        });

        // --- player list mode, reload, save, kill ------------------------------------------------------------------------
        at(5, "player list", () -> {
            run("bot settings addplayerlist true");
            run("bot create Listed Listed 10 " + y + " 10");
        });

        at(40, "player list result", () -> {
            ServerPlayer listed = server.getPlayerList().getPlayerByName("Listed");
            check("bot is in the player list", listed instanceof Bot);
            run("tp Listed 12 " + y + " 12");
            run("reload");
            run("save-all flush");
            run("bot settings addplayerlist false");
        });

        at(40, "after reload/save", () -> {
            Terminator listed = bots("Listed").stream().findFirst().orElse(null);
            check("teleport moved the bot", listed != null && listed.getEntity().blockPosition().getX() == 12);
            Path playerData = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            boolean saved = listed != null && Files.exists(playerData.resolve(listed.getEntity().getStringUUID() + ".dat"));
            check("bots are not saved to playerdata", !saved);
            check("commands still work after /reload", server.getCommands().getDispatcher().getRoot().getChild("bot") != null);
            run("kill @e[type=minecraft:player]");
        });

        at(40, "after kill", () -> {
            check("killed bots were removed", TerminatorPlus.getManager().fetch().isEmpty() && server.getPlayerList().getPlayers().isEmpty()
                    && level.players().isEmpty());
        });

        // --- tab completion -----------------------------------------------------------------------------------------------
        at(5, "suggestions", () -> {
            check("suggests sub commands", suggestions("bot se").contains("settings"));
            check("suggests settings", suggestions("bot settings setg").contains("setgoal"));
            check("suggests goals", suggestions("bot settings setgoal nearest").contains("nearestbot"));
            check("suggests armor tiers", suggestions("bot armor ").contains("diamond"));
            check("suggests blocks", suggestions("botenv addSolid minecraft:sto").contains("minecraft:stone"));
        });

        // --- the enhanced AI: obstacles, stuck recovery, elytra, mace, pearls, targeting ------------------------------
        at(5, "skill scenarios", () -> runScenarios(skillScenarios(y), 0, () -> startReinforcement(y)));
    }

    // ---- scenario runner: setup, poll until it passes (or times out), clean up, next ---------------------------------

    private record Scenario(String name, Runnable setup, BooleanSupplier passed, int timeout, boolean cleanup) {
    }

    private final List<Entity> spawned = new ArrayList<>();
    private final List<TickTask> trackers = new ArrayList<>();
    private final List<PlayerTeam> testTeams = new ArrayList<>();

    private void runScenarios(List<Scenario> scenarios, int index, Runnable then) {
        if (index >= scenarios.size()) {
            then.run();
            return;
        }

        Scenario scenario = scenarios.get(index);
        LOGGER.info("[SelfTest] ---- scenario: {} ----", scenario.name());

        try {
            scenario.setup().run();
        } catch (Throwable t) {
            fail(scenario.name() + " (setup threw " + t + ")");
            LOGGER.error("[SelfTest] scenario setup failed", t);
            cleanup();
            scheduler.runTaskLater(() -> runScenarios(scenarios, index + 1, then), 10);
            return;
        }

        poll(scenario, 0, () -> runScenarios(scenarios, index + 1, then));
    }

    private void poll(Scenario scenario, int waited, Runnable next) {
        scheduler.runTaskLater(() -> {
            boolean passed;
            try {
                passed = scenario.passed().getAsBoolean();
            } catch (Throwable t) {
                LOGGER.error("[SelfTest] scenario check threw", t);
                passed = false;
            }

            if (passed || waited + 5 >= scenario.timeout()) {
                logBots();
                LOGGER.info("[SelfTest]   ({} after {} ticks)", passed ? "passed" : "timed out", waited + 5);
                check(scenario.name(), passed);
                if (scenario.cleanup()) {
                    cleanup();
                }
                scheduler.runTaskLater(next, 10);
            } else {
                poll(scenario, waited + 5, next);
            }
        }, 5);
    }

    private void cleanup() {
        legacy().getSkillSettings().defaultEquipment = false;
        scenarioHooks.forEach(Runnable::run); scenarioHooks.clear();
        trackers.forEach(TickTask::cancel);
        trackers.clear();
        spawned.forEach(Entity::discard);
        server.getPlayerList().players.removeIf(p -> spawned.contains(p) && !(p instanceof Bot));
        spawned.clear();
        humanPackets.clear();
        run("bot settings setgoal none");
        run("bot settings buildblock minecraft:cobblestone");
        run("bot settings hardness 7");
        run("bot preset use none");
        run("bot reset");
        testTeams.forEach(server.getScoreboard()::removePlayerTeam);
        testTeams.clear();
    }

    private void track(Runnable tracker) {
        trackers.add(scheduler.runTaskTimer(tracker, 0, 1));
    }

    private void forceChunk(double x, double z) {
        int cx = SectionPos.blockToSectionCoord(x);
        int cz = SectionPos.blockToSectionCoord(z);
        level.setChunkForced(cx, cz, true);
        level.getChunk(cx, cz);
    }

    private void forceArea(int x1, int z1, int x2, int z2) {
        for (int x = x1; x <= x2; x += 16) {
            for (int z = z1; z <= z2; z += 16) {
                forceChunk(x, z);
            }
        }
        forceChunk(x2, z2);
    }

    /** Native explosions persist between runs; restore only this isolated flat test fixture. */
    private void warfareFlatArena(int x1, int z1, int x2, int z2, int y) {
        cooperationArena(x1, z1, x2, z2, y);
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) for (int depth = 1; depth <= 3; depth++) {
            BlockPos floor = new BlockPos(x, y - depth, z);
            BlockState expected = (depth == 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState();
            if (level.getBlockState(floor) != expected) level.setBlock(floor, expected, 3);
        }
    }

    private Bot spawnBot(String name, double x, double y, double z) {
        forceChunk(x, z);
        return Bot.createBot(new Location(level, x, y, z), name, null);
    }

    private Husk spawnHusk(double x, double y, double z, float health, boolean invulnerable) {
        forceChunk(x, z);
        Husk husk = Objects.requireNonNull(EntityType.HUSK.create(level));
        husk.moveTo(x, y, z, 0, 0);
        husk.setNoAi(true);
        husk.setPersistenceRequired();
        husk.setSilent(true);
        husk.setInvulnerable(invulnerable);
        AttributeInstance maxHealth = husk.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null && health > maxHealth.getBaseValue()) {
            maxHealth.setBaseValue(health);
        }
        husk.setHealth(health);
        level.addFreshEntity(husk);
        spawned.add(husk);
        return husk;
    }

    private void setBlock(int x, int y, int z, BlockState state) {
        level.setBlockAndUpdate(new BlockPos(x, y, z), state);
    }

    private static double horizontal(Entity a, Entity b) {
        return Math.sqrt(Math.pow(a.getX() - b.getX(), 2) + Math.pow(a.getZ() - b.getZ(), 2));
    }

    /**
     * Largest health loss of the entity within a single tick, from now on.
     */
    private float[] trackBiggestHit(LivingEntity entity) {
        float[] state = {entity.getHealth(), 0};
        track(() -> {
            float health = entity.isAlive() ? entity.getHealth() : 0;
            state[1] = Math.max(state[1], state[0] - health);
            state[0] = health;
        });
        return state;
    }

    private List<Scenario> skillScenarios(int y) {
        List<Scenario> list = new ArrayList<>();

        // walled in by bedrock (can't be mined): pillar up and over
        Bot[] boxed = new Bot[1];
        list.add(new Scenario("climbs out of a bedrock enclosure", () -> {
            forceArea(110, -10, 135, 10);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        for (int dy = 0; dy < 3; dy++) {
                            setBlock(120 + dx, y + dy, dz, Blocks.BEDROCK.defaultBlockState());
                        }
                    }
                }
            }
            run("bot settings setgoal nearesthostile");
            boxed[0] = spawnBot("Boxed", 120.5, y, 0.5);
            spawnHusk(129.5, y, 0.5, 20, true);
        }, () -> Math.abs(boxed[0].getX() - 120.5) > 2.6 || Math.abs(boxed[0].getZ() - 0.5) > 2.6, 600, true));

        // an unbreakable wall in the way: around it (or over it)
        Bot[] walker = new Bot[1];
        list.add(new Scenario("gets past an unbreakable wall", () -> {
            forceArea(150, -15, 175, 15);
            for (int dz = -4; dz <= 4; dz++) {
                for (int dy = 0; dy < 4; dy++) {
                    setBlock(160, y + dy, dz, Blocks.BEDROCK.defaultBlockState());
                }
            }
            run("bot settings setgoal nearesthostile");
            walker[0] = spawnBot("Walker", 155.5, y, 0.5);
            spawnHusk(166.5, y, 0.5, 20, true);
        }, () -> walker[0].getX() > 160.6, 600, true));

        // a pool whose water surface is a block below the bank
        Bot[] swimmer = new Bot[1];
        list.add(new Scenario("climbs out of a pool with a high bank", () -> {
            forceArea(190, -10, 215, 10);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    setBlock(200 + dx, y - 1, dz, Blocks.AIR.defaultBlockState());
                    setBlock(200 + dx, y - 2, dz, Blocks.WATER.defaultBlockState());
                    setBlock(200 + dx, y - 3, dz, Blocks.WATER.defaultBlockState());
                }
            }
            run("bot settings setgoal nearesthostile");
            swimmer[0] = spawnBot("Swimmer", 200.5, y - 2, 0.5);
            spawnHusk(208.5, y, 0.5, 20, true);
        }, () -> swimmer[0].getY() >= y - 0.01 && swimmer[0].getX() > 202.6, 400, true));

        // a ladder up a bedrock tower, the target on top
        Bot[] climber = new Bot[1];
        list.add(new Scenario("climbs a ladder to a target above", () -> {
            forceArea(230, -10, 250, 10);
            for (int x = 241; x <= 243; x++) {
                for (int z = -1; z <= 1; z++) {
                    for (int dy = 0; dy < 6; dy++) {
                        setBlock(x, y + dy, z, Blocks.BEDROCK.defaultBlockState());
                    }
                }
            }
            for (int dy = 0; dy < 6; dy++) {
                setBlock(240, y + dy, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
            }
            run("bot settings setgoal nearesthostile");
            run("bot debug offsets(false)");
            climber[0] = spawnBot("Climber", 236.5, y, 0.5);
            spawnHusk(242.5, y + 6, 0.5, 20, true);
        }, () -> climber[0].getY() >= y + 5.9, 500, true));

        // elytra + rockets towards a target 110 blocks away
        Bot[] flyer = new Bot[1];
        Husk[] flyTarget = new Husk[1];
        boolean[] glided = new boolean[1];
        list.add(new Scenario("flies to a far target with an elytra and rockets", () -> {
            run("bot debug offsets(true)");
            forceArea(290, -10, 310, 120);
            run("bot settings setgoal nearesthostile");
            flyer[0] = spawnBot("Flyer", 300.5, y, 0.5);
            flyer[0].giveItem(new ItemStack(Items.ELYTRA));
            flyer[0].giveItem(new ItemStack(Items.FIREWORK_ROCKET, 64));
            flyTarget[0] = spawnHusk(300.5, y, 110.5, 20, true);
            track(() -> glided[0] |= flyer[0].isGliding());
        }, () -> glided[0] && flyer[0].isBotOnGround() && horizontal(flyer[0], flyTarget[0]) < 12, 600, true));

        // mace + elytra: climb above the target and smash down
        Bot[] diver = new Bot[1];
        float[][] diveHits = new float[1][];
        list.add(new Scenario("elytra dive mace smash", () -> {
            forceArea(390, -10, 410, 50);
            run("bot settings setgoal nearesthostile");
            diver[0] = spawnBot("Diver", 400.5, y, 0.5);
            diver[0].giveItem(new ItemStack(Items.MACE));
            diver[0].giveItem(new ItemStack(Items.ELYTRA));
            diver[0].giveItem(new ItemStack(Items.FIREWORK_ROCKET, 64));
            diveHits[0] = trackBiggestHit(spawnHusk(400.5, y, 30.5, 400, false));
        }, () -> {
            if (diveHits[0][1] > 0) LOGGER.info("[SelfTest]   biggest hit so far: {}", diveHits[0][1]);
            return diveHits[0][1] >= 15;
        }, 900, true));

        // mace + wind charges: launch up next to the target and smash down
        Bot[] jumper = new Bot[1];
        float[][] jumpHits = new float[1][];
        list.add(new Scenario("wind charge mace smash", () -> {
            forceArea(450, -10, 470, 10);
            run("bot settings setgoal nearesthostile");
            jumper[0] = spawnBot("Jumper", 460.5, y, 0.5);
            jumper[0].giveItem(new ItemStack(Items.MACE));
            jumper[0].giveItem(new ItemStack(Items.WIND_CHARGE, 16));
            jumpHits[0] = trackBiggestHit(spawnHusk(460.5, y, 4.5, 400, false));
        }, () -> jumpHits[0][1] >= 12, 400, true));

        // ender pearls to catch up with a target 35 blocks away
        Bot[] pearler = new Bot[1];
        boolean[] teleported = new boolean[1];
        list.add(new Scenario("closes the gap with an ender pearl", () -> {
            forceArea(510, -10, 530, 50);
            run("bot settings setgoal nearesthostile");
            pearler[0] = spawnBot("Pearler", 520.5, y, 0.5);
            pearler[0].giveItem(new ItemStack(Items.ENDER_PEARL, 8));
            spawnHusk(520.5, y, 35.5, 20, true);
            Vec3[] last = {pearler[0].position()};
            track(() -> {
                if (pearler[0].position().distanceTo(last[0]) > 8) teleported[0] = true;
                last[0] = pearler[0].position();
            });
        }, () -> teleported[0] && pearler[0].countItem(Items.ENDER_PEARL) < 8, 300, true));

        // falling into the void: pearl back to solid ground
        Bot[] voidFaller = new Bot[1];
        list.add(new Scenario("pearls out of the void", () -> {
            forceArea(580, -20, 625, 20);
            for (int x = 590; x <= 609; x++) {
                for (int z = -10; z <= 9; z++) {
                    for (int dy = 1; dy <= 4; dy++) {
                        setBlock(x, y - dy, z, Blocks.AIR.defaultBlockState());
                    }
                }
            }
            run("bot settings setgoal nearesthostile");
            voidFaller[0] = spawnBot("VoidFaller", 600.5, y + 3, 0.5);
            voidFaller[0].giveItem(new ItemStack(Items.ENDER_PEARL, 4));
            spawnHusk(614.5, y, 0.5, 20, true);
        }, () -> voidFaller[0].isAlive() && voidFaller[0].isBotOnGround() && voidFaller[0].getY() >= y - 0.5
                && voidFaller[0].countItem(Items.ENDER_PEARL) < 4, 300, true));

        // retaliation: no goal target, but something hits it
        Bot[] avenger = new Bot[1];
        list.add(new Scenario("fights back against an attacker", () -> {
            forceArea(690, -10, 720, 10);
            run("bot settings setgoal nearestplayer");
            avenger[0] = spawnBot("Avenger", 700.5, y, 0.5);
            Husk attacker = spawnHusk(712.5, y, 0.5, 20, true);
            // after the spawn protection
            scheduler.runTaskLater(() -> avenger[0].hurt(level.damageSources().mobAttack(attacker), 1.0F), 80);
        }, () -> avenger[0].getX() > 704.5, 300, true));

        // target range: ignored beyond it, chased once unlimited
        Bot[] ranger = new Bot[1];
        int[] rangerTicks = new int[1];
        list.add(new Scenario("ignores targets beyond the target range", () -> {
            forceArea(750, -10, 780, 10);
            run("bot settings setgoal nearesthostile");
            run("bot settings range 10");
            ranger[0] = spawnBot("Ranger", 760.5, y, 0.5);
            spawnHusk(775.5, y, 0.5, 20, true);
            track(() -> rangerTicks[0]++);
        }, () -> rangerTicks[0] >= 60 && Math.abs(ranger[0].getX() - 760.5) < 1.5, 100, false));

        list.add(new Scenario("chases the target once the range is unlimited", () -> run("bot settings range unlimited"),
                () -> ranger[0].getX() > 764, 200, true));

        // a block placed where the bot stands
        Bot[] embedded = new Bot[1];
        boolean[] placed = new boolean[1], reallyEmbedded = new boolean[1];
        list.add(new Scenario("escapes a block placed inside it", () -> {
            forceArea(795, -5, 805, 5);
            // This world is reused across runs; old embedding fixtures must not become a cage.
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = 0; dy <= 4; dy++)
                setBlock(800 + dx, y + dy, dz, Blocks.AIR.defaultBlockState());
            embedded[0] = spawnBot("Embedded", 800.5, y, 0.5);
            scheduler.runTaskLater(() -> {
                level.setBlockAndUpdate(embedded[0].blockPosition(), Blocks.STONE.defaultBlockState());
                placed[0] = true;
                reallyEmbedded[0] = !level.noCollision(embedded[0], embedded[0].getBoundingBox().deflate(1.0E-3));
            }, 30);
        }, () -> placed[0] && reallyEmbedded[0] && level.noCollision(embedded[0], embedded[0].getBoundingBox().deflate(1.0E-3)), 100, true));

        // hostiles that aren't Monster subclasses
        Slime[] slime = new Slime[1];
        list.add(new Scenario("attacks slimes with the nearesthostile goal", () -> {
            forceArea(835, -5, 850, 5);
            run("bot settings setgoal nearesthostile");
            spawnBot("Hunter", 840.5, y, 0.5);
            slime[0] = Objects.requireNonNull(EntityType.SLIME.create(level));
            slime[0].setSize(3, true);
            slime[0].moveTo(846.5, y, 0.5, 0, 0);
            slime[0].setNoAi(true);
            slime[0].setPersistenceRequired();
            level.addFreshEntity(slime[0]);
            spawned.add(slime[0]);
        }, () -> !slime[0].isAlive() || slime[0].getHealth() < slime[0].getMaxHealth(), 300, true));

        // inventory & ability commands
        Bot[] kitBot = new Bot[1];
        list.add(new Scenario("inventory and ability commands", () -> {
            kitBot[0] = spawnBot("Kit", 870.5, y, 0.5);
            run("bot inventory");
            run("bot inventory kit full");
            run("bot inventory give minecraft:diamond_sword");
            run("bot inventory show Kit");
            run("bot settings ability");
            run("bot settings ability elytra false");
        }, () -> {
            boolean kit = kitBot[0].countItem(Items.FIREWORK_ROCKET) == 64 && kitBot[0].countItem(Items.ENDER_PEARL) == 16
                    && kitBot[0].hasMace() && kitBot[0].hasUsableElytra()
                    && kitBot[0].getWeapon().is(Items.DIAMOND_SWORD);
            boolean toggled = !((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) TerminatorPlus.getManager().getAgent()).getSkillSettings().elytra();
            run("bot settings ability elytra true");
            run("bot inventory clear");
            return kit && toggled && kitBot[0].countItem(Items.FIREWORK_ROCKET) == 0;
        }, 20, true));

        // bow and arrows: hits the target from range
        Bot[] archer = new Bot[1];
        Husk[] archerTarget = new Husk[1];
        double[] hitFrom = new double[1];
        list.add(new Scenario("shoots a distant target with a bow", () -> {
            forceArea(890, -10, 925, 10);
            run("bot settings setgoal nearesthostile");
            archer[0] = spawnBot("Archer", 895.5, y, 0.5);
            archer[0].giveItem(new ItemStack(Items.BOW));
            archer[0].giveItem(new ItemStack(Items.ARROW, 16));
            archerTarget[0] = spawnHusk(920.5, y, 0.5, 400, false);
            float[] last = {archerTarget[0].getHealth()};
            track(() -> {
                float health = archerTarget[0].getHealth();
                if (health < last[0]) {
                    hitFrom[0] = Math.max(hitFrom[0], horizontal(archer[0], archerTarget[0]));
                }
                last[0] = health;
            });
        }, () -> hitFrom[0] > 8 && archer[0].countItem(Items.ARROW) < 16, 300, true));

        // the configured build block: pillars out of a bedrock box with obsidian
        Bot[] builder = new Bot[1];
        list.add(new Scenario("builds with the configured block", () -> {
            forceArea(940, -10, 965, 10);
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        for (int dy = 0; dy < 3; dy++) {
                            setBlock(950 + dx, y + dy, dz, Blocks.BEDROCK.defaultBlockState());
                        }
                    }
                }
            }
            run("bot settings buildblock minecraft:obsidian");
            run("bot settings setgoal nearesthostile");
            builder[0] = spawnBot("Builder", 950.5, y, 0.5);
            spawnHusk(959.5, y, 0.5, 20, true);
        }, () -> {
            boolean out = Math.abs(builder[0].getX() - 950.5) > 2.6 || Math.abs(builder[0].getZ() - 0.5) > 2.6;
            boolean obsidian = false;
            boolean cobblestone = false;
            for (BlockPos pos : BlockPos.betweenClosed(948, y, -2, 952, y + 6, 2)) {
                obsidian |= level.getBlockState(pos).is(Blocks.OBSIDIAN);
                cobblestone |= level.getBlockState(pos).is(Blocks.COBBLESTONE);
            }
            return out && obsidian && !cobblestone;
        }, 600, true));

        // totems: a fresh one goes into the off hand right after one pops
        Bot[] immortal = new Bot[1];
        list.add(new Scenario("puts a new totem in the off hand after one pops", () -> {
            forceArea(975, -5, 985, 5);
            run("bot settings setgoal none");
            immortal[0] = spawnBot("Immortal", 980.5, y, 0.5);
            immortal[0].giveItem(new ItemStack(Items.TOTEM_OF_UNDYING));
            immortal[0].giveItem(new ItemStack(Items.TOTEM_OF_UNDYING));
            // two deadly hits once the spawn protection is over
            scheduler.runTaskLater(() -> immortal[0].hurt(level.damageSources().generic(), 100), 80);
            scheduler.runTaskLater(() -> immortal[0].hurt(level.damageSources().generic(), 100), 110);
        }, () -> immortal[0].getAliveTicks() > 115 && immortal[0].isAlive()
                && immortal[0].countItem(Items.TOTEM_OF_UNDYING) == 0 && immortal[0].getOffhandItem().isEmpty(), 200, true));

        list.addAll(hardnessScenarios(y));
        list.addAll(warfareScenarios(y));
        return list;
    }

    private LegacyAgent legacy() { return (LegacyAgent) TerminatorPlus.getManager().getAgent(); }

    private ItemStack warfareItem(String id, int count) {
        var key = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("superbwarfare", id);
        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalStateException("Missing SBW item " + id);
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key), count);
    }
    private ItemStack loadedGun(String id, int rounds) {
        ItemStack stack = warfareItem(id, 1);
        try {
            Class<?> data = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
            Object gun = data.getMethod("from", ItemStack.class).invoke(null, stack);
            data.getMethod("initialize").invoke(gun);
            Object ammo = data.getField("ammo").get(gun);
            ammo.getClass().getMethod("set", int.class).invoke(ammo, rounds);
            data.getMethod("save").invoke(gun);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        return stack;
    }
    private ItemStack equipGun(Bot bot, ItemStack gun) {
        int slot = 1;
        while (!bot.getInventory().items.get(slot).isEmpty()) slot++;
        bot.giveItem(gun);
        return bot.getInventory().items.get(slot);
    }
    private void overrideGun(ItemStack stack, String json) {
        try {
            Class<?> data = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData");
            Object gun = data.getMethod("from", ItemStack.class).invoke(null, stack);
            Object property = data.getField("propertyOverrideString").get(gun);
            property.getClass().getMethod("set", String.class).invoke(property, json);
            data.getMethod("save").invoke(gun);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private void overrideVehicleGun(Entity vehicle, Bot passenger, int index, String json) {
        try {
            // Vehicle GunData has its own default-data supplier and cache. Mutating a
            // second GunData.from(stack) instance does not invalidate the native one.
            Object gun = vehicle.getClass().getMethod("getGunData", Entity.class, int.class).invoke(vehicle, passenger, index);
            Object property = gun.getClass().getField("propertyOverrideString").get(gun);
            property.getClass().getMethod("set", String.class).invoke(property, json);
            gun.getClass().getMethod("save").invoke(gun);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private Bot infantry(String name, int difficulty, double x, int y, double z) {
        Bot bot = spawnBot(name, x, y, z);
        bot.setHardnessOverride(difficulty);
        for (String ability : legacy().getSkillSettings().all().keySet()) bot.profileAbilities().put(ability, false);
        bot.profileAbilities().put("guns", true);
        return bot;
    }
    private List<Scenario> warfareScenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        var warfare = legacy().getSkills().warfare();
        if (!net.neoforged.fml.ModList.get().isLoaded("superbwarfare")) {
            Bot[] vanilla = {null}; Husk[] target = {null}; boolean[] valid = {false};
            list.add(new Scenario("warfare optional absence preserves native melee with guns enabled", () -> {
                forceArea(6300, -5, 6320, 5); run("bot settings setgoal nearesthostile");
                vanilla[0] = infantry("NoWarfare", 8, 6304.5, y, 0.5);
                vanilla[0].giveItem(new ItemStack(Items.NETHERITE_SWORD));
                target[0] = spawnHusk(6310.5, y, 0.5, 80, false);
                valid[0] = !warfare.available();
            }, () -> valid[0] && target[0].getHealth() < 80 && !warfare.holding(vanilla[0]), 220, true));
            return list;
        }
        boolean[] ready = {false};
        list.add(new Scenario("warfare exact installed version binds all native gun APIs", () -> {
            ready[0] = warfare.available(); LOGGER.info("[SelfTest] Warfare: {}", warfare.status());
        }, () -> ready[0], 10, true));
        Bot[] rifleman = {null}; ItemStack[] rifle = {null}; Husk[] dummy = {null};
        java.util.Set<java.util.UUID>[] hits = new java.util.Set[]{null}; boolean[] realHand = {true};
        list.add(new Scenario("warfare native rifle loads inventory ammo fires real owned bullets and preserves components", () -> {
            forceArea(6330, -6, 6370, 6); run("bot settings setgoal nearesthostile");
            rifleman[0] = infantry("WarfareRifle", 7, 6335.5, y, 0.5);
            rifle[0] = loadedGun("ak_47", 0);
            rifle[0].set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Real AK"));
            rifle[0] = equipGun(rifleman[0], rifle[0]); rifleman[0].giveItem(warfareItem("rifle_ammo", 64));
            dummy[0] = spawnHusk(6359.5, y, 0.5, 1000, false); hits[0] = capturePositiveHits(dummy[0]);
            track(() -> { if (warfare.holding(rifleman[0])) realHand[0] &= rifleman[0].getMainHandItem() == rifle[0]; });
        }, () -> hits[0].contains(rifleman[0].getUUID()) && warfare.shots(rifleman[0]) >= 5
                && warfare.ammo(rifle[0]) > 0 && rifleman[0].countItem(warfareItem("rifle_ammo", 1).getItem()) < 64
                && realHand[0] && "Real AK".equals(rifle[0].getHoverName().getString()), 320, true));
        Bot[] finite = {null}; ItemStack[] finiteGun = {null}; long[] exhausted = {-1};
        list.add(new Scenario("warfare finite rounds run out without duplicated display ammo", () -> {
            forceArea(6390, -5, 6430, 5); run("bot settings setgoal nearesthostile");
            finite[0] = infantry("FiniteRifle", 7, 6395.5, y, 0.5); finiteGun[0] = loadedGun("ak_47", 6);
            finiteGun[0] = equipGun(finite[0], finiteGun[0]); spawnHusk(6419.5, y, 0.5, 1000, true);
            track(() -> { if (warfare.shots(finite[0]) == 6 && exhausted[0] < 0) exhausted[0] = server.getTickCount(); });
        }, () -> exhausted[0] > 0 && server.getTickCount() - exhausted[0] > 50 && warfare.shots(finite[0]) == 6
                && warfare.ammo(finiteGun[0]) == 0 && !warfare.holding(finite[0]), 300, true));
        Bot[] infinite = {null}; ItemStack[] infiniteGun = {null}; boolean[] window = {false}; long[] beforeReload = {-1}; long[] reloadAt = {-1};
        list.add(new Scenario("warfare ten infinite reserve still exposes a full native reload window", () -> {
            forceArea(6450, -5, 6490, 5); run("bot settings setgoal nearesthostile");
            infinite[0] = infantry("InfiniteRifle", 10, 6455.5, y, 0.5); infiniteGun[0] = loadedGun("ak_47", 2);
            infiniteGun[0] = equipGun(infinite[0], infiniteGun[0]); spawnHusk(6479.5, y, 0.5, 1000, true);
            track(() -> {
                if (warfare.reloading(infiniteGun[0])) {
                    if (reloadAt[0] < 0) { reloadAt[0] = server.getTickCount(); beforeReload[0] = warfare.shots(infinite[0]); }
                    if (server.getTickCount() - reloadAt[0] >= 40 && warfare.shots(infinite[0]) == beforeReload[0]) window[0] = true;
                }
            });
        }, () -> window[0] && warfare.shots(infinite[0]) >= 8 && !infinite[0].isCreative() && !infinite[0].getAbilities().invulnerable, 340, true));
        Bot[] canceled = {null}; ItemStack[] canceledGun = {null}; boolean[] switched = {false};
        list.add(new Scenario("warfare disabling ability restores exact owned gun and resumes native melee", () -> {
            forceArea(6510, -5, 6550, 5); run("bot settings setgoal nearesthostile");
            canceled[0] = infantry("SwitchRifle", 8, 6515.5, y, 0.5); canceledGun[0] = loadedGun("ak_47", 30);
            canceledGun[0] = equipGun(canceled[0], canceledGun[0]); canceled[0].giveItem(new ItemStack(Items.NETHERITE_SWORD));
            dummy[0] = spawnHusk(6539.5, y, 0.5, 1000, false); hits[0] = capturePositiveHits(dummy[0]);
            track(() -> { if (!switched[0] && warfare.shots(canceled[0]) > 0) {
                switched[0] = true; canceled[0].profileAbilities().put("guns", false); dummy[0].setPos(canceled[0].getX() + 2, y, 0.5);
            } });
        }, () -> switched[0] && !warfare.holding(canceled[0]) && canceled[0].getInventory().items.stream().anyMatch(s -> s == canceledGun[0])
                && canceled[0].getMainHandItem().is(Items.NETHERITE_SWORD) && hits[0].contains(canceled[0].getUUID()), 300, true));
        Bot[] sniper = {null}; ItemStack[] sniperGun = {null}; java.util.Set<java.util.UUID>[] sniperHits = new java.util.Set[]{null};
        List<Long> firedAt = new ArrayList<>(); long[] last = {-1};
        list.add(new Scenario("warfare bolt sniper respects native bolt duration and lands actual damage", () -> {
            forceArea(6570, -5, 6650, 5); run("bot settings setgoal nearesthostile");
            sniper[0] = infantry("NativeSniper", 10, 6575.5, y, 0.5); sniperGun[0] = loadedGun("awm", 5); sniperGun[0] = equipGun(sniper[0], sniperGun[0]);
            Husk victim = spawnHusk(6631.5, y, 0.5, 1000, false); sniperHits[0] = capturePositiveHits(victim);
            track(() -> { long t = warfare.lastShot(sniper[0]); if (t >= 0 && t != last[0]) { firedAt.add(t); last[0] = t; } });
        }, () -> firedAt.size() >= 3 && sniperHits[0].contains(sniper[0].getUUID())
                && firedAt.get(1) - firedAt.get(0) >= 24 && firedAt.get(2) - firedAt.get(1) >= 24, 320, true));
        Bot[] selector = {null}; ItemStack[] shotgun = {null}, longGun = {null}; boolean[] near = {false}, far = {false};
        list.add(new Scenario("warfare weapon choice switches shotgun to sniper by actual distance", () -> {
            forceArea(6670, -5, 6760, 5); run("bot settings setgoal nearesthostile");
            selector[0] = infantry("SelectGuns", 8, 6675.5, y, 0.5); shotgun[0] = loadedGun("aa_12", 25); longGun[0] = loadedGun("awm", 5);
            shotgun[0] = equipGun(selector[0], shotgun[0]); longGun[0] = equipGun(selector[0], longGun[0]); dummy[0] = spawnHusk(6682.5, y, 0.5, 1000, true);
            track(() -> {
                if (!near[0] && selector[0].getMainHandItem() == shotgun[0]) { near[0] = true; dummy[0].setPos(6731.5, y, 0.5); }
                if (near[0] && selector[0].getMainHandItem() == longGun[0] && warfare.shots(selector[0]) > 0) far[0] = true;
            });
        }, () -> near[0] && far[0], 300, true));
        Bot[] wallBot = {null}; long[] wallShots = {-1}; boolean[] blocked = {true};
        list.add(new Scenario("warfare opaque wall stops gunfire without freezing normal navigation", () -> {
            forceArea(6780, -5, 6830, 5); run("bot settings setgoal nearesthostile");
            wallBot[0] = infantry("WarfareWall", 7, 6785.5, y, 0.5); wallBot[0].giveItem(loadedGun("ak_47", 30));
            spawnHusk(6811.5, y, 0.5, 1000, true);
            for (int z = -4; z <= 4; z++) for (int h = 0; h < 5; h++) setBlock(6791, y + h, z, Blocks.AIR.defaultBlockState());
            track(() -> {
                if (wallShots[0] < 0 && warfare.shots(wallBot[0]) > 0) {
                    wallShots[0] = warfare.shots(wallBot[0]);
                    for (int z = -4; z <= 4; z++) for (int h = 0; h < 5; h++) setBlock(6791, y + h, z, Blocks.OBSIDIAN.defaultBlockState());
                }
                if (wallShots[0] >= 0 && wallBot[0].getX() < 6790) blocked[0] &= warfare.shots(wallBot[0]) == wallShots[0];
            });
        }, () -> wallShots[0] > 0 && blocked[0] && wallBot[0].position().distanceToSqr(new Vec3(6785.5, y, 0.5)) > 4 && !warfare.holding(wallBot[0]), 260, true));
        Bot[] friendGun = {null}; Husk[] friend = {null}; boolean[] protectedFriend = {true};
        list.add(new Scenario("warfare teammate in rifle line is protected", () -> {
            forceArea(6850, -10, 6890, 10); run("bot settings setgoal nearesthostile");
            friendGun[0] = infantry("SafeRifle", 9, 6855.5, y, 0.5); friendGun[0].giveItem(loadedGun("ak_47", 30));
            spawnHusk(6879.5, y, 0.5, 1000, true); friend[0] = spawnHusk(6861.5, y, 0.5, 100, false);
            PlayerTeam team = testTeam("warfare_safe", friendGun[0]); server.getScoreboard().addPlayerToTeam(friend[0].getScoreboardName(), team);
            track(() -> protectedFriend[0] &= friend[0].getHealth() == 100);
        }, () -> friendGun[0].getAliveTicks() > 100 && protectedFriend[0], 150, true));
        Bot[] recoveryGun = {null}; ItemStack[] recoveryStack = {null}; boolean[] ate = {false};
        list.add(new Scenario("warfare recovery interrupts real gun hand without losing food or weapon", () -> {
            forceArea(6910, -5, 6950, 5); run("bot settings setgoal nearesthostile");
            recoveryGun[0] = infantry("GunMedic", 8, 6915.5, y, 0.5); recoveryStack[0] = loadedGun("ak_47", 30);
            recoveryGun[0].profileAbilities().put("recovery", true);
            recoveryStack[0] = equipGun(recoveryGun[0], recoveryStack[0]); recoveryGun[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2));
            spawnHusk(6939.5, y, 0.5, 1000, true);
            track(() -> { if (!ate[0] && warfare.holding(recoveryGun[0])) { recoveryGun[0].setHealth(8); ate[0] = recoveryGun[0].beginRecoveryItem(false); } });
        }, () -> ate[0] && recoveryGun[0].hasEffect(MobEffects.REGENERATION) && recoveryGun[0].countItem(Items.GOLDEN_APPLE) == 1
                && (recoveryGun[0].getMainHandItem() == recoveryStack[0] || recoveryGun[0].getInventory().items.stream().anyMatch(s -> s == recoveryStack[0])), 200, true));
        Bot[] antitank = {null}; Entity[] tank = {null}; ItemStack[] rpg = {null}; boolean[] rocket = {false}; float[] initialHP = {0};
        list.add(new Scenario("warfare native tank immunity rejects rifle and RPG produces actual vehicle damage", () -> {
            forceArea(6970, -12, 7040, 12); run("bot settings setgoal nearesthostile");
            antitank[0] = infantry("AntiTank", 10, 6975.5, y, 0.5); antitank[0].giveItem(loadedGun("ak_47", 30));
            rpg[0] = loadedGun("rpg", 1); rpg[0] = equipGun(antitank[0], rpg[0]);
            var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("superbwarfare", "m_1a_2"));
            tank[0] = Objects.requireNonNull(type.create(level)); tank[0].moveTo(7011.5, y, 0.5, 90, 0); level.addFreshEntity(tank[0]); spawned.add(tank[0]);
            Husk occupant = spawnHusk(7011.5, y, 0.5, 1000, true); occupant.startRiding(tank[0], true);
            initialHP[0] = vehicleHealth(tank[0]);
            track(() -> rocket[0] |= antitank[0].getMainHandItem() == rpg[0]);
        }, () -> rocket[0] && vehicleHealth(tank[0]) < initialHP[0] && warfare.shots(antitank[0]) > 0, 400, true));
        Bot[] custom = {null}; ItemStack[] customGun = {null}; List<Long> customShots = new ArrayList<>(); long[] customLast = {-1};
        list.add(new Scenario("warfare runtime gun overrides govern cadence and native magazine reload", () -> {
            forceArea(7070, -5, 7110, 5); run("bot settings setgoal nearesthostile");
            custom[0] = infantry("CustomRifle", 10, 7075.5, y, 0.5); customGun[0] = loadedGun("ak_47", 2);
            overrideGun(customGun[0], "{\"RPM\":120,\"Magazine\":2,\"EmptyReloadTime\":45}");
            customGun[0] = equipGun(custom[0], customGun[0]); spawnHusk(7099.5, y, 0.5, 1000, true);
            track(() -> { long t = warfare.lastShot(custom[0]); if (t >= 0 && t != customLast[0]) { customShots.add(t); customLast[0] = t; } });
        }, () -> customShots.size() >= 4 && customShots.get(1) - customShots.get(0) >= 10
                && customShots.get(2) - customShots.get(1) >= 40 && customShots.get(3) - customShots.get(2) >= 10, 280, true));
        List<Bot> swarm = new ArrayList<>(); java.util.Map<Long, Integer> nativeCounts = new java.util.HashMap<>();
        boolean[] budgetSafe = {true}; java.util.Set<java.util.UUID> nativeOwners = new java.util.HashSet<>();
        list.add(new Scenario("warfare native shotgun projectile budget bounds crowds without starving shooters", () -> {
            forceArea(7140, -32, 7190, 32); run("bot settings setgoal nearesthostile");
            for (int i = 0; i < 12; i++) {
                Bot bot = infantry("CrowdGun" + i, 10, 7145.5, y, -24.5 + i * 4);
                bot.giveItem(loadedGun("aa_12", 25)); swarm.add(bot);
                spawnHusk(7169.5, y, -24.5 + i * 4, 1000, true);
            }
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile
                        && projectile.getOwner() instanceof Bot bot && swarm.contains(bot)) {
                    nativeOwners.add(bot.getUUID());
                    int count = nativeCounts.merge((long) server.getTickCount(), 1, Integer::sum);
                    budgetSafe[0] &= count <= 64;
                }
            };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
        }, () -> nativeCounts.values().stream().mapToInt(Integer::intValue).sum() > 240 && budgetSafe[0]
                && swarm.stream().allMatch(b -> nativeOwners.contains(b.getUUID()) && warfare.shots(b) >= 2), 200, true));
        Bot[] turning = {null}; float[] rotation = {0}; boolean[] bounded = {true};
        list.add(new Scenario("warfare low difficulty uses bounded turning and body aim instead of instant snap", () -> {
            forceArea(7220, -5, 7260, 5); run("bot settings setgoal nearesthostile");
            turning[0] = infantry("EasyGun", 1, 7225.5, y, 0.5); turning[0].giveItem(loadedGun("ak_47", 30));
            spawnHusk(7249.5, y, 0.5, 1000, true); rotation[0] = turning[0].getYRot();
            track(() -> {
                if (warfare.holding(turning[0])) {
                    double delta = Math.abs(net.minecraft.util.Mth.wrapDegrees(turning[0].getYRot() - rotation[0]));
                    if (!(delta <= 4.31)) LOGGER.warn("[SelfTest] Gun turn age={} before={} after={} delta={} shots={} pos={}",
                            turning[0].getAliveTicks(), rotation[0], turning[0].getYRot(), delta, warfare.shots(turning[0]), turning[0].position());
                    bounded[0] &= delta <= 4.31;
                }
                rotation[0] = turning[0].getYRot();
                if (turning[0].getAliveTicks() % 20 == 0) LOGGER.info("[SelfTest] Easy gun age={} state={} bounded={} yaw={} target={}",
                        turning[0].getAliveTicks(), warfare.describe(turning[0]), bounded[0], rotation[0], legacy().getSkills().currentTarget(turning[0]));
            });
        }, () -> turning[0].getAliveTicks() > 100 && warfare.shots(turning[0]) >= 2 && bounded[0], 260, true));
        list.addAll(warfareMovementScenarios(y));
        list.addAll(warfareVehicleScenarios(y));
        list.addAll(warfareFeedbackScenarios(y));
        list.addAll(warfareCooldownScenarios(y));
        list.addAll(warfareFlightFeedbackScenarios(y));
        return list;
    }
    private List<Scenario> warfareMovementScenarios(int y) {
        List<Scenario> list = new ArrayList<>(); var warfare = legacy().getSkills().warfare();
        List<Bot> movers = new ArrayList<>(); double[] lateral = new double[7];
        list.add(new Scenario("warfare movement levels four through ten strafe while actually firing within 100 ticks", () -> {
            forceArea(7300, -8, 7570, 8); run("bot settings setgoal nearesthostile");
            for (int i = 0; i < 7; i++) {
                Bot bot = infantry("MovingGun" + (i + 4), i + 4, 7305.5 + i * 40, y, 0.5);
                bot.setLook(-90, 0); bot.giveItem(loadedGun("ak_47", 30)); movers.add(bot);
                spawnHusk(7329.5 + i * 40, y, 0.5, 1000, true);
            }
            track(() -> { for (int i = 0; i < movers.size(); i++) lateral[i] = Math.max(lateral[i], Math.abs(movers.get(i).getZ() - 0.5)); });
        }, () -> movers.getFirst().getAliveTicks() >= 95 && movers.stream().allMatch(b -> warfare.shots(b) > 0)
                && java.util.Arrays.stream(lateral).allMatch(d -> d > 1.5), 100, true));
        Bot[] close = {null}; double[] approach = {0};
        list.add(new Scenario("warfare movement shotgun outside effective band approaches before real firing", () -> {
            forceArea(7600, -8, 7650, 8); run("bot settings setgoal nearesthostile");
            close[0] = infantry("CloseShotgun", 5, 7605.5, y, 0.5); close[0].setLook(-90, 0);
            close[0].giveItem(loadedGun("aa_12", 25)); spawnHusk(7635.5, y, 0.5, 1000, true);
            track(() -> approach[0] = Math.max(approach[0], close[0].getX() - 7605.5));
        }, () -> approach[0] > 8 && warfare.shots(close[0]) > 0, 180, true));
        Bot[] pearl = {null}; boolean[] thrown = {false}; long[] pearlShots = {0};
        list.add(new Scenario("warfare movement out of shotgun range triggers an actual owned pearl", () -> {
            forceArea(7670, -10, 7750, 10); run("bot settings setgoal nearesthostile");
            pearl[0] = infantry("GunPearl", 5, 7675.5, y, 0.5); pearl[0].setLook(-90, 0);
            pearl[0].profileAbilities().put("pearls", true); pearl[0].giveItem(loadedGun("aa_12", 25)); pearl[0].giveItem(new ItemStack(Items.ENDER_PEARL, 8));
            spawnHusk(7730.5, y, 0.5, 1000, true);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof net.minecraft.world.entity.projectile.ThrownEnderpearl p && p.getOwner() == pearl[0]) { thrown[0] = true; pearlShots[0] = warfare.shots(pearl[0]); }
            };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
        }, () -> thrown[0] && pearl[0].countItem(Items.ENDER_PEARL) < 8 && warfare.shots(pearl[0]) > pearlShots[0], 260, true));
        Bot[] reload = {null}; ItemStack[] reloadGun = {null}; Vec3[] start = {null}; double[] moved = {0};
        list.add(new Scenario("warfare movement open field native reload continues actual evasive walking", () -> {
            forceArea(7770, -10, 7820, 10); run("bot settings setgoal nearesthostile");
            reload[0] = infantry("MovingReload", 5, 7775.5, y, 0.5); reload[0].setLook(-90, 0);
            reloadGun[0] = equipGun(reload[0], loadedGun("ak_47", 0)); overrideGun(reloadGun[0], "{\"EmptyReloadTime\":70}");
            reload[0].giveItem(warfareItem("rifle_ammo", 64)); spawnHusk(7799.5, y, 0.5, 1000, true);
            track(() -> { if (warfare.reloading(reloadGun[0])) {
                if (start[0] == null) start[0] = reload[0].position();
                moved[0] = Math.max(moved[0], horizontalDistance(start[0], reload[0].position()));
            } });
        }, () -> moved[0] > 2 && warfare.shots(reload[0]) > 0, 240, true));
        Bot[] medic = {null}; boolean[] injured = {false}, recovering = {false}, ateApple = {false}, resumedGun = {false}; long[] interruptedShots = {0}; Vec3[] injuryPos = {null}; double[] retreat = {0};
        list.add(new Scenario("warfare movement low health retreats consumes an actual apple and resumes the real gun", () -> {
            cooperationArena(7840, -20, 7900, 20, y); run("bot settings setgoal nearesthostile");
            medic[0] = infantry("MovingMedic", 8, 7845.5, y, 0.5); medic[0].setLook(-90, 0);
            medic[0].profileAbilities().put("recovery", true); medic[0].profileAbilities().put("pathfinding", true); medic[0].giveItem(loadedGun("ak_47", 30)); medic[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2));
            medic[0].giveItem(warfareItem("rifle_ammo", 64));
            Husk elevatedThreat = spawnHusk(7855.5, y + 6, 0.5, 1000, true); elevatedThreat.setNoGravity(true);
            track(() -> {
                if (!injured[0] && medic[0].getAliveTicks() > 70 && warfare.shots(medic[0]) > 0) {
                    injured[0] = true; injuryPos[0] = medic[0].position(); interruptedShots[0] = warfare.shots(medic[0]);
                    elevatedThreat.setPos(medic[0].getX() + 2, y + 12, medic[0].getZ());
                    LOGGER.info("[SelfTest] Gun medic injury pos={} visibleThreat={}", medic[0].position(), medic[0].hasLineOfSight(elevatedThreat));
                    float impact = switch (level.getDifficulty()) { case EASY -> 26; case HARD -> 14 / 1.5F; default -> 14; };
                    if (!medic[0].hurt(level.damageSources().mobAttack(elevatedThreat), impact)) throw new IllegalStateException("Medic fixture did not receive its actual attack");
                    medic[0].getFoodData().setFoodLevel(10); medic[0].getFoodData().setSaturation(0);
                }
                if (injured[0]) {
                    ateApple[0] |= medic[0].hasEffect(MobEffects.REGENERATION) && medic[0].countItem(Items.GOLDEN_APPLE) < 2;
                    resumedGun[0] |= ateApple[0] && warfare.holding(medic[0]) && warfare.shots(medic[0]) > interruptedShots[0];
                    if (medic[0].getAliveTicks() % 40 == 0) LOGGER.info("[SelfTest] Gun medic hp={} tactic={} retreat={} food={} ate={} resumed={} state={}",
                            medic[0].getHealth(), legacy().getSkills().memory(medic[0]).getTactic(), retreat[0], medic[0].countItem(Items.GOLDEN_APPLE), ateApple[0], resumedGun[0], warfare.describe(medic[0]));
                }
                if (injured[0] && legacy().getSkills().memory(medic[0]).getTactic() != BotMemory.Tactic.FIGHT) {
                    recovering[0] = true; retreat[0] = Math.max(retreat[0], horizontalDistance(injuryPos[0], medic[0].position()));
                }
            });
        }, () -> injured[0] && recovering[0] && retreat[0] > 2 && medic[0].countItem(Items.GOLDEN_APPLE) < 2
                && ateApple[0] && resumedGun[0], 500, true));
        Bot[] flier = {null}; boolean[] flying = {false};
        list.add(new Scenario("warfare movement out of shotgun range allows real elytra takeoff and fireworks", () -> {
            forceArea(7930, -15, 8050, 15); run("bot settings setgoal nearesthostile");
            flier[0] = infantry("FlyingGun", 5, 7935.5, y, 0.5); flier[0].setLook(-90, 0);
            flier[0].profileAbilities().put("elytra", true); flier[0].giveItem(loadedGun("aa_12", 25));
            flier[0].giveItem(new ItemStack(Items.ELYTRA)); flier[0].giveItem(new ItemStack(Items.FIREWORK_ROCKET, 32));
            spawnHusk(8020.5, y, 0.5, 1000, true);
            track(() -> flying[0] |= flier[0].isGliding() && flier[0].getY() > y + 2);
        }, () -> flying[0] && flier[0].countItem(Items.FIREWORK_ROCKET) < 32 && !warfare.holding(flier[0]), 240, true));
        List<Bot> red = new ArrayList<>(), blue = new ArrayList<>(); java.util.Map<Bot, Vec3> births = new java.util.HashMap<>();
        java.util.Set<java.util.UUID> pearlOwners = new java.util.HashSet<>(), bulletOwners = new java.util.HashSet<>();
        list.add(new Scenario("warfare movement feedback five versus five empty level five kits hold ranged combat at sixty blocks", () -> {
            forceArea(8100, -30, 8200, 30); run("bot settings setgoal nearestenemy");
            for (int i = 0; i < 5; i++) for (int side = 0; side < 2; side++) {
                Bot b = spawnBot((side == 0 ? "FeedbackRed" : "FeedbackBlue") + i, 8110.5 + side * 60, y, -12.5 + i * 6);
                b.setHardnessOverride(5); net.nuggetmc.tplus.bot.DefaultEquipment.apply(b, 5); b.setLook(side == 0 ? -90 : 90, 0);
                b.giveItem(loadedGun("ak_47", 0)); b.giveItem(loadedGun("aa_12", 0)); b.giveItem(warfareItem("rifle_ammo", 64)); b.giveItem(warfareItem("rifle_ammo", 64)); b.giveItem(warfareItem("shotgun_ammo", 64));
                (side == 0 ? red : blue).add(b); births.put(b, b.position());
            }
            testTeam("feedback_red", red.toArray(Bot[]::new)).setAllowFriendlyFire(false); testTeam("feedback_blue", blue.toArray(Bot[]::new)).setAllowFriendlyFire(false);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner() instanceof Bot b && births.containsKey(b)) {
                    if (p instanceof net.minecraft.world.entity.projectile.ThrownEnderpearl) pearlOwners.add(b.getUUID());
                    else if (p.getClass().getName().startsWith("com.atsuishio.superbwarfare")) bulletOwners.add(b.getUUID());
                }
            };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            track(() -> { if (red.getFirst().getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Rifle5v5 bullets={} pearls={} actors={}", bulletOwners.size(), pearlOwners.size(), births.keySet().stream().map(b -> b.getName().getString() + ":alive=" + b.isAlive() + ":move=" + horizontalDistance(births.get(b), b.position()) + ":fireworks=" + b.countItem(Items.FIREWORK_ROCKET) + ":" + warfare.describe(b)).toList()); });
        }, () -> red.getFirst().getAliveTicks() > 100 && births.keySet().stream().allMatch(b -> horizontalDistance(births.get(b), b.position()) > 3)
                && pearlOwners.isEmpty() && births.keySet().stream().noneMatch(Bot::isGliding)
                && red.getFirst().position().distanceTo(blue.getFirst().position()) > 20
                && births.keySet().stream().allMatch(b -> bulletOwners.contains(b.getUUID()) && b.countItem(Items.FIREWORK_ROCKET) == 64), 400, true));
        return list;
    }
    private Entity spawnVehicle(String id, double x, int y, double z, float yaw) {
        var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("superbwarfare", id));
        Entity entity = Objects.requireNonNull(type.create(level)); entity.moveTo(x, y, z, yaw, 0);
        level.addFreshEntity(entity); spawned.add(entity); legacy().getSkills().warfare().vessel(entity).energy(2000000); return entity;
    }
    private Bot vehicleBot(String name, int difficulty, double x, int y, double z) {
        Bot bot = infantry(name, difficulty, x, y, z);
        for (String key : List.of("vehicles", "vehicleweapons", "helicopters")) bot.profileAbilities().put(key, true);
        return bot;
    }
    private java.util.Set<java.util.UUID> captureNativeOwners(java.util.Collection<Bot> bots) {
        java.util.Set<java.util.UUID> owners = new java.util.HashSet<>();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
            if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner() instanceof Bot b && bots.contains(b)
                    && p.getClass().getName().startsWith("com.atsuishio.superbwarfare")) owners.add(b.getUUID());
        };
        NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener)); return owners;
    }
    private List<Scenario> warfareVehicleScenarios(int y) {
        List<Scenario> list = new ArrayList<>(); var warfare = legacy().getSkills().warfare(); var crew = warfare.crew();
        Bot[] driver = {null}; List<Bot> team = new ArrayList<>(); Entity[] carrier = {null}; boolean[] occupied = {false}, stable = {true};
        list.add(new Scenario("warfare vehicle native crew commands allocate unique seats and track actual driving", () -> {
            forceArea(8300, -25, 8390, 25); run("bot settings setgoal none");
            driver[0] = vehicleBot("CrewDriver", 10, 8305.5, y, 0.5); team.add(driver[0]);
            team.add(vehicleBot("CrewMate1", 10, 8305.5, y, 2.5)); team.add(vehicleBot("CrewMate2", 10, 8305.5, y, -2.5));
            testTeam("vehicle_crew", team.toArray(Bot[]::new)); carrier[0] = spawnVehicle("m_1a_2", 8310.5, y, 0.5, -90);
            run("bot vehicle crew CrewDriver " + carrier[0].getUUID()); run("bot vehicle go CrewDriver 8350.5 " + y + " 0.5");
            track(() -> {
                var v = warfare.vessel(carrier[0]);
                if (team.stream().allMatch(b -> b.getVehicle() == carrier[0])) {
                    occupied[0] = team.stream().mapToInt(v::seatIndex).distinct().count() == team.size();
                    for (Bot b : team) stable[0] &= b.position().distanceTo(carrier[0].position()) < 10 && b.getVelocity().lengthSqr() < 0.0001 && b.fallDistance == 0;
                }
                if (driver[0].getAliveTicks() % 40 == 0) LOGGER.info("[SelfTest] Native driver carrier={} velocity={} state={}", carrier[0].position(), carrier[0].getDeltaMovement(), warfare.describe(driver[0]));
            });
        }, () -> occupied[0] && stable[0] && carrier[0].getX() > 8322 && team.stream().allMatch(b -> b.isAlive() && b.getHealth() == 20), 340, true));
        Bot[] primary = {null}, secondary = {null}; Entity[] tank = {null}; Husk[] enemy = {null};
        java.util.Set<java.util.UUID>[] owners = new java.util.Set[]{null}, damage = new java.util.Set[]{null};
        list.add(new Scenario("warfare vehicle both actual tank weapon seats spawn owned projectiles and cause damage", () -> {
            forceArea(8420, -25, 8490, 25); run("bot settings setgoal nearesthostile");
            primary[0] = vehicleBot("TankPrimary", 10, 8427.5, y, 0.5); secondary[0] = vehicleBot("TankSecondary", 10, 8427.5, y, 2.5);
            testTeam("tank_gunners", primary[0], secondary[0]); tank[0] = spawnVehicle("m_1a_2", 8430.5, y, 0.5, -90);
            crew.board(primary[0], tank[0], 0); crew.board(secondary[0], tank[0], 1);
            enemy[0] = spawnHusk(8460.5, y, 0.5, 10000, false); owners[0] = captureNativeOwners(List.of(primary[0], secondary[0])); damage[0] = capturePositiveHits(enemy[0]);
            track(() -> { if (enemy[0].isAlive()) enemy[0].setHealth(enemy[0].getMaxHealth()); if (primary[0].getAliveTicks() % 40 == 0) LOGGER.info("[SelfTest] Tank seats primary={} secondary={} health={} weapons={}",
                    warfare.describe(primary[0]), warfare.describe(secondary[0]) + "; owners=" + owners[0] + "; damage=" + damage[0], enemy[0].getHealth(), primary[0].isPassenger() ? warfare.vessel(tank[0]).weapons(primary[0]) : "boarding"); });
        }, () -> owners[0].contains(primary[0].getUUID()) && owners[0].contains(secondary[0].getUUID())
                && damage[0].contains(primary[0].getUUID()) && damage[0].contains(secondary[0].getUUID()) && warfare.vessel(tank[0]).seatIndex(secondary[0]) == 1, 420, true));
        Bot[] port = {null}; Entity[] apc = {null}; java.util.Set<java.util.UUID>[] portHits = new java.util.Set[]{null};
        list.add(new Scenario("warfare vehicle infantry firing port uses only its native seat weapon", () -> {
            forceArea(8520, -40, 8580, 40); run("bot settings setgoal nearesthostile");
            port[0] = vehicleBot("FiringPort", 10, 8530.5, y, 3.5); apc[0] = spawnVehicle("bmp_2", 8530.5, y, 0.5, 0);
            // A human driver prevents the bot from switching to seat zero.
            ServerPlayer human = spawnHuman("PortHuman", 8530.5, y, 0.5); human.startRiding(apc[0], true); testTeam("port_team", port[0]);
            server.getScoreboard().addPlayerToTeam(human.getScoreboardName(), port[0].getTeam()); crew.board(port[0], apc[0], 1);
            Husk target = spawnHusk(8555.5, y, 0.5, 10000, false); portHits[0] = capturePositiveHits(target);
        }, () -> portHits[0].contains(port[0].getUUID()) && crew.shots(port[0]) > 0 && warfare.vessel(apc[0]).seatIndex(port[0]) == 1
                && warfare.vessel(apc[0]).weapons(port[0]).stream().allMatch(w -> w.name().equals("MG1")), 320, true));
        Bot[] pilot = {null}, passenger = {null}; Entity[] helicopter = {null}; double[] altitude = {0}, travel = {0}; boolean[] landed = {false};
        list.add(new Scenario("warfare vehicle helicopter uses native inputs to take off fly to a waypoint and land", () -> {
            forceArea(8620, -50, 8760, 50); run("bot settings setgoal none");
            pilot[0] = vehicleBot("HeliPilot", 9, 8635.5, y, 0.5); passenger[0] = vehicleBot("HeliPassenger", 9, 8635.5, y, 2.5);
            testTeam("heli_trip", pilot[0], passenger[0]); helicopter[0] = spawnVehicle("ah_6", 8637.5, y, 0.5, -90);
            crew.board(pilot[0], helicopter[0], 0); crew.board(passenger[0], helicopter[0], 1); crew.go(pilot[0], new Vec3(8677.5, y, 0.5));
            track(() -> {
                altitude[0] = Math.max(altitude[0], helicopter[0].getY() - y); travel[0] = Math.max(travel[0], helicopter[0].getX() - 8637.5);
                landed[0] |= altitude[0] > 6 && helicopter[0].onGround() && Math.abs(helicopter[0].getX() - 8677.5) < 10;
                if (pilot[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Heli flight pos={} vel={} pitch={} yaw={} roll={} ground={} pilot={}",
                        helicopter[0].position(), helicopter[0].getDeltaMovement(), helicopter[0].getXRot(), helicopter[0].getYRot(), warfare.vessel(helicopter[0]).roll(), helicopter[0].onGround(), warfare.describe(pilot[0]));
            });
        }, () -> altitude[0] > 6 && travel[0] > 25 && landed[0] && pilot[0].isPassenger() && passenger[0].isPassenger(), 1200, true));
        Bot[] door = {null}; Entity[] doorHeli = {null}; ItemStack[] doorGun = {null}; java.util.Set<java.util.UUID>[] doorHits = new java.util.Set[]{null};
        list.add(new Scenario("warfare vehicle helicopter open passenger seat uses a real personal rifle", () -> {
            forceArea(8800, -40, 8880, 40); run("bot settings setgoal nearesthostile");
            door[0] = vehicleBot("DoorGun", 8, 8820.5, y, 3.5); door[0].profileAbilities().put("vehicleweapons", false);
            doorGun[0] = equipGun(door[0], loadedGun("ak_47", 30)); doorHeli[0] = spawnVehicle("ah_6", 8820.5, y, 0.5, 0);
            ServerPlayer human = spawnHuman("DoorHuman", 8820.5, y, 0.5); human.startRiding(doorHeli[0], true); testTeam("door_team", door[0]);
            server.getScoreboard().addPlayerToTeam(human.getScoreboardName(), door[0].getTeam()); crew.board(door[0], doorHeli[0], 3);
            Husk target = spawnHusk(8850.5, y, 0.5, 10000, false); doorHits[0] = capturePositiveHits(target);
        }, () -> doorHits[0].contains(door[0].getUUID()) && warfare.shots(door[0]) > 0 && warfare.ammo(doorGun[0]) < 30 && warfare.vessel(doorHeli[0]).seatIndex(door[0]) == 3, 320, true));
        Bot[] heliDriver = {null}, heliGunner = {null}; Entity[] attackHeli = {null}; java.util.Set<java.util.UUID>[] gunnerHits = new java.util.Set[]{null}; double[] airHeight = {0};
        list.add(new Scenario("warfare vehicle helicopter second seat operates its own native cannon during actual flight", () -> {
            forceArea(8910, -70, 9080, 70); run("bot settings setgoal nearesthostile");
            heliDriver[0] = vehicleBot("AttackPilot", 10, 8925.5, y, 0.5); heliGunner[0] = vehicleBot("AttackGunner", 10, 8925.5, y, 2.5);
            testTeam("attack_heli", heliDriver[0], heliGunner[0]); attackHeli[0] = spawnVehicle("mi_28", 8927.5, y, 0.5, -90);
            crew.board(heliDriver[0], attackHeli[0], 0); crew.board(heliGunner[0], attackHeli[0], 1);
            Husk target = spawnHusk(9000.5, y, 0.5, 10000, false); gunnerHits[0] = capturePositiveHits(target);
            track(() -> { airHeight[0] = Math.max(airHeight[0], attackHeli[0].getY() - y);
                if (heliDriver[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Heli attack pos={} pilot={} gunner={}", attackHeli[0].position(), warfare.describe(heliDriver[0]), warfare.describe(heliGunner[0])); });
        }, () -> airHeight[0] > 6 && gunnerHits[0].contains(heliGunner[0].getUUID()) && crew.shots(heliGunner[0]) > 0
                && warfare.vessel(attackHeli[0]).seatIndex(heliGunner[0]) == 1, 1000, true));
        Bot[] guarded = {null}; Entity[] locked = {null}; boolean[] refused = {false};
        list.add(new Scenario("warfare vehicle seat ownership rejects occupied enemy seats and invalid commands", () -> {
            forceArea(9110, -10, 9160, 10); run("bot settings setgoal none"); guarded[0] = vehicleBot("SafeBoard", 9, 9120.5, y, 0.5);
            locked[0] = spawnVehicle("ah_6", 9122.5, y, 0.5, 0); Husk hostile = spawnHusk(9122.5, y, 0.5, 100, true); hostile.startRiding(locked[0], true);
            try { crew.board(guarded[0], locked[0], 0); } catch (IllegalArgumentException expected) { refused[0] = true; }
            run("bot vehicle go SafeBoard NaN " + y + " 0"); run("bot vehicle board SafeBoard nearest 99");
        }, () -> refused[0] && !guarded[0].isPassenger() && warfare.vessel(locked[0]).passenger(0) != guarded[0], 40, true));
        Bot[] airPilot = {null}, airRifle = {null}; Entity[] transport = {null}; boolean[] airHit = {false};
        list.add(new Scenario("warfare vehicle personal rifle passenger causes real damage while helicopter is airborne", () -> {
            forceArea(9200, -80, 9350, 50); run("bot settings setgoal nearesthostile");
            airPilot[0] = vehicleBot("AirRiflePilot", 9, 9220.5, y, 0.5); airRifle[0] = vehicleBot("AirRiflePassenger", 8, 9220.5, y, 2.5);
            airRifle[0].profileAbilities().put("vehicleweapons", false); airRifle[0].giveItem(loadedGun("ak_47", 30)); airRifle[0].giveItem(warfareItem("rifle_ammo", 64));
            testTeam("air_rifle", airPilot[0], airRifle[0]); transport[0] = spawnVehicle("ah_6", 9222.5, y, 0.5, -90);
            crew.board(airPilot[0], transport[0], 0); crew.board(airRifle[0], transport[0], 3); crew.go(airPilot[0], new Vec3(9272.5, y, 0.5));
            Husk target = spawnHusk(9247.5, y, -30.5, 1000, false);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> listener = event -> {
                if (event.getEntity() == target && event.getSource().getEntity() == airRifle[0] && event.getNewDamage() > 0
                        && transport[0].getY() > y + 6 && !transport[0].onGround()) airHit[0] = true;
            };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            track(() -> { if (airPilot[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Air rifle pos={} hand={} state={} target={} hit={}",
                    transport[0].position(), airRifle[0].getMainHandItem(), warfare.describe(airRifle[0]), legacy().getSkills().currentTarget(airRifle[0]), airHit[0]); });
        }, () -> airHit[0] && warfare.shots(airRifle[0]) > 0 && warfare.vessel(transport[0]).seatIndex(airRifle[0]) == 3, 750, true));
        Bot[] landingPilot = {null}, leaving = {null}; Entity[] taxi = {null}; boolean[] requested = {false}, stayed = {true};
        list.add(new Scenario("warfare vehicle passenger exit request lands the actual helicopter before dismounting", () -> {
            forceArea(9380, -50, 9510, 50); run("bot settings setgoal none");
            landingPilot[0] = vehicleBot("LandingPilot", 9, 9400.5, y, 0.5); leaving[0] = vehicleBot("LeavingPassenger", 9, 9400.5, y, 2.5);
            testTeam("landing_taxi", landingPilot[0], leaving[0]); taxi[0] = spawnVehicle("ah_6", 9402.5, y, 0.5, -90);
            crew.board(landingPilot[0], taxi[0], 0); crew.board(leaving[0], taxi[0], 1); crew.go(landingPilot[0], new Vec3(9462.5, y, 0.5));
            track(() -> {
                if (!requested[0] && taxi[0].getY() > y + 8 && leaving[0].isPassenger()) { requested[0] = true; run("bot vehicle leave LeavingPassenger"); }
                if (requested[0] && taxi[0].getY() > y + 2) stayed[0] &= leaving[0].getVehicle() == taxi[0];
                if (landingPilot[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Landing taxi pos={} ground={} request={} passenger={} pilot={}",
                        taxi[0].position(), taxi[0].onGround(), requested[0], leaving[0].isPassenger(), warfare.describe(landingPilot[0]));
            });
        }, () -> requested[0] && stayed[0] && !leaving[0].isPassenger() && leaving[0].getY() < y + 3 && leaving[0].getHealth() == 20, 850, true));
        Bot[] finiteGunner = {null}; Entity[] finiteTank = {null}; boolean[] initialized = {false}; long[] emptyAt = {-1}; java.util.Set<java.util.UUID>[] finiteOwners = new java.util.Set[]{null};
        list.add(new Scenario("warfare vehicle level nine mounted ammo stays finite and exhausts six real rounds", () -> {
            forceArea(9540, -20, 9620, 20); run("bot settings setgoal nearesthostile");
            finiteGunner[0] = vehicleBot("FiniteMounted", 9, 9560.5, y, 2.5); finiteTank[0] = spawnVehicle("m_1a_2", 9562.5, y, 0.5, -90);
            ServerPlayer human = spawnHuman("FiniteDriver", 9562.5, y, 0.5); human.startRiding(finiteTank[0], true); testTeam("finite_mounted", finiteGunner[0]);
            server.getScoreboard().addPlayerToTeam(human.getScoreboardName(), finiteGunner[0].getTeam()); crew.board(finiteGunner[0], finiteTank[0], 1);
            spawnHusk(9592.5, y, 0.5, 1000, true); finiteOwners[0] = captureNativeOwners(List.of(finiteGunner[0]));
            track(() -> {
                if (!initialized[0] && finiteGunner[0].isPassenger()) { initialized[0] = true; var v = warfare.vessel(finiteTank[0]); v.weaponGun(finiteGunner[0], 0).virtualAmmo(6); v.changed(); }
                if (initialized[0] && finiteGunner[0].getAliveTicks() % 30 == 0) LOGGER.info("[SelfTest] Finite mounted state={} ammo={} virtual={} nativeOwners={} target={} look={}",
                        warfare.describe(finiteGunner[0]), warfare.vessel(finiteTank[0]).weapons(finiteGunner[0]), warfare.vessel(finiteTank[0]).weaponGun(finiteGunner[0], 0).virtualAmmo(),
                        finiteOwners[0], legacy().getSkills().currentTarget(finiteGunner[0]), finiteGunner[0].getLookAngle());
                if (initialized[0] && crew.shots(finiteGunner[0]) == 6 && emptyAt[0] < 0) emptyAt[0] = server.getTickCount();
            });
        }, () -> initialized[0] && emptyAt[0] > 0 && server.getTickCount() - emptyAt[0] > 50 && crew.shots(finiteGunner[0]) == 6
                && warfare.vessel(finiteTank[0]).weaponGun(finiteGunner[0], 0).virtualAmmo() == 0 && finiteOwners[0].contains(finiteGunner[0].getUUID()), 300, true));
        Bot[] closed = {null}; Entity[] closedTank = {null}; ItemStack[] closedRifle = {null};
        list.add(new Scenario("warfare vehicle native banned hand seat never fires or consumes personal gun ammo", () -> {
            forceArea(9650, -20, 9730, 20); run("bot settings setgoal nearesthostile");
            closed[0] = vehicleBot("ClosedPassenger", 9, 9660.5, y, 2.5); closedRifle[0] = equipGun(closed[0], loadedGun("ak_47", 30));
            closedTank[0] = spawnVehicle("m_1a_2", 9662.5, y, 0.5, -90); ServerPlayer human = spawnHuman("ClosedDriver", 9662.5, y, 0.5);
            human.startRiding(closedTank[0], true); testTeam("closed_tank", closed[0]); server.getScoreboard().addPlayerToTeam(human.getScoreboardName(), closed[0].getTeam());
            crew.board(closed[0], closedTank[0], 4); spawnHusk(9692.5, y, 0.5, 1000, false);
        }, () -> closed[0].getAliveTicks() >= 100 && warfare.vessel(closedTank[0]).seatIndex(closed[0]) == 4 && !crew.canUseHand(closed[0])
                && warfare.shots(closed[0]) == 0 && warfare.ammo(closedRifle[0]) == 30, 140, true));
        List<Bot> mixed = new ArrayList<>(), mountedBudget = new ArrayList<>(); List<Entity> budgetVehicles = new ArrayList<>();
        java.util.Map<Long, Integer> mixedCounts = new java.util.HashMap<>(); java.util.Set<java.util.UUID> mixedOwners = new java.util.HashSet<>(); java.util.Map<java.util.UUID, java.util.Map<Long, Integer>> mountedFrames = new java.util.HashMap<>(); boolean[] mixedSafe = {true};
        list.add(new Scenario("warfare vehicle delayed mounted and handheld projectiles share the actual tick budget", () -> {
            forceArea(9770, -60, 9880, 140); run("bot settings setgoal nearesthostile");
            List<ServerPlayer> humans = new ArrayList<>();
            List<LivingEntity> budgetParticipants = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                Bot b = infantry("MixedShotgun" + i, 10, 9775.5, y, -25.5 + i * 5); b.setLook(-90, 0); b.giveItem(loadedGun("aa_12", 25)); mixed.add(b);
                budgetParticipants.add(spawnHusk(9799.5, y, -25.5 + i * 5, 1000, true));
            }
            for (int i = 0; i < 2; i++) {
                Bot b = vehicleBot("MixedMounted" + i, 10, 9830.5, y, 80.5 + i * 40); mixed.add(b); mountedBudget.add(b);
                Entity v = spawnVehicle("m_1a_2", 9832.5, y, 80.5 + i * 40, -90); budgetVehicles.add(v);
                ServerPlayer human = spawnHuman("MixedHuman" + i, 9832.5, y, 80.5 + i * 40); human.startRiding(v, true); humans.add(human);
                testTeam("mixed_mount_" + i, b); server.getScoreboard().addPlayerToTeam(human.getScoreboardName(), b.getTeam()); crew.board(b, v, 1);
                budgetParticipants.add(spawnHusk(9862.5, y, 80.5 + i * 40, 1000, true));
            }
            PlayerTeam mixedTeam = testTeam("mixed_budget", mixed.toArray(Bot[]::new));
            humans.forEach(h -> server.getScoreboard().addPlayerToTeam(h.getScoreboardName(), mixedTeam));
            // Keep targets and shooters alive for the sustained budget measurement.
            // Native damage is asserted in other scenarios; this case counts entities.
            budgetParticipants.addAll(mixed);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent> damageIsolation = event -> {
                if (budgetParticipants.contains(event.getEntity())) event.setCanceled(true);
            };
            NeoForge.EVENT_BUS.addListener(damageIsolation); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(damageIsolation));
            java.util.Set<Bot> modified = new java.util.HashSet<>();
            track(() -> { for (int i = 0; i < mountedBudget.size(); i++) {
                Bot b = mountedBudget.get(i);
                if (b.isPassenger() && modified.add(b)) {
                    overrideVehicleGun(budgetVehicles.get(i), b, 0, "{\"ShootDelayTime\":4,\"ProjectileAmount\":16}");
                    var specs = warfare.vessel(budgetVehicles.get(i)).weaponGun(b, 0).specs();
                    if (specs.shootDelay() != 4 || specs.projectiles() != 16) throw new IllegalStateException("Native mounted override was not applied: " + specs);
                    LOGGER.info("[SelfTest] Mounted budget fixture {} native delay={} projectiles={}", b.getName().getString(), specs.shootDelay(), specs.projectiles());
                }
            } });
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner() instanceof Bot b && mixed.contains(b)
                        && p.getClass().getName().startsWith("com.atsuishio.superbwarfare")) {
                    if (mountedBudget.contains(b)) mountedFrames.computeIfAbsent(b.getUUID(), id -> new java.util.HashMap<>()).merge((long) server.getTickCount(), 1, Integer::sum);
                    mixedOwners.add(b.getUUID()); int count = mixedCounts.merge((long) server.getTickCount(), 1, Integer::sum); mixedSafe[0] &= count <= 64;
                    if (count > 64) LOGGER.warn("[SelfTest] Mixed projectile budget tick={} count={}", server.getTickCount(), count);
                }
            };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
        }, () -> {
            boolean passed = mixedSafe[0] && mixedCounts.values().stream().mapToInt(Integer::intValue).sum() > 240
                    && mixed.stream().allMatch(b -> b.getAliveTicks() >= 120 && mixedOwners.contains(b.getUUID()))
                    && mountedBudget.stream().allMatch(b -> mountedFrames.getOrDefault(b.getUUID(), java.util.Map.of()).values().stream().anyMatch(n -> n >= 16));
            if (passed) LOGGER.info("[SelfTest] Mixed budget actual projectile maximum={} total={} owners={}",
                    mixedCounts.values().stream().mapToInt(Integer::intValue).max().orElse(0),
                    mixedCounts.values().stream().mapToInt(Integer::intValue).sum(), mixedOwners.size());
            return passed;
        }, 260, true));
        Bot[] delayedDriver = {null}; Entity[] delayedCarrier = {null}, armor = {null}; Husk[] switchingTarget = {null};
        boolean[] delayModified = {false}, retargeted = {false}, selectionStable = {true}; long[] queued = {-1}; int[] queuedWeapon = {-1};
        java.util.Map<Long, Integer> delayedFrames = new java.util.HashMap<>();
        list.add(new Scenario("warfare vehicle delayed volley keeps its actual weapon through an armored retarget", () -> {
            forceArea(9900, -25, 10000, 25); run("bot settings setgoal nearesthostile");
            delayedDriver[0] = vehicleBot("DelayedRetarget", 10, 9905.5, y, 0.5);
            delayedCarrier[0] = spawnVehicle("m_1a_2", 9907.5, y, 0.5, -90); crew.board(delayedDriver[0], delayedCarrier[0], 0);
            switchingTarget[0] = spawnHusk(9937.5, y, 0.5, 1000, true);
            armor[0] = spawnVehicle("m_1a_2", 9937.5, y, 0.5, -90); armor[0].setInvulnerable(true);
            track(() -> {
                Bot b = delayedDriver[0]; var v = warfare.vessel(delayedCarrier[0]);
                if (!b.isPassenger()) return;
                if (!delayModified[0]) { overrideVehicleGun(delayedCarrier[0], b, 1, "{\"ShootDelayTime\":8,\"ProjectileAmount\":16}"); delayModified[0] = true; }
                if (!retargeted[0] && crew.shots(b) > 0) {
                    queued[0] = server.getTickCount(); queuedWeapon[0] = v.selectedWeapon(b);
                    if (queuedWeapon[0] != 1) throw new IllegalStateException("Delayed fixture did not choose its native machine gun");
                    retargeted[0] = switchingTarget[0].startRiding(armor[0], true);
                } else if (retargeted[0] && server.getTickCount() <= queued[0] + 6) selectionStable[0] &= v.selectedWeapon(b) == queuedWeapon[0];
            });
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner() == delayedDriver[0]
                        && p.getClass().getName().startsWith("com.atsuishio.superbwarfare")) delayedFrames.merge((long) server.getTickCount(), 1, Integer::sum);
            };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
        }, () -> retargeted[0] && selectionStable[0] && delayedFrames.values().stream().anyMatch(n -> n >= 16)
                && warfare.vessel(delayedCarrier[0]).selectedWeapon(delayedDriver[0]) == 0, 200, true));
        return list;
    }
    private List<Scenario> warfareFeedbackScenarios(int y) {
        List<Scenario> list = new ArrayList<>(); var warfare = legacy().getSkills().warfare(); var crew = warfare.crew();
        List<Bot> cannonDrivers = new ArrayList<>(); List<Entity> cannonTargets = new ArrayList<>();
        int[] cannonCounts = new int[2];
        list.add(new Scenario("warfare feedback cannon comparison solo driver and commander both damage armored targets", () -> {
            forceArea(10100, -25, 10440, 25); run("bot settings setgoal nearesthostile");
            for (int i = 0; i < 2; i++) {
                double x = 10110.5 + i * 220;
                Bot driver = vehicleBot("CompareDriver" + i, 10, x - 3, y, 0.5); driver.setLook(-90, 0); cannonDrivers.add(driver);
                Entity own = spawnVehicle("m_1a_2", x, y, 0.5, -90);
                testTeam("cannon_compare_" + i, driver); crew.board(driver, own, 0);
                if (i == 1) {
                    Bot commander = vehicleBot("CompareCommander", 10, x - 3, y, 2.5);
                    server.getScoreboard().addPlayerToTeam(commander.getScoreboardName(), driver.getTeam()); crew.board(commander, own, 1);
                }
                Entity target = spawnVehicle("m_1a_2", x + 70, y, 0.5, 90); cannonTargets.add(target);
                spawnHusk(x + 70, y, 0.5, 10000, true).startRiding(target, true);
            }
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner() instanceof Bot b) {
                    int index = cannonDrivers.indexOf(b);
                    if (index >= 0 && p.getClass().getSimpleName().contains("CannonShell")) cannonCounts[index]++;
                }
            };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            track(() -> { if (cannonDrivers.getFirst().getAliveTicks() % 40 == 0) LOGGER.info("[SelfTest] Cannon comparison shells={} health={} solo={} crew={}",
                    java.util.Arrays.toString(cannonCounts), cannonTargets.stream().map(this::vehicleHealth).toList(), warfare.describe(cannonDrivers.get(0)), warfare.describe(cannonDrivers.get(1))); });
        }, () -> cannonDrivers.getFirst().getAliveTicks() >= 180 && cannonCounts[0] > 0 && cannonCounts[1] > 0
                && cannonTargets.stream().allMatch(v -> vehicleHealth(v) < 500), 240, true));
        Bot[] borderDriver = {null}; Entity[] borderTank = {null}; boolean[] bounded = {true}, retreated = {false};
        list.add(new Scenario("warfare feedback retreat stays inside a 150 block border with native braking", () -> {
            forceArea(10500, -65, 10650, 65); run("bot settings setgoal nearesthostile"); testBorder(10575, 0, 150);
            borderDriver[0] = vehicleBot("BorderDriver", 10, 10626.5, y, 0.5);
            borderDriver[0].profileAbilities().put("vehicleweapons", false);
            borderTank[0] = spawnVehicle("m_1a_2", 10629.5, y, 0.5, -90); crew.board(borderDriver[0], borderTank[0], 0);
            Entity enemy = spawnVehicle("m_1a_2", 10589.5, y, 0.5, 90); Husk rider = spawnHusk(10589.5, y, 0.5, 10000, true); rider.startRiding(enemy, true);
            warfare.vessel(enemy).weaponGun(rider, 0).virtualAmmo(4); setVehicleHealth(borderTank[0], 145);
            track(() -> {
                retreated[0] |= warfare.describe(borderDriver[0]).contains("RETREAT") || warfare.describe(borderDriver[0]).contains("BORDER_BRAKE");
                bounded[0] &= net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds.contains(borderTank[0], borderTank[0].position(), 2)
                        && level.getWorldBorder().isWithinBounds(borderDriver[0].getBoundingBox());
                if (borderDriver[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Border retreat position={} state={} bounded={}", borderTank[0].position(), warfare.describe(borderDriver[0]), bounded[0]);
            });
        }, () -> borderDriver[0].getAliveTicks() >= 280 && retreated[0] && bounded[0] && borderDriver[0].isAlive()
                && borderTank[0].getX() > 10631 && borderTank[0].getX() < 10644, 300, true));

        Bot[] dual = {null}; ItemStack[] dualGuns = new ItemStack[2]; boolean[] stableReload = {true}; int[] switches = {0}; ItemStack[] lastHand = {null};
        list.add(new Scenario("warfare feedback two empty guns complete independent cooldowns while distance and skills change", () -> {
            forceArea(10700, -10, 10780, 10); run("bot settings setgoal nearesthostile");
            dual[0] = infantry("DualEmpty", 5, 10710.5, y, 0.5); dual[0].setLook(-90, 0);
            dualGuns[0] = equipGun(dual[0], loadedGun("ak_47", 0)); dualGuns[1] = equipGun(dual[0], loadedGun("aa_12", 0));
            dual[0].giveItem(warfareItem("rifle_ammo", 64)); dual[0].giveItem(warfareItem("shotgun_ammo", 64));
            dual[0].profileAbilities().put("pearls", true); dual[0].profileAbilities().put("elytra", true);
            dual[0].giveItem(new ItemStack(Items.ENDER_PEARL, 8)); dual[0].giveItem(new ItemStack(Items.ELYTRA)); dual[0].giveItem(new ItemStack(Items.FIREWORK_ROCKET, 32));
            Husk enemy = spawnHusk(10750.5, y, 0.5, 10000, true);
            track(() -> {
                int age = (int) dual[0].getAliveTicks(); enemy.setPos(dual[0].getX() + (age / 20 % 2 == 0 ? 7 : 24), y, dual[0].getZ());
                ItemStack hand = dual[0].getMainHandItem();
                if (lastHand[0] != null && hand != lastHand[0]) { switches[0]++; }
                if (warfare.isGun(hand)) lastHand[0] = hand;
                if (age % 60 == 0) LOGGER.info("[SelfTest] Dual empty {} stable={} switches={}", warfare.describe(dual[0]), stableReload[0], switches[0]);
            });
        }, () -> dual[0].getAliveTicks() >= 160 && warfare.shots(dual[0]) > 0 && dual[0].countItem(warfareItem("rifle_ammo", 1).getItem()) < 64 && dual[0].countItem(warfareItem("shotgun_ammo", 1).getItem()) < 64, 340, true));

        Bot[] idle = {null}; ItemStack[] idleGuns = new ItemStack[3]; java.util.Set<ItemStack> actualHands = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        list.add(new Scenario("warfare feedback idle independent preparation loads real guns including RPG without occupying the hand", () -> {
            forceArea(10810, -5, 10840, 5); run("bot settings setgoal none");
            idle[0] = infantry("IdleLoader", 7, 10820.5, y, 0.5);
            idleGuns[0] = equipGun(idle[0], loadedGun("ak_47", 0)); idleGuns[1] = equipGun(idle[0], loadedGun("aa_12", 1)); idleGuns[2] = equipGun(idle[0], loadedGun("rpg", 0));
            idle[0].giveItem(warfareItem("rifle_ammo", 64)); idle[0].giveItem(warfareItem("shotgun_ammo", 64)); idle[0].giveItem(warfareItem("rpg_rocket_standard", 4));
            track(() -> { if (warfare.holding(idle[0])) actualHands.add(idle[0].getMainHandItem());
                if (idle[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Idle loader mags={} hand={}", java.util.Arrays.stream(idleGuns).mapToInt(warfare::ammo).toArray(), warfare.describe(idle[0])); });
        }, () -> actualHands.isEmpty() && warfare.ammo(idleGuns[0]) == 30 && warfare.ammo(idleGuns[1]) >= 25 && warfare.ammo(idleGuns[1]) <= 26 && warfare.ammo(idleGuns[2]) == 1
                && idle[0].countItem(warfareItem("rpg_rocket_standard", 1).getItem()) < 4 && warfare.shots(idle[0]) == 0, 400, true));

        Bot[] closeDriver = {null}; Entity[] closeTank = {null}; Husk[] closeInfantry = {null}; boolean[] rammed = {false};
        list.add(new Scenario("warfare feedback tank attacks six block infantry by native machine gun or vehicle strike", () -> {
            forceArea(10900, -20, 10960, 20); run("bot settings setgoal nearesthostile");
            closeDriver[0] = vehicleBot("CloseDriver", 10, 10912.5, y, 0.5); closeDriver[0].setLook(-90, 0);
            closeTank[0] = spawnVehicle("m_1a_2", 10915.5, y, 0.5, -90); crew.board(closeDriver[0], closeTank[0], 0);
            closeInfantry[0] = spawnHusk(10921.5, y, 0.5, 10000, false); closeInfantry[0].setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> damage = event -> {
                if (event.getEntity() == closeInfantry[0] && event.getNewDamage() > 0 && event.getSource().getMsgId().contains("vehicle")) rammed[0] = true;
            };
            NeoForge.EVENT_BUS.addListener(damage); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(damage));
            track(() -> { if (closeDriver[0].getAliveTicks() % 40 == 0) LOGGER.info("[SelfTest] Close tank {} hp={} ram={}", warfare.describe(closeDriver[0]), closeInfantry[0].getHealth(), rammed[0]); });
        }, () -> closeInfantry[0].getHealth() < 10000 && (crew.shots(closeDriver[0]) > 0 || rammed[0]), 300, true));

        Bot[] rocket = {null}; Entity[] armor = {null}; ItemStack[] rpg = {null}; boolean[] covered = {false}, returned = {false}; Vec3[] shotPos = {null};
        list.add(new Scenario("warfare feedback preloaded RPG peeks from actual cover damages a tank then withdraws", () -> {
            forceArea(11000, -20, 11075, 20); run("bot settings setgoal nearesthostile");
            for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 3; dy++) setBlock(11016, y + dy, dz, Blocks.OBSIDIAN.defaultBlockState());
            rocket[0] = infantry("CoverRocket", 10, 11014.5, y, 0.5); rocket[0].setLook(-90, 0); rocket[0].profileAbilities().put("pathfinding", true);
            rpg[0] = equipGun(rocket[0], loadedGun("rpg", 1)); rocket[0].giveItem(warfareItem("rpg_rocket_standard", 4));
            armor[0] = spawnVehicle("m_1a_2", 11055.5, y, 0.5, 0); spawnHusk(11055.5, y, 0.5, 10000, true).startRiding(armor[0], true);
            track(() -> {
                boolean hide = !rocket[0].hasLineOfSight(armor[0]); covered[0] |= hide && warfare.shots(rocket[0]) == 0;
                if (shotPos[0] == null && warfare.shots(rocket[0]) > 0) shotPos[0] = rocket[0].position();
                returned[0] |= shotPos[0] != null && hide && rocket[0].position().distanceToSqr(shotPos[0]) > 1;
                if (rocket[0].getAliveTicks() % 40 == 0) LOGGER.info("[SelfTest] RPG cover pos={} state={} armor={} covered={} returned={}", rocket[0].position(), warfare.describe(rocket[0]), vehicleHealth(armor[0]), covered[0], returned[0]);
            });
        }, () -> covered[0] && returned[0] && vehicleHealth(armor[0]) < 500 && warfare.shots(rocket[0]) > 0, 420, true));

        Bot[] brave = {null}; Entity[] immune = {null}; Husk[] sword = {null}; boolean[] noRetreat = {true};
        list.add(new Scenario("warfare feedback low health tank holds against an infantry weapon with zero native damage", () -> {
            forceArea(11100, -20, 11165, 20); run("bot settings setgoal nearesthostile");
            brave[0] = vehicleBot("ImmuneDriver", 10, 11112.5, y, 0.5); brave[0].setLook(-90, 0);
            immune[0] = spawnVehicle("m_1a_2", 11115.5, y, 0.5, -90); crew.board(brave[0], immune[0], 0); setVehicleHealth(immune[0], 145);
            sword[0] = spawnHusk(11145.5, y, 0.5, 10000, true); sword[0].setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            track(() -> noRetreat[0] &= !warfare.describe(brave[0]).contains("RETREAT"));
        }, () -> brave[0].getAliveTicks() >= 120 && noRetreat[0] && sword[0].getHealth() < 10000 && crew.shots(brave[0]) > 0, 260, true));

        Bot[] coveredReload = {null}; Entity[] reloadArmor = {null}; ItemStack[] emptyRocket = {null}; boolean[] hiddenReload = {false}, emerged = {false};
        list.add(new Scenario("warfare feedback level seven remembers observed armor through a covered native reload", () -> {
            warfareFlatArena(11600, -20, 11680, 20, y); run("bot settings setgoal nearesthostile");
            for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 3; dy++) setBlock(11616, y + dy, dz, Blocks.OBSIDIAN.defaultBlockState());
            coveredReload[0] = infantry("RememberRocket", 7, 11614.5, y, 4.5); coveredReload[0].setLook(-90, 0); coveredReload[0].profileAbilities().put("pathfinding", true);
            emptyRocket[0] = equipGun(coveredReload[0], loadedGun("rpg", 0)); coveredReload[0].giveItem(warfareItem("rpg_rocket_standard", 4));
            reloadArmor[0] = spawnVehicle("m_1a_2", 11655.5, y, 0.5, 0); spawnHusk(11655.5, y, 0.5, 10000, true).startRiding(reloadArmor[0], true);
            track(() -> {
                if (!hiddenReload[0] && warfare.reloading(emptyRocket[0]) && legacy().getSkills().currentTarget(coveredReload[0]) != null) {
                    // Move only the fixture into its prepared cover; production movement remains unchanged.
                    coveredReload[0].setPos(11614.5, y, 0.5); coveredReload[0].setVelocity(Vec3.ZERO); hiddenReload[0] = true;
                }
                emerged[0] |= hiddenReload[0] && warfare.shots(coveredReload[0]) > 0;
                if (coveredReload[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Covered reload pos={} state={} target={} hidden={} armor={}", coveredReload[0].position(), warfare.describe(coveredReload[0]), legacy().getSkills().currentTarget(coveredReload[0]), hiddenReload[0], vehicleHealth(reloadArmor[0]));
            });
        }, () -> hiddenReload[0] && emerged[0] && vehicleHealth(reloadArmor[0]) < 500, 420, true));
        List<Bot> pair = new ArrayList<>(); Entity[] pairTank = {null}; boolean[] roles = {false}, flankShot = {false};
        float[] flankHealth = {Float.NaN};
        list.add(new Scenario("warfare feedback level seven team exposes a bait and fires from a distinct tank flank", () -> {
            cooperationArena(11700, -35, 11790, 35, y);
            // Previous native explosions persist in the isolated world; restore this flat combat fixture.
            for (int x = 11700; x <= 11790; x++) for (int z = -35; z <= 35; z++) {
                for (int dy = 1; dy <= 3; dy++) {
                    BlockPos floor = new BlockPos(x, y - dy, z);
                    if (!level.getBlockState(floor).isSolid()) level.setBlock(floor,
                            (dy == 1 ? Blocks.GRASS_BLOCK : Blocks.DIRT).defaultBlockState(), 3);
                }
            }
            run("bot settings setgoal nearesthostile");
            for (int i = 0; i < 2; i++) {
                Bot b = infantry("AntiPair" + i, 7, 11710.5, y, -4.5 + i * 9); b.setLook(-90, 0); b.profileAbilities().put("teamwork", true);
                b.giveItem(loadedGun("rpg", 1)); b.giveItem(warfareItem("rpg_rocket_standard", 4)); pair.add(b);
            }
            testTeam("anti_pair", pair.toArray(Bot[]::new)); pairTank[0] = spawnVehicle("m_1a_2", 11750.5, y, 0.5, 90);
            spawnHusk(11750.5, y, 0.5, 10000, true).startRiding(pairTank[0], true);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> fired = event -> {
                if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile && projectile.getOwner() instanceof Bot b
                        && pair.contains(b) && projectile.getClass().getName().startsWith("com.atsuishio.superbwarfare")
                        && warfare.describe(b).contains("antiTank=FLANK")) {
                    double front = pairTank[0].getLookAngle().multiply(1, 0, 1).normalize().dot(b.position().subtract(pairTank[0].position()).multiply(1, 0, 1).normalize());
                    if (!flankShot[0] && front <= 0.4 && Math.abs(b.getZ()) > 8) {
                        flankShot[0] = true; flankHealth[0] = vehicleHealth(pairTank[0]);
                    }
                    LOGGER.info("[SelfTest] Anti flank actual projectile owner={} pos={} front={} direction={} valid={}", b.getName().getString(), b.position(), front, projectile.getDeltaMovement(), flankShot[0]);
                }
            };
            NeoForge.EVENT_BUS.addListener(fired); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(fired));
            track(() -> { roles[0] |= pair.stream().anyMatch(b -> warfare.describe(b).contains("antiTank=BAIT"))
                    && pair.stream().anyMatch(b -> warfare.describe(b).contains("antiTank=FLANK"));
                if (pair.getFirst().getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Anti pair roles={} flank={} tank={} members={}", roles[0], flankShot[0], vehicleHealth(pairTank[0]), pair.stream().map(b -> b.position() + " " + warfare.describe(b)).toList()); });
        }, () -> roles[0] && flankShot[0] && vehicleHealth(pairTank[0]) < flankHealth[0] && pair.stream().allMatch(Bot::isAlive)
                && pair.get(0).position().distanceTo(pair.get(1).position()) > 8, 480, true));
        Bot[] medic = {null}; ItemStack[] kit = {null};
        list.add(new Scenario("warfare feedback medical kit uses native duration healing and consumes the owned item", () -> {
            forceArea(11270, -5, 11290, 5); run("bot settings setgoal none");
            medic[0] = infantry("NativeMedic", 8, 11280.5, y, 0.5); medic[0].profileAbilities().put("recovery", true);
            medic[0].giveItem(warfareItem("medical_kit", 2)); medic[0].setHealth(6);
        }, () -> medic[0].getHealth() > 6 && medic[0].countItem(warfareItem("medical_kit", 1).getItem()) == 1
                && medic[0].hasEffect(MobEffects.REGENERATION), 140, true));
        Bot[] ramDriver = {null}; Entity[] ramTank = {null}; Husk[] victim = {null}; boolean[] collisionHit = {false};
        list.add(new Scenario("warfare feedback native driving alone causes an actual vehicle strike", () -> {
            forceArea(11500, -15, 11550, 15); run("bot settings setgoal nearesthostile");
            ramDriver[0] = vehicleBot("RamOnly", 10, 11512.5, y, 0.5); ramDriver[0].profileAbilities().put("vehicleweapons", false);
            ramTank[0] = spawnVehicle("m_1a_2", 11515.5, y, 0.5, -90); crew.board(ramDriver[0], ramTank[0], 0);
            victim[0] = spawnHusk(11521.5, y, 0.5, 10000, true);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> hit = event -> {
                if (event.getEntity() == victim[0] && event.getNewDamage() > 0 && event.getSource().getMsgId().contains("vehicle")) collisionHit[0] = true;
            };
            NeoForge.EVENT_BUS.addListener(hit); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(hit));
            track(() -> { if (ramDriver[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Ram only pos={} victim={} state={} hit={}", ramTank[0].position(), victim[0].getHealth(), warfare.describe(ramDriver[0]), collisionHit[0]); });
        }, () -> collisionHit[0] && ramTank[0].getX() > 11518 && crew.shots(ramDriver[0]) == 0, 300, true));
        boolean[] kits = {false};
        list.add(new Scenario("warfare feedback independent kits scale native armor weapons ammo and real enchantments", () -> {
            run("bot settings setgoal none"); Bot b = infantry("WarfareKits", 7, 11210.5, y, 0.5);
            int previous = -1; boolean ok = true;
            java.util.Set<String> common = java.util.Set.of("minecraft:totem_of_undying", "minecraft:elytra", "minecraft:firework_rocket", "minecraft:ender_pearl");
            for (int i = 1; i <= 10; i++) {
                net.nuggetmc.tplus.bot.DefaultEquipment.apply(b, i, "warfare");
                java.util.List<ItemStack> stacks = new ArrayList<>(b.getInventory().items);
                for (EquipmentSlot slot : EquipmentSlot.values()) stacks.add(b.getItemBySlot(slot));
                int strength = stacks.stream().filter(stack -> !stack.isEmpty()).mapToInt(stack -> stack.getEnchantments().entrySet().stream().mapToInt(e -> e.getIntValue()).sum()).sum();
                ok &= strength > previous && (i != 1 || strength == 0); previous = strength;
                ok &= stacks.stream().filter(stack -> !stack.isEmpty()).allMatch(stack -> {
                    String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); return id.startsWith("superbwarfare:") || common.contains(id);
                });
                ok &= net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(b.getItemBySlot(EquipmentSlot.HEAD).getItem()).getNamespace().equals("superbwarfare");
                if (i >= 7) ok &= b.countItem(warfareItem("rpg", 1).getItem()) == 1;
            }
            run("bot settings gear warfare"); var saved = legacy().getSkillSettings().save();
            var reloaded = new net.nuggetmc.tplus.api.agent.legacyagent.skill.SkillSettings(); reloaded.load(saved);
            kits[0] = ok && reloaded.defaultGear.equals("warfare") && suggestions("bot settings gear ").containsAll(List.of("vanilla", "warfare"));
            scenarioHooks.add(() -> legacy().getSkillSettings().defaultGear = "vanilla");
        }, () -> kits[0], 20, true));
        return list;
    }


    private List<Scenario> warfareCooldownScenarios(int y) {
        List<Scenario> list = new ArrayList<>(); var warfare = legacy().getSkills().warfare();
        Bot[] parallel = {null}; ItemStack[] twins = new ItemStack[2]; long[] ends = {-1, -1}; boolean[] exact = {true}, firstOnly = {false};
        list.add(new Scenario("warfare cooldown identical owned guns keep separate runtime deadlines and finite reserve debit", () -> {
            forceArea(11300, -8, 11345, 8); run("bot settings setgoal none");
            parallel[0] = infantry("ParallelReload", 9, 11310.5, y, 0.5);
            for (int i = 0; i < 2; i++) { twins[i] = equipGun(parallel[0], loadedGun("ak_47", 0)); overrideGun(twins[i], "{\"EmptyReloadTime\":" + (i == 0 ? 28 : 66) + "}"); }
            parallel[0].giveItem(warfareItem("rifle_ammo", 64));
            track(() -> {
                long now = server.getTickCount();
                for (int i = 0; i < 2; i++) {
                    long left = warfare.reloadRemaining(parallel[0], twins[i]);
                    if (ends[i] < 0 && left > 0) ends[i] = now + left;
                    if (ends[i] > now) exact[0] &= warfare.ammo(twins[i]) == 0 && now + left == ends[i];
                }
                if (ends[0] > 0 && now >= ends[0] && now < ends[1]) firstOnly[0] |= warfare.ammo(twins[0]) == 30
                        && warfare.ammo(twins[1]) == 0 && parallel[0].countItem(warfareItem("rifle_ammo", 1).getItem()) == 34;
            });
        }, () -> ends[0] > 0 && ends[1] - ends[0] == 38 && firstOnly[0] && exact[0] && warfare.ammo(twins[0]) == 30
                && warfare.ammo(twins[1]) == 30 && parallel[0].countItem(warfareItem("rifle_ammo", 1).getItem()) == 4 && !warfare.holding(parallel[0]), 120, true));

        Bot[] mobile = {null}; ItemStack[] partial = {null}; long[] deadline = {-1}; boolean[] continuous = {true}, eaten = {false}, thrown = {false}, flying = {false};
        long[] jumpAt = {-1};
        list.add(new Scenario("warfare cooldown normal reload survives real food pearl and elytra with limited backup", () -> {
            forceArea(11360, -30, 11450, 30); run("bot settings setgoal none");
            mobile[0] = infantry("MobileCooldown", 9, 11375.5, y, 0.5); mobile[0].setLook(-90, 0); mobile[0].profileAbilities().put("recovery", true); mobile[0].profileAbilities().put("elytra", true);
            partial[0] = equipGun(mobile[0], loadedGun("ak_47", 6)); overrideGun(partial[0], "{\"NormalReloadTime\":140,\"EmptyReloadTime\":220}");
            mobile[0].giveItem(warfareItem("rifle_ammo", 7)); mobile[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 1));
            mobile[0].giveItem(new ItemStack(Items.ENDER_PEARL, 1)); mobile[0].giveItem(new ItemStack(Items.ELYTRA)); mobile[0].setHealth(12);
            track(() -> {
                long now = server.getTickCount(), left = warfare.reloadRemaining(mobile[0], partial[0]);
                if (deadline[0] < 0 && left > 0) { deadline[0] = now + left; continuous[0] &= left >= 139 && left <= 140; mobile[0].beginRecoveryItem(false); }
                if (deadline[0] > now) continuous[0] &= now + left == deadline[0] && warfare.ammo(partial[0]) == 6
                        && mobile[0].countItem(warfareItem("rifle_ammo", 1).getItem()) == 7;
                eaten[0] |= mobile[0].hasEffect(MobEffects.REGENERATION) && mobile[0].countItem(Items.GOLDEN_APPLE) == 0;
                if (eaten[0] && !thrown[0]) thrown[0] = mobile[0].throwEnderPearl(-90, -25);
                if (thrown[0] && left < 55 && left > 45 && jumpAt[0] < 0 && mobile[0].isBotOnGround()) { mobile[0].jump(new Vec3(0.1, 0.42, 0)); jumpAt[0] = now; }
                if (jumpAt[0] >= 0 && now > jumpAt[0] && !flying[0]) flying[0] = mobile[0].startGliding();
                if (mobile[0].getAliveTicks() % 40 == 0) LOGGER.info("[SelfTest] Mobile cooldown left={} continuous={} eaten={} pearl={} flight={} ammo={} reserve={} jump={}", left, continuous[0], eaten[0], thrown[0], flying[0], warfare.ammo(partial[0]), mobile[0].countItem(warfareItem("rifle_ammo", 1).getItem()), jumpAt[0]);
            });
        }, () -> deadline[0] > 0 && server.getTickCount() >= deadline[0] && continuous[0] && eaten[0] && thrown[0] && flying[0]
                && warfare.ammo(partial[0]) == 13 && mobile[0].countItem(warfareItem("rifle_ammo", 1).getItem()) == 0
                && mobile[0].countItem(Items.ENDER_PEARL) == 0, 220, true));

        Bot[] switching = {null}; ItemStack[] empty = {null}, usable = {null}; boolean[] firedDuring = {false}, filledStored = {false}; java.util.Set<java.util.UUID>[] actualHits = new java.util.Set[]{null};
        list.add(new Scenario("warfare cooldown stored rifle reload permits another real gun to inflict native damage", () -> {
            forceArea(11470, -10, 11520, 10); run("bot settings setgoal nearesthostile");
            switching[0] = infantry("SwitchCooldown", 9, 11475.5, y, 0.5); switching[0].setLook(-90, 0);
            empty[0] = equipGun(switching[0], loadedGun("ak_47", 0)); overrideGun(empty[0], "{\"EmptyReloadTime\":120}");
            usable[0] = equipGun(switching[0], loadedGun("mp_5", 30)); switching[0].giveItem(warfareItem("rifle_ammo", 30));
            actualHits[0] = capturePositiveHits(spawnHusk(11499.5, y, 0.5, 10000, false));
            track(() -> {
                firedDuring[0] |= warfare.reloadRemaining(switching[0], empty[0]) > 0 && switching[0].getMainHandItem() == usable[0]
                        && warfare.shots(switching[0]) > 0 && warfare.ammo(empty[0]) == 0 && actualHits[0].contains(switching[0].getUUID());
                filledStored[0] |= warfare.ammo(empty[0]) > 0;
                if (switching[0].getAliveTicks() % 40 == 0) LOGGER.info("[SelfTest] Switch cooldown firedDuring={} filled={} state={} hits={}", firedDuring[0], filledStored[0], warfare.describe(switching[0]), actualHits[0]);
            });
        }, () -> firedDuring[0] && filledStored[0] && switching[0].countItem(warfareItem("rifle_ammo", 1).getItem()) == 0, 190, true));

        Bot[] aggressive = {null}; Husk[] pressuredEnemy = {null}; boolean[] pressure = {false}; long[] before = {0};
        java.util.Set<java.util.UUID>[] aggressiveHits = new java.util.Set[]{null};
        list.add(new Scenario("warfare cooldown ten guns only with air default keeps attacking through mild real damage", () -> {
            forceArea(11540, -15, 11610, 15); run("bot settings setgoal nearesthostile");
            aggressive[0] = infantry("GunOnlyTen", 10, 11545.5, y, 0.5); aggressive[0].setDefaultItem(ItemStack.EMPTY); aggressive[0].setLook(-90, 0);
            for (String ability : List.of("recovery", "deception", "pearls", "elytra", "pathfinding")) aggressive[0].profileAbilities().put(ability, true);
            aggressive[0].giveItem(loadedGun("ak_47", 30)); aggressive[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 4)); aggressive[0].giveItem(new ItemStack(Items.ENDER_PEARL, 8));
            aggressive[0].giveItem(new ItemStack(Items.ELYTRA)); aggressive[0].giveItem(new ItemStack(Items.FIREWORK_ROCKET, 32)); aggressive[0].giveItem(new ItemStack(Items.TOTEM_OF_UNDYING, 2));
            pressuredEnemy[0] = spawnHusk(11575.5, y, 0.5, 10000, false); aggressiveHits[0] = capturePositiveHits(pressuredEnemy[0]);
            track(() -> {
                int age = (int) aggressive[0].getAliveTicks();
                if (age >= 70 && age < 110 && age % 12 == 0) { pressure[0] |= aggressive[0].hurt(level.damageSources().mobAttack(pressuredEnemy[0]), 1); before[0] = warfare.shots(aggressive[0]); }
            });
        }, () -> aggressive[0].getAliveTicks() >= 150 && pressure[0] && warfare.shots(aggressive[0]) >= before[0] + 6 && aggressiveHits[0].contains(aggressive[0].getUUID())
                && aggressive[0].countItem(Items.GOLDEN_APPLE) == 4 && aggressive[0].countItem(Items.ENDER_PEARL) == 8 && !aggressive[0].isGliding(), 220, true));
        return list;
    }


    private List<Scenario> warfareFlightFeedbackScenarios(int y) {
        List<Scenario> list = new ArrayList<>(); var warfare = legacy().getSkills().warfare(); var crew = warfare.crew();
        for (String id : List.of("ah_6", "m_1a_2", "bmp_2")) {
            List<Bot> tails = new ArrayList<>(); Entity[] body = {null}; int[] reservations = {0};
            int base = id.equals("ah_6") ? 11630 : id.equals("m_1a_2") ? 11700 : 11770;
            list.add(new Scenario("warfare flight feedback " + id + " tail approach mounts distinct native seats from their reachable sides", () -> {
                forceArea(base - 15, -25, base + 35, 25); run("bot settings setgoal none");
                body[0] = spawnVehicle(id, base + 10.5, y, 0.5, 0);
                for (int i = 0; i < 4; i++) tails.add(vehicleBot("Tail" + id + i, 10, base + 10.2 + i * 0.2, y, -9.5 - i * 2));
                testTeam("tails_" + id, tails.toArray(Bot[]::new)); reservations[0] = crew.boardCrew(tails.getFirst(), body[0]);
                track(() -> { if (tails.getFirst().getAliveTicks() % 80 == 0) LOGGER.info("[SelfTest] Tail boarding {} count={} seats={} bots={}", id, reservations[0], body[0].getPassengers().size(), tails.stream().map(b -> b.position() + " " + warfare.describe(b)).toList()); });
            }, () -> reservations[0] == 4 && tails.stream().allMatch(b -> b.getVehicle() == body[0])
                    && tails.stream().map(b -> warfare.vessel(body[0]).seatIndex(b)).distinct().count() == 4, 320, true));
        }
        Bot[] pilot = {null}; Entity[] attackHeli = {null}; Husk[] victim = {null}; boolean[] airborne = {false}, pullout = {false};
        java.util.Set<java.util.UUID>[] hits = new java.util.Set[]{null}, owners = new java.util.Set[]{null};
        list.add(new Scenario("warfare flight feedback AH6 pilot aligns native fixed cannon fires damages and pulls out", () -> {
            forceArea(11830, -100, 12010, 100); run("bot settings setgoal nearesthostile");
            pilot[0] = vehicleBot("FixedNosePilot", 10, 11850.5, y, 2.5); attackHeli[0] = spawnVehicle("ah_6", 11852.5, y, 0.5, -90);
            crew.board(pilot[0], attackHeli[0], 0); victim[0] = spawnHusk(11915.5, y, 0.5, 10000, false);
            hits[0] = capturePositiveHits(victim[0]); owners[0] = captureNativeOwners(List.of(pilot[0]));
            track(() -> {
                airborne[0] |= attackHeli[0].getY() > y + 6;
                pullout[0] |= airborne[0] && crew.shots(pilot[0]) > 0 && warfare.describe(pilot[0]).contains("vehiclePilot=EXTEND");
                if (pilot[0].getAliveTicks() % 60 == 0) LOGGER.info("[SelfTest] Fixed nose pos={} pitch={} yaw={} state={} hits={} pullout={}", attackHeli[0].position(), attackHeli[0].getXRot(), attackHeli[0].getYRot(), warfare.describe(pilot[0]), hits[0], pullout[0]);
            });
        }, () -> airborne[0] && pullout[0] && crew.shots(pilot[0]) >= 3 && owners[0].contains(pilot[0].getUUID()) && hits[0].contains(pilot[0].getUUID()), 650, true));

        Bot[] fast = {null}; Entity[] courier = {null}; double[] speed = {0};
        list.add(new Scenario("warfare flight feedback helicopter transit turns hover off and moves at native forward speed", () -> {
            forceArea(12030, -60, 12200, 60); run("bot settings setgoal none");
            fast[0] = vehicleBot("NativeFastTransit", 9, 12045.5, y, 2.5); courier[0] = spawnVehicle("ah_6", 12047.5, y, 0.5, -90);
            crew.board(fast[0], courier[0], 0); crew.go(fast[0], new Vec3(12157.5, y, 0.5));
            track(() -> { if (warfare.describe(fast[0]).contains("vehiclePilot=TRANSIT")) speed[0] = Math.max(speed[0], courier[0].getDeltaMovement().horizontalDistance()); });
        }, () -> courier[0].getX() > 12107.5 && speed[0] > 0.18 && !warfare.vessel(courier[0]).hovering() && fast[0].isPassenger(), 420, true));

        Bot[] search = {null}; Entity[] searchingTank = {null}; Husk[] lost = {null}; boolean[] erased = {false}, searching = {false}; double[] farthest = {0}, returned = {0};
        list.add(new Scenario("warfare flight feedback vehicle returns to an observed enemy point after target loss", () -> {
            forceArea(12220, -30, 12365, 30); run("bot settings setgoal nearesthostile");
            search[0] = vehicleBot("VehicleSearch", 10, 12235.5, y, 2.5); search[0].profileAbilities().put("vehicleweapons", false);
            searchingTank[0] = spawnVehicle("m_1a_2", 12237.5, y, 0.5, -90); crew.board(search[0], searchingTank[0], 0);
            lost[0] = spawnHusk(12257.5, y, 0.5, 10000, true); crew.go(search[0], new Vec3(12317.5, y, 0.5));
            track(() -> {
                if (!erased[0] && searchingTank[0].getX() > 12287) { erased[0] = true; lost[0].discard(); }
                if (erased[0]) {
                    farthest[0] = Math.max(farthest[0], searchingTank[0].getX());
                    if (warfare.describe(search[0]).contains("vehiclePilot=SEARCH_LAST_SEEN")) { searching[0] = true; returned[0] = Math.max(returned[0], farthest[0] - searchingTank[0].getX()); }
                }
            });
        }, () -> erased[0] && searching[0] && returned[0] > 8 && search[0].isPassenger(), 500, true));

        Bot[] boundedPilot = {null}; Entity[] boundedHeli = {null}; boolean[] stayed = {true}, flew = {false};
        list.add(new Scenario("warfare flight feedback helicopter attack and extension stay inside actual world border", () -> {
            forceArea(12380, -85, 12560, 85); run("bot settings setgoal nearesthostile"); testBorder(12470, 0, 160);
            boundedPilot[0] = vehicleBot("HeliBorder", 10, 12425.5, y, 2.5); boundedHeli[0] = spawnVehicle("ah_6", 12427.5, y, 0.5, -90);
            crew.board(boundedPilot[0], boundedHeli[0], 0); spawnHusk(12520.5, y, 0.5, 10000, true);
            track(() -> { flew[0] |= boundedHeli[0].getY() > y + 6; stayed[0] &= level.getWorldBorder().isWithinBounds(boundedHeli[0].getBoundingBox()) && level.getWorldBorder().isWithinBounds(boundedPilot[0].getBoundingBox()); });
        }, () -> boundedPilot[0].getAliveTicks() >= 420 && flew[0] && stayed[0] && boundedPilot[0].isAlive(), 440, true));

        List<Bot> twoCrews = new ArrayList<>(); Entity[] twoHelis = new Entity[2]; int[] assigned = {0, 0};
        list.add(new Scenario("warfare flight feedback filling a second vehicle preserves the first crew reservations", () -> {
            warfareFlatArena(12590, -25, 12665, 25, y); run("bot settings setgoal none");
            twoHelis[0] = spawnVehicle("ah_6", 12610.5, y, 0.5, 0); twoHelis[1] = spawnVehicle("ah_6", 12635.5, y, 0.5, 0);
            for (int i = 0; i < 6; i++) twoCrews.add(vehicleBot("MultiCrew" + i, 10, 12605.5 + i, y, -7.5));
            testTeam("multi_crew", twoCrews.toArray(Bot[]::new)); assigned[0] = crew.boardCrew(twoCrews.getFirst(), twoHelis[0]); assigned[1] = crew.boardCrew(twoCrews.get(4), twoHelis[1]);
        }, () -> assigned[0] == 4 && assigned[1] == 2 && twoCrews.subList(0, 4).stream().allMatch(b -> b.getVehicle() == twoHelis[0])
                && twoCrews.subList(4, 6).stream().allMatch(b -> b.getVehicle() == twoHelis[1]), 350, true));

        Bot[] cautious = {null}; Entity[] evasive = {null}; boolean[] flanked = {false}, locked = {false}, avoided = {false}; int[] flares = {0};
        list.add(new Scenario("warfare flight feedback native anti air approach and locked missile release real finite decoys", () -> {
            forceArea(12690, -100, 12900, 100); run("bot settings setgoal nearesthostile");
            cautious[0] = vehicleBot("CautiousPilot", 10, 12715.5, y, 2.5); evasive[0] = spawnVehicle("ah_6", 12717.5, y, 0.5, -90); crew.board(cautious[0], evasive[0], 0);
            try { evasive[0].getClass().getMethod("setItem", int.class, ItemStack.class).invoke(evasive[0], 0, warfareItem("flying_flare_ammo", 4)); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            Husk aa = spawnHusk(12817.5, y, 0.5, 10000, true); aa.setItemSlot(EquipmentSlot.MAINHAND, loadedGun("igla_9k38", 1));
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> {
                if (event.getEntity().getClass().getName().endsWith("FlareDecoyEntity") && event.getEntity().position().distanceTo(evasive[0].position()) < 10) flares[0]++;
            };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            track(() -> {
                String state = warfare.describe(cautious[0]);
                flanked[0] |= state.contains("vehiclePilot=FLANK_APPROACH") && Math.abs(evasive[0].getZ() - 0.5) > 12;
                if (!locked[0] && warfare.vessel(evasive[0]).decoys() == 4 && evasive[0].getY() > y + 6) {
                    Entity missile = Objects.requireNonNull(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("superbwarfare", "javelin_missile")).create(level));
                    missile.setPos(evasive[0].position().add(35, 4, 0));
                    try { missile.getClass().getMethod("setTargetUUID", String.class).invoke(missile, evasive[0].getUUID().toString()); }
                    catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    if (missile instanceof net.minecraft.world.entity.projectile.Projectile projectile) projectile.setOwner(aa);
                    level.addFreshEntity(missile); spawned.add(missile); locked[0] = true;
                }
                avoided[0] |= locked[0] && state.contains("vehiclePilot=EVADE");
                if (cautious[0].getAliveTicks() % 100 == 0) LOGGER.info("[SelfTest] AA decoy pos={} state={} flank={} locked={} evade={} flares={} reserve={}", evasive[0].position(), state, flanked[0], locked[0], avoided[0], flares[0], warfare.vessel(evasive[0]).decoyItems());
            });
        }, () -> flanked[0] && locked[0] && avoided[0] && flares[0] >= 2 && flares[0] == 2 * (4 - warfare.vessel(evasive[0]).decoys())
                && warfare.vessel(evasive[0]).decoyItems() == 0 && cautious[0].isPassenger(), 700, true));

        Bot[] orbitPilot = {null}, leftBench = {null}, rightBench = {null}; Entity[] orbitHeli = {null}; boolean[] orbiting = {false};
        java.util.Set<java.util.UUID>[] benchHits = new java.util.Set[]{null};
        list.add(new Scenario("warfare flight feedback alternating helicopter passes give both real side rifles a firing window", () -> {
            forceArea(12920, -120, 13130, 120); run("bot settings setgoal nearesthostile");
            orbitPilot[0] = vehicleBot("OrbitPilot", 10, 12945.5, y, 2.5); orbitPilot[0].profileAbilities().put("vehicleweapons", false); leftBench[0] = vehicleBot("LeftBench", 10, 12945.5, y, -2.5); rightBench[0] = vehicleBot("RightBench", 10, 12945.5, y, 4.5);
            for (Bot b : List.of(leftBench[0], rightBench[0])) { b.profileAbilities().put("vehicleweapons", false); b.giveItem(loadedGun("ak_47", 30)); }
            testTeam("orbit_crew", orbitPilot[0], leftBench[0], rightBench[0]); orbitHeli[0] = spawnVehicle("ah_6", 12947.5, y, 0.5, -90);
            crew.board(orbitPilot[0], orbitHeli[0], 0); crew.board(leftBench[0], orbitHeli[0], 2); crew.board(rightBench[0], orbitHeli[0], 3);
            benchHits[0] = capturePositiveHits(spawnHusk(13015.5, y, 0.5, 10000, false));
            track(() -> {
                orbiting[0] |= warfare.describe(orbitPilot[0]).contains("vehiclePilot=GUNNER_ORBIT");
                if (orbitPilot[0].getAliveTicks() % 100 == 0) LOGGER.info("[SelfTest] Bench orbit state={} leftShots={} rightShots={} hits={} orbit={}", warfare.describe(orbitPilot[0]), warfare.shots(leftBench[0]), warfare.shots(rightBench[0]), benchHits[0], orbiting[0]);
            });
        }, () -> orbiting[0] && orbitHeli[0].getY() > y + 6 && benchHits[0].contains(leftBench[0].getUUID()) && benchHits[0].contains(rightBench[0].getUUID())
                && warfare.vessel(orbitHeli[0]).seatIndex(leftBench[0]) == 2 && warfare.vessel(orbitHeli[0]).seatIndex(rightBench[0]) == 3, 1100, true));

        Bot[] roofDriver = {null}; Entity[] roofTank = {null}; Husk[] roofEnemy = {null}; boolean[] separated = {false}; double[] clearance = {0};
        list.add(new Scenario("warfare flight feedback tank repositions from a roof enemy and deals real native damage", () -> {
            forceArea(13150, -35, 13230, 35); run("bot settings setgoal nearesthostile");
            roofDriver[0] = vehicleBot("RoofSeparation", 10, 13170.5, y, 2.5); roofTank[0] = spawnVehicle("m_1a_2", 13172.5, y, 0.5, -90); crew.board(roofDriver[0], roofTank[0], 0);
            roofEnemy[0] = spawnHusk(13172.5, y + 3, 0.5, 10000, false);
            track(() -> { separated[0] |= warfare.describe(roofDriver[0]).contains("vehiclePilot=CLOSE_SEPARATION"); clearance[0] = Math.max(clearance[0], roofTank[0].position().subtract(roofEnemy[0].position()).horizontalDistance()); });
        }, () -> separated[0] && clearance[0] > 8 && roofEnemy[0].getHealth() < roofEnemy[0].getMaxHealth() && crew.shots(roofDriver[0]) > 0, 350, true));
        return list;
    }

    private void testBorder(double x, double z, double size) {
        var border = level.getWorldBorder(); double oldX = border.getCenterX(), oldZ = border.getCenterZ(), oldSize = border.getSize();
        scenarioHooks.add(() -> { border.setSize(oldSize); border.setCenter(oldX, oldZ); });
        border.setSize(size); border.setCenter(x, z);
    }
    private void setVehicleHealth(Entity vehicle, float health) {
        try { vehicle.getClass().getMethod("setHealth", float.class).invoke(vehicle, health); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private float vehicleHealth(Entity entity) {
        try { return ((Number) entity.getClass().getMethod("getHealth").invoke(entity)).floatValue(); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private PlayerTeam testTeam(String name, Bot... bots) {
        PlayerTeam team = server.getScoreboard().addPlayerTeam(name);
        testTeams.add(team);
        for (Bot bot : bots) server.getScoreboard().addPlayerToTeam(bot.getScoreboardName(), team);
        return team;
    }

    private List<Scenario> hardnessScenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        boolean[] vanillaKit = {false};
        list.add(new Scenario("equipment vanilla levels progressively enchant and level ten has a legal maximum OP kit", () -> {
            run("bot settings setgoal none"); Bot b = spawnBot("VanillaKits", 1090.5, y, 0.5);
            int previous = -1; boolean valid = true;
            for (int i = 1; i <= 10; i++) {
                net.nuggetmc.tplus.bot.DefaultEquipment.apply(b, i);
                java.util.List<ItemStack> stacks = new ArrayList<>(b.getInventory().items);
                for (EquipmentSlot slot : EquipmentSlot.values()) stacks.add(b.getItemBySlot(slot));
                int value = stacks.stream().filter(stack -> !stack.isEmpty()).mapToInt(stack -> stack.getEnchantments().entrySet().stream().mapToInt(e -> e.getIntValue()).sum()).sum();
                valid &= value > previous && (i != 1 || value == 0); previous = value;
            }
            var registry = b.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            ItemStack bow = b.findItem(stack -> stack.is(Items.BOW));
            vanillaKit[0] = valid && b.getWeapon().getEnchantments().getLevel(registry.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS)) == 5
                    && b.getItemBySlot(EquipmentSlot.CHEST).getEnchantments().getLevel(registry.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.PROTECTION)) == 4
                    && bow.getEnchantments().getLevel(registry.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.INFINITY)) == 1
                    && bow.getEnchantments().getLevel(registry.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING)) == 0
                    && b.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
        }, () -> vanillaKit[0], 20, true));
        Bot[] edge = {null}; Vec3[] birth = {null}; boolean[] edgeSafe = {true}; double[] walk = {0};
        list.add(new Scenario("equipment border infantry retreats heals and navigates with an inward margin", () -> {
            forceArea(11300, -65, 11450, 65); testBorder(11375, 0, 150); run("bot settings setgoal nearesthostile");
            edge[0] = infantry("BorderInfantry", 8, 11437.5, y, 0.5); edge[0].profileAbilities().put("recovery", true);
            edge[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2)); edge[0].setHealth(6); edge[0].getFoodData().setFoodLevel(0); birth[0] = edge[0].position();
            spawnHusk(11431.5, y + 6, 0.5, 1000, true).setNoGravity(true);
            track(() -> {
                edgeSafe[0] &= net.nuggetmc.tplus.api.agent.legacyagent.skill.MovementBounds.contains(edge[0], edge[0].position(), 2);
                walk[0] = Math.max(walk[0], horizontalDistance(birth[0], edge[0].position()));
            });
        }, () -> edge[0].getAliveTicks() >= 120 && edgeSafe[0] && walk[0] > 2 && edge[0].getHealth() > 6
                && edge[0].countItem(Items.GOLDEN_APPLE) < 2, 300, true));
        boolean[] commands = {false};
        list.add(new Scenario("hardness command validation and per-bot override", () -> {
            run("bot settings hardness 0");
            run("bot settings hardness 11");
            boolean unchanged = legacy().getSkillSettings().hardness == 7;
            Bot one = spawnBot("EasyOverride", 1100.5, y, 0.5);
            Bot two = spawnBot("DefaultSeven", 1110.5, y, 0.5);
            run("bot settings hardness 1 EasyOverride");
            commands[0] = unchanged && legacy().getSkills().hardness(one).level() == 1
                    && legacy().getSkills().hardness(two).level() == 7
                    && suggestions("bot settings hardness ").containsAll(List.of("1", "7", "10"));
        }, () -> commands[0], 20, true));

        Bot[] easy = {null}; Husk[] easyTarget = {null};
        list.add(new Scenario("easy bot waits before reacting to a new opponent", () -> {
            run("bot settings hardness 1"); run("bot settings setgoal nearesthostile");
            easy[0] = spawnBot("SlowReaction", 1140.5, y, 0.5);
            easy[0].setDefaultItem(new ItemStack(Items.IRON_SWORD));
            easyTarget[0] = spawnHusk(1142.5, y, 0.5, 200, false);
        }, () -> easy[0].getAliveTicks() >= 15 && easy[0].getAliveTicks() < 30 && easyTarget[0].getHealth() == 200, 20, true));

        Bot[] casual = {null}, baseline = {null}; Husk[] casualTarget = {null}, baselineTarget = {null}; int[] hits = {0, 0};
        list.add(new Scenario("easy bot attacks less frequently than baseline seven", () -> {
            run("bot settings setgoal nearesthostile");
            casual[0] = spawnBot("CasualCadence", 1180.5, y, 0.5); casual[0].setHardnessOverride(1);
            baseline[0] = spawnBot("LegacyCadence", 1210.5, y, 0.5);
            casual[0].setDefaultItem(new ItemStack(Items.IRON_SWORD)); baseline[0].setDefaultItem(new ItemStack(Items.IRON_SWORD));
            casualTarget[0] = spawnHusk(1182.5, y, 0.5, 1000, false);
            baselineTarget[0] = spawnHusk(1212.5, y, 0.5, 1000, false);
            float[] previous = {1000, 1000};
            track(() -> {
                Husk[] targets = {casualTarget[0], baselineTarget[0]};
                for (int i = 0; i < 2; i++) { if (targets[i].getHealth() < previous[i]) hits[i]++; previous[i] = targets[i].getHealth(); }
            });
        }, () -> casual[0].getAliveTicks() >= 120 && hits[0] > 0 && hits[1] > hits[0] + 2, 200, true));

        Bot[] seven = {null};
        list.add(new Scenario("seven preserves legacy recovery and does not consume food", () -> {
            run("bot settings setgoal nearesthostile"); seven[0] = spawnBot("SevenBaseline", 1250.5, y, 0.5);
            seven[0].setHealth(5); seven[0].giveItem(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE));
            spawnHusk(1256.5, y, 0.5, 1000, true);
        }, () -> seven[0].getAliveTicks() >= 60 && seven[0].countItem(Items.ENCHANTED_GOLDEN_APPLE) == 1
                && legacy().getSkills().memory(seven[0]).getTactic() == BotMemory.Tactic.FIGHT, 100, true));

        Bot[] eater = {null};
        list.add(new Scenario("high difficulty consumes one real golden apple with vanilla effects", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal nearesthostile");
            eater[0] = spawnBot("RecoverApple", 1290.5, y, 0.5); eater[0].setHealth(5);
            eater[0].getFoodData().setSaturation(0); eater[0].getFoodData().setFoodLevel(10);
            eater[0].giveItem(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2)); spawnHusk(1298.5, y, 0.5, 1000, true);
        }, () -> eater[0].countItem(Items.ENCHANTED_GOLDEN_APPLE) == 1 && eater[0].hasEffect(MobEffects.REGENERATION)
                && eater[0].hasEffect(MobEffects.ABSORPTION) && eater[0].getHealth() > 5, 240, true));

        Bot[] food = {null};
        list.add(new Scenario("high difficulty eats food and recovers through vanilla hunger", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal none");
            food[0] = spawnBot("RecoverFood", 1330.5, y, 0.5); food[0].setHealth(6);
            food[0].getFoodData().setFoodLevel(10); food[0].getFoodData().setSaturation(0);
            food[0].giveItem(new ItemStack(Items.COOKED_BEEF, 3));
        }, () -> food[0].countItem(Items.COOKED_BEEF) < 3 && food[0].getFoodData().getFoodLevel() >= 18 && food[0].getHealth() > 6, 240, true));

        Bot[] drinker = {null};
        list.add(new Scenario("recovery potion heals through vanilla item use and leaves one glass bottle", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal none");
            drinker[0] = spawnBot("RecoverPotion", 2170.5, y, 0.5); drinker[0].setHealth(5);
            drinker[0].getFoodData().setFoodLevel(10); drinker[0].getFoodData().setSaturation(0);
            ItemStack potion = new ItemStack(Items.POTION);
            potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                    new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.HEALING));
            drinker[0].giveItem(potion);
        }, () -> drinker[0].getHealth() >= 9 && drinker[0].countItem(Items.POTION) == 0
                && drinker[0].countItem(Items.GLASS_BOTTLE) == 1, 120, true));

        Bot[] chorus = {null}; Vec3[] beforeChorus = {null};
        list.add(new Scenario("chorus fruit consumes a real item and uses vanilla random teleport", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal none"); forceArea(2210, -15, 2240, 15);
            chorus[0] = spawnBot("ChorusEscape", 2220.5, y, 0.5); chorus[0].giveItem(new ItemStack(Items.CHORUS_FRUIT));
            beforeChorus[0] = chorus[0].position();
            chorus[0].beginRecoveryItem(true);
        }, () -> !chorus[0].isConsuming() && chorus[0].countItem(Items.CHORUS_FRUIT) == 0
                && horizontalDistance(chorus[0].position(), beforeChorus[0]) > 0.5, 100, true));

        Bot[] cancel = {null};
        list.add(new Scenario("difficulty change cancels eating without losing or duplicating the item", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none");
            cancel[0] = spawnBot("CancelEating", 1370.5, y, 0.5); cancel[0].setHealth(5);
            cancel[0].getFoodData().setFoodLevel(10); cancel[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2));
            scheduler.runTaskLater(() -> run("bot settings hardness 7 CancelEating"), 15);
        }, () -> cancel[0].getAliveTicks() >= 20 && !cancel[0].isConsuming() && cancel[0].countItem(Items.GOLDEN_APPLE) == 2
                && !cancel[0].hasEffect(MobEffects.REGENERATION), 40, true));

        Bot[] supplied = {null};
        list.add(new Scenario("giving supplies during eating preserves both the real food and supplies", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal none");
            supplied[0] = spawnBot("SupplyEating", 2270.5, y, 0.5); supplied[0].setHealth(5);
            supplied[0].getFoodData().setFoodLevel(10); supplied[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2));
            scheduler.runTaskLater(() -> supplied[0].giveItem(new ItemStack(Items.ENDER_PEARL, 7)), 15);
        }, () -> supplied[0].hasEffect(MobEffects.REGENERATION) && supplied[0].countItem(Items.GOLDEN_APPLE) == 1
                && supplied[0].countItem(Items.ENDER_PEARL) == 7, 100, true));

        Bot[] cleared = {null};
        list.add(new Scenario("clearing inventory during eating cannot restore a removed consumable", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none");
            cleared[0] = spawnBot("ClearEating", 2310.5, y, 0.5); cleared[0].setHealth(5);
            cleared[0].getFoodData().setFoodLevel(10); cleared[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2));
            scheduler.runTaskLater(() -> run("bot inventory clear"), 15);
        }, () -> cleared[0].getAliveTicks() >= 45 && !cleared[0].isConsuming() && cleared[0].countItem(Items.GOLDEN_APPLE) == 0
                && !cleared[0].hasEffect(MobEffects.REGENERATION), 60, true));

        for (int hardness = 8; hardness <= 10; hardness++) {
            int value = hardness;
            Bot[] buffer = {null}, ally = {null};
            list.add(new Scenario("team splash buffs at hardness " + value + " grant " + (value - 7) + " types", () -> {
                run("bot settings hardness " + value); run("bot settings setgoal none");
                buffer[0] = spawnBot("Buffer" + value, 1410.5 + (value - 8) * 40, y, 0.5);
                ally[0] = spawnBot("BuffAlly" + value, 1412.5 + (value - 8) * 40, y, 0.5);
                ally[0].setHardnessOverride(7); testTeam("selftest_buff" + value, buffer[0], ally[0]);
            }, () -> {
                int count = 0;
                for (var effect : List.of(MobEffects.DAMAGE_BOOST, MobEffects.MOVEMENT_SPEED, MobEffects.JUMP)) if (ally[0].hasEffect(effect)) count++;
                return count == value - 7 && buffer[0].countItem(Items.SPLASH_POTION) == 0;
            }, 380, true));
        }

        Bot[] friendA = {null}, friendB = {null};
        list.add(new Scenario("same-team bots never attack each other even in nearestbot mode", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal nearestbot");
            friendA[0] = spawnBot("TeamFriendA", 1530.5, y, 0.5); friendB[0] = spawnBot("TeamFriendB", 1532.5, y, 0.5);
            friendA[0].setDefaultItem(new ItemStack(Items.NETHERITE_SWORD)); friendB[0].setDefaultItem(new ItemStack(Items.NETHERITE_SWORD));
            testTeam("selftest_friends", friendA[0], friendB[0]);
        }, () -> friendA[0].getAliveTicks() >= 80 && friendA[0].getHealth() == 20 && friendB[0].getHealth() == 20, 120, true));

        Bot[] pressure = {null}; Husk[] attacker = {null}; boolean[] escaped = {false};
        list.add(new Scenario("sustained pressure makes a high difficulty bot disengage with a real pearl", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal nearesthostile"); forceArea(1570, -30, 1640, 30);
            pressure[0] = spawnBot("UnderPressure", 1600.5, y, 0.5); pressure[0].giveItem(new ItemStack(Items.ENDER_PEARL, 4));
            attacker[0] = spawnHusk(1604.5, y, 0.5, 1000, true);
            scheduler.runTaskLater(() -> pressure[0].hurt(level.damageSources().mobAttack(attacker[0]), 1), 70);
            scheduler.runTaskLater(() -> pressure[0].hurt(level.damageSources().mobAttack(attacker[0]), 1), 90);
            scheduler.runTaskLater(() -> pressure[0].hurt(level.damageSources().mobAttack(attacker[0]), 1), 110);
            track(() -> { if (legacy().getSkills().memory(pressure[0]).getTactic() == BotMemory.Tactic.RETREAT) escaped[0] = true; });
        }, () -> escaped[0] && pressure[0].countItem(Items.ENDER_PEARL) < 4 && horizontal(pressure[0], attacker[0]) > 10, 240, true));

        Bot[] guard = {null}, wounded = {null};
        list.add(new Scenario("healthy teammate protects a wounded ally with cover", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal nearesthostile");
            guard[0] = spawnBot("Bodyguard", 1670.5, y, 0.5); wounded[0] = spawnBot("WoundedAlly", 1670.5, y, 2.5);
            wounded[0].setHardnessOverride(7); wounded[0].setHealth(5); testTeam("selftest_guard", guard[0], wounded[0]);
            spawnHusk(1680.5, y, 1.5, 1000, true);
        }, () -> legacy().getSkills().memory(guard[0]).getTactic() == BotMemory.Tactic.COVER_ALLY
                && level.getBlockState(new BlockPos(1672, y + 1, 2)).is(Blocks.COBBLESTONE), 100, true));

        Bot[] hunter = {null}; Husk[] weak = {null}; boolean[] wasHunt = {false};
        list.add(new Scenario("weak opponent is tracked from the air while eating", () -> {
            run("bot settings hardness 8"); run("bot settings setgoal nearesthostile"); forceArea(1710, -30, 1800, 30);
            hunter[0] = spawnBot("AirHunter", 1730.5, y, 0.5); hunter[0].setHealth(10);
            hunter[0].getFoodData().setFoodLevel(10); hunter[0].giveItem(new ItemStack(Items.ELYTRA));
            hunter[0].giveItem(new ItemStack(Items.FIREWORK_ROCKET, 32)); hunter[0].giveItem(new ItemStack(Items.GOLDEN_APPLE));
            weak[0] = spawnHusk(1760.5, y, 0.5, 20, true); weak[0].setHealth(4);
            track(() -> { if (hunter[0].isGliding() && legacy().getSkills().memory(hunter[0]).getTactic() == BotMemory.Tactic.HUNT) wasHunt[0] = true; });
        }, () -> wasHunt[0] && hunter[0].countItem(Items.GOLDEN_APPLE) == 0 && hunter[0].hasEffect(MobEffects.REGENERATION), 300, true));

        for (var weapon : List.of(Items.DIAMOND_SWORD, Items.DIAMOND_AXE)) {
            Bot[] fighter = {null}; Husk[] dummy = {null}; float[][] biggest = {null};
            list.add(new Scenario("vanilla jumping critical with " + weapon, () -> {
                run("bot settings hardness 9"); run("bot settings setgoal nearesthostile");
                fighter[0] = spawnBot("CriticalFighter", 1830.5, y, 0.5); fighter[0].giveItem(new ItemStack(weapon));
                dummy[0] = spawnHusk(1832.5, y, 0.5, 1000, false); biggest[0] = trackBiggestHit(dummy[0]);
            }, () -> biggest[0][1] >= (weapon == Items.DIAMOND_SWORD ? 10 : 13)
                    && fighter[0].findItem(s -> s.is(weapon)).getDamageValue() > 0, 400, true));
        }

        Bot[] diver = {null}; Husk[] runner = {null}; float[][] smash = {null};
        list.add(new Scenario("high difficulty elytra mace intercepts a moving target", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal nearesthostile"); forceArea(1880, -25, 1980, 35);
            diver[0] = spawnBot("LeadMace", 1890.5, y, 0.5); diver[0].giveItem(new ItemStack(Items.MACE));
            diver[0].giveItem(new ItemStack(Items.ELYTRA)); diver[0].giveItem(new ItemStack(Items.FIREWORK_ROCKET, 64));
            runner[0] = spawnHusk(1925.5, y, 0.5, 1000, false); smash[0] = trackBiggestHit(runner[0]);
            track(() -> { double z = Math.sin(diver[0].getAliveTicks() * 0.012) * 10; runner[0].moveTo(1925.5, y, z); });
        }, () -> smash[0][1] > 15, 1600, true));

        boolean[] presetOk = {false};
        list.add(new Scenario("equipment preset persists enchanted inventory armor and offhand without duplicates", () -> {
            run("bot settings setgoal none"); Bot source = spawnBot("PresetSource", 2000.5, y, 0.5);
            ItemStack sword = new ItemStack(Items.NETHERITE_SWORD); sword.setDamageValue(15);
            sword.enchant(level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                    .getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SHARPNESS), 5);
            source.getInventory().items.set(0, sword); source.setItem(new ItemStack(Items.DIAMOND_CHESTPLATE), EquipmentSlot.CHEST);
            source.setItemOffhand(new ItemStack(Items.TOTEM_OF_UNDYING)); source.giveItem(new ItemStack(Items.GOLDEN_APPLE, 5));
            source.giveItem(new ItemStack(Items.ENDER_PEARL, 7));
            try {
                var presets = TerminatorPlus.getManager().presets(); presets.save("selftest_gear", source); presets.select("selftest_gear");
                EquipmentPresets loaded = new EquipmentPresets(server); Bot clone = spawnBot("PresetClone", 2010.5, y, 0.5);
                ItemStack actual = clone.findItem(s -> s.is(Items.NETHERITE_SWORD));
                presetOk[0] = loaded.get("selftest_gear") != null && "selftest_gear".equals(loaded.selectedName())
                        && ItemStack.matches(actual, sword) && clone.countItem(Items.NETHERITE_SWORD) == 1
                        && clone.countItem(Items.GOLDEN_APPLE) == 5 && clone.countItem(Items.ENDER_PEARL) == 7
                        && clone.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE) && clone.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
                presets.select(null);
            } catch (Exception e) { throw new RuntimeException(e); }
        }, () -> presetOk[0], 20, true));

        list.add(new Scenario("createpreset atomically applies equipment hardness and team after async creation", () -> {
            run("team add selftest_spawn"); testTeams.add(server.getScoreboard().getPlayerTeam("selftest_spawn"));
            try { TerminatorPlus.getManager().presets().profile("selftest_gear", "team", "selftest_spawn", null); }
            catch (Exception e) { throw new RuntimeException(e); }
            run("bot createpreset selftest_gear Configured 9 selftest_spawn Steve 2040 " + y + " 0");
            run("bot createpreset selftest_gear ConfiguredNone 9 none Steve 2050 " + y + " 0");
        }, () -> {
            Terminator bot = TerminatorPlus.getManager().getFirst("Configured", null);
            Terminator unaffiliated = TerminatorPlus.getManager().getFirst("ConfiguredNone", null);
            return bot instanceof Bot b && legacy().getSkills().hardness(b).level() == 9 && b.countItem(Items.NETHERITE_SWORD) == 1
                    && b.countItem(Items.GOLDEN_APPLE) == 5 && b.getTeam() == server.getScoreboard().getPlayerTeam("selftest_spawn")
                    && unaffiliated != null && unaffiliated.getEntity().getTeam() == null;
        }, 100, true));

        boolean[] rejected = {false};
        list.add(new Scenario("full preset is rejected without losing an inventory slot", () -> {
            Bot full = spawnBot("FullInventory", 2070.5, y, 0.5);
            for (int i = 0; i < 36; i++) full.getInventory().items.set(i, new ItemStack(Items.COBBLESTONE, 64));
            try { EquipmentPresets.capture(full); } catch (IllegalArgumentException expected) { rejected[0] = true; }
            try { TerminatorPlus.getManager().presets().delete("selftest_gear"); } catch (Exception e) { throw new RuntimeException(e); }
        }, () -> rejected[0], 20, true));

        boolean[] flanks = {false};
        list.add(new Scenario("teammates choose separated pursuit lanes around the same target", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none"); forceArea(2100, -10, 2150, 10);
            Bot a = spawnBot("FlankA", 2110.5, y, 0.5), b = spawnBot("FlankB", 2110.5, y, 2.5);
            testTeam("selftest_flanks", a, b); Husk target = spawnHusk(2130.5, y, 0.5, 1000, true);
            Vec3 left = legacy().getSkills().pursuitPoint(a, target, target.position());
            Vec3 right = legacy().getSkills().pursuitPoint(b, target, target.position());
            flanks[0] = left.distanceTo(right) >= 4 && left.distanceTo(target.position()) <= 6 && right.distanceTo(target.position()) <= 6;
        }, () -> flanks[0], 20, true));
        list.addAll(foundationScenarios(y));
        list.addAll(eliteScenarios(y));
        list.addAll(strategyScenarios(y));
        list.addAll(cooperationScenarios(y));
        list.addAll(teamDiveScenarios(y));
        list.addAll(feedbackScenarios(y));
        return list;
    }

    private List<Scenario> foundationScenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        boolean[] kits = {false};
        list.add(new Scenario("foundation default equipment covers all ten levels", () -> {
            run("bot settings setgoal none"); run("bot settings defaultgear true");
            kits[0] = true;
            for (int i = 1; i <= 10; i++) {
                legacy().getSkillSettings().hardness = i;
                Bot b = spawnBot("DefaultKit" + i, 2360.5 + i * 3, y, 0.5);
                kits[0] &= !b.getWeapon().isEmpty() && !b.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
                if (i >= 8) kits[0] &= b.countItem(Items.GOLDEN_APPLE) > 0 && b.countItem(Items.COOKED_BEEF) > 0;
                kits[0] &= b.getAbilities().instabuild == (i == 10) && !b.getAbilities().invulnerable;
            }
            run("bot settings defaultgear false");
        }, () -> kits[0], 20, true));

        Bot[] ten = {null};
        list.add(new Scenario("foundation ten preserves food pearls charges arrows and durability but remains vulnerable", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none");
            ten[0] = spawnBot("InfiniteTen", 2400.5, y, 0.5); ten[0].setHealth(5);
            ten[0].getFoodData().setFoodLevel(10); ten[0].getFoodData().setSaturation(0);
            ten[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2));
            ten[0].giveItem(new ItemStack(Items.ENDER_PEARL, 2)); ten[0].giveItem(new ItemStack(Items.WIND_CHARGE, 2));
            ten[0].giveItem(new ItemStack(Items.BOW)); ten[0].giveItem(new ItemStack(Items.ARROW, 2));
            ten[0].consumeItem(Items.ENDER_PEARL); ten[0].throwWindCharge(0, -80);
            ten[0].shootBow(0, -80);
        }, () -> ten[0].hasEffect(MobEffects.REGENERATION) && ten[0].countItem(Items.GOLDEN_APPLE) == 2
                && ten[0].countItem(Items.ENDER_PEARL) == 2 && ten[0].countItem(Items.WIND_CHARGE) == 2
                && ten[0].countItem(Items.ARROW) == 2 && ten[0].findItem(s -> s.is(Items.BOW)).getDamageValue() == 0
                && !ten[0].isInvulnerableTo(level.damageSources().generic()), 100, true));

        Bot[] regen9 = {null}, regen10 = {null};
        list.add(new Scenario("foundation passive regeneration is retained at ten and disabled at nine", () -> {
            run("bot settings setgoal none");
            regen9[0] = spawnBot("NoFreeRegen", 2440.5, y, 0.5); regen9[0].setHardnessOverride(9);
            regen10[0] = spawnBot("FreeRegenTen", 2450.5, y, 0.5); regen10[0].setHardnessOverride(10);
            for (Bot b : List.of(regen9[0], regen10[0])) { b.setHealth(10); b.getFoodData().setFoodLevel(10); b.getFoodData().setSaturation(0); }
        }, () -> regen9[0].getAliveTicks() >= 40 && regen9[0].getHealth() == 10 && regen10[0].getHealth() > 10.8, 60, true));

        Bot[] mutex = {null}; boolean[] blockedActions = {false};
        list.add(new Scenario("foundation eating rejects bow mace and shield without losing real stacks", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal none");
            mutex[0] = spawnBot("MutexFood", 2480.5, y, 0.5);
            mutex[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2)); mutex[0].giveItem(new ItemStack(Items.MACE));
            mutex[0].giveItem(new ItemStack(Items.BOW)); mutex[0].giveItem(new ItemStack(Items.ARROW, 2)); mutex[0].setShield(true);
            Husk target = spawnHusk(2482.5, y, 0.5, 100, true);
            boolean began = mutex[0].beginRecoveryItem(false);
            mutex[0].drawBow(); mutex[0].block(20, 20);
            blockedActions[0] = began && !mutex[0].shootBow(0, 0) && !mutex[0].smash(target)
                    && mutex[0].isConsuming() && mutex[0].getUseItem().is(Items.GOLDEN_APPLE);
        }, () -> blockedActions[0] && !mutex[0].isConsuming() && mutex[0].countItem(Items.GOLDEN_APPLE) == 1
                && mutex[0].countItem(Items.MACE) == 1 && mutex[0].countItem(Items.BOW) == 1 && mutex[0].countItem(Items.ARROW) == 2, 60, true));

        boolean[] shieldWorks = {false};
        list.add(new Scenario("foundation vanilla shield blocks frontal damage after five ticks", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal none");
            Bot b = spawnBot("ActualShield", 2520.5, y, 0.5); b.setShield(true);
            Husk source = spawnHusk(2520.5, y, 3.5, 100, true);
            b.faceLocation(source.position()); b.block(30, 10);
            scheduler.runTaskLater(() -> {
                float hp = b.getHealth(); b.hurt(level.damageSources().mobAttack(source), 4);
                shieldWorks[0] = b.isBotBlocking() && b.getHealth() == hp;
            }, 8);
        }, () -> shieldWorks[0], 25, true));

        Bot[] delayed = {null};
        list.add(new Scenario("foundation old shield timer cannot cancel a newer food action", () -> {
            run("bot settings hardness 9"); run("bot settings setgoal none");
            delayed[0] = spawnBot("ShieldToFood", 2560.5, y, 0.5); delayed[0].setShield(true);
            delayed[0].giveItem(new ItemStack(Items.GOLDEN_APPLE, 2)); delayed[0].block(10, 10);
            scheduler.runTaskLater(() -> delayed[0].beginRecoveryItem(false), 5);
        }, () -> delayed[0].getAliveTicks() >= 45 && delayed[0].hasEffect(MobEffects.REGENERATION)
                && delayed[0].countItem(Items.GOLDEN_APPLE) == 1 && !delayed[0].isConsuming(), 60, true));

        boolean[] savedSettings = {false};
        list.add(new Scenario("foundation settings SNBT reload preserves difficulty abilities goal range and material", () -> {
            run("bot settings hardness 9"); run("bot settings ability bow false"); run("bot settings range 48");
            run("bot settings setgoal nearestenemy"); run("bot settings buildblock obsidian"); run("bot settings chatter off");
            legacy().getSkillSettings().hardness = 1; legacy().getSkillSettings().targetRange = 3;
            legacy().getSkillSettings().set("bow", true);
            try { TerminatorPlus.getManager().reloadSettings(); } catch (Exception e) { throw new RuntimeException(e); }
            var s = legacy().getSkillSettings();
            savedSettings[0] = s.hardness == 9 && s.targetRange == 48 && !s.get("bow") && s.buildBlock == Blocks.OBSIDIAN
                    && legacy().getTargetType() == net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal.NEAREST_ENEMY && s.chatter.equals("off");
            run("bot settings range unlimited"); run("bot settings ability bow true"); run("bot settings chatter mild");
        }, () -> savedSettings[0], 20, true));

        boolean[] tuning = {false};
        list.add(new Scenario("foundation editable numeric profiles roundtrip and reject invalid intervals atomically", () -> {
            var settings = new net.nuggetmc.tplus.api.agent.legacyagent.skill.SkillSettings();
            var tag = settings.save(); tag.getCompound("Profiles").getCompound("9").putInt("ReactionTicks", 12);
            settings.load(tag);
            var before = settings.save(); var invalid = before.copy(); invalid.getCompound("Profiles").getCompound("9").putInt("AttackInterval", 0);
            boolean rejected = false;
            try { settings.load(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
            tuning[0] = settings.profile(9).reactionTicks() == 12 && settings.profile(7).reactionTicks() == 0 && rejected && before.equals(settings.save());
        }, () -> tuning[0], 20, true));

        boolean[] mortality = {false};
        list.add(new Scenario("foundation ten consumes its finite totem and can still die from actual damage", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none");
            Bot b = spawnBot("FiniteTotemTen", 2580.5, y, 0.5); b.giveItem(new ItemStack(Items.TOTEM_OF_UNDYING)); b.equipTotem();
            scheduler.runTaskLater(() -> {
                b.invulnerableTime = 0; b.hurt(level.damageSources().generic(), 100);
                boolean popped = b.isAlive() && b.countItem(Items.TOTEM_OF_UNDYING) == 0 && b.getOffhandItem().isEmpty();
                b.invulnerableTime = 0; b.hurt(level.damageSources().generic(), 200);
                mortality[0] = popped && !b.isAlive();
            }, 65);
        }, () -> mortality[0], 100, true));

        boolean[] migration = {false};
        list.add(new Scenario("foundation legacy compressed NBT migrates to editable SNBT without altering original", () -> {
            Bot source = spawnBot("MigrationSource", 2590.5, y, 0.5); source.getInventory().items.set(0, new ItemStack(Items.DIAMOND_SWORD));
            var snapshot = EquipmentPresets.capture(source);
            var root = new net.minecraft.nbt.CompoundTag(); var entries = new net.minecraft.nbt.CompoundTag(); var entry = new net.minecraft.nbt.CompoundTag();
            entry.put("Inventory", snapshot.inventory()); entry.put("Weapon", snapshot.weapon()); entries.put("oldkit", entry);
            root.put("Presets", entries); root.putString("Selected", "oldkit");
            Path directory = server.getWorldPath(LevelResource.ROOT).resolve("data/selftest-migration");
            try {
                Files.createDirectories(directory); Path binary = directory.resolve("legacy.dat"), text = directory.resolve("loadouts.snbt");
                Files.deleteIfExists(text); net.minecraft.nbt.NbtIo.writeCompressed(root, binary); byte[] bytes = Files.readAllBytes(binary);
                var loaded = new EquipmentPresets(server, binary, text); Bot clone = spawnBot("MigratedClone", 2595.5, y, 0.5);
                loaded.selected().apply(clone);
                migration[0] = Files.isRegularFile(text) && java.util.Arrays.equals(bytes, Files.readAllBytes(binary))
                        && clone.countItem(Items.DIAMOND_SWORD) == 1 && loaded.selectedName().equals("oldkit");
            } catch (Exception e) { throw new RuntimeException(e); }
        }, () -> migration[0], 20, true));
        return list;
    }

    private ServerPlayer spawnHuman(String name, double x, int y, double z) {
        var profile = new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, net.minecraft.server.level.ClientInformation.createDefault());
        List<net.minecraft.network.protocol.Packet<?>> packets = new ArrayList<>(); humanPackets.put(player.getUUID(), packets);
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(server,
                new net.nuggetmc.tplus.bot.BotConnection(), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) { packets.add(packet); }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.PacketSendListener listener) { packets.add(packet); }
        };
        player.moveTo(x, y, z);
        level.addFreshEntity(player); server.getPlayerList().players.add(player); spawned.add(player);
        return player;
    }

    private List<Scenario> eliteScenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        boolean[] perception = {false};
        list.add(new Scenario("elite eight and nine require FOV and LOS while ten retains information advantage", () -> {
            run("bot settings setgoal none"); forceArea(2610, -10, 2650, 10);
            for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy < 3; dy++) setBlock(2620 + dx, y + dy, 4, Blocks.AIR.defaultBlockState());
            Bot b = spawnBot("Perception", 2620.5, y, 0.5); b.setHardnessOverride(9);
            Husk target = spawnHusk(2620.5, y, 8.5, 100, true);
            b.setLook(180, 0); boolean rear = !legacy().getSkills().canSee(b, target);
            b.setLook(0, 0); boolean front = legacy().getSkills().canSee(b, target);
            for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy < 3; dy++) setBlock(2620 + dx, y + dy, 4, Blocks.STONE.defaultBlockState());
            boolean wall = !legacy().getSkills().canSee(b, target);
            b.setHardnessOverride(10);
            perception[0] = rear && front && wall && legacy().getSkills().canSee(b, target);
        }, () -> perception[0], 20, true));

        boolean[] read = {false};
        list.add(new Scenario("elite reads real enemy inventory eating and attack cooldown windows", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none");
            Bot b = spawnBot("ReadOpponent", 2670.5, y, 0.5);
            ServerPlayer player = spawnHuman("ReadHuman", 2672.5, y, 0.5);
            player.getInventory().items.set(2, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.getInventory().offhand.set(0, new ItemStack(Items.TOTEM_OF_UNDYING));
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 4));
            player.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
            legacy().getSkills().tickActive(b, player);
            var memory = legacy().getSkills().memory(b);
            boolean food = memory.getWindow() == BotMemory.Window.EATING && memory.getEnemyTotems() == 2;
            player.stopUsingItem(); player.resetAttackStrengthTicker();
            legacy().getSkills().tickActive(b, player);
            read[0] = food && memory.getWindow() == BotMemory.Window.ATTACK_COOLDOWN;
        }, () -> read[0], 20, true));

        Bot[] breaker = {null}, shieldTarget = {null}; boolean[] broken = {false}, swordHit = {false};
        list.add(new Scenario("elite axe disables a real shield then switches to a sword critical with vanilla cooldown", () -> {
            run("bot settings setgoal none");
            breaker[0] = spawnBot("AxeSword", 2710.5, y, 0.5); breaker[0].setHardnessOverride(10);
            shieldTarget[0] = spawnBot("ShieldDummy", 2710.5, y, 2.5); shieldTarget[0].setHardnessOverride(9);
            shieldTarget[0].getAttribute(Attributes.MAX_HEALTH).setBaseValue(200); shieldTarget[0].setHealth(200);
            breaker[0].giveItem(new ItemStack(Items.DIAMOND_AXE)); breaker[0].giveItem(new ItemStack(Items.DIAMOND_SWORD));
            shieldTarget[0].setShield(true); shieldTarget[0].faceLocation(breaker[0].position()); shieldTarget[0].block(120, 0);
            scheduler.runTaskLater(() -> {
                track(() -> {
                    shieldTarget[0].moveTo(2710.5, y, 2.5); shieldTarget[0].setVelocity(Vec3.ZERO);
                    legacy().getSkills().tickActive(breaker[0], shieldTarget[0]);
                    if (breaker[0].tickDelay(3)) breaker[0].attackTarget(shieldTarget[0]);
                    broken[0] |= shieldTarget[0].getCooldowns().isOnCooldown(Items.SHIELD);
                    swordHit[0] |= broken[0] && shieldTarget[0].getHealth() <= 189.5;
                });
            }, 8);
        }, () -> broken[0] && swordHit[0] && breaker[0].findItem(s -> s.is(Items.DIAMOND_SWORD)).getDamageValue() == 0, 160, true));

        boolean[] routing = {false};
        list.add(new Scenario("elite nearest enemy mixes humans and bots and excludes the human teammate", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal nearestenemy");
            Bot b = spawnBot("MixedTeam", 2760.5, y, 0.5); b.setDefaultItem(new ItemStack(Items.IRON_SWORD));
            Bot other = spawnBot("OtherEnemy", 2776.5, y, 0.5); other.setHardnessOverride(1);
            ServerPlayer ally = spawnHuman("HumanAlly", 2761.5, y, 0.5), enemy = spawnHuman("HumanEnemy", 2763.5, y, 0.5);
            PlayerTeam team = testTeam("selftest_mixed", b);
            server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
            track(() -> { routing[0] |= legacy().getSkills().currentTarget(b) == enemy && ally.getHealth() == 20; });
        }, () -> routing[0], 60, true));

        boolean[] profiles = {false};
        list.add(new Scenario("elite text loadout profile retains difficulty team personality and ability overrides", () -> {
            run("bot settings setgoal none");
            Bot source = spawnBot("ProfileSource", 2810.5, y, 0.5); source.giveItem(new ItemStack(Items.IRON_SWORD));
            testTeam("selftest_profile", source);
            try {
                var presets = TerminatorPlus.getManager().presets(); presets.save("selftest_profile", source);
                presets.profile("selftest_profile", "hardness", "10", null);
                presets.profile("selftest_profile", "team", "selftest_profile", null);
                presets.profile("selftest_profile", "personality", "分析", null);
                presets.profile("selftest_profile", "ability", "bow", "false");
                var loaded = new EquipmentPresets(server); Bot clone = spawnBot("ProfileClone", 2815.5, y, 0.5);
                loaded.get("selftest_profile").apply(clone);
                profiles[0] = clone.hardness().level() == 10 && clone.personality().equals("分析")
                        && !legacy().getSkills().enabled(clone, "bow") && clone.getTeam() == source.getTeam() && clone.countItem(Items.IRON_SWORD) == 1;
                presets.delete("selftest_profile");
            } catch (Exception e) { throw new RuntimeException(e); }
        }, () -> profiles[0], 20, true));

        boolean[] learning = {false};
        list.add(new Scenario("elite learning persists supplied outcomes and biases choices without eliminating exploration", () -> {
            java.util.UUID player = java.util.UUID.fromString("cafef00d-1234-1234-1234-012345678910");
            var store = legacy().getSkills().learning();
            for (int i = 0; i < 10; i++) { store.observe(player, net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.MACE, true); store.observe(player, net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.BOW, false); }
            try { store.save(); } catch (Exception e) { throw new RuntimeException(e); }
            var loaded = new net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning(server);
            learning[0] = loaded.result(player, net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.MACE).hits() >= 10
                    && loaded.weight(player, net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.MACE) > loaded.weight(player, net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.BOW)
                    && loaded.weight(player, net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.BOW) > 0;
        }, () -> learning[0], 20, true));

        boolean[] catalog = {false};
        list.add(new Scenario("elite chatter parses all 637 phrases and skips unknown or unavailable slots", () -> {
            var chatter = legacy().getSkills().chatter();
            int phrases = chatter.categories().values().stream().mapToInt(c -> c.phrases().size()).sum();
            var parsed = net.nuggetmc.tplus.api.agent.legacyagent.skill.ChatterSystem.parse("[enemy.test] cd=3 p=1 via=title\n! @分析 目标{player}，{habit}\n@阴人 ! 测试\n");
            var category = parsed.get("enemy.test");
            catalog[0] = chatter.categories().size() == 74 && phrases == 637 && category.cooldown() == 60 && category.phrases().size() == 2
                    && net.nuggetmc.tplus.api.agent.legacyagent.skill.ChatterSystem.render("{unknown}", java.util.Map.of()) == null
                    && net.nuggetmc.tplus.api.agent.legacyagent.skill.ChatterSystem.render("{player}", java.util.Map.of("player", "$test" )).equals("$test");
        }, () -> catalog[0], 20, true));

        boolean[] speechRouting = {false};
        list.add(new Scenario("elite chatter routes enemies allies and title channels to real intended listeners only", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none");
            Bot b = spawnBot("PrivateTalk", 2870.5, y, 0.5);
            ServerPlayer ally = spawnHuman("TalkAlly", 2872.5, y, 0.5), enemy = spawnHuman("TalkEnemy", 2875.5, y, 0.5), bystander = spawnHuman("TalkOutside", 2878.5, y, 0.5);
            PlayerTeam team = testTeam("selftest_talk", b); server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
            var chatter = legacy().getSkills().chatter();
            speechRouting[0] = chatter.eligible(b, "enemy.dive_warn", enemy, enemy) && !chatter.eligible(b, "enemy.dive_warn", bystander, enemy)
                    && !chatter.eligible(b, "enemy.dive_warn", ally, enemy) && chatter.eligible(b, "ally.cover", ally, enemy)
                    && !chatter.eligible(b, "ally.cover", enemy, enemy) && !chatter.eligible(b, "ally.cover", b, enemy);
        }, () -> speechRouting[0], 20, true));

        boolean[] observed = {false};
        list.add(new Scenario("elite learning counts confirmed native damage to an actual ServerPlayer", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none");
            Bot b = spawnBot("NativeLearning", 2910.5, y, 0.5); b.giveItem(new ItemStack(Items.DIAMOND_SWORD));
            b.profileAbilities().put("criticals", false);
            ServerPlayer player = spawnHuman("LearnHuman", 2910.5, y, 2.5);
            scheduler.runTaskLater(() -> {
                b.attackTarget(player); legacy().getSkills().beginAttempt(b, player, net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.BOW);
                var result = legacy().getSkills().learning().result(player.getUUID(), net.nuggetmc.tplus.api.agent.legacyagent.skill.OpponentLearning.Move.MELEE);
                observed[0] = player.getHealth() < 20 && result.attempts() == 1 && result.hits() == 1;
            }, 65);
        }, () -> observed[0], 100, true));

        boolean[] delivered = {false}, second = {false}, speakerLimit = {false};
        list.add(new Scenario("elite chatter sends targeted packets and enforces speaker and shared listener limits", () -> {
            run("bot settings hardness 10"); run("bot settings setgoal none");
            Bot a = spawnBot("TalkFirst", 2950.5, y, 0.5), b = spawnBot("TalkSecond", 2952.5, y, 0.5), title = spawnBot("TalkTitle", 2954.5, y, 0.5);
            PlayerTeam team = testTeam("selftest_limits", a, b, title);
            ServerPlayer ally = spawnHuman("LimitAlly", 2951.5, y, 2.5), enemy = spawnHuman("LimitEnemy", 2951.5, y, 5.5), outside = spawnHuman("LimitOutside", 2951.5, y, 8.5);
            server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
            var chatter = legacy().getSkills().chatter();
            List<java.util.UUID> first = chatter.say(a, "ally.greet", enemy, java.util.Map.of());
            delivered[0] = first.equals(List.of(ally.getUUID())) && chatter.say(b, "ally.greet", enemy, java.util.Map.of()).isEmpty();
            for (int attempt = 0; attempt < 64; attempt++) {
                if (!chatter.say(title, "enemy.dive_warn", enemy, java.util.Map.of()).isEmpty()) break;
            }
            delivered[0] &= humanPackets.get(enemy.getUUID()).stream().anyMatch(p -> p instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket)
                    && humanPackets.get(outside.getUUID()).stream().noneMatch(p -> p instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket);
            scheduler.runTaskLater(() -> second[0] = chatter.say(b, "ally.greet", enemy, java.util.Map.of()).equals(List.of(ally.getUUID())), 45);
            scheduler.runTaskLater(() -> speakerLimit[0] = chatter.say(a, "ally.greet", enemy, java.util.Map.of()).isEmpty(), 60);
        }, () -> delivered[0] && second[0] && speakerLimit[0], 80, true));
        return list;
    }

    private Bot strategyBot(String name, double x, int y, double z) {
        Bot bot = spawnBot(name, x, y, z);
        bot.setHardnessOverride(10);
        for (String ability : List.of("mace", "elytra", "bow", "pearls", "windcharges", "recovery", "teamwork", "criticals")) bot.profileAbilities().put(ability, false);
        return bot;
    }

    private List<Scenario> strategyScenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        boolean[] feint = {false}, speed = {true}; double[] retreatDistance = {0}; Bot[] retreat = {null};
        list.add(new Scenario("strategy feigned retreat moves away then returns to combat within existing speed limits", () -> {
            run("bot settings setgoal none"); forceArea(3000, -16, 3040, 32);
            retreat[0] = strategyBot("FeignedRetreat", 3020.5, y, 0.5);
            Husk target = spawnHusk(3020.5, y, 10.5, 200, true);
            var skills = legacy().getSkills();
            scheduler.runTaskLater(() -> {
                retreat[0].setHardnessOverride(9); boolean nine = !skills.tryFeint(retreat[0], target, false);
                retreat[0].setHardnessOverride(10); skills.tickActive(retreat[0], target);
                feint[0] = nine && skills.tryFeint(retreat[0], target, false);
                run("bot settings setgoal nearesthostile");
            }, 5);
            track(() -> {
                if (skills.memory(retreat[0]).getFeint() == BotMemory.Feint.RETREAT) {
                    retreatDistance[0] = Math.max(retreatDistance[0], horizontal(retreat[0], target));
                    speed[0] &= retreat[0].getVelocity().horizontalDistance() <= 0.40001;
                }
            });
        }, () -> feint[0] && speed[0] && retreatDistance[0] > 12 && retreat[0].getAliveTicks() >= 30
                && legacy().getSkills().memory(retreat[0]).getFeint() == BotMemory.Feint.NONE, 100, true));

        boolean[] interrupted = {false}, began = {false};
        list.add(new Scenario("strategy actual incoming damage cancels a feint before continuing normal survival", () -> {
            run("bot settings setgoal none"); forceArea(3050, -16, 3090, 32);
            Bot bot = strategyBot("FeintInterrupted", 3070.5, y, 0.5);
            Husk target = spawnHusk(3070.5, y, 10.5, 200, true);
            scheduler.runTaskLater(() -> {
                legacy().getSkills().tickActive(bot, target);
                began[0] = legacy().getSkills().tryFeint(bot, target, false);
                run("bot settings setgoal nearesthostile");
                scheduler.runTaskLater(() -> {
                    boolean hurt = bot.hurt(bot.damageSources().mobAttack(target), 3);
                    scheduler.runTaskLater(() -> interrupted[0] = hurt && bot.getHealth() < 19
                            && legacy().getSkills().memory(bot).getFeint() == BotMemory.Feint.NONE, 2);
                }, 8);
            }, 65);
        }, () -> began[0] && interrupted[0], 100, true));

        boolean[] forecast = {false};
        list.add(new Scenario("strategy pearl forecast matches the actual native owner teleport position", () -> {
            run("bot settings setgoal none"); forceArea(3100, -16, 3180, 16);
            ServerPlayer owner = spawnHuman("PearlForecast", 3120.5, y, 0.5);
            var pearl = new net.minecraft.world.entity.projectile.ThrownEnderpearl(level, owner);
            pearl.setDeltaMovement(new Vec3(0.8, 0.65, 0));
            level.addFreshEntity(pearl); spawned.add(pearl);
            var landing = net.nuggetmc.tplus.api.agent.legacyagent.skill.PearlTrajectory.predict(pearl, 80);
            if (landing == null) throw new IllegalStateException("No native pearl forecast");
            track(() -> { if (pearl.isRemoved()) forecast[0] = owner.position().distanceTo(landing.position()) < 0.15; });
        }, () -> forecast[0], 90, true));

        boolean[] selected = {false}, arrived = {false}, hit = {false}; double[] moved = {0};
        list.add(new Scenario("strategy intercepts a real enemy pearl then attacks its owner on landing", () -> {
            run("bot settings setgoal none"); forceArea(3190, -20, 3280, 20);
            Bot bot = strategyBot("PearlAmbush", 3245.5, y, 0.5); bot.giveItem(new ItemStack(Items.DIAMOND_SWORD));
            bot.profileAbilities().put("deception", false);
            ServerPlayer owner = spawnHuman("PearlEnemy", 3210.5, y, 0.5);
            scheduler.runTaskLater(() -> {
                var pearl = new net.minecraft.world.entity.projectile.ThrownEnderpearl(level, owner);
                pearl.setDeltaMovement(new Vec3(0.8, 0.65, 0)); level.addFreshEntity(pearl); spawned.add(pearl);
                run("bot settings setgoal nearestenemy");
                track(() -> {
                    var memory = legacy().getSkills().memory(bot);
                    selected[0] |= memory.getInterceptPoint() != null && memory.getCombo() == BotMemory.Combo.PEARL_AMBUSH;
                    if (!pearl.isRemoved()) moved[0] = Math.max(moved[0], 3245.5 - bot.getX());
                    else {
                        arrived[0] |= bot.position().distanceTo(owner.position()) < 3.5 && owner.getX() > 3225;
                        hit[0] |= owner.getHealth() < 14.5;
                    }
                });
            }, 65);
        }, () -> selected[0] && arrived[0] && hit[0] && moved[0] > 1, 200, true));

        boolean[] gates = {false};
        list.add(new Scenario("strategy interception gates respect difficulty toggle and unloaded terrain", () -> {
            run("bot settings setgoal none"); forceArea(3290, -16, 3340, 16);
            Bot bot = strategyBot("PearlGate", 3325.5, y, 0.5);
            ServerPlayer owner = spawnHuman("PearlGateEnemy", 3300.5, y, 0.5);
            var pearl = new net.minecraft.world.entity.projectile.ThrownEnderpearl(level, owner);
            pearl.setDeltaMovement(new Vec3(0.8, 0.65, 0)); level.addFreshEntity(pearl); spawned.add(pearl);
            var skills = legacy().getSkills(); bot.setHardnessOverride(9); skills.tickActive(bot, owner);
            boolean nine = skills.memory(bot).getInterceptPoint() == null;
            bot.setHardnessOverride(10); bot.profileAbilities().put("interception", false); skills.tickActive(bot, owner);
            boolean disabled = skills.memory(bot).getInterceptPoint() == null;
            var remote = new net.minecraft.world.entity.projectile.ThrownEnderpearl(level, owner);
            remote.setPos(100000.5, y + 2, 100000.5); remote.setDeltaMovement(new Vec3(1, 0, 0));
            gates[0] = nine && disabled && net.nuggetmc.tplus.api.agent.legacyagent.skill.PearlTrajectory.predict(remote, 80) == null
                    && !level.hasChunkAt(remote.blockPosition());
            remote.discard();
        }, () -> gates[0], 20, true));

        boolean[] commitment = {false};
        list.add(new Scenario("strategy observed totem exhaustion commits pressure instead of feinting or hunting", () -> {
            run("bot settings setgoal none"); forceArea(3360, -16, 3400, 16);
            Bot bot = strategyBot("NoTotemPressure", 3380.5, y, 0.5);
            bot.profileAbilities().put("elytra", true); bot.profileAbilities().put("mace", true); bot.profileAbilities().put("recovery", true);
            bot.giveItem(new ItemStack(Items.ELYTRA)); bot.giveItem(new ItemStack(Items.FIREWORK_ROCKET, 64)); bot.giveItem(new ItemStack(Items.MACE));
            bot.setHealth(17.5F);
            ServerPlayer owner = spawnHuman("LastTotem", 3380.5, y, 6.5);
            owner.getInventory().offhand.set(0, new ItemStack(Items.TOTEM_OF_UNDYING));
            var skills = legacy().getSkills(); skills.tickActive(bot, owner);
            owner.getInventory().offhand.set(0, ItemStack.EMPTY); owner.setHealth(4);
            skills.tickActive(bot, owner);
            commitment[0] = skills.memory(bot).isAllIn() && skills.memory(bot).getTactic() == BotMemory.Tactic.FIGHT
                    && !skills.tryFeint(bot, owner, false) && !skills.tryStart(bot, owner);
        }, () -> commitment[0], 20, true));

        boolean[] climbed = {false}, dived = {false}, exited = {false}, legal = {true}, started = {false};
        list.add(new Scenario("strategy feigned elytra dive descends and pulls away without a mace attack", () -> {
            run("bot settings setgoal none"); forceArea(3410, -80, 3550, 80);
            Bot bot = strategyBot("FakeDive", 3470.5, y, 0.5);
            bot.profileAbilities().put("elytra", true); bot.profileAbilities().put("mace", true);
            bot.giveItem(new ItemStack(Items.ELYTRA)); bot.giveItem(new ItemStack(Items.FIREWORK_ROCKET, 64)); bot.giveItem(new ItemStack(Items.MACE));
            Husk target = spawnHusk(3470.5, y, 14.5, 200, true);
            var skills = legacy().getSkills();
            scheduler.runTaskLater(() -> {
                skills.tickActive(bot, target); started[0] = skills.tryFeint(bot, target, true);
                run("bot settings setgoal nearesthostile");
            }, 5);
            track(() -> {
                var memory = skills.memory(bot);
                climbed[0] |= memory.getFeint() == BotMemory.Feint.CLIMB && bot.getY() > y + 5;
                dived[0] |= memory.getFeint() == BotMemory.Feint.DIVE && bot.getVelocity().y < -0.05;
                exited[0] |= memory.getFeint() == BotMemory.Feint.EXIT;
                if (memory.getFeint() != BotMemory.Feint.NONE) legal[0] &= !memory.isMaceDropping() && target.getHealth() == 200;
            });
        }, () -> started[0] && climbed[0] && dived[0] && exited[0] && legal[0], 330, true));
        boolean[] guarded = {false}, shieldHit = {false}; Bot[] defender = {null}; ItemStack[] actualShield = {null}; int[] firstShieldDamage = {0};
        list.add(new Scenario("strategy ordinary high bot swaps its real totem for a shield and blocks a native incoming arrow", () -> {
            run("bot settings setgoal none"); forceArea(3570, -20, 3610, 30);
            defender[0] = strategyBot("AutoShield", 3590.5, y, 0.5); defender[0].setHardnessOverride(9);
            actualShield[0] = new ItemStack(Items.SHIELD);
            actualShield[0].set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Persistent Shield"));
            actualShield[0].enchant(server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING), 1);
            defender[0].setLook(0, 0); defender[0].setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, actualShield[0]);
            defender[0].giveItem(new ItemStack(Items.TOTEM_OF_UNDYING));
            ServerPlayer shooter = spawnHuman("ShieldArcher", 3590.5, y, 18.5);
            scheduler.runTaskLater(() -> {
                defender[0].setLook(0, 0);
                var arrow = new net.minecraft.world.entity.projectile.Arrow(level, shooter, new ItemStack(Items.ARROW), null);
                arrow.setPos(3590.5, y + 1.5, 16.5); arrow.setDeltaMovement(new Vec3(0, 0.25, -1.5)); arrow.setBaseDamage(4);
                level.addFreshEntity(arrow); spawned.add(arrow);
                track(() -> {
                    guarded[0] |= defender[0].isBlocking() && legacy().getSkills().memory(defender[0]).isDefending();
                    ItemStack shield = defender[0].getOffhandItem().is(Items.SHIELD) ? defender[0].getOffhandItem() : defender[0].findItem(stack -> stack.is(Items.SHIELD));
                    shieldHit[0] |= !shield.isEmpty() && shield.getDamageValue() > 0;

                });
            }, 65);
            scheduler.runTaskLater(() -> {
                firstShieldDamage[0] = actualShield[0].getDamageValue(); defender[0].setLook(0, 0);
                var arrow = new net.minecraft.world.entity.projectile.Arrow(level, shooter, new ItemStack(Items.ARROW), null);
                arrow.setPos(3590.5, y + 1.5, 16.5); arrow.setDeltaMovement(new Vec3(0, 0.25, -1.5)); arrow.setBaseDamage(4);
                level.addFreshEntity(arrow); spawned.add(arrow);
            }, 105);
        }, () -> guarded[0] && shieldHit[0] && defender[0].getHealth() == 20 && defender[0].getAliveTicks() >= 130
                && defender[0].getOffhandItem().is(Items.TOTEM_OF_UNDYING) && defender[0].countItem(Items.SHIELD) == 1
                && defender[0].findItem(stack -> stack.is(Items.SHIELD)) == actualShield[0]
                && firstShieldDamage[0] > 0 && actualShield[0].getDamageValue() > firstShieldDamage[0] && actualShield[0].isEnchanted()
                && actualShield[0].get(net.minecraft.core.component.DataComponents.CUSTOM_NAME).getString().equals("Persistent Shield")
                && !legacy().getSkills().memory(defender[0]).isDefending(), 170, true));

        boolean[] shieldGate = {false};
        list.add(new Scenario("strategy automatic defense obeys native shield cooldown and cannot replace active food", () -> {
            run("bot settings setgoal none"); forceArea(3630, -20, 3670, 30);
            Bot bot = strategyBot("ShieldCooldown", 3650.5, y, 0.5); bot.setHardnessOverride(9); bot.setLook(0, 0);
            bot.profileAbilities().put("recovery", true);
            bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            bot.giveItem(new ItemStack(Items.GOLDEN_APPLE, 2));
            ServerPlayer shooter = spawnHuman("CooldownArcher", 3650.5, y, 18.5);
            var arrow = new net.minecraft.world.entity.projectile.Arrow(level, shooter, new ItemStack(Items.ARROW), null);
            arrow.setPos(3650.5, y + 1.5, 16.5); arrow.setDeltaMovement(new Vec3(0, 0.25, -1.5)); level.addFreshEntity(arrow); spawned.add(arrow);
            scheduler.runTaskLater(() -> {
                bot.getCooldowns().addCooldown(Items.SHIELD, 100); legacy().getSkills().tickActive(bot, null);
                boolean cooldown = !legacy().getSkills().memory(bot).isDefending() && !bot.isUsingItem();
                bot.getCooldowns().removeCooldown(Items.SHIELD); boolean eating = bot.beginRecoveryItem(false);
                legacy().getSkills().tickActive(bot, null);
                shieldGate[0] = cooldown && eating && bot.isConsuming() && !legacy().getSkills().memory(bot).isDefending();
            }, 5);
        }, () -> shieldGate[0], 20, true));
        boolean[] singleBow = {false}; Bot[] archer = {null};
        java.util.Set<java.util.UUID> bowShots = new java.util.HashSet<>();
        list.add(new Scenario("strategy disabled mace uses standalone bow shots without entering an unavailable combo", () -> {
            run("bot settings setgoal none"); forceArea(3690, -20, 3730, 70);
            archer[0] = strategyBot("SingleBow", 3710.5, y, 0.5); archer[0].profileAbilities().put("bow", true); archer[0].profileAbilities().put("deception", false);
            archer[0].giveItem(new ItemStack(Items.MACE)); archer[0].giveItem(new ItemStack(Items.BOW)); archer[0].giveItem(new ItemStack(Items.ARROW, 32));
            Husk target = spawnHusk(3710.5, y, 48.5, 200, true);
            scheduler.runTaskLater(() -> {
                legacy().getSkills().tickActive(archer[0], target);
                run("bot settings setgoal nearesthostile");
                track(() -> {
                    var memory = legacy().getSkills().memory(archer[0]);
                    singleBow[0] |= memory.isDrawingBow() && memory.getCombo() == BotMemory.Combo.NONE;
                    level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class, archer[0].getBoundingBox().inflate(64), arrow -> arrow.getOwner() == archer[0])
                            .forEach(arrow -> bowShots.add(arrow.getUUID()));
                });
            }, 5);
        }, () -> singleBow[0] && bowShots.size() >= 2
                && !legacy().getSkills().memory(archer[0]).isMaceDropping(), 180, true));

        boolean[] noFalseGuard = {true}; Bot[] protectedBot = {null};
        list.add(new Scenario("strategy an actual wall stops the incoming arrow without wasting a shield action", () -> {
            run("bot settings setgoal none"); forceArea(3750, -20, 3790, 30);
            protectedBot[0] = strategyBot("ShieldCover", 3770.5, y, 0.5); protectedBot[0].setHardnessOverride(9);
            protectedBot[0].setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            for (int dx = -1; dx <= 1; dx++) for (int dy = 0; dy <= 3; dy++) setBlock(3770 + dx, y + dy, 4, Blocks.OBSIDIAN.defaultBlockState());
            ServerPlayer shooter = spawnHuman("CoverArcher", 3770.5, y, 18.5);
            scheduler.runTaskLater(() -> {
                var arrow = new net.minecraft.world.entity.projectile.Arrow(level, shooter, new ItemStack(Items.ARROW), null);
                arrow.setPos(3770.5, y + 1.5, 16.5); arrow.setDeltaMovement(new Vec3(0, 0.25, -1.5)); arrow.setBaseDamage(4);
                level.addFreshEntity(arrow); spawned.add(arrow);
            }, 65);
            track(() -> { protectedBot[0].setLook(0, 0); noFalseGuard[0] &= !legacy().getSkills().memory(protectedBot[0]).isDefending(); });
        }, () -> protectedBot[0].getAliveTicks() >= 90 && noFalseGuard[0] && protectedBot[0].getHealth() == 20
                && protectedBot[0].getOffhandItem().is(Items.SHIELD) && protectedBot[0].getOffhandItem().getDamageValue() == 0, 110, true));
        return list;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return a.subtract(b).multiply(1, 0, 1).length();
    }

    private Bot cooperationBot(String name, double x, int y, double z) {
        Bot bot = strategyBot(name, x, y, z);
        bot.profileAbilities().put("teamwork", true);
        for (String ability : List.of("deception", "interception", "shield")) bot.profileAbilities().put(ability, false);
        return bot;
    }

    private void cooperationArena(int minX, int minZ, int maxX, int maxZ, int y) {
        forceArea(minX, minZ, maxX, maxZ);
        int removed = 0;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) for (int dy = 0; dy < 6; dy++) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (!level.getBlockState(pos).isAir()) { level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3); removed++; }
        }
        LOGGER.info("[SelfTest] Reset cooperation arena above the floor: {} prior blocks removed", removed);
    }

    private List<Scenario> cooperationScenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        boolean[] followedAttack = {false}; ServerPlayer[] playerTarget = {null};
        list.add(new Scenario("cooperation actual human melee attack redirects the teammate from a nearer enemy", () -> {
            run("bot settings setgoal none"); cooperationArena(3810, -12, 3850, 26, y);
            Bot bot = cooperationBot("HumanFocus", 3830.5, y, 0.5); bot.setHardnessOverride(9); bot.setLook(0, 0);
            ServerPlayer ally = spawnHuman("FocusLeader", 3830.5, y, 14.5);
            playerTarget[0] = spawnHuman("FocusFar", 3830.5, y, 16.5);
            spawnHuman("FocusNear", 3830.5, y, 6.5);
            PlayerTeam team = testTeam("selftest_human_focus", bot); server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
            ally.getInventory().items.set(0, new ItemStack(Items.DIAMOND_SWORD));
            scheduler.runTaskLater(() -> {
                bot.setLook(0, 0); run("bot settings setgoal nearestenemy"); ally.attack(playerTarget[0]);
                track(() -> followedAttack[0] |= legacy().getSkills().currentTarget(bot) == playerTarget[0]);
            }, 65);
        }, () -> followedAttack[0] && playerTarget[0].getHealth() < 20, 100, true));

        boolean[] signalGates = {false}, expired = {false};
        list.add(new Scenario("cooperation human focus rechecks LOS difficulty toggles team identity and expiry", () -> {
            run("bot settings setgoal none"); cooperationArena(3870, -12, 3910, 26, y);
            Bot bot = cooperationBot("FocusGates", 3890.5, y, 0.5); bot.setLook(0, 0);
            ServerPlayer ally = spawnHuman("GateLeader", 3890.5, y, 14.5);
            ServerPlayer far = spawnHuman("GateFar", 3890.5, y, 16.5), near = spawnHuman("GateNear", 3890.5, y, 4.5);
            PlayerTeam team = testTeam("selftest_focus_gates", bot); server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
            for (int dx = -2; dx <= 2; dx++) for (int dy = 0; dy < 3; dy++) setBlock(3890 + dx, y + dy, 9, Blocks.STONE.defaultBlockState());
            scheduler.runTaskLater(() -> {
                ally.attack(far);
                var skills = legacy().getSkills();
                bot.setHardnessOverride(9); boolean hidden = skills.coordinateTarget(bot, near) == near;
                bot.setHardnessOverride(10); boolean read = skills.coordinateTarget(bot, near) == far;
                bot.setHardnessOverride(7); boolean seven = skills.coordinateTarget(bot, near) == near;
                bot.setHardnessOverride(10); bot.profileAbilities().put("teamwork", false);
                boolean disabled = skills.coordinateTarget(bot, near) == near;
                bot.profileAbilities().put("teamwork", true); server.getScoreboard().removePlayerFromTeam(ally.getScoreboardName());
                boolean left = skills.coordinateTarget(bot, near) == near;
                server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
                server.getScoreboard().addPlayerToTeam(far.getScoreboardName(), team);
                boolean friendly = skills.coordinateTarget(bot, near) == near;
                server.getScoreboard().removePlayerFromTeam(far.getScoreboardName());
                far.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                boolean creative = skills.coordinateTarget(bot, near) == near;
                far.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                signalGates[0] = hidden && read && seven && disabled && left && friendly && creative;
            }, 5);
            scheduler.runTaskLater(() -> expired[0] = legacy().getSkills().coordinateTarget(bot, near) == near, 80);
        }, () -> signalGates[0] && expired[0], 100, true));

        boolean[] guardedHuman = {false}, intercepted = {false}, defenseDisabled = {false}; Husk[] pursuer = {null};
        list.add(new Scenario("cooperation native damage triggers human protection interception and clean teamwork disable", () -> {
            run("bot settings setgoal none"); cooperationArena(3930, -12, 3970, 26, y);
            Bot bot = cooperationBot("HumanGuard", 3950.5, y, 0.5);
            bot.giveItem(new ItemStack(Items.DIAMOND_SWORD));
            ServerPlayer ally = spawnHuman("WoundedHuman", 3950.5, y, 4.5); ally.getFoodData().setFoodLevel(0);
            PlayerTeam team = testTeam("selftest_human_guard", bot); server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
            spawnHusk(3954.5, y, 0.5, 1000, true); pursuer[0] = spawnHusk(3950.5, y, 10.5, 1000, false);
            scheduler.runTaskLater(() -> {
                run("bot settings setgoal nearesthostile"); ally.setHealth(8); ally.hurt(level.damageSources().mobAttack(pursuer[0]), 4);
                track(() -> {
                    var memory = legacy().getSkills().memory(bot);
                    guardedHuman[0] |= memory.getTeamRole() == BotMemory.TeamRole.GUARD && memory.getGuardedAlly() == ally.getId()
                            && legacy().getSkills().currentTarget(bot) == pursuer[0] && memory.getTactic() == BotMemory.Tactic.COVER_ALLY;
                    intercepted[0] |= pursuer[0].getHealth() < 1000 && pursuer[0].getLastHurtByMob() == bot;
                    if (guardedHuman[0] && intercepted[0]) {
                        bot.profileAbilities().put("teamwork", false); legacy().getSkills().tickActive(bot, pursuer[0]);
                        defenseDisabled[0] = memory.getTeamRole() == BotMemory.TeamRole.NONE && memory.getGuardedAlly() == -1
                                && memory.getTactic() == BotMemory.Tactic.FIGHT;
                    }
                });
            }, 65);
        }, () -> guardedHuman[0] && intercepted[0] && defenseDisabled[0], 260, true));

        for (int value : List.of(8, 9, 10)) {
            Bot[] escort = {null}; ServerPlayer[] ally = {null}; boolean[] still = {false}, legal = {true};
            int x = 4010 + (value - 8) * 60;
            list.add(new Scenario("cooperation hardness " + value + " idle escort follows a real teammate within the existing speed limit", () -> {
                run("bot settings setgoal none"); cooperationArena(x - 20, -12, x + 20, 30, y);
                escort[0] = cooperationBot("Escort" + value, x + 0.5, y, 0.5); escort[0].setHardnessOverride(value); escort[0].setLook(0, 0);
                ally[0] = spawnHuman("Leader" + value, x + 0.5, y, 18.5);
                PlayerTeam team = testTeam("selftest_escort" + value, escort[0]); server.getScoreboard().addPlayerToTeam(ally[0].getScoreboardName(), team);
                scheduler.runTaskLater(() -> { still[0] = escort[0].getZ() < 1; run("bot settings setgoal nearestenemy"); }, 10);
                track(() -> legal[0] &= escort[0].getVelocity().multiply(1, 0, 1).length() <= 0.4001);
            }, () -> still[0] && legal[0] && escort[0].getZ() > 10 && escort[0].distanceTo(ally[0]) <= 5.5
                    && legacy().getSkills().memory(escort[0]).getGuardedAlly() == ally[0].getId(), 130, true));
        }

        boolean[] roleSet = {false}, ranged = {true}; Bot[] archer = {null};
        java.util.Set<java.util.UUID> teamArrows = new java.util.HashSet<>();
        list.add(new Scenario("cooperation ten assigns bait flank and archer and fires repeated native arrows from range", () -> {
            run("bot settings setgoal none"); cooperationArena(4170, -30, 4230, 60, y);
            Bot bait = cooperationBot("TeamBait", 4200.5, y, 0.5), flank = cooperationBot("TeamFlank", 4208.5, y, 0.5);
            archer[0] = cooperationBot("TeamArcher", 4200.5, y, -12.5);
            archer[0].profileAbilities().put("bow", true); archer[0].giveItem(new ItemStack(Items.BOW)); archer[0].giveItem(new ItemStack(Items.ARROW, 32));
            testTeam("selftest_roles", bait, flank, archer[0]); Husk target = spawnHusk(4200.5, y, 22.5, 1000, true);
            scheduler.runTaskLater(() -> {
                run("bot settings setgoal nearesthostile");
                track(() -> {
                    var skills = legacy().getSkills();
                    roleSet[0] |= skills.memory(bait).getTeamRole() == BotMemory.TeamRole.BAIT
                            && skills.memory(flank).getTeamRole() == BotMemory.TeamRole.FLANK && skills.memory(archer[0]).getTeamRole() == BotMemory.TeamRole.ARCHER;
                    ranged[0] &= archer[0].distanceTo(target) >= 8;
                    level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class, archer[0].getBoundingBox().inflate(64), a -> a.getOwner() == archer[0])
                            .forEach(a -> teamArrows.add(a.getUUID()));
                    if (archer[0].getAliveTicks() % 20 == 0) LOGGER.info("[SelfTest] Archer roles={} archerRole={} roleSet={} ranged={} arrows={} distance={} drawing={}",
                            List.of(skills.memory(bait).getTeamRole(), skills.memory(flank).getTeamRole()), skills.memory(archer[0]).getTeamRole(), roleSet[0], ranged[0], teamArrows.size(), archer[0].distanceTo(target), skills.memory(archer[0]).isDrawingBow());
                });
            }, 5);
        }, () -> roleSet[0] && ranged[0] && teamArrows.size() >= 2 && archer[0].getAliveTicks() >= 100, 160, true));

        boolean[] replaced = {false}, withdrew = {false}; Bot[] hurtBait = {null};
        list.add(new Scenario("cooperation a wounded front bot withdraws from an elevated threat while a healthy teammate takes its role", () -> {
            run("bot settings setgoal none"); cooperationArena(4250, -35, 4330, 45, y);
            hurtBait[0] = cooperationBot("OldFront", 4290.5, y, 8.5); hurtBait[0].profileAbilities().put("recovery", true);
            hurtBait[0].giveItem(new ItemStack(Items.GOLDEN_APPLE)); hurtBait[0].getFoodData().setFoodLevel(0);
            Bot replacement = cooperationBot("NewFront", 4290.5, y, 0.5), third = cooperationBot("Reserve", 4298.5, y, -5.5);
            testTeam("selftest_rotation", hurtBait[0], replacement, third);
            setBlock(4290, y + 3, 12, Blocks.OBSIDIAN.defaultBlockState());
            Husk target = spawnHusk(4290.5, y + 4, 12.5, 1000, true);
            scheduler.runTaskLater(() -> {
                hurtBait[0].setHealth(5); run("bot settings setgoal nearesthostile");
                track(() -> {
                    var skills = legacy().getSkills();
                    replaced[0] |= skills.memory(replacement).getTeamRole() == BotMemory.TeamRole.BAIT
                            && skills.memory(hurtBait[0]).getTeamRole() == BotMemory.TeamRole.NONE;
                    withdrew[0] |= horizontal(hurtBait[0], target) > 6.5
                            && (hurtBait[0].distanceTo(target) >= 14 || !target.hasLineOfSight(hurtBait[0]))
                            && hurtBait[0].hasEffect(MobEffects.REGENERATION)
                            && (skills.memory(hurtBait[0]).getTactic() == BotMemory.Tactic.RETREAT || skills.memory(hurtBait[0]).getTactic() == BotMemory.Tactic.RECOVER);
                });
            }, 5);
        }, () -> replaced[0] && withdrew[0], 240, true));

        boolean[] canceledSignal = {false};
        list.add(new Scenario("cooperation canceled native attack events do not request a teammate focus", () -> {
            run("bot settings setgoal none"); cooperationArena(4350, -12, 4390, 26, y);
            Bot bot = cooperationBot("CancelFocus", 4370.5, y, 0.5);
            ServerPlayer ally = spawnHuman("CancelLeader", 4370.5, y, 14.5);
            ServerPlayer far = spawnHuman("CancelFar", 4370.5, y, 16.5), near = spawnHuman("CancelNear", 4370.5, y, 4.5);
            PlayerTeam team = testTeam("selftest_canceled_focus", bot); server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
            var event = new net.neoforged.neoforge.event.entity.player.AttackEntityEvent(ally, far); event.setCanceled(true);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
            canceledSignal[0] = legacy().getSkills().coordinateTarget(bot, near) == near;
        }, () -> canceledSignal[0], 20, true));
        boolean[] pairGuard = {false}, released = {false};
        list.add(new Scenario("cooperation two wounded humans get distinct guards and a departed teammate releases its guard", () -> {
            run("bot settings setgoal none"); cooperationArena(4410, -12, 4470, 26, y);
            Bot a = cooperationBot("PairGuardA", 4430.5, y, 0.5), b = cooperationBot("PairGuardB", 4450.5, y, 0.5);
            ServerPlayer wardA = spawnHuman("PairWardA", 4430.5, y, 4.5), wardB = spawnHuman("PairWardB", 4450.5, y, 4.5);
            wardA.getFoodData().setFoodLevel(0); wardB.getFoodData().setFoodLevel(0);
            PlayerTeam team = testTeam("selftest_pair_guard", a, b);
            server.getScoreboard().addPlayerToTeam(wardA.getScoreboardName(), team); server.getScoreboard().addPlayerToTeam(wardB.getScoreboardName(), team);
            Husk enemyA = spawnHusk(4430.5, y, 10.5, 1000, true), enemyB = spawnHusk(4450.5, y, 10.5, 1000, true);
            scheduler.runTaskLater(() -> {
                wardA.setHealth(8); wardB.setHealth(8);
                wardA.hurt(level.damageSources().mobAttack(enemyA), 4); wardB.hurt(level.damageSources().mobAttack(enemyB), 4);
                var skills = legacy().getSkills();
                skills.coordinateTarget(a, enemyB); skills.coordinateTarget(b, enemyA);
                pairGuard[0] = skills.memory(a).getGuardedAlly() == wardA.getId() && skills.memory(b).getGuardedAlly() == wardB.getId();
                server.getScoreboard().removePlayerFromTeam(wardA.getScoreboardName());
                skills.coordinateTarget(a, enemyA);
                released[0] = skills.memory(a).getGuardedAlly() == -1 && skills.memory(a).getTeamRole() != BotMemory.TeamRole.GUARD;
            }, 65);
        }, () -> pairGuard[0] && released[0], 90, true));

        boolean[] withheld = {false}, allySafe = {true}, drawingBeforeBlock = {false}; Bot[] safeArcher = {null};
        java.util.Set<java.util.UUID> safeArrows = new java.util.HashSet<>(); Husk[] rangedEnemy = {null};
        list.add(new Scenario("cooperation a real teammate entering the draw lane prevents release until the lane clears", () -> {
            run("bot settings setgoal none"); cooperationArena(4490, -12, 4530, 40, y);
            safeArcher[0] = cooperationBot("SafeArcher", 4510.5, y, 0.5);
            safeArcher[0].profileAbilities().put("bow", true); safeArcher[0].giveItem(new ItemStack(Items.BOW)); safeArcher[0].giveItem(new ItemStack(Items.ARROW, 32));
            ServerPlayer ally = spawnHuman("CrossingAlly", 4516.5, y, 12.5);
            PlayerTeam team = testTeam("selftest_safe_shot", safeArcher[0]); server.getScoreboard().addPlayerToTeam(ally.getScoreboardName(), team);
            rangedEnemy[0] = spawnHusk(4510.5, y, 24.5, 1000, false);
            scheduler.runTaskLater(() -> {
                run("bot settings setgoal nearesthostile");
                track(() -> {
                    allySafe[0] &= ally.getHealth() == 20;
                    level.getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class, safeArcher[0].getBoundingBox().inflate(64), arrow -> arrow.getOwner() == safeArcher[0])
                            .forEach(arrow -> safeArrows.add(arrow.getUUID()));
                });
            }, 65);
            scheduler.runTaskLater(() -> {
                drawingBeforeBlock[0] = legacy().getSkills().memory(safeArcher[0]).isDrawingBow();
                ally.teleportTo(4510.5, y, 12.5);
            }, 72);
            scheduler.runTaskLater(() -> {
                withheld[0] = drawingBeforeBlock[0] && safeArrows.isEmpty() && ally.getHealth() == 20;
                ally.teleportTo(4516.5, y, 12.5);
            }, 90);
        }, () -> withheld[0] && allySafe[0] && !safeArrows.isEmpty() && rangedEnemy[0].getHealth() < 1000, 190, true));
        return list;
    }

    private record SmashObservation(long tick, Vec3 position, float fallDistance) {}
    private static final class SmashTrace {
        final java.util.Map<java.util.UUID, SmashObservation> attacks = new java.util.HashMap<>();
        final java.util.Map<java.util.UUID, Long> damage = new java.util.HashMap<>();
        final java.util.Map<java.util.UUID, Long> releases = new java.util.HashMap<>();
        final java.util.Map<java.util.UUID, Vec3> origins = new java.util.HashMap<>();
    }
    private SmashTrace captureSmashes(LivingEntity target) {
        SmashTrace trace = new SmashTrace();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.player.AttackEntityEvent> attack = event -> {
            if (!event.isCanceled() && event.getTarget() == target && event.getEntity() instanceof Bot bot
                    && bot.getMainHandItem().is(Items.MACE) && bot.canSmash() && bot.getVelocity().y < 0) {
                if (trace.attacks.putIfAbsent(bot.getUUID(), new SmashObservation(server.getTickCount(), bot.position(), bot.fallDistance)) == null)
                    LOGGER.info("[SelfTest] Native smash: {} at tick {}, fall distance {}, team impact {}", bot.getBotName(), server.getTickCount(), bot.fallDistance, legacy().getSkills().memory(bot).getTeamImpactTick());
            }
        };
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> damage = event -> {
            if (event.getEntity() == target && event.getNewDamage() > 0 && event.getSource().getEntity() instanceof Bot bot
                    && bot.getMainHandItem().is(Items.MACE) && bot.canSmash()) trace.damage.putIfAbsent(bot.getUUID(), (long) server.getTickCount());
        };
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, attack);
        NeoForge.EVENT_BUS.addListener(damage);
        scenarioHooks.add(() -> { NeoForge.EVENT_BUS.unregister(attack); NeoForge.EVENT_BUS.unregister(damage); });
        return trace;
    }
    private Bot diveBot(String name, double x, int y, double z) {
        Bot bot = cooperationBot(name, x, y, z);
        bot.profileAbilities().put("mace", true); bot.profileAbilities().put("elytra", true);
        bot.giveItem(new ItemStack(Items.MACE)); bot.giveItem(new ItemStack(Items.ELYTRA)); bot.giveItem(new ItemStack(Items.FIREWORK_ROCKET, 64));
        return bot;
    }
    private void diveArena(int x, int y) {
        cooperationArena(x - 32, -40, x + 32, 40, y);
        for (int bx = x - 24; bx <= x + 24; bx++) for (int bz = -32; bz <= 32; bz++) for (int dy = 6; dy <= 44; dy++) {
            BlockPos pos = new BlockPos(bx, y + dy, bz);
            if (!level.getBlockState(pos).isAir()) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }
    private void divePlatform(int x, int y) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) setBlock(x + dx, y - 1, dz, Blocks.OBSIDIAN.defaultBlockState());
    }
    private void trackReleases(SmashTrace trace, List<Bot> bots, boolean[] planned) {
        track(() -> {
            var skills = legacy().getSkills();
            if (bots.size() > 1 && bots.stream().allMatch(b -> skills.memory(b).getTeamImpactTick() > 0)
                    && bots.stream().map(b -> skills.memory(b).getTeamImpactTick()).distinct().count() == 1) planned[0] = true;
            for (Bot bot : bots) {
                var memory = skills.memory(bot);
                if (memory.isMaceDropping() && !trace.releases.containsKey(bot.getUUID())) {
                    trace.releases.put(bot.getUUID(), (long) server.getTickCount()); trace.origins.put(bot.getUUID(), bot.position());
                    LOGGER.info("[SelfTest] Dive release: {} at tick {}, pos {}, planned {}, contact estimate {}", bot.getBotName(), server.getTickCount(), bot.position(), memory.getTeamImpactTick(), memory.getTeamContactTicks());
                }
            }
        });
    }
    private boolean simultaneous(SmashTrace trace, Bot a, Bot b, LivingEntity target, boolean planned) {
        if (!planned || !trace.attacks.containsKey(a.getUUID()) || !trace.attacks.containsKey(b.getUUID())
                || !trace.releases.containsKey(a.getUUID()) || !trace.releases.containsKey(b.getUUID()) || trace.damage.isEmpty()) return false;
        Vec3 one = trace.origins.get(a.getUUID()), two = trace.origins.get(b.getUUID());
        Vec3 left = one.subtract(target.position()).multiply(1, 0, 1).normalize(), right = two.subtract(target.position()).multiply(1, 0, 1).normalize();
        return Math.abs(trace.attacks.get(a.getUUID()).tick() - trace.attacks.get(b.getUUID()).tick()) <= 2
                && Math.abs(trace.releases.get(a.getUUID()) - trace.releases.get(b.getUUID())) >= 2
                && Math.abs(one.y - two.y) >= 4 && left.dot(right) < 0.25;
    }
    private List<Scenario> teamDiveScenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        Bot[] solo = {null}; Husk[] soloTarget = {null}; SmashTrace[] singleTrace = {null}; long[] predicted = {-1};
        list.add(new Scenario("teamdive first contact forecast matches an actual native solo elytra smash", () -> {
            run("bot settings setgoal none"); diveArena(4600, y);
            solo[0] = diveBot("ContactForecast", 4584.5, y, 0.5); solo[0].profileAbilities().put("teamwork", false);
            soloTarget[0] = spawnHusk(4600.5, y, 0.5, 1000, false); singleTrace[0] = captureSmashes(soloTarget[0]);
            scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
            track(() -> {
                if (predicted[0] < 0 && legacy().getSkills().memory(solo[0]).isMaceDropping()) {
                    var contact = net.nuggetmc.tplus.api.agent.legacyagent.skill.DiveForecast.contact(solo[0], soloTarget[0], Vec3.ZERO);
                    if (contact != null) predicted[0] = server.getTickCount() + contact.ticks();
                }
            });
        }, () -> predicted[0] > 0 && singleTrace[0].attacks.containsKey(solo[0].getUUID()) && singleTrace[0].damage.containsKey(solo[0].getUUID())
                && Math.abs(singleTrace[0].attacks.get(solo[0].getUUID()).tick() - predicted[0]) <= 1, 800, true));

        for (boolean moving : List.of(false, true)) {
            int x = moving ? 4800 : 4700; Bot[] pair = new Bot[2]; Husk[] target = {null}; SmashTrace[] trace = {null}; boolean[] planned = {false};
            list.add(new Scenario("teamdive unequal native flight heights synchronize opposing " + (moving ? "moving" : "stationary") + " target smashes", () -> {
                run("bot settings setgoal none"); diveArena(x, y); divePlatform(x - 16, y + 18);
                pair[0] = diveBot("HighDive" + x, x - 15.5, y + 18, 0.5); pair[1] = diveBot("LowDive" + x, x + 16.5, y, 0.5);
                testTeam("selftest_dive_" + x, pair); target[0] = spawnHusk(x + 0.5, y, 0.5, 1000, false); trace[0] = captureSmashes(target[0]);
                if (moving) track(() -> target[0].move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0, 0, 0.12)));
                scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
                trackReleases(trace[0], List.of(pair), planned);
            }, () -> simultaneous(trace[0], pair[0], pair[1], target[0], planned[0]) && (!moving || target[0].getZ() > 2), 900, true));
        }
        for (boolean nearFirst : List.of(false, true)) {
            Bot[] sameSide = new Bot[2]; Husk[] sideTarget = {null}; SmashTrace[] sideTrace = {null}; boolean[] spread = {false};
            list.add(new Scenario("teamdive same side native takeoffs spread before a common smash with " + (nearFirst ? "near" : "far") + " member first", () -> {
                run("bot settings setgoal none"); diveArena(4850, y);
                Bot a = diveBot("SameSideA", 4830.5, y, -1.5), b = diveBot("SameSideB", 4833.5, y, 2.5);
                sameSide[0] = a.getUUID().compareTo(b.getUUID()) < 0 ? a : b;
                sameSide[1] = sameSide[0] == a ? b : a;
                // Exercise both real approaches in the UUID-ordered formation, rather than leaving coverage to random IDs.
                sameSide[nearFirst ? 1 : 0].moveTo(4830.5, y, -1.5);
                sameSide[nearFirst ? 0 : 1].moveTo(4833.5, y, 2.5);
                testTeam("selftest_same_side_dives", sameSide); sideTarget[0] = spawnHusk(4850.5, y, 0.5, 1000, false); sideTrace[0] = captureSmashes(sideTarget[0]);
                scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5); trackReleases(sideTrace[0], List.of(sameSide), spread);
            }, () -> {
                if (!spread[0] || sideTrace[0].attacks.size() < 2 || sideTrace[0].origins.size() < 2 || sideTrace[0].damage.isEmpty()) return false;
                Vec3 one = sideTrace[0].origins.get(sameSide[0].getUUID()).subtract(sideTarget[0].position()).multiply(1, 0, 1).normalize();
                Vec3 two = sideTrace[0].origins.get(sameSide[1].getUUID()).subtract(sideTarget[0].position()).multiply(1, 0, 1).normalize();
                return one.dot(two) < 0.25 && Math.abs(sideTrace[0].attacks.get(sameSide[0].getUUID()).tick() - sideTrace[0].attacks.get(sameSide[1].getUUID()).tick()) <= 2;
            }, 1000, true));
        }

        Bot[] survivors = new Bot[2]; SmashTrace[] survivingTrace = {null}; boolean[] canceled = {false}; long[] woundTick = {-1};
        list.add(new Scenario("teamdive a wounded airborne teammate cancels the shared impact and the healthy bot still smashes", () -> {
            run("bot settings setgoal none"); diveArena(4900, y); divePlatform(4884, y + 18);
            survivors[0] = diveBot("DiveSurvivor", 4884.5, y + 18, 0.5); survivors[1] = diveBot("DiveWounded", 4916.5, y, 0.5);
            survivors[1].profileAbilities().put("recovery", true); survivors[1].giveItem(new ItemStack(Items.GOLDEN_APPLE));
            testTeam("selftest_dive_cancel", survivors); Husk target = spawnHusk(4900.5, y, 0.5, 1000, false); survivingTrace[0] = captureSmashes(target);
            scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
            track(() -> {
                var skills = legacy().getSkills();
                if (woundTick[0] < 0 && skills.memory(survivors[0]).getTeamImpactTick() > 0 && skills.memory(survivors[1]).getTeamImpactTick() > 0) {
                    survivors[1].setHealth(5); woundTick[0] = server.getTickCount();
                }
                if (woundTick[0] >= 0 && skills.memory(survivors[1]).getTactic() != BotMemory.Tactic.FIGHT)
                    canceled[0] |= skills.memory(survivors[0]).getTeamImpactTick() < 0 && skills.memory(survivors[1]).getTeamImpactTick() < 0;
            });
        }, () -> canceled[0] && survivingTrace[0].damage.containsKey(survivors[0].getUUID())
                && !survivingTrace[0].attacks.containsKey(survivors[1].getUUID()) && survivors[1].isAlive(), 900, true));

        Bot[] blockedPair = new Bot[2]; SmashTrace[] blockedTrace = {null}; boolean[] routeCanceled = {false}; long[] blockedAt = {-1};
        list.add(new Scenario("teamdive new blocking terrain cancels a pending impact and native dives resume after it clears", () -> {
            run("bot settings setgoal none"); diveArena(4975, y); divePlatform(4959, y + 18);
            blockedPair[0] = diveBot("BlockedHigh", 4959.5, y + 18, 0.5); blockedPair[1] = diveBot("BlockedLow", 4991.5, y, 0.5);
            testTeam("selftest_dive_blocked", blockedPair); Husk target = spawnHusk(4975.5, y, 0.5, 1000, false); blockedTrace[0] = captureSmashes(target);
            scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
            track(() -> {
                var skills = legacy().getSkills();
                if (blockedAt[0] < 0 && skills.memory(blockedPair[0]).getTeamImpactTick() > 0 && skills.memory(blockedPair[1]).getTeamImpactTick() > 0) {
                    blockedAt[0] = server.getTickCount();
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) setBlock(4975 + dx, y + 7, dz, Blocks.OBSIDIAN.defaultBlockState());
                }
                if (blockedAt[0] >= 0 && server.getTickCount() - blockedAt[0] <= 5) {
                    routeCanceled[0] |= blockedTrace[0].attacks.isEmpty() && skills.memory(blockedPair[0]).getTeamImpactTick() < 0 && skills.memory(blockedPair[1]).getTeamImpactTick() < 0;
                    if (server.getTickCount() - blockedAt[0] == 5) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) setBlock(4975 + dx, y + 7, dz, Blocks.AIR.defaultBlockState());
                }
            });
        }, () -> routeCanceled[0] && blockedTrace[0].attacks.size() == 2 && !blockedTrace[0].damage.isEmpty(), 900, true));

        Bot[] separated = new Bot[2]; SmashTrace[] separateTrace = new SmashTrace[2]; boolean[] independent = {true};
        list.add(new Scenario("teamdive same team fighting different actual targets does not share an impact plan", () -> {
            run("bot settings setgoal none"); diveArena(5000, y); diveArena(5050, y); run("bot settings range 26");
            separated[0] = diveBot("SeparateDiveA", 4984.5, y, 0.5); separated[1] = diveBot("SeparateDiveB", 5066.5, y, 0.5);
            testTeam("selftest_separate_dives", separated);
            separateTrace[0] = captureSmashes(spawnHusk(5000.5, y, 0.5, 1000, false)); separateTrace[1] = captureSmashes(spawnHusk(5050.5, y, 0.5, 1000, false));
            scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
            track(() -> independent[0] &= java.util.Arrays.stream(separated).allMatch(b -> legacy().getSkills().memory(b).getTeamImpactTick() < 0));
            scenarioHooks.add(() -> run("bot settings range unlimited"));
        }, () -> independent[0] && separateTrace[0].damage.containsKey(separated[0].getUUID()) && separateTrace[1].damage.containsKey(separated[1].getUUID()), 900, true));

        Bot[] ordinary = new Bot[3]; SmashTrace[] ordinaryTrace = {null}; boolean[] unchanged = {true};
        list.add(new Scenario("teamdive eight nine and disabled teamwork automatically perform independent native dives", () -> {
            run("bot settings setgoal none"); diveArena(5150, y);
            ordinary[0] = diveBot("NineDive", 5134.5, y, 0.5); ordinary[0].setHardnessOverride(9);
            ordinary[1] = diveBot("NoTeamDive", 5166.5, y, 0.5); ordinary[1].profileAbilities().put("teamwork", false);
            ordinary[2] = diveBot("EightDive", 5150.5, y, 16.5); ordinary[2].setHardnessOverride(8);
            testTeam("selftest_ordinary_dives", ordinary); Husk target = spawnHusk(5150.5, y, 0.5, 1000, false); ordinaryTrace[0] = captureSmashes(target);
            scheduler.runTaskLater(() -> { for (Bot bot : ordinary) bot.faceLocation(target.getEyePosition()); run("bot settings setgoal nearesthostile"); }, 5);
            track(() -> unchanged[0] &= java.util.Arrays.stream(ordinary).allMatch(b -> legacy().getSkills().memory(b).getTeamImpactTick() < 0));
        }, () -> unchanged[0] && ordinaryTrace[0].attacks.size() == 3 && !ordinaryTrace[0].damage.isEmpty(), 900, true));

        boolean[] safeForecasts = {false};
        list.add(new Scenario("teamdive forecast rejects blocking terrain hazardous ground and unloaded target columns", () -> {
            run("bot settings setgoal none"); diveArena(5250, y);
            Bot bot = diveBot("ForecastTerrain", 5250.5, y + 18, 0.5); Husk target = spawnHusk(5250.5, y, 0.5, 1000, true); bot.setVelocity(new Vec3(0, -0.2, 0));
            var initial = net.nuggetmc.tplus.api.agent.legacyagent.skill.DiveForecast.contact(bot, target, Vec3.ZERO);
            setBlock(5250, y + 7, 0, Blocks.OBSIDIAN.defaultBlockState());
            boolean blocked = net.nuggetmc.tplus.api.agent.legacyagent.skill.DiveForecast.contact(bot, target, Vec3.ZERO) == null;
            setBlock(5250, y + 7, 0, Blocks.AIR.defaultBlockState()); setBlock(5250, y - 1, 0, Blocks.MAGMA_BLOCK.defaultBlockState());
            boolean hazard = net.nuggetmc.tplus.api.agent.legacyagent.skill.DiveForecast.contact(bot, target, Vec3.ZERO) == null;
            setBlock(5250, y - 1, 0, Blocks.GRASS_BLOCK.defaultBlockState());
            Husk remote = Objects.requireNonNull(EntityType.HUSK.create(level)); remote.moveTo(8765432.5, y, 8765432.5, 0, 0);
            boolean unloaded = !level.hasChunkAt(remote.blockPosition()) && net.nuggetmc.tplus.api.agent.legacyagent.skill.DiveForecast.contact(bot, remote, Vec3.ZERO) == null && !level.hasChunkAt(remote.blockPosition());
            remote.discard();
            LOGGER.info("[SelfTest] Forecast gates: initial={}, blocked={}, hazard={}, unloaded={}, origin={}", initial, blocked, hazard, unloaded, bot.position());
            safeForecasts[0] = initial != null && blocked && hazard && unloaded;
        }, () -> safeForecasts[0], 20, true));
        return list;
    }

    private java.util.Set<java.util.UUID> capturePositiveHits(LivingEntity target) {
        java.util.Set<java.util.UUID> hits = new java.util.HashSet<>();
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> listener = event -> {
            if (event.getEntity() == target && event.getNewDamage() > 0 && event.getSource().getEntity() instanceof Bot bot) hits.add(bot.getUUID());
        };
        NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
        return hits;
    }

    private List<Scenario> feedbackScenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        Bot[] grounded = new Bot[3]; List<java.util.Set<java.util.UUID>> closeHits = new ArrayList<>(); boolean[] stayedGrounded = {true};
        list.add(new Scenario("feedback high levels keep close combat on the ground and deal native melee damage", () -> {
            run("bot settings setgoal none");
            for (int i = 0; i < 3; i++) {
                int x = 5400 + i * 80; cooperationArena(x - 20, -16, x + 32, 16, y);
                Bot bot = diveBot("Grounded" + (8 + i), x + 0.5, y, 0.5); bot.setHardnessOverride(8 + i);
                bot.profileAbilities().put("teamwork", false); bot.setDefaultItem(new ItemStack(Items.DIAMOND_SWORD)); grounded[i] = bot;
                Husk target = spawnHusk(x + 6.5, y, 0.5, 200, false); closeHits.add(capturePositiveHits(target)); bot.faceLocation(target.getEyePosition());
            }
            scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
            track(() -> { for (Bot bot : grounded) stayedGrounded[0] &= !bot.isGliding() && legacy().getSkills().memory(bot).getFlight() == BotMemory.Flight.NONE; });
        }, () -> stayedGrounded[0] && java.util.stream.IntStream.range(0, 3).allMatch(i -> closeHits.get(i).contains(grounded[i].getUUID())), 240, true));

        Bot[] moderate = {null}; boolean[] groundRecovery = {true};
        list.add(new Scenario("feedback moderate injury recovers with a real apple without an unnecessary elytra launch", () -> {
            run("bot settings setgoal none"); cooperationArena(5630, -30, 5700, 30, y);
            moderate[0] = diveBot("ModerateRecovery", 5660.5, y, 0.5); moderate[0].profileAbilities().put("recovery", true);
            moderate[0].profileAbilities().put("teamwork", false); moderate[0].setHealth(11); moderate[0].getFoodData().setFoodLevel(0);
            moderate[0].giveItem(new ItemStack(Items.GOLDEN_APPLE)); spawnHusk(5666.5, y, 0.5, 200, true);
            scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
            track(() -> groundRecovery[0] &= !moderate[0].isGliding() && legacy().getSkills().memory(moderate[0]).getFlight() == BotMemory.Flight.NONE);
        }, () -> groundRecovery[0] && moderate[0].hasEffect(MobEffects.REGENERATION) && moderate[0].getHealth() > 11, 180, true));

        for (boolean emergency : List.of(false, true)) {
            Bot[] bot = {null}; Husk[] target = {null}; SmashTrace[] trace = {null}; long[] landed = {-1}; Vec3[] groundOrigin = {null};
            int[] launches = {0}; boolean[] wasFlying = {false}, escaping = {false}, cooldown = {false}, injury = {false};
            list.add(new Scenario("feedback " + (emergency ? "serious native damage permits escape during the offensive flight rest" : "native offensive smash is followed by ground pursuit instead of repeat takeoff"), () -> {
                int x = emergency ? 5880 : 5770; run("bot settings setgoal none"); diveArena(x, y);
                bot[0] = diveBot(emergency ? "EmergencyFlight" : "RestedFlight", x - 15.5, y, 0.5); bot[0].profileAbilities().put("teamwork", false);
                bot[0].setDefaultItem(new ItemStack(Items.DIAMOND_SWORD)); target[0] = spawnHusk(x + 0.5, y, 0.5, 1000, false); trace[0] = captureSmashes(target[0]);
                scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
                track(() -> {
                    var mem = legacy().getSkills().memory(bot[0]);
                    boolean flight = mem.getFlight() != BotMemory.Flight.NONE || bot[0].isGliding();
                    if (flight && !wasFlying[0]) launches[0]++;
                    wasFlying[0] = flight;
                    if (landed[0] < 0 && !trace[0].damage.isEmpty() && bot[0].isBotOnGround() && !flight && (!emergency || bot[0].getAliveTicks() >= 70)) {
                        landed[0] = server.getTickCount(); groundOrigin[0] = bot[0].position();
                        cooldown[0] = mem.getNextOffensiveFlight() > landed[0] + 200;
                        target[0].moveTo(bot[0].getX() + 24, y, bot[0].getZ());
                        if (emergency) {
                            injury[0] = bot[0].hurt(bot[0].damageSources().mobAttack(target[0]), 2);
                            LOGGER.info("[SelfTest] Emergency pressure first hit: accepted={}, age={}, health={}", injury[0], bot[0].getAliveTicks(), bot[0].getHealth());
                            scheduler.runTaskLater(() -> {
                                boolean second = bot[0].hurt(bot[0].damageSources().mobAttack(target[0]), 2); injury[0] &= second;
                                LOGGER.info("[SelfTest] Emergency pressure second hit: accepted={}, age={}, health={}, rest={}", second, bot[0].getAliveTicks(), bot[0].getHealth(), legacy().getSkills().memory(bot[0]).getNextOffensiveFlight() - server.getTickCount());
                            }, 21);
                        }
                    }
                    if (landed[0] >= 0) {
                        if (!emergency) target[0].move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.3, 0, 0));
                        escaping[0] |= mem.getFlightPlan() == BotMemory.FlightPlan.RECOVER && bot[0].isGliding()
                                && server.getTickCount() - landed[0] < 60 && mem.getNextOffensiveFlight() > server.getTickCount();
                    }
                });
            }, () -> cooldown[0] && landed[0] >= 0 && (emergency ? injury[0] && escaping[0] && launches[0] >= 2
                    : server.getTickCount() - landed[0] >= 200 && launches[0] == 1 && horizontalDistance(bot[0].position(), groundOrigin[0]) > 5), 700, true));
        }

        Bot[] blocked = {null}; java.util.Set<java.util.UUID>[] blockedHits = new java.util.Set[]{null}; boolean[] fallback = {false}; Vec3[] blockedOrigin = {null};
        list.add(new Scenario("feedback blocked close formation yields to navigation and actually reaches native melee", () -> {
            run("bot settings setgoal none"); cooperationArena(5950, -24, 6000, 24, y);
            blocked[0] = cooperationBot("BlockedFormation", 5970.5, y, 0.5); blocked[0].setDefaultItem(new ItemStack(Items.DIAMOND_SWORD));
            Bot ally = cooperationBot("FormationPartner", 5959.5, y, 0.5); testTeam("selftest_blocked_formation", blocked[0], ally);
            Husk enemy = spawnHusk(5974.0, y, 0.5, 1000, false); blockedHits[0] = capturePositiveHits(enemy); blockedOrigin[0] = blocked[0].position();
            for (int z = -5; z <= 5; z++) for (int dy = 0; dy < 3; dy++) setBlock(5971, y + dy, z, Blocks.OBSIDIAN.defaultBlockState());
            scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
            track(() -> fallback[0] |= legacy().getSkills().memory(blocked[0]).getGroundFallbackUntil() > server.getTickCount());
        }, () -> fallback[0] && blockedHits[0].contains(blocked[0].getUUID()) && horizontalDistance(blocked[0].position(), blockedOrigin[0]) > 1.5, 600, true));

        Bot[] empty = {null}; boolean[] resumed = {false}; Vec3[] recoveryOrigin = {null};
        list.add(new Scenario("feedback empty recovery supplies release ground control and resume real pursuit", () -> {
            run("bot settings setgoal none"); cooperationArena(6020, -30, 6080, 30, y);
            empty[0] = strategyBot("EmptyRecovery", 6040.5, y, 0.5); empty[0].profileAbilities().put("recovery", true);
            empty[0].profileAbilities().put("shield", false); empty[0].profileAbilities().put("deception", false); empty[0].profileAbilities().put("interception", false);
            empty[0].setHealth(9); empty[0].getFoodData().setFoodLevel(0); recoveryOrigin[0] = empty[0].position(); spawnHusk(6062.5, y, 0.5, 1000, true);
            scheduler.runTaskLater(() -> run("bot settings setgoal nearesthostile"), 5);
            track(() -> resumed[0] |= empty[0].getAliveTicks() >= 60 && legacy().getSkills().memory(empty[0]).getTactic() == BotMemory.Tactic.FIGHT);
        }, () -> resumed[0] && horizontalDistance(empty[0].position(), recoveryOrigin[0]) > 3 && empty[0].getAliveTicks() <= 180, 180, true));
        return list;
    }

    private void startReinforcement(int y) {
        // reinforcement learning session: a bot plays the player who runs /ai reinforcement
        LOGGER.info("[SelfTest] ---- reinforcement setup ----");
        run("bot create Anchor Anchor 60 " + y + " 60");

        scheduler.runTaskLater(() -> {
            LOGGER.info("[SelfTest] ---- reinforcement start ----");
            Terminator anchor = bots("Anchor").stream().findFirst().orElseThrow();
            AICommand ai = (AICommand) TerminatorPlus.getHandler().getCommand("ai");
            ai.reinforcement(anchor.getEntity(), 5, "Gen", null);
            check("training session started", ai.hasActiveSession());
            waitForGeneration(ai, 0);
        }, 20);
    }

    private void waitForGeneration(AICommand ai, int waited) {
        scheduler.runTaskLater(() -> {
            IntelligenceAgent session = ai.getSession();
            int generation = session == null ? -1 : session.getGeneration();

            if (generation >= 2 || session == null || waited >= 20 * 180) {
                LOGGER.info("[SelfTest] ---- reinforcement result ----");
                check("a full training generation completed", generation >= 2);
                run("ai stop");
                scheduler.runTaskLater(() -> {
                    check("training session stopped", !ai.hasActiveSession());
                    run("bot settings setgoal none");
                    run("bot reset");
                    summary();
                }, 40);
            } else {
                waitForGeneration(ai, waited + 100);
            }
        }, 100);
    }

    private void summary() {
        scheduler.runTaskLater(() -> {
            LOGGER.info("[SelfTest] ---- summary ----");
            if (failures.isEmpty()) {
                LOGGER.info("[SelfTest] ALL {} CHECKS PASSED", checks);
            } else {
                LOGGER.error("[SelfTest] {} of {} checks FAILED: {}", failures.size(), checks, failures);
            }
            server.halt(false);
        }, 20);
    }

    private List<String> suggestions(String input) {
        ParseResults<CommandSourceStack> parse = server.getCommands().getDispatcher().parse(input, console);
        List<String> result = server.getCommands().getDispatcher().getCompletionSuggestions(parse).join()
                .getList().stream().map(Suggestion::getText).filter(Objects::nonNull).collect(Collectors.toList());
        LOGGER.info("[SelfTest]   suggestions for '{}': {}", input, result.size() > 12 ? result.subList(0, 12) + "..." : result);
        return result;
    }
}
