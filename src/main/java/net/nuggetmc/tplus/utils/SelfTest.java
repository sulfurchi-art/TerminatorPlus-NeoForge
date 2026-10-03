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
        LOGGER.info("[SelfTest] starting, flat world surface at y={}", ground(0, 0));

        int y = ground(0, 0);

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
        trackers.forEach(TickTask::cancel);
        trackers.clear();
        spawned.forEach(Entity::discard);
        spawned.clear();
        run("bot settings setgoal none");
        run("bot settings buildblock minecraft:cobblestone");
        run("bot reset");
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
        boolean[] placed = new boolean[1];
        list.add(new Scenario("escapes a block placed inside it", () -> {
            forceArea(795, -5, 805, 5);
            embedded[0] = spawnBot("Embedded", 800.5, y, 0.5);
            scheduler.runTaskLater(() -> {
                level.setBlockAndUpdate(embedded[0].blockPosition(), Blocks.STONE.defaultBlockState());
                placed[0] = true;
            }, 30);
        }, () -> placed[0] && level.noCollision(embedded[0], embedded[0].getBoundingBox().deflate(1.0E-3)), 100, true));

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
