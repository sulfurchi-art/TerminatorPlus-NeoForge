exec((__import__('pathlib').Path(__file__).parent/'edit_4_22.py').read_text(encoding='utf-8').split("edit('gradle.properties'")[0])
p=root/'src/main/java/net/nuggetmc/tplus/utils/SelfTest.java';s=p.read_text(encoding='utf-8')
idx=s.index('        return list;\n    }\n    private List<Scenario> warfare422Scenarios',s.index('private List<Scenario> battleLogScenarios'))
s=s[:idx]+r'''        net.nuggetmc.tplus.utils.BattleLogWriter[] broken = {null};
        list.add(new Scenario("battle log 4.22 writer failure is isolated from the server thread", () -> {
            try {
                var parent = java.nio.file.Path.of("logs/terminatorplus/not-a-directory-" + java.util.UUID.randomUUID());
                java.nio.file.Files.createDirectories(parent.getParent()); java.nio.file.Files.writeString(parent, "fixture");
                broken[0] = new net.nuggetmc.tplus.utils.BattleLogWriter(parent.resolve("battle.jsonl")); broken[0].offer("{}");
                scenarioHooks.add(() -> broken[0].close());
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }, () -> broken[0].closed() && broken[0].error() != null && !broken[0].offer("{}"), 100, true));
        List<Bot> crowd = new ArrayList<>(); long[] benchmarkStarted = {0}, tickStarted = {0}; List<Double> offTicks = new ArrayList<>(), onTicks = new ArrayList<>();
        boolean[] benchDone = {false}; java.nio.file.Path[] benchFile = {null};
        list.add(new Scenario("battle log 4.22 one hundred idle bots collect snapshots without queue loss", () -> {
            run("bot settings setgoal none"); forceArea(29800, -8, 29860, 48);
            for (int i = 0; i < 100; i++) crowd.add(spawnBot("LogLoad" + i, 29804.5 + i % 10 * 4, y, i / 10 * 4 + 0.5));
            benchmarkStarted[0] = server.getTickCount();
            java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Pre> before = event -> tickStarted[0] = System.nanoTime();
            java.util.function.Consumer<net.neoforged.neoforge.event.tick.ServerTickEvent.Post> after = event -> {
                long elapsed = server.getTickCount() - benchmarkStarted[0]; double ms = (System.nanoTime() - tickStarted[0]) / 1000000.0;
                if (elapsed >= 80 && elapsed < 180) offTicks.add(ms);
                if (elapsed >= 240 && elapsed < 340) onTicks.add(ms);
            };
            NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST, before); NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, after);
            scenarioHooks.add(() -> { NeoForge.EVENT_BUS.unregister(before); NeoForge.EVENT_BUS.unregister(after); log.toggle(false); });
            track(() -> {
                long elapsed = server.getTickCount() - benchmarkStarted[0];
                if (elapsed == 180) { log.toggle(true); benchFile[0] = log.path(); }
                if (elapsed == 360) {
                    LOGGER.info("[SelfTest] BattleLog 100 idle bots baselineAvgMs={} loggingAvgMs={} baselineP95Ms={} loggingP95Ms={} dropped={}",
                            offTicks.stream().mapToDouble(Double::doubleValue).average().orElse(-1), onTicks.stream().mapToDouble(Double::doubleValue).average().orElse(-1),
                            offTicks.stream().sorted().skip(95).findFirst().orElse(-1.0), onTicks.stream().sorted().skip(95).findFirst().orElse(-1.0), log.dropped());
                    log.toggle(false); benchDone[0] = true;
                }
            });
        }, () -> benchDone[0] && crowd.stream().allMatch(Bot::isAlive) && offTicks.size() == 100 && onTicks.size() == 100 &&
                battleRows(benchFile[0]).stream().noneMatch(o -> o.get("type").getAsString().equals("log_gap")) &&
                battleRows(benchFile[0]).stream().filter(o -> o.get("type").getAsString().equals("snap")).map(o -> o.get("bot").getAsString()).distinct().count() == 100, 440, true));
''' +s[idx:]
idx=s.index('        return list;\n    }\n\n    private List<Scenario> warfareMissileScenarios',s.index('private List<Scenario> warfare422Scenarios'))
s=s[:idx]+r'''        Bot[] survivor = {null}, launcher = {null}; Entity[] first = {null}, second = {null}; int[] peak = {0}; boolean[] damagedBlock = {false}, noRepair = {true}; java.nio.file.Path[] defenseFile = {null}; boolean[] defenseDone = {false};
        list.add(new Scenario("warfare 4.22 missile shelter has a twenty five second cooldown and never reinforces broken blocks", () -> {
            warfareFlatArena(30000, -20, 30150, 20, y); run("bot settings setgoal none"); run("bot log on"); defenseFile[0] = log.path(); scenarioHooks.add(() -> log.toggle(false));
            survivor[0] = missileBot("CooldownCover", 10, 30100.5, y, 0.5); survivor[0].giveItem(warfareItem("steel_block", 64)); survivor[0].setInvulnerable(true);
            launcher[0] = infantry("RepeatedLauncher", 7, 30010.5, y, 0.5); launcher[0].profileAbilities().put("guns", false);
            track(() -> {
                int age = survivor[0].getAliveTicks();
                if (age == 80) first[0] = fireJavelin(launcher[0], survivor[0], false);
                peak[0] = Math.max(peak[0], skills.missiles().placed(survivor[0]));
                if (!damagedBlock[0] && peak[0] >= 4) {
                    for (BlockPos pos : BlockPos.betweenClosed(30097, y, -3, 30103, y + 3, 3)) if (level.getBlockState(pos).getBlock() == ((net.minecraft.world.item.BlockItem) warfareItem("steel_block", 1).getItem()).getBlock()) {
                        skills.missiles().invalidate(net.minecraft.core.GlobalPos.of(level.dimension(), pos.immutable())); setBlock(pos.getX(), pos.getY(), pos.getZ(), Blocks.AIR.defaultBlockState()); damagedBlock[0] = true; break;
                    }
                }
                if (age == 140) second[0] = fireJavelin(launcher[0], survivor[0], false);
                if (damagedBlock[0]) noRepair[0] &= survivor[0].countItem(warfareItem("steel_block", 1).getItem()) == 60;
                if (age == 240) { log.toggle(false); defenseDone[0] = true; }
            });
        }, () -> defenseDone[0] && damagedBlock[0] && peak[0] == 4 && noRepair[0] && first[0].isRemoved() && second[0].isRemoved() &&
                battleRows(defenseFile[0]).stream().filter(o -> o.get("type").getAsString().equals("shelter_start")).count() == 1 &&
                battleRows(defenseFile[0]).stream().filter(o -> o.get("type").getAsString().equals("block_place") && o.get("reason").getAsString().equals("missile_shelter")).count() == 4, 300, true));
        Bot[] covered = {null}, coverLauncher = {null}; Entity[] coverMissile = {null}; boolean[] seek = {false};
        list.add(new Scenario("warfare 4.22 missile prefers reachable existing roof and wall over creating blocks", () -> {
            warfareFlatArena(30300, -20, 30460, 20, y); run("bot settings setgoal none");
            BlockState wall = ((net.minecraft.world.item.BlockItem) warfareItem("steel_block", 1).getItem()).getBlock().defaultBlockState();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int h = 0; h <= 2; h++)
                if (h == 2 || Math.abs(dx) == 2 || Math.abs(dz) == 2) setBlock(30404 + dx, y + h, dz, wall);
            setBlock(30404, y, -2, Blocks.AIR.defaultBlockState()); setBlock(30404, y + 1, -2, Blocks.AIR.defaultBlockState());
            covered[0] = missileBot("NaturalCover", 10, 30400.5, y, 0.5); covered[0].giveItem(warfareItem("steel_block", 64)); covered[0].setInvulnerable(true);
            coverLauncher[0] = infantry("CoverLauncher", 7, 30310.5, y, 0.5); coverLauncher[0].profileAbilities().put("guns", false);
            track(() -> { if (covered[0].getAliveTicks() == 80) coverMissile[0] = fireJavelin(coverLauncher[0], covered[0], true); seek[0] |= war.describe(covered[0]).contains("SEEK_SHELTER"); });
        }, () -> covered[0].getAliveTicks() > 180 && coverMissile[0].isRemoved() && seek[0] && skills.missiles().placed(covered[0]) == 0 && covered[0].countItem(warfareItem("steel_block", 1).getItem()) == 64, 260, true));
        Bot[] deadOwner = {null}; Entity[] retained = {null}; long[] diedAt = {-1};
        list.add(new Scenario("warfare 4.22 operator death releases remote controls while the native drone remains", () -> {
            warfareFlatArena(30500, -15, 30600, 15, y); run("bot settings setgoal nearesthostile"); deadOwner[0] = ordnanceBot("DeadOperator", 10, 30510.5, y, 0.5);
            giveDrone(deadOwner[0], "grenade_40mm", 4); spawnHusk(30554.5, y, 0.5, 5000, false);
            track(() -> {
                Entity d = tactics.drone(deadOwner[0]);
                if (d != null && retained[0] == null) { retained[0] = d; spawned.add(d); deadOwner[0].invulnerableTime = 0; deadOwner[0].hurt(level.damageSources().genericKill(), 10000); diedAt[0] = server.getTickCount(); }
            });
        }, () -> diedAt[0] >= 0 && server.getTickCount() - diedAt[0] > 60 && !deadOwner[0].isAlive() && retained[0].isAlive() && !retained[0].isRemoved() && tactics.drone(deadOwner[0]) == null, 240, true));
''' +s[idx:]
p.write_text(s,encoding='utf-8',newline='\n')
print('cooldown, existing cover, retained drone, disk failure, and 100-bot snapshot checks added')
